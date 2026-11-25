//! Native Android UI's narrow JNI bridge. The UI never launches a desktop window.
//! A single Android worker thread owns the catalogue and edits; originals are
//! selected through Android's document provider and copied before we open them.

use std::cell::RefCell;
use std::path::{Path, PathBuf};

use jni::EnvUnowned;
use jni::errors::{Error, ThrowRuntimeExAndDefault};
use jni::objects::{JByteArray, JClass, JString};
use lightcraft_catalog::{HistoryStep, MediaKind, Op, PhotoId};
use lightcraft_develop::controls;
use lightcraft_engine::Session;
use lightcraft_engine::export::{ExportOptions, MetadataPolicy, Resize, encode_rendered, export_photo, write_file_new};
use serde_json::{Value, json};

thread_local! {
    static LAB: RefCell<Option<Lab>> = const { RefCell::new(None) };
}

mod canvas_tools;
mod denoise_tools;
mod develop_tools;
mod export_tools;
mod library_tools;
mod looks;

struct Lab {
    root: PathBuf,
    session: Session,
}

type Reply<T> = Result<T, &'static str>;

fn bytes_json(v: Value) -> Vec<u8> {
    v.to_string().into_bytes()
}

impl Lab {
    fn start(root: &Path) -> Reply<Self> {
        let root = root.canonicalize().map_err(|_| "library")?;
        let mut session = Session::new().with_fs().with_system_clock();
        lightcraft_engine::gpu::set_enabled(false);
        session.media.set_budget(192 * 1024 * 1024);
        session.open_library(root.join("Library"), false).map_err(|_| "library")?;
        let mut lab = Self { root, session };
        let _ = lab.setup_mobile_denoise();
        Ok(lab)
    }

    #[cfg(test)]
    fn list(&self, p: &Value) -> Value {
        self.mobile_library_page(p)
    }

    fn valid_photo(&self, p: &Value) -> Reply<PhotoId> {
        let id = PhotoId(p["id"].as_u64().ok_or("selection")?);
        self.session.catalog.photo(id).filter(|p| !p.deleted && !p.local && p.kind != MediaKind::Video).map(|_| id).ok_or("selection")
    }

    fn select(&mut self, id: PhotoId) -> Reply<()> {
        if self.session.active() != Some(id) {
            self.session.execute("library.select", &json!({"active":id.0,"ids":[id.0]})).map_err(|_| "selection")?;
        }
        Ok(())
    }

    fn ensure_history_baseline(&mut self, id: PhotoId) -> Reply<()> {
        let photo = self.session.catalog.photo(id).ok_or("selection")?;
        if photo.history.is_empty() {
            let step = HistoryStep { label: "Before first mobile edit".into(), settings: photo.develop.clone() };
            self.session.commit("Start photo history", Op::SetHistory { id, history: vec![step] }).map_err(|_| "edit")?;
        }
        Ok(())
    }

    fn settings(&self, id: PhotoId) -> Reply<Value> {
        let edits = self.session.develop_of(id).ok_or("selection")?;
        let mut values: serde_json::Map<String, Value> = controls::CONTROLS
            .iter()
            .map(|s| s.id.to_owned())
            .chain(controls::indexed_instances(&edits).into_iter().map(|(id, _)| id))
            .filter_map(|key| controls::get(&edits, &key).map(|value| (key, json!(value))))
            .collect();
        values.insert("advanced".into(), self.advanced_values(id, &edits));
        let photo = self.session.catalog.photo(id).ok_or("selection")?;
        values.insert("canUndo".into(), json!(photo.history_index().is_some_and(|i| i > 0)));
        values.insert("canRedo".into(), json!(photo.history_index().is_some_and(|i| i + 1 < photo.history.len())));
        Ok(Value::Object(values))
    }

    fn photo_options(p: &Value) -> ExportOptions {
        ExportOptions {
            quality: p["quality"].as_u64().unwrap_or(86).clamp(55, 98) as u8,
            resize: Some(Resize::long_edge(p["edge"].as_u64().unwrap_or(960).clamp(128, 4096) as u32)),
            metadata: MetadataPolicy::None,
            ..Default::default()
        }
    }

    fn render(&mut self, id: PhotoId, p: &Value) -> Reply<Vec<u8>> {
        export_photo(&mut self.session, id, &Self::photo_options(p), 1)
            .map(|out| out.bytes)
            .map_err(|error| if error.contains("High Efficiency") || error.contains("HE★") { "nef_he" } else { "unsupported" })
    }

    fn render_before(&mut self, id: PhotoId, p: &Value) -> Reply<Vec<u8>> {
        let edge = p["edge"].as_u64().unwrap_or(960).clamp(128, 1440) as usize;
        let result = self.session.render_job(id, edge, edge, true, true).ok_or("selection")?.run();
        let rendered = result.rendered.map_err(|_| "unsupported")?;
        encode_rendered(&rendered, &Self::photo_options(p), None).map_err(|_| "unsupported")
    }

    fn import_path(&self, p: &str) -> Option<PathBuf> {
        let file = Path::new(p).canonicalize().ok()?;
        let imports = self.root.join("import").canonicalize().ok()?;
        (file.is_file() && file.starts_with(imports)).then_some(file)
    }

    fn import(&mut self, p: &Value) -> Reply<Value> {
        let paths = p["paths"].as_array().ok_or("import")?.iter().take(50).filter_map(|p| self.import_path(p.as_str()?)).collect::<Vec<_>>();
        if paths.is_empty() {
            return Err("import");
        }
        let v = self.session.execute("library.import", &json!({"paths":paths,"mode":"add"})).map_err(|_| "import")?;
        let count = |name: &str| v[name].as_array().map_or(0, Vec::len);
        // Drop rejected duplicate/bad copies only when no catalogue record points to them.
        for path in paths {
            let in_catalogue = self
                .session
                .catalog
                .photos()
                .any(|photo| matches!(&photo.source, lightcraft_catalog::Source::File {path: p} if Path::new(p) == path));
            if !in_catalogue {
                let _ = std::fs::remove_file(path);
            }
        }
        Ok(json!({"imported":count("imported")+count("restored"),"duplicates":count("duplicates"),"failed":count("failed")}))
    }

    fn action(&mut self, op: &str, p: &Value) -> Reply<Vec<u8>> {
        if canvas_tools::handles(op) {
            let id = self.valid_photo(p)?;
            if canvas_tools::mutates(op) {
                self.select(id)?;
                self.ensure_history_baseline(id)?;
            }
            let response = canvas_tools::run(&mut self.session, id, op, p)?;
            return if canvas_tools::mutates(op) { self.settings(id).map(bytes_json) } else { Ok(response) };
        }
        match op {
            "list" => Ok(bytes_json(self.mobile_library_page(p))),
            "export_configured" => export_tools::run(self, p),
            o if o.starts_with("looks.") => self.looks_action(o, p),
            o if o.starts_with("library_") => self.mobile_library(o, p).map(bytes_json),
            o if o.starts_with("noise_") => self.mobile_denoise(o, p).map(bytes_json),
            "import" => self.import(p).map(bytes_json),
            "metadata" => self.valid_photo(p).and_then(|id| self.settings(id)).map(bytes_json),
            "controls" => {
                let id = self.valid_photo(p)?;
                self.mobile_controls(id).map(bytes_json)
            }
            "advanced" => {
                let id = self.valid_photo(p)?;
                self.select(id)?;
                self.ensure_history_baseline(id)?;
                self.advanced_edit(id, p).map(bytes_json)
            }
            "render" => self.valid_photo(p).and_then(|id| self.render(id, p)),
            "before" => self.valid_photo(p).and_then(|id| self.render_before(id, p)),
            "export" => {
                let id = self.valid_photo(p)?;
                let file = p["file"]
                    .as_str()
                    .filter(|s| s.len() <= 100 && s.ends_with(".jpg") && s.bytes().all(|b| b.is_ascii_alphanumeric() || [b'-', b'.'].contains(&b)))
                    .ok_or("export")?;
                let folder = self.root.join("ExportQueue");
                std::fs::create_dir_all(&folder).map_err(|_| "export")?;
                let output = folder.join(file);
                let jpeg = self.render(id, p)?;
                write_file_new(output.to_str().ok_or("export")?, &jpeg).map_err(|_| "export")?;
                Ok(bytes_json(json!({"ready":true})))
            }
            "adjust_many" | "rotate" | "reset" | "undo" | "redo" | "crop" => {
                let id = self.valid_photo(p)?;
                self.select(id)?;
                if !matches!(op, "undo" | "redo") {
                    self.ensure_history_baseline(id)?;
                }
                match op {
                    "adjust_many" => {
                        let supplied = p["values"].as_object().filter(|o| o.len() <= 128).ok_or("edit")?;
                        let settings = self.session.develop_of(id).ok_or("selection")?;
                        let values = supplied
                            .iter()
                            .filter_map(|(k, v)| {
                                (develop_tools::slider_allowed(k, &settings) && v.as_f64().is_some_and(f64::is_finite))
                                    .then_some((k.clone(), v.clone()))
                            })
                            .collect::<serde_json::Map<String, Value>>();
                        if values.is_empty() || values.len() != supplied.len() {
                            return Err("edit");
                        }
                        self.session.execute("develop.set", &json!({"values":values})).map_err(|_| "edit")?;
                    }
                    "rotate" => {
                        self.session.execute("photo.rotateRight", &json!({"ids":[id.0]})).map_err(|_| "edit")?;
                    }
                    "undo" | "redo" => {
                        self.session.execute(if op == "undo" { "history.previous" } else { "history.next" }, &json!({})).map_err(|_| "edit")?;
                    }
                    "crop" => {
                        match p["aspect"].as_str() {
                            Some("reset") => self.session.execute("crop.reset", &json!({})),
                            Some(a @ ("1x1" | "4x5" | "5x4" | "2x3" | "3x2" | "16x9" | "9x16")) => {
                                self.session.execute("crop.aspect", &json!({"aspect":a, "orientation":"given"}))
                            }
                            _ => return Err("edit"),
                        }
                        .map_err(|_| "edit")?;
                    }
                    _ => {
                        self.session.execute("develop.reset", &json!({"ids":[id.0]})).map_err(|_| "edit")?;
                    }
                }
                self.settings(id).map(bytes_json)
            }
            _ => Err("unavailable"),
        }
    }
}

fn request(op: &str, input: &str) -> Reply<Vec<u8>> {
    let value: Value = serde_json::from_str(input).map_err(|_| "unavailable")?;
    LAB.with(|owner| {
        let mut lab = owner.try_borrow_mut().map_err(|_| "unavailable")?;
        if op == "open" {
            let path = Path::new(value["root"].as_str().ok_or("library")?);
            if lab.as_ref().is_none_or(|s| s.root != path) {
                *lab = Some(Lab::start(path)?);
            }
            Ok(bytes_json(json!({"ready":true})))
        } else {
            lab.as_mut().ok_or("library")?.action(op, &value)
        }
    })
}

// SAFETY: JNI static method signature matches NativeBridge.nativeRequest. EnvUnowned upgrades the
// caller's local frame and prevents a Rust panic from unwinding across the VM boundary.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_NativeBridge_nativeRequest<'a>(
    mut env: EnvUnowned<'a>,
    _: JClass<'a>,
    op: JString<'a>,
    input: JString<'a>,
) -> JByteArray<'a> {
    env.with_env(|env| -> Result<_, Error> {
        let v = request(&op.to_string(), &input.to_string()).unwrap_or_else(|e| bytes_json(json!({"error":e})));
        env.byte_array_from_slice(&v)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    #[test]
    fn mobile_engine_imports_edits_exports_and_reopens_without_touching_original() {
        let d = tempfile::tempdir().unwrap();
        let original = d.path().join("untouched.png");
        let local = d.path().join("import").join("pick-1").join("fixture.png");
        std::fs::create_dir_all(local.parent().unwrap()).unwrap();
        let raster = Rgba8::from_fn(90, 60, |x, y| [70 + x as u8, 30 + y as u8, 86, 255]);
        let png = encode_png(&EncodeImage::rgba8(&raster), &EncodeMeta::default()).unwrap();
        std::fs::write(&original, &png).unwrap();
        std::fs::copy(&original, &local).unwrap();

        let mut lab = Lab::start(d.path()).unwrap();
        let lib = d.path().join("Library");
        assert!(
            matches!(lightcraft_catalog::LibraryLock::acquire(&lib, "second opener"), Err(lightcraft_catalog::lock::LockError::InUse(_))),
            "the app must actually hold the catalogue lock while open"
        );
        assert_eq!(lab.import(&json!({"paths":[original]})), Err("import"));
        assert_eq!(lab.import(&json!({"paths":[local]})).unwrap()["imported"], 1);
        let row = lab.mobile_library_page(&json!({"query":"FIXTURE"}));
        let id = row["items"][0]["id"].as_u64().unwrap();
        assert_eq!(row["total"], 1);
        assert_eq!(lab.mobile_library_page(&json!({"query":"none"}))["total"], 0);
        let before = lab.action("render", &json!({"id":id,"edge":320})).unwrap();
        let edits = json!({"id":id,"values":{"light.exposure":1.5,"color.saturation":10}});
        lab.action("adjust_many", &edits).unwrap();
        assert_eq!(lab.settings(PhotoId(id)).unwrap()["light.exposure"], 1.5);
        let after = lab.action("render", &json!({"id":id,"edge":320})).unwrap();
        assert!(after.starts_with(&[0xff, 0xd8]));
        assert_ne!(before, after);
        assert_ne!(lab.action("before", &json!({"id":id,"edge":320})).unwrap(), after);
        assert_eq!(lab.settings(PhotoId(id)).unwrap()["canUndo"], true);
        lab.action("undo", &json!({"id":id})).unwrap();
        assert_eq!(lab.settings(PhotoId(id)).unwrap()["light.exposure"], 0.0);
        assert_eq!(lab.settings(PhotoId(id)).unwrap()["canRedo"], true);
        lab.action("redo", &json!({"id":id})).unwrap();
        assert_eq!(lab.settings(PhotoId(id)).unwrap()["light.exposure"], 1.5);
        assert_eq!(lab.action("export", &json!({"id":id,"file":"../out.jpg"})), Err("export"));
        lab.action("export", &json!({"id":id,"file":"Export-test.jpg","edge":640})).unwrap();
        let output = std::fs::read(d.path().join("ExportQueue/Export-test.jpg")).unwrap();
        let decoded = lightcraft_codecs::decode(&output, lightcraft_codecs::DecodeOptions::default()).unwrap();
        assert_eq!((decoded.width, decoded.height), (90, 60));
        assert_eq!(std::fs::read(&original).unwrap(), png);
        assert_eq!(std::fs::read(&local).unwrap(), png);
        lab.session.close_library().unwrap();
        drop(lab);
        let mut reopened = Lab::start(d.path()).unwrap();
        assert_eq!(reopened.settings(PhotoId(id)).unwrap()["light.exposure"], 1.5);
        reopened.action("reset", &json!({"id":id})).unwrap();
        assert_eq!(reopened.settings(PhotoId(id)).unwrap()["light.exposure"], 0.0);
        reopened.action("crop", &json!({"id":id,"aspect":"1x1"})).unwrap();
        let square = reopened.action("render", &json!({"id":id,"edge":320})).unwrap();
        let square = lightcraft_codecs::decode(&square, lightcraft_codecs::DecodeOptions::default()).unwrap();
        assert_eq!(square.width, square.height);
        assert!(square.width > 0);
    }
}
