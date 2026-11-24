//! A4: wiring of 导入单人存档 to the `SaveImport` global. The rules are in `save_import/flow.rs`;
//! this file runs the file, server and game work off the UI thread and hands the results back
//! through a channel drained by `tick`. Each room keeps its own flow for the launcher's lifetime,
//! so a player who leaves the page while the game is open finds the same step on return.
use crate::{
    AppWindow, SaveImport, SaveMapRow, SaveRoleRow,
    api::Client,
    images,
    play::{Admitted, Sessions},
    rooms::requests,
    save_import::{
        api::{HttpImportApi, ImportApi, PrecheckVo},
        flow::{self, Flow, HostInfo, Me, Review, Step, Target, TargetSource},
        marking::{MarkingRecord, MarkingService, MarkingSummary, normalize},
        service::{self, ImportEnv, Preparation},
    },
    tasks,
};
use anyhow::{Result, anyhow};
use rdi_anvilrw::{DualIdentityKeep, NbtDocument, SaveMarkingPlayer};
use slint::{ComponentHandle, Model, SharedString, VecModel};
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet},
    path::PathBuf,
    rc::Rc,
    sync::{Arc, mpsc},
    time::SystemTime,
};
use tokio::runtime::Handle;
use uuid::Uuid;

/// A picked save that passed the first precheck.
struct Picked {
    source: PathBuf,
    players: Vec<SaveMarkingPlayer>,
    reusable: Option<MarkingRecord>,
}

enum Reply {
    Host(String, Result<HostInfo>),
    /// `None` when the folder dialog was cancelled.
    Picked(String, Result<Option<Picked>>),
    Progress(String, String),
    CopyReady(String, MarkingRecord),
    Launched(String, Result<(MarkingRecord, String)>),
    Summary(String, Result<MarkingSummary>),
    Prepared(String, Result<Preparation>),
    Reviewed(String, Result<(Review, PrecheckVo)>),
    Cleaned(String, Result<()>),
    Qq(u64, Result<(Target, Option<image::RgbaImage>)>),
    Head(String, Option<image::RgbaImage>),
}

pub struct SaveImportUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    tasks: tasks::Manager,
    packs: Rc<RefCell<crate::packs::PackUi>>,
    sessions: Sessions,
    server: String,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    /// Sign-ins so far; replies of an earlier sign-in are dropped.
    epoch: u64,
    flows: HashMap<String, Flow>,
    /// The room the page shows.
    host: String,
    heads: HashMap<String, slint::Image>,
    heads_loading: HashSet<String>,
    fallback: slint::Image,
    /// The player the QQ dialog assigns, and what it found.
    qq_for: Option<(String, Uuid)>,
    qq_found: Option<Target>,
    qq_epoch: u64,
    pending_nav: Vec<&'static str>,
    sessions_revision: Option<Vec<(String, bool, bool)>>,
}

fn global(ui: &AppWindow) -> SaveImport<'_> {
    ui.global::<SaveImport>()
}

fn hex_id(value: &str) -> bool {
    value.len() == 24 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

/// The message shown for a failed step; the full chain goes to the log.
fn shown(error: &anyhow::Error, what: &str) -> String {
    tracing::warn!(error = %format!("{error:#}"), "存档导入操作失败: {what}");
    let text = error.to_string();
    if text.trim().is_empty() { "操作失败".into() } else { text }
}

fn time_text(time: SystemTime) -> String {
    chrono::DateTime::<chrono::Local>::from(time).format("%Y-%m-%d %H:%M").to_string()
}

fn marking() -> MarkingService {
    MarkingService::new(&crate::paths::data_dir())
}

impl SaveImportUi {
    pub fn wire(
        ui: &AppWindow,
        runtime: Handle,
        packs: Rc<RefCell<crate::packs::PackUi>>,
        server: String,
    ) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let (tasks, sessions) = {
            let p = packs.borrow();
            (p.tasks(), p.play.clone())
        };
        let fallback = images::steve().map(|s| images::to_slint(&s)).unwrap_or_default();
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            tasks,
            packs,
            sessions,
            server,
            api: None,
            tx,
            rx,
            epoch: 0,
            flows: HashMap::new(),
            host: String::new(),
            heads: HashMap::new(),
            heads_loading: HashSet::new(),
            fallback,
            qq_for: None,
            qq_found: None,
            qq_epoch: 0,
            pending_nav: Vec::new(),
            sessions_revision: None,
        }));
        let weak = Rc::downgrade(&state);
        global(ui).on_action(move |action, value| {
            let Some(state) = weak.upgrade() else { return };
            match state.try_borrow_mut() {
                Ok(mut state) => state.action(action.as_str(), value.as_str()),
                Err(_) => tracing::warn!(%action, "导入存档页面操作重入，已忽略"),
            }
        });
        state
    }

    /// A new sign-in or a sign-out forgets every flow; copies on disk stay and can be reused.
    pub fn connect(&mut self, api: Option<Client>) {
        self.api = api;
        self.epoch += 1;
        self.flows.clear();
        self.host.clear();
        self.heads.clear();
        self.heads_loading.clear();
        self.qq_for = None;
        self.qq_found = None;
        // Replies of the old sign-in carry room ids that no longer have a flow, so they are dropped.
        while self.rx.try_recv().is_ok() {}
        if let Some(ui) = self.ui.upgrade() {
            let g = global(&ui);
            g.set_cleanup_open(false);
            g.set_qq_open(false);
        }
    }

    fn me(&self) -> Option<Me> {
        let api = self.api.as_ref()?;
        Some(Me { id: api.uid().ok()?, name: api.account_name.clone(), is_dav: api.is_dav })
    }

    fn flow(&mut self) -> Option<&mut Flow> {
        self.flows.get_mut(&self.host)
    }

    fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        match action {
            "open" if hex_id(value) && self.api.is_some() => self.open(value),
            "back-nav" => self.pending_nav.push("back"),
            "tasks" => self.pending_nav.push("tasks"),
            "dismiss" => {
                let g = global(&ui);
                if g.get_qq_open() {
                    self.action("qq-cancel", "");
                } else {
                    g.set_cleanup_open(false);
                }
            }
            "pick" => self.pick(),
            "role" => {
                if let (Ok(uuid), Some(flow)) = (Uuid::parse_str(value), self.flow()) {
                    flow.select_player(uuid);
                }
            }
            "mark" => self.start_marking(value == "reuse"),
            "relaunch" => self.relaunch(),
            "exited" => {
                let host = self.host.clone();
                self.read_marks(&host);
            }
            "continue" => self.continue_to_mapping(),
            "clear" | "me" => {
                let Ok(uuid) = Uuid::parse_str(value) else { return };
                let target = if action == "me" { self.me().map(|m| m.target()) } else { None };
                if action == "me" && target.is_none() {
                    return;
                }
                if let Some(flow) = self.flow()
                    && flow.busy.is_none()
                {
                    flow.set_target(uuid, target);
                }
                self.load_heads();
            }
            "keep" => {
                let Some((uuid, keep)) = value.split_once('|') else { return };
                let Ok(uuid) = Uuid::parse_str(uuid) else { return };
                let keep = if keep == "rdi" { DualIdentityKeep::Rdi } else { DualIdentityKeep::Other };
                if let Some(flow) = self.flow()
                    && flow.busy.is_none()
                {
                    flow.set_dual_keep(uuid, keep);
                }
            }
            "qq" => {
                let Ok(uuid) = Uuid::parse_str(value) else { return };
                self.qq_for = Some((self.host.clone(), uuid));
                self.qq_found = None;
                self.qq_epoch += 1;
                let g = global(&ui);
                g.set_qq_text("".into());
                g.set_qq_found(false);
                g.set_qq_searching(false);
                g.set_qq_error("".into());
                g.set_qq_open(true);
            }
            "qq-edited" => {
                let digits: String = value.chars().filter(char::is_ascii_digit).collect();
                let g = global(&ui);
                if digits != value {
                    g.set_qq_text(digits.as_str().into());
                }
                self.qq_found = None;
                g.set_qq_found(false);
                g.set_qq_error("".into());
            }
            "qq-search" => self.search_qq(&ui),
            "qq-pick" => {
                if let (Some((host, uuid)), Some(target)) = (self.qq_for.take(), self.qq_found.take())
                    && let Some(flow) = self.flows.get_mut(&host)
                {
                    flow.set_target(uuid, Some(target));
                }
                self.qq_epoch += 1;
                global(&ui).set_qq_open(false);
                self.load_heads();
            }
            "qq-cancel" => {
                self.qq_for = None;
                self.qq_found = None;
                self.qq_epoch += 1;
                global(&ui).set_qq_open(false);
            }
            "review" => self.review(),
            "back-mapping" => {
                if let Some(flow) = self.flow()
                    && flow.busy.is_none()
                {
                    flow.back_to_mapping();
                }
            }
            "confirm" => self.confirm(),
            "cleanup" => {
                if self.flow().is_some_and(|f| f.busy.is_none() && f.cleanup_record().is_some()) {
                    global(&ui).set_cleanup_open(true);
                }
            }
            "cleanup-cancel" => global(&ui).set_cleanup_open(false),
            "cleanup-confirm" => {
                global(&ui).set_cleanup_open(false);
                self.cleanup();
            }
            _ => {}
        }
        self.render(&ui);
    }

    fn open(&mut self, host: &str) {
        self.host = host.into();
        let (Some(api), Some(me)) = (self.api.clone(), self.me()) else { return };
        let flow = self.flows.entry(host.into()).or_default();
        if flow.host.is_some() || !flow.begin("正在加载房间") {
            return;
        }
        let (tx, host) = (self.tx.clone(), host.to_owned());
        self.runtime.spawn(async move {
            let result = async {
                let detail = requests::detail(api.server(), &host).await?;
                let info = HostInfo {
                    id: detail.id,
                    name: detail.name,
                    owner_id: detail.owner_id,
                    version: detail.version,
                    modloader: detail.modpack.modloader,
                };
                flow::check_host(&info, &me)?;
                Ok(info)
            }
            .await;
            let _ = tx.send(Reply::Host(host, result));
        });
    }

    fn import_api(&self) -> Option<Arc<HttpImportApi>> {
        Some(Arc::new(HttpImportApi::new(self.api.as_ref()?.server().clone())))
    }

    fn pick(&mut self) {
        let Some(api) = self.import_api() else { return };
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        if flow.host.is_none() || !flow.begin("正在检查存档") {
            return;
        }
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = async {
                let Some(folder) = rfd::AsyncFileDialog::new().set_title("选择要导入的单人存档文件夹").pick_folder().await else {
                    return Ok(None);
                };
                let source = normalize(folder.path())?;
                let level_dat = source.join("level.dat");
                let level = tokio::task::spawn_blocking(move || NbtDocument::read_level_dat(&level_dat)).await?.map_err(|error| {
                    tracing::warn!(%error, "读取level.dat失败");
                    anyhow!("无效的存档，无法读取主要数据")
                })?;
                api.precheck(&host, &flow::pick_precheck(&level)?).await?;
                let (s, h) = (source.clone(), host.clone());
                let (players, reusable) = tokio::task::spawn_blocking(move || -> Result<_> {
                    let service = marking();
                    Ok((service.players(&s)?, service.find_reusable(&s, &h)?))
                })
                .await??;
                Ok(Some(Picked { source, players, reusable }))
            }
            .await;
            let _ = tx.send(Reply::Picked(host, result));
        });
    }

    /// The room launch's checks before the deps are built, as `App::start_room`.
    fn launch_check(&mut self) -> bool {
        let error = if crate::java_runtime::state() == crate::java_runtime::State::Missing {
            Some(format!("{}，请先进入房间游玩一次安装Java后再标记", crate::java_runtime::MISSING_MESSAGE))
        } else if self.api.is_none() {
            Some("请先登录".to_owned())
        } else {
            None
        };
        match (error, self.flow()) {
            (Some(error), Some(flow)) => {
                flow.error = Some(error);
                false
            }
            (error, _) => error.is_none(),
        }
    }

    fn window_size(&self) -> (u32, u32) {
        self.ui.upgrade().map(|ui| ui.window().size()).map(|s| (s.width, s.height)).unwrap_or((854, 480))
    }

    /// 开始标记: copy the save (or reuse the earlier copy) and open it in marking mode.
    fn start_marking(&mut self, reuse: bool) {
        if !self.launch_check() {
            return;
        }
        let deps = {
            let Ok(packs) = self.packs.try_borrow() else { return };
            packs.room_deps(&self.server)
        };
        let Some(deps) = deps else { return };
        let Some(me) = self.me() else { return };
        let window = self.window_size();
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        let reused = flow.reuse_record(reuse);
        let Some(source) = flow.source.clone() else { return };
        let selected = flow.selected_player;
        if !flow.begin(if reused.is_some() { "正在准备游戏" } else { "正在复制存档" }) {
            return;
        }
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = async {
                let deps = tokio::task::spawn_blocking(deps).await?;
                let record = match reused {
                    Some(record) => record,
                    None => {
                        let saves = service::host_saves_dir(&deps, &host).await?;
                        let rdi_uuid = Uuid::parse_str(&crate::play::room::uuid_of(&me.id)?)?;
                        let (progress_tx, progress_host) = (tx.clone(), host.clone());
                        let h = host.clone();
                        let record = tokio::task::spawn_blocking(move || {
                            let mut last = u64::MAX;
                            marking().create_copy(&source, &h, &saves, rdi_uuid, selected, &|| Ok(()), &mut |done, total| {
                                let percent = done * 100 / total.max(1);
                                if percent != last {
                                    last = percent;
                                    let _ = progress_tx.send(Reply::Progress(progress_host.clone(), format!("正在复制存档 {percent}%")));
                                }
                            })
                        })
                        .await??;
                        let _ = tx.send(Reply::CopyReady(host.clone(), record.clone()));
                        record
                    }
                };
                let session = launch(&deps, &record, window).await?;
                Ok((record, session))
            }
            .await;
            let _ = tx.send(Reply::Launched(host, result));
        });
    }

    /// 重新进入游戏调整: open the same copy again.
    fn relaunch(&mut self) {
        if !self.launch_check() {
            return;
        }
        let deps = {
            let Ok(packs) = self.packs.try_borrow() else { return };
            packs.room_deps(&self.server)
        };
        let Some(deps) = deps else { return };
        let window = self.window_size();
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        let Some(record) = flow.record.clone() else { return };
        if !flow.begin("正在准备游戏") {
            return;
        }
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = async {
                let deps = tokio::task::spawn_blocking(deps).await?;
                let session = launch(&deps, &record, window).await?;
                Ok((record, session))
            }
            .await;
            let _ = tx.send(Reply::Launched(host, result));
        });
    }

    /// The marking game ended (or 我已退出游戏): read what it marked.
    fn read_marks(&mut self, host: &str) {
        let Some(flow) = self.flows.get_mut(host) else { return };
        let Some(record) = flow.record.clone() else { return };
        if !flow.begin("正在读取标记") {
            return;
        }
        let (tx, host) = (self.tx.clone(), host.to_owned());
        self.runtime.spawn_blocking(move || {
            let service = marking();
            let result = service.check_identity(&record).and_then(|()| service.summary(&record));
            let _ = tx.send(Reply::Summary(host, result));
        });
    }

    fn continue_to_mapping(&mut self) {
        let Some(api) = self.import_api() else { return };
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        if flow.busy.is_some() {
            return;
        }
        let record = match flow.mapping_record() {
            Ok(record) => record,
            Err(error) => {
                flow.error = Some(error.to_string());
                return;
            }
        };
        flow.begin("正在识别存档中的玩家");
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = service::prepare(api.as_ref(), &marking(), &record).await;
            let _ = tx.send(Reply::Prepared(host, result));
        });
    }

    fn review(&mut self) {
        let Some(api) = self.import_api() else { return };
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        if flow.busy.is_some() {
            return;
        }
        let review = match flow.review() {
            Ok(review) => review,
            Err(error) => {
                flow.error = Some(shown(&error, "检查玩家对应"));
                return;
            }
        };
        flow.begin("正在检查房间");
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = api.precheck(&host, &review.dto).await.map(|precheck| (review, precheck));
            let _ = tx.send(Reply::Reviewed(host, result));
        });
    }

    fn confirm(&mut self) {
        let Some(api) = self.import_api() else { return };
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        if flow.busy.is_some() || flow.step != Step::Confirm {
            return;
        }
        let Some(plan) = flow.confirmation.as_ref().map(|c| c.plan.clone()) else { return };
        let data = crate::paths::data_dir();
        let run = service::submit(&self.tasks, &self.runtime, api, MarkingService::new(&data), plan, ImportEnv::new(&data));
        flow.submitted(run);
    }

    fn cleanup(&mut self) {
        let host = self.host.clone();
        let Some(flow) = self.flows.get_mut(&host) else { return };
        let Some(record) = flow.cleanup_record() else { return };
        if !flow.begin("正在清理副本") {
            return;
        }
        let tx = self.tx.clone();
        self.runtime.spawn_blocking(move || {
            let _ = tx.send(Reply::Cleaned(host, marking().delete(&record)));
        });
    }

    fn search_qq(&mut self, ui: &AppWindow) {
        let g = global(ui);
        let qq = g.get_qq_text().trim().to_owned();
        let (Some(api), Some(client)) = (self.import_api(), self.api.clone()) else { return };
        if qq.is_empty() || g.get_qq_searching() || self.qq_for.is_none() {
            return;
        }
        g.set_qq_searching(true);
        g.set_qq_error("".into());
        self.qq_epoch += 1;
        let (tx, epoch) = (self.tx.clone(), self.qq_epoch);
        self.runtime.spawn(async move {
            let result = async {
                let found = api.find_by_qq(&qq).await?;
                let head = match crate::rooms_ui::player(&client, &found.id).await {
                    Ok((_, head)) => Some(head),
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "玩家头像加载失败");
                        None
                    }
                };
                Ok((Target { account_id: found.id, name: found.name, source: TargetSource::Qq }, head))
            }
            .await;
            let _ = tx.send(Reply::Qq(epoch, result));
        });
    }

    /// Fetches the heads of the accounts the mapping step shows.
    fn load_heads(&mut self) {
        let Some(api) = self.api.clone() else { return };
        let Some(flow) = self.flows.get(&self.host) else { return };
        for id in flow::target_ids(flow) {
            if self.heads.contains_key(&id) || !self.heads_loading.insert(id.clone()) {
                continue;
            }
            let (api, tx) = (api.clone(), self.tx.clone());
            self.runtime.spawn(async move {
                let head = match crate::rooms_ui::player(&api, &id).await {
                    Ok((_, head)) => Some(head),
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "玩家头像加载失败");
                        None
                    }
                };
                let _ = tx.send(Reply::Head(id, head));
            });
        }
    }

    // ---------- tick ----------

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            if ui.get_modal_open() {
                self.pending_nav.push(action);
                continue;
            }
            ui.invoke_navigate(action.into());
        }
        let mut changed = false;
        while let Ok(reply) = self.rx.try_recv() {
            self.reply(&ui, reply);
            changed = true;
        }
        changed |= self.watch_sessions();
        if changed {
            self.render(&ui);
        }
    }

    /// Notices the end of a marking game and reads its marks; also refreshes the game status line.
    fn watch_sessions(&mut self) -> bool {
        let watched: Vec<(String, String)> = self.flows.iter().filter_map(|(h, f)| f.session.clone().map(|s| (h.clone(), s))).collect();
        if watched.is_empty() {
            return std::mem::take(&mut self.sessions_revision).is_some();
        }
        let views = self.sessions.views();
        let mut states = vec![];
        for (host, session) in watched {
            let view = views.iter().find(|v| v.id == session);
            let (preparing, running) = view.map(|v| (v.preparing, v.running)).unwrap_or((false, false));
            states.push((host.clone(), preparing, running));
            if !preparing && !running {
                if let Some(message) = view.and_then(|v| v.exit_message.clone()) {
                    tracing::info!(%message, "标记模式游戏已结束");
                }
                let flow = self.flows.get_mut(&host).expect("a watched flow");
                if flow.busy.is_none() {
                    flow.session = None;
                    self.read_marks(&host);
                }
            }
        }
        let changed = self.sessions_revision.as_ref() != Some(&states);
        self.sessions_revision = Some(states);
        changed
    }

    fn reply(&mut self, ui: &AppWindow, reply: Reply) {
        let me = self.me();
        let (host, result): (String, Result<()>) = match reply {
            Reply::Host(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let result = result.map(|info| flow.host_loaded(info));
                (host, result)
            }
            Reply::Picked(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let result = result.map(|picked| {
                    if let Some(p) = picked {
                        flow.picked(p.source, p.players, p.reusable);
                    }
                });
                (host, result)
            }
            Reply::Progress(host, message) => {
                if let Some(flow) = self.flows.get_mut(&host)
                    && flow.busy.is_some()
                {
                    flow.busy = Some(message);
                }
                return;
            }
            Reply::CopyReady(host, record) => {
                if let Some(flow) = self.flows.get_mut(&host) {
                    flow.copy_ready(record);
                }
                return;
            }
            Reply::Launched(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let result = result.map(|(record, session)| flow.marking_started(record, session));
                (host, result)
            }
            Reply::Summary(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let result = result.map(|summary| flow.exited(summary));
                (host, result)
            }
            Reply::Prepared(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let Some(me) = me else { return };
                let result = result.map(|preparation| flow.mapped(preparation, &me));
                if host == self.host {
                    self.load_heads();
                }
                (host, result)
            }
            Reply::Reviewed(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let result = result.and_then(|(review, precheck)| flow.reviewed(review, precheck));
                (host, result)
            }
            Reply::Cleaned(host, result) => {
                let Some(flow) = self.flows.get_mut(&host) else { return };
                let result = result.map(|()| flow.cleaned());
                (host, result)
            }
            Reply::Qq(epoch, result) => {
                if epoch != self.qq_epoch {
                    return;
                }
                let g = global(ui);
                g.set_qq_searching(false);
                match result {
                    Ok((target, head)) => {
                        let image = head.map(|h| images::to_slint(&h)).unwrap_or_else(|| self.fallback.clone());
                        self.heads.insert(target.account_id.clone(), image.clone());
                        g.set_qq_name(target.name.as_str().into());
                        g.set_qq_head(image);
                        g.set_qq_found(true);
                        self.qq_found = Some(target);
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "按QQ号查找玩家失败");
                        let text = error.to_string();
                        g.set_qq_error(if text.trim().is_empty() { "查找失败".into() } else { text.into() });
                    }
                }
                return;
            }
            Reply::Head(id, head) => {
                self.heads_loading.remove(&id);
                self.heads.insert(id, head.map(|h| images::to_slint(&h)).unwrap_or_else(|| self.fallback.clone()));
                return;
            }
        };
        if let Some(flow) = self.flows.get_mut(&host) {
            flow.end(result.err().map(|error| shown(&error, "存档导入")));
        }
    }

    // ---------- render ----------

    fn render(&self, ui: &AppWindow) {
        let g = global(ui);
        let Some(flow) = self.flows.get(&self.host) else { return };
        let busy = flow.busy.clone().unwrap_or_default();
        g.set_title(match &flow.host {
            Some(host) => format!("导入单人存档 · {}", host.name).into(),
            None => "导入单人存档".into(),
        });
        g.set_loading(flow.host.is_none() && flow.error.is_none() && flow.busy.is_some());
        g.set_busy(busy.into());
        g.set_error(flow.error.clone().unwrap_or_default().into());
        g.set_can_pick(flow.host.is_some());
        g.set_step(match flow.step {
            Step::PickSave => 0,
            Step::SelectPlayer => 1,
            Step::Marking => 2,
            Step::Summary => 3,
            Step::Mapping => 4,
            Step::Confirm => 5,
            Step::Submitted => 6,
        });
        g.set_source(flow.source.as_ref().map(|s| s.display().to_string()).unwrap_or_default().into());
        g.set_fabric_note(flow.host.as_ref().is_some_and(HostInfo::is_fabric));
        set_rows(g.get_roles(), &flow.marking_players.iter().map(|p| role_row(p, flow.selected_player)).collect::<Vec<_>>(), |rows| g.set_roles(rows));
        g.set_reuse_line(
            flow.reusable
                .as_ref()
                .map(|r| {
                    let name = r.selected_player_name.clone().unwrap_or_else(|| r.launch_uuid.chars().take(8).collect());
                    format!("上次标记角色：{name}。继续标记会使用该角色；更换角色会创建新副本，保留旧副本及标记。")
                })
                .unwrap_or_default()
                .into(),
        );
        g.set_marking_status(self.marking_status(flow).into());
        let summary = flow.summary.as_ref();
        g.set_summary_empty(summary.is_none_or(|s| s.marked.is_empty()));
        g.set_summary_headline(
            summary.map(|s| format!("已标记{}个区块，其中{}个在存档中已经生成。", s.marked.len(), s.present_count)).unwrap_or_default().into(),
        );
        let mut lines: Vec<SharedString> = vec![];
        if let Some(s) = summary.filter(|s| !s.marked.is_empty()) {
            lines.extend(s.marked_by_dimension.iter().map(|(d, n)| format!("{}：{n}个区块", flow::dimension_name(d)).into()));
            if !s.players_outside.is_empty() {
                lines.push(format!("有{}名玩家最后所在的位置不在保留的区块内，进入房间后可能位于重新生成的地形中。", s.players_outside.len()).into());
            }
        }
        g.set_summary_lines(Rc::new(VecModel::from(lines)).into());
        let rows: Vec<SaveMapRow> = flow.rows.iter().map(|r| self.map_row(flow, r)).collect();
        g.set_rows(Rc::new(VecModel::from(rows)).into());
        let confirm: Vec<SharedString> = flow.confirmation.iter().flat_map(|c| c.messages.iter().map(|m| m.as_str().into())).collect();
        g.set_confirm_lines(Rc::new(VecModel::from(confirm)).into());
        g.set_has_run(flow.run_id.is_some());
    }

    fn marking_status(&self, flow: &Flow) -> String {
        let Some(session) = &flow.session else { return String::new() };
        match self.sessions.views().into_iter().find(|v| &v.id == session) {
            Some(v) if v.preparing => "正在启动游戏…".into(),
            Some(v) if v.running => "游戏已启动，标记完成后保存并退出游戏。".into(),
            _ => String::new(),
        }
    }

    fn map_row(&self, flow: &Flow, row: &flow::Row) -> SaveMapRow {
        let mut details = vec![];
        if let Some(time) = row.player.last_played {
            details.push(format!("最后游玩{}", time_text(time)));
        }
        if let Some(hours) = flow::play_time_text(row.player.play_time_ticks) {
            details.push(format!("游戏时长{hours}"));
        }
        let dual = flow.duals.iter().find(|d| d.other == row.player.uuid);
        let keep_rdi = dual.is_some_and(|d| flow.dual_choices.get(&d.other).copied().unwrap_or(d.default_keep) == DualIdentityKeep::Rdi);
        let label = |base: &str, ticks: Option<i64>| match flow::play_time_text(ticks) {
            Some(hours) => format!("{base}（{hours}）"),
            None => base.to_owned(),
        };
        let target = row.target.as_ref();
        SaveMapRow {
            uuid: row.player.uuid.to_string().into(),
            name: row.display_name.as_str().into(),
            badge: row.badge.into(),
            owner: row.player.is_singleplayer_host,
            details: details.join("，").into(),
            read_only: row.read_only,
            missing: row.account_missing,
            has_target: target.is_some(),
            target_name: target.map(|t| t.name.as_str()).unwrap_or_default().into(),
            target_head: target.and_then(|t| self.heads.get(&t.account_id).cloned()).unwrap_or_else(|| self.fallback.clone()),
            msid: target.is_some_and(|t| t.source == TargetSource::MsidMatch),
            dual: dual.is_some(),
            keep_rdi,
            rdi_label: label("RDI账号的数据", dual.and_then(|d| d.rdi_play_time_ticks)).into(),
            other_label: label("这份数据", dual.and_then(|d| d.other_play_time_ticks)).into(),
        }
    }
}

/// Opens `record` in marking mode and returns the game session.
async fn launch(deps: &crate::play::room::RoomDeps, record: &MarkingRecord, window: (u32, u32)) -> Result<String> {
    match service::marking_launch(deps, &marking(), record, window).await? {
        Admitted::Started(session) => Ok(session),
        Admitted::Duplicate => Err(anyhow!("该房间的整合包已在运行，请先退出游戏再标记区块")),
    }
}

fn role_row(role: &SaveMarkingPlayer, selected: Option<Uuid>) -> SaveRoleRow {
    let name = role.display_name();
    let name = if role.player.is_singleplayer_host && name != "单机存档主人" { format!("{name}（单机存档主人）") } else { name };
    let position = match &role.position {
        Some(p) => format!("{} · {}, {}, {}", flow::dimension_name(&p.dimension), p.x.floor() as i64, p.y.floor() as i64, p.z.floor() as i64),
        None => "最后位置未知".into(),
    };
    SaveRoleRow {
        uuid: role.player.uuid.to_string().into(),
        name: name.into(),
        id_line: format!("角色编号：{}", &role.player.uuid.to_string()[..8]).into(),
        position: position.into(),
        saved: role.player.last_played.map(|t| format!("最近保存：{}", time_text(t))).unwrap_or_default().into(),
        selected: Some(role.player.uuid) == selected,
    }
}

/// Replaces the rows only when they changed, so a radio click does not rebuild the list.
fn set_rows(current: slint::ModelRc<SaveRoleRow>, rows: &[SaveRoleRow], set: impl FnOnce(slint::ModelRc<SaveRoleRow>)) {
    if current.row_count() == rows.len() && current.iter().zip(rows).all(|(a, b)| &a == b) {
        return;
    }
    set(Rc::new(VecModel::from(rows.to_vec())).into());
}

