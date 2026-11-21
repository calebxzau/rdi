//! Shared shell state and layout bridge, independent of room/login jobs in app.rs.
use crate::{
    AppWindow, GlassShape,
    app::Interaction,
    navigation::{Navigation, Screen},
};
use rdi_renderer::glass;
use slint::{ComponentHandle, Model};
use std::{
    cell::{Cell, RefCell},
    rc::Rc,
    time::{Duration, Instant},
};

pub struct Shell {
    ui: slint::Weak<AppWindow>,
    navigation: Navigation,
    menu_started: bool,
    toast_since: Rc<Cell<Option<Instant>>>,
    _gallery: Rc<RefCell<crate::gallery::Gallery>>,
    wardrobe: Rc<RefCell<crate::wardrobe::Wardrobe>>,
}
impl Shell {
    pub fn new(
        ui: &AppWindow,
        gallery: bool,
        runtime: tokio::runtime::Handle,
    ) -> Rc<RefCell<Self>> {
        let mut navigation = Navigation::default();
        if gallery {
            navigation.push(Screen::Gallery);
        }
        ui.set_gallery_enabled(gallery);
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            navigation,
            menu_started: !gallery,
            toast_since: Rc::new(Cell::new(None)),
            _gallery: crate::gallery::Gallery::wire(ui, runtime.clone()),
            wardrobe: crate::wardrobe::Wardrobe::wire(ui, runtime),
        }));
        state.borrow().sync(ui);
        let weak = Rc::downgrade(&state);
        ui.on_navigate(move |action| {
            if let Some(state) = weak.upgrade() {
                let mut s = state.borrow_mut();
                let Some(ui) = s.ui.upgrade() else {
                    return;
                };
                if ui.get_login_visible() {
                    if action == "back" {
                        ui.set_login_register(false);
                        ui.set_login_history(false);
                        ui.set_action_error("".into());
                    }
                    return;
                }
                if ui.get_modal_open() {
                    if action == "back" {
                        if ui.get_local_play() { ui.invoke_pack_action("play.cancel".into(), "".into()); }
                        else if ui.get_install_missing() { ui.invoke_pack_action("installer.dismissMissing".into(), "".into()); }
                        else if ui.get_picked_candidates().row_count() > 0 { ui.invoke_pack_action("installer.dismissCandidates".into(), "".into()); }
                        else if !ui.get_action_error().is_empty() { ui.set_action_error("".into()); ui.invoke_pack_action("installer.dismissError".into(), "".into()); }
                        else { ui.set_pack_menu(false); ui.set_discard_options(false); ui.set_duplicate_host("".into()); ui.set_confirm_exit(false); ui.set_gallery_dialog(false); }
                    }
                    return;
                }
                match action.as_str() {
                    "install" => { s.navigation.push(Screen::LocalModpackInstall); ui.invoke_pack_action("installer.open".into(), "".into()); }
                    "options" => s.navigation.push(Screen::ModpackLocalAdvanceOption),
                    "plaza" if s.navigation.current() == Screen::Menu => {
                        s.navigation.push(Screen::ModpackPlaza);
                        ui.invoke_plaza_action("open".into(), "".into());
                    }
                    "plaza-detail" if matches!(s.navigation.current(), Screen::Menu | Screen::ModpackPlaza) => s.navigation.push(Screen::ModpackDetail),
                    "plaza-version" if s.navigation.current() == Screen::ModpackDetail => s.navigation.push(Screen::ModpackVersionDetail),
                    "packs" => { s.navigation.home(); ui.invoke_select_tab(1); }
                    "back" | "home" if ui.get_pack_screen() == 2 && ui.get_options_dirty() => { ui.set_discard_options(true); return; }
                    "back" if ui.get_pack_screen() == 1 && ui.get_install_editor() => {ui.invoke_pack_action("installer.back".into(), "".into()); return;}
                    "back" if ui.get_activity_panel() != 0 => ui.set_activity_panel(0),
                    "back" => {
                        if ui.get_pack_screen() == 1 { ui.invoke_pack_action("installer.close".into(), "".into()); }
                        if ui.get_pack_screen() == 2 { ui.invoke_pack_action("options.close".into(), "".into()); }
                        let was_pack = ui.get_pack_screen() != 0;
                        s.navigation.back();
                        if was_pack && s.navigation.current() == Screen::Menu {ui.invoke_select_tab(1);}
                    },
                    "home" => {
                        if ui.get_pack_screen() == 1 { ui.invoke_pack_action("installer.close".into(), "".into()); }
                        if ui.get_pack_screen() == 2 { ui.invoke_pack_action("options.close".into(), "".into()); }
                        s.navigation.home();
                        ui.set_activity_panel(0);
                    }
                    "host-list" if s.navigation.current() == Screen::Menu => s.navigation.push(Screen::HostList),
                    "gallery" if ui.get_gallery_enabled() => s.navigation.push(Screen::Gallery),
                    "wardrobe" => {
                        s.navigation.push(Screen::Wardrobe);
                        s.wardrobe.borrow_mut().open(&ui);
                    }
                    "console" => { ui.set_activity_panel(1); ui.invoke_pack_action("console.subscribe".into(), "".into()); }
                    "tasks" => { ui.set_activity_panel(2); ui.invoke_pack_action("tasks.subscribe".into(), "".into()); }
                    _ => {
                        ui.invoke_stub(action);
                    }
                }
                s.sync(&ui);
                if s.navigation.current() == Screen::Menu && !s.menu_started {
                    s.menu_started = true;
                    ui.invoke_retry();
                }
            }
        });
        let toast_since = state.borrow().toast_since.clone();
        let weak_ui = ui.as_weak();
        ui.on_notify(move |message| {
            if let Some(ui) = weak_ui.upgrade() {
                ui.set_toast_message(message);
            }
            toast_since.set(Some(Instant::now()));
        });
        state
    }
    fn sync(&self, ui: &AppWindow) {
        ui.set_gallery(self.navigation.current() == Screen::Gallery);
        ui.set_host_list_visible(self.navigation.current() == Screen::HostList);
        ui.set_wardrobe_visible(self.navigation.current() == Screen::Wardrobe);
        ui.set_pack_screen(match self.navigation.current() {
            Screen::LocalModpackInstall => 1,
            Screen::ModpackLocalAdvanceOption => 2,
            Screen::ModpackPlaza => 3,
            Screen::ModpackDetail => 4,
            Screen::ModpackVersionDetail => 5,
            _ => 0,
        });
        ui.set_can_back(self.navigation.can_back());
    }
    pub fn scene_frozen(&self, ui: &AppWindow) -> bool {
        ui.get_login_visible() || self.navigation.scene_frozen(ui.get_game_running()) || ui.get_activity_panel() != 0
    }
    pub fn tick(&mut self) {
        self._gallery.borrow_mut().tick();
        self.wardrobe.borrow_mut().tick();
        if self
            .toast_since
            .get()
            .is_some_and(|t| t.elapsed() >= Duration::from_secs(5))
        {
            if let Some(ui) = self.ui.upgrade() {
                ui.set_toast_message("".into());
            }
            self.toast_since.set(None);
        }
    }
}
fn rect(s: &GlassShape) -> [f32; 4] {
    [s.x, s.y, s.w, s.h]
}
fn panel(s: GlassShape) -> glass::Panel {
    glass::Panel {
        rect: rect(&s),
        corner_radius: s.radius,
        opacity: s.alpha,
        tint: [
            s.tint.red() as f32 / 255.,
            s.tint.green() as f32 / 255.,
            s.tint.blue() as f32 / 255.,
            s.tint.alpha() as f32 / 255.,
        ],
    }
}
/// Slint alone owns geometry. Rust contributes interaction springs and domain row counts.
pub fn frame(
    ui: &AppWindow,
    interactions: &[Interaction; 2],
    pills: &[crate::anim::Spring; 5],
    pivots: &[[f32; 2]; 5],
    selected: usize,
) -> glass::Frame {
    if ui.get_ui_proof() {
        return crate::ui_proof::frame(ui);
    }
    let size = ui.window().size();
    let mut f = glass::Frame {
        width: size.width as i32,
        height: size.height as i32,
        scale: ui.window().scale_factor(),
        grids: vec![],
        pills: vec![],
        panels: vec![],
    };
    if ui.get_login_visible() {
        f.panels.push(panel(ui.get_login_panel()));
    } else if ui.get_gallery() {
        f.panels.extend(
            ui.get_glass_panels()
                .iter()
                .take(2)
                .enumerate()
                .filter(|(i, _)| ui.get_gallery_page() != 3 || *i != 0)
                .map(|(_, s)| panel(s)),
        );
        if ui.get_gallery_page() == 3 {
            let shape = ui.get_gallery_grid();
            f.grids.push(glass::Grid {
                clip: rect(&shape),
                x: shape.x,
                y: shape.y,
                row_pitch: crate::hosts::PITCH,
                tile_size: crate::hosts::TILE,
                columns: None,
                row_offsets: [0.0; 2],
                scroll: ui.get_gallery_scroll(),
                count: ui.get_gallery_tiles().row_count(),
                opacity: 1.,
                hover: ui.get_gallery_hover(),
                hover_progress: 1.,
                pressed: ui.get_gallery_pressed(),
                press_scale: ui.get_gallery_press_scale(),
            });
        }
    } else if ui.get_pack_screen() == 0 && !ui.get_wardrobe_visible() && !ui.get_host_list_visible() {
        if !ui.get_loading() && ui.get_error().is_empty() {
            let row_pitch = ui.get_menu_grid_row_pitch();
            let tile_size = ui.get_menu_grid_tile_size();
            for (tab, shape) in ui.get_menu_grids().iter().enumerate() {
                let i = &interactions[tab];
                f.grids.push(glass::Grid {
                    clip: rect(&shape),
                    x: shape.x,
                    y: shape.y,
                    row_pitch,
                    tile_size,
                    columns: Some(ui.get_menu_grid_columns().max(1) as usize),
                    row_offsets: [
                        ui.get_menu_grid_row_offset(),
                        if tab == 0 {
                            ui.get_host_grid_last_row_offset()
                        } else {
                            ui.get_pack_grid_last_row_offset()
                        },
                    ],
                    scroll: if tab == 0 {
                        ui.get_host_scroll()
                    } else {
                        ui.get_modpack_scroll()
                    },
                    count: if tab == 0 {
                        ui.get_hosts().row_count()
                    } else {
                        ui.get_modpacks().row_count()
                    },
                    opacity: shape.alpha,
                    hover: i.hover,
                    hover_progress: i.hover_spring.value,
                    pressed: i.pressed,
                    press_scale: 1. - 0.04 * i.press_spring.value,
                });
            }
        }
        for (index, shape) in ui.get_menu_pills().iter().enumerate() {
            let spring = match index {
                0 => None,
                1 => Some(selected),
                2..=4 => Some(index),
                _ => continue,
            };
            f.pills.push(glass::Pill {
                rect: rect(&shape),
                selected: index == 1,
                progress: spring.map_or(0., |i| pills[i].value),
                pivot: spring.map_or([0.; 2], |i| pivots[i]),
            });
        }
    }
    if ui.get_pack_screen() != 0 {
        f.panels.extend(ui.get_pack_panels().iter().filter(|s| s.alpha > 0.).map(panel));
    }
    if ui.get_modal_open()
        && let Some(shape) = ui.get_glass_panels().row_data(2)
    {
        f.panels.push(panel(shape));
    }
    f
}
