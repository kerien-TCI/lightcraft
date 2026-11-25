//! Native batch workflows over the existing desktop command registry.
//! Selection and clipboard state belong to the previous editor, not the sync
//! sheet. They are restored after every request, including failed requests.

use std::collections::{HashSet, hash_map::DefaultHasher};
use std::hash::{Hash, Hasher};

use lightcraft_catalog::{MediaKind, PhotoId};
use lightcraft_engine::Selection;
use serde_json::{Value, json};

use crate::{Lab, Reply};

const MAX_PHOTOS: usize = 250;
const DEVELOP_FIELDS: &[&str] = &[
    "profile",
    "treatment",
    "whiteBalance",
    "light",
    "toneCurve",
    "color",
    "colorMixer",
    "colorGrading",
    "effects",
    "vignette",
    "grain",
    "detail",
    "optics",
    "geometry",
    "crop",
    "masks",
    "spots",
    "redEye",
    "lensBlur",
    "calibration",
];
const META_GROUPS: &[(&str, &[&str])] = &[
    (
        "rights",
        &["copyright", "copyrightStatus", "usageTerms", "copyrightUrl"],
    ),
    ("creator", &["creator"]),
    (
        "description",
        &["title", "caption", "altText", "extendedDescription"],
    ),
    ("keywords", &["keywords"]),
    ("location", &["location", "city", "state", "country"]),
];

fn selected_keys<'a>(p: &'a Value, allowed: &[&str]) -> Reply<Vec<&'a str>> {
    let raw = p["groups"]
        .as_array()
        .filter(|a| !a.is_empty() && a.len() <= allowed.len())
        .ok_or("edit")?;
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(raw.len());
    for v in raw {
        let key = v.as_str().filter(|k| allowed.contains(k)).ok_or("edit")?;
        if seen.insert(key) {
            out.push(key);
        }
    }
    Ok(out)
}

fn stack_signature(gap: u64, groups: &[Vec<PhotoId>]) -> String {
    let mut digest = DefaultHasher::new();
    gap.hash(&mut digest);
    groups.hash(&mut digest);
    format!("{:016x}", digest.finish())
}

impl Lab {
    fn sync_ids(&self, p: &Value) -> Reply<Vec<PhotoId>> {
        let ids = p["ids"]
            .as_array()
            .filter(|a| (2..=MAX_PHOTOS).contains(&a.len()))
            .ok_or("selection")?;
        let mut unique = HashSet::new();
        let mut out = Vec::with_capacity(ids.len());
        for v in ids {
            let id = PhotoId(v.as_u64().ok_or("selection")?);
            if !self
                .session
                .catalog
                .photo(id)
                .is_some_and(|p| !p.deleted && !p.local && p.kind != MediaKind::Video)
                || !unique.insert(id)
            {
                return Err("selection");
            }
            out.push(id);
        }
        Ok(out)
    }

    pub(super) fn mobile_sync(&mut self, action: &str, p: &Value) -> Reply<Value> {
        let ids = self.sync_ids(p)?;
        if action == "sync_describe" {
            return Ok(
                json!({"photos":ids.iter().filter_map(|id|self.session.catalog.photo(*id)).map(|ph| {
                let m=&ph.meta;
                json!({
                    "id":ph.id.0,"name":ph.file_name,"copyName":ph.copy_name,
                    "rights":!m.copyright.is_empty() || !m.usage_terms.is_empty() || !m.copyright_url.is_empty() || m.copyright_status.id()!="unknown",
                    "creator":!m.creator.is_empty(),
                })
            }).collect::<Vec<_>>() }),
            );
        }
        if matches!(action, "sync_auto_preview" | "sync_auto_apply") {
            let gap = p["gap"]
                .as_u64()
                .filter(|g| (1..=3600).contains(g))
                .ok_or("edit")?;
            let groups = self.session.catalog.auto_stack_groups(&ids, gap as f64);
            let stamp = stack_signature(gap, &groups);
            let photos = groups.iter().map(Vec::len).sum::<usize>();
            if action == "sync_auto_preview" {
                // The desktop command remains the authority for the reported counts.
                let mut result = self
                    .session
                    .execute("stack.auto", &json!({"ids":ids,"gap":gap,"preview":true}))
                    .map_err(|_| "edit")?;
                result["stamp"] = json!(stamp);
                return Ok(result);
            }
            if p["stamp"].as_str() != Some(stamp.as_str())
                || p["confirmed"].as_u64() != Some(photos as u64)
            {
                return Ok(json!({"applied":false}));
            }
            if groups.is_empty() {
                return Ok(json!({"applied":true,"stacks":0,"photos":0}));
            }
            let selection = self.session.selection.clone();
            let xmp = std::mem::replace(&mut self.session.xmp.auto_write, false);
            let mut result = self
                .session
                .execute("stack.auto", &json!({"ids":ids,"gap":gap}))
                .map_err(|_| "edit");
            self.session.selection = selection;
            self.session.xmp.auto_write = xmp;
            if let Ok(result) = &mut result {
                result["applied"] = json!(true);
            }
            return result;
        }

        let source = PhotoId(p["source"].as_u64().ok_or("selection")?);
        if !ids.contains(&source) || p["confirmed"].as_u64() != Some((ids.len() - 1) as u64) {
            return Err("selection");
        }

        let groups = if action == "sync_develop" {
            selected_keys(p, DEVELOP_FIELDS)?
        } else if action == "sync_meta" {
            let safe = META_GROUPS
                .iter()
                .map(|(group, _)| *group)
                .collect::<Vec<_>>();
            selected_keys(p, &safe)?
                .iter()
                .flat_map(|group| {
                    META_GROUPS
                        .iter()
                        .filter(move |(g, _)| g == group)
                        .flat_map(|(_, fields)| fields.iter().copied())
                })
                .collect()
        } else {
            return Err("unavailable");
        };
        let targets = ids
            .iter()
            .copied()
            .filter(|id| *id != source)
            .collect::<Vec<_>>();
        let previous = std::mem::replace(
            &mut self.session.selection,
            Selection {
                ids,
                active: Some(source),
            },
        );
        let xmp = std::mem::replace(&mut self.session.xmp.auto_write, false);
        let original_clipboard = self.session.meta_clipboard.take();
        let result = if action == "sync_develop" {
            self.session
                .execute("develop.sync", &json!({"groups":groups}))
        } else {
            self.session
                .execute("photo.copyMetadata", &json!({}))
                .and_then(|_| {
                    self.session.execute(
                        "photo.pasteMetadata",
                        &json!({"ids":targets,"fields":groups}),
                    )
                })
        };
        self.session.meta_clipboard = original_clipboard;
        self.session.xmp.auto_write = xmp;
        self.session.selection = previous;
        result.map_err(|_| "edit")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_catalog::{Op, Photo, Source};
    use lightcraft_develop::{Mask, Spot};

    fn fixture(lab: &mut Lab, scene: u32, file: &str, time: &str) -> PhotoId {
        let id = lab.session.catalog.alloc_photo_id();
        let mut ph = Photo::new(
            id,
            Source::Demo { scene },
            file,
            "jpg",
            640,
            480,
            "2026-11-25",
        );
        ph.captured = Some(time.into());
        lab.session
            .commit(
                "Fixture",
                Op::AddPhoto {
                    photo: Box::new(ph),
                },
            )
            .unwrap();
        id
    }

    #[test]
    fn batch_sync_reads_explicit_source_and_is_undoable_in_one_desktop_step() {
        let d = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let a = fixture(&mut lab, 1, "Source.jpg", "2026-11-25T10:00:00");
        let b = fixture(&mut lab, 2, "Destination.jpg", "2026-11-25T10:00:03");
        let c = fixture(&mut lab, 3, "Also Destination.jpg", "2026-11-25T10:02:00");
        let ids = json!([b, a, c]); // not the first id; explicit source matters
        for (id, ev, degrees, name) in [
            (a, 1.3, 5.0, "source-mask"),
            (b, -0.8, -3.0, "target-mask"),
            (c, 0.0, 0.0, "other-mask"),
        ] {
            let mut dev = (*lab.session.develop_of(id).unwrap()).clone();
            lightcraft_develop::controls::set(&mut dev, "light.exposure", ev);
            dev.crop.geometry.angle = degrees;
            dev.masks.push(Mask {
                name: name.into(),
                on_photo: true,
                ..Default::default()
            });
            if id == b {
                dev.spots.push(Spot::default());
            }
            lab.session.set_develop(id, dev, "Existing edits").unwrap();
        }
        lab.session.selection = Selection::single(c);
        lab.session.xmp.auto_write = true;
        let original = lab.session.develop_of(b).unwrap();
        let safety = lab.mobile_sync(
            "sync_develop",
            &json!({"ids":ids,"source":a,"groups":["light"],"confirmed":3}),
        );
        assert_eq!(
            safety,
            Err("selection"),
            "must confirm exactly the target count"
        );
        assert!(
            lab.mobile_sync(
                "sync_develop",
                &json!({"ids":[a,a],"source":a,"groups":["light"],"confirmed":1})
            )
            .is_err()
        );
        assert!(
            lab.mobile_sync(
                "sync_develop",
                &json!({"ids":ids,"source":a,"groups":["unknown"],"confirmed":2})
            )
            .is_err()
        );
        let before = lab.session.commits();
        assert_eq!(
            lab.mobile_sync(
                "sync_develop",
                &json!({"ids":ids,"source":a,"groups":["light"],"confirmed":2})
            )
            .unwrap()["changed"],
            2
        );
        assert_eq!(lab.session.commits(), before + 1);
        assert_eq!(lab.session.selection, Selection::single(c));
        assert!(lab.session.xmp.auto_write);
        for id in [b, c] {
            assert_eq!(
                lightcraft_develop::controls::get(
                    &lab.session.develop_of(id).unwrap(),
                    "light.exposure"
                ),
                Some(1.3)
            );
        }
        let current = lab.session.develop_of(b).unwrap();
        assert_eq!(current.crop, original.crop);
        assert_eq!(current.masks, original.masks);
        assert_eq!(current.spots, original.spots);
        assert_eq!(lab.session.undo_step().unwrap(), "Sync Settings");
        assert_eq!(lab.session.develop_of(b).unwrap().light, original.light);

        for (id, creator, city) in [
            (a, "Source Author", "Lyon"),
            (b, "Target Author", "Paris"),
            (c, "Other Author", "Lille"),
        ] {
            lab.session.selection = Selection::single(id);
            lab.session
                .execute("photo.setMeta", &json!({"creator":creator,"city":city}))
                .unwrap();
        }
        lab.session.selection = Selection::single(c);
        lab.session.meta_clipboard = Some(json!({"title":"Other copied title"}));
        let before = lab.session.commits();
        assert_eq!(
            lab.mobile_sync(
                "sync_meta",
                &json!({"ids":ids,"source":a,"groups":["creator"],"confirmed":2})
            )
            .unwrap()["changed"],
            2
        );
        assert_eq!(lab.session.commits(), before + 1);
        assert_eq!(
            lab.session.catalog.photo(b).unwrap().meta.city,
            "Paris",
            "unselected location must never change"
        );
        assert_eq!(
            lab.session.catalog.photo(b).unwrap().meta.creator,
            "Source Author"
        );
        assert_eq!(
            lab.session.meta_clipboard,
            Some(json!({"title":"Other copied title"}))
        );
        assert_eq!(lab.session.undo_step().unwrap(), "Edit Info");
        assert_eq!(
            lab.session.catalog.photo(b).unwrap().meta.creator,
            "Target Author"
        );
    }

    #[test]
    fn auto_stack_is_previewed_and_exact_group_membership_confirmed() {
        let d = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let a = fixture(&mut lab, 1, "One.jpg", "2026-11-25T10:00:00");
        let b = fixture(&mut lab, 2, "Two.jpg", "2026-11-25T10:00:03");
        let c = fixture(&mut lab, 3, "Three.jpg", "2026-11-25T10:02:00");
        let ids = json!([a, b, c]);
        let preview = lab
            .mobile_sync("sync_auto_preview", &json!({"ids":ids,"gap":5}))
            .unwrap();
        assert_eq!(preview["stacks"], 1);
        assert_eq!(preview["photos"], 2);
        assert!(lab.session.catalog.stacks().next().is_none());
        let stale = lab
            .mobile_sync(
                "sync_auto_apply",
                &json!({"ids":ids,"gap":180,"stamp":preview["stamp"],"confirmed":2}),
            )
            .unwrap();
        assert_eq!(stale["applied"], false);
        assert!(lab.session.catalog.stacks().next().is_none());
        let applied = lab
            .mobile_sync(
                "sync_auto_apply",
                &json!({"ids":ids,"gap":5,"stamp":preview["stamp"],"confirmed":2}),
            )
            .unwrap();
        assert_eq!(applied["applied"], true);
        assert_eq!(lab.session.catalog.stack_of(a).unwrap().photos, vec![a, b]);
        assert!(lab.session.catalog.stack_of(c).is_none());
    }
}
