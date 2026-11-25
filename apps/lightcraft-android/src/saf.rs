// SAFETY: this module is the crate's JNI/FFI shim and the only place it wraps raw pointers. Every
// `unsafe` block is annotated with its own `// SAFETY:` justification below; the rest of the crate
// keeps the workspace's `unsafe_code = "deny"` (see `Cargo.toml`).
#![allow(unsafe_code)]
#![cfg(target_os = "android")]

//! Storage Access Framework bridge: ask the Java side (`SafBridge`) for files, which copies the
//! picked documents into the app's own directory and hands back real paths — LightCraft's engine
//! works with paths, not `content://` URIs.
//!
//! Everything here runs on a background thread: the pickers are posted to the UI thread by Java and
//! this side polls the `isDone` / `isSaveDone` flags, so the egui UI thread never blocks. Import
//! results travel through the filesystem (`<filesDir>/import-manifest.txt`, written by Java) rather
//! than JNI marshalling, so the JNI surface stays at a handful of calls — the same shape the
//! FilmCraft pilot proved on a real device.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use jni::objects::{JObject, JValue};
use jni::refs::Global;
use jni::{JavaVM, jni_sig, jni_str};
use winit::platform::android::activity::AndroidApp;

/// The manifest the Java side writes; one picked path per line.
const MANIFEST: &str = "import-manifest.txt";

/// Attach the current thread, look up the activity object and run `body`.
fn call<T>(app: &AndroidApp, body: impl FnOnce(&mut jni::Env<'_>, &JObject<'_>) -> jni::errors::Result<T>) -> Option<T> {
    let vm = JavaVM::singleton().ok()?;
    vm.attach_current_thread(|env| -> jni::errors::Result<T> {
        let raw = app.activity_as_ptr() as jni::sys::jobject;
        // SAFETY: AndroidApp owns this global reference for as long as it is borrowed here.
        // as_cast_raw borrows, rather than taking ownership of or deleting, the reference.
        let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        body(env, activity.as_ref())
    })
    .ok()
}

/// Open the system document picker and return the paths copied into the app directory (empty when
/// the user cancels, or on any failure — the caller just does nothing).
///
/// `activity` is `AndroidApp::activity_as_ptr()` (the Java activity object; `ndk_context` hands out
/// the *Application* instead, which has no `startActivityForResult`), and `data_dir` is
/// `AndroidApp::internal_data_path()`, the same `filesDir` the Java side uses.
pub fn pick_media(activity: &AndroidApp, data_dir: &Path, timeout: Duration) -> Vec<String> {
    let manifest: PathBuf = data_dir.join(MANIFEST);

    let picked = call(activity, |env, activity| {
        if !env.call_method(activity, jni_str!("safPickMedia"), jni_sig!("()Z"), &[])?.z()? {
            return Ok(false);
        }
        let deadline = Instant::now() + timeout;
        loop {
            let done = env.call_method(activity, jni_str!("safIsDone"), jni_sig!("()Z"), &[])?.z().unwrap_or(false);
            if done {
                return Ok(true);
            }
            if Instant::now() > deadline {
                log::warn!("SAF: picker timed out after {timeout:?}");
                return Ok(false);
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    });

    match picked {
        Some(true) => read_manifest(&manifest),
        _ => Vec::new(),
    }
}

/// Ask the user where a saved file should land (`ACTION_CREATE_DOCUMENT`); true when a destination
/// was chosen. The URI stays on the Java side until [`publish`] spends it.
pub fn pick_save(activity: &AndroidApp, title: &str, timeout: Duration) -> bool {
    let result = call(activity, |env, activity| {
        let title = env.new_string(title)?;
        if !env.call_method(activity, jni_str!("safPickSave"), jni_sig!("(Ljava/lang/String;)Z"), &[JValue::Object(&title)])?.z()? {
            return Ok(false);
        }
        let deadline = Instant::now() + timeout;
        loop {
            let done = env.call_method(activity, jni_str!("safSaveDone"), jni_sig!("()Z"), &[])?.z().unwrap_or(false);
            if done {
                return env.call_method(activity, jni_str!("safHasSaveUri"), jni_sig!("()Z"), &[])?.z();
            }
            if Instant::now() > deadline {
                log::warn!("SAF: save picker timed out after {timeout:?}");
                return Ok(false);
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    });
    result.unwrap_or(false)
}

/// Copy a finished file to the destination chosen by [`pick_save`].
pub fn publish(activity: &AndroidApp, path: &str) -> bool {
    let result = call(activity, |env, activity| {
        let path = env.new_string(path)?;
        env.call_method(activity, jni_str!("safPublish"), jni_sig!("(Ljava/lang/String;)Z"), &[JValue::Object(&path)])?.z()
    });
    result.unwrap_or(false)
}

/// Open an `https://` link in the browser (Help ▸ Website, About ▸ Discord): the desktop shell
/// spawns `open`/`xdg-open`, Android starts an `ACTION_VIEW` intent instead.
pub fn open_url(activity: &AndroidApp, url: &str) -> bool {
    let result = call(activity, |env, activity| {
        let url = env.new_string(url)?;
        env.call_method(activity, jni_str!("safOpenUrl"), jni_sig!("(Ljava/lang/String;)Z"), &[JValue::Object(&url)])?.z()
    });
    result.unwrap_or(false)
}

fn read_manifest(path: &Path) -> Vec<String> {
    match std::fs::read_to_string(path) {
        Ok(text) => {
            let paths: Vec<String> = text.lines().map(str::trim).filter(|l| !l.is_empty()).map(str::to_owned).collect();
            log::info!("SAF: {} file(s) picked", paths.len());
            paths
        }
        Err(e) => {
            log::info!("SAF: nothing picked ({e})");
            Vec::new()
        }
    }
}
