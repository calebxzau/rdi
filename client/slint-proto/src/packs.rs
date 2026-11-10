//! U4a UI adapters. Import, tasks and play stay in Kotlin; since J2 the installed pack list
//! (`local_packs`) and pack options (`pack_options`) are read and written here.
use crate::{
    AppWindow, CandidateRow, ContentRow, LogRow, OptionDraft, TaskRow,
    api::Client,
    images,
    local_packs::{self, PackList},
    pack_options::{self, Draft},
};
use anyhow::{Context, Result};
use serde_json::{Value, json};
use slint::{ComponentHandle, Model, VecModel};
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet, VecDeque},
    path::PathBuf,
    rc::Rc,
    sync::mpsc,
};

/// Same units and rounding as Kotlin `Long.humanFileSize`, which the Compose screen shows.
fn human_file_size(bytes: u64) -> String {
    if bytes < 1024 {
        return format!("{bytes}B");
    }
    let mut value = bytes as f64 / 1024.;
    for unit in ["KB", "MB"] {
        if value < 1024. {
            return format!("{value:.1}{unit}");
        }
        value /= 1024.;
    }
    format!("{value:.1}GB")
}
/// Compose joins the non-blank pack and version names, falling back to the version id.
fn options_title(id: &str, name: &str, version: &str) -> String {
    let title = [name, version]
        .into_iter()
        .filter(|s| !s.trim().is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    if title.is_empty() {
        id.to_string()
    } else {
        title
    }
}
pub use crate::local_packs::InstalledPack;
enum Reply {
    Service(u64, String, Result<Value>),
    Scan(u64, Vec<crate::import::scanner::Candidate>),
    Directory(u64, PathBuf, Result<Vec<crate::import::scanner::Candidate>>),
    Packs(u64, PackList),
    OptionsLoaded(u64, Result<pack_options::Loaded>),
    OptionsSaved(u64, Draft, bool, Result<()>),
    Pick(u64, String, Result<Option<String>>),
    Image(u64, String, Result<image::RgbaImage>),
}
pub struct PackUi {
    ui: slint::Weak<AppWindow>,
    runtime: tokio::runtime::Handle,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    generation: u64,
    queue: VecDeque<(String, Value)>,
    busy: bool,
    installer: Value,
    native: crate::import::state::State,
    import_generation: u64,
    archive_bridge: bool,
    native_tasks: crate::tasks::Manager,
    service_tasks: Vec<Value>,
    watched: String,
    task_sample: std::time::Instant,
    refreshed_tasks: HashSet<String>,
    /// An `installer.open` is queued or in flight, so a close must still follow it.
    installer_opening: bool,
    contents: Vec<Value>,
    visible_keys: Vec<String>,
    filter: (String, i32, bool),
    images: std::collections::HashMap<String, slint::Image>,
    pending_images: HashSet<String>,
    failed_images: HashSet<String>,
    global_java_path: String,
    packs_generation: u64,
    loaded_packs: Option<Vec<InstalledPack>>,
    pack_dirs: HashMap<String, PathBuf>,
    refreshed_run: String,
    options: OptionsState,
    java_was_enabled: bool,
    candidates: Rc<VecModel<CandidateRow>>,
    picked: Rc<VecModel<CandidateRow>>,
    runtimes: Rc<VecModel<slint::SharedString>>,
    content_rows: Rc<VecModel<ContentRow>>,
    logs: Rc<VecModel<LogRow>>,
    tasks: Rc<VecModel<TaskRow>>,
    task_list: Rc<VecModel<CandidateRow>>,
    sessions: Rc<VecModel<CandidateRow>>,
    task: Value,
    task_run_id: String,
    collapsed: HashSet<String>,
    collapsed_candidates: HashSet<String>,
    session_id: String,
    last_device_uri: String,
}
impl PackUi {
    pub fn wire(ui: &AppWindow, runtime: tokio::runtime::Handle) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let candidates = Rc::new(VecModel::default());
        let picked = Rc::new(VecModel::default());
        let runtimes = Rc::new(VecModel::default());
        let content_rows = Rc::new(VecModel::default());
        let logs = Rc::new(VecModel::default());
        let tasks = Rc::new(VecModel::default());
        let task_list = Rc::new(VecModel::default());
        let sessions = Rc::new(VecModel::default());
        ui.set_install_candidates(candidates.clone().into());
        ui.set_picked_candidates(picked.clone().into());
        ui.set_install_runtimes(runtimes.clone().into());
        ui.set_install_contents(content_rows.clone().into());
        ui.set_activity_logs(logs.clone().into());
        ui.set_activity_tasks(tasks.clone().into());
        ui.set_activity_task_list(task_list.clone().into());
        ui.set_activity_sessions(sessions.clone().into());
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            api: None,
            tx,
            rx,
            generation: 0,
            queue: VecDeque::new(),
            busy: false,
            installer: Value::Null,
            native: Default::default(),
            import_generation: 0,
            archive_bridge: false,
            native_tasks: Default::default(),
            service_tasks: vec![],
            watched: String::new(),
            task_sample: std::time::Instant::now(),
            refreshed_tasks: Default::default(),
            installer_opening: false,
            contents: vec![],
            visible_keys: vec![],
            filter: (String::new(), 0, false),
            images: Default::default(),
            pending_images: Default::default(),
            failed_images: Default::default(),
            global_java_path: String::new(),
            packs_generation: 0,
            loaded_packs: None,
            pack_dirs: Default::default(),
            refreshed_run: String::new(),
            options: OptionsState::default(),
            java_was_enabled: false,
            candidates,
            picked,
            runtimes,
            content_rows,
            logs,
            tasks,
            task_list,
            sessions,
            task: Value::Null,
            task_run_id: String::new(),
            collapsed: Default::default(),
            collapsed_candidates: Default::default(),
            session_id: String::new(),
            last_device_uri: String::new(),
        }));
        let weak = Rc::downgrade(&state);
        ui.on_pack_action(move |action, value| {
            if let Some(state) = weak.upgrade() {
                state.borrow_mut().action(action.as_str(), value.as_str());
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_pack_filter(move |q, k, d| {
            if let Some(state) = weak.upgrade() {
                let mut state = state.borrow_mut();
                state.filter = (q.to_string(), k, d);
                state.render_contents();
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_pack_proof(move |topic, value| {
            if let Some(state) = weak.upgrade() {
                if let Some(ui) = state.borrow().ui.upgrade() {
                    ui.set_pack_name("测试整合包".into());
                    ui.set_options_title(options_title("", "测试整合包", "1.21.1").into());
                }
                match serde_json::from_str::<Value>(value.as_str()) {
                    Ok(value) if topic == "packOptions.state" => {
                        state.borrow_mut().options_proof(&value)
                    }
                    Ok(value) => state.borrow_mut().event(topic.as_str(), &value),
                    Err(error) => tracing::error!(%error,"截图样例数据无效"),
                }
            }
        });
        state
    }
    pub fn connect(&mut self, api: Option<Client>) {
        self.generation += 1;
        self.busy = false;
        self.queue.clear();
        self.global_java_path = api
            .as_ref()
            .map(|a| a.java_path.clone())
            .unwrap_or_default();
        self.api = api;
        // A list still loading for the previous session must not reach the next one.
        self.packs_generation += 1;
        self.loaded_packs = None;
        self.pack_dirs.clear();
        self.installer = Value::Null;
        self.installer_opening = false;
        self.archive_bridge = false;
        self.import_generation += 1;
        self.native = Default::default();
        self.service_tasks.clear();
        self.watched.clear();
        self.task = Value::Null;
        self.last_device_uri.clear();
        self.pending_images.clear();
        self.failed_images.clear();
        if let Some(ui) = self.ui.upgrade() {
            ui.set_local_play(false);
            ui.set_pack_menu(false);
            ui.set_install_editor(false);
            ui.set_install_missing(false);
            ui.set_discard_options(false);
            ui.set_tasks_running(false);
            self.picked.clear();
            if self.api.is_some() {
                self.refresh_packs();
                if ui.get_pack_screen() == 1 {
                    self.open_native();
                }
            }
        }
    }
    pub fn service_idle(&self) -> bool {
        !self.busy && self.queue.is_empty()
    }
    pub fn service_lost(&mut self) {
        // Never replay commands queued for a lost generation.
        self.generation += 1;
        self.queue.clear();
        self.busy = false;
        self.installer_opening = false;
        self.service_tasks.clear();
        self.logs.clear();
        self.sessions.clear();
        self.last_device_uri.clear();
        // Rust imports survive; only a mirrored Kotlin archive editor is lost.
        let archive = self.archive_bridge;
        if archive {
            self.archive_bridge = false;
            self.import_generation += 1;
            self.native = crate::import::state::State::open();
            self.native.value["scanning"] = json!(false);
            self.native_render();
        }
        if !self.native_tasks.owns(&self.watched) {
            self.task = Value::Null;
            self.watched.clear();
            self.render_task();
        }
        if let Some(ui) = self.ui.upgrade() {
            ui.set_local_play(false);
            ui.set_play_busy(false);
            ui.set_duplicate_host("".into());
            // The console needs the process; the task screen does not.
            if ui.get_activity_panel() == 1 {
                ui.set_activity_panel(0);
            }
            if archive {
                self.navigate("packs");
            }
        }
        self.merge_tasks();
    }
    pub fn has_running_tasks(&self) -> bool {
        self.native_tasks.running() || self.service_tasks.iter().any(|t| t["terminal"] == false)
    }
    pub fn shutdown_tasks(&self) {
        self.native_tasks.shutdown(std::time::Duration::from_secs(3));
    }
    /// Screens that still need the service: the mirrored archive editor and the console.
    pub fn service_screens_idle(&self) -> bool {
        let Some(ui) = self.ui.upgrade() else {
            return true;
        };
        !self.archive_bridge && ui.get_activity_panel() != 1
    }
    fn native_render(&mut self) {
        self.installer(&self.native.value.clone());
    }
    fn open_native(&mut self) {
        self.import_generation += 1;
        self.native = crate::import::state::State::open();
        if self.archive_bridge {
            self.call("installer.close", json!({}));
        }
        self.archive_bridge = false;
        self.native_render();
        self.scan_native();
    }
    fn scan_native(&mut self) {
        self.native.value["scanning"] = json!(true);
        self.native_render();
        let tx = self.tx.clone();
        let generation = self.import_generation;
        self.runtime.spawn_blocking(move || {
            let data = crate::paths::data_dir();
            let list = crate::import::scanner::scan(&data.join("data.db"), &data.join("mc"));
            let _ = tx.send(Reply::Scan(generation, list));
        });
    }
    fn end_bridge(&mut self) {
        if self.archive_bridge {
            self.call("installer.close", json!({}));
        }
        self.archive_bridge = false;
        self.import_generation += 1;
    }
    /// J4a bridge: archives go to the Kotlin editor, every other source stays in Rust.
    fn import_command(&mut self, action: &str, params: Value) {
        if action == "archive" {
            self.import_generation += 1;
            if !self.archive_bridge {
                self.archive_bridge = true;
                self.call("installer.open", json!({}));
            }
            self.call("installer.action", params);
            return;
        }
        let leaves_bridge = matches!(
            action,
            "create" | "directory" | "candidate" | "pickedCandidate" | "back"
        );
        if self.archive_bridge && !leaves_bridge {
            self.call("installer.action", params);
            return;
        }
        if self.archive_bridge {
            self.end_bridge();
        }
        if matches!(action, "create" | "candidate" | "pickedCandidate" | "back") {
            self.import_generation += 1;
            self.native.value["scanning"] = json!(false);
            self.native.value["directoryLoading"] = json!(false);
        }
        if action == "scan" {
            self.scan_native();
            return;
        }
        if action == "directory" {
            self.import_generation += 1;
            self.native.value["directoryLoading"] = json!(true);
            self.native_render();
            let path = PathBuf::from(text(&params, "path"));
            let tx = self.tx.clone();
            let generation = self.import_generation;
            self.runtime.spawn_blocking(move || {
                let result = crate::import::scanner::directory(&path);
                let _ = tx.send(Reply::Directory(generation, path, result));
            });
            return;
        }
        let index = params["index"].as_u64().unwrap_or(0) as usize;
        if let Err(error) = self.native.action(action, text(&params, "value"), index) {
            self.native.error(error);
        }
        self.native_render();
    }
    /// Rust and Kotlin tasks in one list, newest first.
    fn merge_tasks(&mut self) {
        let mut all = self.native_tasks.list();
        all.extend(self.service_tasks.clone());
        all.sort_by_key(|t| std::cmp::Reverse(t["createdAt"].as_u64().unwrap_or(0)));
        if let Some(ui) = self.ui.upgrade() {
            ui.set_tasks_running(all.iter().any(|t| t["terminal"] == false));
        }
        let rows = all
            .iter()
            .enumerate()
            .map(|(i, t)| CandidateRow {
                name: text(t, "title").into(),
                detail: text(t, "runId").into(),
                index: i as i32,
                // The task list reads `collapsed` as "running, show the cancel button".
                collapsed: t["terminal"] == false,
                ..Default::default()
            })
            .collect();
        sync(&self.task_list, rows);
        let mut refresh = false;
        for task in &all {
            if task["terminal"] == true && self.refreshed_tasks.insert(text(task, "runId").into()) {
                refresh = true;
            }
        }
        if refresh {
            self.refresh_packs();
        }
        if self.watched.is_empty()
            && let Some(task) = all.first()
        {
            self.watched = text(task, "runId").into();
            self.task = task.clone();
            self.render_task();
        }
        if let Some(task) = self.native_tasks.snapshot(&self.watched)
            && task != self.task
        {
            self.task = task;
            self.render_task();
        }
    }
    fn call(&mut self, method: &str, params: Value) {
        self.installer_opening |= method == "installer.open";
        self.queue.push_back((method.into(), params));
        self.pump();
    }
    fn pump(&mut self) {
        if self.busy {
            return;
        }
        let Some(api) = self.api.clone() else {
            return;
        };
        let Some((method, params)) = self.queue.pop_front() else {
            return;
        };
        self.busy = true;
        let tx = self.tx.clone();
        let generation = self.generation;
        self.runtime.spawn(async move {
            let result = api.call(&method, params).await;
            let _ = tx.send(Reply::Service(generation, method, result));
        });
    }
    fn navigate(&self, target: &str) {
        let weak = self.ui.clone();
        let target = slint::SharedString::from(target);
        let _ = weak.upgrade_in_event_loop(move |ui| ui.invoke_navigate(target));
    }
    pub fn open_pack(&mut self, id: &str, name: &str, version: &str, right: bool) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        ui.set_pack_selected_id(id.into());
        ui.set_pack_name(name.into());
        ui.set_options_title(options_title(id, name, version).into());
        if right {
            ui.set_pack_menu(true);
        } else {
            self.begin_play();
        }
    }
    fn begin_play(&mut self) {
        if let Some(ui) = self.ui.upgrade() {
            ui.set_pack_menu(false);
            ui.set_play_error("".into());
            ui.set_play_device_uri("".into());
            ui.set_local_play(true);
            ui.set_play_busy(true);
            self.call(
                "packPlay.open",
                json!({"versionId":ui.get_pack_selected_id().as_str()}),
            );
        }
    }
    /// The app takes each finished list; `None` until the next refresh completes.
    pub fn take_packs(&mut self) -> Option<Vec<InstalledPack>> {
        self.loaded_packs.take()
    }
    fn refresh_packs(&mut self) {
        let Some(api) = self.api.clone() else {
            return;
        };
        self.packs_generation += 1;
        tracing::info!(generation=self.packs_generation,"整合包列表刷新");
        if let Some(ui) = self.ui.upgrade() {
            ui.set_packs_loading(true);
            ui.set_packs_error("".into());
        }
        let tx = self.tx.clone();
        let generation = self.packs_generation;
        self.runtime.spawn(async move {
            let list = local_packs::load(&api).await;
            let _ = tx.send(Reply::Packs(generation, list));
        });
    }
    /// Kotlin refreshed the list when a watched task ended; refresh once per run.
    fn refresh_after_task(&mut self) {
        let run_id = text(&self.task, "runId");
        if self.task["terminal"] == true && !run_id.is_empty() && run_id != self.refreshed_run {
            self.refreshed_run = run_id.into();
            self.refresh_packs();
        }
    }
    fn open_options(&mut self, ui: &AppWindow) {
        let version_id = ui.get_pack_selected_id().to_string();
        self.options = OptionsState {
            generation: self.options.generation + 1,
            ..Default::default()
        };
        // Like Kotlin's loading snapshot: defaults, not the previous pack's values.
        ui.set_options_draft(to_ui(&Draft::default()));
        self.render_options(ui);
        ui.set_options_error("".into());
        let tx = self.tx.clone();
        let generation = self.options.generation;
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(move || {
                pack_options::load(&pack_options::database_path(), &version_id)
            })
            .await
            .context("读取整合包设置失败")
            .and_then(|r| r);
            let _ = tx.send(Reply::OptionsLoaded(generation, result));
        });
    }
    fn render_options(&self, ui: &AppWindow) {
        let o = &self.options;
        ui.set_options_loading(o.saved.is_none() && !o.failed);
        ui.set_options_enabled(o.saved.is_some() && !o.failed && !o.saving);
        ui.set_options_total_memory(o.total_memory_mb);
        ui.set_options_dirty(
            o.saved
                .as_ref()
                .is_some_and(|saved| *saved != from_ui(&ui.get_options_draft())),
        );
    }
    /// Kotlin `updateDraft`: a new draft clears the error and recomputes dirty.
    fn options_update(&mut self, ui: &AppWindow) {
        let mut d = ui.get_options_draft();
        if d.java_custom_enabled && !self.java_was_enabled && d.java_path.is_empty() {
            d.java_path = self.global_java_path.clone().into();
            ui.set_options_draft(d.clone());
        }
        self.java_was_enabled = d.java_custom_enabled;
        ui.set_options_error("".into());
        self.render_options(ui);
    }
    fn save_options(&mut self, ui: &AppWindow, back: bool) {
        let o = &self.options;
        if o.saved.is_none() || o.failed || o.saving {
            return;
        }
        self.options.saving = true;
        self.render_options(ui);
        let draft = from_ui(&ui.get_options_draft());
        let version_id = ui.get_pack_selected_id().to_string();
        let total = self.options.total_memory_mb;
        let tx = self.tx.clone();
        let generation = self.options.generation;
        self.runtime.spawn(async move {
            let saved = draft.clone();
            let result = tokio::task::spawn_blocking(move || {
                pack_options::save(&pack_options::database_path(), &version_id, &draft, total)
            })
            .await
            .context("保存整合包设置失败")
            .and_then(|r| r);
            let _ = tx.send(Reply::OptionsSaved(generation, saved, back, result));
        });
    }
    pub fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        match action {
            "installer.open" => {
                self.open_native();
            }
            "installer.close" if self.installer.is_null() && !self.installer_opening && !self.archive_bridge => {}
            "installer.close" => { self.end_bridge(); self.installer=Value::Null; }
            "installer.dismissError" if self.installer.is_null() => {}
            "installer.pickArchive" | "installer.pickDirectory" | "options.pickJdk" => {
                self.pick(action)
            }
            "installer.install" | "installer.confirmMissing" => {
                ui.set_install_can_submit(false);
                if self.archive_bridge {
                    let confirmed = action.ends_with("confirmMissing");
                    self.call("installer.install", json!({"missingFilesConfirmed":confirmed}));
                } else {
                    match self.native.request() {
                        Ok(request) => {
                            let id = uuid::Uuid::now_v7().to_string();
                            let versions = local_packs::versions_dir();
                            let verb = if request.source.is_some() { "导入" } else { "创建" };
                            let title = format!("{verb}整合包{}", request.name.trim());
                            let dedupe = format!("local-modpack-install:{id}");
                            let run = self.native_tasks.submit(&self.runtime, title, dedupe, move |context| {
                                crate::import::installer::install(&versions, &id, &request, &context)
                            });
                            self.watched = run;
                            self.import_generation += 1;
                            self.native.value = Value::Null;
                            self.installer = Value::Null;
                            self.merge_tasks();
                            self.navigate("packs");
                            self.navigate("tasks");
                        }
                        Err(error) => {
                            self.native.error(error);
                            self.native_render();
                        }
                    }
                }
            }
            "installer.group" => {
                if !self.collapsed_candidates.remove(value) {
                    self.collapsed_candidates.insert(value.into());
                }
                self.installer(&self.installer.clone());
            }
            "installer.optionalIndex" => {
                if let Some(key) = value
                    .parse::<usize>()
                    .ok()
                    .and_then(|i| self.visible_keys.get(i))
                    .cloned()
                    && self
                        .contents
                        .iter()
                        .any(|v| text(v, "key") == key && v["optional"] == true)
                {
                    self.import_command("optional", json!({"action":"optional","key":key}));
                }
            }
            "installer.retryArchive" => {
                self.import_command("archive",json!({"action":"archive","path":text(&self.installer["editor"],"archivePath")}));
            }
            a if a.starts_with("installer.") => {
                let command = a.trim_start_matches("installer.");
                self.import_command(command,json!({"action":command,"value":value,"index":value.parse::<usize>().unwrap_or(0)}));
            }
            "packs.refresh" => self.refresh_packs(),
            "menu" => {
                ui.set_pack_menu(false);
                match value {
                    "0" => self.begin_play(),
                    "1" => {
                        self.open_options(&ui);
                        self.navigate("options");
                    }
                    "2" => match self.pack_dirs.get(ui.get_pack_selected_id().as_str()) {
                        Some(path) => self.open_directory(&path.to_string_lossy()),
                        None => ui.set_action_error("找不到这个整合包，请刷新列表".into()),
                    },
                    "3" => ui.invoke_stub("整合包详情".into()),
                    "4" => ui.invoke_stub("整合包内容".into()),
                    _ => {}
                }
            }
            "options.update" => self.options_update(&ui),
            "options.reset" => {
                ui.set_options_draft(to_ui(&Draft::default()));
                self.options_update(&ui);
            }
            "options.save" | "options.saveBack" => {
                self.options_update(&ui);
                self.save_options(&ui, action.ends_with("saveBack"));
            }
            "options.discard" => {
                ui.set_options_dirty(false);
                self.navigate("back");
            }
            "options.close" => {
                self.options = OptionsState {
                    generation: self.options.generation + 1,
                    ..Default::default()
                };
            }
            "play.nickname" => {
                ui.set_play_nonstandard_name(!standard_name(value));
            }
            "play.browser" => self.open_url(value),
            "play.cancel" => {
                ui.set_local_play(false);
                ui.set_play_busy(false);
                self.call("packPlay.cancel", json!({}));
            }
            "play.start" | "play.duplicate" => {
                let size = ui.window().size();
                ui.set_play_busy(true);
                self.call("packPlay.start",json!({"versionId":ui.get_pack_selected_id().as_str(),"mode":if ui.get_play_online(){"Online"}else{"Offline"},"nickname":ui.get_play_nickname().as_str(),"windowWidth":size.width,"windowHeight":size.height,"allowDuplicateVersion":action=="play.duplicate"}));
            }
            "tasks.subscribe" => {
                self.merge_tasks();
                // Only a Kotlin task needs the service; opening the screen alone does not.
                if !self.watched.is_empty() && !self.native_tasks.owns(&self.watched) {
                    self.call("tasks.watch", json!({"runId":self.watched}));
                }
            }
            "tasks.cancel" => {
                let run = if value.is_empty() { self.watched.clone() } else { value.to_owned() };
                if self.native_tasks.owns(&run) {
                    self.native_tasks.cancel(&run);
                } else {
                    self.call("tasks.cancel", json!({"runId":run}));
                }
            }
            "tasks.watch" => {
                self.collapsed.clear();
                self.watched = value.into();
                if let Some(task) = self.native_tasks.snapshot(value) {
                    self.task = task;
                    self.render_task();
                } else {
                    self.call("tasks.watch", json!({"runId":value}));
                }
            }
            "tasks.toggle" => {
                if let Some(i) = value.parse::<usize>().ok()
                    && let Some(v) = self.visible_task_rows().get(i)
                {
                    let key = text(v, "key").to_string();
                    if !self.collapsed.remove(&key) {
                        self.collapsed.insert(key);
                    }
                    self.render_task();
                }
            }
            "console.subscribe" | "console.select" => {
                if action.ends_with("select") {
                    self.session_id = value.into();
                }
                self.call("console.subscribe", json!({"sessionId":self.session_id}));
            }
            "console.clear" => self.call("console.clear", json!({"sessionId":self.session_id})),
            "console.line" => {
                if let Some(row) = value
                    .parse::<usize>()
                    .ok()
                    .and_then(|i| self.logs.row_data(i))
                {
                    ui.set_activity_selected_log(row.text);
                }
            }
            "console.export" => self.export(ui.get_activity_log_text().to_string()),
            _ => {}
        }
    }
    fn open_url(&self, url: &str) {
        let url = url.to_owned();
        let tx = self.tx.clone();
        let generation = self.generation;
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(move || webbrowser::open(&url).map(|_| None))
                .await
                .context("打开页面失败")
                .and_then(|v| v.map_err(Into::into));
            let _ = tx.send(Reply::Pick(generation, "browser".into(), result));
        });
    }
    fn pick(&self, kind: &str) {
        let kind = kind.to_owned();
        let tx = self.tx.clone();
        let generation = self.generation;
        self.runtime.spawn(async move {
            let dialog = rfd::AsyncFileDialog::new();
            let file = if kind == "installer.pickArchive" {
                let dialog = dialog
                    .set_title("选择客户端安装包")
                    .add_filter("整合包安装包", &["zip", "mrpack"]);
                let dialog = if let Some(home) =
                    std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME"))
                {
                    dialog.set_directory(std::path::PathBuf::from(home).join("Downloads"))
                } else {
                    dialog
                };
                dialog.pick_file().await
            } else {
                dialog
                    .set_title(if kind == "options.pickJdk" {
                        "选择JDK目录"
                    } else {
                        "选择整合包所在的文件夹"
                    })
                    .pick_folder()
                    .await
            };
            let _ = tx.send(Reply::Pick(
                generation,
                kind,
                Ok(file.map(|f| f.path().to_string_lossy().into_owned())),
            ));
        });
    }
    fn export(&self, text: String) {
        let tx = self.tx.clone();
        let generation = self.generation;
        self.runtime.spawn(async move {
            let result = async {
                let Some(file) = rfd::AsyncFileDialog::new()
                    .set_title("导出日志")
                    .set_file_name("minecraft-console.log")
                    .save_file()
                    .await
                else {
                    return Ok(None);
                };
                file.write(text.as_bytes()).await?;
                Ok(Some("日志已导出".into()))
            }
            .await;
            let _ = tx.send(Reply::Pick(generation, "export".into(), result));
        });
    }
    pub fn tick(&mut self) {
        // Rust tasks publish freely; the UI samples them like Kotlin's sample(34).
        if self.task_sample.elapsed() >= std::time::Duration::from_millis(34) {
            self.task_sample = std::time::Instant::now();
            self.merge_tasks();
        }
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Scan(g, list) if g == self.import_generation && !self.archive_bridge => {
                    self.native.candidates(list);
                    self.native_render();
                }
                Reply::Directory(g, path, result)
                    if g == self.import_generation && !self.archive_bridge =>
                {
                    self.native.directory(path, result);
                    self.native_render();
                }
                Reply::Packs(g, list) if g == self.packs_generation => {
                    tracing::info!(generation=g,count=list.packs.len(),"整合包列表已读取");
                    if let Some(ui) = self.ui.upgrade() {
                        ui.set_packs_loading(false);
                        ui.set_packs_error(list.error.as_str().into());
                    }
                    self.pack_dirs = list.packs.iter().map(|p| (p.id.clone(), p.directory.clone())).collect();
                    self.loaded_packs = Some(list.packs);
                }
                Reply::OptionsLoaded(g, result) if g == self.options.generation => {
                    let Some(ui) = self.ui.upgrade() else { continue };
                    match result {
                        Ok(loaded) => {
                            self.java_was_enabled = loaded.draft.java_custom_enabled;
                            self.options.total_memory_mb = loaded.total_memory_mb;
                            ui.set_options_draft(to_ui(&loaded.draft));
                            self.options.saved = Some(loaded.draft);
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "读取整合包设置失败");
                            self.options.failed = true;
                            ui.set_options_error(format!("读取整合包设置失败：{}", root_message(&error)).into());
                        }
                    }
                    self.render_options(&ui);
                }
                Reply::OptionsSaved(g, draft, back, result) if g == self.options.generation => {
                    let Some(ui) = self.ui.upgrade() else { continue };
                    self.options.saving = false;
                    match result {
                        Ok(()) => {
                            self.options.saved = Some(draft);
                            self.render_options(&ui);
                            if back {
                                ui.set_options_dirty(false);
                                self.navigate("back");
                            }
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "保存整合包设置失败");
                            ui.set_options_error(format!("保存整合包设置失败：{}", root_message(&error)).into());
                            self.render_options(&ui);
                        }
                    }
                }
                Reply::Service(g,method,result) if g==self.generation=>{
                    self.busy=false;
                    if method == "installer.open" { self.installer_opening = false; }
                    match result {
                        Ok(v)=>match method.as_str() {
                            "installer.open" if self.archive_bridge=>self.installer(&v),
                            "installer.install" if v["runId"].is_string()=>{
                                if let Some(ui)=self.ui.upgrade(){ui.set_install_missing(false);}
                                self.watched=text(&v,"runId").into();self.navigate("packs");self.call("tasks.watch",json!({"runId":v["runId"]}));self.end_bridge();
                                self.navigate("tasks");
                            }
                            "installer.install"=>self.installer(&v),
                            "packPlay.open"=>{
                                self.play(&v);
                                if v["local"]==false{self.action("play.start","");}
                            }
                            "installer.close"=>{},
                            "tasks.watch" if text(&v,"runId")==self.watched=>{self.task=v;self.render_task();self.refresh_after_task();}
                            _=>{}
                        },
                        Err(error)=>if let Some(ui)=self.ui.upgrade(){
                            self.queue.clear();
                            self.installer_opening = false;
                            ui.set_action_error(format!("{error:#}").into());
                            if method.starts_with("installer"){ui.set_install_can_submit(self.installer["editor"]["canSubmit"]==true);}
                            ui.set_play_busy(false);
                            if method == "installer.open" { ui.set_install_scanning(false); }
                            if method == "packPlay.open" { ui.set_local_play(false); }
                        }
                    }
                    self.pump();
                }
                Reply::Pick(g,kind,result) if g==self.generation=>match result {
                    Ok(Some(path))=>match kind.as_str(){
                        "installer.pickArchive"|"installer.pickDirectory"=>{let action=if kind.ends_with("Archive"){"archive"}else{"directory"};self.import_command(action,json!({"action":action,"path":path}));},
                        "options.pickJdk"=>if let Some(ui)=self.ui.upgrade(){let mut d=ui.get_options_draft();d.java_path=path.into();ui.set_options_draft(d);self.options_update(&ui);},
                        "export"=>if let Some(ui)=self.ui.upgrade(){ui.invoke_notify(path.into());},_=>{}
                    },
                    Ok(None)=>{},Err(e)=>if let Some(ui)=self.ui.upgrade(){ui.set_action_error(format!("{e:#}").into());}
                },
                Reply::Image(g,key,result) if g==self.generation=>{
                    self.pending_images.remove(&key);
                    match result {
                        Ok(image)=>{self.images.insert(key,images::to_slint(&image));}
                        Err(error)=>{tracing::warn!(%error,"整合包内容图标加载失败");self.failed_images.insert(key);}
                    }
                    self.render_contents();
                }
                _=>{}
            }
        }
    }
    fn open_directory(&self, path: &str) {
        let path = path.to_owned();
        let tx = self.tx.clone();
        let generation = self.generation;
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(move || {
                #[cfg(windows)]
                let output = std::process::Command::new("explorer.exe")
                    .arg(&path)
                    .spawn();
                #[cfg(target_os = "macos")]
                let output = std::process::Command::new("open").arg(&path).spawn();
                #[cfg(all(not(windows), not(target_os = "macos")))]
                let output = std::process::Command::new("xdg-open").arg(&path).spawn();
                output.map(|_| None).map_err(anyhow::Error::from)
            })
            .await
            .context("打开目录失败")
            .and_then(|v| v);
            let _ = tx.send(Reply::Pick(generation, "directory".into(), result));
        });
    }
    pub fn event(&mut self, topic: &str, v: &Value) {
        match topic {
            "installer.state" if self.archive_bridge => self.installer(v),
            "packPlay.state" => self.play(v),
            "task.state" if self.watched.is_empty() || self.watched==text(v,"runId") => {
                self.task = v.clone();
                self.render_task();
                self.refresh_after_task();
            }
            "tasks.state" => {
                self.service_tasks = array(v, "tasks").to_vec();
                self.merge_tasks();
            }
            "console.state" => {
                self.session_id = text(v, "sessionId").into();
                sync(
                    &self.sessions,
                    array(v, "sessions")
                        .iter()
                        .enumerate()
                        .map(|(i, t)| CandidateRow {
                            name: text(t, "title").into(),
                            detail: text(t, "id").into(),
                            index: i as i32,
                            collapsed: text(t, "id") == self.session_id,
                            ..Default::default()
                        })
                        .collect(),
                );
                let lines = array(v, "lines")
                    .iter()
                    .filter_map(Value::as_str)
                    .collect::<Vec<_>>();
                sync(
                    &self.logs,
                    lines
                        .iter()
                        .map(|line| LogRow {
                            text: (*line).into(),
                            level: if line.contains("ERROR") {
                                2
                            } else if line.contains("WARN") {
                                1
                            } else {
                                0
                            },
                        })
                        .collect(),
                );
                if let Some(ui) = self.ui.upgrade() {
                    ui.set_activity_log_text(lines.join("\n").into());
                }
            }
            _ => {}
        }
    }
    fn installer(&mut self, v: &Value) {
        self.installer = v.clone();
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        let e = &v["editor"];
        ui.set_install_editor(e.is_object());
        ui.set_install_scanning(v["scanning"] == true);
        ui.set_install_loading(
            v["archiveLoading"] == true
                || v["directoryLoading"] == true
                || e["previewLoading"] == true,
        );
        ui.set_install_can_submit(e["canSubmit"] == true);
        ui.set_install_version_selectable(e["versionSelectable"] == true);
        ui.set_install_include_saves(e["includeSaves"] == true);
        ui.set_install_source(text(e, "source").into());
        ui.set_install_source_directory(text(e, "sourceDirectory").into());
        ui.set_install_error(text(v, "error").into());
        ui.set_install_progress(text(e, "progress").into());
        ui.set_install_progress_fraction(e["progressFraction"].as_f64().unwrap_or(-1.) as f32);
        ui.set_install_name(text(e, "name").into());
        ui.set_install_comment(text(e, "comment").into());
        ui.set_install_icon(text(e, "iconUrl").into());
        ui.set_install_missing(e["confirmMissingFiles"] == true);
        let missing: Vec<_> = array(e, "missingFiles")
            .iter()
            .filter_map(Value::as_str)
            .collect();
        ui.set_install_missing_count(missing.len() as i32);
        ui.set_install_missing_text(format!("这些文件的作者不允许第三方下载，或者在CurseForge上找不到。继续导入将不包含它们，你可以之后自己放进整合包文件夹。\n{}",missing.iter().map(|name| format!("· {name}")).collect::<Vec<_>>().join("\n")).into());
        let candidates = |key| {
            let mut seen = HashSet::new();
            array(v, key)
                .iter()
                .enumerate()
                .map(|(i, c)| {
                    let group = text(c, "directory").to_string();
                    CandidateRow {
                        name: text(c, "name").into(),
                        detail: format!("{} / {}", text(c, "mcVersion"), text(c, "modLoader"))
                            .into(),
                        index: i as i32,
                        header: seen.insert(group.clone()),
                        collapsed: self.collapsed_candidates.contains(&group),
                        group: group.into(),
                    }
                })
                .collect()
        };
        sync(&self.candidates, candidates("candidates"));
        sync(&self.picked, candidates("pickedCandidates"));
        let runtimes = array(v, "runtimes");
        sync(
            &self.runtimes,
            runtimes.iter().map(|r| text(r, "label").into()).collect(),
        );
        ui.set_install_runtime_index(
            runtimes
                .iter()
                .position(|r| r["mcVersion"] == e["mcVersion"] && r["modLoader"] == e["modLoader"])
                .unwrap_or(0) as i32,
        );
        self.contents = array(e, "contents").to_vec();
        let size: u64 = self
            .contents
            .iter()
            .filter(|f| f["selected"] == true)
            .filter_map(|f| f["size"].as_u64())
            .sum();
        ui.set_install_size(human_file_size(size).into());
        self.render_contents();
    }
    fn render_contents(&mut self) {
        let (query, kind, descending) = &self.filter;
        let query = query.to_lowercase();
        let mut files: Vec<_> = self
            .contents
            .iter()
            .filter(|f| {
                let name = text(f, "name");
                let k = text(f, "kind");
                (query.is_empty() || name.to_lowercase().contains(&query))
                    && (*kind == 0
                        || ["", "Mod", "资源包", "光影"]
                            .get(*kind as usize)
                            .is_some_and(|wanted| k == *wanted))
            })
            .collect();
        files.sort_by_key(|a| text(a, "name").to_lowercase());
        if *descending {
            files.reverse();
        }
        self.visible_keys = files.iter().map(|f| text(f, "key").into()).collect();
        let mut requested = vec![];
        let rows = files
            .iter()
            .map(|f| {
                let key = text(f, "icon");
                let image = self.images.get(key).cloned();
                if !key.is_empty()
                    && image.is_none()
                    && !self.pending_images.contains(key)
                    && !self.failed_images.contains(key)
                {
                    requested.push(key.to_string());
                }
                ContentRow {
                    name: text(f, "name").into(),
                    detail: text(f, "detail").into(),
                    selection: match (f["optional"] == true, f["selected"] == true) {
                        (true, true) => 1,
                        (true, false) => 2,
                        (false, _) => 3,
                    },
                    kind: text(f, "kind").into(),
                    icon: image.unwrap_or_default(),
                    image_state: if key.is_empty() {
                        2
                    } else if self.images.contains_key(key) {
                        1
                    } else if self.failed_images.contains(key) {
                        3
                    } else {
                        0
                    },
                    ..Default::default()
                }
            })
            .collect();
        sync(&self.content_rows, rows);
        if let Some(api) = &self.api {
            for key in requested
                .into_iter()
                .take(5_usize.saturating_sub(self.pending_images.len()))
            {
                self.pending_images.insert(key.clone());
                let http = api.http.clone();
                let tx = self.tx.clone();
                let generation = self.generation;
                self.runtime.spawn(async move {
                    let image = images::icon(&http, &key, 80).await;
                    let _ = tx.send(Reply::Image(generation, key, image));
                });
            }
        }
    }
    /// Screenshot fixtures only (`tools/u4a-visuals.ps1`); real options come from `pack_options`.
    fn options_proof(&mut self, v: &Value) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        let d = &v["draft"];
        self.java_was_enabled = d["javaCustomEnabled"] == true;
        ui.set_options_loading(v["loading"] == true);
        ui.set_options_enabled(
            v["loading"] != true && v["loadFailed"] != true && v["saving"] != true,
        );
        ui.set_options_dirty(v["dirty"] == true);
        ui.set_options_error(text(v, "error").into());
        ui.set_options_total_memory(v["totalMemoryMb"].as_i64().unwrap_or(0) as i32);
        ui.set_options_draft(OptionDraft {
            java_custom_enabled: d["javaCustomEnabled"] == true,
            java_path: text(d, "javaPath").into(),
            memory_custom_enabled: d["memoryCustomEnabled"] == true,
            max_memory_text: text(d, "maxMemoryText").into(),
            jdwp_enabled: d["jdwpEnabled"] == true,
            jdwp_param: text(d, "jdwpParam").into(),
            custom_jvm_params: text(d, "customJvmParams").into(),
            forgeguard_disabled: d["forgeguardDisabled"] == true,
        });
        if v["navigateBack"] == true {
            ui.set_options_dirty(false);
            self.navigate("back");
        }
    }
    fn play(&mut self, v: &Value) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        ui.set_play_busy(v["busy"] == true);
        ui.set_play_error(text(v, "error").into());
        ui.set_play_device_uri(text(v, "deviceUri").into());
        if v["closed"] == true {
            ui.set_local_play(false);
            return;
        }
        if v["duplicateVersion"] == true {
            ui.set_local_play(false);
            ui.set_duplicate_host(format!("pack:{}", text(v, "versionId")).into());
            return;
        }
        if v["nickname"].is_string() {
            ui.set_play_nickname(text(v, "nickname").into());
            ui.set_play_nonstandard_name(!standard_name(text(v, "nickname")));
            ui.set_play_online(text(v, "mode") == "Online");
        }
        if v["onlineAvailable"].is_boolean() {
            ui.set_play_online_available(v["onlineAvailable"] == true);
        }
        if v["local"] == true {
            ui.set_local_play(true);
        }
        let uri = text(v, "deviceUri");
        if !uri.is_empty() && uri != self.last_device_uri {
            self.last_device_uri = uri.into();
            self.open_url(uri);
        }
    }
    fn visible_task_rows(&self) -> Vec<Value> {
        let mut hidden_depth = None;
        array(&self.task, "rows")
            .iter()
            .filter(|row| {
                let depth = row["depth"].as_i64().unwrap_or(0);
                if hidden_depth.is_some_and(|hidden| depth > hidden) {
                    return false;
                }
                hidden_depth = None;
                if self.collapsed.contains(text(row, "key")) {
                    hidden_depth = Some(depth);
                }
                true
            })
            .cloned()
            .collect()
    }
    fn render_task(&mut self) {
        let run_id = text(&self.task, "runId").to_string();
        if self.task_run_id != run_id {
            self.task_run_id = run_id;
            self.collapsed = array(&self.task, "rows")
                .iter()
                .filter(|row| row["group"] == true && row["expanded"] == false)
                .map(|row| text(row, "key").to_string())
                .collect();
        }
        if let Some(ui) = self.ui.upgrade() {
            ui.set_activity_can_cancel(self.task["terminal"]==false);
            ui.set_activity_title(text(&self.task, "title").into());
            ui.set_activity_message(text(&self.task, "message").into());
            ui.set_activity_error(text(&self.task, "error").into());
            ui.set_activity_fraction(self.task["fraction"].as_f64().unwrap_or(-1.) as f32);
        }
        sync(
            &self.tasks,
            self.visible_task_rows()
                .iter()
                .map(|r| TaskRow {
                    title: text(r, "title").into(),
                    detail: text(r, "detail").into(),
                    depth: r["depth"].as_i64().unwrap_or(0) as i32,
                    group: r["group"] == true,
                    expanded: !self.collapsed.contains(text(r, "key")),
                    status: r["status"].as_i64().unwrap_or(0) as i32,
                    progress: r["fraction"].as_f64().unwrap_or(-1.) as f32,
                })
                .collect(),
        );
    }
}
#[derive(Default)]
struct OptionsState {
    /// Bumped on open and close, so a late reply for another pack is dropped.
    generation: u64,
    /// The last loaded or saved draft; `None` while loading.
    saved: Option<Draft>,
    failed: bool,
    saving: bool,
    total_memory_mb: i32,
}
fn to_ui(d: &Draft) -> OptionDraft {
    OptionDraft {
        java_custom_enabled: d.java_custom_enabled,
        java_path: d.java_path.as_str().into(),
        memory_custom_enabled: d.memory_custom_enabled,
        max_memory_text: d.max_memory_text.as_str().into(),
        jdwp_enabled: d.jdwp_enabled,
        jdwp_param: d.jdwp_param.as_str().into(),
        custom_jvm_params: d.custom_jvm_params.as_str().into(),
        forgeguard_disabled: d.forgeguard_disabled,
    }
}
fn from_ui(d: &OptionDraft) -> Draft {
    Draft {
        java_custom_enabled: d.java_custom_enabled,
        java_path: d.java_path.to_string(),
        memory_custom_enabled: d.memory_custom_enabled,
        max_memory_text: d.max_memory_text.to_string(),
        jdwp_enabled: d.jdwp_enabled,
        jdwp_param: d.jdwp_param.to_string(),
        custom_jvm_params: d.custom_jvm_params.to_string(),
        forgeguard_disabled: d.forgeguard_disabled,
    }
}
/// Kotlin showed `cause.message`: the innermost reason, without the context chain.
fn root_message(error: &anyhow::Error) -> String {
    error.root_cause().to_string()
}
fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or("")
}
fn array<'a>(v: &'a Value, key: &str) -> &'a [Value] {
    v[key].as_array().map(Vec::as_slice).unwrap_or(&[])
}
fn sync<T: Clone + PartialEq + 'static>(model: &Rc<VecModel<T>>, rows: Vec<T>) {
    while model.row_count() > rows.len() {
        model.remove(model.row_count() - 1);
    }
    for (i, row) in rows.into_iter().enumerate() {
        if i >= model.row_count() {
            model.push(row);
        } else if model.row_data(i).as_ref() != Some(&row) {
            model.set_row_data(i, row);
        }
    }
}
fn standard_name(name: &str) -> bool {
    let n = name.trim();
    (3..=16).contains(&n.len()) && n.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_size_matches_kotlin_units() {
        assert_eq!(human_file_size(0), "0B");
        assert_eq!(human_file_size(1023), "1023B");
        assert_eq!(human_file_size(1024), "1.0KB");
        assert_eq!(human_file_size(1536), "1.5KB");
        assert_eq!(human_file_size(5 * 1024 * 1024), "5.0MB");
        assert_eq!(human_file_size(3 * 1024 * 1024 * 1024), "3.0GB");
    }

    #[test]
    fn options_title_joins_names_like_compose() {
        assert_eq!(
            options_title("v1", "测试整合包", "1.21.1"),
            "测试整合包 1.21.1"
        );
        assert_eq!(options_title("v1", "测试整合包", " "), "测试整合包");
        assert_eq!(options_title("v1", "", ""), "v1");
    }
}
