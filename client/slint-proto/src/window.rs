use crate::AppWindow;
use slint::{ComponentHandle, winit_030::WinitWindowAccessor};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
pub fn wire(ui: &AppWindow, paused: Arc<AtomicBool>, probe_unfocused: bool) {
    let weak = ui.as_weak();
    ui.on_window_action(move |action| {
        if let Some(ui) = weak.upgrade() {
            if action == 3 {
                if let Err(e) = slint::quit_event_loop() {
                    tracing::error!(error=?e,"Closing window failed");
                }
                return;
            }
            ui.window().with_winit_window(|w| match action {
                0 => {
                    if let Err(e) = w.drag_window() {
                        tracing::warn!(error=?e,"Window drag failed");
                    }
                }
                1 => w.set_minimized(true),
                2 => w.set_maximized(!w.is_maximized()),
                _ => {}
            });
        }
    });
    ui.window().on_winit_window_event(move |window, event| {
        let focus = match event {
            slint::winit_030::winit::event::WindowEvent::Focused(value) => Some(*value),
            _ => None,
        };
        window.with_winit_window(|w| {
            let inactive = w.is_minimized().unwrap_or(false)
                || !w.is_visible().unwrap_or(true)
                || (!probe_unfocused && !focus.unwrap_or_else(|| w.has_focus()));
            if paused.swap(inactive, Ordering::Relaxed) != inactive {
                tracing::info!(active = !inactive, "Scene window activity changed");
            }
        });
        slint::winit_030::EventResult::Propagate
    });
}
