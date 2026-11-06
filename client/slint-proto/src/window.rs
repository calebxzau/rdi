use crate::AppWindow;
use slint::{ComponentHandle, winit_030::WinitWindowAccessor};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
pub fn wire(ui: &AppWindow, paused: Arc<AtomicBool>, probe_unfocused: bool) {
    let close_ui = ui.as_weak();
    ui.window().on_close_requested(move || {
        if let Some(ui) = close_ui.upgrade() && ui.get_game_running() {
            ui.set_confirm_exit(true);
            slint::CloseRequestResponse::KeepWindowShown
        } else {
            slint::CloseRequestResponse::HideWindow
        }
    });
    let weak = ui.as_weak();
    ui.on_window_action(move |action| {
        if let Some(ui) = weak.upgrade() {
            if action == 3 && ui.get_game_running() {
                ui.set_confirm_exit(true);
                return;
            }
            if action == 3 || action == 4 {
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
    let resize_ui = ui.as_weak();
    ui.on_window_resize(move |edge| {
        if let Some(ui) = resize_ui.upgrade() {
            ui.window().with_winit_window(|w| {
                use slint::winit_030::winit::window::ResizeDirection;
                let direction = match edge {
                    0 => ResizeDirection::West,
                    1 => ResizeDirection::East,
                    2 => ResizeDirection::North,
                    3 => ResizeDirection::South,
                    4 => ResizeDirection::SouthEast,
                    5 => ResizeDirection::SouthWest,
                    6 => ResizeDirection::NorthEast,
                    7 => ResizeDirection::NorthWest,
                    other => {
                        tracing::error!(edge = other, "Unknown window resize edge");
                        return;
                    }
                };
                if let Err(error) = w.drag_resize_window(direction) { tracing::warn!(?error,"Window resize failed"); }
            });
        }
    });
    let state_ui = ui.as_weak();
    ui.window().on_winit_window_event(move |window, event| {
        let focus = match event {
            slint::winit_030::winit::event::WindowEvent::Focused(value) => Some(*value),
            _ => None,
        };
        window.with_winit_window(|w| {
            if let slint::winit_030::winit::event::WindowEvent::Resized(_) = event
                && let Some(ui) = state_ui.upgrade()
            {
                ui.set_window_maximized(w.is_maximized());
            }
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
