//! Bounded, read-only preview of the canonical desktop culling and similarity
//! analysis. A flag gets its own explicit command and confirmation.
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use jni::EnvUnowned;
use jni::objects::JClass;
use lightcraft_catalog::{MediaKind, Photo, PhotoId};
use lightcraft_engine::{
    Selection,
    cmd::{cull, similar},
};
use serde_json::{Value, json};

use crate::{Lab, Reply};

const MAX_PHOTOS: usize = 40;
const THUMB_LANES: usize = 2;
const RECENT: usize = 40;

#[derive(Default)]
struct Study {
    cancel: AtomicBool,
    done: AtomicUsize,
    total: AtomicUsize,
}

impl Study {
    fn total(&self, n: usize) {
        self.total.store(n, Ordering::Release);
    }
    fn progress(&self, n: usize) {
        self.done.fetch_max(n, Ordering::AcqRel);
    }
    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::Acquire)
    }
}

static JOB: Mutex<Option<(i64, Arc<Study>)>> = Mutex::new(None);

fn control(token: i64, action: i32) -> i64 {
    if token <= 0 {
        return 0;
    }
    let mut state = JOB.lock().unwrap_or_else(PoisonError::into_inner);
    if action == 0 {
        if state.is_some() {
            return 0;
        }
        *state = Some((token, Arc::new(Study::default())));
        return 1;
    }
    let Some((id, job)) = state.as_ref() else {
        return 0;
    };
    if *id != token {
        return 0;
    }
    match action {
        1 => (((job.total.load(Ordering::Acquire) as u64) << 32) | (job.done.load(Ordering::Acquire) as u64)) as i64,
        2 => {
            job.cancel.store(true, Ordering::Release);
            1
        }
        3 => {
            job.cancel.store(true, Ordering::Release);
            *state = None;
            1
        }
        _ => 0,
    }
}

// No JNI objects are touched; only bounded counters and a cancellation flag are shared.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_MobileAssistedCull_nativeStudyControl<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    token: i64,
    action: i32,
) -> i64 {
    control(token, action)
}

fn job(token: i64) -> Option<Arc<Study>> {
    JOB.lock().unwrap_or_else(PoisonError::into_inner).as_ref().filter(|(id, _)| *id == token).map(|(_, c)| c.clone())
}

fn eligible(p: &Photo) -> bool {
    p.in_library() && p.kind != MediaKind::Video
}

fn threshold(p: &Value, key: &str, min: f64, max: f64) -> Reply<f64> {
    p[key].as_f64().filter(|v| v.is_finite() && (min..=max).contains(v)).ok_or("edit")
}

fn info(p: &Photo) -> Value {
    json!({"id":p.id.0,"name":p.file_name,"copyName":p.copy_name,"flag":p.flag,"width":p.width,"height":p.height,"format":p.format})
}

impl Lab {
    fn study_ids(&self, p: &Value) -> Reply<Vec<PhotoId>> {
        let a = p["ids"].as_array().filter(|a| !a.is_empty() && a.len() <= MAX_PHOTOS).ok_or("selection")?;
        let mut ids = Vec::with_capacity(a.len());
        let mut seen = HashSet::with_capacity(a.len());
        for v in a {
            let id = PhotoId(v.as_u64().ok_or("selection")?);
            if !self.session.catalog.photo(id).is_some_and(|p| eligible(p)) || !seen.insert(id) {
                return Err("selection");
            }
            ids.push(id);
        }
        Ok(ids)
    }

    fn study_records(&self, result: &mut Value) -> Reply<()> {
        if result["cancelled"].as_bool() == Some(true) {
            return Ok(());
        }
        let photos = result["photos"].as_array_mut().ok_or("edit")?;
        for row in photos {
            let Some(photo) = row["id"].as_u64().and_then(|id| self.session.catalog.photo(PhotoId(id))) else {
                return Err("selection");
            };
            let record = info(photo);
            for (key, value) in record.as_object().into_iter().flatten() {
                row[key] = value.clone();
            }
        }
        Ok(())
    }

    pub(super) fn mobile_study(&mut self, action: &str, p: &Value) -> Reply<Value> {
        match action {
            "study_scope" => {
                let ids = self.study_ids(p)?;
                let out = ids.iter().filter_map(|id| self.session.catalog.photo(*id)).map(|p| info(p)).collect::<Vec<_>>();
                Ok(json!({"photos":out,"limit":MAX_PHOTOS}))
            }
            "study_recent" => {
                let reference = self.valid_photo(p)?;
                let mut possible = self.session.catalog.photos().filter(|p| eligible(p) && p.id != reference).collect::<Vec<_>>();
                possible.sort_by(|a, b| b.imported.cmp(&a.imported).then(b.id.cmp(&a.id)));
                let ids = std::iter::once(reference).chain(possible.into_iter().take(RECENT - 1).map(|p| p.id)).collect::<Vec<_>>();
                let count = ids.len();
                Ok(json!({"ids":ids,"count":count}))
            }
            "study_photo" => self.valid_photo(p).and_then(|id| self.session.catalog.photo(id).map(|ph| info(ph)).ok_or("selection")),
            "study_flag" => self.study_flag(p),
            "study_analyze" | "study_similar" => {
                let token = p["token"].as_i64().ok_or("edit")?;
                let Some(job) = job(token) else {
                    return Ok(json!({"cancelled":true}));
                };
                if job.cancelled() {
                    return Ok(json!({"cancelled":true}));
                }
                self.run_study(action, p, &job)
            }
            _ => Err("unavailable"),
        }
    }

    fn study_flag(&mut self, p: &Value) -> Reply<Value> {
        let id = self.valid_photo(p)?;
        let flag = p["flag"].as_str().filter(|f| matches!(*f, "pick" | "reject" | "none")).ok_or("edit")?;
        let current = self.session.catalog.photo(id).map(|ph| json!(ph.flag)).ok_or("selection")?;
        if p["confirmed"].as_bool() != Some(true) || p["expected"] != current {
            return Err("edit");
        }
        let selection = std::mem::replace(&mut self.session.selection, Selection::single(id));
        let write = std::mem::replace(&mut self.session.xmp.auto_write, false);
        let changed = self.session.execute("photo.flag", &json!({"ids":[id],"flag":flag})).map_err(|_| "edit");
        self.session.selection = selection;
        self.session.xmp.auto_write = write;
        changed.map(|_| json!({"flag":flag}))
    }

    fn run_study(&mut self, action: &str, p: &Value, job: &Study) -> Reply<Value> {
        let ids = self.study_ids(p)?;
        let similarity = threshold(p, "similarity", 0.0, 1.0)?;
        if ids.len() < 2 && action == "study_similar" {
            return Err("selection");
        }
        let mut result = if action == "study_analyze" {
            let burst = threshold(p, "burstGap", 0.0, 120.0)?;
            threshold(p, "sharpFloor", 0.0, 100.0)?;
            threshold(p, "clipFloor", 0.0, 100.0)?;
            // Score-only: no analysis records, flags or settings are persisted.
            job.total(ids.len());
            cull::analyze_cancellable(&mut self.session, &json!({"ids":ids,"burstGap":burst,"similarity":similarity}), false, &job.cancel, &|done| {
                job.progress(done)
            })
            .map_err(|_| "edit")?
        } else {
            let reference = PhotoId(p["source"].as_u64().ok_or("selection")?);
            if !ids.contains(&reference) {
                return Err("selection");
            }
            let (search, reads) =
                similar::plan_scoped(&mut self.session, &json!({"id":reference,"similarity":similarity,"filter":false}), &ids).map_err(|_| "edit")?;
            job.total(reads.len());
            let learned = similar::measure_limited(reads, &job.cancel, &|done| job.progress(done), THUMB_LANES);
            if job.cancelled() {
                return Ok(json!({"cancelled":true}));
            }
            similar::keep(&mut self.session, learned);
            let mut result = similar::apply(&mut self.session, &search).map_err(|_| "unsupported")?;
            let hit_rows = result["photos"].as_array_mut().ok_or("edit")?;
            hit_rows.retain(|row| row["id"].as_u64() != Some(reference.0));
            result["reference"] = self.session.catalog.photo(reference).map(|p| info(p)).ok_or("selection")?;
            result
        };
        if job.cancelled() {
            return Ok(json!({"cancelled":true}));
        }
        self.study_records(&mut result)?;
        // Desktop errors may contain internal source paths. Report count, not text.
        if let Some(failed) = result.get_mut("failed") {
            *failed = json!(failed.as_array().map_or(0, Vec::len));
        } else {
            result["failed"] = json!(ids.len().saturating_sub(result["compared"].as_u64().unwrap_or(0) as usize));
        }
        result["count"] = json!(ids.len());
        if action == "study_analyze" {
            let counts = result["photos"].as_array().into_iter().flatten().filter_map(|r| r["group"].as_u64().zip(r["id"].as_u64())).fold(
                HashMap::<u64, Vec<u64>>::new(),
                |mut map, (g, id)| {
                    map.entry(g).or_default().push(id);
                    map
                },
            );
            result["bursts"] = json!(counts);
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_catalog::{Flag, Op, Source};
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;
    use std::path::{Path, PathBuf};

    type Fixture = (PhotoId, PathBuf, Vec<u8>);

    fn photo(lab: &mut Lab, root: &Path, name: &str, seconds: &str, img: &Rgba8) -> Fixture {
        let path = root.join(name);
        let data = encode_png(&EncodeImage::rgba8(img), &EncodeMeta::default()).unwrap();
        std::fs::write(&path, &data).unwrap();
        let id = lab.session.catalog.alloc_photo_id();
        let mut p = Photo::new(id, Source::File { path: path.to_string_lossy().into_owned() }, name, "png", 192, 160, "2026-11-25T10:50:00");
        p.captured = Some(format!("2026-11-25T10:00:{seconds}"));
        lab.session.commit("Synthetic test", Op::AddPhoto { photo: Box::new(p) }).unwrap();
        (id, path, data)
    }

    fn sports_frame(soft: bool) -> Rgba8 {
        let value = |x: usize, y: usize| -> u8 {
            let broad = if x < 96 { 55 } else { 183 };
            if (x / 3 + y / 3) % 2 == 0 { broad - 30 } else { broad + 30 }
        };
        Rgba8::from_fn(192, 160, |x, y| {
            let v = if soft {
                let mut sum = 0usize;
                let mut count = 0;
                for yy in y.saturating_sub(5)..(y + 6).min(160) {
                    for xx in x.saturating_sub(5)..(x + 6).min(192) {
                        sum += value(xx, yy) as usize;
                        count += 1;
                    }
                }
                (sum / count) as u8
            } else {
                value(x, y)
            };
            [v, v, v, 255]
        })
    }

    #[test]
    fn mobile_sports_study_estimates_bursts_without_changing_photo_records_or_originals() {
        let d = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let a = photo(&mut lab, d.path(), "S001-sharp.png", "01", &sports_frame(false));
        let b = photo(&mut lab, d.path(), "S002-soft.png", "02", &sports_frame(true));
        let c = photo(
            &mut lab,
            d.path(),
            "S003-clipped.png",
            "03",
            &Rgba8::from_fn(192, 160, |_, y| {
                let l = if y < 80 { 0 } else { 255 };
                [l, l, l, 255]
            }),
        );
        let outside = photo(&mut lab, d.path(), "outside.png", "04", &sports_frame(false));
        lab.session.selection = Selection::single(c.0);
        lab.session.filter.only = vec![c.0];
        let before = lab.session.commits();
        let out = lab
            .run_study(
                "study_analyze",
                &json!({
                    "ids":[b.0,c.0,a.0],"burstGap":5.0,"similarity":0.90,"sharpFloor":30.0,"clipFloor":5.0,
                    // Even injected desktop writer switches are ignored.
                    "pickBest":true,"rejectBelow":100.0
                }),
                &Study::default(),
            )
            .unwrap();
        let rows = out["photos"].as_array().unwrap();
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0]["id"], a.0.0);
        assert!(rows[0]["sharpness"].as_f64().unwrap() > rows[1]["sharpness"].as_f64().unwrap());
        assert_eq!(rows[0]["group"], rows[1]["group"]);
        assert!(!rows[0]["group"].is_null());
        assert_eq!(rows[0]["best"], true);
        assert!(rows[2]["clipped"].as_f64().unwrap() > 0.5);
        assert_eq!(out["picked"], 0);
        assert_eq!(out["rejected"], 0);
        assert_eq!(lab.session.commits(), before);
        for (id, path, bytes) in [&a, &b, &c, &outside] {
            assert_eq!(std::fs::read(path).unwrap(), *bytes);
            let p = lab.session.catalog.photo(*id).unwrap();
            assert_eq!(p.flag, Flag::None);
            assert!(p.analysis.is_none());
        }

        // Learning the out-of-scope image in one search cannot leak it into another.
        lab.run_study("study_similar", &json!({"ids":[a.0,outside.0],"source":a.0,"similarity":0.8}), &Study::default()).unwrap();
        let hits = lab.run_study("study_similar", &json!({"ids":[a.0,b.0,c.0],"source":a.0,"similarity":0.8}), &Study::default()).unwrap();
        assert_eq!(hits["photos"].as_array().unwrap().iter().map(|p| p["id"].as_u64().unwrap()).collect::<Vec<_>>(), vec![b.0.0]);
        assert_eq!(hits["compared"], 3);
        assert_eq!(lab.session.selection, Selection::single(c.0));
        assert_eq!(lab.session.filter.only, vec![c.0]);
        assert_eq!(lab.session.commits(), before);

        assert!(lab.mobile_study("study_flag", &json!({"id":a.0,"flag":"reject","expected":"none"})).is_err());
        assert!(lab.mobile_study("study_flag", &json!({"id":a.0,"flag":"reject","expected":"pick","confirmed":true})).is_err());
        assert_eq!(lab.session.catalog.photo(a.0).unwrap().flag, Flag::None);
        lab.mobile_study("study_flag", &json!({"id":a.0,"flag":"pick","expected":"none","confirmed":true})).unwrap();
        assert_eq!(lab.session.catalog.photo(a.0).unwrap().flag, Flag::Pick);
        assert_eq!(lab.session.catalog.photo(b.0).unwrap().flag, Flag::None);
        assert_eq!(std::fs::read(&a.1).unwrap(), a.2);
    }

    #[test]
    fn native_job_cancel_and_selection_limits_work_before_decode() {
        let d = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let a = photo(&mut lab, d.path(), "One.png", "01", &sports_frame(false));
        let b = photo(&mut lab, d.path(), "Two.png", "02", &sports_frame(true));
        let c = Study::default();
        c.cancel.store(true, Ordering::Release);
        assert!(
            cull::analyze_cancellable(&mut lab.session, &json!({"ids":[a.0,b.0]}), false, &c.cancel, &|_| panic!("must not report a read")).unwrap()
                ["cancelled"]
                .as_bool()
                .unwrap()
        );
        assert!(lab.mobile_study("study_scope", &json!({"ids":vec![a.0;41]})).is_err());
        assert!(lab.mobile_study("study_scope", &json!({"ids":[a.0,a.0]})).is_err());
        assert_eq!(lab.mobile_study("study_recent", &json!({"id":a.0})).unwrap()["count"], 2);
        assert_eq!(control(66583, 0), 1);
        let detached = job(66583).unwrap();
        detached.total(10);
        detached.progress(3);
        detached.progress(2);
        assert_eq!(control(66583, 1), ((10_i64) << 32) | 3);
        assert_eq!(control(66583, 3), 1);
        assert!(detached.cancelled());
        assert!(job(66583).is_none());
        assert_eq!(
            lab.mobile_study("study_similar", &json!({"ids":[a.0,b.0],"source":a.0,"token":66583,"similarity":0.8})).unwrap()["cancelled"],
            true
        );
    }
}
