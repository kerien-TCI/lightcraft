//! LightCraft on Android — GameActivity + eframe/wgpu, reusing the desktop engine and egui UI.
//!
//! Nothing here is Android-specific beyond the entry point, the data directory, the touch size
//! adjustments, the font fallback and the storage bridge: the same `lightcraft_engine::Session` and
//! `lightcraft_ui_egui::LightcraftApp` the desktop shell builds are wired up here, and every action
//! goes through the app's own command ids over the app's own control channel.
//!
//! | Desktop (`apps/lightcraft`) | Android (this crate) |
//! |---|---|
//! | `std::env::args()` (`--library`, `--control`, files) | [`winit::platform::android::activity::AndroidApp`]; the library, settings and export folders come from `$HOME` = the app's private data dir |
//! | rfd file dialogs in `services()` | the SAF pickers in [`saf`]; the result reaches the engine as the *same* `library.import` / `preset.import` the desktop menus run |
//! | `ui.json` written by `PrefsWriter` | the same file (prefs writer below), in `<data>/.config/lightcraft` |
//! | 1600×1000 desktop layout | [`phone_layout`] picks a phone-sized view; [`TouchShell`] enlarges touch targets after LightCraft installs its theme |
//! | macOS `muda` native menu bar | not installed (`native_menu` stays false: the in-window menus are used) |
//!
//! The engine, the UI and the theme are untouched — the menus, the Import dialog, the Develop
//! panels and the export path are the desktop ones. The shell only adjusts egui's spacing *after*
//! LightCraft installs its own theme on the first frame (adding fonts any earlier drops the theme's
//! `FontFamily::Name("semibold")` binding and egui panics).
#![cfg(target_os = "android")]

use std::path::{Path, PathBuf};
use std::sync::mpsc::Sender;
use std::sync::{Arc, OnceLock};

use lightcraft_engine::Session;
use lightcraft_ui_egui::state::{RightPanel, ViewMode};
use lightcraft_ui_egui::{ControlRequest, LightcraftApp, Services, UiState};
use winit::platform::android::activity::AndroidApp;

mod saf;
#[cfg(debug_assertions)]
mod selftest;

/// How much to scale the desktop UI up on a phone before the layout reflows.
///
/// Measured on a 1080×2400 @ 420 dpi phone: egui's logical size is 411×914 pt at
/// `pixels_per_point = 2.625`, while LightCraft's layout is designed for ≥900×560 pt (the desktop
/// window's minimum is 900×560). Scaling up only shrinks the logical viewport further and makes the
/// panels overlap more, so the shell does **not** scale: the answer is a phone layout
/// ([`phone_layout`]) with finger-sized targets, not a zoom. Keep this at 1.0.
const TOUCH_SCALE: f32 = 1.0;

/// The UI context of the running app, published on the first frame: the SAF threads use it to ask
/// for a repaint so the control channel is serviced right away (an idle egui app does not run a
/// frame on its own, and the request would sit in the queue until the next touch).
static UI_CTX: OnceLock<egui::Context> = OnceLock::new();

fn wake() {
    if let Some(ctx) = UI_CTX.get() {
        ctx.request_repaint();
    }
}

/// Touch shell: forward everything to [`LightcraftApp`] and apply the phone adjustments once, after
/// LightCraft's own theme is in place.
struct TouchShell {
    inner: LightcraftApp,
    /// The theme install ran and the touch sizes were applied.
    tuned: bool,
    /// Activity used only for storage pickers.
    activity: AndroidApp,
    exports: PathBuf,
    jpeg_pending: Option<JpegExport>,
    /// The app settings (`ui.json`), written when they change.
    prefs: PrefsWriter,
}

impl TouchShell {
    /// Phone-sized adjustments to egui's spacing: a comfortable hit target (40 pt ≈ 105 px at
    /// 420 dpi), roomier padding and a little more separation so neighbouring controls are harder
    /// to hit by accident. LightCraft's own theme sets the colours and the section heights; this
    /// only touches the metrics a finger cares about.
    fn tune(ctx: &egui::Context) {
        let before = ctx.pixels_per_point();
        if (TOUCH_SCALE - 1.0).abs() > f32::EPSILON {
            ctx.set_pixels_per_point((before * TOUCH_SCALE).min(4.0));
        }
        ctx.all_styles_mut(|style| {
            style.spacing.interact_size = egui::vec2(40.0, 40.0);
            style.spacing.button_padding = egui::vec2(10.0, 8.0);
            style.spacing.item_spacing = egui::vec2(8.0, 8.0);
            style.spacing.slider_width = style.spacing.slider_width.max(160.0);
            // A resting finger must not pop tooltips (they cover the panel they describe).
            style.interaction.show_tooltips_only_when_still = true;
        });
        // LightCraft's layout constants live in `theme::Tokens` (top bar, tool strip, slider rows,
        // filmstrip); `theme::apply` published them once, on the first frame. Growing the bars a
        // little keeps their controls inside the 40 pt targets above.
        let mut tokens = lightcraft_ui_egui::theme::Tokens::get(ctx);
        tokens.top_bar_h = tokens.top_bar_h.max(56.0);
        tokens.bottom_bar_h = tokens.bottom_bar_h.max(58.0);
        tokens.strip_w = tokens.strip_w.max(56.0);
        tokens.slider_row_h = tokens.slider_row_h.max(52.0);
        tokens.section_h = tokens.section_h.max(64.0);
        tokens.film_h = tokens.film_h.max(116.0);
        ctx.data_mut(|d| d.insert_temp(egui::Id::NULL, tokens));
        let rect = ctx.viewport_rect();
        log::info!(
            "lightcraft-android: touch mode applied (ppp {before}, logical {}×{} pt, interact {} pt)",
            rect.width().round(),
            rect.height().round(),
            ctx.style_of(ctx.theme()).spacing.interact_size.x,
        );
    }

    /// Whether the theme's own fonts are live (it defines the named family "semibold"), i.e. the
    /// first frame's theme install has happened.
    fn theme_fonts_live(ctx: &egui::Context) -> bool {
        ctx.fonts(|f| f.definitions().families.keys().any(|k| matches!(k, egui::FontFamily::Name(_))))
    }

    /// Add the device's CJK font to egui's current definitions when it is missing: LightCraft's
    /// bundled Inter has no CJK glyphs (the optional craft-fonts input is not part of this build),
    /// so Chinese/Japanese text would render as tofu.
    ///
    /// Read-modify-write on the *current* definitions, so the theme's own families (including
    /// `FontFamily::Name("semibold")`) stay bound; the font is appended to every family, so text
    /// drawn with the theme's semibold style has glyphs too. Re-installed when a language switch
    /// makes LightCraft rebuild its fonts (`theme::install_fonts` replaces the definitions).
    fn ensure_system_font(ctx: &egui::Context) {
        if ctx.fonts(|f| f.definitions().font_data.contains_key(SYSTEM_FONT)) {
            return;
        }
        if !Self::theme_fonts_live(ctx) {
            return; // the theme's install would drop it again
        }
        const CANDIDATES: &[&str] = &[
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSerifCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/DroidSansFallback.ttf",
        ];
        for path in CANDIDATES {
            let Ok(bytes) = std::fs::read(path) else { continue };
            let mut fonts = ctx.fonts(|f| f.definitions().clone());
            fonts.font_data.insert(SYSTEM_FONT.to_owned(), Arc::new(egui::FontData::from_owned(bytes)));
            for family in fonts.families.values_mut() {
                if !family.iter().any(|name| name == SYSTEM_FONT) {
                    family.push(SYSTEM_FONT.to_owned());
                }
            }
            ctx.set_fonts(fonts);
            log::info!("lightcraft-android: loaded system CJK font {path}");
            return;
        }
        log::warn!("lightcraft-android: no system CJK font found; CJK text will be tofu");
    }
}

/// Name of the fallback font inside egui's definitions.
const SYSTEM_FONT: &str = "system-cjk";

impl eframe::App for TouchShell {
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let _ = UI_CTX.set(ctx.clone());
        self.inner.logic(ctx);
        self.prefs.tick(&self.inner, ctx);
    }

    fn raw_input_hook(&mut self, ctx: &egui::Context, raw_input: &mut egui::RawInput) {
        self.inner.raw_input_hook(ctx, raw_input);
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.inner.ui(ui);
        if !self.tuned {
            // The first `logic` pass runs LightCraft's theme install (`theme::apply`), so the touch
            // adjustments must come after it.
            self.tuned = true;
            Self::tune(ui.ctx());
        }
        Self::ensure_system_font(ui.ctx());
        self.finish_export(ui.ctx());
        if lightcraft_ui_egui::android::take_jpeg_request(ui.ctx()) {
            self.start_export(ui.ctx());
        }
    }

    fn on_exit(&mut self) {
        if let Err(e) = self.prefs.save(&self.inner) {
            log::warn!("lightcraft-android: {e}");
        }
        if let Err(e) = self.inner.session.close_library() {
            log::warn!("lightcraft-android: saving the library failed: {e}");
        }
    }
}

enum JpegExport {
    Rendering(PathBuf),
    Saving(std::sync::mpsc::Receiver<SaveOutcome>),
}

enum SaveOutcome {
    Saved,
    Cancelled,
    Failed,
}

impl TouchShell {
    fn start_export(&mut self, ctx: &egui::Context) {
        if self.jpeg_pending.is_some() {
            self.inner.toast(ctx, "Finish the current export first.");
            return;
        }
        let Some(id) = self.inner.session.active() else { return };
        let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos();
        let output = self.exports.join(format!("Photo-{stamp}.jpg"));
        let path = output.to_string_lossy();
        self.inner.last_export_result = None;
        match self.inner.run(
            "app.export",
            serde_json::json!({"ids": [id.0], "path": path, "format": "jpeg", "quality": 90, "longEdge": 2048, "colorSpace": "srgb", "background": true}),
        ) {
            Ok(_) => self.jpeg_pending = Some(JpegExport::Rendering(output)),
            Err(error) => self.inner.toast(ctx, error),
        }
    }

    fn finish_export(&mut self, ctx: &egui::Context) {
        let Some(pending) = self.jpeg_pending.take() else { return };
        match pending {
            JpegExport::Rendering(path) if self.inner.export.is_some() => {
                self.jpeg_pending = Some(JpegExport::Rendering(path));
            }
            JpegExport::Rendering(path) => {
                let reported = self.inner.last_export_result.as_ref().is_some_and(|report| {
                    report["cancelled"] != true
                        && report["files"].as_array().is_some_and(|files| {
                            files.iter().any(|file| {
                                file["path"].as_str().is_some_and(|p| Path::new(p) == path)
                                    && file["error"].is_null()
                                    && file["bytes"].as_u64().is_some_and(|n| n > 0)
                            })
                        })
                });
                if !reported {
                    let _ = std::fs::remove_file(&path);
                    return;
                }
                let activity = self.activity.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                std::thread::spawn(move || {
                    let result = if saf::pick_save(&activity, "Photo.jpg", std::time::Duration::from_secs(300)) {
                        if saf::publish(&activity, &path.to_string_lossy()) { SaveOutcome::Saved } else { SaveOutcome::Failed }
                    } else {
                        SaveOutcome::Cancelled
                    };
                    if !matches!(result, SaveOutcome::Failed) {
                        let _ = std::fs::remove_file(&path);
                    }
                    let _ = tx.send(result);
                    wake();
                });
                self.jpeg_pending = Some(JpegExport::Saving(rx));
            }
            JpegExport::Saving(rx) => match rx.try_recv() {
                Ok(result) => self.inner.toast(
                    ctx,
                    match result {
                        SaveOutcome::Saved => "JPEG saved.",
                        SaveOutcome::Cancelled => "Save cancelled. The original was not changed.",
                        SaveOutcome::Failed => "Could not save the JPEG. The original was not changed.",
                    },
                ),
                Err(std::sync::mpsc::TryRecvError::Empty) => self.jpeg_pending = Some(JpegExport::Saving(rx)),
                Err(std::sync::mpsc::TryRecvError::Disconnected) => self.inner.toast(ctx, "Could not save the JPEG."),
            },
        }
    }
}

/// Saves `ui.json` (language, view, panel sizes, library location): a few seconds after anything
/// changed, and at exit — the same file, and the same atomic write, as the desktop app.
struct PrefsWriter {
    path: Option<PathBuf>,
    written: Vec<u8>,
    library: String,
    checked: f64,
    failing: bool,
}

impl PrefsWriter {
    fn new(app: &LightcraftApp) -> Self {
        Self {
            path: prefs_path(),
            written: serde_json::to_vec_pretty(&app.ui).unwrap_or_default(),
            library: app.ui.settings.library_path.clone(),
            checked: 0.0,
            failing: false,
        }
    }

    fn save(&mut self, app: &LightcraftApp) -> Result<(), String> {
        let Some(path) = self.path.clone() else { return Ok(()) };
        let bytes = serde_json::to_vec_pretty(&app.ui).map_err(|e| e.to_string())?;
        if bytes == self.written {
            return Ok(());
        }
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).map_err(|e| format!("saving the app settings failed: {e}"))?;
        }
        write_atomic(&path, &bytes).map_err(|e| format!("saving the app settings failed: {e}"))?;
        self.written = bytes;
        self.library = app.ui.settings.library_path.clone();
        Ok(())
    }

    fn tick(&mut self, app: &LightcraftApp, ctx: &egui::Context) {
        let now = ctx.input(|i| i.time);
        let moved = app.ui.settings.library_path != self.library;
        if !moved && now - self.checked < 3.0 {
            return;
        }
        self.checked = now;
        match self.save(app) {
            Ok(()) => self.failing = false,
            Err(e) => {
                if !self.failing {
                    log::warn!("lightcraft-android: {e}");
                }
                self.failing = true;
                // don't retry every frame
                self.library = app.ui.settings.library_path.clone();
            }
        }
    }
}

/// Where the app settings live (`<data>/Settings/ui.json`).
fn prefs_path() -> Option<PathBuf> {
    lightcraft_engine::camera_profiles::config_dir().map(|d| d.join("ui.json"))
}

/// The saved UI state, if any and readable. A damaged file is reported and ignored: the next save
/// overwrites it with the defaults rather than failing every launch.
fn load_prefs() -> Option<UiState> {
    let path = prefs_path()?;
    let bytes = match std::fs::read(&path) {
        Ok(b) => b,
        Err(e) => {
            log::info!("lightcraft-android: no app settings at {} ({e})", path.display());
            return None;
        }
    };
    match serde_json::from_slice::<UiState>(&bytes) {
        Ok(ui) => {
            log::info!("lightcraft-android: app settings loaded from {}", path.display());
            Some(ui.sanitized())
        }
        Err(e) => {
            log::warn!("lightcraft-android: app settings at {} are damaged ({e}); defaults are used", path.display());
            None
        }
    }
}

/// Write `bytes` to `path` atomically: a temp file, fsynced, renamed over it (a crash or a full
/// disk leaves the old file or the new one, never a truncated one).
fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let tmp = path.with_extension("json.tmp");
    let written = (|| {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()
    })();
    if let Err(e) = written {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// The phone-sized view: the photo grid (a phone cannot show the desktop's 270 pt sidebar, the
/// 48 pt tool strip *and* a canvas), with the filmstrip kept and the panels one tap away in the
/// palette. Applied before the saved settings, so a returned user keeps their own choices.
fn phone_layout(app: &mut LightcraftApp) {
    app.ui.view = ViewMode::PhotoGrid;
    app.ui.left_panel = false;
    app.ui.right = RightPanel::None;
    app.ui.presets = false;
    app.ui.filmstrip = true;
    // A phone has far less memory for previews than a desktop, and the emulator has less still.
    // Upstream replaced the old `preview_edge` (a hard 2560 default) with `preview_limit`, where
    // 0 means Automatic; keep the port's 1600 px cap for both Automatic and larger user limits.
    if app.ui.settings.preview_limit == 0 || app.ui.settings.preview_limit > 1600 {
        app.ui.settings.preview_limit = 1600;
    }
    app.ui.thumb_size = app.ui.thumb_size.max(160.0);
}

/// The host services LightCraft asks for. Everything the desktop does with `rfd` goes through the
/// SAF pickers in [`saf`]; everything else (writing exported files, encoding PNGs) is the app's own
/// code, unchanged.
fn services(control: Sender<ControlRequest>, activity: AndroidApp, data_dir: PathBuf, exports: PathBuf) -> Services {
    let pick = |method: &'static str| {
        let (control, data_dir, activity) = (control.clone(), data_dir.clone(), activity.clone());
        move || {
            spawn_pick(control.clone(), activity.clone(), data_dir.clone(), method, "paths");
            Vec::new()
        }
    };
    let save = |exports: PathBuf| {
        let activity = activity.clone();
        move |name: &str| {
            let scratch = scratch_path(&exports, name);
            spawn_publish(activity.clone(), scratch.clone());
            Some(scratch)
        }
    };
    Services {
        pick_files: Some(Box::new(pick("library.import"))),
        pick_preset_files: Some(Box::new(pick("preset.import"))),
        pick_tracklog: Some(Box::new(pick("photo.autoTagTracklog"))),
        pick_curve_preset_files: Some(Box::new(pick("curve.importPresets"))),
        save_preset_file: Some(Box::new(save(exports.clone()))),
        save_curve_preset_file: Some(Box::new(save(exports.clone()))),
        // The engine writes exported files itself (atomically, temp file + rename): unchanged.
        write_shared: Some(Arc::new(lightcraft_engine::export::write_file)),
        write: Some(Box::new(lightcraft_engine::export::write_file)),
        png: Some(Box::new(|img: &lightcraft_raster::Rgba8| {
            lightcraft_codecs::encode_png(&lightcraft_codecs::EncodeImage::rgba8(img), &lightcraft_codecs::EncodeMeta::default()).unwrap_or_default()
        })),
        // The library is a folder inside the app's private storage (there is no `HOME` to browse):
        // the pickers answer with a folder the app can actually write, and the logcat line says
        // which one, rather than handing the engine a `content://` tree it cannot use by path.
        pick_folder: Some(Box::new(move || {
            log::info!("lightcraft-android: folder picker → {}", exports.display());
            Some(exports.to_string_lossy().into_owned())
        })),
        open_url: Some(Box::new(move |url: &str| {
            if !url.starts_with("https://") {
                return Err("only https links are opened".into());
            }
            if saf::open_url(&activity, url) { Ok(()) } else { Err("could not open the link".into()) }
        })),
        // No external editor and no file manager on Android; the UI shows the returned error.
        open_with: Some(Box::new(|_path: &str, _app: &str| Err("there is no external editor on Android".into()))),
        reveal: Some(Box::new(|_path: &str| Err("there is no file manager on Android".into()))),
        backup_library: None,
        restore_library: None,
        // Upstream grew the host service surface: off-thread `picker`, dialogs for denoise models,
        // ICC profiles, keyword lists, slideshow templates and the Lightroom catalogue, external
        // editor discovery, and the system appearance. Their defaults (all `None`) are right on
        // Android: the SAF hooks above cover the dialogs the port supports, and leaving `picker`
        // unset keeps the synchronous pickers serving the commands, as before.
        ..Services::default()
    }
}

/// A scratch path for a "save as" dialog: a sanitized file name inside the app's export directory
/// (the engine writes with plain file APIs, so a real path is what it needs).
fn scratch_path(dir: &Path, name: &str) -> String {
    let leaf = name.rsplit(['/', '\\']).next().unwrap_or(name);
    let leaf = if leaf.is_empty() { "export" } else { leaf };
    let path = dir.join(leaf);
    log::info!("lightcraft-android: save dialog → {}", path.display());
    path.to_string_lossy().into_owned()
}

/// Start the SAF picker for one of LightCraft's own file dialogs.
///
/// The services hooks are synchronous (they answer "which files?" inside the frame) and a picker
/// cannot be answered synchronously without freezing the UI thread, so the hook starts this
/// background task and answers "nothing"; the chosen files reach the engine a moment later over the
/// control channel, which runs the *normal* command (`library.import`, `preset.import`, …) — the
/// same path the desktop menu takes once the files are known.
fn spawn_pick(control: Sender<ControlRequest>, activity: AndroidApp, data_dir: PathBuf, method: &'static str, key: &'static str) {
    std::thread::spawn(move || {
        let paths = saf::pick_media(&activity, &data_dir, std::time::Duration::from_secs(300));
        if paths.is_empty() {
            log::info!("lightcraft-android: nothing picked");
            return;
        }
        let params = if key == "path" { serde_json::json!({key: paths[0]}) } else { serde_json::json!({key: paths}) };
        let (request, reply) = ControlRequest::new("engine.execute", serde_json::json!({"command": method, "params": params}));
        match control.send(request) {
            Ok(()) => log::info!("lightcraft-android: sent {method} for {} file(s)", paths.len()),
            Err(_) => {
                log::warn!("lightcraft-android: control channel is closed");
                return;
            }
        }
        wake();
        match reply.recv_timeout(std::time::Duration::from_secs(120)) {
            Ok(value) => log::info!("lightcraft-android: {method} replied {value}"),
            Err(e) => log::warn!("lightcraft-android: no reply for {method}: {e}"),
        }
    });
}

/// Ask where a saved file should land, wait for the app to finish writing the scratch file, then
/// publish it there. Nothing is lost when the user cancels: the file stays in the export directory.
fn spawn_publish(activity: AndroidApp, scratch: String) {
    std::thread::spawn(move || {
        let title = scratch.rsplit('/').next().unwrap_or("export").to_owned();
        if !saf::pick_save(&activity, &title, std::time::Duration::from_secs(300)) {
            log::info!("SAF: no export destination chosen; the file stays at {scratch}");
            return;
        }
        // The app writes the file after the dialog: wait until it exists and its size has stopped
        // changing for a moment.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(900);
        let (mut size, mut stable_since) = (0u64, std::time::Instant::now());
        loop {
            let now = std::fs::metadata(&scratch).map(|m| m.len()).unwrap_or(0);
            if now != size {
                size = now;
                stable_since = std::time::Instant::now();
            } else if size > 0 && stable_since.elapsed() > std::time::Duration::from_millis(1500) {
                break;
            }
            if std::time::Instant::now() > deadline {
                log::warn!("SAF: giving up on publishing {scratch} (nothing was written)");
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(400));
        }
        if saf::publish(&activity, &scratch) {
            log::info!("SAF: published {scratch} ({size} bytes) to the chosen destination");
        } else {
            log::warn!("SAF: publishing {scratch} failed; the file stays in the export directory");
        }
    });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("LightCraft"));
    log::info!("lightcraft-android: android_main entered");
    // Use the engine's explicit per-process profile. Mutating HOME would be unsafe with JVM
    // and Android system threads already running.
    let Some(data_dir) = app.internal_data_path() else {
        log::error!("lightcraft-android: application-private storage is unavailable");
        return;
    };
    if lightcraft_engine::config::profile_root() != Some(data_dir.as_path()) {
        if let Err(error) = lightcraft_engine::config::set_profile_root(data_dir.clone()) {
            log::error!("lightcraft-android: could not select app profile: {error}");
            return;
        }
    }
    // A panic anywhere in the engine or the UI is written here before the process dies, so a crash
    // on a phone (where stderr is gone) still leaves a record next to the library.
    lightcraft_engine::guard::install_hook(data_dir.join("lightcraft-panics.log"));
    // Self test on its own thread: generate the procedural demo library and render, encode, decode
    // and store one photo through the engine's own paths (logcat evidence that the still pipeline
    // works on the device). It never panics and never touches the user's library.
    #[cfg(debug_assertions)]
    {
        let scratch = data_dir.clone();
        std::thread::spawn(move || log::info!("lightcraft-android: {}", selftest::run(&scratch)));
    }
    // Exports, preset exports and anything else the user creates: the app's external files dir when
    // there is one (reachable over MTP/`adb pull`), else the private one.
    let exports = data_dir.join("exports");
    if let Err(e) = std::fs::create_dir_all(&exports) {
        log::warn!("lightcraft-android: cannot create {}: {e}", exports.display());
    }
    log::info!("lightcraft-android: export directory {}", exports.display());
    // Where the library lives, and where it came from; the UI shows it in Settings → General.
    let library_dir = data_dir.join("Library");
    log::info!("lightcraft-android: library {}", library_dir.display());
    // The Java activity object: `ndk_context` only hands out the Application, which cannot start
    // the document picker for a result.
    let activity = app.clone();
    // GPU compute (the develop pipeline's wgpu device) is off on Android by default: the window
    // itself is still drawn by the GPU through eframe/wgpu, but a *second* device created inside a
    // phone's driver is the one place LightCraft has been seen to take the process down (see
    // `gpu::backend`'s init marker). Settings ▸ Performance turns it back on.
    lightcraft_engine::gpu::set_enabled(false);
    let prefs = load_prefs();
    let started = eframe::run_native(
        "Photo Lab Android",
        eframe::NativeOptions { android_app: Some(app), ..Default::default() },
        Box::new(move |_cc| {
            // The engine's own session: filesystem services, a system clock, the library on disk.
            let mut session = Session::new().with_fs().with_system_clock();
            let mut problem = None;
            // `open_library` returns `Result<&LoadReport>`, so the borrow is tied to `session`.
            // Copy the one field out, then drop the borrow before touching `session.catalog`.
            let opened = session.open_library(&library_dir, true).map(|report| report.replayed);
            match opened {
                Ok(replayed) => {
                    log::info!("lightcraft-android: library opened: {} photos, {replayed} log records replayed", session.catalog.len());
                }
                Err(e) => {
                    log::error!("lightcraft-android: can't open the library {}: {e}", library_dir.display());
                    problem = Some(lightcraft_ui_egui::panels::library_problem::LibraryProblem::new(library_dir.to_string_lossy(), e.to_string()));
                }
            }
            // The command palette's exports and the folder pickers write here.
            let (control, requests) = std::sync::mpsc::channel::<ControlRequest>();
            let mut app = LightcraftApp::new(session, services(control, activity.clone(), data_dir.clone(), exports.clone()));
            phone_layout(&mut app);
            if let Some(ui) = prefs {
                app.ui = ui;
            }
            app.ui.settings.gpu = false;
            lightcraft_ui_egui::i18n::set_language(app.ui.language);
            app.library_problem = problem;
            // Upstream shows everything in `notices` as a modal "Settings file problem" window, so
            // this informational line belongs in the status bar, not in a dialog that blocks the
            // first frame on every launch.
            app.ui.status = format!("Exports are saved to {}.", exports.display());
            let app = app.with_control(requests);
            let prefs = PrefsWriter::new(&app);
            Ok(Box::new(TouchShell { inner: app, tuned: false, activity, exports, jpeg_pending: None, prefs }))
        }),
    );
    if let Err(e) = started {
        log::error!("lightcraft-android: eframe exited: {e}");
    }
}
