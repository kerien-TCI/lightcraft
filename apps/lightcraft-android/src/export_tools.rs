//! Rendered export options accepted from the native Android form. Only a new private queue
//! file can be written here; the Activity sends it to a newly created SAF document.

use lightcraft_engine::export::{
    ExportFormat, ExportOptions, MetadataPolicy, OutputSpace, Resize, ResizeMode, SharpenFor, Watermark, export_photo, output_size, write_file_new,
};
use serde_json::{Value, json};

use super::{Lab, Reply, bytes_json};

const MAX_ENCODED_BYTES: usize = 512 << 20;

fn integer(params: &Value, key: &str, default: u32, min: u32, max: u32) -> Reply<u32> {
    match params.get(key) {
        None => Ok(default),
        Some(n) => n.as_u64().filter(|v| (u64::from(min)..=u64::from(max)).contains(v)).map(|v| v as u32).ok_or("export_options"),
    }
}

fn string<'a>(params: &'a Value, key: &str, default: &'a str) -> Reply<&'a str> {
    params.get(key).map_or(Ok(default), |s| s.as_str().ok_or("export_options"))
}

fn boolean(params: &Value, key: &str, default: bool) -> Reply<bool> {
    params.get(key).map_or(Ok(default), |b| b.as_bool().ok_or("export_options"))
}

fn options(p: &Value) -> Reply<ExportOptions> {
    use ExportFormat::*;
    let format = match string(p, "format", "jpeg")? {
        "jpeg" => Jpeg,
        "png" => Png,
        "tiff" => Tiff,
        "psd" => Psd,
        "webp" => Webp,
        "avif" => Avif,
        "jxl" => Jxl,
        "renderedDng" => RenderedDng,
        _ => return Err("export_options"),
    };
    let (mode, min, max) = match string(p, "sizeMode", "long")? {
        "full" => (None, 128, 12000),
        "long" => (Some(ResizeMode::LongEdge), 128, 12000),
        "short" => (Some(ResizeMode::ShortEdge), 128, 12000),
        "width" => (Some(ResizeMode::Width), 128, 12000),
        "height" => (Some(ResizeMode::Height), 128, 12000),
        "fit" => (Some(ResizeMode::WidthHeight), 128, 12000),
        "percent" => (Some(ResizeMode::Percent), 1, 100),
        "megapixels" => (Some(ResizeMode::Megapixels), 1, 64),
        _ => return Err("export_options"),
    };
    let resize = if let Some(mode) = mode {
        let default = match mode {
            ResizeMode::Percent => 100,
            ResizeMode::Megapixels => 12,
            _ => 2048,
        };
        Some(Resize {
            mode,
            value: integer(p, "edge", default, min, max)? as f32,
            height: integer(p, "fitHeight", 2048, 128, 12000)?,
            dont_enlarge: true,
        })
    } else {
        None
    };
    let bit_depth = integer(p, "bitDepth", 0, 0, 32)? as u8;
    if !matches!((format, bit_depth), (_, 0 | 8) | (Avif, 10) | (Png | Tiff | Psd | Jxl, 16) | (Tiff, 32)) {
        return Err("export_options");
    }
    let color_space = OutputSpace::parse(string(p, "colorSpace", "srgb")?).ok_or("export_options")?;
    let metadata = MetadataPolicy::parse(string(p, "metadata", "none")?).ok_or("export_options")?;
    let sharpen = SharpenFor::parse(string(p, "sharpen", "none")?).ok_or("export_options")?;
    let watermark = string(p, "watermark", "")?.trim();
    if watermark.chars().count() > 120 || watermark.chars().any(char::is_control) {
        return Err("export_options");
    }

    let limit = integer(p, "limitKb", 0, 0, 50_000)?;
    let hdr = boolean(p, "hdr", false)?;
    if hdr && (!matches!(format, Jpeg | Png | Tiff | Avif | Jxl) || (format == Tiff && bit_depth != 32)) {
        return Err("export_options");
    }
    Ok(ExportOptions {
        format,
        resize,
        quality: integer(p, "quality", 90, 1, 100)? as u8,
        ppi: integer(p, "ppi", 240, 1, 1200)? as u16,
        limit_kb: (format == Jpeg && limit > 0).then_some(limit),
        bit_depth: (bit_depth > 0).then_some(bit_depth),
        color_space,
        metadata,
        remove_location: true,
        sharpen,
        watermark: (!watermark.is_empty()).then(|| Watermark { text: watermark.to_owned(), ..Default::default() }),
        hdr,
        jxl_lossless: format == Jxl && boolean(p, "lossless", false)?,
        ..Default::default()
    })
}

fn ticket(s: &str, extension: &str) -> bool {
    let Some(id) = s.strip_prefix("Export-").and_then(|s| s.strip_suffix(extension)).and_then(|s| s.strip_suffix('.')) else {
        return false;
    };
    id.len() == 36 && id.bytes().enumerate().all(|(i, b)| if matches!(i, 8 | 13 | 18 | 23) { b == b'-' } else { b.is_ascii_hexdigit() })
}

pub(super) fn run(lab: &mut Lab, p: &Value) -> Reply<Vec<u8>> {
    let id = lab.valid_photo(p)?;
    let options = options(p)?;
    let file = p["file"].as_str().filter(|s| ticket(s, options.format.extension())).ok_or("export")?;
    let photo = lab.session.catalog.photo(id).ok_or("selection")?;
    let (w, h) = output_size(photo, &options);
    let pixels = w.saturating_mul(h);
    // Hold more headroom for wide/floating point output and HDR gain maps. Do not ask the
    // allocator to render huge data on low-memory devices; a smaller explicit size is offered.
    let budget = integer(p, "budgetMiB", 768, 128, 2048)? as usize * (1 << 20);
    let bytes_per_pixel = if options.hdr || options.effective_depth() != lightcraft_engine::export::OutputDepth::U8 { 66 } else { 48 };
    if w.max(h) > 16_000 || pixels > 64_000_000 || pixels.saturating_mul(bytes_per_pixel) > budget {
        return Err("export_size");
    }
    let folder = lab.root.join("ExportQueue");
    std::fs::create_dir_all(&folder).map_err(|_| "export")?;
    if !folder.symlink_metadata().is_ok_and(|m| m.is_dir()) {
        return Err("export");
    }
    let output = folder.join(file);
    if output.symlink_metadata().is_ok() {
        return Err("export");
    }
    let outcome = export_photo(&mut lab.session, id, &options, 1).map_err(|why| {
        if why.contains("High Efficiency") || why.contains("HE★") {
            "nef_he"
        } else if why.contains("cannot fit within") {
            "export_limit"
        } else {
            "export"
        }
    })?;
    if !outcome.sidecars.is_empty() || outcome.bytes.is_empty() || outcome.bytes.len() > MAX_ENCODED_BYTES {
        return Err("export_size");
    }
    write_file_new(output.to_str().ok_or("export")?, &outcome.bytes).map_err(|_| "export")?;
    Ok(bytes_json(json!({"ready":true,"width":outcome.width,"height":outcome.height})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{DecodeOptions, EncodeImage, EncodeMeta, Format as DecodedFormat, decode, encode_png, sniff};
    use lightcraft_raster::Rgba8;

    #[test]
    fn rendered_formats_use_the_desktop_encoders_and_keep_the_imported_original() {
        let dir = tempfile::tempdir().unwrap();
        let source = dir.path().join("import").join("one").join("source.png");
        std::fs::create_dir_all(source.parent().unwrap()).unwrap();
        let raster = Rgba8::from_fn(256, 160, |x, y| [x as u8, y as u8, (x ^ y) as u8, 255]);
        let original = encode_png(&EncodeImage::rgba8(&raster), &EncodeMeta::default()).unwrap();
        std::fs::write(&source, &original).unwrap();
        let mut lab = Lab::start(dir.path()).unwrap();
        assert_eq!(lab.import(&json!({"paths":[source]})).unwrap()["imported"], 1);
        let id = lab.session.catalog.photos().next().unwrap().id.0;
        lab.session
            .execute(
                "photo.setMeta",
                &json!({
                    "ids":[id], "creator":"Visible author", "city":"LocationNotForExport", "gps":[47.0,2.0]
                }),
            )
            .unwrap();
        for (kind, ext, depth, decoded_format) in [
            ("jpeg", "jpg", 8, DecodedFormat::Jpeg),
            ("png", "png", 16, DecodedFormat::Png),
            ("tiff", "tif", 32, DecodedFormat::Tiff),
            ("psd", "psd", 16, DecodedFormat::Psd),
            ("webp", "webp", 8, DecodedFormat::WebP),
            ("avif", "avif", 8, DecodedFormat::Avif),
            ("jxl", "jxl", 8, DecodedFormat::Jxl),
            ("renderedDng", "dng", 0, DecodedFormat::RawTiffLike),
        ] {
            let file = format!("Export-00000000-0000-4000-8000-000000000000.{ext}");
            let input = json!({"id":id,"file":file,"format":kind,"bitDepth":depth,"sizeMode":"long","edge":128,"metadata":if kind == "jpeg" {"all"} else {"none"}});
            let reply: Value = serde_json::from_slice(&run(&mut lab, &input).unwrap_or_else(|why| panic!("export {kind}: {why}"))).unwrap();
            assert_eq!([reply["width"].as_u64(), reply["height"].as_u64()], [Some(128), Some(80)], "{kind}");
            let written = std::fs::read(dir.path().join("ExportQueue").join(&file)).unwrap();
            assert_eq!(sniff(&written), Some(decoded_format), "{kind}");
            if kind != "renderedDng" {
                let reader = decode(&written, DecodeOptions::default()).unwrap_or_else(|why| panic!("decode {kind}: {why}"));
                assert_eq!((reader.width, reader.height), (128, 80), "{kind}");
                if matches!(kind, "png" | "tiff" | "psd") {
                    assert_eq!(reader.bit_depth, depth, "{kind}");
                }
                if kind == "jpeg" {
                    let xmp = reader.xmp.unwrap();
                    assert!(xmp.contains("Visible author"));
                    assert!(!xmp.contains("LocationNotForExport") && !xmp.contains("GPSLatitude") && !xmp.contains("GPSLongitude"));
                }
            }
            assert_eq!(run(&mut lab, &input), Err("export"), "cannot overwrite the queue slot");
        }
        assert_eq!(std::fs::read(source).unwrap(), original);
    }

    #[test]
    fn export_is_private_and_disallows_invalid_or_unscoped_inputs() {
        assert!(!ticket("../Export-00000000-0000-4000-8000-000000000000.png", "png"));
        assert!(!ticket("Export-00000000-0000-4000-8000-000000000000.jpg", "png"));
        assert!(ticket("Export-00000000-0000-4000-8000-000000000000.png", "png"));
        assert!(options(&json!({"format":"original"})).is_err());
        assert!(options(&json!({"format":"jpg","hdr":true})).is_err());
        assert!(options(&json!({"format":"tiff","bitDepth":16,"hdr":true})).is_err());
        assert!(options(&json!({"format":"png","bitDepth":32})).is_err());
        assert!(options(&json!({"format":"png","edge":12001})).is_err());
        assert!(options(&json!({"sizeMode":"fit","fitHeight":127})).is_err());
        let o = options(&json!({"metadata":"all","removeLocation":false,"watermark":"Copyright 2026"})).unwrap();
        assert_eq!(o.metadata, MetadataPolicy::All);
        assert!(o.remove_location, "Android always strips location");
        assert_eq!(o.watermark.unwrap().text, "Copyright 2026");
    }
}
