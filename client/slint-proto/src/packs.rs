//! U4a UI adapters. Pack rules, account credentials and installation stay in Kotlin.
use crate::{
    AppWindow, CandidateRow, ContentRow, LogRow, OptionDraft, TaskRow, api::Client, images,
};
use anyhow::{Context, Result};
use serde::Deserialize;
use serde_json::{Value, json};
use slint::{ComponentHandle, Model, VecModel};
use std::{
    cell::RefCell,
    collections::{HashSet, VecDeque},
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
    if title.is_empty() { id.to_string() } else { title }
}
#[derive(Clone, Debug, Deserialize)]
pub struct InstalledPack {
    #[serde(rename = "versionId")]
    pub id: String,
    pub name: String,
    pub icon: Option<String>,
    #[serde(rename = "versionName")]
    pub version: String,
    pub local: bool,
}
enum Reply {
    Service(u64, String, Result<Value>),
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
    contents: Vec<Value>,
    visible_keys: Vec<String>,
    filter: (String, i32, bool),
    images: std::collections::HashMap<String, slint::Image>,
    pending_images: HashSet<String>,
    failed_images: HashSet<String>,
    options_pending: usize,
    global_java_path: String,
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
            contents: vec![],
            visible_keys: vec![],
            filter: (String::new(), 0, false),
            images: Default::default(),
            pending_images: Default::default(),
            failed_images: Default::default(),
            options_pending: 0,
            global_java_path: String::new(),
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
        self.api = api;
        self.options_pending = 0;
        self.installer = Value::Null;
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
                self.call("tasks.subscribe", json!({}));
                self.call("installedPacks.subscribe", json!({}));
                if ui.get_pack_screen() == 1 {
                    self.call("installer.open", json!({}));
                }
                if ui.get_pack_screen() == 2 {
                    ui.set_options_enabled(false);
                    ui.set_options_error("启动服务已重新连接，请返回后重新打开设置".into());
                }
            }
        }
    }
    fn call(&mut self, method: &str, params: Value) {
        if method == "packOptions.action" {
            self.options_pending += 1;
        }
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
            self.call(
                "packPlay.open",
                json!({"versionId":ui.get_pack_selected_id().as_str()}),
            );
        }
    }
    fn options_update(&mut self, ui: &AppWindow) {
        let mut d = ui.get_options_draft();
        if d.java_custom_enabled && !self.java_was_enabled && d.java_path.is_empty() {
            d.java_path = self.global_java_path.clone().into();
            ui.set_options_draft(d.clone());
        }
        self.java_was_enabled = d.java_custom_enabled;
        self.call("packOptions.action",json!({"action":"update","draft":{
            "javaCustomEnabled":d.java_custom_enabled,"javaPath":d.java_path.as_str(),
            "memoryCustomEnabled":d.memory_custom_enabled,"maxMemoryText":d.max_memory_text.as_str(),
            "jdwpEnabled":d.jdwp_enabled,"jdwpParam":d.jdwp_param.as_str(),"customJvmParams":d.custom_jvm_params.as_str(),"forgeguardDisabled":d.forgeguard_disabled}}));
        ui.set_options_dirty(true);
    }
    pub fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        match action {
            "installer.open" => {
                ui.set_install_scanning(true);
                self.call("installer.open", json!({}));
            }
            "installer.close" => self.call("installer.close", json!({})),
            "installer.pickArchive" | "installer.pickDirectory" | "options.pickJdk" => {
                self.pick(action)
            }
            "installer.install" | "installer.confirmMissing" => {
                ui.set_install_can_submit(false);
                self.call(
                    "installer.install",
                    json!({"missingFilesConfirmed":action.ends_with("confirmMissing")}),
                );
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
                    self.call("installer.action", json!({"action":"optional","key":key}));
                }
            }
            "installer.retryArchive" => {
                self.call("installer.action",json!({"action":"archive","path":text(&self.installer["editor"],"archivePath")}));
            }
            a if a.starts_with("installer.") => {
                let command = a.trim_start_matches("installer.");
                self.call("installer.action",json!({"action":command,"value":value,"index":value.parse::<usize>().unwrap_or(0)}));
            }
            "packs.refresh" => self.call("installedPacks.refresh", json!({})),
            "menu" => {
                ui.set_pack_menu(false);
                match value {
                    "0" => self.begin_play(),
                    "1" => {
                        ui.set_options_loading(true);
                        self.navigate("options");
                        self.call(
                            "packOptions.open",
                            json!({"versionId":ui.get_pack_selected_id().as_str()}),
                        );
                    }
                    "2" => self.call(
                        "installedPacks.directory",
                        json!({"versionId":ui.get_pack_selected_id().as_str()}),
                    ),
                    "3" => ui.invoke_stub("整合包详情".into()),
                    "4" => ui.invoke_stub("整合包内容".into()),
                    _ => {}
                }
            }
            "options.update" => self.options_update(&ui),
            "options.reset" => self.call("packOptions.action", json!({"action":"reset"})),
            "options.save" | "options.saveBack" => {
                self.options_update(&ui);
                self.call(
                    "packOptions.action",
                    json!({"action":"save","returnAfterSave":action.ends_with("saveBack")}),
                );
            }
            "options.discard" => {
                ui.set_options_dirty(false);
                self.navigate("back");
            }
            "options.close" => self.call("packOptions.action", json!({"action":"close"})),
            "play.nickname" => {
                ui.set_play_nonstandard_name(!standard_name(value));
            }
            "play.browser" => self.open_url(value),
            "play.cancel" => {
                ui.set_local_play(false);
                self.call("packPlay.cancel", json!({}));
            }
            "play.start" | "play.duplicate" => {
                let size = ui.window().size();
                ui.set_play_busy(true);
                self.call("packPlay.start",json!({"versionId":ui.get_pack_selected_id().as_str(),"mode":if ui.get_play_online(){"Online"}else{"Offline"},"nickname":ui.get_play_nickname().as_str(),"windowWidth":size.width,"windowHeight":size.height,"allowDuplicateVersion":action=="play.duplicate"}));
            }
            "tasks.subscribe" => self.call("tasks.subscribe", json!({})),
            "tasks.watch" => {
                self.collapsed.clear();
                self.call("tasks.watch", json!({"runId":value}));
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
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Service(g,method,result) if g==self.generation=>{
                    self.busy=false;
                    if method=="packOptions.action" {self.options_pending=self.options_pending.saturating_sub(1);}
                    match result {
                        Ok(v)=>match method.as_str() {
                            "installer.open"=>self.installer(&v),
                            "installer.install" if v["runId"].is_string()=>{
                                if let Some(ui)=self.ui.upgrade(){ui.set_install_missing(false);}
                                self.navigate("packs");self.call("tasks.watch",json!({"runId":v["runId"]}));self.call("installer.close",json!({}));
                                self.navigate("tasks");
                            }
                            "installer.install"=>self.installer(&v),
                            "packOptions.open"=>self.options(&v),
                            "packOptions.action" if self.options_pending==0 && v.get("draft").is_some()=>self.options(&v),
                            "packPlay.open"=>{
                                self.play(&v);
                                if v["local"]==false{self.action("play.start","");}
                            }
                            "installedPacks.directory"=>{if let Some(path)=v["path"].as_str(){self.open_directory(path);}}
                            "tasks.watch"=>{self.task=v;self.render_task();}
                            _=>{}
                        },
                        Err(error)=>if let Some(ui)=self.ui.upgrade(){
                            ui.set_action_error(format!("{error:#}").into());
                            if method.starts_with("installer"){ui.set_install_can_submit(self.installer["editor"]["canSubmit"]==true);}
                            ui.set_play_busy(false);
                        }
                    }
                    self.pump();
                }
                Reply::Pick(g,kind,result) if g==self.generation=>match result {
                    Ok(Some(path))=>match kind.as_str(){
                        "installer.pickArchive"|"installer.pickDirectory"=>self.call("installer.action",json!({"action":if kind.ends_with("Archive"){"archive"}else{"directory"},"path":path})),
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
            "installer.state" => self.installer(v),
            "packOptions.state" if self.options_pending == 0 => self.options(v),
            "packPlay.state" => self.play(v),
            "installedPacks.state" => {
                if let Some(ui) = self.ui.upgrade() {
                    ui.set_packs_loading(v["loading"] == true);
                    ui.set_packs_error(text(v, "error").into());
                }
            }
            "task.state" => {
                self.task = v.clone();
                self.render_task();
            }
            "tasks.state" => {
                if let Some(ui) = self.ui.upgrade() {
                    ui.set_tasks_running(array(v, "tasks").iter().any(|t| t["terminal"] == false));
                }
                sync(
                    &self.task_list,
                    array(v, "tasks")
                        .iter()
                        .enumerate()
                        .map(|(i, t)| CandidateRow {
                            name: text(t, "title").into(),
                            detail: text(t, "runId").into(),
                            index: i as i32,
                            ..Default::default()
                        })
                        .collect(),
                );
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
        let missing: Vec<_> = array(e, "missingFiles").iter().filter_map(Value::as_str).collect();
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
    fn options(&mut self, v: &Value) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        let d = &v["draft"];
        self.global_java_path = text(v, "globalJavaPath").into();
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
        assert_eq!(options_title("v1", "测试整合包", "1.21.1"), "测试整合包 1.21.1");
        assert_eq!(options_title("v1", "测试整合包", " "), "测试整合包");
        assert_eq!(options_title("v1", "", ""), "v1");
    }
}
