//! Smartphone canvas tools: convert bounded UI gestures into edits on the real engine.
//! No disk paths or unfiltered command names pass through this API.
use lightcraft_catalog::PhotoId;
use lightcraft_develop::{BrushStroke, LocalAdjustments, MAX_RED_EYES, Mask, MaskComponent, MaskOp, MaskShape, RedEye};
use lightcraft_engine::Session;
use lightcraft_engine::export::{ExportOptions, encode_rendered};
use serde_json::{Value, json};

type Reply<T> = Result<T, &'static str>;

pub(super) fn handles(op: &str) -> bool {
    matches!(
        op,
        "canvas_info"
            | "canvas_source"
            | "canvas_curve"
            | "canvas_mask"
            | "canvas_mask_adjust"
            | "canvas_mask_delete"
            | "canvas_mask_effect"
            | "canvas_mask_component"
            | "canvas_eyes"
            | "canvas_crop"
            | "canvas_remove"
            | "canvas_guide"
            | "canvas_lens_brush"
    )
}

pub(super) fn mutates(op: &str) -> bool {
    !matches!(op, "canvas_info" | "canvas_source")
}

fn number(v: &Value, lo: f64, hi: f64) -> Reply<f64> {
    let v = v.as_f64().ok_or("edit")?;
    if v.is_finite() && (lo..=hi).contains(&v) { Ok(v) } else { Err("edit") }
}

fn pair(v: &Value) -> Reply<[f64; 2]> {
    if v.as_array().is_none_or(|a| a.len() != 2) {
        return Err("edit");
    }
    Ok([number(&v[0], 0., 1.)?, number(&v[1], 0., 1.)?])
}

fn point([x, y]: [f64; 2]) -> Value {
    json!({"x": x, "y": y})
}

fn curves(s: &mut Session, p: &Value) -> Reply<()> {
    let set = p["channels"].as_object().filter(|o| !o.is_empty()).ok_or("edit")?;
    let mut changes = serde_json::Map::new();
    for (channel, samples) in set {
        if !matches!(channel.as_str(), "master" | "red" | "green" | "blue") {
            return Err("edit");
        }
        let samples = samples.as_array().filter(|a| a.len() <= 64 && a.len() != 1).ok_or("edit")?;
        let points = samples.iter().map(pair).collect::<Reply<Vec<_>>>()?;
        if !points.windows(2).all(|w| w[0][0] < w[1][0]) {
            return Err("edit");
        }
        changes.insert(channel.clone(), json!(points.into_iter().map(point).collect::<Vec<_>>()));
    }
    execute(s, "develop.merge", &json!({"settings": {"curve": changes}, "label": "Tone Curve"}))
}

fn list_masks(d: &lightcraft_develop::DevelopSettings) -> Vec<Value> {
    d.masks
        .iter()
        .map(|m| {
            let components = m
                .components
                .iter()
                .map(|c| {
                    json!({
                        "operation":c.op,"invert":c.invert,"name":c.name,
                        "type":match c.shape {
                            MaskShape::Brush {..} => "brush", MaskShape::Linear {..} => "linear",
                            MaskShape::Radial {..} => "radial", MaskShape::LuminanceRange {..} => "luminance",
                            MaskShape::Sky => "sky", MaskShape::Subject => "subject", MaskShape::Background => "background",
                            _ => "other"
                        }
                    })
                })
                .collect::<Vec<_>>();
            json!({"id":m.id,"name":m.name,"visible":m.visible,"invert":m.invert,"adjust":m.adjust,"components":components,"refine":m.refine})
        })
        .collect()
}

fn info(s: &mut Session, id: PhotoId) -> Reply<Value> {
    let d = s.develop_of(id).ok_or("selection")?;
    let c = &d.curve;
    let pts = |points: Value| points.as_array().map(|items| items.iter().map(|p| json!([p["x"], p["y"]])).collect::<Vec<_>>()).unwrap_or_default();
    let r = d.crop.geometry.rect;
    let guides = d
        .geometry
        .guides
        .iter()
        .map(|(a, b)| {
            let a = coord([a.x, a.y], d.crop.flip_h, d.crop.flip_v);
            let b = coord([b.x, b.y], d.crop.flip_h, d.crop.flip_v);
            [a[0], a[1], b[0], b[1]]
        })
        .collect::<Vec<_>>();
    let effects = s
        .execute("mask.effects", &Value::Null)
        .ok()
        .and_then(|catalog| {
            catalog["effects"].as_array().map(|items| items.iter().filter_map(|v| v["name"].as_str().map(str::to_owned)).collect::<Vec<_>>())
        })
        .unwrap_or_default();
    Ok(json!({
        "effects":effects,
        "guides":guides,
        "lensStrokes":{"focus": d.lens_blur.focus_strokes.len(), "blur": d.lens_blur.blur_strokes.len()},
        "channels": {
            "master":pts(json!(c.master)), "red":pts(json!(c.red)),
            "green":pts(json!(c.green)), "blue":pts(json!(c.blue))
        },
        "crop":{"rect":[r.x0,r.y0,r.x1,r.y1],"angle":d.crop.geometry.angle, "flipH":d.crop.flip_h,"flipV":d.crop.flip_v},
        "masks":list_masks(&d),
        "spots":d.spots.len(),
        "redEyes":d.red_eye.iter().map(|e| {
            let xy=coord([e.center.x,e.center.y],d.crop.flip_h,d.crop.flip_v);
            json!({"center":xy,"rx":e.rx,"ry":e.ry,"pet":e.pet,"pupilSize":e.pupil_size,"darken":e.darken,"catchlight":e.catchlight.is_some()})
        }).collect::<Vec<_>>()
    }))
}

fn steps(p: &Value) -> Reply<Vec<[f64; 2]>> {
    let items = p["points"].as_array().filter(|a| !a.is_empty() && a.len() <= 512).ok_or("edit")?;
    items.iter().map(pair).collect::<Reply<Vec<_>>>()
}

fn execute(s: &mut Session, cmd: &str, values: &Value) -> Reply<()> {
    s.execute(cmd, values).map(|_| ()).map_err(|_| "edit")
}

fn coord([mut x, mut y]: [f64; 2], flip_h: bool, flip_v: bool) -> [f64; 2] {
    if flip_h {
        x = 1. - x;
    }
    if flip_v {
        y = 1. - y;
    }
    [x, y]
}

fn local_values(p: &Value) -> Reply<Value> {
    let vals = p["values"].as_object().filter(|v| !v.is_empty()).ok_or("edit")?;
    let mut out = serde_json::Map::new();
    for (key, v) in vals {
        let (lo, hi) = match key.as_str() {
            "exposure" => (-4., 4.),
            "temp" | "tint" | "contrast" | "highlights" | "shadows" | "whites" | "blacks" | "texture" | "clarity" | "dehaze" | "hue"
            | "saturation" | "sharpness" | "noise" | "moire" | "defringe" => (-100., 100.),
            "lens_blur" | "lens_blur_boost" | "lens_blur_transition" | "color_sat" => (0., 100.),
            "amount" => (0., 200.),
            "color_hue" => (0., 360.),
            _ => return Err("edit"),
        };
        out.insert(key.clone(), json!(number(v, lo, hi)?));
    }
    Ok(Value::Object(out))
}

fn shape(current: &lightcraft_develop::DevelopSettings, p: &Value) -> Reply<MaskShape> {
    let coordinates = |xy| coord(xy, current.crop.flip_h, current.crop.flip_v);
    let shape = match p["shape"].as_str().ok_or("edit")? {
        "linear" => {
            let (a, b) = (pair(&p["start"])?, pair(&p["end"])?);
            if (a[0] - b[0]).hypot(a[1] - b[1]) < 0.015 {
                return Err("edit");
            }
            json!({"kind":"linear","start":point(coordinates(a)),"end":point(coordinates(b))})
        }
        "radial" => json!({
            "kind":"radial","center":point(coordinates(pair(&p["center"])?)),
            "rx":number(&p["rx"], 0.005, 1.)?,"ry":number(&p["ry"], 0.005, 1.)?,
            "angle":0.,"feather":number(&p["feather"], 0., 100.)?,"invert":false
        }),
        "brush" => {
            let pts = steps(p)?.into_iter().map(coordinates).map(point).collect::<Vec<_>>();
            let stroke: BrushStroke = serde_json::from_value(json!({
                "points":pts,"size":number(&p["size"], 0.005, 0.3)?,"feather":number(&p["feather"], 0., 100.)?
            }))
            .map_err(|_| "edit")?;
            json!({"kind":"brush","strokes":[stroke]})
        }
        "luminance" => {
            let lo = number(&p["lo"], 0., 1.)?;
            let hi = number(&p["hi"], lo, 1.)?;
            json!({"kind":"luminanceRange","lo":lo,"hi":hi,"lo_feather":0.1,"hi_feather":0.1})
        }
        kind @ ("sky" | "subject" | "background") => json!({"kind":kind}),
        _ => return Err("edit"),
    };
    serde_json::from_value(shape).map_err(|_| "edit")
}

fn mask_op(p: &Value) -> Reply<MaskOp> {
    match p.as_str() {
        Some("add") => Ok(MaskOp::Add),
        Some("subtract") => Ok(MaskOp::Subtract),
        Some("intersect") => Ok(MaskOp::Intersect),
        _ => Err("edit"),
    }
}

fn new_mask(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    let current = s.develop_of(id).ok_or("selection")?;
    let shape = shape(&current, p)?;
    if !p["mask"].is_null() {
        let target = p["mask"].as_u64().and_then(|v| u32::try_from(v).ok()).ok_or("edit")?;
        let mut to_add = (*current).clone();
        // Normalize a separately drawn component to the same on-photo space as old ones.
        to_add.masks = vec![Mask {
            on_photo: false,
            components: vec![MaskComponent { name: None, op: mask_op(&p["combine"])?, invert: false, shape }],
            ..Default::default()
        }];
        s.attach_masks(id, &current, &mut to_add);
        let c = to_add.masks.pop().and_then(|mut m| m.components.pop()).ok_or("edit")?;
        let mut d = (*current).clone();
        s.attach_masks(id, &current, &mut d);
        let mask = d.masks.iter_mut().find(|m| m.id == target).ok_or("edit")?;
        if mask.components.len() >= 64 {
            return Err("edit");
        }
        mask.components.push(c);
        return execute(s, "develop.merge", &json!({"settings":{"masks":d.masks},"label":"Combine Mask"}));
    }
    let adjust: LocalAdjustments = serde_json::from_value(local_values(p)?).map_err(|_| "edit")?;
    let next_id = current.next_mask_id();
    let name = p["name"].as_str().map(str::trim).filter(|n| !n.is_empty() && n.chars().count() <= 80).ok_or("edit")?;
    let mut d = (*current).clone();
    if d.masks.len() >= 512 {
        return Err("edit");
    }
    d.masks.push(Mask {
        id: next_id,
        name: name.into(),
        adjust,
        on_photo: false,
        components: vec![MaskComponent { name: None, op: Default::default(), invert: false, shape }],
        ..Default::default()
    });
    s.attach_masks(id, &current, &mut d);
    execute(s, "develop.merge", &json!({"settings":{"masks":d.masks},"label":"Add Mask"}))
}

fn existing_mask(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    let target = p["mask"].as_u64().and_then(|i| u32::try_from(i).ok()).ok_or("edit")?;
    let before = s.develop_of(id).ok_or("selection")?;
    let mut masks = before.masks.clone();
    let mask = masks.iter_mut().find(|m| m.id == target).ok_or("edit")?;
    let vals = local_values(p)?;
    let mut adjust = serde_json::to_value(mask.adjust).map_err(|_| "edit")?;
    lightcraft_develop::presets::deep_merge(&mut adjust, &vals);
    mask.adjust = serde_json::from_value(adjust).map_err(|_| "edit")?;
    if let Some(visible) = p["visible"].as_bool() {
        mask.visible = visible;
    }
    if let Some(invert) = p["invert"].as_bool() {
        mask.invert = invert;
    }
    if !p["refine"].is_null() {
        mask.refine = number(&p["refine"], 0., 100.)?;
    }
    if let Some(name) = p["name"].as_str().map(str::trim).filter(|n| !n.is_empty() && n.chars().count() <= 80) {
        mask.name = name.into();
    }
    execute(s, "develop.merge", &json!({"settings":{"masks":masks},"label":"Edit Mask"}))
}

fn crop(s: &mut Session, p: &Value) -> Reply<()> {
    if p["reset"].as_bool() == Some(true) {
        return execute(s, "crop.reset", &Value::Null);
    }
    let angle = number(&p["angle"], -45., 45.)?;
    if p["rect"].is_null() {
        return execute(s, "crop.set", &json!({"angle": angle}));
    }
    let rect = p["rect"].as_array().filter(|a| a.len() == 4).ok_or("edit")?;
    // There may be valid positions slightly outside the normalized square after straightening.
    let values = rect.iter().map(|v| number(v, -1., 2.)).collect::<Reply<Vec<_>>>()?;
    execute(s, "crop.set", &json!({"rect":values, "angle":angle}))
}

fn remove(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    if p["deleteLast"].as_bool() == Some(true) {
        let n = s.develop_of(id).ok_or("selection")?.spots.len();
        return execute(s, "spot.delete", &json!({"index":n.checked_sub(1).ok_or("edit")?}));
    }
    let mode = p["mode"].as_str().filter(|m| matches!(*m, "remove" | "heal" | "clone" | "fill")).ok_or("edit")?;
    let d = s.develop_of(id).ok_or("selection")?;
    let ps = steps(p)?.into_iter().map(|xy| coord(xy, d.crop.flip_h, d.crop.flip_v)).collect::<Vec<_>>();
    execute(
        s,
        "spot.add",
        &json!({
            "mode":mode,"points":ps,"size":number(&p["size"], 0.005, 0.3)?,"feather":number(&p["feather"], 0., 100.)?,
            "opacity":number(&p["opacity"], 0., 100.)?
        }),
    )
}

fn guide(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    let d = s.develop_of(id).ok_or("selection")?;
    let guides = p["guides"].as_array().filter(|a| a.len() <= 4).ok_or("edit")?;
    let mut out = Vec::with_capacity(guides.len());
    for v in guides {
        let a = v.as_array().filter(|a| a.len() == 4).ok_or("edit")?;
        let x = coord([number(&a[0], -1., 2.)?, number(&a[1], -1., 2.)?], d.crop.flip_h, d.crop.flip_v);
        let y = coord([number(&a[2], -1., 2.)?, number(&a[3], -1., 2.)?], d.crop.flip_h, d.crop.flip_v);
        if (x[0] - y[0]).hypot(x[1] - y[1]) < 0.018 {
            return Err("edit");
        }
        out.push([x[0], x[1], y[0], y[1]]);
    }
    execute(s, "geometry.guides", &json!({"guides":out}))
}

fn lens_brush(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    let mode = p["mode"].as_str().filter(|m| matches!(*m, "focus" | "blur")).ok_or("edit")?;
    if p["reset"].as_bool() == Some(true) {
        return execute(s, "lensBlur.brushReset", &json!({"mode":mode}));
    }
    let d = s.develop_of(id).ok_or("selection")?;
    let ps = steps(p)?.into_iter().map(|xy| coord(xy, d.crop.flip_h, d.crop.flip_v)).collect::<Vec<_>>();
    execute(
        s,
        "lensBlur.brushStroke",
        &json!({
            "mode":mode,"points":ps,"size":number(&p["size"],0.005,0.3)?,"feather":number(&p["feather"],0.,100.)?,
            "amount":number(&p["amount"],1.,100.)?,"erase":p["erase"].as_bool().unwrap_or(false)
        }),
    )
}

fn mask_component(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    let d = s.develop_of(id).ok_or("selection")?;
    let mask = p["mask"].as_u64().and_then(|i| u32::try_from(i).ok()).ok_or("edit")?;
    let idx = p["index"].as_u64().and_then(|i| usize::try_from(i).ok()).ok_or("edit")?;
    let m = d.masks.iter().find(|x| x.id == mask).ok_or("edit")?;
    if idx >= m.components.len() {
        return Err("edit");
    }
    let action = p["action"].as_str().ok_or("edit")?;
    let req = match action {
        "op" if idx > 0 => json!({"id":mask,"component":idx,"action":action,"op":mask_op(&p["op"])?}),
        "invert" | "delete" => json!({"id":mask,"component":idx,"action":action}),
        "duplicate" if m.components.len() < 64 => json!({"id":mask,"component":idx,"action":action}),
        _ => return Err("edit"),
    };
    execute(s, "mask.component", &req)
}

fn red_eye(s: &mut Session, id: PhotoId, p: &Value) -> Reply<()> {
    let d = s.develop_of(id).ok_or("selection")?;
    let mut eyes = d.red_eye.clone();
    let action = p["action"].as_str().ok_or("edit")?;
    if action == "delete" {
        let i = p["index"].as_u64().ok_or("edit")?;
        return execute(s, "redeye.delete", &json!({"index":i}));
    }
    let label = match action {
        "add" if eyes.len() < MAX_RED_EYES => {
            let pet = p["pet"].as_bool().unwrap_or(false);
            let center = coord(pair(&p["center"])?, d.crop.flip_h, d.crop.flip_v);
            let correction: RedEye = serde_json::from_value(json!({
                "center":point(center),"rx":number(&p["rx"],0.001,0.5)?,"ry":number(&p["ry"],0.001,0.5)?,
                "pupil_size":number(&p["pupilSize"],0.,100.)?,"darken":number(&p["darken"],0.,100.)?,"pet":pet,
                "catchlight":if pet && p["catchlight"].as_bool()==Some(true) {point([-0.35,-0.35])} else {Value::Null}
            }))
            .map_err(|_| "edit")?;
            eyes.push(correction);
            if pet { "Pet Eye" } else { "Red Eye" }
        }
        "adjust" => {
            let i = p["index"].as_u64().and_then(|i| usize::try_from(i).ok()).ok_or("edit")?;
            let e = eyes.get_mut(i).ok_or("edit")?;
            e.pupil_size = number(&p["pupilSize"], 0., 100.)?;
            e.darken = number(&p["darken"], 0., 100.)?;
            if e.pet
                && let Some(on) = p["catchlight"].as_bool()
            {
                e.catchlight = if on { e.catchlight.or_else(|| serde_json::from_value(point([-0.35, -0.35])).ok()) } else { None };
            }
            if e.pet { "Adjust Pet Eye" } else { "Adjust Red Eye" }
        }
        _ => return Err("edit"),
    };
    execute(s, "develop.merge", &json!({"settings":{"red_eye":eyes},"label":label}))
}

pub(super) fn run(s: &mut Session, id: PhotoId, op: &str, p: &Value) -> Reply<Vec<u8>> {
    match op {
        "canvas_info" => return info(s, id).map(|v| v.to_string().into_bytes()),
        "canvas_source" => {
            // Crop and masks need a photo-space source, not the already cropped loupe.
            let mut job = s.render_job(id, 960, 960, false, false).ok_or("selection")?;
            if p["tool"] == "guide" || p["tool"] == "eyes" {
                // The command stores guides before the perspective transform; render that space
                // without changing the catalogue, retaining both the lens correction and flips.
                let settings = std::sync::Arc::make_mut(&mut job.settings);
                settings.geometry = Default::default();
                if p["tool"] == "eyes" {
                    // The detector locates the pupil on unwarped photo pixels.
                    settings.optics = Default::default();
                }
            }
            let r = job.run();
            let r = r.rendered.map_err(|error| if error.contains("High Efficiency") || error.contains("HE★") { "nef_he" } else { "unsupported" })?;
            return encode_rendered(&r, &ExportOptions { quality: 82, ..Default::default() }, None).map_err(|_| "unsupported");
        }
        "canvas_curve" => curves(s, p)?,
        "canvas_mask" => new_mask(s, id, p)?,
        "canvas_mask_adjust" => existing_mask(s, id, p)?,
        "canvas_mask_delete" => {
            let i = p["mask"].as_u64().and_then(|i| u32::try_from(i).ok()).ok_or("edit")?;
            execute(s, "mask.delete", &json!({"id":i}))?;
        }
        "canvas_mask_effect" => {
            let mask = p["mask"].as_u64().and_then(|i| u32::try_from(i).ok()).ok_or("edit")?;
            let name = p["name"].as_str().map(str::trim).filter(|name| !name.is_empty() && name.chars().count() <= 160).ok_or("edit")?;
            execute(s, "mask.applyEffect", &json!({"id":mask,"name":name}))?;
        }
        "canvas_mask_component" => mask_component(s, id, p)?,
        "canvas_eyes" => red_eye(s, id, p)?,
        "canvas_crop" => crop(s, p)?,
        "canvas_remove" => remove(s, id, p)?,
        "canvas_guide" => guide(s, id, p)?,
        "canvas_lens_brush" => lens_brush(s, id, p)?,
        _ => return Err("unavailable"),
    }
    Ok(Vec::new())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Lab;
    use lightcraft_codecs::{DecodeOptions, EncodeImage, EncodeMeta, decode, encode_png};
    use lightcraft_raster::Rgba8;

    #[test]
    fn canvas_workflows_change_the_render_and_keep_edits_on_reopen() {
        let d = tempfile::tempdir().unwrap();
        let path = d.path().join("import/fixture.png");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let img = Rgba8::from_fn(240, 160, |x, y| [60 + (x / 3) as u8, 48 + (y / 2) as u8, 77, 255]);
        let png = encode_png(&EncodeImage::rgba8(&img), &EncodeMeta::default()).unwrap();
        std::fs::write(&path, &png).unwrap();

        let mut lab = Lab::start(d.path()).unwrap();
        assert_eq!(lab.import(&json!({"paths":[path]})).unwrap()["imported"], 1);
        let id = lab.session.catalog.photos().next().unwrap().id.0;
        let args = json!({"id":id,"edge":320});
        let source = lab.action("render", &args).unwrap();

        // Channel-specific points are applied atomically; argument validation protects the catalogue.
        assert_eq!(lab.action("canvas_curve", &json!({"id":id,"channels":{"master":[[0.5,2.0]]}})), Err("edit"));
        lab.action("canvas_curve", &json!({"id":id,"channels":{"master":[[0,0],[0.45,0.8],[1,1]],"blue":[[0,0],[0.45,0.40],[1,1]]}})).unwrap();
        let curved = lab.action("render", &args).unwrap();
        assert_ne!(curved, source);
        let read_info = serde_json::from_slice::<Value>(&lab.action("canvas_info", &args).unwrap()).unwrap();
        assert_eq!(read_info["channels"]["master"][1], json!([0.45, 0.8]));

        // One undo removes its complete shape and adjustment together.
        lab.action(
            "canvas_mask",
            &json!({
                "id":id, "name":"Linear test", "shape":"linear", "start":[0.0,0.2],"end":[0.85,0.25],
                "values":{"exposure":-2.0,"contrast":20.0}
            }),
        )
        .unwrap();
        let softened = lab.action("render", &args).unwrap();
        assert_ne!(softened, curved);
        lab.action("undo", &args).unwrap();
        assert_eq!(lab.action("render", &args).unwrap(), curved);
        lab.action("redo", &args).unwrap();
        assert_eq!(lab.action("render", &args).unwrap(), softened);
        lab.action(
            "canvas_mask",
            &json!({
                "id":id,"name":"Brush test","shape":"brush","points":[[0.3,0.35],[0.45,0.55]],
                "size":0.045,"feather":60,"values":{"exposure":1.6}
            }),
        )
        .unwrap();
        lab.action(
            "canvas_mask",
            &json!({
                "id":id,"name":"Radial test","shape":"radial","center":[0.5,0.5],
                "rx":0.2,"ry":0.12,"feather":60,"values":{"exposure":0.25}
            }),
        )
        .unwrap();
        lab.action(
            "canvas_mask",
            &json!({
                "id":id,"name":"Lights","shape":"luminance","lo":0.3,"hi":0.8,"values":{"exposure":0.45}
            }),
        )
        .unwrap();

        lab.action("canvas_crop", &json!({"id":id,"rect":[0.1,0.1,0.62,0.9],"angle":-3.5})).unwrap();
        lab.action("canvas_remove", &json!({"id":id,"mode":"clone","points":[[0.3,0.3]],"size":0.025,"feather":50,"opacity":100})).unwrap();
        let uncut = lab.action("canvas_source", &args).unwrap();
        let edited = lab.action("render", &args).unwrap();
        let uncut = decode(&uncut, DecodeOptions::default()).unwrap();
        let edited = decode(&edited, DecodeOptions::default()).unwrap();
        assert!(edited.width < uncut.width);
        assert_eq!(std::fs::read(&path).unwrap(), png);

        lab.session.close_library().unwrap();
        drop(lab);
        let mut lab = Lab::start(d.path()).unwrap();
        let read_info = serde_json::from_slice::<Value>(&lab.action("canvas_info", &args).unwrap()).unwrap();
        assert_eq!(read_info["masks"].as_array().unwrap().len(), 4);
        assert_eq!(read_info["spots"], 1);
        assert_eq!(read_info["crop"]["angle"], -3.5);
        assert_eq!(read_info["channels"]["blue"][1], json!([0.45, 0.4]));
        lab.action("canvas_guide", &json!({"id":id,"guides":[[0.2,0.08,0.28,0.88],[0.8,0.08,0.72,0.89]]})).unwrap();
        let guide_source = lab.action("canvas_source", &json!({"id":id,"tool":"guide"})).unwrap();
        let actual = lab.action("canvas_source", &args).unwrap();
        assert_ne!(guide_source, actual);
        lab.action("canvas_lens_brush", &json!({"id":id,"mode":"focus","points":[[0.35,0.45]],"size":0.04,"feather":50,"amount":100})).unwrap();
        let read_info = serde_json::from_slice::<Value>(&lab.action("canvas_info", &args).unwrap()).unwrap();
        assert_eq!(read_info["guides"].as_array().unwrap().len(), 2);
        assert_eq!(read_info["lensStrokes"]["focus"], 1);
    }

    #[test]
    fn offline_combined_masks_and_eye_repairs_change_local_pixels() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("import/local.png");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        let img = Rgba8::from_fn(240, 160, |x, y| {
            if (x as f64 - 60.).hypot(y as f64 - 100.) < 10. {
                [235, 20, 22, 255]
            } else if (x as f64 - 155.).hypot(y as f64 - 100.) < 9. {
                [232, 230, 40, 255]
            } else if y < 50 {
                [80, 143, 235, 255]
            } else {
                [100, 100, 101, 255]
            }
        });
        let png = encode_png(&EncodeImage::rgba8(&img), &EncodeMeta::default()).unwrap();
        std::fs::write(&path, &png).unwrap();
        let mut lab = Lab::start(dir.path()).unwrap();
        assert_eq!(lab.import(&json!({"paths":[path]})).unwrap()["imported"], 1);
        let id = lab.session.catalog.photos().next().unwrap().id.0;
        let args = json!({"id":id,"edge":320});
        let pixel = |p: &lightcraft_codecs::Decoded, x: usize, y: usize| p.image.data[y * p.image.width + x];
        let decode_jpeg = |bytes: Vec<u8>| decode(&bytes, DecodeOptions::default()).unwrap();
        let initial = decode_jpeg(lab.action("render", &args).unwrap());
        let payload = json!({"id":id,"action":"add","center":[0.25,0.625],"rx":0.085,"ry":0.075,
            "pupilSize":65,"darken":60});
        lab.action("canvas_eyes", &payload).unwrap();
        let red = decode_jpeg(lab.action("render", &args).unwrap());
        let (a, b) = (pixel(&initial, 60, 100), pixel(&red, 60, 100));
        assert!(a[0] - a[1] > 0.3 && b[0] - b[1] < 0.5 * (a[0] - a[1]), "{a:?} -> {b:?}");
        lab.action(
            "canvas_eyes",
            &json!({"id":id,"action":"add","center":[155.0/240.0,0.625],
            "rx":0.085,"ry":0.075,"pet":true,"catchlight":true,"pupilSize":55,"darken":62}),
        )
        .unwrap();
        let both = decode_jpeg(lab.action("render", &args).unwrap());
        assert!(pixel(&both, 155, 100)[1] < pixel(&red, 155, 100)[1] * 0.7);
        let state = serde_json::from_slice::<Value>(&lab.action("canvas_info", &args).unwrap()).unwrap();
        assert_eq!(state["redEyes"][1]["catchlight"], true);
        lab.action("canvas_eyes", &json!({"id":id,"action":"adjust","index":1,"pupilSize":45,"darken":45,"catchlight":false})).unwrap();
        lab.action("canvas_mask", &json!({"id":id,"name":"Sky","shape":"sky","values":{"exposure":-2.0}})).unwrap();
        let dark = decode_jpeg(lab.action("render", &args).unwrap());
        let sb = pixel(&both, 111, 20)[2];
        let sd = pixel(&dark, 111, 20)[2];
        assert!(sd < sb, "{sd} should be darker than {sb}");
        let state = serde_json::from_slice::<Value>(&lab.action("canvas_info", &args).unwrap()).unwrap();
        let mask = state["masks"][0]["id"].as_u64().unwrap();
        lab.action(
            "canvas_mask",
            &json!({"id":id,"shape":"radial","center":[111.0/240.0,20.0/160.0],
            "rx":0.25,"ry":0.15,"feather":25.0,"mask":mask,"combine":"subtract"}),
        )
        .unwrap();
        let mixed = decode_jpeg(lab.action("render", &args).unwrap());
        assert!(pixel(&mixed, 111, 20)[2] > sd);
        lab.action("canvas_mask_component", &json!({"id":id,"mask":mask,"index":1,"action":"op","op":"intersect"})).unwrap();
        lab.action("canvas_mask_component", &json!({"id":id,"mask":mask,"index":1,"action":"delete"})).unwrap();
        lab.action("canvas_mask", &json!({"id":id,"shape":"subject","name":"Subject","values":{"saturation":-40.0}})).unwrap();
        lab.action("canvas_mask", &json!({"id":id,"shape":"background","name":"Background","values":{"saturation":-25.0}})).unwrap();
        lab.session.close_library().unwrap();
        drop(lab);
        let mut reopened = Lab::start(dir.path()).unwrap();
        let state = serde_json::from_slice::<Value>(&reopened.action("canvas_info", &args).unwrap()).unwrap();
        assert_eq!(state["masks"][1]["components"][0]["type"], "subject");
        assert_eq!(state["masks"][2]["components"][0]["type"], "background");
        assert_eq!(state["redEyes"][1]["pupilSize"], 45.0);
        assert_eq!(std::fs::read(&path).unwrap(), png);
    }
}
