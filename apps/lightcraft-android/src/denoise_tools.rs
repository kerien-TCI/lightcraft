//! Android surface for LightCraft's built-in CPU RAW denoiser.
//! The upstream core is compiled even without the optional external-model/GPU runtime.

use lightcraft_catalog::PhotoId;
use lightcraft_engine::denoise::PhotoState;
use serde_json::{Value, json};

use super::{Lab, Reply};

/// Limit mobile sensor scratch allocations without changing the desktop engine. This covers the
/// usual APS-C and full-frame workflows (including up to 61 MP), not every medium-format camera.
const MAX_MOBILE_PIXELS: u64 = 65_000_000;

impl Lab {
    pub(super) fn setup_mobile_denoise(&mut self) -> Reply<()> {
        // Use a new private directory. No user-provided model/path is accepted by this bridge.
        let directory = self.root.join("BuiltInNoiseReduction");
        if !directory.join("settings.json").is_file() {
            self.session.set_denoise_models_dir(Some(directory));
            self.session.execute("denoise.settings", &json!({"auto":false,"cacheGb":1,"threads":1,"runOn":"cpu"})).map_err(|_| "denoise")?;
        } else if self.session.denoise_auto() {
            // After restart the engine has not yet read the persisted mobile configuration.
            self.session.set_denoise_models_dir(Some(directory));
        }
        Ok(())
    }

    fn pump_mobile_denoise(&mut self) -> Reply<()> {
        self.session.execute("denoise.pump", &json!({"pace":"light"})).map(|_| ()).map_err(|_| "denoise")
    }

    fn noise_status(&self, id: PhotoId) -> Reply<Value> {
        let photo = self.session.catalog.photo(id).ok_or("selection")?;
        let pixels = u64::from(photo.width) * u64::from(photo.height);
        let mut v = match self.session.denoise_photo_state(id) {
            _ if pixels > MAX_MOBILE_PIXELS => json!({"state":"tooLarge","pixels":pixels}),
            PhotoState::NotApplicable => json!({"state":"notApplicable","pixels":pixels}),
            PhotoState::Ready => json!({"state":"ready","pixels":pixels}),
            PhotoState::Queued { ahead } => json!({"state":"queued","ahead":ahead,"pixels":pixels}),
            PhotoState::Running { done, total } => json!({"state":"running","done":done,"total":total,"pixels":pixels}),
            PhotoState::Failed { unsupported, .. } => json!({"state":if unsupported { "notApplicable" } else { "failed" },"pixels":pixels}),
            PhotoState::Idle => json!({"state":"idle","pixels":pixels}),
        };
        let d = self.session.develop_of(id).ok_or("selection")?;
        v["enabled"] = json!(d.enhance.denoise_enabled());
        v["amount"] = json!(d.enhance.denoise.round().clamp(0.0, 100.0) as u32);
        Ok(v)
    }

    pub(super) fn mobile_denoise(&mut self, op: &str, p: &Value) -> Reply<Value> {
        let id = self.valid_photo(p)?;
        self.setup_mobile_denoise()?;
        self.pump_mobile_denoise()?;
        let status = self.noise_status(id)?;
        let state = status["state"].as_str().unwrap_or("failed");
        match op {
            "noise_status" => return Ok(status),
            "noise_prepare" if matches!(state, "idle" | "failed") => {
                self.session.execute("denoise.queue", &json!({"ids":[id.0],"retry":true})).map_err(|_| "denoise")?;
            }
            "noise_prepare" => return Ok(status),
            "noise_cancel" => {
                self.session.execute("denoise.cancel", &json!({"id":id.0})).map_err(|_| "denoise")?;
            }
            "noise_apply" => {
                let enable = p["enabled"].as_bool().ok_or("denoise")?;
                let amount = p["amount"].as_u64().filter(|n| (1..=100).contains(n)).ok_or("denoise")?;
                if (enable && state != "ready") || (!enable && !status["enabled"].as_bool().unwrap_or(false)) {
                    return Err("denoise");
                }
                // Merge the amount and flag in one history step after the CPU result exists,
                // so cancelling the preparation never changes a recipe.
                self.select(id)?;
                self.ensure_history_baseline(id)?;
                let patch = if enable { json!({"denoise":amount, "denoise_on":true}) } else { json!({"denoise_on":false}) };
                self.session.execute("develop.merge", &json!({"settings":{"enhance":patch},"label":"AI Denoise"})).map_err(|_| "denoise")?;
            }
            _ => return Err("unavailable"),
        }
        self.pump_mobile_denoise()?;
        self.noise_status(id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    // Small deterministic noisy Bayer DNG, generated here so tests run without a download.
    fn bayer_fixture() -> Vec<u8> {
        let w = 80u32;
        let h = 64u32;
        let mut random = 0x117d_5241u32;
        let mut image = Vec::with_capacity((w * h * 2) as usize);
        for y in 0..h {
            for x in 0..w {
                random = random.wrapping_mul(1664525).wrapping_add(1013904223);
                let pattern = 1800 + x * 74 + y * 38 + (x / 17 % 2) * 2000;
                let value = (pattern + ((random >> 24) << 2)) as u16;
                image.extend(value.to_le_bytes());
            }
        }
        let long = |v: u32| v.to_le_bytes().to_vec();
        let short = |v: u16| v.to_le_bytes().to_vec();
        let rational = |v: i32| [v.to_le_bytes(), 1i32.to_le_bytes()].concat();
        let mut entries: Vec<(u16, u16, u32, Vec<u8>)> = vec![
            (256, 4, 1, long(w)),
            (257, 4, 1, long(h)),
            (258, 3, 1, short(16)),
            (259, 3, 1, short(1)),
            (262, 3, 1, short(32803)), // CFA
            (273, 4, 1, long(0)),      // later written to the strip offset
            (277, 3, 1, short(1)),
            (278, 4, 1, long(h)),
            (279, 4, 1, long(image.len() as u32)),
            (33421, 3, 2, [short(2), short(2)].concat()), // CFARepeatPatternDim
            (33422, 1, 4, vec![0, 1, 1, 2]),              // RGGB
            (50706, 1, 4, vec![1, 4, 0, 0]),              // DNGVersion
            (50707, 1, 4, vec![1, 1, 0, 0]),              // DNGBackwardVersion
            (50708, 2, 11, b"Mobile RAW\0".to_vec()),
            (50714, 3, 1, short(256)),
            (50717, 4, 1, long(16383)),
            (50721, 10, 9, [1, 0, 0, 0, 1, 0, 0, 0, 1].into_iter().flat_map(rational).collect()),
            (50728, 5, 3, [1, 1, 1].into_iter().flat_map(rational).collect()),
            (50778, 3, 1, short(21)), // illuminant D65
        ];
        let base = (8 + 2 + entries.len() * 12 + 4) as u32;
        let extra = |n: usize| if n > 4 { n + n % 2 } else { 0 };
        let strip = base + entries.iter().map(|e| extra(e.3.len()) as u32).sum::<u32>();
        entries[5].3 = long(strip);
        let mut bytes = b"II\x2a\0\x08\0\0\0".to_vec();
        bytes.extend((entries.len() as u16).to_le_bytes());
        let mut data = Vec::<u8>::new();
        for (key, kind, count, item) in entries {
            bytes.extend(key.to_le_bytes());
            bytes.extend(kind.to_le_bytes());
            bytes.extend(count.to_le_bytes());
            if item.len() > 4 {
                let offset = base + data.len() as u32;
                bytes.extend(offset.to_le_bytes());
                data.extend(&item);
                if item.len() % 2 != 0 {
                    data.push(0);
                }
            } else {
                bytes.extend(&item);
                bytes.resize(bytes.len() + 4 - item.len(), 0);
            }
        }
        bytes.extend([0; 4]); // no next IFD
        bytes.extend(data);
        bytes.extend(image);
        bytes
    }

    #[test]
    fn mobile_cpu_denoise_changes_render_and_preserves_original() {
        let folder = tempfile::tempdir().unwrap();
        let import = folder.path().join("import");
        std::fs::create_dir(&import).unwrap();
        let file = import.join("noise.dng");
        let original = bayer_fixture();
        std::fs::write(&file, &original).unwrap();

        let mut lab = Lab::start(folder.path()).unwrap();
        lab.import(&json!({"paths":[file]})).unwrap();
        let id = lab.session.catalog.photos().next().expect("Bayer DNG imported").id;
        let args = json!({"id":id.0,"edge":128});
        let before = lab.render(id, &args).unwrap();
        assert_eq!(lab.mobile_denoise("noise_status", &args).unwrap()["state"], "idle");
        lab.mobile_denoise("noise_prepare", &args).unwrap();
        let deadline = Instant::now() + Duration::from_secs(25);
        loop {
            let s = lab.mobile_denoise("noise_status", &args).unwrap();
            match s["state"].as_str().unwrap() {
                "ready" => break,
                "running" | "queued" if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
                state => panic!("Bayer denoise did not finish: {state}"),
            }
        }
        assert!(!lab.session.develop_of(id).unwrap().enhance.denoise_enabled(), "preparing doesn't enable the edit");
        assert_eq!(lab.render(id, &args).unwrap(), before, "cached preparation doesn't alter rendering");
        let on = json!({"id":id.0,"enabled":true,"amount":100});
        let s = lab.mobile_denoise("noise_apply", &on).unwrap();
        assert_eq!(s["state"], "ready");
        assert_eq!(s["enabled"], true);
        assert_eq!(s["amount"], 100);
        assert_ne!(lab.render(id, &args).unwrap(), before, "same RAW renders differently after applying denoise");
        lab.mobile_denoise("noise_apply", &json!({"id":id.0,"enabled":false,"amount":100})).unwrap();
        assert_eq!(lab.render(id, &args).unwrap(), before, "disabling restores the unfiltered look");
        lab.mobile_denoise("noise_apply", &on).unwrap();
        lab.session.close_library().unwrap();
        drop(lab);
        let mut reopened = Lab::start(folder.path()).unwrap();
        let s = reopened.mobile_denoise("noise_status", &args).unwrap();
        assert_eq!(s["state"], "ready", "existing cache is available after a process restart");
        assert_eq!(s["enabled"], true, "the applied edit survives reopening");
        assert_ne!(reopened.render(id, &args).unwrap(), before);
        assert_eq!(std::fs::read(file).unwrap(), original);
    }
}
