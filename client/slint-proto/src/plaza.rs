//! Authenticated browse flow for the RDI modpack plaza.
use crate::{
    AppWindow, PlazaCard, PlazaFilters, PlazaMod, PlazaPack, PlazaVersion,
    api::Client,
    app::name_placeholder,
    images, local_packs,
    mod_cards::{self, ModCards, initial},
    pack_install::mods::Mod,
    packs::human_file_size,
    rooms::mods,
};
use anyhow::{Context, Result};
use serde::Deserialize;
use slint::{ComponentHandle, Model, SharedString, VecModel};
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet},
    rc::Rc,
    sync::{Arc, mpsc},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    runtime::Handle,
    sync::Semaphore,
    task::{JoinHandle, JoinSet},
};

const PAGE_SIZE: usize = 60;
const MINECRAFT: [&str; 4] = ["全部MC版本", "1.21.1", "1.20.1", "1.7.10"];
const SORTS: [(&str, &str); 3] = [
    ("最近更新", "UPDATED"),
    ("最多游玩", "POPULAR"),
    ("相关", "RELEVANCE"),
];
const CATEGORIES: [(&str, &str); 21] = [
    ("全部分类", ""),
    ("大型", "LARGE"),
    ("中型", "MEDIUM"),
    ("小型", "SMALL"),
    ("魔法", "MAGIC"),
    ("硬核", "HARDCORE"),
    ("空岛", "SKYBLOCK"),
    ("纯净", "VANILLA"),
    ("科技", "TECH"),
    ("剧情", "STORY"),
    ("探险", "ADVENTURE"),
    ("休闲", "CASUAL"),
    ("经营", "MANAGE"),
    ("末日", "APOCALYPSE"),
    ("战争", "WAR"),
    ("魔改", "HEAVY_MOD"),
    ("RPG", "RPG"),
    ("战斗", "COMBAT"),
    ("轻量", "LIGHT_MOD"),
    ("优化", "OPTIMIZE"),
    ("其他", "OTHER"),
];

fn query(keyword: &str, filters: &PlazaFilters, offset: usize) -> Vec<(&'static str, String)> {
    let mut query = vec![
        ("offset", offset.to_string()),
        ("limit", PAGE_SIZE.to_string()),
    ];
    if !keyword.is_empty() {
        query.push(("q", keyword.into()));
    }
    if filters.mine {
        query.push(("mine", "true".into()));
    }
    if let Some(version) = MINECRAFT
        .get(filters.minecraft as usize)
        .filter(|_| filters.minecraft > 0)
    {
        query.push(("mcVer", (*version).into()));
    }
    if let Some((_, category)) = CATEGORIES
        .get(filters.category as usize)
        .filter(|_| filters.category > 0)
    {
        query.push(("category", (*category).into()));
    }
    let sort = SORTS.get(filters.sort as usize).unwrap_or(&SORTS[0]).1;
    if sort != "RELEVANCE" {
        query.push(("sort", sort.into()));
    }
    query
}

#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct Page {
    items: Vec<Card>,
    has_more: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Card {
    id: String,
    #[serde(default = "unknown_pack_name")]
    name: String,
    icon: Option<String>,
    info: Option<String>,
    #[serde(default)]
    play_count: u64,
    #[serde(default)]
    last_updated_time: u64,
    #[serde(default)]
    categories: Vec<String>,
}

fn unknown_pack_name() -> String {
    "未知整合包".into()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Detail {
    name: String,
    author_id: String,
    #[serde(default)]
    author_name: String,
    info: Option<String>,
    source_url: Option<String>,
    modloader: String,
    mc_ver: String,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    versions: Vec<Version>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Version {
    name: String,
    status: String,
    time: u64,
    uploader_id: Option<String>,
    total_size: Option<u64>,
    #[serde(default)]
    changelog: String,
    #[serde(default)]
    mods: Vec<Mod>,
    #[serde(default)]
    client_extras: Vec<Extra>,
    base_world: Option<BaseWorld>,
}
#[derive(Deserialize)]
struct Extra {
    slug: String,
    #[serde(rename = "type")]
    kind: String,
}
#[derive(Deserialize)]
struct BaseWorld {
    #[serde(default)]
    required: bool,
}

fn visible(version: &Version, uid: &str, is_author: bool) -> bool {
    is_author || version.status == "OK" || version.uploader_id.as_deref() == Some(uid)
}

fn categories(entries: &[String]) -> String {
    entries
        .iter()
        .filter_map(|entry| {
            CATEGORIES
                .iter()
                .find(|(_, key)| key == entry)
                .map(|(label, _)| *label)
        })
        .collect::<Vec<_>>()
        .join(" · ")
}

fn ago(millis: u64) -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
        .saturating_sub(millis / 1000);
    for (unit, suffix) in [(86400, "天前"), (3600, "小时前"), (60, "分钟前")] {
        if seconds >= unit {
            return format!("{}{suffix}", seconds / unit);
        }
    }
    "刚刚".into()
}

/// `Modpack.Status` as 0 可用, 1 构建中, 2 构建失败, 3 等待构建 or unknown; the page picks the
/// icon and colour of `ModpackVersionCard` from it.
fn status_state(status: &str) -> i32 {
    match status {
        "OK" => 0,
        "BUILDING" => 1,
        "FAIL" => 2,
        _ => 3,
    }
}

/// `millisToHumanDateTime` (`yyyy-MM-dd HH:mm:ss`, local time).
fn date_time(millis: u64) -> String {
    i64::try_from(millis)
        .ok()
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|time| time.with_timezone(&chrono::Local).format("%Y-%m-%d %H:%M:%S").to_string())
        .unwrap_or_default()
}

fn status_text(status: &str) -> &str {
    match status {
        "OK" => "可用",
        "BUILDING" => "构建中",
        "FAIL" => "构建失败",
        "WAIT" => "等待构建",
        _ => "未知状态",
    }
}

impl Version {
    fn summary(&self) -> PlazaVersion {
        let mut metadata = vec![format!("{}个Mod", self.mods.len()), ago(self.time)];
        if let Some(size) = self.total_size.filter(|size| *size > 0) {
            metadata.push(human_file_size(size));
        }
        PlazaVersion {
            name: self.name.as_str().into(),
            status: status_text(&self.status).into(),
            state: status_state(&self.status),
            available: self.status == "OK",
            metadata: metadata.join(" · ").into(),
        }
    }

    /// 「上传 time · size」 before the uploader on the version page.
    fn uploaded(&self) -> String {
        let mut text = format!("上传 {}", date_time(self.time));
        if let Some(size) = self.total_size.filter(|size| *size > 0) {
            text.push_str(&format!(" · {}", human_file_size(size)));
        }
        text
    }

    /// The client extras by kind, e.g. 「资源包：a、b · 光影：c」; empty without any.
    fn extras(&self) -> String {
        let mut groups: Vec<(&str, Vec<&str>)> = Vec::new();
        for entry in &self.client_extras {
            let kind = match entry.kind.as_str() {
                "ResPack" => "资源包",
                "ShaderPack" => "光影",
                _ => "附加内容",
            };
            match groups.iter_mut().find(|(k, _)| *k == kind) {
                Some((_, names)) => names.push(entry.slug.as_str()),
                None => groups.push((kind, vec![entry.slug.as_str()])),
            }
        }
        groups.iter().map(|(kind, names)| format!("{kind}：{}", names.join("、"))).collect::<Vec<_>>().join(" · ")
    }
}

fn pack_id(id: &str) -> bool {
    id.len() == 24 && id.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn source_url(source: Option<&str>) -> Option<reqwest::Url> {
    let url = reqwest::Url::parse(source?.trim()).ok()?;
    (matches!(url.scheme(), "http" | "https") && url.has_host()).then_some(url)
}

/// The offset is based on raw server rows, including IDs that were already displayed.
fn next_offset(offset: usize, page: &Page) -> Option<usize> {
    (page.has_more && !page.items.is_empty()).then(|| offset + page.items.len())
}

enum Reply {
    Page(u64, usize, Result<Page>),
    Detail(u64, Result<Detail>),
    Icon(u64, usize, image::RgbaImage),
    Cards(u64, HashMap<String, mod_cards::Card>),
    ModIcon(String, Option<image::RgbaImage>),
    Uploader(String, Result<(String, image::RgbaImage)>),
}

pub struct Plaza {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    cards: Rc<VecModel<PlazaCard>>,
    ids: HashSet<String>,
    keyword: String,
    filters: PlazaFilters,
    next: Option<usize>,
    opened: bool,
    search_epoch: u64,
    detail_epoch: u64,
    search_job: Option<JoinHandle<()>>,
    detail_job: Option<JoinHandle<()>>,
    icon_jobs: Vec<JoinHandle<()>>,
    icon_slots: Arc<Semaphore>,
    detail_id: String,
    detail: Option<Detail>,
    tasks: crate::tasks::Manager,
    /// The version page's (name, built) for the install button.
    shown_version: Option<(String, bool)>,
    install_checked: std::time::Instant,
    /// The version page's mods, their catalog cards and when the rows were last rebuilt.
    version_mods: Vec<Mod>,
    version_epoch: u64,
    mod_cards: ModCards,
    mods_dirty: bool,
    mods_rendered: Option<Instant>,
    /// Uploader id -> name and head; 「未知玩家」 and Steve after a failed request.
    players: HashMap<String, (String, slint::Image)>,
    uploader: String,
}

impl Plaza {
    pub fn wire(ui: &AppWindow, runtime: Handle, tasks: crate::tasks::Manager) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let cards = Rc::new(VecModel::default());
        ui.set_plaza_cards(cards.clone().into());
        ui.set_plaza_categories(VecModel::from_slice(
            &CATEGORIES.map(|(label, _)| SharedString::from(label)),
        ));
        ui.set_plaza_minecraft(VecModel::from_slice(&MINECRAFT.map(SharedString::from)));
        ui.set_plaza_sorts(VecModel::from_slice(
            &SORTS.map(|(label, _)| SharedString::from(label)),
        ));
        let plaza = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            api: None,
            tx,
            rx,
            cards,
            ids: HashSet::new(),
            keyword: String::new(),
            filters: PlazaFilters::default(),
            next: None,
            opened: false,
            search_epoch: 0,
            detail_epoch: 0,
            search_job: None,
            detail_job: None,
            icon_jobs: Vec::new(),
            icon_slots: Arc::new(Semaphore::new(6)),
            detail_id: String::new(),
            detail: None,
            tasks,
            shown_version: None,
            install_checked: std::time::Instant::now(),
            version_mods: Vec::new(),
            version_epoch: 0,
            mod_cards: ModCards::default(),
            mods_dirty: false,
            mods_rendered: None,
            players: HashMap::new(),
            uploader: String::new(),
        }));
        let weak = Rc::downgrade(&plaza);
        ui.on_plaza_action(move |action, value| {
            if let Some(plaza) = weak.upgrade() {
                plaza.borrow_mut().action(action.as_str(), value.as_str());
            }
        });
        plaza
    }

    pub fn connect(&mut self, api: Option<Client>) {
        self.cancel_search();
        self.detail_epoch += 1;
        abort(&mut self.detail_job);
        self.detail = None;
        self.detail_id.clear();
        self.shown_version = None;
        self.version_epoch += 1;
        self.version_mods.clear();
        self.uploader.clear();
        self.players.clear();
        self.keyword.clear();
        self.filters = PlazaFilters::default();
        self.next = None;
        self.opened = false;
        self.api = api;
        if let Some(ui) = self.ui.upgrade() {
            ui.set_plaza_query("".into());
            ui.set_plaza_filters(self.filters.clone());
            ui.set_plaza_loading(false);
            ui.set_plaza_more(false);
            ui.set_plaza_error("".into());
            ui.set_plaza_detail_loading(false);
            ui.set_plaza_detail_error("".into());
            ui.set_plaza_pack(PlazaPack::default());
            ui.set_plaza_versions(Default::default());
            ui.set_plaza_version(PlazaVersion::default());
            ui.set_plaza_mods(Default::default());
            ui.set_plaza_mod_query("".into());
            ui.set_plaza_extras("".into());
            ui.set_plaza_changelog("".into());
            ui.set_plaza_base_world("".into());
        }
    }

    fn cancel_search(&mut self) {
        self.search_epoch += 1;
        abort(&mut self.search_job);
        for job in self.icon_jobs.drain(..) {
            job.abort();
        }
        self.cards.clear();
        self.ids.clear();
    }

    fn restart_search(&mut self, ui: &AppWindow) {
        self.cancel_search();
        self.opened = true;
        self.next = Some(0);
        ui.set_plaza_scroll(0.);
        ui.set_plaza_more(false);
        self.request_page(ui);
    }

    fn request_page(&mut self, ui: &AppWindow) {
        let Some(offset) = self.next.filter(|_| self.search_job.is_none()) else {
            return;
        };
        let Some(api) = self.api.clone() else {
            ui.set_plaza_error("请先登录".into());
            return;
        };
        ui.set_plaza_loading(true);
        ui.set_plaza_error("".into());
        let query = query(&self.keyword, &self.filters, offset);
        let epoch = self.search_epoch;
        let tx = self.tx.clone();
        self.search_job = Some(self.runtime.spawn(async move {
            let result = api.get_query("/modpack/search", &query).await;
            let _ = tx.send(Reply::Page(epoch, offset, result));
        }));
    }

    fn show_detail(&mut self, ui: &AppWindow, id: String) {
        self.detail_id = id;
        self.request_detail(ui);
        ui.invoke_navigate("plaza-detail".into());
    }

    fn request_detail(&mut self, ui: &AppWindow) {
        if self.detail_id.is_empty() {
            return;
        }
        self.detail_epoch += 1;
        abort(&mut self.detail_job);
        self.detail = None;
        ui.set_plaza_pack(PlazaPack::default());
        ui.set_plaza_versions(Default::default());
        ui.set_plaza_detail_error("".into());
        let Some(api) = self.api.clone() else {
            ui.set_plaza_detail_loading(false);
            ui.set_plaza_detail_error("请先登录".into());
            return;
        };
        ui.set_plaza_detail_loading(true);
        let path = format!("/modpack/{}/detail", self.detail_id);
        let epoch = self.detail_epoch;
        let tx = self.tx.clone();
        self.detail_job = Some(self.runtime.spawn(async move {
            let result = api.get(&path).await;
            let _ = tx.send(Reply::Detail(epoch, result));
        }));
    }

    fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        match action {
            "open" if !self.opened => self.restart_search(&ui),
            "search" | "filter" | "clear" if ui.get_pack_screen() == 3 => {
                if action == "clear" {
                    ui.set_plaza_query("".into());
                    ui.set_plaza_filters(PlazaFilters::default());
                }
                if action != "filter" {
                    self.keyword = ui.get_plaza_query().trim().to_owned();
                }
                self.filters = ui.get_plaza_filters();
                self.restart_search(&ui);
            }
            "more" if ui.get_pack_screen() == 3 => self.request_page(&ui),
            "detail" if ui.get_pack_screen() == 3 && self.ids.contains(value) => {
                self.show_detail(&ui, value.to_owned());
            }
            "installed" if ui.get_pack_screen() == 0 => {
                if let Some((id, _)) = local_packs::remote_name(value) {
                    self.show_detail(&ui, id);
                }
            }
            // U4c1: the pack card on a room's details page.
            "pack" if pack_id(value) => self.show_detail(&ui, value.to_owned()),
            // U4c1 D1(b): 创建房间 on a built version opens the room form.
            "create-room" if ui.get_pack_screen() == 5 => {
                let Some(((name, true), detail)) = self.shown_version.clone().zip(self.detail.as_ref())
                else {
                    return;
                };
                let value = serde_json::json!({"modpackId":self.detail_id,"version":name,
                    "name":detail.name,"mcVer":detail.mc_ver});
                ui.global::<crate::Rooms>().invoke_action("create".into(), value.to_string().into());
            }
            "retry-detail" if ui.get_pack_screen() == 4 => self.request_detail(&ui),
            "version" if ui.get_pack_screen() == 4 => self.show_version(&ui, value),
            "mods-search" if ui.get_pack_screen() == 5 => self.render_mods(&ui),
            "copied" if ui.get_pack_screen() == 4 => ui.invoke_notify(format!("已复制{value}").into()),
            "install" if ui.get_pack_screen() == 5 => {
                let Some(((name, true), detail)) = self.shown_version.clone().zip(self.detail.as_ref())
                else {
                    return;
                };
                match self.install_state(&name) {
                    crate::pack_install::State::Installing(run) => {
                        ui.invoke_pack_action("install.open".into(), run.into())
                    }
                    state => {
                        let size = detail
                            .versions
                            .iter()
                            .find(|v| v.name == name)
                            .and_then(|v| v.total_size);
                        let reinstall = state == crate::pack_install::State::Installed;
                        let value = serde_json::json!({"modpackId":self.detail_id,"version":name,
                            "name":detail.name,"size":size,"reinstall":reinstall});
                        ui.invoke_pack_action("install.prompt".into(), value.to_string().into());
                    }
                }
            }
            "source" if ui.get_pack_screen() == 4 => {
                let Some(url) = self
                    .detail
                    .as_ref()
                    .and_then(|d| source_url(d.source_url.as_deref()))
                else {
                    return;
                };
                let weak = self.ui.clone();
                self.runtime.spawn_blocking(move || {
                    if let Err(error) = webbrowser::open(url.as_str()) {
                        tracing::warn!(kind = ?error.kind(), "打开整合包原帖失败");
                        let _ = weak
                            .upgrade_in_event_loop(|ui| ui.invoke_notify("无法打开原帖".into()));
                    }
                });
            }
            _ => {}
        }
    }

    /// The version page (`ModpackVersionInfoScreen` as a player sees it).
    fn show_version(&mut self, ui: &AppWindow, name: &str) {
        let Some((detail, api)) = self.detail.as_ref().zip(self.api.clone()) else {
            return;
        };
        let Ok(uid) = api.uid() else { return };
        let Some(version) = detail
            .versions
            .iter()
            .find(|version| version.name == name && visible(version, &uid, api.is_dav || detail.author_id == uid))
        else {
            return;
        };
        ui.set_plaza_version(version.summary());
        ui.set_plaza_changelog(version.changelog.trim().into());
        ui.set_plaza_base_world(
            match &version.base_world {
                Some(world) if world.required => "必须使用预设世界",
                Some(_) => "可使用预设世界",
                None => "",
            }
            .into(),
        );
        ui.set_plaza_uploaded(version.uploaded().into());
        ui.set_plaza_extras(version.extras().into());
        ui.set_plaza_mod_query("".into());
        self.uploader = version.uploader_id.clone().filter(|id| !id.trim().is_empty()).unwrap_or_else(|| detail.author_id.clone());
        self.version_epoch += 1;
        self.version_mods = version.mods.clone();
        self.shown_version = Some((version.name.clone(), version.status == "OK"));
        let lookup = self.mod_cards.lookup(&api, &self.version_mods);
        ui.set_plaza_mods_hydrating(lookup.is_some());
        if let Some(lookup) = lookup {
            let (epoch, tx) = (self.version_epoch, self.tx.clone());
            self.runtime.spawn(async move {
                let _ = tx.send(Reply::Cards(epoch, lookup.await));
            });
        }
        self.show_uploader(ui, &api);
        self.render_mods(ui);
        self.update_install(ui);
        ui.invoke_navigate("plaza-version".into());
    }

    /// 「by」 the uploader's head and name (`HeadButton(uploaderId, showName = true)`).
    fn show_uploader(&mut self, ui: &AppWindow, api: &Client) {
        if let Some((name, head)) = self.players.get(&self.uploader) {
            ui.set_plaza_uploader_name(name.as_str().into());
            ui.set_plaza_uploader_head(head.clone());
            return;
        }
        ui.set_plaza_uploader_name("…".into());
        ui.set_plaza_uploader_head(steve());
        let (api, uid, tx) = (api.clone(), self.uploader.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = crate::rooms_ui::player(&api, &uid).await;
            let _ = tx.send(Reply::Uploader(uid, result));
        });
    }

    /// The version's mods as cards, filtered by 「搜索Mod」.
    fn render_mods(&mut self, ui: &AppWindow) {
        self.mods_dirty = false;
        self.mods_rendered = Some(Instant::now());
        let query = ui.get_plaza_mod_query().trim().to_lowercase();
        let rows: Vec<PlazaMod> = self
            .version_mods
            .iter()
            .filter(|m| self.mod_cards.matches(m, &query))
            .map(|m| {
                let name = self.mod_cards.name(m);
                let icon = self.mod_cards.icon(m);
                PlazaMod {
                    key: mods::key(m).into(),
                    initial: initial(&name).into(),
                    detail: self.mod_cards.intro(m).into(),
                    name: name.into(),
                    has_icon: icon.is_some(),
                    icon: icon.unwrap_or_default(),
                }
            })
            .collect();
        ui.set_plaza_mod_count(self.version_mods.len() as i32);
        ui.set_plaza_mods(VecModel::from_slice(&rows));
    }

    fn show_page(&mut self, ui: &AppWindow, offset: usize, page: Page) {
        self.next = next_offset(offset, &page);
        ui.set_plaza_more(self.next.is_some());
        let mut icons = Vec::new();
        for card in page.items {
            if !pack_id(&card.id) || !self.ids.insert(card.id.clone()) {
                continue;
            }
            if let Some(url) = card.icon.filter(|url| !url.trim().is_empty()) {
                icons.push((self.cards.row_count(), url));
            }
            let timestamp = if card.last_updated_time > 0 {
                card.last_updated_time
            } else {
                u64::from_str_radix(&card.id[..8], 16).unwrap_or_default() * 1000
            };
            let category_text = categories(&card.categories);
            let update = format!("{}更新", ago(timestamp));
            let metadata = if category_text.is_empty() {
                update
            } else {
                format!("{category_text} · {update}")
            };
            self.cards.push(PlazaCard {
                id: card.id.into(),
                initial: name_placeholder(&card.name),
                name: card.name.into(),
                intro: card.info.unwrap_or_default().into(),
                play_count: format!("{}次游玩", card.play_count).into(),
                metadata: metadata.into(),
                ..Default::default()
            });
        }
        self.load_icons(icons);
    }

    fn load_icons(&mut self, icons: Vec<(usize, String)>) {
        let Some(http) = self
            .api
            .as_ref()
            .map(|api| api.http.clone())
            .filter(|_| !icons.is_empty())
        else {
            return;
        };
        let epoch = self.search_epoch;
        let slots = self.icon_slots.clone();
        let tx = self.tx.clone();
        self.icon_jobs.retain(|job| !job.is_finished());
        self.icon_jobs.push(self.runtime.spawn(async move {
            let mut jobs = JoinSet::new();
            for (index, url) in icons {
                let http = http.clone();
                let slots = slots.clone();
                jobs.spawn(async move {
                    let _slot = slots.acquire_owned().await?;
                    Ok::<_, anyhow::Error>((index, images::icon(&http, url.trim(), 144).await?))
                });
            }
            while let Some(result) = jobs.join_next().await {
                match result {
                    Ok(Ok((index, icon))) => {
                        let _ = tx.send(Reply::Icon(epoch, index, icon));
                    }
                    Ok(Err(error)) => tracing::warn!(%error, "整合广场图标加载失败"),
                    Err(error) => tracing::warn!(%error, "整合广场图标任务失败"),
                }
            }
        }));
    }

    fn show_pack(&mut self, ui: &AppWindow, detail: Detail) -> Result<()> {
        let api = self.api.as_ref().context("请先登录")?;
        let uid = api.uid()?;
        let is_author = api.is_dav || detail.author_id == uid;
        let versions: Vec<_> = detail
            .versions
            .iter()
            .filter(|version| visible(version, &uid, is_author))
            .map(Version::summary)
            .collect();
        let minecraft = local_packs::mc_version_name(&detail.mc_ver).unwrap_or(&detail.mc_ver);
        let loader = match detail.modloader.as_str() {
            "forge" => "Forge",
            "neoforge" => "NeoForge",
            "Fabric" | "fabric" => "Fabric",
            loader => loader,
        };
        ui.set_plaza_pack(PlazaPack {
            id: self.detail_id.as_str().into(),
            minecraft: minecraft.into(),
            name: detail.name.as_str().into(),
            author: detail.author_name.as_str().into(),
            intro: detail.info.as_deref().unwrap_or_default().trim().into(),
            metadata: format!("MC{minecraft} · {loader}").into(),
            categories: categories(&detail.categories).into(),
            has_source: source_url(detail.source_url.as_deref()).is_some(),
        });
        ui.set_plaza_versions(VecModel::from_slice(&versions));
        self.detail = Some(detail);
        Ok(())
    }

    fn install_state(&self, version: &str) -> crate::pack_install::State {
        crate::pack_install::state_in(
            &self.tasks,
            &local_packs::versions_dir(),
            &self.detail_id,
            version,
        )
    }
    /// D2/D7: 安装, 安装中 or 重新安装, only for built versions.
    fn update_install(&mut self, ui: &AppWindow) {
        self.install_checked = std::time::Instant::now();
        let value = match &self.shown_version {
            Some((name, true)) => match self.install_state(name) {
                crate::pack_install::State::Missing => 1,
                crate::pack_install::State::Installing(_) => 2,
                crate::pack_install::State::Installed => 3,
            },
            _ => 0,
        };
        if ui.get_plaza_install() != value {
            ui.set_plaza_install(value);
        }
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        if ui.get_pack_screen() == 5
            && self.install_checked.elapsed() >= std::time::Duration::from_millis(300)
        {
            self.update_install(&ui);
        }
        // Icons arrive one by one; the rows are rebuilt at most every 150ms.
        if self.mods_dirty && self.mods_rendered.is_none_or(|t| t.elapsed() >= Duration::from_millis(150)) {
            self.render_mods(&ui);
        }
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Page(epoch, offset, result) if epoch == self.search_epoch => {
                    self.search_job = None;
                    ui.set_plaza_loading(false);
                    match result {
                        Ok(page) => self.show_page(&ui, offset, page),
                        Err(error) => ui.set_plaza_error(format!("加载整合包失败：{error}").into()),
                    }
                }
                Reply::Detail(epoch, result) if epoch == self.detail_epoch => {
                    self.detail_job = None;
                    ui.set_plaza_detail_loading(false);
                    if let Err(error) = result.and_then(|detail| self.show_pack(&ui, detail)) {
                        ui.set_plaza_detail_error(format!("加载整合包详情失败：{error}").into());
                    }
                }
                Reply::Cards(epoch, cards) if epoch == self.version_epoch => {
                    ui.set_plaza_mods_hydrating(false);
                    let Some(api) = self.api.clone() else { continue };
                    let size = (40.0 * ui.window().scale_factor()).ceil().max(1.0) as u32;
                    for job in self.mod_cards.add_cards(&api, size, cards) {
                        let tx = self.tx.clone();
                        self.runtime.spawn(async move {
                            let (url, icon) = job.await;
                            let _ = tx.send(Reply::ModIcon(url, icon));
                        });
                    }
                    self.mods_dirty = true;
                }
                Reply::ModIcon(url, icon) => self.mods_dirty |= self.mod_cards.add_icon(url, icon),
                Reply::Uploader(uid, result) => {
                    let (name, head) = match result {
                        Ok((name, head)) => (name, images::to_slint(&head)),
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "加载上传者信息失败");
                            ("未知玩家".to_owned(), steve())
                        }
                    };
                    self.players.insert(uid.clone(), (name.clone(), head.clone()));
                    if uid == self.uploader {
                        ui.set_plaza_uploader_name(name.into());
                        ui.set_plaza_uploader_head(head);
                    }
                }
                Reply::Icon(epoch, index, image) if epoch == self.search_epoch => {
                    if let Some(mut card) = self.cards.row_data(index) {
                        card.icon = images::to_slint(&image);
                        card.has_icon = true;
                        self.cards.set_row_data(index, card);
                    }
                }
                _ => {}
            }
        }
    }
}

impl Drop for Plaza {
    fn drop(&mut self) {
        self.cancel_search();
        abort(&mut self.detail_job);
    }
}

/// The default head while the uploader loads or when it cannot be loaded.
fn steve() -> slint::Image {
    images::steve().map(|head| images::to_slint(&head)).unwrap_or_default()
}

fn abort(job: &mut Option<JoinHandle<()>>) {
    if let Some(job) = job.take() {
        job.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn query_uses_kotlin_wire_values_and_server_page_limit() {
        let actual = query(
            "魔法&空岛",
            &PlazaFilters {
                minecraft: 2,
                category: 7,
                sort: 2,
                mine: true,
            },
            60,
        );
        assert_eq!(
            actual,
            [
                ("offset", "60".into()),
                ("limit", "60".into()),
                ("q", "魔法&空岛".into()),
                ("mine", "true".into()),
                ("mcVer", "1.20.1".into()),
                ("category", "VANILLA".into()),
            ]
        );
        assert_eq!(
            query("", &PlazaFilters::default(), 0)[2],
            ("sort", "UPDATED".into())
        );
    }

    #[test]
    fn paging_advances_by_server_rows_even_with_duplicate_ids() {
        let page: Page = serde_json::from_value(json!({
            "items": [
                {"id":"661000000000000000000001","name":"包"},
                {"id":"661000000000000000000001","name":"包"}
            ],
            "hasMore": true
        }))
        .unwrap();
        assert_eq!(next_offset(60, &page), Some(62));
        assert_eq!(
            next_offset(
                62,
                &Page {
                    has_more: true,
                    ..Page::default()
                }
            ),
            None
        );
    }

    #[test]
    fn versions_follow_compose_visibility_with_omitted_defaults() {
        let detail: Detail = serde_json::from_value(json!({
            "name":"包", "authorId":"author", "modloader":"Fabric", "mcVer":"V201",
            "versions":[
                {"name":"1.0","status":"OK","time":1},
                {"name":"2.0","status":"BUILDING","time":2,"uploaderId":"mine"},
                {"name":"3.0","status":"FAIL","time":3},
            ]
        }))
        .unwrap();
        let names = |uid, author| {
            detail
                .versions
                .iter()
                .filter(|v| visible(v, uid, author))
                .map(|v| v.name.as_str())
                .collect::<Vec<_>>()
        };
        assert_eq!(names("mine", false), ["1.0", "2.0"]);
        assert_eq!(names("other", false), ["1.0"]);
        assert_eq!(names("author", true), ["1.0", "2.0", "3.0"]);
    }

    #[test]
    fn version_page_texts_follow_compose() {
        let version: Version = serde_json::from_value(json!({
            "name":"1.0", "status":"OK", "time":0, "totalSize":1536,
            "mods":[{"platform":"cf","projectId":"238222","slug":"jei","fileId":"1","hash":"h"}],
            "clientExtras":[
                {"slug":"faithful","type":"ResPack"},
                {"slug":"bsl","type":"ShaderPack"},
                {"slug":"stay-true","type":"ResPack"}
            ]
        }))
        .unwrap();
        assert_eq!(version.mods.len(), 1);
        assert!(version.uploaded().starts_with("上传 19"), "{}", version.uploaded());
        assert!(version.uploaded().ends_with(" · 1.5KB"), "{}", version.uploaded());
        assert_eq!(version.extras(), "资源包：faithful、stay-true · 光影：bsl");
        assert_eq!(version.summary().state, 0);
        assert_eq!(
            ["BUILDING", "FAIL", "WAIT", "?"].map(status_state),
            [1, 2, 3, 3]
        );
        assert_eq!(date_time(u64::MAX), "");
    }
}
