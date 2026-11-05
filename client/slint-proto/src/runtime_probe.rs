//! Diagnostic-only state/commands used by the Windows P5 harness. Never polls files in normal runs.
use crate::AppWindow;
use slint::{ComponentHandle, winit_030::WinitWindowAccessor};
#[derive(Default)]
pub struct Stats {
    pub scene_frames: u64,
    pub ui_frames: u64,
    pub bound: bool,
    pub loading: bool,
}
pub fn heartbeat(ui: &AppWindow, stats: &Stats, elapsed: f64, active: bool, enabled: bool) {
    ui.window().with_winit_window(|w| {
        tracing::info!(data=%serde_json::json!({"scene_frames":stats.scene_frames,"ui_frames":stats.ui_frames,"bound":stats.bound,"loading":stats.loading,"elapsed":elapsed,"active":active,"enabled":enabled,"focused":w.has_focus(),"minimized":w.is_minimized(),"visible":w.is_visible(),"width":w.inner_size().width,"height":w.inner_size().height,"scale":ui.window().scale_factor()}),"Runtime probe");
    });
}
#[derive(serde::Deserialize)]
struct Command {
    id: u64,
    action: String,
    value: Option<f32>,
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
