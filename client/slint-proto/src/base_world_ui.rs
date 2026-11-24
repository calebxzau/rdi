//! U4e3: wiring of 地图模板, 创建地图模板 and 初始地图模板 to the `Worlds` global, shaped like
//! `mail_ui.rs`. The rules and the upload task are in `base_world/`.
use crate::{
    AppWindow, WorldCard, WorldDetailRow, Worlds,
    api::Client,
    base_world::{
        self, BaseWorld, LevelChoice,
        api::{Binding, HttpWorldApi},
        upload::{self, Request},
    },
    images,
    packs::human_file_size,
    tasks,
};
use anyhow::Result;
use slint::{ComponentHandle, Model, VecModel};
use std::{
    cell::RefCell,
    collections::HashMap,
    path::PathBuf,
    rc::Rc,
    sync::{Arc, mpsc},
    time::{Duration, Instant},
};
use tokio::runtime::Handle;

enum Reply {
    List(u64, Result<Vec<BaseWorld>>),
    Renamed(u64, String, Result<BaseWorld>),
    Deleted(u64, String, Result<()>),
    Picked(u64, Option<PathBuf>),
    Ready(u64, Result<Vec<BaseWorld>>),
    Saved(u64, Result<()>),
    Player(String, Result<(String, image::RgbaImage)>),
}

/// The version whose 初始地图模板 the page edits, with the binding it had when opened.
struct Target {
    modpack_id: String,
    version: String,
    binding: Option<Binding>,
}

pub struct BaseWorldUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    tasks: tasks::Manager,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    epoch: u64,
    worlds: Vec<BaseWorld>,
    rows: Rc<VecModel<WorldCard>>,
    /// The template of the open rename or delete dialog.
    editing: Option<String>,
    // 创建地图模板.
    directory: Option<PathBuf>,
    auto_name: Option<String>,
    choice: LevelChoice,
    /// The submission this page started and its run, so a second click joins it.
    active: Option<(String, String)>,
    /// Upload keys started this session; the list reloads when one of them ends.
    watched: Vec<String>,
    watched_state: Vec<Option<u8>>,
    last_check: Instant,
    // 初始地图模板.
    target: Option<Target>,
    ready: Vec<BaseWorld>,
    draft: Option<String>,
    required: bool,
    loaded: bool,
    saving: bool,
    bind_rows: Rc<VecModel<WorldCard>>,
    players: HashMap<String, (String, slint::Image)>,
    steve: slint::Image,
    last_screen: i32,
    pending_nav: Vec<&'static str>,
}

fn worlds(ui: &AppWindow) -> Worlds<'_> {
    ui.global::<Worlds>()
}

fn shown(error: &anyhow::Error) -> String {
    let text = format!("{error:#}");
    if text.trim().is_empty() { "未知错误".into() } else { text }
}

impl BaseWorldUi {
    pub fn wire(ui: &AppWindow, runtime: Handle, tasks: tasks::Manager) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let rows = Rc::new(VecModel::default());
        let bind_rows = Rc::new(VecModel::default());
        worlds(ui).set_worlds(rows.clone().into());
        worlds(ui).set_bind_worlds(bind_rows.clone().into());
        let steve = images::steve().map(|i| images::to_slint(&i)).unwrap_or_default();
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            tasks,
            api: None,
            tx,
            rx,
            epoch: 0,
            worlds: vec![],
            rows,
            editing: None,
            directory: None,
            auto_name: None,
            choice: LevelChoice::Normal,
            active: None,
            watched: vec![],
            watched_state: vec![],
            last_check: Instant::now(),
            target: None,
            ready: vec![],
            draft: None,
            required: false,
            loaded: false,
            saving: false,
            bind_rows,
            players: HashMap::new(),
            steve,
            last_screen: 0,
            pending_nav: vec![],
        }));
        let weak = Rc::downgrade(&state);
        worlds(ui).on_action(move |name, value| {
            if let Some(state) = weak.upgrade() {
                match state.try_borrow_mut() {
                    Ok(mut state) => state.action(name.as_str(), value.as_str()),
                    Err(_) => tracing::warn!(%name, "地图模板操作重入，已忽略"),
                }
            }
        });
        state
    }

    /// A new sign-in or a sign-out leaves the pages.
    pub fn connect(&mut self, api: Option<Client>) {
        self.epoch += 1;
        self.api = api;
        self.worlds.clear();
        self.active = None;
        self.watched.clear();
        self.watched_state.clear();
        self.target = None;
        self.players.clear();
        while self.rx.try_recv().is_ok() {}
        if let Some(ui) = self.ui.upgrade() {
            self.reset_list(&ui);
            self.reset_create(&ui);
            self.reset_binding(&ui);
        }
    }

    fn owner(&self) -> Option<String> {
        self.api.as_ref().and_then(|a| a.uid().ok())
    }

    // ---- 地图模板 ----

    fn reset_list(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        self.editing = None;
        g.set_loading(false);
        g.set_error("".into());
        g.set_notice("".into());
        g.set_detail_open(false);
        g.set_rename_open(false);
        g.set_rename_busy(false);
        g.set_delete_open(false);
        g.set_delete_busy(false);
        self.render_list();
    }

    fn render_list(&self) {
        let rows: Vec<WorldCard> = self
            .worlds
            .iter()
            .map(|w| WorldCard {
                id: w.id.as_str().into(),
                name: w.name.as_str().into(),
                detail: format!("{} · {}", human_file_size(w.size), base_world::upload_time(&w.id)).into(),
                ..Default::default()
            })
            .collect();
        self.rows.set_vec(rows);
    }

    fn load_list(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        self.epoch += 1;
        let g = worlds(ui);
        g.set_loading(true);
        g.set_error("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = HttpWorldApi::new(api.server().clone()).list_mine().await;
            let _ = tx.send(Reply::List(epoch, result));
        });
    }

    fn world(&self, id: &str) -> Option<&BaseWorld> {
        self.worlds.iter().find(|w| w.id == id)
    }

    fn rename(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        let (Some(api), Some(id)) = (self.api.clone(), self.editing.clone()) else { return };
        if g.get_rename_busy() || self.world(&id).is_none() {
            return;
        }
        let name = g.get_rename_text().to_string();
        if let Err(error) = base_world::validate_name(&name) {
            g.set_rename_error(error.into());
            return;
        }
        g.set_rename_busy(true);
        g.set_rename_error("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = HttpWorldApi::new(api.server().clone()).rename(&id, &name).await;
            let _ = tx.send(Reply::Renamed(epoch, id, result));
        });
    }

    fn delete(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        let (Some(api), Some(id)) = (self.api.clone(), self.editing.clone()) else { return };
        let Some(world) = self.world(&id) else { return };
        if g.get_delete_busy() || g.get_delete_text() != world.name.as_str() {
            return;
        }
        g.set_delete_busy(true);
        g.set_notice("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            use base_world::api::WorldApi;
            let result = HttpWorldApi::new(api.server().clone()).delete(&id).await;
            let _ = tx.send(Reply::Deleted(epoch, id, result));
        });
    }

    // ---- 创建地图模板 ----

    fn reset_create(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        self.directory = None;
        self.auto_name = None;
        self.choice = LevelChoice::Normal;
        g.set_directory("".into());
        g.set_picking(false);
        g.set_name("".into());
        g.set_level_choice(0);
        g.set_level_type("minecraft:normal".into());
        g.set_settings("".into());
        g.set_create_error("".into());
        g.set_custom_open(false);
        g.set_custom_text("".into());
        g.set_custom_error("".into());
        self.update_can_create(ui);
    }

    /// `canSubmit`: a valid name and level type while no folder is being chosen.
    fn update_can_create(&self, ui: &AppWindow) {
        let g = worlds(ui);
        let ok = base_world::validate_name(&g.get_name()).is_ok() && base_world::validate_level_type(&g.get_level_type()).is_ok() && !g.get_picking();
        g.set_can_create(ok);
    }

    fn pick(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        if g.get_picking() {
            return;
        }
        g.set_picking(true);
        g.set_create_error("".into());
        self.update_can_create(ui);
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let picked = rfd::AsyncFileDialog::new().set_title("选择地图文件夹").pick_folder().await.map(|f| f.path().to_path_buf());
            let _ = tx.send(Reply::Picked(epoch, picked));
        });
    }

    fn picked(&mut self, ui: &AppWindow, picked: Option<PathBuf>) {
        let g = worlds(ui);
        g.set_picking(false);
        if let Some(directory) = picked {
            match base_world::validate_directory(&directory) {
                Ok(()) => {
                    let folder = directory.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                    let name = g.get_name().to_string();
                    if name.trim().is_empty() || Some(&name) == self.auto_name.as_ref() {
                        g.set_name(folder.as_str().into());
                    }
                    self.auto_name = Some(folder);
                    g.set_directory(directory.display().to_string().into());
                    self.directory = Some(directory);
                }
                Err(error) => g.set_create_error(error.into()),
            }
        }
        self.update_can_create(ui);
    }

    fn select_level(&mut self, ui: &AppWindow, value: &str) {
        let g = worlds(ui);
        let Some(choice) = value.parse().ok().and_then(LevelChoice::from_index) else { return };
        g.set_create_error("".into());
        match choice.preset() {
            Some(level) => {
                self.choice = choice;
                g.set_level_choice(choice.index());
                g.set_level_type(level.into());
            }
            None => {
                let text = if self.choice == LevelChoice::Custom { g.get_level_type() } else { "".into() };
                g.set_custom_text(text);
                g.set_custom_error("".into());
                g.set_custom_open(true);
            }
        }
        self.update_can_create(ui);
    }

    fn apply_custom(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        let value = g.get_custom_text().trim().to_owned();
        if let Err(error) = base_world::validate_level_type(&value) {
            g.set_custom_error(error.into());
            return;
        }
        self.choice = LevelChoice::Custom;
        g.set_level_choice(LevelChoice::Custom.index());
        g.set_level_type(value.into());
        g.set_custom_open(false);
        self.update_can_create(ui);
    }

    /// `submit`: validates, starts the task, opens it and returns to the list.
    fn create(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        let fail = |message: &str| g.set_create_error(message.into());
        if g.get_picking() {
            return fail("正在选择地图文件夹");
        }
        let (Some(api), Some(owner)) = (self.api.clone(), self.owner()) else { return fail("当前账号无效") };
        let name = g.get_name().to_string();
        if let Err(error) = base_world::validate_name(&name) {
            return fail(&error);
        }
        let level_type = g.get_level_type().trim().to_owned();
        if let Err(error) = base_world::validate_level_type(&level_type) {
            return fail(&error);
        }
        let settings = g.get_settings().to_string();
        if let Err(error) = base_world::validate_generator_settings(&settings) {
            return fail(&error);
        }
        let request = Request::new(self.directory.clone(), &name, &level_type, &settings);
        let key = match &request.directory {
            Some(directory) => base_world::upload_task_key(&owner, directory),
            None => base_world::generated_task_key(&owner, &request.name, &request.level_type, request.generator_settings.as_deref()),
        };
        let run = match &self.active {
            Some((active_key, run)) if active_key == &key && self.tasks.active(&key).as_ref() == Some(run) => run.clone(),
            _ => match self.tasks.active(&key) {
                Some(run) => run,
                None => upload::submit(
                    &self.tasks,
                    &self.runtime,
                    Arc::new(HttpWorldApi::new(api.server().clone())),
                    request,
                    key.clone(),
                    upload::Env::new(&crate::paths::data_dir()),
                ),
            },
        };
        self.active = Some((key.clone(), run.clone()));
        if !self.watched.contains(&key) {
            self.watched.push(key);
        }
        g.set_create_error("".into());
        // Back to the list first: a later `back` would only close the task panel.
        ui.invoke_navigate("back".into());
        ui.invoke_pack_action("install.open".into(), run.into());
    }

    // ---- 初始地图模板 ----

    fn reset_binding(&mut self, ui: &AppWindow) {
        self.ready.clear();
        self.draft = None;
        self.required = false;
        self.loaded = false;
        self.saving = false;
        let g = worlds(ui);
        g.set_bind_loading(false);
        g.set_bind_error("".into());
        g.set_bind_notice("".into());
        self.render_binding(ui);
    }

    /// Opens the page for `{"modpackId","version","binding"}` from the plaza version page.
    fn open_binding(&mut self, ui: &AppWindow, value: &str) {
        let Ok(json) = serde_json::from_str::<serde_json::Value>(value) else { return };
        let (Some(modpack_id), Some(version)) = (json["modpackId"].as_str(), json["version"].as_str()) else { return };
        let binding = serde_json::from_value::<Option<Binding>>(json["binding"].clone()).ok().flatten();
        self.target = Some(Target { modpack_id: modpack_id.into(), version: version.into(), binding });
        self.reset_binding(ui);
        self.load_binding(ui);
        self.pending_nav.push("version-base-world");
    }

    fn load_binding(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        self.epoch += 1;
        self.loaded = false;
        let g = worlds(ui);
        g.set_bind_loading(true);
        g.set_bind_error("".into());
        g.set_bind_notice("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = HttpWorldApi::new(api.server().clone()).list_ready().await;
            let _ = tx.send(Reply::Ready(epoch, result));
        });
        self.render_binding(ui);
    }

    fn selected(&self) -> Option<&BaseWorld> {
        self.draft.as_ref().and_then(|id| self.ready.iter().find(|w| &w.id == id))
    }

    fn render_binding(&mut self, ui: &AppWindow) {
        let g = worlds(ui);
        let unavailable = self.loaded && self.draft.is_some() && self.selected().is_none();
        g.set_bind_title(match self.selected() {
            Some(world) => format!("已选择 {}", world.name),
            None if unavailable => "已选择的地图模板暂不可用".into(),
            None => "在下方选择一个指定地图模板".into(),
        }
        .into());
        g.set_bind_loaded(self.loaded);
        g.set_bind_has_draft(self.draft.is_some());
        g.set_bind_required(self.required);
        g.set_bind_unavailable(unavailable);
        g.set_bind_saving(self.saving);
        g.set_bind_can_save(self.loaded && !self.saving && self.target.is_some() && (self.draft.is_none() || self.selected().is_some()));
        let mut rows = Vec::new();
        if unavailable && let Some(id) = &self.draft {
            rows.push(WorldCard { id: id.as_str().into(), name: "已失效的地图模板".into(), detail: id.as_str().into(), unavailable: true, selected: true, ..Default::default() });
        }
        let mut wanted = Vec::new();
        for world in &self.ready {
            let owner = crate::rooms_ui::uuid_object_id(&world.owner_id);
            let (owner_name, owner_head) = match owner.as_deref() {
                None => ("未知玩家".to_owned(), self.steve.clone()),
                Some(uid) => self.players.get(uid).cloned().unwrap_or_else(|| {
                    wanted.push(uid.to_owned());
                    ("…".into(), self.steve.clone())
                }),
            };
            rows.push(WorldCard {
                id: world.id.as_str().into(),
                name: world.name.as_str().into(),
                owner_name: owner_name.into(),
                owner_head,
                has_owner: true,
                selected: self.draft.as_ref() == Some(&world.id),
                ..Default::default()
            });
        }
        if rows.len() == self.bind_rows.row_count() {
            for (i, row) in rows.into_iter().enumerate() {
                if self.bind_rows.row_data(i).as_ref() != Some(&row) {
                    self.bind_rows.set_row_data(i, row);
                }
            }
        } else {
            self.bind_rows.set_vec(rows);
        }
        wanted.sort();
        wanted.dedup();
        for uid in wanted {
            self.request_player(uid);
        }
    }

    fn request_player(&mut self, uid: String) {
        let Some(api) = self.api.clone() else { return };
        self.players.insert(uid.clone(), ("…".into(), self.steve.clone()));
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = crate::rooms_ui::player(&api, &uid).await;
            let _ = tx.send(Reply::Player(uid, result));
        });
    }

    fn pick_binding(&mut self, ui: &AppWindow, id: &str) {
        if !self.loaded || self.saving {
            return;
        }
        if self.draft.as_deref() == Some(id) {
            self.draft = None;
            self.required = false;
        } else if self.ready.iter().any(|w| w.id == id) {
            self.draft = Some(id.to_owned());
        } else {
            return;
        }
        worlds(ui).set_bind_notice("".into());
        self.render_binding(ui);
    }

    fn save_binding(&mut self, ui: &AppWindow) {
        let (Some(api), Some(target)) = (self.api.clone(), self.target.as_ref()) else { return };
        if !worlds(ui).get_bind_can_save() {
            return;
        }
        let binding = self.draft.clone().map(|id| Binding { id, required: self.required });
        let (modpack_id, version) = (target.modpack_id.clone(), target.version.clone());
        self.saving = true;
        worlds(ui).set_bind_notice("".into());
        self.render_binding(ui);
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = base_world::api::set_version_binding(&api, &modpack_id, &version, binding.as_ref()).await;
            let _ = tx.send(Reply::Saved(epoch, result));
        });
    }

    // ---- actions and replies ----

    fn action(&mut self, name: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = worlds(&ui);
        match name {
            "open-list" => {
                self.worlds.clear();
                self.reset_list(&ui);
                self.load_list(&ui);
                self.pending_nav.push("base-worlds");
            }
            "reload" => self.load_list(&ui),
            "back-nav" => self.pending_nav.push("back"),
            "detail" => {
                let Some(world) = self.world(value) else { return };
                let account = self.api.as_ref().map(|a| a.account_name.clone()).unwrap_or_default();
                let rows = [
                    ("地图大小", human_file_size(world.size)),
                    ("上传时间", base_world::upload_time(&world.id)),
                    ("上传者", account),
                    ("生成器设置", base_world::generator_text(world.generator_settings.as_deref())),
                    ("世界类型", world.level_type.clone()),
                ]
                .map(|(label, value)| WorldDetailRow { label: label.into(), value: value.into() });
                g.set_detail_title(world.name.as_str().into());
                g.set_detail_rows(VecModel::from_slice(&rows));
                g.set_detail_open(true);
            }
            "detail-close" => g.set_detail_open(false),
            "rename" => {
                let Some(world) = self.world(value) else { return };
                g.set_rename_text(world.name.as_str().into());
                g.set_rename_error("".into());
                g.set_rename_valid(base_world::validate_name(&world.name).is_ok());
                self.editing = Some(value.to_owned());
                g.set_rename_open(true);
            }
            "rename-text" => {
                let check = base_world::validate_name(value);
                g.set_rename_valid(check.is_ok());
                g.set_rename_error(if value.is_empty() { "".into() } else { check.err().unwrap_or_default().into() });
            }
            "rename-ok" => self.rename(&ui),
            "rename-cancel" if !g.get_rename_busy() => g.set_rename_open(false),
            "delete" => {
                if g.get_delete_busy() {
                    return;
                }
                let Some(world) = self.world(value) else { return };
                g.set_delete_name(world.name.as_str().into());
                g.set_delete_text("".into());
                g.set_notice("".into());
                self.editing = Some(value.to_owned());
                g.set_delete_open(true);
            }
            "delete-ok" => self.delete(&ui),
            "delete-cancel" if !g.get_delete_busy() => g.set_delete_open(false),
            "open-create" => {
                self.reset_create(&ui);
                ui.global::<crate::Rooms>().invoke_action("icons".into(), "".into());
                self.pending_nav.push("base-world-create");
            }
            "pick" => self.pick(&ui),
            "name" => {
                g.set_create_error("".into());
                self.update_can_create(&ui);
            }
            "settings" => g.set_create_error("".into()),
            "level" => self.select_level(&ui, value),
            "custom-text" => g.set_custom_error("".into()),
            "custom-ok" => self.apply_custom(&ui),
            "custom-cancel" => {
                g.set_custom_error("".into());
                g.set_custom_open(false);
            }
            "create" => self.create(&ui),
            "open-binding" => self.open_binding(&ui, value),
            "bind-reload" => self.load_binding(&ui),
            "bind-pick" => self.pick_binding(&ui, value),
            "bind-required" => {
                let required = value == "1";
                if self.loaded && !self.saving && (!required || self.draft.is_some()) {
                    self.required = required;
                    g.set_bind_notice("".into());
                }
                self.render_binding(&ui);
            }
            "bind-save" => self.save_binding(&ui),
            "dismiss" => {
                if g.get_custom_open() {
                    self.action("custom-cancel", "");
                } else if g.get_rename_open() && !g.get_rename_busy() {
                    g.set_rename_open(false);
                } else if g.get_delete_open() && !g.get_delete_busy() {
                    g.set_delete_open(false);
                } else {
                    g.set_detail_open(false);
                }
            }
            _ => tracing::debug!(name, "忽略的地图模板操作"),
        }
    }

    /// Reloads the list when an upload started here ends (Compose watches the task entries).
    fn watch_uploads(&mut self, ui: &AppWindow) {
        if self.watched.is_empty() || self.last_check.elapsed() < Duration::from_millis(500) {
            return;
        }
        self.last_check = Instant::now();
        let state: Vec<Option<u8>> = self.watched.iter().map(|k| self.tasks.latest_status(k)).collect();
        if state != self.watched_state {
            let ended = state.iter().zip(self.watched_state.iter().chain(std::iter::repeat(&None))).any(|(now, before)| now.is_some_and(|s| s >= 2) && now != before);
            self.watched_state = state;
            if ended && worlds(ui).get_screen() == 1 {
                self.load_list(ui);
            }
        }
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            ui.invoke_navigate(action.into());
        }
        let screen = worlds(&ui).get_screen();
        if screen != self.last_screen {
            // Back on the list from 创建地图模板: reload, as Compose does on resume.
            if screen == 1 && self.last_screen == 2 {
                self.load_list(&ui);
            }
            if screen == 0 {
                self.epoch += 1;
                self.reset_list(&ui);
                self.reset_binding(&ui);
                worlds(&ui).set_custom_open(false);
            }
            self.last_screen = screen;
        }
        self.watch_uploads(&ui);
        while let Ok(reply) = self.rx.try_recv() {
            let g = worlds(&ui);
            match reply {
                Reply::List(epoch, result) if epoch == self.epoch => {
                    g.set_loading(false);
                    match result {
                        Ok(list) => {
                            g.set_error("".into());
                            self.worlds = list;
                            self.render_list();
                        }
                        Err(error) if self.worlds.is_empty() => g.set_error(format!("加载地图模板失败：{}", shown(&error)).into()),
                        Err(error) => g.set_notice(format!("加载地图模板失败：{}", shown(&error)).into()),
                    }
                }
                Reply::Renamed(epoch, id, result) if epoch == self.epoch => {
                    g.set_rename_busy(false);
                    match result {
                        Ok(renamed) => {
                            if let Some(world) = self.worlds.iter_mut().find(|w| w.id == id) {
                                *world = renamed;
                            }
                            self.render_list();
                            g.set_rename_open(false);
                        }
                        Err(error) => g.set_rename_error(format!("修改地图模板名称失败：{}", shown(&error)).into()),
                    }
                }
                Reply::Deleted(epoch, id, result) if epoch == self.epoch => {
                    g.set_delete_busy(false);
                    g.set_delete_open(false);
                    match result {
                        Ok(()) => {
                            self.worlds.retain(|w| w.id != id);
                            self.render_list();
                        }
                        Err(error) => g.set_notice(format!("删除地图模板失败：{}", shown(&error)).into()),
                    }
                }
                Reply::Picked(epoch, picked) if epoch == self.epoch => self.picked(&ui, picked),
                Reply::Ready(epoch, result) if epoch == self.epoch => {
                    g.set_bind_loading(false);
                    match result {
                        Ok(list) => {
                            self.ready = list;
                            let binding = self.target.as_ref().and_then(|t| t.binding.clone());
                            self.draft = binding.as_ref().map(|b| b.id.clone());
                            self.required = binding.is_some_and(|b| b.required);
                            self.loaded = true;
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "加载版本地图模板设置失败");
                            g.set_bind_error(format!("加载地图模板失败：{}", shown(&error)).into());
                        }
                    }
                    self.render_binding(&ui);
                }
                Reply::Saved(epoch, result) if epoch == self.epoch => {
                    self.saving = false;
                    match result {
                        Ok(()) => {
                            let binding = self.draft.clone().map(|id| Binding { id, required: self.required });
                            if let Some(target) = &self.target {
                                ui.invoke_plaza_action("base-world-saved".into(), serde_json::json!({ "version": target.version, "binding": binding }).to_string().into());
                            }
                            self.pending_nav.push("back");
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "更新版本地图模板失败");
                            g.set_bind_notice(format!("更新地图模板失败: {}", shown(&error)).into());
                        }
                    }
                    self.render_binding(&ui);
                }
                Reply::Player(uid, result) => {
                    let entry = match result {
                        Ok((name, head)) => (name, images::to_slint(&head)),
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "加载地图模板作者失败");
                            ("未知玩家".into(), self.steve.clone())
                        }
                    };
                    self.players.insert(uid, entry);
                    if g.get_screen() == 3 {
                        self.render_binding(&ui);
                    }
                }
                _ => {}
            }
        }
    }
}
