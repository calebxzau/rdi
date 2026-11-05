use crate::{
    AppWindow, TileData,
    anim::Spring,
    api::{Client, model::*},
    config::Config,
    glass,
    hosts::{self, Hosts},
    images::{self, HeadCache},
};
use anyhow::Result;
use rdi_slint_proto::scene::player::{Appearance, Skin};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
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
    time::Duration,
};
use tokio::task::JoinHandle;
struct Loaded {
    api: Client,
    player: PlayerInfo,
    head: image::RgbaImage,
    mine: Vec<HostBrief>,
    page: Vec<HostBrief>,
    packs: Vec<ModpackBrief>,
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
    Appearance(u64, Result<Appearance>),
    CrowdSkin(u64, String, Skin),
    Page(u64, Result<Vec<HostBrief>>),
    Online(u64, Result<Vec<String>>),
    Art(u64, String, Art),
}
#[derive(Default)]
pub struct Interaction {
    hover: i32,
    pressed: i32,
    hover_spring: Spring,
    press_spring: Spring,
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
#[derive(Clone)]
struct Entry {
    id: String,
    name: String,
    detail: String,
    url: Option<String>,
    owner: Option<String>,
    heads: Vec<String>,
    dimmed: bool,
    status: i32,
}
impl Entry {
    fn key(&self, size: u32) -> String {
        format!(
            "{}|{}|{size}|{}|{}",
            self.id,
            self.url.as_deref().unwrap_or(""),
            self.owner.as_deref().unwrap_or(""),
            self.heads.join(",")
        )
    }
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
            id: self.id.clone().into(),
            name: self.name.clone().into(),
            initial: self.name.chars().next().unwrap_or('房').to_string().into(),
            detail: self.detail.clone().into(),
            icon,
            has_icon,
            owner,
            has_owner: self.owner.is_some(),
            heads,
            dimmed: self.dimmed,
            status: self.status,
        }
    }
}
pub struct App {
    ui: slint::Weak<AppWindow>,
    runtime: tokio::runtime::Handle,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
    config: Config,
    hosts: Hosts,
    packs: Vec<ModpackBrief>,
    api: Option<Client>,
    heads: HeadCache,
    cache: lru::LruCache<String, CachedArt>,
    jobs: HashMap<String, JoinHandle<()>>,
    page_job: Option<JoinHandle<()>>,
    login_job: Option<JoinHandle<()>>,
    poll_job: Option<JoinHandle<()>>,
    appearance_job: Option<JoinHandle<()>>,
    player_appearance: Option<Appearance>,
    crowd_job: Option<JoinHandle<()>>,
    crowd_skins: Vec<(String, Skin)>,
    epoch: u64,
    paused: Arc<AtomicBool>,
    fallback: slint::Image,
    /// Kept for the app's lifetime and updated row by row: replacing a model recreates every tile,
    /// and femtovg then uploads every icon again (in-memory images have no shared texture cache).
    tiles: [Rc<VecModel<TileData>>; 2],
    pub interactions: [Interaction; 2],
    pills: [Spring; 7],
    pivots: [[f32; 2]; 7],
    selected: usize,
    show_others: bool,
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
        ui.set_show_others(config.show_others);
        let tiles = [Rc::new(VecModel::default()), Rc::new(VecModel::default())];
        ui.set_hosts(ModelRc::from(tiles[0].clone()));
        ui.set_modpacks(ModelRc::from(tiles[1].clone()));
        let app = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            tx,
            rx,
            show_others: config.show_others,
            config,
            hosts: Hosts::default(),
            packs: vec![],
            api: None,
            heads: HeadCache::default(),
            cache: lru::LruCache::new(NonZeroUsize::new(60).unwrap()),
            jobs: HashMap::new(),
            page_job: None,
            login_job: None,
            poll_job: None,
            appearance_job: None,
            player_appearance: None,
            crowd_job: None,
            crowd_skins: Vec::new(),
            epoch: 0,
            paused,
            fallback,
            tiles,
            interactions: [Interaction::new(), Interaction::new()],
            pills: [Spring::default(); 7],
            pivots: [[0.0; 2]; 7],
            selected: 0,
            last_scale: 0.0,
        }));
        Self::wire(&app, ui);
        if !app.borrow().config.ui_proof { app.borrow_mut().login(); }
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
        ui.on_toggle_others(move || {
            if let Some(a) = weak.upgrade() {
                let mut a = a.borrow_mut();
                a.show_others = !a.show_others;
                if let Some(ui) = a.ui.upgrade() {
                    ui.set_show_others(a.show_others);
                    ui.set_host_scroll(0.0);
                }
                a.refresh();
            }
        });
        let weak = Rc::downgrade(app);
        ui.on_scroll_changed(move || {
            if let Some(a) = weak.upgrade()
                && let Ok(mut a) = a.try_borrow_mut()
            {
                a.refresh();
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
                tracing::info!(id = %entry.id, action, "Prototype tile action");
            }
        });
        ui.on_stub(|name| tracing::info!(action=%name,"Prototype action"));
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
        for job in [&mut self.appearance_job, &mut self.crowd_job] {
            if let Some(job) = job.take() {
                job.abort();
            }
        }
        self.player_appearance = None;
        self.crowd_skins = Vec::new();
        self.config.no_scene = true;
    }
    pub fn take_player_appearance(&mut self) -> Option<Appearance> {
        self.player_appearance.take()
    }
    pub fn take_crowd_skins(&mut self) -> Vec<(String, Skin)> {
        std::mem::take(&mut self.crowd_skins)
    }
    fn login(&mut self) {
        self.epoch += 1;
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
        self.crowd_skins = Vec::new();
        self.player_appearance = if self.player_enabled() {
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
        self.api = None;
        self.cache.clear();
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        ui.set_loading(true);
        ui.set_error("".into());
        ui.set_page_error("".into());
        ui.set_loading_more(false);
        for model in &self.tiles {
            model.clear();
        }
        let tx = self.tx.clone();
        let epoch = self.epoch;
        let base = self.config.server.clone();
        let heads = self.heads.clone();
        self.login_job = Some(self.runtime.spawn(async move {
            let result = async {
                let (usr, pwd) = Config::credentials()?;
                let api = Client::new(base)?.login(&usr, &pwd).await?;
                drop(usr);
                drop(pwd);
                let uid = api.uid()?;
                let player: PlayerInfo = api.get(&format!("/player/{uid}/info")).await?;
                let mine = api.get("/host/my").await?;
                let page = api.get("/host/list/0").await?;
                let packs: ModpackPage = api.get("/modpack/search?offset=0&limit=100").await?;
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
                    page,
                    packs: packs.items,
                })
            }
            .await;
            let _ = tx.send(Event::Loaded(epoch, result));
        }));
    }
    fn entries(&self, tab: usize) -> Vec<Entry> {
        if tab == 0 {
            self.hosts
                .shown(self.show_others, self.config.all_playable)
                .into_iter()
                .map(|h| Entry {
                    id: h.id,
                    name: h.name,
                    detail: h.modpack_name,
                    url: h.icon_url,
                    owner: h.owner_id,
                    heads: h.online_player_ids,
                    dimmed: !(h.playable || self.config.all_playable),
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
                    id: m.id.clone(),
                    name: m.name.clone(),
                    detail: String::new(),
                    url: m.icon.clone(),
                    owner: None,
                    heads: vec![],
                    dimmed: false,
                    status: 0,
                })
                .collect()
        }
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
        if !ui.get_page_error().is_empty() {
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
        for tab in 0..2 {
            let entries = self.entries(tab);
            let scroll = if tab == 0 {
                ui.get_host_scroll()
            } else {
                ui.get_modpack_scroll()
            };
            let columns = hosts::columns(scroll, ui.get_logical_width(), entries.len(), 2);
            let model: Vec<_> = entries
                .iter()
                .enumerate()
                .map(|(index, e)| {
                    let key = e.key(size);
                    let visible = columns.contains(&hosts::cell(index).0)
                        && (self.selected == tab
                            || (ui.get_tab_position() > 0.0 && ui.get_tab_position() < 1.0));
                    let art = if visible {
                        wanted.insert(key.clone());
                        let cached = self.cache.get(&key).cloned();
                        if cached.is_none() {
                            pending.push((key.clone(), e.clone()));
                        }
                        cached
                    } else {
                        None
                    };
                    e.tile(art, &self.fallback)
                })
                .collect();
            sync_rows(&self.tiles[tab], model);
            if tab == 0 && self.selected == 0 && columns.end * 3 + 6 >= entries.len() {
                self.load_page();
            }
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
        for (key, e) in pending {
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
                let icon = if let Some(url) = e.url.filter(|u| !u.is_empty()) {
                    match images::icon(&api.http, &url, size).await {
                        Ok(i) => Some(i),
                        Err(error) => {
                            let host = reqwest::Url::parse(&url)
                                .ok()
                                .and_then(|u| u.host_str().map(str::to_owned));
                            tracing::warn!(?host,id=%e.id,error=?error,"Icon fallback");
                            None
                        }
                    }
                } else {
                    None
                };
                let owner = if let Some(uid) = e.owner {
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
                for uid in e.heads {
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
    pub fn tick(&mut self, dt: f32) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        let mut dirty = false;
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Loaded(epoch, result) if epoch == self.epoch => {
                    self.login_job = None;
                    ui.set_loading(false);
                    match result {
                        Ok(data) => {
                            if self.player_enabled() {
                                let http = data.api.http.clone();
                                let cloth = data.player.cloth;
                                let tx = self.tx.clone();
                                let epoch = self.epoch;
                                self.appearance_job = Some(self.runtime.spawn(async move {
                                    let result = images::background_appearance(&http, cloth).await;
                                    let _ = tx.send(Event::Appearance(epoch, result));
                                }));
                            }
                            if self.crowd_enabled() {
                                let api = data.api.clone();
                                let tx = self.tx.clone();
                                let epoch = self.epoch;
                                self.crowd_job = Some(self.runtime.spawn(async move {
                                    images::background_crowd(&api, |id, skin| {
                                        let _ = tx.send(Event::CrowdSkin(epoch, id, skin));
                                    })
                                    .await;
                                }));
                            }
                            ui.set_player_name(data.player.name.into());
                            ui.set_player_head(images::to_slint(&data.head));
                            self.hosts.merge(data.mine);
                            self.hosts.page(data.page);
                            self.packs = data.packs;
                            self.api = Some(data.api.clone());
                            tracing::info!(
                                hosts = self.hosts.items.len(),
                                modpacks = self.packs.len(),
                                "Menu data loaded"
                            );
                            let tx = self.tx.clone();
                            let paused = self.paused.clone();
                            let api = data.api;
                            self.poll_job = Some(self.runtime.spawn(async move {
                                loop {
                                    if !paused.load(Ordering::Relaxed) {
                                        let result = api.get("/host/online-player-ids").await;
                                        let _ = tx.send(Event::Online(epoch, result));
                                    }
                                    tokio::time::sleep(Duration::from_secs(30)).await;
                                }
                            }));
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
                    self.crowd_skins.push((id, skin));
                    ui.window().request_redraw();
                }
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
                            dirty = true;
                        }
                        Err(error) => {
                            tracing::error!(error=?error,"Host page failed");
                            ui.set_page_error(format!("{error:#}").into());
                        }
                    }
                }
                Event::Online(epoch, result) if epoch == self.epoch => match result {
                    Ok(ids) => ui.set_online_count(ids.len() as i32),
                    Err(error) => tracing::warn!(error=?error,"Online player poll failed"),
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
        if self.paused.load(Ordering::Relaxed) {
            return;
        }
        let scale = ui.window().scale_factor();
        if scale != self.last_scale {
            self.last_scale = scale;
            dirty = true;
        }
        if dirty {
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
        if ui.get_ui_proof() { return crate::ui_proof::frame(ui); }
        let size = ui.window().size();
        let width = ui.get_logical_width();
        let height = ui.get_logical_height();
        let pos = ui.get_tab_position();
        let grids = (0..2)
            .map(|tab| {
                let i = &self.interactions[tab];
                glass::Grid {
                    y: ui.get_grid_y(),
                    x: if tab == 0 {
                        -pos * width
                    } else {
                        (1.0 - pos) * width
                    },
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
                    opacity: if tab == 0 { 1.0 - pos } else { pos },
                    hover: i.hover,
                    hover_progress: i.hover_spring.value,
                    pressed: i.pressed,
                    press_scale: 1.0 - 0.04 * i.press_spring.value,
                }
            })
            .collect();
        let navx = (width - 292.0) / 2.0;
        let mut pills = vec![glass::Pill {
            rect: [navx, 56.0, 292.0, 56.0],
            selected: false,
            progress: 0.0,
            pivot: [146.0, 28.0],
        }];
        let idx = self.selected;
        pills.push(glass::Pill {
            rect: [navx + 6.0 + 158.0 * pos, 62.0, 154.0 - 32.0 * pos, 44.0],
            selected: true,
            progress: self.pills[idx].value,
            pivot: self.pivots[idx],
        });
        let rects: Vec<(usize, [f32; 4])> = if self.selected == 0 {
            let x = (width - 308.0) / 2.0;
            vec![
                (2, [x, height - 60.0, 128.0, 36.0]),
                (3, [x + 140.0, height - 60.0, 168.0, 36.0]),
            ]
        } else {
            let x = (width - 484.0) / 2.0;
            vec![
                (4, [x, height - 60.0, 128.0, 36.0]),
                (5, [x + 140.0, height - 60.0, 176.0, 36.0]),
                (6, [x + 328.0, height - 60.0, 156.0, 36.0]),
            ]
        };
        pills.extend(rects.into_iter().map(|(index, rect)| glass::Pill {
            rect,
            selected: false,
            progress: self.pills[index].value,
            pivot: self.pivots[index],
        }));
        glass::Frame {
            width: size.width as i32,
            height: size.height as i32,
            scale: ui.window().scale_factor(),
            grids,
            pills,
            panels: vec![],
        }
    }
}
/// Updates `model` to `rows`, touching only rows that changed so unchanged tiles keep their textures.
fn sync_rows(model: &VecModel<TileData>, rows: Vec<TileData>) {
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
