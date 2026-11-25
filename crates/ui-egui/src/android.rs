//! Phone presentation; engine commands and editor widgets are shared with the desktop.

use crate::state::{RightPanel, ViewMode};
use crate::{LightcraftApp, i18n::tr, panels, theme::Tokens, widgets};
use egui::{Context, Id, Ui, UiBuilder};
use serde_json::{Value, json};

fn export_id() -> Id {
    Id::unique("android-save-jpeg")
}

/// The host consumes the request after drawing the UI. The Android picker owns the output location.
pub fn take_jpeg_request(ctx: &Context) -> bool {
    ctx.data_mut(|data| data.remove_temp::<bool>(export_id()).unwrap_or(false))
}

fn action(ui: &mut Ui, label: &str, enabled: bool, selected: bool) -> bool {
    ui.add_enabled(enabled, egui::Button::new(tr(label)).selected(selected).min_size(egui::vec2(48.0, 48.0))).clicked()
}

fn run(app: &mut LightcraftApp, ctx: &Context, command: &str, params: Value) {
    if let Err(error) = app.run(command, params) {
        app.toast(ctx, error);
    }
}

pub(crate) fn show(app: &mut LightcraftApp, ui: &mut Ui) {
    let ctx = ui.ctx().clone();
    let tokens = Tokens::get(&ctx);
    let has_photo = app.session.active().is_some();
    let detail = app.ui.view == ViewMode::Detail;
    let editing = detail && app.ui.right != RightPanel::None;
    let toolbar = egui::Frame::NONE.fill(tokens.chrome).inner_margin(egui::Margin::symmetric(8, 2));
    egui::Panel::top("android-toolbar").frame(toolbar).show(ui, |ui| {
        widgets::announce_region(ui, egui::accesskit::Role::Toolbar, tr("Photo tools"));
        ui.horizontal_wrapped(|ui| {
            if action(ui, "Photos", true, !detail) {
                run(app, &ctx, "view.photoGrid", json!({}));
            }
            if action(ui, "Import…", true, false) {
                run(app, &ctx, "file.addPhotos", json!({}));
            }
            if action(ui, "Edit", has_photo, editing) {
                if editing {
                    run(app, &ctx, "panel.close", json!({}));
                } else {
                    run(app, &ctx, "view.detail", json!({}));
                    run(app, &ctx, "panel.edit", json!({}));
                }
            }
            if action(ui, "Save JPEG…", has_photo && app.export.is_none(), false) {
                ctx.data_mut(|data| data.insert_temp(export_id(), true));
            }
            if ui
                .menu_button(tr("More"), |ui| {
                    for (label, id) in [("Settings", "app.settings"), ("About", "app.about")] {
                        if action(ui, label, true, false) {
                            run(app, &ctx, id, json!({}));
                            ui.close();
                        }
                    }
                })
                .response
                .clicked()
            {
                ctx.request_repaint();
            }
        });
    });
    panels::library_problem::banner(app, ui);
    if !matches!(app.ui.view, ViewMode::PhotoGrid | ViewMode::SquareGrid | ViewMode::Detail) {
        app.ui.view = ViewMode::PhotoGrid;
    }
    if app.ui.view == ViewMode::Detail && app.ui.right != RightPanel::None && has_photo {
        let height = (ui.available_height() * 0.47).max(1.0);
        egui::Panel::bottom("android-edit-sheet").exact_size(height).show(ui, |ui| {
            widgets::announce_region(ui, egui::accesskit::Role::Complementary, tr("Photo adjustments"));
            ui.horizontal_wrapped(|ui| {
                for (label, panel, command) in [
                    ("Edit", RightPanel::Edit, "panel.edit"),
                    ("Crop", RightPanel::Crop, "panel.crop"),
                    ("Masking", RightPanel::Masking, "panel.masking"),
                    ("Info", RightPanel::Info, "panel.info"),
                ] {
                    if action(ui, label, true, app.ui.right == panel) {
                        run(app, &ctx, command, json!({}));
                    }
                }
            });
            ui.scope_builder(UiBuilder::new().id_salt("android-edit"), |ui| panels::right::show(app, ui));
        });
    }
    egui::CentralPanel::default().frame(egui::Frame::NONE.fill(if app.ui.view == ViewMode::Detail { tokens.canvas } else { tokens.grid_bg })).show(
        ui,
        |ui| {
            widgets::announce_region(ui, egui::accesskit::Role::Main, tr("Photos"));
            if app.ui.view == ViewMode::Detail {
                panels::detail::show(app, ui);
            } else {
                panels::grid::show(app, ui);
            }
        },
    );
}
