//! Native smart-album authoring: typed desktop rule definitions, restricted open/write commands.
use lightcraft_catalog::rules::{Rule, RuleSet};
use lightcraft_catalog::{AlbumId, Filter, MediaKind};
use serde_json::{Value, json};

use crate::{Lab, Reply};

const MAX_DEPTH: usize = 4;
const MAX_NODES: usize = 48;

fn rule_limit(set: &RuleSet, depth: usize, left: &mut usize) -> bool {
    if depth > MAX_DEPTH || set.rules.is_empty() || set.rules.len() > *left {
        return false;
    }
    *left -= set.rules.len();
    set.rules.iter().all(|rule| match rule {
        Rule::Group { group } => rule_limit(group, depth + 1, left),
        Rule::Field { field, op, value } => {
            fn frame(v: &Value) -> bool {
                match v {
                    Value::Null | Value::Number(_) | Value::Bool(_) => true,
                    Value::String(s) => s.chars().count() <= 400 && !s.contains('\0'),
                    _ => false,
                }
            }
            field.len() < 64
                && op.len() < 48
                && match value {
                    Value::Array(a) => a.len() == 2 && a.iter().all(frame),
                    Value::Object(o) => o.len() <= 3 && o.iter().all(|(k, v)| k.len() < 20 && frame(v)),
                    value => frame(value),
                }
        }
    })
}

impl Lab {
    pub(super) fn mobile_smart_album(&mut self, op: &str, p: &Value) -> Reply<Value> {
        let existing = p["id"].as_u64().map(AlbumId);
        if existing.is_some_and(|id| self.session.catalog.album(id).and_then(|a| a.smart.as_ref()).is_none()) {
            return Err("smart_id");
        }
        match op {
            "smart_open" => {
                let fields = self.session.execute("album.ruleFields", &json!({})).map_err(|_| "smart_edit")?;
                let albums = self
                    .session
                    .catalog
                    .albums()
                    .filter(|a| !a.folder && Some(a.id) != existing)
                    .map(|a| json!({"id":a.id.0,"name":a.name}))
                    .collect::<Vec<_>>();
                let folders = self.session.catalog.albums().filter(|a| a.folder).map(|a| json!({"id":a.id.0,"name":a.name})).collect::<Vec<_>>();
                let album = existing.and_then(|id| self.session.catalog.album(id));
                let mut other = album.and_then(|a| a.smart.as_deref().cloned()).unwrap_or_default();
                let rules = other.rule_set.take().unwrap_or_default();
                let constrained = serde_json::to_value(&other).ok() != serde_json::to_value(Filter::default()).ok();
                Ok(json!({"name":album.map(|a|a.name.as_str()).unwrap_or_default(),"parent":album.and_then(|a|a.parent).map(|a|a.0),
                    "fields":fields,"albums":albums,"folders":folders,"rules":rules,"legacy":constrained}))
            }
            "smart_preview" | "smart_save" => {
                let v = p.get("rules").filter(|v| v.to_string().len() <= 32768).cloned().ok_or("smart_invalid")?;
                let set: RuleSet = serde_json::from_value(v).map_err(|_| "smart_invalid")?;
                let mut remaining = MAX_NODES;
                if !rule_limit(&set, 1, &mut remaining) {
                    return Err("smart_limits");
                }
                let notes = set.check_for(&self.session.catalog, existing);
                if !notes.is_empty() {
                    return Ok(
                        json!({"issues":notes.into_iter().map(|n|json!({"path":n.path,"field":n.field,"message":n.issue.text()})).collect::<Vec<_>>()}),
                    );
                }
                if op == "smart_preview" {
                    let mut filter =
                        existing.and_then(|id| self.session.catalog.album(id)).and_then(|a| a.smart.as_deref().cloned()).unwrap_or_default();
                    filter.rule_set = Some(set);
                    let matches = self
                        .session
                        .catalog
                        .query(&filter, &Default::default())
                        .iter()
                        .filter_map(|id| self.session.catalog.photo(*id))
                        .filter(|p| !p.deleted && !p.local && p.kind != MediaKind::Video)
                        .count();
                    return Ok(json!({"count":matches}));
                }
                let name = p["name"]
                    .as_str()
                    .map(str::trim)
                    .filter(|s| !s.is_empty() && s.chars().count() <= 120 && !s.contains('\0'))
                    .ok_or("smart_name")?;
                if let Some(id) = existing {
                    // Keep the source and other filters from desktop-created or saved-view albums.
                    self.session.execute("album.setRules", &json!({"id":id.0,"name":name,"rules":{"ruleSet":set}})).map_err(|_| "smart_edit")
                } else {
                    let parent = match p.get("parent") {
                        None | Some(Value::Null) => None,
                        Some(v) => {
                            Some(v.as_u64().map(AlbumId).filter(|id| self.session.catalog.album(*id).is_some_and(|a| a.folder)).ok_or("smart_id")?)
                        }
                    };
                    self.session
                        .execute("album.createSmart", &json!({"name":name,"parent":parent.map(|a|a.0),"rules":{"ruleSet":set}}))
                        .map_err(|_| "smart_edit")
                }
            }
            _ => Err("unavailable"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    #[test]
    fn smart_album_rule_groups_change_engine_match_count_and_persist() {
        let tmp = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(tmp.path()).unwrap();
        let folder = tmp.path().join("import");
        std::fs::create_dir(&folder).unwrap();
        for (i, name) in ["one.png", "two.png"].into_iter().enumerate() {
            let a = Rgba8::from_fn(40, 32, |x, y| [(x * 3 + i * 50) as u8, (y * 4) as u8, 60, 255]);
            let px = encode_png(&EncodeImage::rgba8(&a), &EncodeMeta::default()).unwrap();
            let path = folder.join(name);
            std::fs::write(&path, px).unwrap();
            assert_eq!(lab.import(&json!({"paths":[path]})).unwrap()["imported"], 1);
        }
        let schema = lab.mobile_smart_album("smart_open", &Value::Null).unwrap();
        assert!(schema["fields"].as_array().is_some_and(|v| v.iter().any(|r| r["field"] == "rating")));
        let id = lab.session.catalog.photos().next().unwrap().id;
        lab.mobile_culling("cull_rate", &json!({"ids":[id.0],"rating":4})).unwrap();
        let rules = json!({"match":"all","rules":[{"field":"rating","op":"gte","value":4},{"group":{"match":"any",
            "rules":[{"field":"extension","op":"is","value":"png"},{"field":"flag","op":"is","value":"pick"}]}}]});
        assert_eq!(lab.mobile_smart_album("smart_preview", &json!({"rules":rules})).unwrap()["count"], 1);
        let saved = lab.mobile_smart_album("smart_save", &json!({"name":"Selected","rules":rules})).unwrap();
        let album = saved["id"].as_u64().unwrap();
        let cycle = json!({"match":"all","rules":[{"field":"album","op":"is","value":album}]});
        assert!(lab.mobile_smart_album("smart_save", &json!({"id":album,"name":"Loop","rules":cycle})).unwrap()["issues"].is_array());
        let update = json!({"match":"none","rules":[{"field":"rating","op":"gte","value":1}]});
        // The current catalogue keeps the same title unless a valid rule set commits.
        lab.mobile_smart_album("smart_save", &json!({"id":album,"name":"Unchecked","rules":update})).unwrap();
        lab.session.close_library().unwrap();
        drop(lab);
        let mut lab = Lab::start(tmp.path()).unwrap();
        let state = lab.mobile_smart_album("smart_open", &json!({"id":album})).unwrap();
        assert_eq!(state["name"], "Unchecked");
        assert_eq!(state["rules"], update);
        assert_eq!(lab.mobile_smart_album("smart_preview", &json!({"id":album,"rules":update})).unwrap()["count"], 1);
        let legacy = lab.session.execute("album.createSmart", &json!({"name":"Legacy","rules":{"rating":4}})).unwrap()["id"].clone();
        // Rules can be added on Android without dropping the desktop-era filter.
        let catch_all = json!({"match":"all","rules":[{"field":"extension","op":"is","value":"png"}]});
        assert_eq!(lab.mobile_smart_album("smart_open", &json!({"id":legacy})).unwrap()["legacy"], true);
        assert_eq!(lab.mobile_smart_album("smart_preview", &json!({"id":legacy,"rules":catch_all})).unwrap()["count"], 1);
        lab.mobile_smart_album("smart_save", &json!({"id":legacy,"name":"Preserved","rules":catch_all})).unwrap();
        assert_eq!(lab.session.catalog.album(AlbumId(legacy.as_u64().unwrap())).unwrap().smart.as_ref().unwrap().rating, 4);
    }
}
