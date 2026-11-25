//! The Android workspace owns its settings; the shared engine composes every displayed and exported slide.
use std::{
    collections::HashSet,
    path::Path,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering::Relaxed},
    },
};

use lightcraft_catalog::PhotoId;
use lightcraft_codecs::{ChromaSubsampling, EncodeImage, EncodeMeta, encode_jpeg, video::AV1_AVAILABLE};
use lightcraft_engine::{
    slide_template::SlideLook,
    slide_title::TitleCompose,
    slideshow::{Item, Playback, Title},
    slideshow_export::{Prepared, prepare_with_look},
};
use serde_json::{Value, json};

use super::{Lab, Reply, bytes_json};

const MAX_PHOTOS: usize = 80;
const MAX_OUTPUT: u64 = 120 * 1024 * 1024;

pub(super) struct Run {
    ticket: String,
    signal: Arc<AtomicBool>,
    done: Arc<AtomicU64>,
    total: u64,
    result: Arc<Mutex<Option<Reply<Value>>>>,
}

fn ticket(token: &str) -> bool {
    token.len() == 36 && token.bytes().enumerate().all(|(i, b)| if matches!(i, 8 | 13 | 18 | 23) { b == b'-' } else { b.is_ascii_hexdigit() })
}

fn output_bytes(path: &Path) -> Option<u64> {
    let meta = path.symlink_metadata().ok()?;
    if meta.is_file() {
        return Some(meta.len());
    }
    if !meta.is_dir() {
        return None;
    }
    path.read_dir().ok()?.try_fold(0_u64, |acc, f| {
        let meta = f.ok()?.path().symlink_metadata().ok()?;
        meta.is_file().then(|| acc.saturating_add(meta.len()))
    })
}

impl Lab {
    pub(super) fn mobile_slideshow(&mut self, op: &str, p: &Value) -> Reply<Vec<u8>> {
        match op {
            "slide_info" => Ok(bytes_json(json!({"video":AV1_AVAILABLE, "limit":MAX_PHOTOS}))),
            "slide_plan" => self.slide_plan(p).map(bytes_json),
            "slide_frame" => self.slide_frame(p),
            "slide_export" => self.slide_export(p).map(bytes_json),
            "slide_status" | "slide_cancel" => self.slide_status(p, op == "slide_cancel").map(bytes_json),
            _ => Err("slideshow"),
        }
    }

    fn show_ids(&self, p: &Value) -> Reply<Vec<PhotoId>> {
        let ids = p["ids"].as_array().filter(|v| (1..=MAX_PHOTOS).contains(&v.len())).ok_or("slide_selection")?;
        let mut seen = HashSet::new();
        ids.iter()
            .map(|v| {
                let id = PhotoId(v.as_u64().ok_or("slide_selection")?);
                (seen.insert(id) && self.valid_photo(&json!({"id": id.0})).is_ok()).then_some(id).ok_or("slide_selection")
            })
            .collect()
    }

    fn show_look(&mut self, p: &Value) -> Reply<SlideLook> {
        let mut look = match p["template"].as_str() {
            Some(
                id @ ("builtin:plain-black" | "builtin:edge-to-edge" | "builtin:gallery-wall" | "builtin:cinema-wide" | "builtin:shooting-data"),
            ) => self.session.slide_template(id).map_err(|_| "slideshow")?.look.clone(),
            None | Some("") => SlideLook::of(&self.session),
            Some(_) => return Err("slideshow"),
        };
        let mut layout = serde_json::Map::new();
        for key in ["aspect", "margins", "zoomToFill", "backdropColor"] {
            if let Some(v) = p.get(key).filter(|v| !v.is_null()) {
                layout.insert(key.into(), v.clone());
            }
        }
        if !layout.is_empty() {
            look.layout = look.layout.merged(&Value::Object(layout)).map_err(|_| "slideshow")?;
        }
        if let Some(play) = p.get("playback") {
            look.playback = look.playback.merged(play).map_err(|_| "slideshow")?;
        }
        let mut titles = serde_json::Map::new();
        for key in ["intro", "ending"] {
            if let Some(text) = p[key].as_str() {
                if text.chars().count() > 180 {
                    return Err("slideshow");
                }
                titles.insert(key.into(), json!({"enabled": !text.trim().is_empty(),"content":"text","text":text.trim()}));
            }
        }
        if !titles.is_empty() {
            look.titles = look.titles.merged(&Value::Object(titles)).map_err(|_| "slideshow")?;
        }
        Ok(look)
    }

    fn slide_plan(&mut self, p: &Value) -> Reply<Value> {
        let mut ids = self.show_ids(p)?;
        if p["reverse"].as_bool() == Some(true) {
            ids.reverse();
        }
        let look = self.show_look(p)?;
        let settings = look.playback.clone().sanitized();
        let (intro, ending) = look.titles.durations(settings.slide_duration);
        let seed = p["seed"].as_u64().unwrap_or(0);
        let play = Playback::new(ids, None, settings.clone(), seed, 0.0).ok_or("slideshow")?.with_titles(intro, ending);
        let items = std::iter::once(play.current())
            .chain(play.upcoming(MAX_PHOTOS + 2))
            .map(|it| {
                let duration = (play.duration(it) * 1000.0).round() as u64;
                match it {
                    Item::Photo(id) => json!({"id":id.0,"ms":duration}),
                    Item::Title(title) => json!({"title":title.id(),"ms":duration}),
                }
            })
            .collect::<Vec<_>>();
        Ok(json!({"items":items, "repeat":settings.repeat,"shuffle":settings.random_order,"manual":settings.manual,
            "fadeMs":(settings.fade_duration * 1000.0).round() as u64,
            "fadeColor":settings.to_json()["fadeColor"],"prepare":settings.prepare_previews}))
    }

    fn slide_frame(&mut self, p: &Value) -> Reply<Vec<u8>> {
        let look = self.show_look(p)?;
        let (w, h) = match (p["width"].as_u64(), p["height"].as_u64()) {
            (Some(w @ 96..=1280), Some(h @ 96..=1280)) => (w as usize, h as usize),
            _ => return Err("slideshow"),
        };
        let ratio = look.layout.aspect.ratio(w as f64 / h as f64);
        let (mut w, mut h) = (w, h);
        if ratio > w as f64 / h as f64 {
            h = (w as f64 / ratio).round().max(1.0) as usize;
        } else {
            w = (h as f64 * ratio).round().max(1.0) as usize;
        }
        let pixels = if let Some(t) = p["title"].as_str().and_then(Title::parse) {
            TitleCompose::new(&look.titles, &look.overlays, t, w, h).compose()
        } else {
            let id = self.valid_photo(p)?;
            self.session.slide_now_look(&look.layout, &look.overlays, id, w, h).map_err(|_| "slide_frame")?
        };
        encode_jpeg(&EncodeImage::rgba8(&pixels), 87, ChromaSubsampling::S444, &EncodeMeta::default()).map_err(|_| "slide_frame")
    }

    fn slide_export(&mut self, p: &Value) -> Reply<Value> {
        if self.mobile_show_export.as_ref().is_some_and(|job| job.result.lock().is_ok_and(|r| r.is_none())) {
            return Err("slide_busy");
        }
        let mut ids = self.show_ids(p)?;
        if p["reverse"].as_bool() == Some(true) {
            ids.reverse();
        }
        let look = self.show_look(p)?;
        let token = p["ticket"].as_str().filter(|s| ticket(s)).ok_or("slide_export")?;
        let (format, name) = match p["format"].as_str() {
            Some("pdf") => ("pdf", "Slideshow.pdf"),
            Some("jpeg") => ("jpeg", "images"),
            Some("mp4") if AV1_AVAILABLE => ("video", "Slideshow.mp4"),
            Some("webm") if AV1_AVAILABLE => ("video", "Slideshow.webm"),
            _ => return Err("slide_export"),
        };
        let edge = p["size"].as_u64().unwrap_or(1024);
        if (format != "video" && ![128, 1024, 1920].contains(&edge))
            || (format == "video" && (!AV1_AVAILABLE || !(64..=720).contains(&edge) || ids.len() > 24 || look.playback.fade_duration > 2.0))
        {
            return Err("slide_limit");
        }
        let quality = p["quality"].as_u64().unwrap_or(88).clamp(55, 95);
        let dir = self.root.join("SlideshowQueue");
        std::fs::create_dir_all(&dir).map_err(|_| "slide_export")?;
        if !dir.canonicalize().is_ok_and(|r| r == dir) {
            return Err("slide_export");
        }
        let staging = dir.join(token);
        std::fs::create_dir(&staging).map_err(|_| "slide_export")?;
        let path = staging.join(name);
        let params = json!({"ids":ids.iter().map(|id|id.0).collect::<Vec<_>>(),"path":path,
            "format":format,"size":edge,"quality":quality,"fps":24,"speed":10,
            "container":if name.ends_with(".webm") {"webm"} else {"mp4"}, "name":if format == "jpeg" { Some("Slide") } else { None }, "fullScreen":p["fullScreen"].as_bool().unwrap_or(true)});
        let prepared = prepare_with_look(&mut self.session, &params, &look).map_err(|_| "slide_export").and_then(|prepared| {
            if format == "video" && prepared.seconds() > 120.0 {
                return Err("slide_limit");
            }
            Ok(prepared)
        });
        let job = match prepared.and_then(|prepared| Run::start(token, prepared, staging.clone(), path)) {
            Ok(job) => job,
            Err(err) => {
                let _ = std::fs::remove_dir_all(staging);
                return Err(err);
            }
        };
        self.mobile_show_export = Some(job);
        Ok(json!({"ticket":token}))
    }

    fn slide_status(&self, p: &Value, cancel: bool) -> Reply<Value> {
        let token = p["ticket"].as_str().ok_or("slide_export")?;
        let job = self.mobile_show_export.as_ref().filter(|j| j.ticket == token).ok_or("slide_export")?;
        if cancel {
            job.signal.store(true, Relaxed);
        }
        let result = job.result.lock().map_err(|_| "slide_export")?;
        Ok(match result.as_ref() {
            None => json!({"state":"working","done":job.done.load(Relaxed),"total":job.total}),
            Some(Ok(v)) => v.clone(),
            Some(Err(code)) => json!({"state":"failed","code":code}),
        })
    }
}

impl Run {
    fn start(ticket: &str, prep: Prepared, staging: std::path::PathBuf, path: std::path::PathBuf) -> Reply<Self> {
        let job = Self { ticket: ticket.into(), signal: Arc::default(), done: Arc::default(), total: prep.total(), result: Arc::default() };
        let done = job.done.clone();
        let signal = job.signal.clone();
        let result = job.result.clone();
        std::thread::Builder::new()
            .name("lc-mobile-slideshow".into())
            .spawn(move || {
                let render = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| prep.run(&|n, _, _| done.store(n, Relaxed), &signal)));
                let value = if signal.load(Relaxed) {
                    Err("slide_cancelled")
                } else {
                    match render {
                        Ok(Ok(out)) if output_bytes(&path).is_some_and(|bytes| (1..=MAX_OUTPUT).contains(&bytes)) => {
                            Ok(json!({"state":"ready", "slides":out.json["slides"],"skipped":out.json["skipped"]}))
                        }
                        Ok(Ok(_)) => Err("slide_limit"),
                        _ => Err("slide_export"),
                    }
                };
                if value.is_err() {
                    let _ = std::fs::remove_dir_all(staging);
                }
                if let Ok(mut reply) = result.lock() {
                    *reply = Some(value);
                }
            })
            .map_err(|_| "slide_export")?;
        Ok(job)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::encode_png;
    use lightcraft_raster::Rgba8;
    use std::time::{Duration, Instant};

    const PDF: &str = "a20b7f1e-b123-4038-92ab-10303f53838d";
    const JPEG: &str = "d1778216-c2ae-4798-bf65-5f24fcb4b541";
    const CANCEL: &str = "32c6bf37-947e-4d72-84ac-e56fda1c2d10";
    const MP4: &str = "08a5a315-6cb3-47a7-95c9-8f545d16021a";
    const WEBM: &str = "5316e7ba-8951-4ba7-8a29-8e9cf63c0ca5";

    fn wait(lab: &Lab, ticket: &str) -> Value {
        let deadline = Instant::now() + Duration::from_secs(45);
        loop {
            let status = lab.slide_status(&json!({"ticket":ticket}), false).unwrap();
            if status["state"] != "working" {
                return status;
            }
            assert!(Instant::now() < deadline, "timed out exporting {ticket}");
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    #[test]
    fn slideshow_uses_shared_composer_and_safe_export_without_changing_catalogue() {
        let root = tempfile::tempdir().unwrap();
        let incoming = root.path().join("import");
        std::fs::create_dir(&incoming).unwrap();
        let photos = [[224, 49, 25, 255], [26, 90, 214, 255]]
            .into_iter()
            .enumerate()
            .map(|(i, color)| {
                let raw = encode_png(&EncodeImage::rgba8(&Rgba8::from_fn(96, 70, |_, _| color)), &EncodeMeta::default()).unwrap();
                let path = incoming.join(format!("slideshow-test-{i}.png"));
                std::fs::write(&path, &raw).unwrap();
                (path, raw)
            })
            .collect::<Vec<_>>();
        let mut lab = Lab::start(root.path()).unwrap();
        lab.import(&json!({"paths":photos.iter().map(|(p,_)|p).collect::<Vec<_>>() })).unwrap();
        let ids = lab.session.catalog.photos().map(|photo| photo.id.0).collect::<Vec<_>>();
        assert_eq!(ids.len(), 2);
        let original = SlideLook::of(&lab.session);
        let initial_active = lab.session.active();
        let mut options = json!({
            "ids":ids,"template":"builtin:gallery-wall","aspect":"4:3","reverse":true,
            "intro":"Opening","ending":"Closing",
            "playback":{"slideDuration":0.5,"fadeDuration":0.2,"repeat":false},"size":128
        });
        let plan = lab.slide_plan(&options).unwrap();
        assert_eq!(plan["items"][0]["title"], "intro");
        assert_eq!(plan["items"][1]["id"], ids[1]);
        assert_eq!(plan["items"][2]["id"], ids[0]);
        assert_eq!(plan["items"][3]["title"], "ending");
        assert_eq!(plan["fadeMs"], 200);

        let mut args = options.clone();
        args["width"] = json!(160);
        args["height"] = json!(240);
        args["id"] = json!(ids[0]);
        let photo = lab.slide_frame(&args).unwrap();
        assert!(photo.starts_with(&[0xff, 0xd8]));
        args["title"] = json!("intro");
        let title = lab.slide_frame(&args).unwrap();
        assert!(title.starts_with(&[0xff, 0xd8]));
        assert_ne!(title, photo);

        options["format"] = json!("pdf");
        options["ticket"] = json!("../outside");
        assert!(lab.slide_export(&options).is_err());
        options["ticket"] = json!(PDF);
        lab.slide_export(&options).unwrap();
        let status = wait(&lab, PDF);
        assert_eq!(status["state"], "ready", "{status}");
        assert_eq!(status["slides"], 4);
        let stage = root.path().join("SlideshowQueue");
        let pdf = std::fs::read(stage.join(PDF).join("Slideshow.pdf")).unwrap();
        assert!(pdf.starts_with(b"%PDF-"));
        assert!(pdf.windows(b"/PageMode /FullScreen".len()).any(|v| v == b"/PageMode /FullScreen"));
        assert!(pdf.windows(5).any(|v| v == b"/Dur "));
        assert!(lab.slide_export(&options).is_err(), "existing staged PDF cannot be overwritten");

        options["format"] = json!("jpeg");
        options["ticket"] = json!(JPEG);
        lab.slide_export(&options).unwrap();
        let status = wait(&lab, JPEG);
        assert_eq!(status["state"], "ready", "{status}");
        assert_eq!(status["slides"], 4);
        for n in 1..=4 {
            let path = stage.join(JPEG).join("images").join(format!("Slide-{n:03}.jpg"));
            assert!(std::fs::read(path).unwrap().starts_with(&[0xff, 0xd8]));
        }
        options["ticket"] = json!(CANCEL);
        options["size"] = json!(1920);
        lab.slide_export(&options).unwrap();
        let cancellation = lab.slide_status(&json!({"ticket":CANCEL}), true).unwrap();
        let status = wait(&lab, CANCEL);
        if cancellation["state"] == "working" {
            assert_eq!(status["code"], "slide_cancelled", "{status}");
            assert!(!stage.join(CANCEL).exists());
        }

        if AV1_AVAILABLE {
            for (format, ticket, extension, header) in
                [("mp4", MP4, "mp4", b"ftyp".as_slice()), ("webm", WEBM, "webm", b"\x1a\x45\xdf\xa3".as_slice())]
            {
                options["format"] = json!(format);
                options["ticket"] = json!(ticket);
                options["size"] = json!(96);
                lab.slide_export(&options).unwrap();
                let status = wait(&lab, ticket);
                assert_eq!(status["state"], "ready", "{format}: {status}");
                let video = std::fs::read(stage.join(ticket).join(format!("Slideshow.{extension}"))).unwrap();
                let offset = if format == "mp4" { 4 } else { 0 };
                assert_eq!(&video[offset..offset + 4], header);
            }
        }
        assert_eq!(SlideLook::of(&lab.session), original);
        assert_eq!(lab.session.active(), initial_active);
        for (path, bytes) in photos {
            assert_eq!(std::fs::read(path).unwrap(), bytes);
        }
    }
}
