//! Native library operations backed by the desktop catalogue. Only explicit,
//! photo-local metadata and album actions are accepted from the Android UI.

use std::collections::HashSet;

use lightcraft_catalog::{Album, AlbumId, ColorLabel, Flag, MediaKind, Op, Photo, PhotoId};
use serde_json::{Value, json};

use crate::{Lab, Reply};

const PAGE: usize = 120;
const MAX_SELECTION: usize = 250;
const IPTC: [(&str, fn(&Photo) -> &str); 12] = [
    ("title", |p| &p.meta.title),
    ("caption", |p| &p.meta.caption),
    ("creator", |p| &p.meta.creator),
    ("copyright", |p| &p.meta.copyright),
    ("altText", |p| &p.meta.alt_text),
    ("extendedDescription", |p| &p.meta.extended_description),
    ("usageTerms", |p| &p.meta.usage_terms),
    ("copyrightUrl", |p| &p.meta.copyright_url),
    ("location", |p| &p.meta.location),
    ("city", |p| &p.meta.city),
    ("state", |p| &p.meta.state),
    ("country", |p| &p.meta.country),
];

fn mobile_photo(p: &Photo) -> bool {
    !p.deleted && !p.local && p.kind != MediaKind::Video
}

fn bounded_text<'a>(p: &'a Value, key: &str, max: usize) -> Reply<&'a str> {
    p[key].as_str().filter(|s| s.chars().count() <= max && !s.contains('\0')).ok_or("edit")
}

impl Lab {
    pub(super) fn mobile_library_page(&self, p: &Value) -> Value {
        let catalogue = &self.session.catalog;
        let start = p["start"].as_u64().unwrap_or(0).min(1_000_000) as usize;
        let query = p["query"].as_str().unwrap_or_default().trim().to_lowercase();
        let min_rating = p["rating"].as_u64().unwrap_or(0).min(5) as u8;
        let flag = p["flag"].as_str().and_then(Flag::parse);
        let label = p["label"].as_str();
        let quick = catalogue.quick_collection();
        let quick_only = p["favorite"].as_bool().unwrap_or(false);
        let album = p["album"].as_u64().map(AlbumId);
        let matches = |photo: &Photo| {
            mobile_photo(photo)
                && photo.rating >= min_rating
                && flag.is_none_or(|f| photo.flag == f)
                && label
                    .is_none_or(|l| if l == "none" { photo.label.is_none() } else { ColorLabel::parse(l).is_some_and(|l| photo.label == Some(l)) })
                && (!quick_only || quick.is_some_and(|a| catalogue.album_contains(a, photo)))
                && album.is_none_or(|a| catalogue.album_contains(a, photo))
                && (query.is_empty()
                    || photo.file_name.to_lowercase().contains(&query)
                    || photo.meta.title.to_lowercase().contains(&query)
                    || photo.meta.keywords.iter().any(|word| word.to_lowercase().contains(&query)))
        };
        let entry = |photo: &Photo| {
            json!({
                "id":photo.id.0,"name":photo.file_name,"width":photo.width,"height":photo.height,"format":photo.format,
                "rating":photo.rating,"flag":photo.flag,"label":photo.label,
                "favorite": quick.is_some_and(|a| catalogue.album_contains(a, photo))
            })
        };
        let order = p["sort"].as_str().unwrap_or_default();
        if matches!(order, "name" | "rating" | "capture" | "recent") {
            let mut all = catalogue.photos().filter(|photo| matches(photo)).collect::<Vec<_>>();
            match order {
                "name" => all.sort_by_cached_key(|p| (p.file_name.to_lowercase(), p.id)),
                "rating" => all.sort_by_key(|p| (std::cmp::Reverse(p.rating), p.id)),
                "capture" => all.sort_by(|a, b| b.captured.cmp(&a.captured).then(a.id.cmp(&b.id))),
                _ => all.sort_by(|a, b| b.imported.cmp(&a.imported).then(b.id.cmp(&a.id))),
            }
            return json!({"total":all.len(), "items":all.into_iter().skip(start).take(PAGE).map(|p|entry(p)).collect::<Vec<_>>()});
        }
        let mut items = Vec::new();
        let mut total = 0;
        for photo in catalogue.photos().filter(|photo| matches(photo)) {
            if total >= start && items.len() < PAGE {
                items.push(entry(photo));
            }
            total += 1;
        }
        json!({"total":total,"items":items})
    }

    fn mobile_targets(&self, p: &Value) -> Reply<Vec<PhotoId>> {
        let ids = p["ids"].as_array().filter(|a| !a.is_empty() && a.len() <= MAX_SELECTION).ok_or("selection")?;
        let mut seen = HashSet::new();
        ids.iter()
            .filter_map(|v| {
                let id = v.as_u64().map(PhotoId);
                if !seen.insert(id) {
                    return None;
                }
                Some(id.filter(|id| self.session.catalog.photo(*id).is_some_and(|ph| mobile_photo(ph))).ok_or("selection"))
            })
            .collect::<Reply<Vec<_>>>()
    }

    fn mobile_album_path(&self, album: &Album) -> String {
        let mut names = vec![album.name.as_str()];
        let mut parent = album.parent;
        for _ in 0..32 {
            let Some(folder) = parent.and_then(|p| self.session.catalog.album(p)) else { break };
            names.push(folder.name.as_str());
            parent = folder.parent;
        }
        names.reverse();
        names.join(" › ")
    }

    fn mobile_albums(&self, ids: &[PhotoId]) -> Value {
        let mut rows = self
            .session
            .catalog
            .albums()
            .map(|a| {
                let member = !ids.is_empty()
                    && ids.iter().all(|id| self.session.catalog.photo(*id).is_some_and(|p| self.session.catalog.album_contains(a.id, p)));
                (self.mobile_album_path(a), a, member)
            })
            .collect::<Vec<_>>();
        rows.sort_by_cached_key(|(path, a, _)| (!a.quick, path.to_lowercase(), a.id));
        json!({
            "albums": rows.into_iter().map(|(path,a,member)| json!({
                "id":a.id.0,"name":a.name,"path":path,"folder":a.folder,"smart":a.is_smart(),"quick":a.quick,
                "member":member
            })).collect::<Vec<_>>()
        })
    }

    fn mobile_catalogue_command(&mut self, command: &str, args: Value) -> Reply<Value> {
        // Even if a migrated catalogue requested sidecar auto-write on a computer,
        // culling and IPTC changes in the phone interface do not edit any source or XMP.
        let auto_write = std::mem::replace(&mut self.session.xmp.auto_write, false);
        let result = self.session.execute(command, &args).map_err(|_| "edit");
        self.session.xmp.auto_write = auto_write;
        result
    }

    pub(super) fn mobile_library(&mut self, op: &str, p: &Value) -> Reply<Value> {
        if op == "library_albums" && p.get("ids").is_none() {
            return Ok(self.mobile_albums(&[]));
        }
        if matches!(op, "library_create_album" | "library_rename_album" | "library_move_album") {
            let parent = match p.get("parent") {
                None | Some(Value::Null) => None,
                Some(v) => Some(v.as_u64().map(AlbumId).filter(|a| self.session.catalog.album(*a).is_some_and(|a| a.folder)).ok_or("edit")?),
            };
            if op == "library_create_album" {
                let name = bounded_text(p, "name", 120)?.trim();
                if name.is_empty() {
                    return Err("edit");
                }
                return self.mobile_catalogue_command(
                    "album.create",
                    json!({
                        "name":name, "parent":parent.map(|a|a.0), "folder":p["folder"].as_bool().unwrap_or(false)
                    }),
                );
            }
            let id = p["album"].as_u64().map(AlbumId).filter(|a| self.session.catalog.album(*a).is_some_and(|a| !a.quick)).ok_or("edit")?;
            if op == "library_rename_album" {
                let name = bounded_text(p, "name", 120)?.trim();
                if name.is_empty() {
                    return Err("edit");
                }
                return self.mobile_catalogue_command("album.rename", json!({"id":id.0, "name":name}));
            }
            return self.mobile_catalogue_command("album.move", json!({"id":id.0, "parent":parent.map(|x|x.0)}));
        }

        let ids = self.mobile_targets(p)?;
        if matches!(op, "library_rate" | "library_flag" | "library_color" | "library_keywords" | "library_iptc") {
            // Their shared desktop handlers require a live selection even with explicit IDs.
            self.select(ids[0])?;
        }
        let numbers = ids.iter().map(|id| id.0).collect::<Vec<_>>();
        match op {
            "library_albums" => Ok(self.mobile_albums(&ids)),
            "library_photo_info" => {
                let photo = self.session.catalog.photo(ids[0]).ok_or("selection")?;
                let single = ids.len() == 1;
                let quick = self.session.catalog.quick_collection();
                let same_rating = ids.iter().all(|id| self.session.catalog.photo(*id).is_some_and(|p| p.rating == photo.rating));
                let same_flag = ids.iter().all(|id| self.session.catalog.photo(*id).is_some_and(|p| p.flag == photo.flag));
                let same_label = ids.iter().all(|id| self.session.catalog.photo(*id).is_some_and(|p| p.label == photo.label));
                let mut r = json!({
                    "count":ids.len(),
                    "rating":same_rating.then_some(photo.rating), "flag":same_flag.then_some(photo.flag),
                    "label":if same_label { json!(photo.label.map_or_else(|| "none".into(), |l| format!("{l:?}").to_lowercase())) } else {Value::Null},
                    "favorite": quick.is_some_and(|a| ids.iter().all(|id| self.session.catalog.photo(*id).is_some_and(|p| self.session.catalog.album_contains(a, p)))),
                    "keywords":if single { json!(photo.meta.keywords) } else { json!([]) }
                });
                if single {
                    for (key, get) in IPTC {
                        r[key] = json!(get(photo));
                    }
                    r["copyrightStatus"] = json!(photo.meta.copyright_status);
                    r["gps"] = json!(photo.meta.gps);
                }
                Ok(r)
            }
            "library_rate" => {
                let rating = p["rating"].as_u64().filter(|x| *x <= 5).ok_or("edit")?;
                self.mobile_catalogue_command("photo.rate", json!({"ids":numbers, "rating":rating}))
            }
            "library_flag" => {
                let flag = bounded_text(p, "flag", 8)?;
                if !matches!(flag, "none" | "pick" | "reject") {
                    return Err("edit");
                }
                self.mobile_catalogue_command("photo.flag", json!({"ids":numbers, "flag":flag}))
            }
            "library_color" => {
                let label = bounded_text(p, "label", 8)?;
                if label != "none" && ColorLabel::parse(label).is_none() {
                    return Err("edit");
                }
                self.mobile_catalogue_command("photo.label", json!({"ids":numbers, "label":label}))
            }
            "library_favorite" => {
                let set = p["set"].as_bool().ok_or("edit")?;
                let id = match self.session.catalog.quick_collection() {
                    Some(id) => id,
                    None if set => {
                        let id = self.session.catalog.alloc_album_id();
                        let album = Album { quick: true, ..Album::new(id, "Quick Collection") };
                        self.session.commit("New Quick Collection", Op::AddAlbum { album }).map_err(|_| "edit")?;
                        id
                    }
                    None => return Ok(json!({})),
                };
                self.mobile_catalogue_command(if set { "album.addPhotos" } else { "album.removePhotos" }, json!({"id":id.0, "ids":numbers}))
            }
            "library_album_add" | "library_album_remove" => {
                let id = p["album"]
                    .as_u64()
                    .map(AlbumId)
                    .filter(|a| self.session.catalog.album(*a).is_some_and(|a| !a.folder && !a.is_smart()))
                    .ok_or("edit")?;
                self.mobile_catalogue_command(
                    if op == "library_album_add" { "album.addPhotos" } else { "album.removePhotos" },
                    json!({"id":id.0,"ids":numbers}),
                )
            }
            "library_keywords" => {
                let values = p["keywords"].as_array().filter(|a| a.len() <= 100).ok_or("edit")?;
                let values = values
                    .iter()
                    .map(|v| v.as_str().filter(|s| !s.trim().is_empty() && s.chars().count() <= 160 && !s.contains('\0')).ok_or("edit"))
                    .collect::<Reply<Vec<_>>>()?;
                let field = match p["mode"].as_str() {
                    Some("replace") if ids.len() == 1 => "keywords",
                    Some("add") => "addKeywords",
                    Some("remove") => "removeKeywords",
                    _ => return Err("edit"),
                };
                self.mobile_catalogue_command("photo.setMeta", json!({"ids":numbers, (field):values}))
            }
            "library_iptc" if ids.len() == 1 => {
                let fields = p["fields"].as_object().filter(|o| !o.is_empty()).ok_or("edit")?;
                let mut args = json!({"ids":numbers});
                for (k, v) in fields {
                    if IPTC.iter().any(|(key, _)| key == k) {
                        args[k] = json!(v.as_str().filter(|s| s.chars().count() <= 4_000 && !s.contains('\0')).ok_or("edit")?);
                    } else if k == "copyrightStatus" {
                        args[k] = json!(v.as_str().filter(|s| matches!(*s, "unknown" | "copyrighted" | "publicDomain")).ok_or("edit")?);
                    } else if k == "gps" {
                        args[k] = if v.is_null() {
                            Value::Null
                        } else {
                            json!(v.as_str().filter(|s| s.chars().count() <= 100 && !s.contains('\0')).ok_or("edit")?)
                        };
                    } else {
                        return Err("edit");
                    }
                }
                self.mobile_catalogue_command("photo.setMeta", args)
            }
            _ => Err("unavailable"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_catalog::Source;

    fn fixture(lab: &mut Lab, scene: u32, name: &str) -> u64 {
        let id = lab.session.catalog.alloc_photo_id();
        let photo = Photo::new(id, Source::Demo { scene }, name, "jpg", 640, 480, "2026-11-25");
        lab.session.commit("Provide catalogue fixture", Op::AddPhoto { photo: Box::new(photo) }).unwrap();
        id.0
    }

    #[test]
    fn phone_catalogue_culls_groups_and_reopens_the_desktop_operations() {
        let dir = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(dir.path()).unwrap();
        let one = fixture(&mut lab, 1, "Sunrise.jpg");
        let two = fixture(&mut lab, 2, "River.jpg");
        let both = json!([one, two]);
        let single = json!([one]);

        assert_eq!(lab.mobile_library("library_rate", &json!({"ids":[one,9000],"rating":4})), Err("selection"));
        assert_eq!(lab.mobile_library_page(&json!({"rating":1}))["total"], 0);
        lab.mobile_library("library_rate", &json!({"ids":both,"rating":4})).unwrap();
        lab.mobile_library("library_flag", &json!({"ids":single,"flag":"pick"})).unwrap();
        lab.mobile_library("library_color", &json!({"ids":single,"label":"red"})).unwrap();
        lab.mobile_library("library_favorite", &json!({"ids":single,"set":true})).unwrap();
        lab.mobile_library("library_keywords", &json!({"ids":both,"mode":"add","keywords":["Sports|Gymnastique"]})).unwrap();
        lab.mobile_library("library_keywords", &json!({"ids":[two],"mode":"remove","keywords":["Sports|Gymnastique"]})).unwrap();
        lab.mobile_library(
            "library_iptc",
            &json!({"ids":single, "fields":{"title":"Finale 2026","gps":"45.5, 4.7","copyrightStatus":"copyrighted"}}),
        )
        .unwrap();

        let folder = lab.mobile_library("library_create_album", &json!({"name":"Événements", "folder":true})).unwrap()["id"].as_u64().unwrap();
        let album = lab.mobile_library("library_create_album", &json!({"name":"Tournoi", "parent":folder})).unwrap()["id"].as_u64().unwrap();
        lab.mobile_library("library_album_add", &json!({"ids":both,"album":album})).unwrap();
        lab.mobile_library("library_album_remove", &json!({"ids":[two],"album":album})).unwrap();
        lab.mobile_library("library_rename_album", &json!({"album":album,"name":"Finale"})).unwrap();
        assert_eq!(lab.mobile_library_page(&json!({"album":album}))["total"], 1);
        assert_eq!(lab.mobile_library_page(&json!({"label":"none"}))["items"][0]["id"], two);
        assert_eq!(
            lab.mobile_library_page(&json!({"query":"GYMNASTIQUE","favorite":true,"rating":3,"label":"red","flag":"pick"}))["items"][0]["id"],
            one
        );
        assert_eq!(lab.mobile_library_page(&json!({"sort":"name","start":1}))["items"][0]["id"], one);
        assert_eq!(lab.mobile_library_page(&json!({"query":"finale 2026"}))["total"], 1);
        assert_eq!(lab.mobile_library("library_photo_info", &json!({"ids":single})).unwrap()["gps"], json!([45.5, 4.7]));
        assert_eq!(lab.mobile_library("library_photo_info", &json!({"ids":both})).unwrap()["flag"], Value::Null);
        assert_eq!(lab.mobile_library("library_albums", &json!({"ids":single})).unwrap()["albums"][1]["path"], "Événements");
        assert!(lab.mobile_library("library_iptc", &json!({"ids":single,"fields":{"path":"/not-allowed"}})).is_err());
        assert!(lab.mobile_library("library_delete", &json!({"ids":single})).is_err());

        drop(lab);
        let mut reopened = Lab::start(dir.path()).unwrap();
        let all = reopened.mobile_library_page(&json!({"album":album,"query":"finale"}));
        assert_eq!(all["total"], 1);
        assert_eq!(all["items"][0]["rating"], 4);
        assert_eq!(all["items"][0]["favorite"], true);
        assert_eq!(reopened.mobile_library("library_photo_info", &json!({"ids":single})).unwrap()["copyrightStatus"], "copyrighted");
        reopened.mobile_library("library_favorite", &json!({"ids":single,"set":false})).unwrap();
        assert_eq!(reopened.mobile_library_page(&json!({"favorite":true}))["total"], 0);
    }
}
