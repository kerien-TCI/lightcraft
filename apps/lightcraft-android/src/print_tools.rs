//! Mobile entry point to the desktop print compositor. Only selected catalogue photos and an
//! app-owned staging filename cross JNI; Android chooses the final destination separately.
use std::collections::HashSet;

use lightcraft_catalog::PhotoId;
use lightcraft_engine::export::write_file_new;
use lightcraft_engine::print::layout::{Margins, Orientation, Style};
use lightcraft_engine::print::{compose, output, template::PrintSettings};
use serde_json::{Value, json};

use super::{Lab, Reply};

const MAX_PHOTOS: usize = 80;
const MAX_PAGE_PIXELS: u64 = 18_000_000;
const MAX_JOB_PIXELS: u64 = 24_000_000;
const MAX_PAGES: usize = 16;
const MAX_PDF_BYTES: usize = 96 * 1024 * 1024;

fn valid_ticket(file: &str) -> bool {
    file.strip_prefix("Print-").and_then(|s| s.strip_suffix(".pdf")).is_some_and(|uuid| {
        uuid.len() == 36 && uuid.bytes().enumerate().all(|(i, b)| if matches!(i, 8 | 13 | 18 | 23) { b == b'-' } else { b.is_ascii_hexdigit() })
    })
}

fn choice<'a>(args: &'a Value, key: &str, allowed: &[&str]) -> Reply<&'a str> {
    args[key].as_str().filter(|v| allowed.contains(v)).ok_or("print_page")
}

fn integer(args: &Value, key: &str, min: u64, max: u64) -> Reply<u64> {
    args[key].as_u64().filter(|v| (min..=max).contains(v)).ok_or("print_page")
}

fn switch(args: &Value, key: &str) -> Reply<bool> {
    match &args[key] {
        Value::Null => Ok(false),
        Value::Bool(v) => Ok(*v),
        _ => Err("print_page"),
    }
}

fn settings(args: &Value) -> Reply<PrintSettings> {
    let mut s = PrintSettings::default();
    s.layout.style = match choice(args, "layout", &["single", "contact"])? {
        "single" => Style::SingleImage,
        _ => Style::ContactSheet,
    };
    s.layout.page.paper = choice(args, "paper", &["a3", "a4", "a5", "letter", "4x6", "5x7"])?.into();
    s.layout.page.orientation = match choice(args, "orientation", &["portrait", "landscape"])? {
        "landscape" => Orientation::Landscape,
        _ => Orientation::Portrait,
    };
    s.layout.page.margins = Margins::uniform(integer(args, "marginMm", 0, 25)? as f64 / 25.4);
    if s.layout.style == Style::ContactSheet {
        s.layout.grid.columns = integer(args, "columns", 1, 6)? as usize;
        s.layout.grid.rows = integer(args, "rows", 1, 8)? as usize;
        let gap = integer(args, "gapMm", 0, 10)? as f64 / 25.4;
        s.layout.grid.spacing_x = gap;
        s.layout.grid.spacing_y = gap;
        s.layout.grid.keep_square = switch(args, "square")?;
    }
    s.image.zoom_to_fill = switch(args, "fill")?;
    s.image.rotate_to_fit = switch(args, "rotate")?;
    s.page.page_numbers = switch(args, "pageNumbers")?;
    s.page.crop_marks = switch(args, "cropMarks")?;
    s.job.ppi = integer(args, "ppi", 96, 300)? as f32;
    // Printer-managed sRGB output, preserving the same ICC-tagged compositor as desktop. Native PDF
    // preview/importers are most compatible with 8-bit embedded images; no printer ICC paths here.
    s.job.color.sixteen_bit = false;
    s.validate().map_err(|_| "print_page")?;
    Ok(s)
}

fn preflight(s: &PrintSettings, count: usize) -> Reply<()> {
    let (w, h) = s.layout.page_size().map_err(|_| "print_page")?;
    let ppi = f64::from(s.job.effective_ppi());
    let pixels = ((w * ppi).round() as u64).saturating_mul((h * ppi).round() as u64);
    let pages = s.layout.page_count(count);
    if pixels == 0 || pixels > MAX_PAGE_PIXELS || pages == 0 || pages > MAX_PAGES || pixels.saturating_mul(pages as u64) > MAX_JOB_PIXELS {
        return Err("print_limit");
    }
    Ok(())
}

impl Lab {
    pub(super) fn mobile_print(&mut self, p: &Value) -> Reply<Value> {
        let selected = p["ids"].as_array().filter(|v| !v.is_empty() && v.len() <= MAX_PHOTOS).ok_or("selection")?;
        let mut seen = HashSet::new();
        let ids = selected.iter().map(|id| self.valid_photo(&json!({"id":id}))).collect::<Reply<Vec<PhotoId>>>()?;
        if ids.iter().any(|id| !seen.insert(*id)) {
            return Err("selection");
        }
        let file = p["file"].as_str().filter(|s| valid_ticket(s)).ok_or("print_pdf")?;
        let s = settings(p)?;
        preflight(&s, ids.len())?;
        let stage = self.root.join("PrintQueue");
        std::fs::create_dir_all(&stage).map_err(|_| "print_pdf")?;
        if stage.canonicalize().ok().as_deref() != Some(stage.as_path()) {
            return Err("print_pdf");
        }
        let path = stage.join(file);
        if path.exists() {
            return Err("print_pdf");
        }
        let pages = compose::prepare(&mut self.session, &s, &ids).and_then(|job| job.run(&mut |_, _| true)).map_err(|_| "print_pdf")?;
        let pdf = output::write(&pages, &s.job, "Photo Lab print").map_err(|_| "print_pdf")?;
        if pdf.len() > MAX_PDF_BYTES {
            return Err("print_limit");
        }
        write_file_new(path.to_str().ok_or("print_pdf")?, &pdf).map_err(|_| "print_pdf")?;
        Ok(json!({"pages":pages.len(),"bytes":pdf.len()}))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    const TICKET: &str = "Print-c097c7e3-4b98-4d39-b04c-cd0f55dea7b0.pdf";

    fn payload(ids: &[u64], file: &str) -> Value {
        json!({
            "ids": ids, "file": file, "layout":"contact", "paper":"a5", "orientation":"landscape",
            "marginMm":10,"gapMm":5,"columns":2,"rows":1,"ppi":96,"pageNumbers":true
        })
    }

    #[test]
    fn print_is_scoped_and_composes_a_contact_sheet_without_touching_originals() {
        let tmp = tempfile::tempdir().unwrap();
        let base = tmp.path().join("import");
        std::fs::create_dir(&base).unwrap();
        let photos: Vec<_> = [[208, 42, 31, 255], [27, 70, 195, 255]]
            .into_iter()
            .enumerate()
            .map(|(i, color)| {
                let b = encode_png(&EncodeImage::rgba8(&Rgba8::from_fn(96, 64, |_, _| color)), &EncodeMeta::default()).unwrap();
                let f = base.join(format!("print-test-{i}.png"));
                std::fs::write(&f, &b).unwrap();
                (f, b)
            })
            .collect();
        let mut lab = Lab::start(tmp.path()).unwrap();
        lab.import(&json!({"paths":photos.iter().map(|(p, _)|p).collect::<Vec<_>>()})).unwrap();
        let ids: Vec<_> = lab.session.catalog.photos().map(|p| p.id.0).collect();
        let call = payload(&ids, TICKET);
        assert!(lab.mobile_print(&payload(&ids, "../bad.pdf")).is_err());
        assert!(lab.mobile_print(&payload(&[ids[0], ids[0]], TICKET)).is_err());
        let reply = lab.mobile_print(&call).unwrap();
        assert_eq!(reply["pages"], 1);
        let path = tmp.path().join("PrintQueue").join(TICKET);
        let pdf = std::fs::read(&path).unwrap();
        assert!(pdf.starts_with(b"%PDF-"));
        assert!(pdf.windows(15).any(|part| part == b"/Subtype /Image"));
        assert!(lab.mobile_print(&call).is_err(), "even private staging must not overwrite an existing file");
        assert_eq!(std::fs::read(path).unwrap(), pdf);

        let mut single = call.clone();
        single["layout"] = json!("single");
        single["file"] = json!("Print-eac68a29-062a-44c6-84c0-0b228b53edda.pdf");
        assert_eq!(lab.mobile_print(&single).unwrap()["pages"], 2);
        let mut large = settings(&json!({
            "layout":"single","paper":"a3","orientation":"portrait","marginMm":10,"ppi":300
        }))
        .unwrap();
        assert!(preflight(&large, 2).is_err());
        large.job.ppi = 120.0;
        assert!(preflight(&large, 2).is_ok());
        for (path, original) in photos {
            assert_eq!(std::fs::read(path).unwrap(), original);
        }
    }
}
