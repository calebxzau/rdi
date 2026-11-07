//! Diagnostic-only state/commands used by the Windows P5 harness. Never polls files in normal runs.
use crate::AppWindow;
use slint::{ComponentHandle, Model, winit_030::WinitWindowAccessor};
#[derive(Default)]
pub struct Stats {
    pub scene_frames: u64,
    pub ui_frames: u64,
    pub bound: bool,
    pub loading: bool,
}
pub fn heartbeat(ui: &AppWindow, stats: &Stats, elapsed: f64, active: bool, enabled: bool) {
    ui.window().with_winit_window(|w| {
        let pack_data = serde_json::json!({"pack_screen":ui.get_pack_screen(),"pack_rows":ui.get_modpacks().row_count(),"packs_loading":ui.get_packs_loading(),"packs_error":ui.get_packs_error().as_str(),"install_editor":ui.get_install_editor(),"install_can_submit":ui.get_install_can_submit(),"install_loading":ui.get_install_loading(),"install_error":ui.get_install_error().as_str(),"install_missing":ui.get_install_missing(),"install_contents":ui.get_install_contents().row_count(),"options_dirty":ui.get_options_dirty(),"options_error":ui.get_options_error().as_str(),"local_play":ui.get_local_play(),"play_error":ui.get_play_error().as_str(),"activity_rows":ui.get_activity_tasks().row_count(),"activity_message":ui.get_activity_message().as_str(),"activity_error":ui.get_activity_error().as_str(),"activity_fraction":ui.get_activity_fraction(),"activity_logs":ui.get_activity_logs().row_count(),"game_status":ui.get_game_status().as_str(),"action_error":ui.get_action_error().as_str(),"duplicate":!ui.get_duplicate_host().is_empty(),"selected_tab":ui.get_selected()});
        let mut data = serde_json::json!({"content_scroll":ui.get_gallery_content_scroll(),"tile_scroll":ui.get_gallery_scroll(),"dialog_focused":ui.get_dialog_focused(),"password_revealed":ui.get_gallery_password_revealed(),"toast_visible":!ui.get_toast_message().is_empty(),"gallery":ui.get_gallery(),"gallery_page":ui.get_gallery_page(),"content_state":ui.get_gallery_content_state(),"content_rows":ui.get_gallery_items().row_count(),"task_rows":ui.get_gallery_tasks().row_count(),"log_rows":ui.get_gallery_logs().row_count(),"modal":ui.get_modal_open(),"game_running":ui.get_game_running(),"image_state":ui.get_gallery_image_state(),"can_back":ui.get_can_back(),"scene_frames":stats.scene_frames,"ui_frames":stats.ui_frames,"bound":stats.bound,"loading":stats.loading,"elapsed":elapsed,"active":active,"enabled":enabled,"focused":w.has_focus(),"minimized":w.is_minimized(),"visible":w.is_visible(),"width":w.inner_size().width,"height":w.inner_size().height,"scale":ui.window().scale_factor()});
        data.as_object_mut().unwrap().extend(pack_data.as_object().unwrap().clone());
        tracing::info!(data=%data,"Runtime probe");
    });
}
#[derive(serde::Deserialize)]
struct Command {
    id: u64,
    action: String,
    value: Option<f32>,
    name: Option<String>,
    text: Option<String>,
}
pub struct Control {
    path: Option<std::path::PathBuf>,
    last: u64,
}
impl Control {
    pub fn new() -> Self {
        Self {
            path: std::env::var_os("RDI_PROTO_CONTROL").map(Into::into),
            last: 0,
        }
    }
    pub fn poll(&mut self, ui: &AppWindow, scene_frozen: &std::cell::Cell<bool>) {
        let Some(path) = &self.path else {
            return;
        };
        let text = match std::fs::read_to_string(path) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return,
            Err(error) => {
                tracing::error!(?error, "Runtime control read failed");
                return;
            }
        };
        let command: Command = match serde_json::from_str(&text) {
            Ok(c) => c,
            Err(error) => {
                tracing::error!(?error, "Runtime control parse failed");
                return;
            }
        };
        if command.id <= self.last {
            return;
        }
        self.last = command.id;
        match command.action.as_str() {
            "pack-fixture" if ui.get_gallery_enabled() => ui.invoke_pack_proof(command.name.clone().unwrap_or_default().into(), command.text.clone().unwrap_or_default().into()),
            "shell-tasks" => ui.invoke_navigate("tasks".into()),
            "shell-console" => ui.invoke_navigate("console".into()),
            "packs-tab" => ui.invoke_select_tab(1),
            "packs-install" => ui.invoke_navigate("install".into()),
            "packs-options" => ui.invoke_navigate("options".into()),
            "pack-tile" => ui.invoke_tile_action(1, command.value.unwrap_or(0.) as i32, command.text.as_deref()==Some("right")),
            "pack-action" => ui.invoke_pack_action(command.name.clone().unwrap_or_default().into(), command.text.clone().unwrap_or_default().into()),
            "gallery-page" if ui.get_gallery_enabled() => ui.set_gallery_page(command.value.unwrap_or(0.) as i32),
            "gallery-state" if ui.get_gallery_enabled() => {ui.set_gallery_content_state(command.value.unwrap_or(0.) as i32); ui.invoke_gallery_filter("".into(),0,false);}
            "gallery-dialog" if ui.get_gallery_enabled() => ui.set_gallery_dialog(command.value.unwrap_or(0.) > 0.),
            "gallery-toast" if ui.get_gallery_enabled() => ui.invoke_notify("房间已准备好，与朋友一起开始冒险吧".into()),
            "shell-home" => ui.invoke_navigate("home".into()),
            "shell-back" => ui.invoke_navigate("back".into()),
            "shell-gallery" if ui.get_gallery_enabled() => ui.invoke_navigate("gallery".into()),
            // Explicit probe-only simulation, never an assertion of a running Minecraft process.
            "shell-game-probe" if ui.get_gallery_enabled() => ui.set_game_running(command.value.unwrap_or(0.) > 0.),
            "proof-start" => { ui.invoke_proof_start(); }
            "scene-freeze" => { scene_frozen.set(command.value.unwrap_or(0.) > 0.); ui.window().request_redraw(); }
            "proof-panel" => { ui.set_proof_panel_open(command.value.unwrap_or(0.) > 0.); }
            "proof-freeze" => { ui.set_proof_freeze(command.value.unwrap_or(-1.)); }
            "proof-modal" => { ui.set_proof_modal_open(command.value.unwrap_or(0.) > 0.); }
            "proof-login" => {
                match crate::config::Config::credentials() {
                    Ok((user, password)) => ui.invoke_proof_submit(user.into(), password.into()),
                    Err(error) => tracing::error!(?error, "U1 account environment missing"),
                }
            }
            "hide" => {
                // Hiding Slint's last component unregisters it and exits its event loop.
                // Keep that component registered to exercise native visibility pause/resume.
                ui.window().with_winit_window(|w| w.set_visible(false));
            }
            "show" => {
                ui.window().with_winit_window(|w| w.set_visible(true));
            }
            "scale" => {
                if let Some(scale) = command
                    .value
                    .filter(|v| v.is_finite() && (0.5..=4.).contains(v))
                {
                    ui.window()
                        .dispatch_event(slint::platform::WindowEvent::ScaleFactorChanged {
                            scale_factor: scale,
                        });
                    ui.window().request_redraw();
                }
            }
            "focus" => {
                ui.window().with_winit_window(|w| w.focus_window());
            }
            _ => tracing::error!(action = command.action, "Unknown runtime control"),
        }
        tracing::info!(
            id = command.id,
            action = command.action,
            "Runtime control applied"
        );
    }
}
