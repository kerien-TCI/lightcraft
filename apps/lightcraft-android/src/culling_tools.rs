//! Mobile culling over the desktop catalogue. IDs are validated separately from
//! the engine's current selection and no file operation is exposed.

use std::collections::HashSet;

use lightcraft_catalog::{MediaKind, Photo, PhotoId};
use serde_json::{Value, json};

use crate::{Lab, Reply};

const MAX_IDS: usize = 250;

fn available(photo: &Photo) -> bool {
    !photo.deleted && !photo.local && photo.kind != MediaKind::Video
}

impl Lab {
    pub(super) fn mobile_arrange_culling(&self, visible: &[PhotoId]) -> Vec<PhotoId> {
        self.session.catalog.arrange_stacks(visible)
    }

    pub(super) fn mobile_culling_row(&self, photo: &Photo, row: &mut Value) {
        row["copyName"] = json!(photo.copy_name);
        row["virtualCopy"] = json!(photo.copy_of.is_some());
        if let Some(stack) = self.session.catalog.stack_of(photo.id) {
            row["stack"] = json!({
                "id":stack.id.0,
                "position":stack.position(photo.id),
                "count":stack.photos.len(),
                "collapsed":stack.collapsed,
            });
        }
    }

    fn culling_ids(&self, args: &Value) -> Reply<Vec<PhotoId>> {
        let raw = args["ids"].as_array().filter(|ids| !ids.is_empty() && ids.len() <= MAX_IDS).ok_or("selection")?;
        let mut unique = HashSet::new();
        let mut ids = Vec::with_capacity(raw.len());
        for raw_id in raw {
            let id = PhotoId(raw_id.as_u64().ok_or("selection")?);
            if !self.session.catalog.photo(id).is_some_and(|p| available(p)) {
                return Err("selection");
            }
            if unique.insert(id) {
                ids.push(id);
            }
        }
        Ok(ids)
    }

    fn culling_command(&mut self, command: &str, args: &Value) -> Reply<Value> {
        let auto_write = std::mem::replace(&mut self.session.xmp.auto_write, false);
        let done = self.session.execute(command, args).map_err(|_| "edit");
        self.session.xmp.auto_write = auto_write;
        done
    }

    pub(super) fn mobile_culling(&mut self, action: &str, args: &Value) -> Reply<Value> {
        if matches!(action, "cull_expand_all" | "cull_collapse_all") {
            let command = if action == "cull_expand_all" { "stack.expandAll" } else { "stack.collapseAll" };
            return self.culling_command(command, &json!({}));
        }
        let ids = self.culling_ids(args)?;
        let top = args.get("top").map(|v| v.as_u64().map(PhotoId).filter(|p| ids.contains(p)).ok_or("selection")).transpose()?.unwrap_or(ids[0]);
        let encoded = ids.iter().map(|id| id.0).collect::<Vec<_>>();
        if action == "cull_info" {
            let catalogue = &self.session.catalog;
            return Ok(json!({
                "photos":ids.iter().filter_map(|id| catalogue.photo(*id)).map(|p| {
                    let mut row = json!({"id":p.id.0,"name":p.file_name,"rating":p.rating,"flag":p.flag});
                    self.mobile_culling_row(p,&mut row);
                    row
                }).collect::<Vec<_>>(),
                "hasStacks":catalogue.stacks().next().is_some(),
            }));
        }
        // Only the narrow allowlist below may invoke engine operations. Build
        // parameters from validated IDs, never forward arbitrary desktop input.
        let (command, payload) = match action {
            "cull_virtual" => {
                if let Some(name) = args.get("name") {
                    let name = name.as_str().map(str::trim).filter(|n| n.chars().count() <= 120 && !n.chars().any(char::is_control)).ok_or("edit")?;
                    ("photo.virtualCopy", json!({"ids":encoded,"name":name}))
                } else {
                    ("photo.virtualCopy", json!({"ids":encoded}))
                }
            }
            "cull_group" if ids.len() >= 2 => {
                ("stack.group", json!({"ids":encoded,"top":top.0,"collapsed":args["collapsed"].as_bool().unwrap_or(true)}))
            }
            "cull_ungroup" => ("stack.ungroup", json!({"ids":encoded})),
            "cull_remove" => ("stack.remove", json!({"ids":encoded})),
            "cull_set_top" if ids.len() == 1 => ("stack.setTop", json!({"id":top.0})),
            "cull_up" if ids.len() == 1 => ("stack.moveUp", json!({"id":top.0})),
            "cull_down" if ids.len() == 1 => ("stack.moveDown", json!({"id":top.0})),
            "cull_split" if ids.len() == 1 => ("stack.split", json!({"id":top.0})),
            "cull_toggle" => {
                let collapsed = args["collapsed"].as_bool().ok_or("edit")?;
                ("stack.toggle", json!({"ids":encoded,"collapsed":collapsed}))
            }
            "cull_rate" => {
                let rating = args["rating"].as_u64().filter(|r| *r <= 5).ok_or("edit")?;
                ("photo.rate", json!({"ids":encoded,"rating":rating}))
            }
            "cull_flag" => {
                let flag = args["flag"].as_str().filter(|f| matches!(*f, "pick" | "reject" | "none")).ok_or("edit")?;
                ("photo.flag", json!({"ids":encoded,"flag":flag}))
            }
            _ => return Err("unavailable"),
        };
        // In-stack commands require an active stack member even with explicit
        // IDs. For a mixed selection, make a stacked member active for them.
        let active = if matches!(action, "cull_ungroup" | "cull_remove") {
            ids.iter().find(|id| self.session.catalog.stack_of(**id).is_some()).copied().ok_or("selection")?
        } else {
            top
        };
        self.culling_command("library.select", &json!({"ids":encoded,"active":active.0}))?;
        self.culling_command(command, &payload)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_catalog::{Op, Source};
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    fn file_photo(lab: &mut Lab, path: &std::path::Path, name: &str) -> PhotoId {
        let id = lab.session.catalog.alloc_photo_id();
        let photo = Photo::new(id, Source::File { path: path.to_string_lossy().into_owned() }, name, "png", 80, 60, "2026-11-25");
        lab.session.commit("Test photo", Op::AddPhoto { photo: Box::new(photo) }).unwrap();
        id
    }

    #[test]
    fn copies_and_manual_stacks_are_native_catalogue_records() {
        let d = tempfile::tempdir().unwrap();
        let path = d.path().join("peer.png");
        let img = Rgba8::from_fn(80, 60, |x, y| [x as u8 + 60, y as u8 + 50, 85, 255]);
        let bytes = encode_png(&EncodeImage::rgba8(&img), &EncodeMeta::default()).unwrap();
        std::fs::write(&path, &bytes).unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let a = file_photo(&mut lab, &path, "One.png");
        let b = file_photo(&mut lab, &path, "Two.png");
        let c = file_photo(&mut lab, &path, "Three.png");
        let copy = PhotoId(lab.mobile_culling("cull_virtual", &json!({"ids":[a],"name":"Monochrome"})).unwrap()["ids"][0].as_u64().unwrap());
        let model = lab.session.catalog.photo(copy).unwrap();
        assert_eq!(model.copy_of, Some(a));
        assert_eq!(model.source, lab.session.catalog.photo(a).unwrap().source);
        assert_eq!(lab.mobile_culling("cull_info", &json!({"ids":[copy]})).unwrap()["photos"][0]["copyName"], "Monochrome");

        lab.action("adjust_many", &json!({"id":copy,"values":{"light.exposure":1.2}})).unwrap();
        let original = lab.action("render", &json!({"id":a,"edge":128})).unwrap();
        let adjusted = lab.action("render", &json!({"id":copy,"edge":128})).unwrap();
        assert_ne!(original, adjusted);
        assert_eq!(std::fs::read(&path).unwrap(), bytes);

        lab.mobile_culling("cull_group", &json!({"ids":[a,b,c],"top":b,"collapsed":true})).unwrap();
        assert_eq!(lab.mobile_arrange_culling(&[a, b, c, copy]), vec![b]);
        assert_eq!(lab.mobile_arrange_culling(&[a, c, copy]), vec![a], "the filter hides the actual cover");
        lab.mobile_culling("cull_toggle", &json!({"ids":[b],"collapsed":false})).unwrap();
        assert_eq!(lab.mobile_arrange_culling(&[a, b, c, copy]), vec![b, a, c, copy]);
        lab.mobile_culling("cull_set_top", &json!({"ids":[copy]})).unwrap();
        assert_eq!(lab.mobile_arrange_culling(&[a, b, c, copy]), vec![copy, b, a, c]);
        lab.mobile_culling("cull_down", &json!({"ids":[copy]})).unwrap();
        assert_eq!(lab.mobile_arrange_culling(&[a, b, c, copy]), vec![b, copy, a, c]);
        lab.mobile_culling("cull_split", &json!({"ids":[a]})).unwrap();
        assert_ne!(lab.session.catalog.stack_of(a).unwrap().id, lab.session.catalog.stack_of(copy).unwrap().id);
        lab.mobile_culling("cull_remove", &json!({"ids":[copy]})).unwrap();
        lab.mobile_culling("cull_ungroup", &json!({"ids":[c]})).unwrap();
        assert_eq!(lab.session.catalog.stacks().count(), 0);
        lab.mobile_culling("cull_flag", &json!({"ids":[copy],"flag":"pick"})).unwrap();
        lab.mobile_culling("cull_rate", &json!({"ids":[copy],"rating":5})).unwrap();
        assert!(lab.mobile_culling("photo.delete", &json!({"ids":[a],"path":path})).is_err());
        assert!(lab.mobile_culling("cull_virtual", &json!({"ids":[7000],"name":"bad"})).is_err());

        lab.session.close_library().unwrap();
        drop(lab);
        let mut reopened = Lab::start(d.path()).unwrap();
        let photos = reopened.mobile_culling("cull_info", &json!({"ids":[a,copy]})).unwrap();
        assert_eq!(photos["photos"][0]["rating"], 0);
        assert_eq!(photos["photos"][1]["rating"], 5);
        assert_eq!(photos["photos"][1]["flag"], "pick");
        assert_eq!(photos["photos"][1]["virtualCopy"], true);
        assert_eq!(std::fs::read(path).unwrap(), bytes);
    }
}
