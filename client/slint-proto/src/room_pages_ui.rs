//! Wiring of the room's 后台, 存档 and 回档 pages (U4 batch c2a) to the `RoomPages` global.
//! Shaped like `rooms_ui.rs`: one `action(name, value)` callback, an epoch that drops replies
//! for a page that was left, and a channel drained on the UI thread. The logic is in
//! `rooms/backend.rs`, `rooms/world.rs` and `rooms/rollback.rs`.
use crate::{
    AppWindow, LogRow, RollbackRow, RoomPages,
    api::Client,
    rooms::{
        backend::{self, ConsoleLines, LogEvent, RuntimeAction},
        model::HostDetail,
        permissions::{self, Me},
        requests,
        rollback::{self, Confirm as RollbackConfirm},
        world::{self, DownloadStatus},
    },
    tasks,
};
use anyhow::Result;
use slint::{ComponentHandle, SharedString, VecModel};
use std::{
    path::PathBuf,
    rc::Rc,
    sync::mpsc,
    time::{Duration, Instant},
};
use tokio::{runtime::Handle, task::JoinHandle};

#[path = "room_pages_mods.rs"]
mod mods_page;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Page {
    None,
    Backend,
    World,
    Rollback,
    Mods,
}
impl Page {
    fn from_screen(screen: i32) -> Self {
        match screen {
            1 => Self::Backend,
            2 => Self::World,
            3 => Self::Rollback,
            4 => Self::Mods,
            _ => Self::None,
        }
    }
}

/// What 下载房间存档 found before the task can start.
enum Prepared {
    Ready(DownloadStatus, PathBuf),
    /// The pack is being installed; the download follows it.
    Installing(DownloadStatus),
    /// The pack is missing; the prompt data for `install.prompt`.
    Missing(DownloadStatus, serde_json::Value),
}

enum Reply {
    Detail(u64, Result<HostDetail>),
    World(u64, Result<(HostDetail, Option<DownloadStatus>)>),
    Reset(u64, Result<(HostDetail, Option<DownloadStatus>)>),
    Download(u64, Result<Prepared>),
    DownloadStatus(u64, Result<DownloadStatus>),
    Log(u64, LogEvent),
    LogReset(u64),
    Runtime(u64, RuntimeAction, Result<()>),
    Command(u64, Result<String>),
    Rollback(u64, bool, Result<(Option<&'static str>, rollback::Status)>),
    Exported(Result<bool>),
    Opened(Result<()>),
    Mods(u64, Result<(HostDetail, Vec<crate::pack_install::mods::Mod>)>),
    Cards(u64, std::collections::HashMap<String, mods_page::Card>),
    ModIcon(String, Option<image::RgbaImage>),
    ModsChanged(u64, mods_page::ModsOp, Result<Vec<crate::pack_install::mods::Mod>>),
    Github(u64, mods_page::GithubReply),
}

enum Pending {
    Runtime(RuntimeAction),
    Rollback(RollbackConfirm),
    RemoveExtra(Vec<crate::pack_install::mods::Mod>),
    Side(crate::pack_install::mods::Mod, crate::pack_install::mods::Side),
}

/// A download that waits for the room's pack to be installed first (owner decision D5).
struct WaitingInstall {
    host: String,
    host_name: String,
    pack: String,
    version: String,
    checked: Instant,
}

pub struct RoomPagesUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    tasks: tasks::Manager,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    host: String,
    detail: Option<HostDetail>,
    epoch: u64,
    page: Page,
    busy: bool,
    console: ConsoleLines,
    shown_revision: u64,
    log_rows: Rc<VecModel<LogRow>>,
    stream: Option<JoinHandle<()>>,
    command_epoch: u64,
    access: world::Access,
    download: Option<DownloadStatus>,
    download_run: Option<(String, String)>,
    saves: Option<PathBuf>,
    waiting: Option<WaitingInstall>,
    rollback: Option<rollback::Status>,
    rollback_rows: Rc<VecModel<RollbackRow>>,
    refresh_at: Option<Instant>,
    pending: Option<Pending>,
    pending_nav: Vec<&'static str>,
    mods: mods_page::ModsView,
}

fn pages(ui: &AppWindow) -> RoomPages<'_> {
    ui.global::<RoomPages>()
}

fn hex_id(value: &str) -> bool {
    value.len() == 24 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn text(error: &anyhow::Error, fallback: &str) -> String {
    let text = error.to_string();
    if text.trim().is_empty() { fallback.into() } else { text }
}

fn local_offset() -> chrono::FixedOffset {
    *chrono::Local::now().offset()
}

fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

impl RoomPagesUi {
    pub fn wire(ui: &AppWindow, runtime: Handle, tasks: tasks::Manager) -> Rc<std::cell::RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let log_rows = Rc::new(VecModel::default());
        let rollback_rows = Rc::new(VecModel::default());
        pages(ui).set_logs(log_rows.clone().into());
        pages(ui).set_rollback_rows(rollback_rows.clone().into());
        let state = Rc::new(std::cell::RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            tasks,
            api: None,
            tx,
            rx,
            host: String::new(),
            detail: None,
            epoch: 0,
            page: Page::None,
            busy: false,
            console: ConsoleLines::default(),
            shown_revision: 0,
            log_rows,
            stream: None,
            command_epoch: 0,
            access: Default::default(),
            download: None,
            download_run: None,
            saves: None,
            waiting: None,
            rollback: None,
            rollback_rows,
            refresh_at: None,
            pending: None,
            pending_nav: Vec::new(),
            mods: Default::default(),
        }));
        let weak = Rc::downgrade(&state);
        pages(ui).on_action(move |action, value| {
            let Some(state) = weak.upgrade() else { return };
            match state.try_borrow_mut() {
                Ok(mut state) => state.action(action.as_str(), value.as_str()),
                Err(_) => tracing::warn!(%action, "房间页面操作重入，已忽略"),
            }
        });
        state
    }

    /// A new sign-in or a sign-out drops every page and request; a running download task keeps going.
    pub fn connect(&mut self, api: Option<Client>) {
        self.leave();
        self.api = api;
        self.waiting = None;
        self.download_run = None;
        self.host.clear();
        self.pending_nav.clear();
        if let Some(ui) = self.ui.upgrade() {
            let g = pages(&ui);
            g.set_alert_title("".into());
            g.set_confirm_title("".into());
        }
    }

    fn navigate(&mut self, action: &'static str) {
        self.pending_nav.push(action);
    }

    fn me(&self) -> Option<(String, bool)> {
        let api = self.api.as_ref()?;
        Some((api.uid().ok()?, api.is_dav))
    }

    fn alert(&self, ui: &AppWindow, title: &str, message: &str) {
        let g = pages(ui);
        g.set_alert_message(message.into());
        g.set_alert_title(title.into());
    }

    /// Stops what the current page runs; replies still in flight are dropped by the epoch.
    fn leave(&mut self) {
        self.epoch += 1;
        self.command_epoch += 1;
        if let Some(stream) = self.stream.take() {
            stream.abort();
        }
        self.busy = false;
        self.pending = None;
        self.refresh_at = None;
        if let Some(ui) = self.ui.upgrade() {
            let g = pages(&ui);
            g.set_busy("".into());
            g.set_command_open(false);
            g.set_command_sending(false);
            g.set_reset_open(false);
            g.set_gh_open(false);
            g.set_gh_loading(false);
            g.set_confirm_title("".into());
        }
    }

    /// Fresh state for a page of `host`.
    fn reset_view(&mut self, ui: &AppWindow) {
        let g = pages(ui);
        g.set_loading(true);
        g.set_error("".into());
        g.set_fail("".into());
        g.set_notice("".into());
        g.set_busy("".into());
        g.set_title("".into());
        g.set_host_name("".into());
        g.set_can_view(false);
        g.set_can_manage(false);
        g.set_can_reset(false);
        g.set_can_download(false);
        g.set_can_rollback(false);
        g.set_download_enabled(false);
        g.set_download_hint("".into());
        g.set_download_today("".into());
        g.set_has_saves(false);
        g.set_rollback_loaded(false);
        g.set_selected_log("".into());
    }

    fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = pages(&ui);
        match action {
            "open-mods" if hex_id(value) && self.api.is_some() => {
                self.leave();
                self.host = value.into();
                self.detail = None;
                self.reset_view(&ui);
                self.open_mods(&ui);
                self.navigate("room-mods");
            }
            "open-backend" | "open-world" if hex_id(value) && self.api.is_some() => {
                self.leave();
                self.host = value.into();
                self.detail = None;
                self.reset_view(&ui);
                if action == "open-backend" {
                    self.page = Page::Backend;
                    self.console.clear();
                    self.load_backend(&ui);
                    self.navigate("room-backend");
                } else {
                    self.page = Page::World;
                    self.download = None;
                    self.saves = None;
                    if let Some(run) = self.tasks.active(&world::task_key(value)) {
                        self.download_run = Some((value.into(), run));
                    }
                    self.load_world(&ui);
                    self.navigate("room-world");
                }
            }
            "retry" => match self.page {
                Page::Backend => self.load_backend(&ui),
                Page::World => self.load_world(&ui),
                Page::Rollback => self.load_rollback(&ui, "正在加载回档点", false),
                Page::Mods => self.load_mods(&ui),
                Page::None => {}
            },
            "back-nav" => self.navigate("back"),
            "alert-ok" => g.set_alert_title("".into()),
            "dismiss" => self.dismiss(&ui),
            "confirm-cancel" => {
                g.set_confirm_title("".into());
                self.pending = None;
            }
            "confirm" => {
                g.set_confirm_title("".into());
                match self.pending.take() {
                    Some(Pending::Runtime(a)) => self.run_runtime(a),
                    Some(Pending::Rollback(RollbackConfirm::Cancel)) => self.rollback_cancel(&ui),
                    Some(Pending::Rollback(c)) => {
                        if let Some(point) = c.point() {
                            self.rollback_request(&ui, point.to_owned());
                        }
                    }
                    Some(p @ (Pending::RemoveExtra(_) | Pending::Side(..))) => self.confirm_mods(&ui, p),
                    None => {}
                }
            }
            // 后台
            "runtime" => {
                let Some(a) = RuntimeAction::from_key(value) else { return };
                match a.confirm() {
                    None => self.run_runtime(a),
                    Some((title, message)) => {
                        self.pending = Some(Pending::Runtime(a));
                        g.set_confirm_lines(VecModel::from_slice(&[SharedString::from(message)]));
                        g.set_confirm_label("确定".into());
                        g.set_confirm_cancel("取消".into());
                        g.set_confirm_danger(false);
                        g.set_confirm_title(title.into());
                    }
                }
            }
            "command" => {
                g.set_command_result("".into());
                g.set_command_open(true);
            }
            "command-edited" => g.set_command_result("".into()),
            "command-cancel" if !g.get_command_sending() => g.set_command_open(false),
            "command-send" => self.send_command(&ui),
            "console-copied" => ui.invoke_notify("已复制".into()),
            "console-clear" => {
                self.console.clear();
                g.set_selected_log("".into());
            }
            "console-select" => {
                let line = value.parse::<usize>().ok().and_then(|i| self.console.lines().nth(i).cloned()).unwrap_or_default();
                g.set_selected_log(line.into());
            }
            "console-export" => self.export(),
            // 存档
            "reset" if self.access.can_reset && !self.busy => {
                g.set_reset_name("".into());
                g.set_reset_open(true);
            }
            "reset-cancel" => g.set_reset_open(false),
            "reset-confirm" => self.reset_world(&ui),
            "download" => self.download(&ui),
            "open-saves" => self.open_saves(),
            "rollback" if self.access.can_rollback => {
                self.navigate("room-rollback");
                self.page = Page::Rollback;
                self.epoch += 1;
                self.busy = false;
                g.set_fail("".into());
                g.set_notice("".into());
                g.set_busy("".into());
                g.set_rollback_loaded(false);
                self.rollback = None;
                self.load_rollback(&ui, "正在加载回档点", false);
            }
            // 回档
            "rollback-reload" => self.load_rollback(&ui, "正在加载回档点", false),
            "point" if !self.busy => self.ask_rollback(&ui, RollbackConfirm::Rollback(value.into())),
            "banner" if !self.busy => {
                let Some(banner) = self.rollback.as_ref().and_then(rollback::banner) else { return };
                match (value, Some(banner)) {
                    ("cancel", Some(rollback::Banner::Pending { can_cancel: true, .. } | rollback::Banner::Unfinished { can_cancel: true, .. })) => {
                        self.ask_rollback(&ui, RollbackConfirm::Cancel)
                    }
                    ("undo", Some(rollback::Banner::Done { undo: Some(undo), .. })) => self.ask_rollback(&ui, RollbackConfirm::Undo(undo)),
                    _ => {}
                }
            }
            _ => {
                self.mods_action(&ui, action, value);
            }
        }
    }

    /// Escape or Alt+Left while a page dialog is open closes the topmost one.
    fn dismiss(&mut self, ui: &AppWindow) {
        let g = pages(ui);
        if !g.get_alert_title().is_empty() {
            g.set_alert_title("".into());
        } else if !g.get_confirm_title().is_empty() {
            self.action("confirm-cancel", "");
        } else if g.get_command_open() {
            if !g.get_command_sending() {
                g.set_command_open(false);
            }
        } else if g.get_reset_open() {
            g.set_reset_open(false);
        } else if g.get_gh_open() {
            self.mods_action(ui, "gh-cancel", "");
        }
    }

    // ---------- 后台 ----------

    fn load_backend(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        let g = pages(ui);
        g.set_loading(true);
        g.set_error("".into());
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Detail(epoch, requests::detail(api.server(), &host).await));
        });
    }

    fn show_backend(&mut self, ui: &AppWindow, detail: HostDetail) {
        let Some((me, dav)) = self.me() else { return };
        let me = Me { id: &me, is_dav: dav };
        let g = pages(ui);
        g.set_title(format!("{} - 后台", detail.name).into());
        let view = permissions::can_view_members(&detail, me);
        g.set_can_view(view);
        g.set_can_manage(permissions::can_manage(&detail, me));
        self.detail = Some(detail);
        if view && self.stream.is_none() {
            self.start_stream();
        }
    }

    fn start_stream(&mut self) {
        let Some(api) = self.api.clone() else { return };
        self.console.clear();
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.stream = Some(self.runtime.spawn(async move {
            loop {
                let mut failed = false;
                let result = backend::log_stream(api.server(), &host, |event| {
                    failed |= matches!(event, LogEvent::Failed(_));
                    tx.send(Reply::Log(epoch, event)).is_ok()
                })
                .await;
                if failed {
                    return;
                }
                if let Err(error) = result {
                    tracing::warn!(error = %format!("{error:#}"), "房间日志读取失败");
                    let _ = tx.send(Reply::Log(epoch, LogEvent::Failed(error.to_string())));
                    return;
                }
                // The server closed the stream; it sends the recent log again on reconnect.
                tokio::time::sleep(Duration::from_secs(3)).await;
                if tx.send(Reply::LogReset(epoch)).is_err() {
                    return;
                }
            }
        }));
    }

    fn run_runtime(&mut self, action: RuntimeAction) {
        let Some(api) = self.api.clone() else { return };
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Runtime(epoch, action, backend::run(api.server(), &host, action).await));
        });
    }

    fn send_command(&mut self, ui: &AppWindow) {
        let g = pages(ui);
        let command = backend::normalize_command(&g.get_command_text());
        if command.is_empty() || g.get_command_sending() {
            return;
        }
        let Some(api) = self.api.clone() else { return };
        g.set_command_sending(true);
        g.set_command_result("".into());
        self.command_epoch += 1;
        let (epoch, host, tx) = (self.command_epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Command(epoch, backend::command(api.server(), &host, &command).await));
        });
    }

    fn export(&self) {
        let text: Vec<String> = self.console.lines().cloned().collect();
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = async {
                let Some(file) = rfd::AsyncFileDialog::new().set_title("导出日志").set_file_name("room-console.log").save_file().await else {
                    return Ok(false);
                };
                file.write(text.join("\n").as_bytes()).await?;
                Ok(true)
            }
            .await;
            let _ = tx.send(Reply::Exported(result));
        });
    }

    fn render_console(&mut self, ui: &AppWindow) {
        if self.console.revision() == self.shown_revision {
            return;
        }
        self.shown_revision = self.console.revision();
        let rows: Vec<LogRow> = self.console.lines().map(|l| LogRow { text: l.as_str().into(), level: backend::line_level(l) }).collect();
        self.log_rows.set_vec(rows);
        let all: Vec<&str> = self.console.lines().map(String::as_str).collect();
        pages(ui).set_all_logs(all.join("\n").into());
    }

    // ---------- 存档 ----------

    fn load_world(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        let Some((me, dav)) = self.me() else { return };
        if self.busy {
            return;
        }
        let g = pages(ui);
        self.busy = true;
        g.set_busy("正在加载房间".into());
        g.set_fail("".into());
        g.set_notice("".into());
        g.set_error("".into());
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::World(epoch, world_state(&api, &host, &me, dav).await));
        });
    }

    fn show_world(&mut self, ui: &AppWindow, detail: HostDetail, status: Option<DownloadStatus>) {
        let Some((me, dav)) = self.me() else { return };
        self.access = world::access(&detail, Me { id: &me, is_dav: dav });
        let g = pages(ui);
        g.set_loading(false);
        g.set_host_name(detail.name.as_str().into());
        g.set_can_reset(self.access.can_reset);
        g.set_can_download(self.access.can_download);
        g.set_can_rollback(self.access.can_rollback);
        self.download = status;
        self.render_download(ui);
        self.detail = Some(detail);
    }

    fn render_download(&self, ui: &AppWindow) {
        let g = pages(ui);
        match &self.download {
            Some(s) => {
                g.set_download_enabled(s.can_start());
                g.set_download_hint(s.hint().into());
                g.set_download_today(s.today_line(local_offset()).into());
            }
            None => {
                g.set_download_enabled(false);
                g.set_download_hint("".into());
                g.set_download_today("".into());
            }
        }
        g.set_has_saves(self.saves.is_some());
    }

    fn reset_world(&mut self, ui: &AppWindow) {
        let g = pages(ui);
        let Some(detail) = &self.detail else { return };
        if self.busy || g.get_reset_name() != detail.name.as_str() {
            return;
        }
        g.set_reset_open(false);
        let Some(api) = self.api.clone() else { return };
        let Some((me, dav)) = self.me() else { return };
        if !self.access.can_reset {
            g.set_fail("只有房主可以重置存档".into());
            return;
        }
        self.busy = true;
        g.set_busy("正在重置存档".into());
        g.set_fail("".into());
        g.set_notice("".into());
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = async {
                world::reset_world(api.server(), &host).await?;
                world_state(&api, &host, &me, dav).await
            }
            .await;
            let _ = tx.send(Reply::Reset(epoch, result));
        });
    }

    fn download(&mut self, ui: &AppWindow) {
        if self.busy {
            return;
        }
        let g = pages(ui);
        let Some(detail) = self.detail.clone() else {
            g.set_fail("房间信息未加载".into());
            return;
        };
        if !self.access.can_download {
            g.set_fail("只有房间成员可以下载存档".into());
            return;
        }
        let Some(api) = self.api.clone() else { return };
        self.busy = true;
        g.set_busy("正在准备下载".into());
        g.set_fail("".into());
        g.set_notice("".into());
        let (epoch, host, tx, tasks) = (self.epoch, self.host.clone(), self.tx.clone(), self.tasks.clone());
        self.runtime.spawn(async move {
            let result = prepare_download(&api, &tasks, &host, &detail).await;
            let _ = tx.send(Reply::Download(epoch, result));
        });
    }

    fn start_download(&mut self, ui: &AppWindow, host: String, host_name: String, saves: PathBuf) {
        let Some(api) = self.api.clone() else { return };
        let server = api.server().clone();
        let runtime = self.runtime.clone();
        let (task_host, task_saves, task_name) = (host.clone(), saves.clone(), host_name.clone());
        let run = self.tasks.submit(&self.runtime, world::task_title(&host_name), world::task_key(&host), move |context| {
            world::run_download(&server, &runtime, &task_host, &task_name, &task_saves, &context).map(|_| ())
        });
        self.download_run = Some((host.clone(), run));
        if self.page == Page::World && self.host == host {
            self.saves = Some(saves);
            pages(ui).set_notice("已开始下载，可以在任务列表查看进度".into());
            self.render_download(ui);
        } else {
            ui.invoke_notify("整合包已安装，开始下载房间存档".into());
        }
    }

    /// Follows the download task and the pack install it waits for.
    fn tick_downloads(&mut self, ui: &AppWindow) {
        if let Some((host, run)) = self.download_run.clone() {
            let status = self.tasks.snapshot(&run).and_then(|s| s["status"].as_u64()).unwrap_or(4);
            if status >= 2 {
                let here = self.page == Page::World && self.host == host;
                self.download_run = None;
                if here {
                    let g = pages(ui);
                    match status {
                        2 => g.set_notice("存档已下载到整合包的单人存档里".into()),
                        3 => {
                            g.set_notice("".into());
                            g.set_fail("存档下载失败，可以在任务列表查看原因".into());
                        }
                        _ => g.set_notice("".into()),
                    }
                    // Refreshes the day's downloads.
                    self.refresh_download_status();
                }
            }
        }
        let Some(waiting) = &mut self.waiting else { return };
        if waiting.checked.elapsed() < Duration::from_secs(1) {
            return;
        }
        waiting.checked = Instant::now();
        let versions = crate::local_packs::versions_dir();
        match crate::pack_install::state_in(&self.tasks, &versions, &waiting.pack, &waiting.version) {
            crate::pack_install::State::Installed => {
                let w = self.waiting.take().unwrap();
                let saves = world::saves_dir(&versions, &w.pack, &w.version);
                self.start_download(ui, w.host, w.host_name, saves);
            }
            crate::pack_install::State::Installing(_) => {}
            // The player closed the install prompt, or the install failed.
            crate::pack_install::State::Missing if !ui.get_install_prompt() => self.waiting = None,
            crate::pack_install::State::Missing => {}
        }
    }

    fn refresh_download_status(&mut self) {
        let Some(api) = self.api.clone() else { return };
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::DownloadStatus(epoch, world::download_status(api.server(), &host).await));
        });
    }

    fn open_saves(&self) {
        let Some(path) = self.saves.clone() else { return };
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(move || -> Result<()> {
                std::fs::create_dir_all(&path)?;
                #[cfg(windows)]
                std::process::Command::new("explorer.exe").arg(&path).spawn()?;
                #[cfg(target_os = "macos")]
                std::process::Command::new("open").arg(&path).spawn()?;
                #[cfg(all(not(windows), not(target_os = "macos")))]
                std::process::Command::new("xdg-open").arg(&path).spawn()?;
                Ok(())
            })
            .await
            .map_err(anyhow::Error::from)
            .and_then(|r| r);
            let _ = tx.send(Reply::Opened(result));
        });
    }

    // ---------- 回档 ----------

    fn load_rollback(&mut self, ui: &AppWindow, message: &'static str, quiet: bool) {
        if self.busy && !quiet {
            return;
        }
        let Some(api) = self.api.clone() else { return };
        let g = pages(ui);
        if !quiet {
            self.busy = true;
            g.set_busy(message.into());
            g.set_fail("".into());
            g.set_notice("".into());
        }
        self.refresh_at = None;
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = rollback::status(api.server(), &host).await.map(|s| (None, s));
            let _ = tx.send(Reply::Rollback(epoch, quiet, result));
        });
    }

    fn ask_rollback(&mut self, ui: &AppWindow, confirm: RollbackConfirm) {
        let Some(status) = &self.rollback else { return };
        let (title, lines, label) = rollback::confirm_text(status, &confirm, local_offset());
        let g = pages(ui);
        let lines: Vec<SharedString> = lines.iter().map(|l| format!("· {l}").into()).collect();
        g.set_confirm_lines(VecModel::from_slice(&lines));
        g.set_confirm_danger(confirm != RollbackConfirm::Cancel);
        g.set_confirm_label(label.into());
        g.set_confirm_cancel("返回".into());
        self.pending = Some(Pending::Rollback(confirm));
        g.set_confirm_title(title.into());
    }

    fn rollback_request(&mut self, ui: &AppWindow, point: String) {
        if self.busy {
            return;
        }
        let Some(api) = self.api.clone() else { return };
        self.busy = true;
        let g = pages(ui);
        g.set_busy("正在设置回档".into());
        g.set_fail("".into());
        self.refresh_at = None;
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = async {
                let created = rollback::request(api.server(), &host, &point).await?;
                let status = rollback::status(api.server(), &host).await?;
                Ok((Some(rollback::requested_notice(created)), status))
            }
            .await;
            let _ = tx.send(Reply::Rollback(epoch, false, result));
        });
    }

    fn rollback_cancel(&mut self, ui: &AppWindow) {
        if self.busy {
            return;
        }
        let Some(api) = self.api.clone() else { return };
        self.busy = true;
        let g = pages(ui);
        g.set_busy("正在取消回档".into());
        g.set_fail("".into());
        self.refresh_at = None;
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = async {
                rollback::cancel(api.server(), &host).await?;
                let status = rollback::status(api.server(), &host).await?;
                Ok((Some(rollback::CANCELLED_NOTICE), status))
            }
            .await;
            let _ = tx.send(Reply::Rollback(epoch, false, result));
        });
    }

    fn render_rollback(&mut self, ui: &AppWindow) {
        let Some(status) = &self.rollback else { return };
        let g = pages(ui);
        let offset = local_offset();
        g.set_rollback_loaded(true);
        match rollback::banner(status) {
            Some(banner) => {
                let view = rollback::banner_view(status, &banner, offset);
                g.set_banner_title(view.title.into());
                g.set_banner_text(view.text.into());
                g.set_banner_error(view.error);
                let (key, label, glyph, enabled) = view.button.unwrap_or(("", "", "", false));
                g.set_banner_key(key.into());
                g.set_banner_label(label.into());
                g.set_banner_glyph(glyph.into());
                g.set_banner_enabled(enabled);
                g.set_banner_visible(true);
            }
            None => g.set_banner_visible(false),
        }
        g.set_usage_line(rollback::usage_line(status).into());
        g.set_usage_fraction(if status.max_bytes > 0 { (status.used_bytes as f32 / status.max_bytes as f32).clamp(0., 1.) } else { 0. });
        let now = now_millis();
        let mut rows = Vec::new();
        for (label, points) in rollback::group_by_day(&status.points, now, offset) {
            rows.push(RollbackRow { header: true, label: label.into(), ..Default::default() });
            for p in points {
                rows.push(RollbackRow {
                    header: false,
                    label: SharedString::default(),
                    id: p.id.as_str().into(),
                    time: rollback::point_time(p.created_at, offset).into(),
                    ago: rollback::ago(p.created_at, now).into(),
                    before: p.kind == rollback::PointKind::BeforeRollback,
                    changes: rollback::changes(p).into(),
                });
            }
        }
        self.rollback_rows.set_vec(rows);
    }

    // ---------- tick ----------

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            // A dialog makes the shell ignore navigation, so it waits until they close.
            if pages(&ui).get_modal() || ui.global::<crate::Rooms>().get_modal() {
                self.pending_nav.push(action);
                continue;
            }
            ui.invoke_navigate(action.into());
        }
        let page = Page::from_screen(pages(&ui).get_screen());
        if page != self.page {
            // Back from 回档 lands on 存档 of the same room; anything else leaves the pages.
            if page == Page::World && self.page == Page::Rollback {
                self.epoch += 1;
                self.busy = false;
                self.refresh_at = None;
                let g = pages(&ui);
                g.set_busy("".into());
                g.set_fail("".into());
                g.set_notice("".into());
            } else {
                self.leave();
            }
            self.page = page;
        }
        if self.refresh_at.is_some_and(|at| Instant::now() >= at) && self.page == Page::Rollback && !self.busy {
            self.load_rollback(&ui, "", true);
        }
        self.tick_downloads(&ui);
        while let Ok(reply) = self.rx.try_recv() {
            self.reply(&ui, reply);
        }
        if self.page == Page::Backend {
            self.render_console(&ui);
        }
        self.mods_tick(&ui);
    }

    fn reply(&mut self, ui: &AppWindow, reply: Reply) {
        let g = pages(ui);
        let now = self.epoch;
        match reply {
            Reply::Detail(epoch, result) if epoch == now => {
                g.set_loading(false);
                match result {
                    Ok(detail) => self.show_backend(ui, detail),
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "房间后台加载失败");
                        g.set_error(text(&error, "无法加载房间信息").into());
                    }
                }
            }
            Reply::World(epoch, result) if epoch == now => {
                self.busy = false;
                g.set_busy("".into());
                g.set_loading(false);
                match result {
                    Ok((detail, status)) => self.show_world(ui, detail, status),
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "房间存档加载失败");
                        if self.detail.is_some() {
                            g.set_fail(text(&error, "操作失败").into());
                        } else {
                            g.set_error(text(&error, "无法加载房间信息").into());
                        }
                    }
                }
            }
            Reply::Reset(epoch, result) if epoch == now => {
                self.busy = false;
                g.set_busy("".into());
                match result {
                    Ok((detail, status)) => {
                        g.set_notice("存档已重置，下次启动房间时会生成新存档".into());
                        self.show_world(ui, detail, status);
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "重置存档失败");
                        g.set_fail(text(&error, "操作失败").into());
                    }
                }
            }
            Reply::Download(epoch, result) if epoch == now => {
                self.busy = false;
                g.set_busy("".into());
                match result {
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "存档下载准备失败");
                        g.set_fail(text(&error, "操作失败").into());
                    }
                    Ok(Prepared::Ready(status, saves)) => {
                        self.download = Some(status);
                        let name = self.detail.as_ref().map(|d| d.name.clone()).unwrap_or_default();
                        self.start_download(ui, self.host.clone(), name, saves);
                    }
                    Ok(Prepared::Installing(status)) => {
                        self.download = Some(status);
                        self.render_download(ui);
                        self.wait_for_pack();
                        g.set_notice("整合包正在下载，装好后会自动下载存档".into());
                    }
                    Ok(Prepared::Missing(status, prompt)) => {
                        self.download = Some(status);
                        self.render_download(ui);
                        self.wait_for_pack();
                        ui.invoke_pack_action("install.prompt".into(), prompt.to_string().into());
                    }
                }
            }
            Reply::DownloadStatus(epoch, result) if epoch == now => match result {
                Ok(status) => {
                    self.download = Some(status);
                    self.render_download(ui);
                }
                Err(error) => tracing::warn!(error = %format!("{error:#}"), "刷新存档下载次数失败"),
            },
            Reply::Log(epoch, event) if epoch == now => match event {
                LogEvent::Line(line) => self.console.append(&line),
                LogEvent::Failed(message) => {
                    self.stream = None;
                    self.alert(ui, "错误", &format!("读取日志错误: {message}"));
                }
            },
            Reply::LogReset(epoch) if epoch == now => self.console.clear(),
            Reply::Runtime(epoch, action, result) if epoch == now => match result {
                Ok(()) => ui.invoke_notify(action.success().into()),
                Err(error) => {
                    tracing::warn!(?action, error = %format!("{error:#}"), "房间操作失败");
                    self.alert(ui, "错误", &text(&error, action.failure()));
                }
            },
            Reply::Command(epoch, result) if epoch == self.command_epoch => {
                g.set_command_sending(false);
                match result {
                    Ok(reply) => {
                        g.set_command_result(reply.into());
                        ui.invoke_notify("命令已执行".into());
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "发送命令失败");
                        self.alert(ui, "错误", &text(&error, "发送命令失败"));
                    }
                }
            }
            Reply::Rollback(epoch, quiet, result) if epoch == now => {
                if !quiet {
                    self.busy = false;
                    g.set_busy("".into());
                }
                match result {
                    Ok((notice, status)) => {
                        if let Some(notice) = notice {
                            g.set_notice(notice.into());
                        }
                        self.refresh_at = rollback::needs_refresh(&status).then(|| Instant::now() + rollback::PENDING_REFRESH);
                        self.rollback = Some(status);
                        self.render_rollback(ui);
                    }
                    Err(error) if quiet => tracing::warn!(error = %format!("{error:#}"), host = %self.host, "自动刷新回档状态失败"),
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "回档操作失败");
                        g.set_fail(text(&error, "操作失败").into());
                    }
                }
            }
            Reply::Exported(result) => match result {
                Ok(true) => ui.invoke_notify("日志已导出".into()),
                Ok(false) => {}
                Err(error) => {
                    tracing::warn!(error = %format!("{error:#}"), "导出房间日志失败");
                    self.alert(ui, "错误", &text(&error, "导出失败"));
                }
            },
            Reply::Mods(epoch, result) if epoch == now => self.mods_loaded(ui, result),
            Reply::Cards(epoch, cards) if epoch == now => self.cards_loaded(ui, cards),
            Reply::ModIcon(url, icon) => self.icon_loaded(url, icon),
            Reply::ModsChanged(epoch, op, result) if epoch == now => self.mods_changed(ui, op, result),
            Reply::Github(epoch, reply) => self.github_reply(ui, epoch, reply),
            Reply::Opened(Err(error)) => {
                tracing::warn!(error = %format!("{error:#}"), "打开单人存档文件夹失败");
                ui.invoke_notify("打开文件夹失败".into());
            }
            _ => {}
        }
    }

    fn wait_for_pack(&mut self) {
        let Some(detail) = &self.detail else { return };
        self.waiting = Some(WaitingInstall {
            host: self.host.clone(),
            host_name: detail.name.clone(),
            pack: detail.modpack.id.clone(),
            version: detail.pack_ver.clone(),
            checked: Instant::now(),
        });
    }
}

/// `HostWorldViewModel.load`: the room, and the day's downloads for a member of a v2 room.
async fn world_state(api: &Client, host: &str, me: &str, dav: bool) -> Result<(HostDetail, Option<DownloadStatus>)> {
    let detail = requests::detail(api.server(), host).await?;
    let access = world::access(&detail, Me { id: me, is_dav: dav });
    let status = if access.is_member && detail.version == 2 { Some(world::download_status(api.server(), host).await?) } else { None };
    Ok((detail, status))
}

/// `downloadSave` up to the task: fresh status and its checks, then the room's pack on disk.
async fn prepare_download(api: &Client, tasks: &tasks::Manager, host: &str, detail: &HostDetail) -> Result<Prepared> {
    let status = world::download_status(api.server(), host).await?;
    status.check()?;
    let versions = crate::local_packs::versions_dir();
    let (pack, version) = (&detail.modpack.id, &detail.pack_ver);
    Ok(match crate::pack_install::state_in(tasks, &versions, pack, version) {
        crate::pack_install::State::Installed => Prepared::Ready(status, world::saves_dir(&versions, pack, version)),
        crate::pack_install::State::Installing(_) => Prepared::Installing(status),
        crate::pack_install::State::Missing => {
            let info: serde_json::Value = api.server().get(&format!("/modpack/{pack}/version/{version}")).await?;
            let prompt = serde_json::json!({"modpackId":pack,"version":version,"name":detail.modpack.name,"size":info["totalSize"]});
            Prepared::Missing(status, prompt)
        }
    })
}
