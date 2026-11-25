//! Photo Merge in the Android workspace: same desktop algorithm, app-private sources,
//! bounded work and cancellation without blocking the catalogue thread.
use lightcraft_catalog::{PhotoId, Source};
use lightcraft_engine::merge::{MergeJob, MergeOutput, parse};
use serde_json::{Value, json};
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, mpsc};

use crate::{Lab, Reply};

const MIN_BUDGET_MB: u64 = 272;
const MAX_BUDGET_MB: u64 = 1200;
const FRAME_COUNT_MAX: usize = 12;
const FILE_BYTES_MAX: u64 = 256 << 20;

#[derive(Default)]
pub(super) struct MergeState {
    active: Option<Running>,
    last: Option<Value>,
}

struct Running {
    job: MergeJob,
    cancelled: Arc<AtomicBool>,
    progress: Arc<AtomicU32>,
    result: mpsc::Receiver<Result<MergeOutput, String>>,
}

impl Drop for Running {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }
}

fn valid_enum<'a>(p: &'a Value, name: &str, choices: &[&str], default: &'a str) -> Reply<&'a str> {
    match p.get(name) {
        None => Ok(default),
        Some(value) => value.as_str().filter(|s| choices.contains(s)).ok_or("merge_options"),
    }
}

impl Lab {
    pub(super) fn mobile_merge(&mut self, op: &str, p: &Value) -> Reply<Value> {
        match op {
            "merge_start" => self.mobile_merge_start(p),
            "merge_status" => self.mobile_merge_poll(),
            "merge_cancel" => {
                if let Some(active) = &self.mobile_merge.active {
                    active.cancelled.store(true, Ordering::Relaxed);
                }
                self.mobile_merge_poll()
            }
            _ => Err("unavailable"),
        }
    }

    fn mobile_merge_start(&mut self, p: &Value) -> Reply<Value> {
        if self.mobile_merge.active.is_some() {
            return self.mobile_merge_poll();
        }
        let name = valid_enum(p, "kind", &["hdr", "panorama", "hdrPanorama"], "hdr")?;
        let command = match name {
            "hdr" => "merge.hdr",
            "panorama" => "merge.panorama",
            _ => "merge.hdrPanorama",
        };
        let minimum = if name == "hdrPanorama" { 4 } else { 2 };
        let picked = p["ids"].as_array().filter(|v| (minimum..=FRAME_COUNT_MAX).contains(&v.len())).ok_or("merge_count")?;
        let mut ids = Vec::with_capacity(picked.len());
        let mut seen = HashSet::new();
        let imports = self.root.join("import").canonicalize().map_err(|_| "merge_source")?;
        let mut all_pixels = 0_u64;
        let mut largest = 0_u64;
        for row in picked {
            let id = self.valid_photo(&json!({"id":row}))?;
            if !seen.insert(id) {
                return Err("merge_count");
            }
            let photo = self.session.catalog.photo(id).ok_or("selection")?;
            let Source::File { path } = &photo.source else { return Err("merge_source") };
            let source = PathBuf::from(path).canonicalize().map_err(|_| "merge_source")?;
            if !source.starts_with(&imports) {
                return Err("merge_source");
            }
            let f = std::fs::metadata(source).map_err(|_| "merge_source")?;
            if !f.is_file() || f.len() == 0 || f.len() > FILE_BYTES_MAX {
                return Err("merge_source");
            }
            let pixels = u64::from(photo.width) * u64::from(photo.height);
            if pixels == 0 {
                return Err("merge_source");
            }
            all_pixels = all_pixels.saturating_add(pixels);
            largest = largest.max(pixels);
            ids.push(id);
        }
        let mb = p["budgetMiB"].as_u64().unwrap_or(400).clamp(MIN_BUDGET_MB, MAX_BUDGET_MB);
        // Native allocations are outside the Java heap; leave room for decode scratch, image pipeline and blend.
        let budget = mb * 1024 * 1024;
        let reduced = p["reduced"].as_bool().unwrap_or(false);
        // A reduced merge shrinks retained frames, but RAWs still decode/demosaic at source size.
        // Enclose the job in a small rayon pool so at most one source is decoding at a time.
        let retained = if reduced { (ids.len() as u64).saturating_mul(1_048_576).min(all_pixels) } else { all_pixels };
        let decode_peak = largest.saturating_mul(if reduced { 64 } else { 40 });
        let estimate = (96_u64 << 20).saturating_add(retained.saturating_mul(14)).saturating_add(decode_peak);
        if estimate > budget {
            return Err("merge_memory");
        }
        let output_mp = (((budget - estimate) / 72).clamp(2_000_000, 30_000_000) as f64 / 1e6).floor();
        let bracket = p["bracket"].as_u64().unwrap_or(0);
        if bracket != 0 && !(2..=5).contains(&bracket) {
            return Err("merge_options");
        }
        let ghost = valid_enum(p, "deghost", &["none", "low", "medium", "high"], "none")?;
        let projection = valid_enum(p, "projection", &["auto", "spherical", "cylindrical", "perspective"], "auto")?;
        let warp = p["boundaryWarp"].as_u64().unwrap_or(0).min(100);
        let options = json!({
            "align":p["align"].as_bool().unwrap_or(true),"deghost":ghost,"projection":projection,
            "bracket":bracket,"boundaryWarp":warp,"autoCrop":p["autoCrop"].as_bool().unwrap_or(true),
            "fillEdges":p["fillEdges"].as_bool().unwrap_or(false),"maxMegapixels":output_mp,
            "autoSettings":p["autoSettings"].as_bool().unwrap_or(true),"stack":p["stack"].as_bool().unwrap_or(false)
        });
        let (kind, finish) = parse(command, &options).map_err(|_| "merge_options")?;
        let mut job = self.session.plan_merge(kind, finish, &ids, reduced).map_err(|_| "merge_source")?;
        job.read = Arc::new(move |path| {
            let src = PathBuf::from(path).canonicalize().map_err(|_| "cannot access photo".to_string())?;
            let meta = std::fs::metadata(&src).map_err(|_| "cannot access photo".to_string())?;
            if !src.starts_with(&imports) || !meta.is_file() || meta.len() == 0 || meta.len() > FILE_BYTES_MAX {
                return Err("cannot read this photo".into());
            }
            std::fs::read(src).map_err(|_| "cannot read this photo".to_string())
        });
        let progress = Arc::new(AtomicU32::new(0));
        let cancelled = Arc::new(AtomicBool::new(false));
        let (send, receiver) = mpsc::sync_channel(1);
        let (run_job, tick, stop) = (job.clone(), progress.clone(), cancelled.clone());
        std::thread::Builder::new()
            .name("Android Photo Merge".into())
            .spawn(move || {
                let result =
                    rayon::ThreadPoolBuilder::new().num_threads(if reduced { 1 } else { 2 }).build().map_err(|e| e.to_string()).and_then(|pool| {
                        pool.install(|| {
                            run_job.run(&|fraction, _| {
                                let n = (fraction.clamp(0.0, 1.0) * 100.0) as u32;
                                tick.fetch_max(n, Ordering::Relaxed);
                                !stop.load(Ordering::Relaxed)
                            })
                        })
                    });
                let _ = send.send(result);
            })
            .map_err(|_| "merge_start")?;
        self.mobile_merge.last = None;
        self.mobile_merge.active = Some(Running { job, cancelled, progress, result: receiver });
        self.mobile_merge_poll()
    }

    fn mobile_merge_poll(&mut self) -> Reply<Value> {
        let Some(job) = self.mobile_merge.active.as_ref() else {
            return Ok(self.mobile_merge.last.clone().unwrap_or(json!({"state":"idle"})));
        };
        let outcome = match job.result.try_recv() {
            Ok(result) => result,
            Err(mpsc::TryRecvError::Empty) => {
                return Ok(json!({
                    "state":if job.cancelled.load(Ordering::Relaxed) {"cancelling"} else {"running"},
                    "progress":job.progress.load(Ordering::Relaxed),
                }));
            }
            Err(mpsc::TryRecvError::Disconnected) => Err("merge worker exited".into()),
        };
        let job = self.mobile_merge.active.take().ok_or("merge_start")?;
        let terminal = if job.cancelled.load(Ordering::Relaxed) {
            json!({"state":"cancelled"})
        } else {
            match outcome {
                Ok(result) => {
                    let v = self.session.finish_merge(&job.job, result).and_then(|reply| {
                        self.session.persist()?;
                        Ok(reply)
                    });
                    match v {
                        Ok(v) => json!({"state":"ready","id":v["id"]}),
                        Err(_) => json!({"state":"failed","reason":"merge_write"}),
                    }
                }
                Err(_) => json!({"state":"failed","reason":"merge_no_result"}),
            }
        };
        self.mobile_merge.last = Some(terminal.clone());
        Ok(terminal)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    fn seed(lab: &mut Lab, name: &str, files: Vec<Vec<u8>>, ext: &str) -> Vec<(PhotoId, PathBuf, Vec<u8>)> {
        let mut rows = Vec::new();
        for (i, body) in files.into_iter().enumerate() {
            let path = lab.root.join("import").join(name).join(format!("{name}-{i}.{ext}"));
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, &body).unwrap();
            assert_eq!(lab.import(&json!({"paths":[path]})).unwrap()["imported"], 1);
            let id =
                lab.session.catalog.photos().find(|p| matches!(&p.source,Source::File {path: stored} if PathBuf::from(stored)==path)).unwrap().id;
            rows.push((id, path, body));
        }
        rows
    }

    fn launched(lab: &mut Lab, rows: &[(PhotoId, PathBuf, Vec<u8>)], kind: &str) -> Value {
        let ids = rows.iter().map(|(id, _, _)| id.0).collect::<Vec<_>>();
        let _ = lab
            .mobile_merge(
                "merge_start",
                &json!({"kind":kind,"ids":ids,"align":false,
            "autoSettings":false,"budgetMiB":600}),
            )
            .unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        let result = loop {
            let state = lab.mobile_merge("merge_status", &Value::Null).unwrap();
            if state["state"] != "running" {
                break state;
            }
            assert!(std::time::Instant::now() < deadline, "merge timed out");
            std::thread::sleep(std::time::Duration::from_millis(30));
        };
        for (_, path, bytes) in rows {
            assert_eq!(&std::fs::read(path).unwrap(), bytes);
        }
        result
    }

    fn assert_dng(lab: &mut Lab, state: &Value) {
        assert_eq!(state["state"], "ready", "{state}");
        let id = state["id"].as_u64().unwrap();
        let photo = lab.session.catalog.photo(PhotoId(id)).unwrap();
        assert!(photo.file_name.ends_with(".dng"));
        let actual = lab.action("render", &json!({"id":id,"edge":640})).unwrap();
        assert!(actual.starts_with(&[0xff, 0xd8]));
    }

    #[test]
    fn phone_merges_real_hdr_and_panorama_and_never_replaces_inputs() {
        let root = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(root.path()).unwrap();
        let raw = lightcraft_merge::synth::bracket_dngs(480, 320, &[-2.0, 0.0, 2.0]).unwrap();
        let bracket = seed(&mut lab, "bracket", raw, "dng");
        let state = launched(&mut lab, &bracket, "hdr");
        assert_dng(&mut lab, &state);
        let views = lightcraft_merge::synth::pano_views(480, 360, 420.0, &[-25.0, 0.0, 25.0]).unwrap();
        let bytes = views
            .into_iter()
            .map(|img| {
                let rgb = Rgba8::from_fn(img.width, img.height, |x, y| {
                    let p = img.data[y * img.width + x];
                    let q = p.map(|v| (v.clamp(0.0, 1.0).powf(1.0 / 2.2) * 255.0).round() as u8);
                    [q[0], q[1], q[2], 255]
                });
                encode_png(&EncodeImage::rgba8(&rgb), &EncodeMeta::default()).unwrap()
            })
            .collect::<Vec<_>>();
        let pano = seed(&mut lab, "pano", bytes, "png");
        let state = launched(&mut lab, &pano, "panorama");
        assert_dng(&mut lab, &state);
        let duplicate = pano[0].0.0;
        assert_eq!(lab.mobile_merge("merge_start", &json!({"ids":[duplicate,duplicate]})), Err("merge_count"));
    }

    #[test]
    fn phone_merge_cancels_before_finishing_and_rejects_unscoped_inputs() {
        let root = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(root.path()).unwrap();
        let bytes = lightcraft_merge::synth::bracket_dngs(480, 320, &[-2.0, 0.0, 2.0]).unwrap();
        let records = seed(&mut lab, "cancel", bytes, "dng");
        let ids = records.iter().map(|(id, _, _)| id.0).collect::<Vec<_>>();
        let count = lab.session.catalog.photos().count();
        lab.mobile_merge("merge_start", &json!({"ids":ids,"align":false})).unwrap();
        lab.mobile_merge("merge_cancel", &Value::Null).unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        loop {
            let state = lab.mobile_merge("merge_status", &Value::Null).unwrap();
            if state["state"] == "cancelled" {
                break;
            }
            assert_eq!(state["state"], "cancelling");
            assert!(std::time::Instant::now() < deadline);
            std::thread::sleep(std::time::Duration::from_millis(15));
        }
        assert_eq!(lab.session.catalog.photos().count(), count);
        assert_eq!(lab.mobile_merge("merge_start", &json!({"ids":[ids[0],999990]})), Err("selection"));
    }
}
