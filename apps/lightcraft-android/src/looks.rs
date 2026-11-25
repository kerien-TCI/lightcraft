//! Photo looks, versions and the private document inbox. Never exposes the desktop command API.
use std::fs;
use std::path::{Component, Path, PathBuf};

use lightcraft_catalog::PhotoId;
use lightcraft_develop::SettingsGroup;
use serde_json::{Value, json};

use super::{Lab, Reply, bytes_json};

const MAX_ASSET: u64 = 16 << 20;
const INBOX: &str = "LookInbox";

fn short_text<'a>(p: &'a Value, field: &str, max: usize) -> Reply<&'a str> {
    p[field].as_str().map(str::trim).filter(|v| !v.is_empty() && v.chars().count() <= max && !v.chars().any(char::is_control)).ok_or("look_invalid")
}

fn index(p: &Value) -> Reply<u64> {
    p["index"].as_u64().filter(|&i| i <= 10_000).ok_or("look_invalid")
}

fn groups(p: &Value) -> Reply<Value> {
    if p["groups"].is_null() {
        return Ok(json!(SettingsGroup::default_copy()));
    }
    let mut list: Vec<SettingsGroup> = serde_json::from_value(p["groups"].clone()).map_err(|_| "look_invalid")?;
    if list.is_empty() || list.len() > SettingsGroup::ALL.len() {
        return Err("look_invalid");
    }
    list.sort_by_key(|g| SettingsGroup::ALL.iter().position(|e| e == g));
    list.dedup();
    Ok(json!(list))
}

fn asset_verb(name: &str) -> Option<&'static str> {
    let ext = Path::new(name).extension()?.to_str()?.to_ascii_lowercase();
    match ext.as_str() {
        "cube" | "dcp" => Some("profile.import"),
        "xmp" | "lrtemplate" | "lcpreset" | "lceffect" => Some("preset.import"),
        _ => None,
    }
}

impl Lab {
    fn persist_looks(&mut self, error: &'static str) -> Reply<()> {
        self.session.persist().map_err(|_| error)?;
        if self.session.unsaved().is_some() {
            return Err(error);
        }
        Ok(())
    }

    /// Only a ready, regular document in a fresh Java-allocated inbox subdirectory is accepted.
    fn look_inbox_file(&self, p: &Value) -> Reply<(PathBuf, &'static str)> {
        let token = short_text(p, "token", 36)?;
        if token.len() != 36 || !token.bytes().enumerate().all(|(i, b)| if [8, 13, 18, 23].contains(&i) { b == b'-' } else { b.is_ascii_hexdigit() })
        {
            return Err("look_import");
        }
        let name = short_text(p, "name", 160)?;
        let mut parts = Path::new(name).components();
        if !matches!(parts.next(), Some(Component::Normal(n)) if n == name) || parts.next().is_some() || name.contains('\\') {
            return Err("look_import");
        }
        let verb = asset_verb(name).ok_or("look_import")?;
        let folder = self.root.join(INBOX).join(token);
        let canonical = folder.canonicalize().map_err(|_| "look_import")?;
        if canonical != folder {
            return Err("look_import");
        }
        let file = folder.join(name);
        let meta = fs::symlink_metadata(&file).map_err(|_| "look_import")?;
        if !meta.is_file() || !(1..=MAX_ASSET).contains(&meta.len()) {
            return Err("look_import");
        }
        Ok((file, verb))
    }

    fn scoped_look_import(&mut self, p: &Value) -> Reply<Value> {
        let (file, verb) = self.look_inbox_file(p)?;
        let result = self.session.execute(verb, &json!({"paths":[file]})).map_err(|_| "look_import")?;
        // The desktop importer retained permanent copies for profiles and stored the others as
        // catalogue data. Never return source paths or parser details to the mobile UI.
        self.persist_looks("look_import")?;
        let count = |key| result[key].as_array().map_or(0, Vec::len);
        let already = result["imported"].as_array().into_iter().flatten().filter(|row| row["existing"] == true).count();
        let imported = count("imported").saturating_sub(already);
        let effects = count("effects") + count("effectDividers");
        let skipped = result["skipped"].as_u64().unwrap_or(0) + already as u64;
        let failed = count("failed");
        if imported == 0 && effects == 0 && failed == 0 && skipped == 0 {
            return Err("look_import");
        }
        Ok(json!({"imported":imported,"effects":effects,"failed":failed,"skipped":skipped}))
    }

    fn look_photo(&mut self, p: &Value) -> Reply<PhotoId> {
        let id = self.valid_photo(&json!({"id":p["photo"]}))?;
        self.select(id)?;
        Ok(id)
    }

    fn apply_look(&mut self, op: &str, p: &Value) -> Reply<Value> {
        let id = self.look_photo(p)?;
        if matches!(op, "looks.applyPreset" | "looks.applyProfile" | "looks.paste" | "looks.pastePrevious" | "looks.restoreVersion") {
            self.ensure_history_baseline(id)?;
        }
        let amount = || {
            if p["amount"].is_null() {
                return Ok(100.0);
            }
            p["amount"].as_f64().filter(|a| a.is_finite() && (0.0..=200.0).contains(a)).ok_or("look_invalid")
        };
        let key = || short_text(p, "key", 512);
        let name = || short_text(p, "name", 80);
        let (cmd, args) = match op {
            "looks.applyPreset" => ("preset.apply", json!({"id":key()?,"amount":amount()?,"ids":[id.0]})),
            "looks.applyProfile" => ("develop.profile", json!({"id":key()?,"amount":amount()?})),
            "looks.createPreset" => ("preset.create", json!({"name":name()?,"groups":groups(p)?})),
            "looks.presetFavorite" => ("preset.favorite", json!({"id":key()?})),
            "looks.profileFavorite" => ("profile.favorite", json!({"id":key()?})),
            "looks.copy" => ("develop.copy", json!({"groups":groups(p)?})),
            "looks.paste" => {
                if self.session.clipboard.is_none() {
                    return Err("look_clipboard");
                }
                ("develop.paste", json!({"ids":[id.0]}))
            }
            "looks.pastePrevious" => ("develop.pastePrevious", json!({"ids":[id.0]})),
            "looks.createVersion" => ("version.create", json!({"name":name()?})),
            "looks.restoreVersion" => ("version.restore", json!({"index":index(p)?})),
            "looks.renameVersion" => ("version.rename", json!({"index":index(p)?,"name":name()?})),
            "looks.updateVersion" => ("version.update", json!({"index":index(p)?})),
            "looks.restoreHistory" => ("history.restore", json!({"index":index(p)?})),
            _ => return Err("unavailable"),
        };
        self.session.execute(cmd, &args).map_err(|_| "look_invalid")?;
        self.persist_looks("look_invalid")?;
        Ok(json!({"ok":true}))
    }

    pub(super) fn looks_action(&mut self, op: &str, p: &Value) -> Reply<Vec<u8>> {
        let value = match op {
            "looks.import" => self.scoped_look_import(p)?,
            "looks.summary" => {
                self.look_photo(p)?;
                let previous = self
                    .session
                    .previous_active
                    .is_some_and(|id| Some(id) != self.session.active() && self.session.catalog.photo(id).is_some_and(|ph| !ph.deleted && !ph.local));
                json!({"clipboard":self.session.clipboard.is_some(),"previous":previous})
            }
            "looks.presets" => {
                self.look_photo(p)?;
                json!({"items": self.session.execute("presets.list", &json!({})).map_err(|_| "look_invalid")? })
            }
            "looks.profiles" => {
                self.look_photo(p)?;
                let data = self.session.execute("profiles.menu", &json!({})).map_err(|_| "look_invalid")?;
                let items: Vec<_> =
                    data["groups"].as_array().into_iter().flatten().filter_map(|g| g["profiles"].as_array()).flatten().cloned().collect();
                json!({"items":items})
            }
            "looks.history" => {
                let id = self.look_photo(p)?;
                self.session.execute("history.list", &json!({"id":id.0})).map_err(|_| "look_invalid")?
            }
            _ => self.apply_look(op, p)?,
        };
        Ok(bytes_json(value))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_codecs::{EncodeImage, EncodeMeta, encode_png};
    use lightcraft_raster::Rgba8;

    fn reply(lab: &mut Lab, op: &str, args: Value) -> Value {
        serde_json::from_slice(&lab.looks_action(op, &args).unwrap()).unwrap()
    }

    fn pictures(root: &Path, lab: &mut Lab) -> (u64, u64) {
        let dir = root.join("import");
        fs::create_dir_all(&dir).unwrap();
        let saved = (0..2)
            .map(|i| {
                let path = dir.join(format!("look-{i}.png"));
                let px = Rgba8::from_fn(22, 17, |x, y| [25 + (x * 5) as u8, 40 + (y * 4) as u8, 90 + i * 30, 255]);
                let png = encode_png(&EncodeImage::rgba8(&px), &EncodeMeta::default()).unwrap();
                fs::write(&path, png).unwrap();
                path
            })
            .collect::<Vec<_>>();
        assert_eq!(lab.import(&json!({"paths":saved})).unwrap()["imported"], 2);
        let ids = lab.session.catalog.photos().map(|p| p.id.0).collect::<Vec<_>>();
        (ids[0], ids[1])
    }

    #[test]
    fn named_preset_profile_clipboard_and_versions_apply_and_reopen() {
        let d = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let (a, b) = pictures(d.path(), &mut lab);
        let current = |l: &Lab, id: u64| l.settings(PhotoId(id)).unwrap()["light.exposure"].as_f64().unwrap();
        lab.action("adjust_many", &json!({"id":a,"values":{"light.exposure":1.25,"color.saturation":22}})).unwrap();
        reply(&mut lab, "looks.createPreset", json!({"photo":a,"name":"Mobile test","groups":["light"]}));
        reply(&mut lab, "looks.createVersion", json!({"photo":a,"name":"Stage one"}));
        reply(&mut lab, "looks.copy", json!({"photo":a,"groups":["light"]}));
        assert_eq!(reply(&mut lab, "looks.summary", json!({"photo":b}))["previous"], true);
        reply(&mut lab, "looks.paste", json!({"photo":b}));
        assert_eq!(current(&lab, b), 1.25);
        assert_eq!(lab.settings(PhotoId(b)).unwrap()["color.saturation"], 0.0);
        reply(&mut lab, "looks.pastePrevious", json!({"photo":b}));
        assert_eq!(current(&lab, b), 1.25);
        reply(&mut lab, "looks.applyProfile", json!({"photo":b,"key":"lc.neutral","amount":58}));
        assert_eq!(lab.session.develop_of(PhotoId(b)).unwrap().profile.id, "lc.neutral");
        assert_eq!(lab.session.develop_of(PhotoId(b)).unwrap().profile.amount, 58.0);
        lab.action("adjust_many", &json!({"id":a,"values":{"light.exposure":-0.5}})).unwrap();
        let history = reply(&mut lab, "looks.history", json!({"photo":a}));
        assert!(history["history"].as_array().unwrap().len() >= 3);
        reply(&mut lab, "looks.renameVersion", json!({"photo":a,"index":0,"name":"Renamed"}));
        reply(&mut lab, "looks.restoreVersion", json!({"photo":a,"index":0}));
        assert_eq!(current(&lab, a), 1.25);

        let presets = reply(&mut lab, "looks.presets", json!({"photo":a}));
        let key = presets["items"].as_array().unwrap().iter().find(|r| r["name"] == "Mobile test").unwrap()["id"].as_str().unwrap();
        reply(&mut lab, "looks.presetFavorite", json!({"photo":a,"key":key}));
        reply(&mut lab, "looks.applyPreset", json!({"photo":a,"key":key,"amount":100}));
        assert_eq!(current(&lab, a), 1.25);
        assert_eq!(lab.looks_action("looks.applyProfile", &json!({"photo":a,"key":"lc.neutral","amount":201})), Err("look_invalid"));
        assert_eq!(lab.looks_action("looks.copy", &json!({"photo":a,"groups":[] })), Err("look_invalid"));
        assert_eq!(lab.looks_action("looks.profiles", &json!({"photo":999 })), Err("selection"));

        lab.session.close_library().unwrap();
        drop(lab);
        let mut reopened = Lab::start(d.path()).unwrap();
        assert_eq!(current(&reopened, a), 1.25);
        assert_eq!(reopened.session.develop_of(PhotoId(b)).unwrap().profile.id, "lc.neutral");
        let history = reply(&mut reopened, "looks.history", json!({"photo":a}));
        assert_eq!(history["versions"][0]["name"], "Renamed");
        let presets = reply(&mut reopened, "looks.presets", json!({"photo":b}));
        assert!(presets["items"].as_array().unwrap().iter().any(|r| r["id"] == key && r["favorite"] == true));
        assert_eq!(reopened.looks_action("looks.paste", &json!({"photo":b})), Err("look_clipboard"));
    }

    // A minimal little-endian DNG camera profile: a camera name, a single colour matrix,
    // an illuminant and a profile name. No camera files or third-party assets in the test.
    fn synthetic_camera_profile() -> Vec<u8> {
        let matrix =
            [90_i32, -30, -5, -40, 120, 20, -5, 15, 70].into_iter().flat_map(|v| [v.to_le_bytes(), 100_i32.to_le_bytes()].concat()).collect();
        let entries: [(u16, u16, u32, Vec<u8>); 4] = [
            (50708, 2, 17, b"Photo Lab Camera\0".to_vec()),
            (50721, 10, 9, matrix),
            (50778, 3, 1, vec![21, 0, 0, 0]),
            (50936, 2, 10, b"Test Look\0".to_vec()),
        ];
        let mut out = b"IIRC\x08\x00\x00\x00".to_vec();
        out.extend(4_u16.to_le_bytes());
        let mut body = Vec::new();
        for (tag, ty, n, value) in entries {
            out.extend(tag.to_le_bytes());
            out.extend(ty.to_le_bytes());
            out.extend(n.to_le_bytes());
            if value.len() == 4 {
                out.extend(value);
            } else {
                out.extend((62_u32 + body.len() as u32).to_le_bytes());
                body.extend(value);
                if body.len() % 2 != 0 {
                    body.push(0);
                }
            }
        }
        out.extend([0_u8; 4]);
        out.extend(body);
        out
    }

    #[test]
    fn only_scoped_assets_and_a_real_xmp_and_cube_import() {
        let d = tempfile::tempdir().unwrap();
        let mut lab = Lab::start(d.path()).unwrap();
        let (id, _) = pictures(d.path(), &mut lab);
        let token = "11111111-1111-1111-1111-111111111111";
        let inbox = d.path().join(INBOX).join(token);
        fs::create_dir_all(&inbox).unwrap();
        let xmp = r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+0.50"/>
</rdf:RDF></x:xmpmeta>"#;
        fs::write(inbox.join("Phone.XMP"), xmp).unwrap();
        let imported = reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.XMP"}));
        assert_eq!(imported["imported"], 1);
        let presets = reply(&mut lab, "looks.presets", json!({"photo":id}));
        let preset = presets["items"].as_array().unwrap().iter().find(|r| !r["builtin"].as_bool().unwrap()).unwrap();
        reply(&mut lab, "looks.applyPreset", json!({"photo":id,"key":preset["id"],"amount":100}));
        assert_eq!(lab.settings(PhotoId(id)).unwrap()["light.exposure"], 0.5);

        fs::write(inbox.join("Phone.cube"), b"TITLE \"Phone Cube\"\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n")
            .unwrap();
        let imported = reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.cube"}));
        assert_eq!(imported["imported"], 1);
        fs::remove_file(inbox.join("Phone.cube")).unwrap();
        let profiles = reply(&mut lab, "looks.profiles", json!({"photo":id}));
        let profile = profiles["items"].as_array().unwrap().iter().find(|r| r["name"] == "Phone Cube").unwrap();
        reply(&mut lab, "looks.applyProfile", json!({"photo":id,"key":profile["id"],"amount":100}));
        assert_eq!(lab.session.develop_of(PhotoId(id)).unwrap().profile.id, profile["id"]);

        fs::write(inbox.join("Phone.dcp"), synthetic_camera_profile()).unwrap();
        let imported = reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.dcp"}));
        assert_eq!(imported["imported"], 1);
        let duplicate = reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.dcp"}));
        assert_eq!(duplicate["imported"], 0);
        assert_eq!(duplicate["skipped"], 1);
        let camera = reply(&mut lab, "looks.profiles", json!({"photo":id}));
        assert!(camera["items"].as_array().unwrap().iter().any(|r| r["name"] == "Test Look" && r["cameraModel"] == "Photo Lab Camera"));

        fs::write(inbox.join("Phone.lcpreset"), br#"{"id":"example","name":"Mobile native","group":"Test","settings":{"light":{"exposure":1.0}}}"#)
            .unwrap();
        assert_eq!(reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.lcpreset"}))["imported"], 1);
        fs::write(inbox.join("Phone.lrtemplate"), br#"s={title="Mobile Lua",value={settings={Exposure2012=0.7}}}"#).unwrap();
        assert_eq!(reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.lrtemplate"}))["imported"], 1);
        fs::write(inbox.join("Phone.lceffect"), br#"{"name":"Mobile mask","adjust":{"exposure":0.2}}"#).unwrap();
        assert_eq!(reply(&mut lab, "looks.import", json!({"token":token,"name":"Phone.lceffect"}))["effects"], 1);

        let out = d.path().join("outside.xmp");
        fs::write(&out, xmp).unwrap();
        for bad in ["../outside.xmp", "/etc/passwd", "nest/Phone.XMP", "archive.zip", "Phone\\.xmp"] {
            assert_eq!(lab.looks_action("looks.import", &json!({"token":token,"name":bad})), Err("look_import"));
        }
        assert!(lab.looks_action("looks.import", &json!({"token":"..","name":"outside.xmp"})).is_err());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&out, inbox.join("linked.xmp")).unwrap();
            assert_eq!(lab.looks_action("looks.import", &json!({"token":token,"name":"linked.xmp"})), Err("look_import"));
        }
        assert!(out.exists());
        lab.session.close_library().unwrap();
        drop(lab);
        let mut reopened = Lab::start(d.path()).unwrap();
        let profiles = reply(&mut reopened, "looks.profiles", json!({"photo":id}));
        assert!(profiles["items"].as_array().unwrap().iter().any(|r| r["id"] == profile["id"]));
    }
}
