//! Byte-for-byte originals plus desktop XMP, staged only in an app-owned queue.
//! Choosing the final folder and creating both siblings is the Android client's job.
use lightcraft_catalog::Source;
use lightcraft_engine::export::{ExportFormat, ExportOptions, export_photo, write_file_new};
use serde_json::{Value, json};
use std::path::Path;

use super::{Lab, Reply, bytes_json};

const MAX_BYTES: u64 = 512 << 20;
const MAX_XMP: usize = 8 << 20;

/// Java sends only a ticket, never a path. Each ticket names exactly two temporary files.
fn valid_ticket(ticket: &str) -> bool {
    ticket.strip_prefix("Batch-").is_some_and(|id| {
        id.len() == 36 && id.bytes().enumerate().all(|(i, b)| if matches!(i, 8 | 13 | 18 | 23) { b == b'-' } else { b.is_ascii_hexdigit() })
    })
}

pub(super) fn original_pair(lab: &mut Lab, args: &Value) -> Reply<Vec<u8>> {
    let id = lab.valid_photo(args)?;
    let ticket = args["ticket"].as_str().filter(|t| valid_ticket(t)).ok_or("batch_stage")?;
    let photo = lab.session.catalog.photo(id).ok_or("selection")?;
    let Source::File { path } = &photo.source else { return Err("batch_original") };
    let source = Path::new(path).canonicalize().map_err(|_| "batch_original")?;
    // Only the photo's existing app-owned copy is allowed, not a caller-selected path.
    if source == lab.root || !source.starts_with(&lab.root) {
        return Err("batch_original");
    }
    let budget = args["budgetMiB"].as_u64().unwrap_or(192).clamp(64, 512) << 20;
    let limit = budget.min(MAX_BYTES);
    let source_stat = source.metadata().map_err(|_| "batch_original")?;
    if !source_stat.is_file() || source_stat.len() == 0 || source_stat.len() > limit {
        return Err("export_size");
    }
    let name = photo.file_name.clone();
    let extension = Path::new(&name)
        .extension()
        .and_then(|s| s.to_str())
        .filter(|s| (1..=12).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_alphanumeric()) && !s.eq_ignore_ascii_case("xmp"))
        .ok_or("batch_original")?
        .to_owned();

    let folder = lab.root.join("ExportQueue");
    std::fs::create_dir_all(&folder).map_err(|_| "batch_stage")?;
    if !folder.symlink_metadata().is_ok_and(|m| m.is_dir()) {
        return Err("batch_stage");
    }
    let original = folder.join(format!("{ticket}.source"));
    let sidecar = folder.join(format!("{ticket}.xmp"));
    if original.symlink_metadata().is_ok() || sidecar.symlink_metadata().is_ok() {
        return Err("batch_stage");
    }

    // Reuse the desktop verification before copying originals: location cannot be stripped from
    // immutable bytes. It also filters location data from the sidecar packet.
    let opts = ExportOptions { format: ExportFormat::Original, remove_location: true, ..Default::default() };
    let exported = export_photo(&mut lab.session, id, &opts, 1)
        .map_err(|e| if e.contains("location metadata") || e.contains("can't remove the location") { "batch_location" } else { "batch_original" })?;
    if exported.bytes.is_empty() || exported.bytes.len() as u64 > limit {
        return Err("export_size");
    }
    let [("xmp", xmp)] = exported.sidecars.as_slice() else { return Err("batch_stage") };
    if xmp.is_empty() || xmp.len() > MAX_XMP {
        return Err("batch_stage");
    }

    write_file_new(original.to_str().ok_or("batch_stage")?, &exported.bytes).map_err(|_| "batch_stage")?;
    if write_file_new(sidecar.to_str().ok_or("batch_stage")?, xmp).is_err() {
        let _ = std::fs::remove_file(&original);
        return Err("batch_stage");
    }
    Ok(bytes_json(json!({"extension":extension,"name":name})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    fn import(lab: &mut Lab, name: &str, embed_location: bool) -> (u64, Vec<u8>) {
        let src = lab.root.join("import").join(name);
        std::fs::create_dir_all(src.parent().unwrap()).unwrap();
        let raster = Rgba8::from_fn(16, 12, |x, y| [2 * x as u8, 120, y as u8, 255]);
        let xmp = embed_location.then_some(r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" photoshop:City="Private City"></rdf:Description></rdf:RDF></x:xmpmeta>"#);
        let bytes = encode_png(&EncodeImage::rgba8(&raster), &EncodeMeta { xmp, ..Default::default() }).unwrap();
        std::fs::write(&src, &bytes).unwrap();
        assert_eq!(lab.import(&json!({"paths":[src]})).unwrap()["imported"], 1);
        (lab.session.catalog.photos().find(|p| p.file_name == name).unwrap().id.0, bytes)
    }

    #[test]
    fn original_siblings_preserve_source_and_edits_but_strip_catalogue_location() {
        let dir = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(dir.path()).unwrap();
        let (id, source) = import(&mut lab, "lake.png", false);
        lab.action("adjust_many", &json!({"id":id,"values":{"light.exposure":1.0}})).unwrap();
        lab.session.execute("photo.setMeta", &json!({"ids":[id],"city":"NotForExport","gps":[48.0,2.0]})).unwrap();
        let ticket = "Batch-00000000-0000-4000-8000-000000000000";
        let request = json!({"id":id,"ticket":ticket});
        let result: Value = serde_json::from_slice(&original_pair(&mut lab, &request).unwrap()).unwrap();
        assert_eq!(result["name"], "lake.png");
        assert_eq!(result["extension"], "png");
        let queue = dir.path().join("ExportQueue");
        assert_eq!(std::fs::read(queue.join(format!("{ticket}.source"))).unwrap(), source);
        let xmp = std::fs::read_to_string(queue.join(format!("{ticket}.xmp"))).unwrap();
        let (_, settings) = xmp.split_once("<lc:settings>").unwrap();
        let (settings, _) = settings.split_once("</lc:settings>").unwrap();
        let settings: Value = serde_json::from_str(settings).unwrap();
        assert_eq!(settings["light"]["exposure"], 1.0);
        assert!(!xmp.contains("NotForExport") && !xmp.contains("GPSLatitude"));
        assert_eq!(original_pair(&mut lab, &request), Err("batch_stage"), "tickets are create-new");
        assert_eq!(std::fs::read(dir.path().join("import/lake.png")).unwrap(), source);
    }

    #[test]
    fn blocks_geotagged_originals_and_invalid_requests_before_creating_output_files() {
        let dir = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(dir.path()).unwrap();
        let (id, _) = import(&mut lab, "has-location.png", true);
        let ticket = "Batch-10000000-0000-4000-8000-000000000000";
        assert_eq!(original_pair(&mut lab, &json!({"id":id,"ticket":ticket})), Err("batch_location"));
        assert_eq!(original_pair(&mut lab, &json!({"id":id,"ticket":"../outside"})), Err("batch_stage"));
        assert_eq!(std::fs::read_dir(dir.path().join("ExportQueue")).unwrap().count(), 0);
    }
}
