use crate::{
    AppWindow, HostListRow, NameInitial, TileData,
    anim::Spring,
    api::{Client, model::*},
    config::Config,
    glass,
    hosts::{self, Hosts},
    images::{self, HeadCache},
};
use anyhow::Result;
use rdi_slint_proto::scene::player::{Appearance, Skin};
use slint::{Color, ComponentHandle, Model, ModelRc, VecModel};
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet},
    num::NonZeroUsize,
    rc::Rc,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::{Duration, Instant},
};
use tokio::task::JoinHandle;

const ONLINE_POLL_INTERVAL: Duration = Duration::from_secs(30);

struct Loaded {
    api: Client,
    player: PlayerInfo,
    head: image::RgbaImage,
    mine: Vec<HostBrief>,
    packs: Vec<crate::packs::InstalledPack>,
}
struct Art {
    icon: Option<image::RgbaImage>,
    owner: Option<image::RgbaImage>,
    heads: Vec<image::RgbaImage>,
}
#[derive(Clone)]
struct CachedArt {
    icon: slint::Image,
    has_icon: bool,
    owner: slint::Image,
    heads: ModelRc<slint::Image>,
}
enum Event {
    Loaded(u64, Result<Loaded>),
    Started(Result<serde_json::Value>),
    /// J4c: a room's pack is not ready; `Ok(run)` is a running install, `Err(prompt)` the dialog data.
    NeedPack(Result<String, serde_json::Value>),
    Appearance(u64, Result<Appearance>),
    CrowdSkin(u64, String, Option<Skin>),
    OnlineSkinsFinished(u64),
    Page(u64, Result<Vec<HostBrief>>),
    Online(u64, Result<Vec<String>>),
    Art(u64, String, Art),
    /// U4c1: `/host/my` again after a room was created, deleted or left.
    Mine(u64, Result<Vec<HostBrief>>),
}
#[derive(Default)]
pub struct Interaction {
    pub(crate) hover: i32,
    pub(crate) pressed: i32,
    pub(crate) hover_spring: Spring,
    pub(crate) press_spring: Spring,
}
impl Interaction {
    fn new() -> Self {
        Self {
            hover: -1,
            pressed: -1,
            ..Default::default()
        }
    }
}
struct Entry {
    name: String,
    detail: String,
    version: String,
    playable: bool,
    initial: NameInitial,
    art: ArtSource,
    status: i32,
}
#[derive(Clone)]
struct ArtSource {
    id: String,
    url: Option<String>,
    owner: Option<String>,
    heads: Vec<String>,
}
#[derive(Default)]
struct OnlineCrowd {
    ids: Vec<String>,
    checked_skins: HashSet<String>,
    changed: bool,
}
impl OnlineCrowd {
    fn update(&mut self, mut ids: Vec<String>) -> bool {
        ids.sort_unstable();
        ids.dedup();
        if self.ids == ids {
            return false;
        }
        self.checked_skins.retain(|id| ids.binary_search(id).is_ok());
        self.ids = ids;
        self.changed = true;
        true
    }
    fn take_roster(&mut self) -> Option<Vec<String>> {
        std::mem::take(&mut self.changed).then(|| self.ids.clone())
    }
    fn record_skin(&mut self, id: &str) -> bool {
        self.ids
            .binary_search_by(|online| online.as_str().cmp(id))
            .is_ok()
            && self.checked_skins.insert(id.to_owned())
    }
    fn unchecked_ids(&self) -> Vec<String> {
        self.ids
            .iter()
            .filter(|id| !self.checked_skins.contains(*id))
            .cloned()
            .collect()
    }
}
impl ArtSource {
    fn key(&self, size: u32) -> String {
        format!(
            "{}|{}|{size}|{}|{}",
            self.id,
            self.url.as_deref().unwrap_or(""),
            self.owner.as_deref().unwrap_or(""),
            self.heads.join(",")
        )
    }
}
impl Entry {
    fn tile(&self, art: Option<CachedArt>, fallback: &slint::Image) -> TileData {
        let (icon, has_icon, owner, heads) = art
            .map(|a| (a.icon, a.has_icon, a.owner, a.heads))
            .unwrap_or_else(|| {
                // No placeholder heads: a fresh model per refresh would make every row compare unequal.
                (
                    slint::Image::default(),
                    false,
                    fallback.clone(),
                    ModelRc::default(),
                )
            });
        TileData {
            id: self.art.id.clone().into(),
            name: self.name.clone().into(),
            initial: self.initial.clone(),
            detail: self.detail.clone().into(),
            version: self.version.clone().into(),
            playable: self.playable,
            icon,
            has_icon,
            owner,
            has_owner: self.art.owner.is_some(),
            heads,
            dimmed: false,
            status: self.status,
        }
    }
}

fn host_placeholder(room_name: &str, pack_name: &str) -> NameInitial {
    name_placeholder(if pack_name.trim().is_empty() {
        room_name
    } else {
        pack_name
    })
}

pub(crate) fn name_placeholder(name: &str) -> NameInitial {
    let initial = name
        .chars()
        .find(|character| character.is_alphanumeric())
        .map(|character| character.to_uppercase().collect::<String>().into())
        .unwrap_or_else(|| "?".into());

    // Match Compose's Java String.hashCode and HSL(45% saturation, 50% lightness).
    let hash = name.encode_utf16().fold(0_i32, |hash, unit| {
        hash.wrapping_mul(31).wrapping_add(i32::from(unit))
    });
    let hue = hash.rem_euclid(360) as f32;
    NameInitial {
        text: initial,
        background: Color::from_hsva(hue, 0.45 / 0.725, 0.725, 1.0),
    }
}

pub struct App {
    ui: slint::Weak<AppWindow>,
    runtime: tokio::runtime::Handle,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
    config: Config,
    hosts: Hosts,
    packs: Vec<crate::packs::InstalledPack>,
    api: Option<Client>,
    /// The signed-in session, also while the menu is still loading and `api` is empty.
    session: Option<Client>,
    play_revision: u64,
    login_ui: Rc<RefCell<crate::login::LoginUi>>,
    pack_ui: Rc<RefCell<crate::packs::PackUi>>,
    plaza: Rc<RefCell<crate::plaza::Plaza>>,
    rooms: Rc<RefCell<crate::rooms_ui::RoomsUi>>,
    room_pages: Rc<RefCell<crate::room_pages_ui::RoomPagesUi>>,
    account_ui: Rc<RefCell<crate::account_ui::AccountUi>>,
    mail_ui: Rc<RefCell<crate::mail_ui::MailUi>>,
    settings_ui: Rc<RefCell<crate::settings_ui::SettingsUi>>,
    rdipack2_ui: Rc<RefCell<crate::rdipack2_ui::Rdipack2Ui>>,
    heads: HeadCache,
    cache: lru::LruCache<String, CachedArt>,
    jobs: HashMap<String, JoinHandle<()>>,
    page_job: Option<JoinHandle<()>>,
    login_job: Option<JoinHandle<()>>,
    poll_job: Option<JoinHandle<()>>,
    next_online_poll: Option<Instant>,
    appearance_job: Option<JoinHandle<()>>,
    player_appearance: Option<Appearance>,
    crowd_job: Option<JoinHandle<()>>,
    crowd_skins: Vec<(String, Skin)>,
    online_crowd: OnlineCrowd,
    epoch: u64,
    paused: Arc<AtomicBool>,
    refresh_pending: bool,
    fallback: slint::Image,
    /// Kept for the app's lifetime and updated row by row: replacing a model recreates every tile,
    /// and femtovg then uploads every icon again (in-memory images have no shared texture cache).
    tiles: [Rc<VecModel<TileData>>; 2],
    host_list_rows: Rc<VecModel<HostListRow>>,
    pub interactions: [Interaction; 2],
    pills: [Spring; 5],
    pivots: [[f32; 2]; 5],
    selected: usize,
    last_scale: f32,
}
impl App {
    pub fn new(
        ui: &AppWindow,
        config: Config,
        runtime: tokio::runtime::Handle,
        paused: Arc<AtomicBool>,
    ) -> Result<Rc<RefCell<Self>>> {
        let (tx, rx) = mpsc::channel();
        let fallback = images::to_slint(&images::steve()?);
        ui.set_player_head(fallback.clone());
        let tiles = [Rc::new(VecModel::default()), Rc::new(VecModel::default())];
        ui.set_hosts(ModelRc::from(tiles[0].clone()));
        ui.set_modpacks(ModelRc::from(tiles[1].clone()));
        let host_list_rows = Rc::new(VecModel::default());
        ui.set_all_hosts(ModelRc::from(host_list_rows.clone()));
        let pack_ui = crate::packs::PackUi::wire(ui, runtime.clone());
        // J5b B5: games this launcher started before a crash come back into the game list.
        {
            let play = pack_ui.borrow().play.clone();
            play.set_journal(crate::play::recovery::path());
            let idle = runtime.clone();
            play.set_rooms_idle(std::sync::Arc::new(move || {
                idle.spawn(crate::route::link::stop());
            }));
            play.recover();
        }
        // J5 D5: look for Java 25 once, in the background, as the updater does.
        crate::java_runtime::start(&runtime);
        let plaza = crate::plaza::Plaza::wire(ui, runtime.clone(), pack_ui.borrow().tasks());
        let login_ui = crate::login::LoginUi::wire(ui, runtime.clone(), config.server.clone());
        let rooms = crate::rooms_ui::RoomsUi::wire(ui, runtime.clone());
        let room_pages = crate::room_pages_ui::RoomPagesUi::wire(ui, runtime.clone(), pack_ui.borrow().tasks());
        let account_ui = crate::account_ui::AccountUi::wire(ui, runtime.clone(), config.server.clone());
        let mail_ui = crate::mail_ui::MailUi::wire(ui, runtime.clone());
        let rdipack2_ui = crate::rdipack2_ui::Rdipack2Ui::wire(ui, runtime.clone(), pack_ui.borrow().tasks(), pack_ui.borrow().play.clone());
        let settings_ui = crate::settings_ui::SettingsUi::wire(ui, runtime.clone(), pack_ui.borrow().tasks(), pack_ui.borrow().play.clone(), config.server.clone());
        let app = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            tx,
            rx,
            config,
            hosts: Hosts::default(),
            packs: vec![],
            api: None,
            session: None,
            pack_ui,
            plaza,
            rooms,
            room_pages,
            account_ui,
            mail_ui,
            settings_ui,
            rdipack2_ui,
            login_ui,
            play_revision: 0,
            heads: HeadCache::default(),
            cache: lru::LruCache::new(NonZeroUsize::new(60).unwrap()),
            jobs: HashMap::new(),
            page_job: None,
            login_job: None,
            poll_job: None,
            next_online_poll: None,
            appearance_job: None,
            player_appearance: None,
            crowd_job: None,
            crowd_skins: Vec::new(),
            online_crowd: OnlineCrowd::default(),
            epoch: 0,
            paused,
            refresh_pending: false,
            fallback,
            tiles,
            host_list_rows,
            interactions: [Interaction::new(), Interaction::new()],
            pills: [Spring::default(); 5],
            pivots: [[0.0; 2]; 5],
            selected: 0,
            last_scale: 0.0,
        }));
        Self::wire(&app, ui);
        if !app.borrow().config.ui_proof && !app.borrow().config.gallery {
            app.borrow_mut().login();
        } else {
            ui.set_loading(false);
        }
        Ok(app)
    }
    fn wire(app: &Rc<RefCell<Self>>, ui: &AppWindow) {
        let weak = Rc::downgrade(app);
        ui.on_retry(move || {
            if let Some(a) = weak.upgrade() {
                a.borrow_mut().login();
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_retry_page(move || {
            if let Some(a) = weak.upgrade() {
                let mut a = a.borrow_mut();
                if let Some(ui) = a.ui.upgrade() {
                    ui.set_page_error("".into());
                }
                a.load_page();
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_select_tab(move |tab| {
            if let Some(a) = weak.upgrade() {
                let mut a = a.borrow_mut();
                a.selected = tab as usize;
                if let Some(ui) = a.ui.upgrade() {
                    ui.set_selected(tab);
                }
                a.refresh();
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_load_host_page(move || {
            if let Some(a) = weak.upgrade()
                && let Ok(mut a) = a.try_borrow_mut()
            {
                a.load_page();
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_scroll_changed(move || {
            if let Some(a) = weak.upgrade()
                && let Ok(mut a) = a.try_borrow_mut()
            {
                a.refresh();
                if let Some(ui) = a.ui.upgrade() {
                    ui.set_backdrop_revision(ui.get_backdrop_revision().wrapping_add(1) & i32::MAX);
                    ui.window().request_redraw();
                }
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_tile_hover(move |tab, index, on| {
            if let Some(a) = weak.upgrade() {
                let mut a = a.borrow_mut();
                let i = &mut a.interactions[tab as usize];
                if on {
                    i.hover = index;
                    i.hover_spring.value = 0.0;
                    i.hover_spring.target(1.0, 1500.0, 1.0);
                } else if i.hover == index {
                    i.hover_spring.target(0.0, 1500.0, 1.0);
                }
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_tile_press(move |tab, index, on| {
            if let Some(a) = weak.upgrade() {
                let mut a = a.borrow_mut();
                let i = &mut a.interactions[tab as usize];
                if on {
                    i.pressed = index;
                    i.press_spring.target(1.0, 10000.0, 1.0);
                } else if i.pressed == index {
                    i.press_spring.target(0.0, 400.0, 0.55);
                }
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_pill_press(move |index, on, x, y| {
            if let Some(a) = weak.upgrade() {
                let mut a = a.borrow_mut();
                let index = index as usize;
                a.pivots[index] = [x, y];
                a.pills[index].target(
                    if on { 1.0 } else { 0.0 },
                    if on { 10000.0 } else { 400.0 },
                    if on { 1.0 } else { 0.55 },
                );
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_tile_action(move |tab, index, right| {
            if let Some(a) = weak.upgrade()
                && let Some(entry) = a.borrow().entries(tab as usize).get(index as usize)
            {
                let action = if right { "detail" } else { "play" };
                let id = &entry.art.id;
                tracing::info!(%id, action, "Room tile action");
                if tab == 1 {
                    let version = a.borrow().pack_version(id);
                    a.borrow().pack_ui.borrow_mut().open_pack(id, &entry.name, &version, right);
                } else if tab == 0 && !right {
                    a.borrow().play_host(id);
                } else if tab == 0 && let Some(ui) = a.borrow().ui.upgrade() {
                    ui.global::<crate::Rooms>().invoke_action("open".into(), id.as_str().into());
                } else if let Some(ui) = a.borrow().ui.upgrade() {
                    ui.invoke_stub(if tab == 0 { "房间详情".into() } else { "整合包详情".into() });
                }
            }
        });
        let stop_app = Rc::downgrade(app);
        ui.on_stop_games(move || {
            if let Some(app) = stop_app.upgrade() {
                let app = app.borrow();
                app.pack_ui.borrow().play.stop_all(true);
            }
        });
        let duplicate_app = Rc::downgrade(app);
        ui.on_confirm_duplicate(move || {
            if let Some(app) = duplicate_app.upgrade() {
                let app = app.borrow();
                if let (Some(api), Some(ui)) = (app.api.clone(), app.ui.upgrade()) {
                    let id = ui.get_duplicate_host().to_string();
                    ui.set_duplicate_host("".into());
                    if id.starts_with("pack:") {
                        app.pack_ui.borrow_mut().action("play.duplicate", "");
                        return;
                    }
                    let _ = api;
                    app.start_room(&ui, &id, true);
                }
            }
        });
        ui.on_stub(|name| tracing::info!(action=%name,"Prototype action"));
        let weak = Rc::downgrade(app);
        ui.on_logout(move || {
            if let Some(a) = weak.upgrade() {
                a.borrow_mut().logout();
            }
        });
    }
    fn player_enabled(&self) -> bool {
        let c = &self.config;
        !c.no_scene
            && !c.no_glass
            && c.renderer == "femtovg-wgpu"
            && !c.scene.p1_only
            && !c.scene.p2_only
            && !c.scene.p3_only
            && !c.scene.pipelines_only
            && !c.scene.steve_only
    }
    fn crowd_enabled(&self) -> bool {
        self.player_enabled() && !self.config.scene.no_crowd
    }
    /// Terminal scene state: cancels pending downloads and discards anything not yet uploaded.
    pub fn stop_player_appearance(&mut self) {
        for job in [&mut self.appearance_job, &mut self.crowd_job, &mut self.poll_job] {
            if let Some(job) = job.take() {
                job.abort();
            }
        }
        self.player_appearance = None;
        self.crowd_skins.clear();
        self.online_crowd.update(Vec::new());
        self.config.no_scene = true;
    }
    pub fn take_player_appearance(&mut self) -> Option<Appearance> {
        self.player_appearance.take()
    }
    pub fn take_crowd_skins(&mut self) -> Vec<(String, Skin)> {
        std::mem::take(&mut self.crowd_skins)
    }
    pub fn take_online_players(&mut self) -> Option<Vec<String>> {
        self.online_crowd.take_roster()
    }
    fn poll_online_players(&mut self) {
        if !self.crowd_enabled()
            || self.poll_job.is_some()
            || self.next_online_poll.is_some_and(|deadline| Instant::now() < deadline)
        {
            return;
        }
        if let Some(api) = self.api.clone() {
            let tx = self.tx.clone();
            let epoch = self.epoch;
            self.poll_job = Some(self.runtime.spawn(async move {
                let result = api.get("/host/online-player-ids").await;
                let _ = tx.send(Event::Online(epoch, result));
            }));
        }
    }
    fn refresh_online_crowd(&mut self, ids: Vec<String>) {
        if self.online_crowd.update(ids) {
            self.crowd_skins
                .retain(|(id, _)| self.online_crowd.ids.binary_search(id).is_ok());
            if let Some(ui) = self.ui.upgrade() {
                ui.window().request_redraw();
            }
        }
        if self.crowd_job.is_some() {
            return;
        }
        let ids = self.online_crowd.unchecked_ids();
        if ids.is_empty() {
            return;
        }
        if let Some(api) = self.api.clone() {
            let tx = self.tx.clone();
            let epoch = self.epoch;
            self.crowd_job = Some(self.runtime.spawn(async move {
                images::online_crowd(&api, ids, |id, skin| {
                    let _ = tx.send(Event::CrowdSkin(epoch, id, skin));
                })
                .await;
                let _ = tx.send(Event::OnlineSkinsFinished(epoch));
            }));
        }
    }
    fn login(&mut self) {
        self.api = None;
        self.session = None;
        self.pack_ui.borrow_mut().connect(None);
        self.plaza.borrow_mut().connect(None);
        self.rooms.borrow_mut().connect(None);
        self.room_pages.borrow_mut().connect(None);
        self.account_ui.borrow_mut().connect(None);
        self.mail_ui.borrow_mut().connect(None);
        self.settings_ui.borrow_mut().connect(None);
        self.rdipack2_ui.borrow_mut().connect(None);
        self.login_ui.borrow_mut().start();
    }
    /// Games and tasks belong to the signed-in account, so logging out waits for them.
    fn logout(&mut self) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        if ui.get_game_running()
            || self.pack_ui.borrow().has_running_tasks()
            || ui.get_play_busy()
            || ui.get_loading()
        {
            ui.set_action_error("游戏或任务正在运行，结束后才能退出登录".into());
            return;
        }
        let Some(session) = self.session.take() else {
            return;
        };
        self.stop_jobs();
        self.api = None;
        self.hosts = Hosts::default();
        self.host_list_rows.clear();
        ui.set_page_error("".into());
        ui.set_loading_more(false);
        self.packs.clear();
        self.cache.clear();
        for model in &self.tiles {
            model.clear();
        }
        ui.set_options_dirty(false);
        ui.invoke_navigate("home".into());
        self.pack_ui.borrow_mut().connect(None);
        self.plaza.borrow_mut().connect(None);
        self.rooms.borrow_mut().connect(None);
        self.room_pages.borrow_mut().connect(None);
        self.account_ui.borrow_mut().connect(None);
        self.mail_ui.borrow_mut().connect(None);
        self.settings_ui.borrow_mut().connect(None);
        self.rdipack2_ui.borrow_mut().connect(None);
        ui.set_player_name("未登录".into());
        ui.set_player_head(self.fallback.clone());
        self.login_ui.borrow_mut().sign_out(session);
    }
    fn stop_jobs(&mut self) {
        self.epoch += 1;
        self.next_online_poll = None;
        self.online_crowd.update(Vec::new());
        self.crowd_skins.clear();
        for (_, j) in self.jobs.drain() {
            j.abort();
        }
        for job in [
            &mut self.login_job,
            &mut self.page_job,
            &mut self.poll_job,
            &mut self.appearance_job,
            &mut self.crowd_job,
        ] {
            if let Some(j) = job.take() {
                j.abort();
            }
        }
    }
    fn load_menu(&mut self, api: Client) {
        self.stop_jobs();
        self.session = Some(api.clone());
        self.plaza.borrow_mut().connect(Some(api.clone()));
        self.rooms.borrow_mut().connect(Some(api.clone()));
        self.room_pages.borrow_mut().connect(Some(api.clone()));
        self.account_ui.borrow_mut().connect(Some(api.clone()));
        self.mail_ui.borrow_mut().connect(Some(api.clone()));
        self.settings_ui.borrow_mut().connect(Some(api.clone()));
        self.rdipack2_ui.borrow_mut().connect(Some(api.clone()));
        self.player_appearance = if self.player_enabled() && !self.crowd_enabled() {
            match Appearance::steve() {
                Ok(appearance) => Some(appearance),
                Err(error) => {
                    tracing::error!(error=%format!("{error:#}"),"Account player fallback decode failed");
                    None
                }
            }
        } else {
            None
        };
        self.hosts = Hosts::default();
        self.packs.clear();
        self.pack_ui.borrow_mut().connect(None);
        self.api = None;
        self.cache.clear();
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        ui.set_loading(true);
        ui.set_error("".into());
        ui.set_page_error("".into());
        ui.set_loading_more(false);
        self.host_list_rows.clear();
        for model in &self.tiles {
            model.clear();
        }
        let tx = self.tx.clone();
        let epoch = self.epoch;
        let heads = self.heads.clone();
        self.login_job = Some(self.runtime.spawn(async move {
            let result = async {
                let uid = api.uid()?;
                let player: PlayerInfo = api.get(&format!("/player/{uid}/info")).await?;
                let mine = api.get("/host/my").await?;
                let packs = Vec::new();
                let head = match heads.get(&api, &uid).await {
                    Ok(h) => h,
                    Err(e) => {
                        tracing::warn!(error=?e,"Account head fallback");
                        images::steve()?
                    }
                };
                Ok(Loaded {
                    api,
                    player,
                    head,
                    mine,
                    packs,
                })
            }
            .await;
            let _ = tx.send(Event::Loaded(epoch, result));
        }));
    }
    fn pack_version(&self, id: &str) -> String {
        self.packs
            .iter()
            .find(|p| p.id == id)
            .map(|p| p.version.clone())
            .unwrap_or_default()
    }
    fn entries(&self, tab: usize) -> Vec<Entry> {
        if tab == 0 {
            self.hosts
                .shown(false)
                .into_iter()
                .cloned()
                .map(|h| Entry {
                    initial: host_placeholder(&h.name, &h.modpack_name),
                    name: h.name,
                    detail: h.modpack_name,
                    version: h.pack_ver.unwrap_or_default(),
                    playable: h.playable,
                    art: ArtSource {
                        id: h.id,
                        url: h.icon_url,
                        owner: h.owner_id,
                        heads: h.online_player_ids,
                    },
                    status: match h.status {
                        HostStatus::Playable => 1,
                        HostStatus::Started => 2,
                        _ => 0,
                    },
                })
                .collect()
        } else {
            self.packs
                .iter()
                .map(|m| Entry {
                    name: m.name.clone(),
                    initial: name_placeholder(&m.name),
                    detail: match (m.local, m.install_time()) {
                        (true, Some(time)) => format!("本地导入 · 安装于{}", time.format("%Y-%m-%d %H:%M")),
                        (false, Some(time)) => format!("安装于{}", time.format("%Y-%m-%d %H:%M")),
                        (true, None) => "本地导入".into(),
                        (false, None) => String::new(),
                    },
                    version: m.version.clone(),
                    playable: true,
                    art: ArtSource {
                        id: m.id.clone(),
                        url: m.icon.clone(),
                        owner: None,
                        heads: vec![],
                    },
                    status: 0,
                })
                .collect()
        }
    }
    fn play_host(&self, id: &str) {
        let (Some(api), Some(ui)) = (self.api.clone(), self.ui.upgrade()) else {
            return;
        };
        if ui.get_loading() || !self.hosts.items.get(id).is_some_and(|host| host.playable) {
            return;
        }
        let _ = api;
        self.start_room(&ui, id, false);
    }
    /// J5b/J5c: every room starts in Rust (Forge 1.20.1, NeoForge 1.21.1, Fabric 1.20.1, GTNH).
    fn start_room(&self, ui: &crate::AppWindow, id: &str, allow_duplicate: bool) {
        let Some(api) = self.api.clone() else {
            return;
        };
        if crate::java_runtime::state() == crate::java_runtime::State::Missing {
            if cfg!(windows) {
                ui.set_java_missing(true);
            } else {
                ui.set_action_error(format!("{}，请安装后重新启动启动器", crate::java_runtime::MISSING_MESSAGE).into());
            }
            return;
        }
        let (builder, tasks) = {
            let packs = self.pack_ui.borrow();
            (packs.room_deps(&self.config.server), packs.tasks())
        };
        let Some(builder) = builder else {
            ui.set_action_error("请先登录".into());
            return;
        };
        ui.set_loading(true);
        ui.set_game_status("正在准备".into());
        let size = ui.window().size();
        let id = id.to_owned();
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            // J4c 4.3: the room's pack must be installed first.
            match room_pack(&api, &id, &tasks).await {
                Ok(None) => {}
                Ok(Some(need)) => {
                    let _ = tx.send(Event::NeedPack(need));
                    return;
                }
                Err(error) => {
                    let _ = tx.send(Event::Started(Err(error)));
                    return;
                }
            }
            let deps = match tokio::task::spawn_blocking(builder).await {
                Ok(deps) => deps,
                Err(error) => {
                    let _ = tx.send(Event::Started(Err(anyhow::anyhow!("准备游玩失败: {error}"))));
                    return;
                }
            };
            let request = crate::play::room::RoomStart {
                host_id: id.clone(),
                window: (size.width, size.height),
                allow_duplicate,
            };
            use crate::play::{Admitted, room::RoomOutcome};
            let result = match crate::play::room::start(&deps, request).await {
                Ok(RoomOutcome::Admitted(Admitted::Started(_))) => Ok(serde_json::json!({"accepted": true})),
                Ok(RoomOutcome::Admitted(Admitted::Duplicate)) => Ok(serde_json::json!({"duplicateVersion": true, "hostId": id})),
                Err(error) => Err(error),
            };
            let _ = tx.send(Event::Started(result));
        });
    }
    fn load_page(&mut self) {
        if self.hosts.ended || self.page_job.is_some() {
            return;
        }
        let Some(api) = self.api.clone() else {
            return;
        };
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        if !ui.get_host_list_visible() || !ui.get_page_error().is_empty() {
            return;
        }
        ui.set_loading_more(true);
        let tx = self.tx.clone();
        let epoch = self.epoch;
        let page = self.hosts.next_page;
        self.page_job = Some(self.runtime.spawn(async move {
            let _ = tx.send(Event::Page(
                epoch,
                api.get(&format!("/host/list/{page}")).await,
            ));
        }));
    }
    fn refresh(&mut self) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        let size = (108.0 * ui.window().scale_factor()).ceil().max(1.0) as u32;
        let mut wanted = HashSet::new();
        let mut pending = vec![];
        if ui.get_host_list_visible() {
            let visible =
                ui.get_host_list_first_room() as usize..ui.get_host_list_end_room() as usize;
            let rows = self
                .hosts
                .shown(true)
                .into_iter()
                .enumerate()
                .map(|(index, host)| {
                    let mut row = HostListRow {
                        id: host.id.as_str().into(),
                        name: host.name.as_str().into(),
                        modpack: match host.pack_ver.as_deref().map(str::trim) {
                            Some(version) if !version.is_empty() => {
                                format!("{} · {version}", host.modpack_name).into()
                            }
                            _ => host.modpack_name.as_str().into(),
                        },
                        initial: host_placeholder(&host.name, &host.modpack_name),
                        ..Default::default()
                    };
                    if visible.contains(&index) {
                        let url = host.icon_url.as_ref().filter(|url| !url.is_empty());
                        if url.is_none() && host.online_player_ids.is_empty() {
                            return row;
                        }
                        let source = ArtSource {
                            id: host.id.clone(),
                            url: url.cloned(),
                            owner: None,
                            heads: host.online_player_ids.iter().take(5).cloned().collect(),
                        };
                        let key = source.key(size);
                        wanted.insert(key.clone());
                        if let Some(art) = self.cache.get(&key) {
                            row.icon = art.icon.clone();
                            row.has_icon = art.has_icon;
                            row.heads = art.heads.clone();
                        } else {
                            pending.push((key, source));
                        }
                    }
                    row
                })
                .collect();
            sync_rows(&self.host_list_rows, rows);
        }
        // The viewport can fit these rows even before the new entries reach the Slint model.
        let viewport_height = ui.get_menu_grid_height();
        let columns = ui.get_menu_grid_columns().max(1) as usize;
        let pitch = ui.get_menu_grid_row_pitch();
        for tab in 0..self.tiles.len() {
            let entries = self.entries(tab);
            let scroll = if tab == 0 {
                ui.get_host_scroll()
            } else {
                ui.get_modpack_scroll()
            };
            let rows = hosts::visible_rows(
                scroll,
                viewport_height,
                pitch,
                entries.len().div_ceil(columns),
            );
            let model: Vec<_> = entries
                .iter()
                .enumerate()
                .map(|(index, e)| {
                    let key = e.art.key(size);
                    let visible = rows.contains(&(index / columns))
                        && (self.selected == tab
                            || (ui.get_tab_position() > 0.0 && ui.get_tab_position() < 1.0));
                    let art = if visible {
                        wanted.insert(key.clone());
                        let cached = self.cache.get(&key).cloned();
                        if cached.is_none() {
                            pending.push((key, e.art.clone()));
                        }
                        cached
                    } else {
                        None
                    };
                    e.tile(art, &self.fallback)
                })
                .collect();
            sync_rows(&self.tiles[tab], model);
        }
        self.jobs.retain(|key, job| {
            if wanted.contains(key) {
                true
            } else {
                job.abort();
                false
            }
        });
        let Some(api) = self.api.clone() else {
            return;
        };
        for (key, source) in pending {
            if self.jobs.len() >= 6 {
                break;
            }
            if self.jobs.contains_key(&key) {
                continue;
            }
            let api = api.clone();
            let heads = self.heads.clone();
            let tx = self.tx.clone();
            let epoch = self.epoch;
            let jobkey = key.clone();
            let fallback = images::steve();
            let job = self.runtime.spawn(async move {
                let icon = if let Some(url) = source.url.filter(|u| !u.is_empty()) {
                    match images::icon(&api.http, &url, size).await {
                        Ok(i) => Some(i),
                        Err(error) => {
                            let host = reqwest::Url::parse(&url)
                                .ok()
                                .and_then(|u| u.host_str().map(str::to_owned));
                            tracing::warn!(?host,id=%source.id,error=?error,"Icon fallback");
                            None
                        }
                    }
                } else {
                    None
                };
                let owner = if let Some(uid) = source.owner {
                    match heads.get(&api, &uid).await {
                        Ok(h) => Some(h),
                        Err(error) => {
                            tracing::warn!(error=?error,"Owner head fallback");
                            None
                        }
                    }
                } else {
                    None
                };
                let mut online = vec![];
                for uid in source.heads {
                    match heads.get(&api, &uid).await {
                        Ok(h) => online.push(h),
                        Err(error) => {
                            tracing::warn!(error=?error,"Player head fallback");
                            if let Ok(h) = &fallback {
                                online.push(h.clone());
                            }
                        }
                    }
                }
                let _ = tx.send(Event::Art(
                    epoch,
                    jobkey,
                    Art {
                        icon,
                        owner,
                        heads: online,
                    },
                ));
            });
            self.jobs.insert(key, job);
        }
    }
    pub fn shutdown_tasks(&self) {
        // The exit dialog says running games end with the launcher.
        self.pack_ui.borrow().play.stop_all(true);
        self.pack_ui.borrow().shutdown_tasks();
    }
    /// The game bar, the running flag and the install guard from the Rust sessions.
    fn render_games(&mut self, ui: &AppWindow) {
        let rust = self.pack_ui.borrow().rust_sessions();
        ui.set_game_running(rust.iter().any(|s| s.preparing || s.running));
        let versions = rust.iter().filter(|s| s.preparing || s.running).map(|s| s.version_id.clone()).collect();
        self.pack_ui.borrow().running.update(versions);
        let status: Vec<String> = rust
            .iter()
            .map(|s| {
                let state = if s.preparing {
                    "启动中"
                } else if s.running {
                    "运行中"
                } else {
                    s.exit_message.as_deref().unwrap_or("已结束")
                };
                format!("{}：{state}", s.title)
            })
            .collect();
        if !rust.is_empty() {
            ui.set_game_status(status.join("；").into());
        }
    }
    pub fn tick(&mut self, dt: f32) {
        let authenticated = self.login_ui.borrow_mut().tick();
        if let Some(api) = authenticated {
            self.load_menu(api);
        }
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        self.pack_ui.borrow_mut().tick();
        self.plaza.borrow_mut().tick();
        self.rooms.borrow_mut().tick();
        self.room_pages.borrow_mut().tick();
        self.account_ui.borrow_mut().tick();
        self.mail_ui.borrow_mut().tick();
        self.settings_ui.borrow_mut().tick();
        self.rdipack2_ui.borrow_mut().tick();
        if self.rooms.borrow_mut().take_hosts_changed()
            && let Some(api) = self.api.clone()
        {
            let (tx, epoch) = (self.tx.clone(), self.epoch);
            self.runtime.spawn(async move {
                let _ = tx.send(Event::Mine(epoch, api.get("/host/my").await));
            });
        }
        let mut dirty = false;
        if let Some(packs) = self.pack_ui.borrow_mut().take_packs() {
            self.packs = packs;
            dirty = true;
        }
        let revision = self.pack_ui.borrow().play.revision();
        if revision != self.play_revision {
            self.play_revision = revision;
            self.render_games(&ui);
        }
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::NeedPack(need) => {
                    ui.set_loading(false);
                    if !ui.get_game_running() {
                        ui.set_game_status("".into());
                    }
                    match need {
                        Ok(run) => {
                            ui.invoke_notify("整合包正在下载，请等待下载完成后再启动".into());
                            self.pack_ui.borrow_mut().open_task(&run);
                        }
                        Err(prompt) => self
                            .pack_ui
                            .borrow_mut()
                            .action("install.prompt", &prompt.to_string()),
                    }
                }
                Event::Started(result) => {
                    ui.set_loading(false);
                    if !ui.get_game_running() {
                        ui.set_game_status("".into());
                    }
                    if let Some(ui) = self.ui.upgrade() {
                        match result {
                            Ok(value) if value["duplicateVersion"] == true => {
                                ui.set_duplicate_host(
                                    value["hostId"].as_str().unwrap_or("").into(),
                                );
                            }
                            Ok(_) => {}
                            Err(error) => ui.set_action_error(format!("{error:#}").into()),
                        }
                    }
                }
                Event::Loaded(epoch, result) if epoch == self.epoch => {
                    self.login_job = None;
                    ui.set_loading(false);
                    match result {
                        Ok(data) => {
                            ui.set_error("".into());
                            if self.player_enabled() && !self.crowd_enabled() {
                                let http = data.api.http.clone();
                                let cloth = data.player.cloth;
                                let tx = self.tx.clone();
                                let epoch = self.epoch;
                                self.appearance_job = Some(self.runtime.spawn(async move {
                                    let result = images::background_appearance(&http, cloth).await;
                                    let _ = tx.send(Event::Appearance(epoch, result));
                                }));
                            }
                            ui.set_player_name(data.player.name.into());
                            ui.set_player_head(images::to_slint(&data.head));
                            self.hosts.merge(data.mine);
                            self.packs = data.packs;
                            self.api = Some(data.api.clone());
                            self.pack_ui.borrow_mut().connect(Some(data.api.clone()));
                            // J5b B3: the node entry at sign-in; a failure keeps the current route.
                            let (server, endpoint) = (data.api.server().clone(), crate::route::Endpoint::new(&self.config.server));
                            let play = self.pack_ui.borrow().play.clone();
                            self.runtime.spawn(async move {
                                let _ = crate::route::refresh(&server, &endpoint).await;
                                reconnect_recovered_room(&play, &endpoint).await;
                            });
                            if ui.get_host_list_visible() {
                                self.refresh();
                            }
                            if ui.get_host_list_near_end() {
                                self.load_page();
                            }
                            tracing::info!(
                                hosts = self.hosts.items.len(),
                                modpacks = self.packs.len(),
                                "Menu data loaded"
                            );
                            dirty = true;
                        }
                        Err(error) => {
                            tracing::error!(error=?error,"Menu login/load failed");
                            ui.set_error(format!("{error:#}").into());
                        }
                    }
                }
                Event::Appearance(epoch, result)
                    if epoch == self.epoch && self.player_enabled() =>
                {
                    self.appearance_job = None;
                    match result {
                        Ok(appearance) => {
                            tracing::info!(
                                slim = appearance.skin.slim,
                                cape = appearance.cape.is_some(),
                                "Account player appearance ready"
                            );
                            self.player_appearance = Some(appearance);
                            ui.window().request_redraw();
                        }
                        Err(error) => {
                            tracing::error!(error=%format!("{error:#}"),"Account player appearance failed; retaining Steve")
                        }
                    }
                }
                Event::CrowdSkin(epoch, id, skin)
                    if epoch == self.epoch && self.crowd_enabled() =>
                {
                    if self.online_crowd.record_skin(&id) && let Some(skin) = skin
                    {
                        self.crowd_skins.push((id, skin));
                        ui.window().request_redraw();
                    }
                }
                Event::OnlineSkinsFinished(epoch) if epoch == self.epoch => self.crowd_job = None,
                Event::Page(epoch, result) if epoch == self.epoch => {
                    self.page_job = None;
                    ui.set_loading_more(false);
                    match result {
                        Ok(page) => {
                            self.hosts.page(page);
                            tracing::info!(
                                hosts = self.hosts.items.len(),
                                ended = self.hosts.ended,
                                "Host page merged"
                            );
                            self.refresh();
                            if ui.get_host_list_near_end() {
                                self.load_page();
                            }
                        }
                        Err(error) => {
                            tracing::error!(error=?error,"Host page failed");
                            ui.set_page_error(format!("{error:#}").into());
                        }
                    }
                }
                Event::Online(epoch, result)
                    if epoch == self.epoch && self.crowd_enabled() =>
                {
                    self.poll_job = None;
                    self.next_online_poll = Some(Instant::now() + ONLINE_POLL_INTERVAL);
                    match result {
                        Ok(ids) => self.refresh_online_crowd(ids),
                        Err(error) => tracing::warn!(?error, "Online player poll failed"),
                    }
                }
                Event::Mine(epoch, result) if epoch == self.epoch => match result {
                    Ok(mine) => {
                        // A deleted or left room must disappear, so the list starts over.
                        if let Some(job) = self.page_job.take() {
                            job.abort();
                        }
                        ui.set_loading_more(false);
                        self.hosts = Hosts::default();
                        self.hosts.merge(mine);
                        dirty = true;
                        if ui.get_host_list_near_end() {
                            self.load_page();
                        }
                    }
                    Err(error) => tracing::warn!(error = ?error, "刷新我的房间失败"),
                },
                Event::Art(epoch, key, art)
                    if epoch == self.epoch && self.jobs.remove(&key).is_some() =>
                {
                    self.cache.put(
                        key,
                        CachedArt {
                            has_icon: art.icon.is_some(),
                            icon: art.icon.as_ref().map(images::to_slint).unwrap_or_default(),
                            owner: art
                                .owner
                                .as_ref()
                                .map(images::to_slint)
                                .unwrap_or_else(|| self.fallback.clone()),
                            heads: ModelRc::new(VecModel::from(
                                art.heads.iter().map(images::to_slint).collect::<Vec<_>>(),
                            )),
                        },
                    );
                    dirty = true;
                }
                _ => {}
            }
        }
        // A list or tile change that arrives while the window is in the background is kept
        // until it is active again, instead of being dropped.
        self.refresh_pending |= dirty;
        if self.paused.load(Ordering::Relaxed) {
            return;
        }
        // Leave due polls pending while inactive so the first focused tick can send them.
        self.poll_online_players();
        let scale = ui.window().scale_factor();
        if scale != self.last_scale {
            self.last_scale = scale;
            self.refresh_pending = true;
        }
        if std::mem::take(&mut self.refresh_pending) {
            self.refresh();
        }
        let mut animated = false;
        for (tab, i) in self.interactions.iter_mut().enumerate() {
            animated |= i.hover_spring.step(dt);
            animated |= i.press_spring.step(dt);
            if i.hover_spring.value == 0.0 && i.hover_spring.target == 0.0 {
                i.hover = -1;
            }
            if i.press_spring.value == 0.0 && i.press_spring.target == 0.0 {
                i.pressed = -1;
            }
            let scale = 1.0 - 0.04 * i.press_spring.value;
            if tab == 0 {
                ui.set_host_pressed(i.pressed);
                ui.set_host_scale(scale);
            } else {
                ui.set_modpack_pressed(i.pressed);
                ui.set_modpack_scale(scale);
            }
        }
        for p in &mut self.pills {
            animated |= p.step(dt);
        }
        if animated {
            ui.window().request_redraw();
        }
    }
    pub fn frame(&self, ui: &AppWindow) -> glass::Frame {
        crate::shell::frame(
            ui,
            &self.interactions,
            &self.pills,
            &self.pivots,
            self.selected,
        )
    }
}
/// Updates `model` to `rows`, touching only rows that changed so unchanged tiles keep their textures.
fn sync_rows<T: Clone + PartialEq + 'static>(model: &VecModel<T>, rows: Vec<T>) {
    let len = rows.len();
    for (i, row) in rows.into_iter().enumerate() {
        if i >= model.row_count() {
            model.push(row);
        } else if model.row_data(i).as_ref() != Some(&row) {
            model.set_row_data(i, row);
        }
    }
    while model.row_count() > len {
        model.remove(model.row_count() - 1);
    }
}
impl Drop for App {
    fn drop(&mut self) {
        for job in self.jobs.values() {
            job.abort();
        }
        for j in [
            &self.login_job,
            &self.page_job,
            &self.poll_job,
            &self.appearance_job,
            &self.crowd_job,
        ]
        .into_iter()
        .flatten()
        {
            j.abort();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn online_crowd_tracks_all_distinct_players_and_discards_offline_skins() {
        let mut crowd = OnlineCrowd::default();
        let ids: Vec<String> = (0..75).map(|id| format!("player-{id:02}")).collect();
        let reported = ids.iter().rev().cloned().chain(["player-00".into()]);
        assert!(crowd.update(reported.collect()));
        assert_eq!(crowd.take_roster(), Some(ids));
        assert_eq!(crowd.unchecked_ids().len(), 75);
        assert!(crowd.record_skin("player-00"));
        assert!(crowd.record_skin("player-01"));
        assert!(crowd.update(vec!["player-01".into(), "player-75".into()]));
        assert_eq!(crowd.take_roster(), Some(vec!["player-01".into(), "player-75".into()]));
        assert_eq!(crowd.unchecked_ids(), ["player-75"]);
        assert!(!crowd.record_skin("player-00"));
        assert!(!crowd.update(vec!["player-75".into(), "player-01".into()]));
        assert_eq!(crowd.take_roster(), None);
        assert!(crowd.update(Vec::new()));
        assert_eq!(crowd.take_roster(), Some(Vec::new()));
    }

    #[test]
    fn name_placeholder_matches_compose_hue_and_initial() {
        // This name exercises a negative Java hash and a UTF-16 surrogate pair.
        let initial = name_placeholder("【a】冒险🐱");
        assert_eq!(initial.text, "A");
        assert_eq!(
            initial.background,
            Color::from_hsva(43.0, 0.45 / 0.725, 0.725, 1.0),
        );

        assert_eq!(name_placeholder("【冒险】整合包").text, "冒");
        assert_eq!(name_placeholder("【2】").text, "2");
        assert_eq!(name_placeholder("🐱").text, "?");
    }

    #[test]
    fn host_placeholder_uses_pack_name_or_falls_back_to_room_name() {
        let room = "【周末】房间";
        let pack = "【冒险】整合包";
        assert_eq!(host_placeholder(room, pack), name_placeholder(pack));
        assert_eq!(host_placeholder(room, "  "), name_placeholder(room));
    }
}

/// The fields of Kotlin `Host.DetailVo` the pack check needs.
#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomDetail {
    modpack: RoomPack,
    pack_ver: String,
}
#[derive(serde::Deserialize)]
struct RoomPack {
    id: String,
    #[serde(default)]
    name: String,
}
#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomVersion {
    total_size: Option<u64>,
}
/// J5b B5 (`GameRecovery.restore`): a room game recovered after a crash gets its room connection
/// back on the port it was started with, so the player can reconnect from the game.
async fn reconnect_recovered_room(play: &crate::play::Sessions, endpoint: &crate::route::Endpoint) {
    let Some(port) = play.recovered_room_port() else {
        return;
    };
    let nodes = endpoint.proxy_nodes(&crate::route::state());
    let lines = play.clone();
    match crate::route::link::start_on(Some(port), nodes, move |line| lines.append_alive(line)).await {
        Ok(address) if address.ends_with(&format!(":{port}")) => {
            play.append_alive("[RDI] 房间连接已恢复，可在游戏中重新连接房间");
        }
        Ok(address) => {
            tracing::warn!(%address, port, "房间连接原端口被占用");
            play.append_alive("[RDI] 房间连接无法使用原来的端口，请结束游戏后重新进入房间");
        }
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "游戏已找回，但房间连接恢复失败");
            play.append_alive("[RDI] 房间连接恢复失败，请结束游戏后重新进入房间");
        }
    }
}
/// `None` when the pack is installed; otherwise the running install or the confirmation data.
async fn room_pack(
    api: &Client,
    host_id: &str,
    tasks: &crate::tasks::Manager,
) -> anyhow::Result<Option<Result<String, serde_json::Value>>> {
    let detail: RoomDetail = api.get(&format!("/host/{host_id}/detail")).await?;
    let (id, version) = (detail.modpack.id, detail.pack_ver);
    let versions = crate::local_packs::versions_dir();
    Ok(match crate::pack_install::state_in(tasks, &versions, &id, &version) {
        crate::pack_install::State::Installed => None,
        crate::pack_install::State::Installing(run) => Some(Ok(run)),
        crate::pack_install::State::Missing => {
            let info: RoomVersion = api
                .get(&format!("/modpack/{id}/version/{version}"))
                .await?;
            Some(Err(serde_json::json!({"modpackId":id,"version":version,
                "name":detail.modpack.name,"size":info.total_size})))
        }
    })
}
