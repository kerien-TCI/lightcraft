//! Native Develop panels reuse the engine's canonical controls and commands.
use lightcraft_catalog::PhotoId;
use lightcraft_develop::{DevelopSettings, Section, controls};
use serde_json::{Value, json};

use crate::{Lab, Reply};

/// Lens/RAW ML model execution is a separate explicit capability, never a dead slider.
pub(super) fn slider_allowed(key: &str, settings: &DevelopSettings) -> bool {
    key != "enhance.denoise" && controls::get(settings, key).is_some()
}

const EYES: &[&str] = &["light", "color", "detail", "effects", "optics", "geometry", "calibration", "lensBlur"];

fn normalized(p: &Value, name: &str) -> Reply<f64> {
    p[name].as_f64().filter(|x| (0.0..=1.0).contains(x)).ok_or("edit")
}

fn region(p: &Value) -> Reply<&str> {
    p["section"].as_str().filter(|s| EYES.contains(s)).ok_or("edit")
}

impl Lab {
    pub(super) fn mobile_controls(&mut self, id: PhotoId) -> Reply<Value> {
        self.select(id)?;
        let mut controls = self.session.execute("develop.controls", &json!({})).map_err(|_| "edit")?;
        let controls = controls.as_array_mut().ok_or("edit")?;
        controls.retain(|row| row["id"].as_str().is_some_and(|key| key != "enhance.denoise"));
        Ok(json!({"controls":controls}))
    }

    pub(super) fn advanced_values(&self, id: PhotoId, d: &DevelopSettings) -> Value {
        let has_lens = self.session.catalog.photo(id).is_some_and(|p| p.embedded_lens.is_some());
        let eyes: serde_json::Map<String, Value> = EYES.iter().map(|&s| (s.into(), json!(d.section_enabled(s)))).collect();
        json!({
            "eyes":eyes, "removeCA":d.optics.remove_ca, "embeddedLens":has_lens,
            "lensProfile":d.optics.lens_profile, "constrainCrop":d.geometry.constrain_crop,
            "hdr":d.hdr.enabled, "hotPixels":d.detail.nr_outliers, "blur":d.lens_blur.enabled,
            "shape":d.lens_blur.shape, "wbMode":d.wb.mode, "bw": d.treatment == lightcraft_develop::Treatment::Bw,
            "samples": d.point_colors.len(), "upright":d.geometry.upright,
        })
    }

    pub(super) fn advanced_edit(&mut self, id: PhotoId, p: &Value) -> Reply<Value> {
        let method = p["tool"].as_str().ok_or("edit")?;
        let boolean = || p["enabled"].as_bool().ok_or("edit");
        let mode = |choices: &[&str]| p["mode"].as_str().filter(|value| choices.contains(value)).ok_or("edit");
        let (cmd, args) = match method {
            "auto" => ("develop.auto", json!({"ids":[id.0]})),
            "wb" => ("develop.wb", json!({"mode":mode(&["asShot","auto","daylight","cloudy","shade","tungsten","fluorescent","flash"])?})),
            "white_sample" => ("develop.wbPick", json!({"x":normalized(p,"x")?, "y":normalized(p,"y")?})),
            "color_sample" => ("pointColor.pick", json!({"x":normalized(p,"x")?, "y":normalized(p,"y")?})),
            "color_delete" => ("pointColor.delete", json!({"index":p["index"].as_u64().filter(|n| *n < 8).ok_or("edit")?})),
            "treatment" => ("develop.treatment", json!({"bw":boolean()?})),
            "auto_bw" => ("develop.autoBwMix", json!({})),
            "hdr" => ("develop.hdr", json!({"enabled":boolean()?})),
            "perspective" => ("geometry.upright", json!({"mode":mode(&["off","auto","level","vertical","full"])?})),
            "toggle" => ("develop.sectionEnabled", json!({"section":region(p)?,"enabled":boolean()?})),
            "reset_section" => {
                let section: Section = serde_json::from_value(p["section"].clone()).map_err(|_| "edit")?;
                ("develop.resetSection", json!({"section":section}))
            }
            "remove_ca" | "lens_profile" | "constrain" | "hot_pixels" | "enable_blur" | "blur_shape" => {
                let mut next = (*self.session.develop_of(id).ok_or("selection")?).clone();
                let label = match method {
                    "remove_ca" => {
                        next.optics.remove_ca = boolean()?;
                        "Chromatic Aberration"
                    }
                    "lens_profile" => {
                        if !self.session.catalog.photo(id).is_some_and(|photo| photo.embedded_lens.is_some()) {
                            return Err("edit");
                        }
                        next.optics.lens_profile = boolean()?;
                        "Lens Profile"
                    }
                    "constrain" => {
                        next.geometry.constrain_crop = boolean()?;
                        "Constrain Crop"
                    }
                    "hot_pixels" => {
                        next.detail.nr_outliers = boolean()?;
                        "Remove Hot Pixels"
                    }
                    "enable_blur" => {
                        next.lens_blur.enabled = boolean()?;
                        if next.lens_blur.enabled && next.lens_blur.amount == 0.0 {
                            next.lens_blur.amount = 50.0;
                        }
                        "Lens Blur"
                    }
                    _ => {
                        next.lens_blur.shape = serde_json::from_value(json!(mode(&["auto", "round", "wide", "tall"])?)).map_err(|_| "edit")?;
                        next.lens_blur.area = None;
                        "Lens Blur Shape"
                    }
                };
                self.session.set_develop(id, next, label).map_err(|_| "edit")?;
                // set_develop() is a direct edit; persist just as a journaled engine command does.
                self.session.persist().map_err(|_| "edit")?;
                return self.settings(id);
            }
            _ => return Err("edit"),
        };
        self.session.execute(cmd, &args).map_err(|_| "edit")?;
        self.settings(id)
    }
}
