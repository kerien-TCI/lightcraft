//! Portable native LightCraft libraries. Never open source paths recorded by a desktop.
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};

use lightcraft_catalog::{Catalog, Filter, FsStore, Journal, Op, Rule, RuleSet, Source};
use lightcraft_engine::Session;
use lightcraft_preview::{Hash128, Hasher128};
use serde_json::{Value, json};

use super::{Lab, Reply, bytes_json};

const LIBS: &str = "DesktopLibraries";
const MARKER: &str = "migration.json";
const ACTIVE: &str = "active";
const TOTAL_BYTES: u64 = 4 << 30;
const FILE_BYTES: u64 = 512 << 20;
const CATALOG_BYTES: u64 = 32 << 20;
const PREFS_BYTES: u64 = 4 << 20;
const MAX_ENTRIES: usize = 12_000;
const MAX_PHOTOS: usize = 20_000;
const MAX_DEPTH: usize = 16;

fn is_id(s: &str) -> bool {
    s.len() == 36
        && s.bytes().enumerate().all(|(i, b)| if [8, 13, 18, 23].contains(&i) { b == b'-' } else { b.is_ascii_hexdigit() && !b.is_ascii_uppercase() })
}

fn safe_component(s: &str) -> bool {
    !s.is_empty() && s.len() <= 240 && s != "." && s != ".." && !s.contains(['/', '\\', ':']) && !s.chars().any(char::is_control)
}

fn exact(path: &Path) -> bool {
    path.canonicalize().ok().as_deref() == Some(path)
}

fn regular(path: &Path, min: u64, max: u64) -> Reply<u64> {
    let m = fs::symlink_metadata(path).map_err(|_| "migration_incomplete")?;
    if !m.is_file() || !exact(path) || !(min..=max).contains(&m.len()) {
        return Err("migration_scope");
    }
    Ok(m.len())
}

fn bounded_json(path: &Path, limit: u64) -> Reply<Value> {
    regular(path, 1, limit)?;
    let mut bytes = Vec::new();
    File::open(path).map_err(|_| "migration_io")?.take(limit + 1).read_to_end(&mut bytes).map_err(|_| "migration_io")?;
    if bytes.len() as u64 > limit {
        return Err("migration_limit");
    }
    serde_json::from_slice(&bytes).map_err(|_| "migration_damaged")
}

fn label(s: &str) -> Reply<&str> {
    let name = s.trim();
    if name.is_empty() || name.chars().count() > 96 || name.chars().any(char::is_control) {
        return Err("migration_incomplete");
    }
    Ok(name)
}

fn written_json(path: &Path, data: &Value) -> Reply<()> {
    let bytes = serde_json::to_vec(data).map_err(|_| "migration_io")?;
    lightcraft_catalog::safe_file::write_atomic(path, &bytes).map_err(|_| "migration_io")
}

fn adopt_info(root: &Path, id: &str) -> Option<Value> {
    if !is_id(id) {
        return None;
    }
    let dir = root.join(LIBS).join(id);
    if !exact(&dir) || !dir.is_dir() {
        return None;
    }
    let record = bounded_json(&dir.join(MARKER), 4096).ok()?;
    if record["version"] != 1 || record["id"].as_str() != Some(id) || label(record["name"].as_str()?).is_err() {
        return None;
    }
    Some(record)
}

/// Used only when the current in-app session is constructed. No URI or external path can be saved here.
pub(super) fn had_preferred_library(root: &Path) -> bool {
    let libs = root.join(LIBS);
    exact(&libs) && libs.join(ACTIVE).symlink_metadata().is_ok()
}

pub(super) fn active_library(root: &Path) -> PathBuf {
    let active = root.join(LIBS).join(ACTIVE);
    if regular(&active, 1, 36).is_ok() {
        if let Ok(id) = fs::read_to_string(active) {
            if adopt_info(root, &id).is_some() {
                return root.join(LIBS).join(id);
            }
        }
    }
    root.join("Library")
}

fn verify_tree(stage: &Path) -> Reply<()> {
    // Validate the same small top-level vocabulary as Android does before parsing any engine data.
    let mut count = 0;
    let mut bytes = 0u64;
    let mut todo = vec![(stage.to_path_buf(), 0)];
    while let Some((dir, depth)) = todo.pop() {
        if depth > MAX_DEPTH || !exact(&dir) {
            return Err("migration_scope");
        }
        for item in fs::read_dir(dir).map_err(|_| "migration_io")? {
            let item = item.map_err(|_| "migration_io")?;
            let name = item.file_name();
            let name = name.to_str().filter(|s| safe_component(s)).ok_or("migration_scope")?;
            let kind = item.file_type().map_err(|_| "migration_io")?;
            let path = item.path();
            if kind.is_symlink() {
                return Err("migration_scope");
            }
            count += 1;
            if count > MAX_ENTRIES {
                return Err("migration_limit");
            }
            if kind.is_dir() {
                if depth == 0 && name != "Originals" {
                    return Err("migration_scope");
                }
                todo.push((path, depth + 1));
            } else if kind.is_file() {
                let (min, max) = match (depth, name) {
                    (0, "catalog.snap") => (1, CATALOG_BYTES),
                    (0, "catalog.log") => (0, CATALOG_BYTES),
                    (0, "prefs.json" | "presets.json") => (1, PREFS_BYTES),
                    (0, _) => return Err("migration_scope"),
                    _ => (0, FILE_BYTES),
                };
                bytes = bytes.checked_add(regular(&path, min, max)?).ok_or("migration_limit")?;
                if bytes > TOTAL_BYTES {
                    return Err("migration_limit");
                }
            } else {
                return Err("migration_scope");
            }
        }
    }
    regular(&stage.join("catalog.snap"), 1, CATALOG_BYTES)?;
    regular(&stage.join("catalog.log"), 0, CATALOG_BYTES)?;
    let src = stage.join("Originals");
    if !src.is_dir() || !exact(&src) {
        return Err("migration_incomplete");
    }
    Ok(())
}

/// Split desktop paths *as text*: full drive/Unix paths supported, but no directory escape,
/// device path, URI, or assumption that any recorded desktop location exists on Android.
fn original_parts(path: &str) -> Reply<(String, Vec<String>)> {
    if path.len() > 4096 || path.chars().any(char::is_control) {
        return Err("migration_scope");
    }
    let clean = path.replace('\\', "/");
    if clean.starts_with("//") {
        return Err("migration_scope");
    }
    let b = clean.as_bytes();
    let (head, suffix) = if clean.starts_with('/') {
        ("/".to_owned(), &clean[1..])
    } else if b.len() > 3 && b[0].is_ascii_alphabetic() && b[1] == b':' && b[2] == b'/' {
        (clean[..3].to_owned(), &clean[3..])
    } else {
        return Err("migration_scope");
    };
    let part: Vec<String> = suffix.split('/').map(str::to_owned).collect();
    if part.len() > MAX_DEPTH * 2 || part.iter().any(|s| !safe_component(s)) {
        return Err("migration_scope");
    }
    Ok((head, part))
}

fn scope(path: &str) -> Reply<(String, PathBuf)> {
    let (head, parts) = original_parts(path)?;
    let i = parts.iter().rposition(|p| p.eq_ignore_ascii_case("Originals")).ok_or("migration_scope")?;
    let root = format!("{}{}", head, parts[..=i].join("/"));
    let tail: PathBuf = parts[i + 1..].iter().collect();
    Ok((root, tail))
}

fn same_root(a: &str, b: &str) -> bool {
    if a.as_bytes().get(1) == Some(&b':') { a.eq_ignore_ascii_case(b) } else { a == b }
}

fn move_path(path: &str, old: &str, new: &Path, stage: &Path) -> Reply<String> {
    let normalized = path.replace('\\', "/").trim_end_matches('/').to_owned();
    let (base, part) = scope(&normalized)?;
    if !same_root(&base, old) {
        return Err("migration_scope");
    }
    let staged = stage.join(&part);
    if !staged.exists() || !exact(&staged) {
        return Err("migration_missing");
    }
    new.join(part).into_os_string().into_string().map_err(|_| "migration_scope")
}

fn migrate_rules(rules: &mut RuleSet, old: &str, new: &Path, stage: &Path) -> Reply<()> {
    for r in &mut rules.rules {
        match r {
            Rule::Group { group } => migrate_rules(group, old, new, stage)?,
            Rule::Field { field, value, .. } if field == "filePath" => {
                if let Some(s) = value.as_str() {
                    // Relative text searches do not imply a desktop root.
                    let n = s.replace('\\', "/");
                    if n.starts_with('/') || n.as_bytes().get(1) == Some(&b':') {
                        *value = json!(move_path(s, old, new, stage)?);
                    }
                }
            }
            _ => {}
        }
    }
    Ok(())
}

fn migrate_filter(filter: &mut Filter, old: &str, new: &Path, stage: &Path) -> Reply<()> {
    for path in [&mut filter.library_folder, &mut filter.folder].into_iter().flatten() {
        *path = move_path(path, old, new, stage)?;
    }
    if let Some(rules) = filter.rule_set.as_mut() {
        migrate_rules(rules, old, new, stage)?;
    }
    Ok(())
}

fn hash_file(path: &Path) -> Reply<Hash128> {
    let mut file = File::open(path).map_err(|_| "migration_io")?;
    let mut h = Hasher128::new();
    let mut buffer = vec![0; 1 << 20];
    let mut bytes = 0u64;
    loop {
        let count = file.read(&mut buffer).map_err(|_| "migration_io")?;
        if count == 0 {
            return Ok(h.finish());
        }
        bytes += count as u64;
        if bytes > FILE_BYTES {
            return Err("migration_limit");
        }
        h.update(&buffer[..count]);
    }
}

fn built_in(id: &str) -> Reply<()> {
    if !id.is_empty() && lightcraft_engine::presets::profile(id).is_none() {
        return Err("migration_profiles");
    }
    Ok(())
}

fn relocate(catalog: &mut Catalog, source: &Path, target: &Path) -> Reply<(usize, usize, String)> {
    let mut old: Option<String> = None;
    let mut ops = Vec::new();
    let mut files: HashMap<PathBuf, (u64, Option<Hash128>)> = HashMap::new();
    let mut size_only: HashSet<PathBuf> = HashSet::new();
    let mut hashed = HashSet::new();
    for photo in catalog.photos() {
        built_in(&photo.develop.profile.id)?;
        for s in photo.versions.iter().map(|v| &v.settings).chain(photo.history.iter().map(|s| &s.settings)).chain(photo.import_look.iter()) {
            built_in(&s.profile.id)?;
        }
        let Source::File { path } = &photo.source else { continue };
        let (root, rel) = scope(path)?;
        if rel.as_os_str().is_empty() || rel.file_name().and_then(|s| s.to_str()) != Some(photo.file_name.as_str()) {
            return Err("migration_scope");
        }
        if let Some(base) = old.as_ref() {
            if !same_root(base, &root) {
                return Err("migration_scope");
            }
        } else {
            old = Some(root);
        }
        let p = source.join(&rel);
        let record = match files.entry(p.clone()) {
            std::collections::hash_map::Entry::Occupied(o) => o.into_mut(),
            std::collections::hash_map::Entry::Vacant(v) => {
                let length = regular(&p, 1, FILE_BYTES).map_err(|_| "migration_missing")?;
                v.insert((length, None))
            }
        };
        if photo.file_size != 0 && photo.file_size != record.0 {
            return Err("migration_mismatch");
        }
        if let Some(expected) = photo.content_hash.as_deref() {
            let expected = Hash128::parse(expected).ok_or("migration_damaged")?;
            let hash = match record.1 {
                Some(h) => h,
                None => {
                    let h = hash_file(&p)?;
                    record.1 = Some(h);
                    h
                }
            };
            if expected != hash {
                return Err("migration_mismatch");
            }
            hashed.insert(p);
        } else if photo.file_size == 0 {
            return Err("migration_mismatch");
        } else {
            size_only.insert(p);
        }
        let path = target.join(rel).into_os_string().into_string().map_err(|_| "migration_scope")?;
        ops.push(Op::Relink { id: photo.id, file_name: photo.file_name.clone(), source: Source::File { path }, format: None });
    }
    let old = old.ok_or("migration_incomplete")?;
    let dest = target.to_str().ok_or("migration_io")?;
    ops.extend(catalog.folder_records_follow(&old, dest));
    for (folder, at) in catalog.browsed_folders() {
        ops.push(Op::SetBrowsed { folder: folder.clone(), at: None });
        if let Ok(relinked) = move_path(folder, &old, target, source) {
            ops.push(Op::SetBrowsed { folder: relinked, at: Some(at.clone()) });
        }
    }
    for album in catalog.albums() {
        if let Some(original) = &album.smart {
            let mut rules = original.clone();
            migrate_filter(&mut rules, &old, target, source)?;
            if &rules != original {
                ops.push(Op::SetAlbumRules { id: album.id, rules });
            }
        }
    }
    for op in ops {
        catalog.apply(op).map_err(|_| "migration_damaged")?;
    }
    Ok((files.len(), size_only.difference(&hashed).count(), old))
}

fn copy_settings(stage: &Path, old: &str, target: &Path, source: &Path) -> Reply<()> {
    let presets = stage.join("presets.json");
    if presets.exists() {
        let p = bounded_json(&presets, PREFS_BYTES)?;
        if !p.is_object() {
            return Err("migration_damaged");
        }
        if let Some(user) = p.get("user") {
            let rows: Vec<lightcraft_develop::Preset> = serde_json::from_value(user.clone()).map_err(|_| "migration_damaged")?;
            for row in rows {
                if let Some(id) = row.settings["profile"]["id"].as_str() {
                    built_in(id)?;
                }
            }
        }
        for key in ["favorites", "profileFavorites", "profileRecent"] {
            if let Some(value) = p.get(key) {
                if !value.as_array().is_some_and(|rows| rows.iter().all(Value::is_string)) {
                    return Err("migration_damaged");
                }
            }
        }
    }
    let prefs = stage.join("prefs.json");
    if prefs.exists() {
        let doc = bounded_json(&prefs, PREFS_BYTES)?;
        if !doc.is_object() {
            return Err("migration_damaged");
        }
        if ["lutProfiles", "dcpProfiles"].iter().any(|&k| !doc[k].is_null() && !doc[k].as_array().is_some_and(Vec::is_empty)) {
            return Err("migration_profiles");
        }
        // Explicitly withhold view.json, desktop export/external paths, XMP auto-write settings,
        // local cache paths and desktop preview preferences.
        let mut kept = serde_json::Map::new();
        for k in ["metadataPresets", "curvePresets", "maskEffects", "maskToolEffects", "labelSets", "keywordSets", "keywordSet", "recentKeywords"] {
            if let Some(v) = doc.get(k) {
                kept.insert(k.to_string(), v.clone());
            }
        }
        if let Some(presets) = doc["filterPresets"].as_array() {
            let mut portable = Vec::new();
            for p in presets {
                let mut filter: Filter = serde_json::from_value(p["filter"].clone()).map_err(|_| "migration_damaged")?;
                migrate_filter(&mut filter, old, target, source)?;
                let name = p["name"].as_str().ok_or("migration_damaged")?;
                portable.push(json!({"name":name,"filter":filter}));
            }
            kept.insert("filterPresets".to_string(), json!(portable));
        }
        written_json(&prefs, &Value::Object(kept))?;
    }
    Ok(())
}

fn adopt(root: &Path, id: &str, name: &str) -> Reply<Value> {
    if !is_id(id) {
        return Err("migration_scope");
    }
    let name = label(name)?;
    let parent = root.join(LIBS);
    let stage = parent.join(format!("{id}.pending"));
    let final_dir = parent.join(id);
    if !exact(&parent) || !exact(&stage) || !stage.is_dir() || fs::symlink_metadata(&final_dir).is_ok() {
        return Err("migration_scope");
    }
    verify_tree(&stage)?;
    let store = FsStore::open(&stage).map_err(|_| "migration_io")?;
    let (mut journal, mut catalog, report) = Journal::open(Box::new(store)).map_err(|_| "migration_damaged")?;
    if report.created
        || report.failed > 0
        || report.torn_bytes > 0
        || report.torn_fragments > 0
        || report.damaged.is_some()
        || !report.repaired.is_empty()
    {
        return Err("migration_damaged");
    }
    if catalog.photos().count() > MAX_PHOTOS || catalog.albums().count() > MAX_PHOTOS {
        return Err("migration_limit");
    }
    let src = stage.join("Originals");
    let dest = final_dir.join("Originals");
    let (originals, size_only, old) = relocate(&mut catalog, &src, &dest)?;
    copy_settings(&stage, &old, &dest, &src)?;
    let record = json!({"version":1,"id":id,"name":name,"photos":catalog.photos().count(),"albums":catalog.albums().count(),"originals":originals,"sizeOnly":size_only});
    journal.snapshot(&catalog).map_err(|_| "migration_io")?;
    drop(journal);
    written_json(&stage.join(MARKER), &record)?;
    // Only a new private UUID is ever created. No existing catalog is replaced.
    fs::rename(&stage, &final_dir).map_err(|_| "migration_io")?;
    Ok(record)
}

impl Lab {
    pub(super) fn migration_library_id(&self) -> &str {
        self.session.library.as_ref().and_then(|l| l.dir.file_name()).and_then(|n| n.to_str()).filter(|id| is_id(id)).unwrap_or("default")
    }
    pub(super) fn migration_action(&mut self, op: &str, p: &Value) -> Reply<Vec<u8>> {
        let result = match op {
            "migration.current" => json!({"active":self.migration_library_id(),"fallback":self.mobile_catalogue_fallback}),
            "migration.copy" => adopt(&self.root, p["id"].as_str().ok_or("migration_scope")?, p["name"].as_str().ok_or("migration_incomplete")?)?,
            "migration.libraries" => {
                let root = self.root.join(LIBS);
                let mut names = Vec::new();
                if exact(&root) {
                    if let Ok(entries) = fs::read_dir(root) {
                        for e in entries.take(1024).flatten() {
                            if let Some(id) = e.file_name().to_str() {
                                if let Some(info) = adopt_info(&self.root, id) {
                                    names.push(info);
                                }
                            }
                        }
                    }
                }
                names.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()).then_with(|| a["id"].as_str().cmp(&b["id"].as_str())));
                json!({"active":self.migration_library_id(),"fallback":self.mobile_catalogue_fallback,"libraries":names})
            }
            "migration.switch" => {
                let id = p["id"].as_str().ok_or("migration_scope")?;
                let dir = if id == "default" {
                    self.root.join("Library")
                } else {
                    adopt_info(&self.root, id).ok_or("migration_scope")?;
                    self.root.join(LIBS).join(id)
                };
                if self.session.library.as_ref().is_some_and(|c| c.dir == dir) {
                    if id != "default" || !self.mobile_catalogue_fallback {
                        return Ok(bytes_json(json!({"active":id})));
                    }
                    let active = self.root.join(LIBS).join(ACTIVE);
                    if had_preferred_library(&self.root) {
                        fs::remove_file(active).map_err(|_| "migration_open")?;
                    }
                    self.mobile_catalogue_fallback = false;
                    return Ok(bytes_json(json!({"active":id})));
                }
                let mut open = Session::new().with_fs().with_system_clock();
                open.media.set_budget(192 * 1024 * 1024);
                open.open_library(&dir, false).map_err(|_| "migration_open")?;
                self.session.close_library().map_err(|_| "migration_open")?;
                if self.session.unsaved().is_some() {
                    return Err("migration_open");
                }
                let parent = self.root.join(LIBS);
                let active = parent.join(ACTIVE);
                if id == "default" {
                    if active.exists() {
                        if !exact(&parent) {
                            return Err("migration_scope");
                        }
                        fs::remove_file(active).map_err(|_| "migration_open")?;
                    }
                } else {
                    if !exact(&parent) {
                        return Err("migration_scope");
                    }
                    lightcraft_catalog::safe_file::write_atomic(&active, id.as_bytes()).map_err(|_| "migration_open")?;
                }
                self.session = open;
                self.mobile_catalogue_fallback = false;
                // Temporary merge plans contain photo IDs and are never shared between libraries.
                self.mobile_merge = Default::default();
                let _ = self.setup_mobile_denoise();
                json!({"active":id})
            }
            _ => return Err("unavailable"),
        };
        Ok(bytes_json(result))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_catalog::{Album, AlbumId, ColorLabel, HistoryStep, Photo, PhotoId, Version};
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};

    const ID: &str = "485e2d86-f6d9-4c89-b933-aa089011fb60";
    const OLD: &str = "C:/Photos/LightCraft/Originals/dev";
    const OLD_FILE: &str = "C:\\Photos\\LightCraft\\Originals\\dev\\pic.png";

    fn project(root: &Path, wrong_hash: bool, reference: &str) -> PathBuf {
        let stage = root.join(LIBS).join(format!("{ID}.pending"));
        let dev = stage.join("Originals/dev");
        fs::create_dir_all(&dev).unwrap();
        let image = lightcraft_raster::Rgba8::from_fn(16, 12, |x, y| [x as u8 * 5, y as u8 * 8, 125, 255]);
        let bytes = encode_png(&EncodeImage::rgba8(&image), &EncodeMeta::default()).unwrap();
        fs::write(dev.join("pic.png"), &bytes).unwrap();
        fs::write(dev.join("pic.png.xmp"), b"<xmpmeta/>").unwrap();
        let mut catalog = Catalog::new();
        let id = PhotoId(1);
        let mut photo = Photo::new(id, Source::File { path: reference.into() }, "pic.png", "PNG", 16, 12, "2026-01-01T00:00:00");
        photo.rating = 4;
        photo.file_size = bytes.len() as u64;
        photo.content_hash =
            Some(if wrong_hash { "10000000000000000000000000000000".into() } else { lightcraft_preview::hash_bytes(&bytes).to_string() });
        std::sync::Arc::make_mut(&mut photo.develop).light.exposure = 1.7;
        photo.history.push(HistoryStep { label: "Exposure".into(), settings: photo.develop.clone() });
        photo.versions.push(Version {
            name: "Saved look".into(),
            created: "2026-01-01T00:00:00".into(),
            settings: photo.develop.clone(),
            auto: false,
        });
        catalog.apply(Op::AddPhoto { photo: Box::new(photo) }).unwrap();
        catalog.apply(catalog.folder_label_op(OLD, Some(ColorLabel::Blue))).unwrap();
        let mut album = Album::new(AlbumId(1), "Best");
        album.photos.push(id);
        catalog.apply(Op::AddAlbum { album }).unwrap();
        let mut smart = Album::new(AlbumId(2), "Shot folder");
        smart.smart = Some(Box::new(Filter { library_folder: Some(OLD.into()), ..Default::default() }));
        catalog.apply(Op::AddAlbum { album: smart }).unwrap();
        let (mut journal, _, _) = Journal::open(Box::new(FsStore::open(&stage).unwrap())).unwrap();
        journal.snapshot(&catalog).unwrap();
        stage
    }

    #[test]
    fn native_copy_preserves_edits_albums_and_photos_as_a_separate_durable_library() {
        let root = tempfile::tempdir().unwrap();
        let root_path = root.path().canonicalize().unwrap();
        let stage = project(&root_path, false, OLD_FILE);
        let original = fs::read(stage.join("Originals/dev/pic.png")).unwrap();
        let mut lab = Lab::start(&root_path).unwrap();
        let active_before = lab.session.library.as_ref().unwrap().dir.clone();
        let r = adopt(&root_path, ID, "Projet test").unwrap();
        assert_eq!(r["photos"], 1);
        assert_eq!(r["albums"], 2);
        assert_eq!(r["sizeOnly"], 0);
        assert_eq!(lab.session.catalog.photos().count(), 0);
        assert!(!stage.exists());
        assert_eq!(active_library(&root_path), active_before);
        let path = root_path.join(LIBS).join(ID);
        let final_image = path.join("Originals/dev/pic.png");
        assert_eq!(fs::read(&final_image).unwrap(), original);
        assert!(path.join("Originals/dev/pic.png.xmp").exists());
        assert_eq!(adopt(&root_path, ID, "No replacement"), Err("migration_scope"));
        lab.migration_action("migration.switch", &json!({"id":ID})).unwrap();
        {
            let blocked = Lab::start(root.path()).unwrap();
            assert!(blocked.mobile_catalogue_fallback);
            assert_eq!(blocked.migration_library_id(), "default");
        }

        let photo = lab.session.catalog.photo(PhotoId(1)).unwrap();
        assert_eq!(photo.rating, 4);
        assert!((photo.develop.light.exposure - 1.7).abs() < 0.0001);
        assert_eq!(photo.history[0].label, "Exposure");
        assert_eq!(photo.versions[0].name, "Saved look");
        assert_eq!(photo.source, Source::File { path: final_image.to_str().unwrap().to_owned() });
        let folder = path.join("Originals/dev");
        assert_eq!(lab.session.catalog.folder_color_label(folder.to_str().unwrap()), Some(ColorLabel::Blue));
        assert_eq!(lab.session.catalog.album_photos(AlbumId(1)), vec![PhotoId(1)]);
        assert_eq!(lab.session.catalog.album_photos(AlbumId(2)), vec![PhotoId(1)]);
        assert_eq!(active_library(&root_path), path);
        lab.migration_action("migration.switch", &json!({"id":"default"})).unwrap();
        assert_eq!(lab.session.catalog.photos().count(), 0);
        assert_eq!(active_library(&root_path), active_before);
        assert_eq!(fs::read(&final_image).unwrap(), original);
    }

    #[test]
    fn missing_wrong_and_outside_originals_and_corrupt_journal_never_activate() {
        for (missing, wrong, reference, corrupt, code) in [
            (true, false, OLD_FILE, false, "migration_missing"),
            (false, true, OLD_FILE, false, "migration_mismatch"),
            (false, false, "C:/Photos/LightCraft/Originals/../dev/pic.png", false, "migration_scope"),
            (false, false, "/outside/dev/pic.png", false, "migration_scope"),
            (false, false, OLD_FILE, true, "migration_damaged"),
        ] {
            let root = tempfile::tempdir().unwrap();
            let path = root.path().canonicalize().unwrap();
            let stage = project(&path, wrong, reference);
            if missing {
                fs::remove_file(stage.join("Originals/dev/pic.png")).unwrap();
            }
            if corrupt {
                fs::write(stage.join("catalog.log"), b"torn").unwrap();
            }
            assert_eq!(adopt(&path, ID, "Refused"), Err(code));
            assert_eq!(active_library(&path), path.join("Library"));
            assert!(adopt_info(&path, ID).is_none());
            assert!(!path.join(LIBS).join(ID).exists());
        }
    }
}
