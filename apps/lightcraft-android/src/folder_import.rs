//! Folder copies from the Android document picker. The native engine sees only app-private files.
use std::collections::HashSet;
use std::fs;
use std::path::{Component, Path, PathBuf};

use serde_json::{Value, json};

use super::{Lab, Reply, bytes_json};

const INBOX: &str = "FolderInbox";
const MAX_FILES: usize = 150;
const MAX_SOURCE: u64 = 512 << 20;
const MAX_BATCH: u64 = 2 << 30;
const MAX_SIDECAR: u64 = 8 << 20;

fn uuid(s: &str) -> bool {
    s.len() == 36 && s.bytes().enumerate().all(|(i, b)| if [8, 13, 18, 23].contains(&i) { b == b'-' } else { b.is_ascii_hexdigit() })
}

fn name_template(value: &Value) -> Reply<Option<&str>> {
    if value.is_null() {
        return Ok(None);
    }
    let template = value.as_str().ok_or("folder_template")?.trim();
    if template.is_empty() {
        return Ok(None);
    }
    if template.len() > 160
        || template.starts_with('.')
        || template.contains(['/', '\\'])
        || template.chars().any(char::is_control)
        || !lightcraft_engine::rename::unknown_tokens(template).is_empty()
    {
        return Err("folder_template");
    }
    // The desktop keeps malformed braces literally. Reject them rather than creating unexpected names.
    let mut open = false;
    let mut has_token_text = false;
    for c in template.chars() {
        match c {
            '{' if !open => {
                open = true;
                has_token_text = false;
            }
            '}' if open && has_token_text => open = false,
            '{' | '}' => return Err("folder_template"),
            _ if open && !c.is_whitespace() => has_token_text = true,
            _ => {}
        }
    }
    if open {
        return Err("folder_template");
    }
    Ok(Some(template))
}

fn photo_paths(root: &Path, p: &Value) -> Reply<Vec<PathBuf>> {
    let token = p["token"].as_str().filter(|s| uuid(s)).ok_or("folder_import")?;
    let photos = p["photos"].as_array().filter(|paths| !paths.is_empty() && paths.len() <= MAX_FILES).ok_or("folder_import")?;
    let inbox = root.join(INBOX).join(token);
    if inbox.canonicalize().ok().as_ref() != Some(&inbox) {
        return Err("folder_import");
    }
    let mut seen = HashSet::with_capacity(photos.len());
    let mut seen_dirs = HashSet::with_capacity(photos.len());
    let mut out = Vec::with_capacity(photos.len());
    let mut total = 0_u64;
    for photo in photos {
        let relative = photo.as_str().filter(|s| s.len() <= 380 && !s.contains('\\') && !s.chars().any(char::is_control)).ok_or("folder_import")?;
        let segments: Vec<_> = relative.split('/').collect();
        if segments.len() != 3
            || !segments[0].starts_with('g')
            || segments[0].len() < 2
            || segments[0].len() > 10
            || !segments[0][1..].bytes().all(|b| b.is_ascii_digit())
            || segments.iter().any(|&part| !matches!(Path::new(part).components().next(), Some(Component::Normal(n)) if n == part))
            || !seen.insert(relative)
        {
            return Err("folder_import");
        }
        let path = inbox.join(relative);
        if !lightcraft_engine::import::is_supported(&path)
            || path.canonicalize().ok().as_ref() != Some(&path)
            || !fs::symlink_metadata(&path).is_ok_and(|m| m.is_file() && (1..=MAX_SOURCE).contains(&m.len()))
        {
            return Err("folder_import");
        }
        total = total.checked_add(fs::metadata(&path).map_err(|_| "folder_import")?.len()).ok_or("folder_import")?;
        let dir = path.parent().ok_or("folder_import")?;
        if seen_dirs.insert(dir.to_path_buf()) {
            // Engine discovers sibling XMP itself, so validate them just as carefully as files
            // submitted explicitly. The SAF host writes only adjacent regular files.
            for file in fs::read_dir(dir).map_err(|_| "folder_import")? {
                let entry = file.map_err(|_| "folder_import")?;
                let sidecar = entry.path();
                if sidecar.extension().is_none_or(|ext| !ext.eq_ignore_ascii_case("xmp")) {
                    continue;
                }
                let meta = fs::symlink_metadata(&sidecar).map_err(|_| "folder_import")?;
                if !meta.is_file() || !(1..=MAX_SIDECAR).contains(&meta.len()) {
                    return Err("folder_import");
                }
                total = total.checked_add(meta.len()).ok_or("folder_import")?;
            }
        }
        if total > MAX_BATCH {
            return Err("folder_import");
        }
        out.push(path);
    }
    Ok(out)
}

impl Lab {
    pub(super) fn folder_import_action(&mut self, op: &str, p: &Value) -> Reply<Vec<u8>> {
        match op {
            "folder.options" => Ok(bytes_json(json!({"extensions":lightcraft_engine::import::EXTENSIONS, "maximum":MAX_FILES}))),
            "folder.validate" => {
                name_template(&p["template"])?;
                Ok(bytes_json(json!({"ok":true})))
            }
            "folder.import" => {
                let rename = name_template(&p["template"])?;
                let start = p["sequence"].as_u64().filter(|n| (1..=999_999_999).contains(n)).ok_or("folder_template")?;
                let organize = p["organize"].as_str().filter(|s| matches!(*s, "date" | "month" | "flat")).ok_or("folder_template")?;
                let paths = photo_paths(&self.root, p)?;
                // No arbitrary paths or import modes cross this boundary. The temporary inbox
                // remains app-private, and the engine's default copy destination is Library/Originals.
                let request = json!({
                    "paths":paths, "mode":"copy", "organize":organize, "rename":rename,
                    "renameStart":start, "onDeleted":"skip",
                });
                let report = self.session.execute("library.import", &request).map_err(|_| "folder_import")?;
                self.session.persist().map_err(|_| "folder_import")?;
                if self.session.unsaved().is_some() {
                    return Err("folder_import");
                }
                let count = |key: &str| report[key].as_array().map_or(0, Vec::len);
                Ok(bytes_json(json!({
                    "imported":count("imported"),
                    "duplicates":count("duplicates"),
                    "failed":count("failed"),
                    "warnings":count("warnings"),
                })))
            }
            _ => Err("unavailable"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_catalog::Source;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    const TOKEN: &str = "11111111-1111-4111-8111-111111111111";

    fn make_png(path: &Path, red: u8) -> Vec<u8> {
        let image = Rgba8::from_fn(19, 13, |x, y| [red, 56 + (x * 4) as u8, 86 + (y * 6) as u8, 255]);
        let bytes = encode_png(&EncodeImage::rgba8(&image), &EncodeMeta::default()).unwrap();
        fs::write(path, &bytes).unwrap();
        bytes
    }

    fn reply(lab: &mut Lab, verb: &str, p: Value) -> Value {
        serde_json::from_slice(&lab.folder_import_action(verb, &p).unwrap()).unwrap()
    }

    #[test]
    fn folder_copy_uses_desktop_naming_xmp_and_content_dedup() {
        let root = tempfile::tempdir().unwrap();
        let folder = root.path().join(INBOX).join(TOKEN).join("g1/DCIM");
        fs::create_dir_all(&folder).unwrap();
        let external = root.path().join("simulated-card");
        fs::create_dir_all(&external).unwrap();
        let original = make_png(&external.join("FIRST.png"), 25);
        fs::copy(external.join("FIRST.png"), folder.join("FIRST.png")).unwrap();
        fs::copy(external.join("FIRST.png"), folder.join("DUP.png")).unwrap();
        let second = make_png(&folder.join("SECOND.png"), 139);
        let xmp = r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+0.55"/>
</rdf:RDF></x:xmpmeta>"#;
        fs::write(folder.join("FIRST.png.xmp"), xmp).unwrap();
        let mut lab = Lab::start(root.path()).unwrap();
        let request = json!({"token":TOKEN,"photos":["g1/DCIM/FIRST.png","g1/DCIM/DUP.png","g1/DCIM/SECOND.png"],"template":"{folder}-{seq:3}","sequence":7,"organize":"flat"});
        let report = reply(&mut lab, "folder.import", request.clone());
        assert_eq!(report["imported"], 2);
        assert_eq!(report["duplicates"], 1);
        assert_eq!(report["failed"], 0);
        let first = lab.session.catalog.photos().find(|p| p.file_name == "DCIM-007.png").unwrap();
        let second_photo = lab.session.catalog.photos().find(|p| p.file_name == "DCIM-008.png").unwrap();
        let Source::File { path } = &first.source else { panic!("copy should be a file") };
        assert!(Path::new(path).starts_with(lab.root.join("Library").join("Originals")));
        assert_eq!(fs::read(path).unwrap(), original);
        let Source::File { path } = &second_photo.source else { panic!("copy should be a file") };
        assert_eq!(fs::read(path).unwrap(), second);
        assert!((first.develop.light.exposure - 0.55).abs() < 0.0001);
        assert_eq!(fs::read(external.join("FIRST.png")).unwrap(), original);
        assert_eq!(fs::read(folder.join("FIRST.png")).unwrap(), original);
        assert_eq!(fs::read_to_string(folder.join("FIRST.png.xmp")).unwrap(), xmp);
        let again = reply(&mut lab, "folder.import", request);
        assert_eq!(again["imported"], 0);
        assert_eq!(again["duplicates"], 3);
        assert_eq!(lab.session.catalog.photos().count(), 2);
    }

    #[test]
    fn rejects_bad_filenames_bounds_traversal_and_linked_inbox_assets() {
        let root = tempfile::tempdir().unwrap();
        let folder = root.path().join(INBOX).join(TOKEN).join("g1/DCIM");
        fs::create_dir_all(&folder).unwrap();
        let photo = folder.join("Photo.png");
        make_png(&photo, 30);
        let mut lab = Lab::start(root.path()).unwrap();
        for template in ["../{seq}", "a/b", "a\\b", "{seq", "{{seq}}", "blank {}", "{unknown}", ".private"] {
            assert_eq!(lab.folder_import_action("folder.validate", &json!({"template":template})), Err("folder_template"));
        }
        assert_eq!(reply(&mut lab, "folder.validate", json!({"template":"Événement-{date:%Y%m%d}-{seq:4}"}))["ok"], true);
        let args =
            |photos: Value| json!({"token":TOKEN,"photos":photos,"template":"","sequence":1,"organize":"date","mode":"move","destination":"ignored"});
        for paths in [json!(["../DCIM/Photo.png"]), json!(["/g1/DCIM/Photo.png"]), json!(["g1/DCIM/Photo.png", "g1/DCIM/Photo.png"])] {
            assert_eq!(lab.folder_import_action("folder.import", &args(paths)), Err("folder_import"));
        }
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&photo, folder.join("Link.png")).unwrap();
            assert_eq!(lab.folder_import_action("folder.import", &args(json!(["g1/DCIM/Link.png"]))), Err("folder_import"));
            fs::remove_file(folder.join("Link.png")).unwrap();
            std::os::unix::fs::symlink(&photo, folder.join("Photo.xmp")).unwrap();
            assert_eq!(lab.folder_import_action("folder.import", &args(json!(["g1/DCIM/Photo.png"]))), Err("folder_import"));
            fs::remove_file(folder.join("Photo.xmp")).unwrap();
        }
        let imported = reply(&mut lab, "folder.import", args(json!(["g1/DCIM/Photo.png"])));
        assert_eq!(imported["imported"], 1);
        assert!(photo.is_file(), "the caller cannot force an import move");
    }
}
