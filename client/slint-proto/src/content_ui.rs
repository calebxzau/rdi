//! U4e1: wiring of 模组 (the mod catalog) and 资源包/光影包 to the `Content` global. Shaped like
//! the other page wirings: one `action(name, value)` callback, sequence numbers that drop the
//! replies of a page that was left, and a channel drained on the UI thread. The rules are in
//! `content/`; downloads run as tasks in the shared task panel.
use crate::{
    AppWindow, Content, ContentCard, ContentFile, GalleryItem, ResourceCard,
    api::{Client, server::ServerApi},
    catalog::{
        Catalog,
        models::{self as cm, Channel, DependencyRequirement, File, FilePage, FileRef, FileRequest, Mod, Outcome, Platform, ProjectRef, SearchCursor, SearchPage, SearchRequest, Sort},
    },
    content::{
        self, Kind, Target,
        install::{self, Outcome as Placed, Placement},
        modrinth,
        records::{self, ContentType, Record},
        status,
    },
    content_store,
    digests::{Algorithm, Digest},
    download::{Downloader, Source, cancellable},
    images,
    pack_install::mods::{self as legacy, SourceHeaders},
    play::Sessions,
    tasks,
};
use anyhow::{Context as _, Result, anyhow};
use slint::{ComponentHandle, VecModel};
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet},
    rc::Rc,
    sync::{Arc, mpsc},
    time::{Duration, Instant},
};
use tokio::{runtime::Handle, sync::OnceCell};

type CatalogCell = Arc<OnceCell<Arc<Catalog>>>;

/// What a dependency resolution and changelog request found for one file.
#[derive(Clone, Default)]
struct Details {
    changelog: String,
    deps: Vec<Mod>,
    summary: Option<String>,
    problems: Vec<&'static str>,
}

struct Dialog {
    file: File,
    source: Source,
    details: Option<Details>,
    submitting: bool,
    error: String,
}

struct ModDetail {
    item: Mod,
    files: Vec<File>,
    cursor: Option<(Platform, usize)>,
    include_alpha: bool,
    show_other: bool,
    files_loading: bool,
    more_loading: bool,
    latest: Option<(FileRef, Details)>,
    latest_loading: bool,
    resolving: bool,
    dialog: Option<Dialog>,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Art {
    Icon,
    Banner,
    Thumb,
    Preview,
}
impl Art {
    fn key(self, url: &str) -> String {
        let tag = match self {
            Self::Icon => "icon",
            Self::Banner => "banner",
            Self::Thumb => "thumb",
            Self::Preview => "preview",
        };
        format!("{tag}|{url}")
    }
}

enum Reply {
    Search(u64, bool, Result<Outcome<SearchPage>>),
    Resources(u64, bool, Result<modrinth::SearchPage>),
    Mod(u64, Result<Mod>),
    Files(u64, bool, Result<Outcome<FilePage>>),
    Latest(u64, FileRef, Details),
    Installed(u64, Result<(Vec<Record>, HashMap<String, &'static str>)>),
    Resolved(u64, File, Result<Source>),
    DialogDetails(u64, FileRef, Details),
    RoomAdded(u64, Result<()>),
    Project(u64, Result<modrinth::Project>),
    Image(String, Option<image::RgbaImage>),
}

/// Download sources for a catalog mod, in Kotlin order: the RDI copy with the login token, the
/// mirror, the official URL (`ModService.downloadModToPath`).
struct Headers<'a> {
    server: &'a ServerApi,
    catalog: &'a Catalog,
}
impl SourceHeaders for Headers<'_> {
    fn rdi_mod_url(&self, file_name: &str) -> String {
        self.server.url(&format!("/mod/download/{}", crate::pack_install::encode_segment(file_name)))
    }
    fn rdi_headers(&self, url: &str) -> Result<reqwest::header::HeaderMap> {
        self.server.auth_headers(url)
    }
    fn curse_forge_headers(&self, url: &str) -> Result<reqwest::header::HeaderMap> {
        self.catalog.download_headers_for(url)
    }
}

async fn catalog(cell: CatalogCell, http: reqwest::Client) -> Result<Arc<Catalog>> {
    Ok(cell
        .get_or_try_init(|| async { anyhow::Ok(Arc::new(tokio::task::spawn_blocking(move || Catalog::new(http, &crate::paths::data_dir())).await?)) })
        .await?
        .clone())
}

/// `loadCatalogFileDetails`: the changelog and the mods the file requires.
async fn file_details(catalog: &Catalog, file: &File, target: &cm::Target) -> Details {
    let mut details = Details::default();
    match catalog.get_changelog(&file.reference).await {
        Ok(text) => details.changelog = content::readable(&text),
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "加载模组更新说明失败");
            details.problems.push("加载更新说明失败");
        }
    }
    match catalog.resolve_dependencies(std::slice::from_ref(file), target, &[DependencyRequirement::Required]).await {
        Ok(graph) => {
            let files: Vec<File> = content::required_refs(&graph.value, &file.reference)
                .into_iter()
                .filter(|r| r != &file.reference)
                .filter_map(|r| graph.value.nodes.get(&r).map(|(f, _)| f.clone()))
                .collect();
            let mut projects: Vec<ProjectRef> = Vec::new();
            for f in &files {
                if !projects.contains(&f.project) {
                    projects.push(f.project.clone());
                }
            }
            if !projects.is_empty() {
                match catalog.get_mods(&projects).await {
                    Ok(found) => {
                        let mut seen = HashSet::new();
                        details.deps = found.value.into_values().filter(|m| seen.insert(m.identity.clone())).collect();
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "加载依赖模组信息失败");
                        details.problems.push("加载依赖信息失败");
                    }
                }
            }
            details.summary = Some(format!("前置{}个", files.len()));
        }
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "解析模组依赖失败");
            details.problems.push("解析依赖失败");
        }
    }
    details
}

fn global(ui: &AppWindow) -> Content<'_> {
    ui.global::<Content>()
}

fn platform_code(p: Platform) -> &'static str {
    match p {
        Platform::CurseForge => "cf",
        Platform::Modrinth => "mr",
    }
}
fn mod_key(r: &ProjectRef) -> String {
    format!("{}|{}", platform_code(r.platform), r.id)
}
fn parse_key(key: &str) -> Option<ProjectRef> {
    let (platform, id) = key.split_once('|')?;
    let platform = match platform {
        "cf" => Platform::CurseForge,
        "mr" => Platform::Modrinth,
        _ => return None,
    };
    (!id.is_empty()).then(|| ProjectRef { platform, id: id.to_owned() })
}
fn initial(name: &str) -> String {
    name.chars().next().map(|c| c.to_uppercase().to_string()).unwrap_or_else(|| "M".into())
}
fn shown(error: &anyhow::Error) -> String {
    let text = error.to_string();
    if text.trim().is_empty() { "未知错误".into() } else { text }
}

pub struct ContentUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    tasks: tasks::Manager,
    packs: Rc<RefCell<crate::packs::PackUi>>,
    sessions: Sessions,
    api: Option<Client>,
    catalog: CatalogCell,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    kind: Kind,
    target: Option<Target>,
    /// The list's request number; a search or a new page drops older answers.
    seq: u64,
    query: String,
    mods: Vec<Mod>,
    cursor: Option<SearchCursor>,
    resources: Vec<modrinth::Card>,
    has_more: bool,
    searching: bool,
    more_loading: bool,
    list_error: String,
    list_blocked: bool,
    /// The details' request number.
    dseq: u64,
    history: Vec<ProjectRef>,
    detail: Option<ModDetail>,
    detail_error: String,
    notice: String,
    project_id: String,
    project: Option<modrinth::Project>,
    project_loading: bool,
    include_alpha: bool,
    preview: Option<usize>,
    records: Vec<Record>,
    health: HashMap<String, &'static str>,
    images: HashMap<String, slint::Image>,
    requested: HashSet<String>,
    image_slots: Arc<tokio::sync::Semaphore>,
    pending_nav: Vec<&'static str>,
    watch: Option<String>,
    snack_since: Option<Instant>,
    snack_run: Option<String>,
    shown_screen: i32,
    last_check: Instant,
    observed: Vec<Option<u8>>,
    observed_running: bool,
    /// An image arrived; the page is drawn again once per tick.
    images_dirty: bool,
    /// The version each resource task downloads, by task key, so only that version says 下载中.
    resource_versions: HashMap<String, String>,
}

impl ContentUi {
    pub fn wire(ui: &AppWindow, runtime: Handle, packs: Rc<RefCell<crate::packs::PackUi>>) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let (tasks, sessions) = {
            let p = packs.borrow();
            (p.tasks(), p.play.clone())
        };
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            tasks,
            packs,
            sessions,
            api: None,
            catalog: Arc::new(OnceCell::new()),
            tx,
            rx,
            kind: Kind::Mods,
            target: None,
            seq: 0,
            query: String::new(),
            mods: vec![],
            cursor: None,
            resources: vec![],
            has_more: false,
            searching: false,
            more_loading: false,
            list_error: String::new(),
            list_blocked: false,
            dseq: 0,
            history: vec![],
            detail: None,
            detail_error: String::new(),
            notice: String::new(),
            project_id: String::new(),
            project: None,
            project_loading: false,
            include_alpha: false,
            preview: None,
            records: vec![],
            health: HashMap::new(),
            images: HashMap::new(),
            requested: HashSet::new(),
            image_slots: Arc::new(tokio::sync::Semaphore::new(6)),
            pending_nav: vec![],
            watch: None,
            snack_since: None,
            snack_run: None,
            shown_screen: 0,
            last_check: Instant::now(),
            observed: vec![],
            observed_running: false,
            images_dirty: false,
            resource_versions: HashMap::new(),
        }));
        let weak = Rc::downgrade(&state);
        global(ui).on_action(move |action, value| {
            let Some(state) = weak.upgrade() else { return };
            match state.try_borrow_mut() {
                Ok(mut state) => state.action(action.as_str(), value.as_str()),
                Err(_) => tracing::warn!(%action, "模组页面操作重入，已忽略"),
            }
        });
        state
    }

    /// A new sign-in or a sign-out leaves the pages.
    pub fn connect(&mut self, api: Option<Client>) {
        self.api = api;
        self.leave();
        while self.rx.try_recv().is_ok() {}
    }

    fn leave(&mut self) {
        self.seq += 1;
        self.dseq += 1;
        self.target = None;
        self.mods.clear();
        self.resources.clear();
        self.cursor = None;
        self.history.clear();
        self.detail = None;
        self.project = None;
        self.preview = None;
        self.records.clear();
        self.health.clear();
        if let Some(ui) = self.ui.upgrade() {
            let g = global(&ui);
            g.set_install_open(false);
            g.set_preview_open(false);
            g.set_snack("".into());
        }
        self.snack_since = None;
    }

    fn snack(&mut self, ui: &AppWindow, text: &str, run: Option<String>) {
        let g = global(ui);
        g.set_snack(text.into());
        g.set_snack_task(run.is_some());
        self.snack_run = run;
        self.snack_since = Some(Instant::now());
    }

    fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let screen = global(&ui).get_screen();
        match action {
            "open-pack" => self.open_pack(&ui, value),
            "open-room" => self.open_room(&ui, value),
            "back-nav" if screen == 2 && self.history.len() > 1 => self.detail_back(&ui),
            "back-nav" => self.pending_nav.push("back"),
            "detail-back" => self.detail_back(&ui),
            "search" => {
                self.query = global(&ui).get_query().trim().to_owned();
                self.search(&ui, true);
            }
            "more" => self.search(&ui, false),
            "open" if screen == 1 => {
                if let Some(r) = parse_key(value) {
                    self.history.clear();
                    self.open_mod(&ui, r);
                    self.pending_nav.push("content-mod");
                }
            }
            "open" if screen == 3 && !value.is_empty() => {
                self.project_id = value.to_owned();
                self.include_alpha = false;
                self.load_resource(&ui);
                self.pending_nav.push("content-resource");
            }
            "dep" => {
                if let Some(r) = parse_key(value) {
                    if let Some(d) = &mut self.detail {
                        d.dialog = None;
                    }
                    global(&ui).set_install_open(false);
                    self.open_mod(&ui, r);
                }
            }
            "retry" if screen == 2 => self.load_mod(&ui),
            "retry" if screen == 4 => self.load_resource(&ui),
            "alpha" if screen == 2 => {
                if let Some(d) = &mut self.detail {
                    d.include_alpha = !d.include_alpha;
                    self.load_files(&ui, true);
                }
            }
            "alpha" if screen == 4 => {
                self.include_alpha = !self.include_alpha;
                self.render_resource(&ui);
            }
            "mcmod" => {
                if let Some(id) = self.detail.as_ref().and_then(|d| content::mcmod_id(&d.item)) {
                    self.open_url(&format!("https://www.mcmod.cn/class/{id}.html"));
                }
            }
            "show-other" => {
                if let Some(d) = &mut self.detail {
                    d.show_other = true;
                }
                self.render_detail(&ui);
            }
            "more-files" => self.load_files(&ui, false),
            "install" => self.request_install(&ui, value),
            "install-cancel" => self.close_dialog(&ui),
            "install-submit" => self.submit(&ui),
            "source" => {
                if let Some(url) = self.project.as_ref().map(|p| p.source_url.clone()) {
                    self.open_url(&url);
                }
            }
            "version" => self.install_version(&ui, value),
            "gallery" => {
                if let Ok(index) = value.parse::<usize>() {
                    self.preview = Some(index);
                    self.render_preview(&ui);
                }
            }
            "preview-close" => {
                self.preview = None;
                global(&ui).set_preview_open(false);
            }
            "preview-browser" => {
                let url = global(&ui).get_preview_url().to_string();
                if !url.is_empty() {
                    self.open_url(&url);
                }
            }
            "preview-copied" => ui.invoke_notify("已复制链接".into()),
            "snack-task" => {
                if let Some(run) = self.snack_run.take() {
                    self.watch = Some(run);
                    self.pending_nav.push("tasks");
                }
                global(&ui).set_snack("".into());
                self.snack_since = None;
            }
            "snack-close" => {
                global(&ui).set_snack("".into());
                self.snack_since = None;
            }
            "dismiss" => {
                if global(&ui).get_preview_open() {
                    self.preview = None;
                    global(&ui).set_preview_open(false);
                } else {
                    self.close_dialog(&ui);
                }
            }
            _ => {}
        }
    }

    fn open_url(&self, url: &str) {
        let url = url.to_owned();
        self.runtime.spawn_blocking(move || {
            if let Err(error) = webbrowser::open(&url) {
                tracing::warn!(%error, %url, "打开浏览器失败");
            }
        });
    }

    // ---- entries ----

    /// The pack menu's 模组/材质/光影: `kind|versionId`.
    fn open_pack(&mut self, ui: &AppWindow, value: &str) {
        let Some((kind, id)) = value.split_once('|') else { return };
        let Some(kind) = Kind::parse(kind) else { return };
        let Some(pack) = self.packs.borrow().installed(id) else {
            ui.invoke_notify("找不到这个整合包，请刷新整合包列表".into());
            return;
        };
        let target = Target::Local { version_id: pack.id, name: pack.name, version: pack.version, dir: pack.directory, minecraft: pack.minecraft, loader: pack.loader };
        self.open_list(ui, kind, target);
    }

    /// A room's 附加Mod: `{"id","name","minecraft","loader"}`.
    fn open_room(&mut self, ui: &AppWindow, value: &str) {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(value) else { return };
        let text = |k: &str| v[k].as_str().unwrap_or_default().to_owned();
        let target = Target::Room { id: text("id"), name: text("name"), minecraft: text("minecraft"), loader: text("loader") };
        self.open_list(ui, Kind::Mods, target);
    }

    fn open_list(&mut self, ui: &AppWindow, kind: Kind, target: Target) {
        self.leave();
        self.kind = kind;
        self.list_blocked = false;
        self.list_error.clear();
        if kind == Kind::Mods
            && let Err(error) = target.check()
        {
            self.list_blocked = true;
            self.list_error = error.to_string();
        }
        self.target = Some(target);
        self.query.clear();
        let g = global(ui);
        g.set_list_title(kind.label().into());
        g.set_query("".into());
        if self.list_blocked {
            self.render_list(ui);
        } else {
            self.search(ui, true);
        }
        self.pending_nav.push(if kind == Kind::Mods { "content-mods" } else { "content-resources" });
    }

    // ---- lists ----

    fn search(&mut self, ui: &AppWindow, reset: bool) {
        let (Some(target), Some(api)) = (self.target.clone(), self.api.clone()) else { return };
        if self.list_blocked || (!reset && (self.more_loading || self.searching || !self.has_more)) {
            return;
        }
        if reset {
            self.seq += 1;
            self.mods.clear();
            self.resources.clear();
            self.cursor = None;
            self.has_more = false;
            self.searching = true;
            self.more_loading = false;
        } else {
            self.more_loading = true;
        }
        self.list_error.clear();
        let (seq, tx, query) = (self.seq, self.tx.clone(), self.query.clone());
        let (cell, http) = (self.catalog.clone(), api.http.clone());
        match self.kind {
            Kind::Mods => {
                let cursor = if reset { None } else { self.cursor.clone() };
                let request = SearchRequest {
                    sort: if query.is_empty() { Sort::Downloads } else { Sort::Relevance },
                    query,
                    target: target.catalog(),
                    page_size: 20,
                    cursor,
                };
                self.runtime.spawn(async move {
                    let result = async { catalog(cell, http).await?.search(&request).await }.await;
                    let _ = tx.send(Reply::Search(seq, reset, result));
                });
            }
            kind => {
                let offset = if reset { 0 } else { self.resources.len() };
                let minecraft = target.minecraft().to_owned();
                self.runtime.spawn(async move {
                    let result = async {
                        let catalog = catalog(cell, http).await?;
                        let answer = catalog.modrinth_get("search", modrinth::search_query(kind.project_type(), &query, Some(&minecraft), offset)).await?;
                        modrinth::parse_search(&answer, kind.project_type())
                    }
                    .await;
                    let _ = tx.send(Reply::Resources(seq, reset, result));
                });
            }
        }
        self.render_list(ui);
    }

    fn render_list(&mut self, ui: &AppWindow) {
        let g = global(ui);
        g.set_loading(self.searching);
        g.set_loading_more(self.more_loading);
        g.set_has_more(self.has_more && !self.list_blocked);
        g.set_error(self.list_error.as_str().into());
        g.set_empty(if self.list_blocked { "".into() } else { format!("没有找到符合条件的{}", self.kind.label()).into() });
        if self.kind == Kind::Mods {
            let cards: Vec<ContentCard> = self.mods.clone().iter().map(|m| self.card(m)).collect();
            g.set_mods(Rc::new(VecModel::from(cards)).into());
            g.set_resources(Rc::new(VecModel::from(Vec::<ResourceCard>::new())).into());
        } else {
            let cards: Vec<ResourceCard> = self
                .resources
                .clone()
                .iter()
                .map(|c| {
                    let icon = c.icon.as_deref().and_then(|u| self.image(Art::Icon, u));
                    let banner = c.banner.as_deref().and_then(|u| self.image(Art::Banner, u));
                    ResourceCard {
                        key: c.id.as_str().into(),
                        title: c.title.as_str().into(),
                        follows: content::separated_count(c.follows).into(),
                        has_icon: icon.is_some(),
                        icon: icon.unwrap_or_default(),
                        has_banner: banner.is_some(),
                        banner: banner.unwrap_or_default(),
                        initial: initial(&c.title).into(),
                    }
                })
                .collect();
            g.set_resources(Rc::new(VecModel::from(cards)).into());
            g.set_mods(Rc::new(VecModel::from(Vec::<ContentCard>::new())).into());
        }
    }

    fn card(&mut self, m: &Mod) -> ContentCard {
        let name = content::display_name(m);
        let icon = m.icons.first().and_then(|u| self.image(Art::Icon, u));
        ContentCard {
            key: mod_key(&m.primary).into(),
            downloads: content::compact_cn(m.downloads).into(),
            summary: m.summary.split_whitespace().collect::<Vec<_>>().join(" ").into(),
            has_icon: icon.is_some(),
            icon: icon.unwrap_or_default(),
            initial: initial(&name).into(),
            name: name.into(),
        }
    }

    // ---- images ----

    /// The decoded image, or `None` after starting its download.
    fn image(&mut self, art: Art, url: &str) -> Option<slint::Image> {
        let url = url.trim();
        if url.is_empty() {
            return None;
        }
        let key = art.key(url);
        if let Some(image) = self.images.get(&key) {
            return Some(image.clone());
        }
        if !self.requested.insert(key.clone()) {
            return None;
        }
        let http = self.api.as_ref()?.http.clone();
        let (tx, slots, url) = (self.tx.clone(), self.image_slots.clone(), url.to_owned());
        self.runtime.spawn(async move {
            let _slot = slots.acquire_owned().await;
            let result = async {
                if art == Art::Icon {
                    return images::icon(&http, &url, 96).await;
                }
                let image = images::download_image(&http, &url).await?;
                tokio::task::spawn_blocking(move || {
                    use image::imageops::FilterType;
                    Ok(match art {
                        Art::Banner => {
                            let mut out = image.resize_to_fill(640, 284, FilterType::Triangle).to_rgba8();
                            images::round_corners(&mut out, 18.);
                            out
                        }
                        Art::Thumb => {
                            let mut out = image.resize_to_fill(480, 270, FilterType::Triangle).to_rgba8();
                            images::round_corners(&mut out, 22.);
                            out
                        }
                        _ => image.resize(2560, 1440, FilterType::Triangle).to_rgba8(),
                    })
                })
                .await?
            }
            .await;
            let image = match result {
                Ok(image) => Some(image),
                Err(error) => {
                    tracing::debug!(error = %format!("{error:#}"), %url, "图片加载失败");
                    None
                }
            };
            let _ = tx.send(Reply::Image(key, image));
        });
        None
    }

    // ---- mod details ----

    fn open_mod(&mut self, ui: &AppWindow, r: ProjectRef) {
        self.history.push(r);
        self.load_mod(ui);
    }

    fn detail_back(&mut self, ui: &AppWindow) {
        if self.history.len() > 1 {
            self.history.pop();
            self.load_mod(ui);
        } else {
            self.pending_nav.push("back");
        }
    }

    fn load_mod(&mut self, ui: &AppWindow) {
        let (Some(r), Some(api)) = (self.history.last().cloned(), self.api.clone()) else { return };
        self.dseq += 1;
        self.detail = None;
        self.detail_error.clear();
        self.notice.clear();
        self.records.clear();
        self.health.clear();
        let g = global(ui);
        g.set_detail_depth(self.history.len() as i32);
        g.set_install_open(false);
        g.set_title("".into());
        let (seq, tx, cell, http) = (self.dseq, self.tx.clone(), self.catalog.clone(), api.http.clone());
        self.runtime.spawn(async move {
            let result = async {
                let found = catalog(cell, http).await?.get_mods(std::slice::from_ref(&r)).await?;
                found.value.get(&r).cloned().ok_or_else(|| anyhow!("模组不存在"))
            }
            .await;
            let _ = tx.send(Reply::Mod(seq, result));
        });
        self.render_detail(ui);
    }

    fn load_files(&mut self, ui: &AppWindow, reset: bool) {
        let (Some(api), Some(target)) = (self.api.clone(), self.target.clone()) else { return };
        let Some(d) = &mut self.detail else { return };
        let ct = match target.check() {
            Ok(t) => t,
            Err(error) => {
                self.notice = error.to_string();
                self.render_detail(ui);
                return;
            }
        };
        let cursor = if reset {
            d.files_loading = true;
            d.latest = None;
            d.latest_loading = false;
            None
        } else {
            if d.more_loading || d.cursor.is_none() {
                return;
            }
            d.more_loading = true;
            d.cursor
        };
        let request = FileRequest {
            item: d.item.clone(),
            target: ct,
            channels: if d.include_alpha { vec![Channel::Release, Channel::Beta, Channel::Alpha] } else { vec![Channel::Release, Channel::Beta] },
            page_size: 30,
            cursor,
        };
        let (seq, tx, cell, http) = (self.dseq, self.tx.clone(), self.catalog.clone(), api.http.clone());
        self.runtime.spawn(async move {
            let result = async { catalog(cell, http).await?.list_files(&request).await }.await;
            let _ = tx.send(Reply::Files(seq, reset, result));
        });
        self.render_detail(ui);
    }

    fn load_latest(&mut self, ui: &AppWindow) {
        let (Some(api), Some(target)) = (self.api.clone(), self.target.as_ref().map(Target::catalog)) else { return };
        let Some(d) = &mut self.detail else { return };
        let Some(file) = content::latest(&d.files).cloned() else { return };
        d.latest_loading = true;
        let (seq, tx, cell, http) = (self.dseq, self.tx.clone(), self.catalog.clone(), api.http.clone());
        self.runtime.spawn(async move {
            let details = match catalog(cell, http).await {
                Ok(catalog) => file_details(&catalog, &file, &target).await,
                Err(error) => {
                    tracing::warn!(error = %format!("{error:#}"), "模组目录不可用");
                    Details { problems: vec!["加载模组附加信息失败"], ..Default::default() }
                }
            };
            let _ = tx.send(Reply::Latest(seq, file.reference, details));
        });
        self.render_detail(ui);
    }

    /// Reads the pack's records (and, on the mod page, checks the managed jars).
    fn load_records(&mut self) {
        let Some((_, _, dir)) = self.target.as_ref().and_then(Target::local) else { return };
        let dir = dir.to_owned();
        let projects: HashSet<String> = self.detail.as_ref().map(|d| d.item.sources.iter().map(|s| s.reference.id.clone()).collect()).unwrap_or_default();
        let (seq, tx) = (self.dseq, self.tx.clone());
        self.runtime.spawn_blocking(move || {
            let result = records::read(&dir).map(|list| {
                let health = status::health(&dir, &list, &projects);
                (list, health)
            });
            let _ = tx.send(Reply::Installed(seq, result));
        });
    }

    fn file_status(&self, file: &File) -> &'static str {
        match &self.target {
            Some(Target::Local { version_id, .. }) => {
                let task = self.tasks.latest_status(&status::mod_task_key(version_id, &file.project.id));
                status::file_status(file, task, &self.records, &self.health, self.sessions.alive_count(version_id) > 0)
            }
            _ => "",
        }
    }

    fn file_row(&self, file: &File, resolving: bool) -> ContentFile {
        let state = self.file_status(file);
        let detail = format!("{}  {} {}", crate::packs::human_file_size(file.size), content::display_date(&file.published), state);
        ContentFile {
            key: file.reference.id.as_str().into(),
            name: content::file_label(file).into(),
            detail: detail.trim_end().into(),
            enabled: !resolving && status::installable(state),
        }
    }

    fn render_detail(&mut self, ui: &AppWindow) {
        let g = global(ui);
        g.set_notice(self.notice.as_str().into());
        g.set_detail_error(self.detail_error.as_str().into());
        let Some(d) = &self.detail else {
            g.set_detail_loading(self.detail_error.is_empty());
            g.set_has_latest(false);
            g.set_files(Rc::new(VecModel::from(Vec::<ContentFile>::new())).into());
            g.set_has_mcmod(false);
            g.set_busy(false);
            return;
        };
        g.set_detail_loading(false);
        g.set_title(content::display_name(&d.item).into());
        g.set_has_mcmod(content::mcmod_id(&d.item).is_some());
        g.set_include_alpha(d.include_alpha);
        g.set_busy(d.files_loading || d.resolving);
        let latest = content::latest(&d.files).cloned();
        g.set_has_latest(latest.is_some());
        let older: Vec<File> = d.files.iter().filter(|f| Some(&f.reference) != latest.as_ref().map(|l| &l.reference)).cloned().collect();
        let (show_other, cursor, resolving) = (d.show_other, d.cursor.is_some(), d.resolving);
        g.set_other_available(!older.is_empty() || cursor);
        g.set_show_other(show_other);
        g.set_files_more(cursor);
        g.set_files_loading_more(d.more_loading);
        g.set_no_files(!d.files_loading && d.files.is_empty());
        g.set_latest_loading(d.latest_loading);
        let latest_details = d.latest.as_ref().filter(|(r, _)| Some(r) == latest.as_ref().map(|l| &l.reference)).map(|(_, details)| details.clone());
        if let Some(file) = &latest {
            g.set_latest(self.file_row(file, resolving));
        }
        let rows: Vec<ContentFile> = if show_other { older.iter().map(|f| self.file_row(f, resolving)).collect() } else { vec![] };
        g.set_files(Rc::new(VecModel::from(rows)).into());
        g.set_latest_has_details(latest_details.is_some());
        match latest_details {
            Some(details) => {
                g.set_latest_changelog(details.changelog.as_str().into());
                g.set_latest_dep_summary(details.summary.clone().unwrap_or_default().into());
                let deps: Vec<ContentCard> = details.deps.iter().map(|m| self.card(m)).collect();
                global(ui).set_latest_deps(Rc::new(VecModel::from(deps)).into());
            }
            None => {
                g.set_latest_changelog("".into());
                g.set_latest_dep_summary("".into());
                g.set_latest_deps(Rc::new(VecModel::from(Vec::<ContentCard>::new())).into());
            }
        }
        self.render_dialog(ui);
    }

    // ---- install dialog ----

    fn request_install(&mut self, ui: &AppWindow, key: &str) {
        let Some(api) = self.api.clone() else { return };
        if let Some((_, _, dir)) = self.target.as_ref().and_then(Target::local)
            && !dir.is_dir()
        {
            self.snack(ui, "目标整合包已不存在", None);
            return;
        }
        let Some(d) = &mut self.detail else { return };
        if d.resolving {
            return;
        }
        let Some(file) = d.files.iter().find(|f| f.reference.id == key).cloned() else { return };
        d.resolving = true;
        let (seq, tx, cell, http) = (self.dseq, self.tx.clone(), self.catalog.clone(), api.http.clone());
        self.runtime.spawn(async move {
            let result = async { catalog(cell, http).await?.resolve_download(&file) }.await;
            let _ = tx.send(Reply::Resolved(seq, file, result));
        });
        self.render_detail(ui);
    }

    fn close_dialog(&mut self, ui: &AppWindow) {
        if let Some(d) = &mut self.detail {
            if d.dialog.as_ref().is_some_and(|x| x.submitting) {
                return;
            }
            d.dialog = None;
        }
        global(ui).set_install_open(false);
    }

    fn render_dialog(&mut self, ui: &AppWindow) {
        let g = global(ui);
        let Some(dialog) = self.detail.as_ref().and_then(|d| d.dialog.as_ref()) else {
            g.set_install_open(false);
            return;
        };
        let title = format!("安装{}", dialog.file.filename);
        let (error, submitting, details) = (dialog.error.clone(), dialog.submitting, dialog.details.clone());
        g.set_install_title(title.into());
        g.set_install_error(error.into());
        g.set_install_submitting(submitting);
        g.set_install_loading(details.is_none());
        g.set_install_target(self.target.as_ref().map(Target::install_line).unwrap_or_default().into());
        g.set_install_can_submit(details.is_some() && !submitting);
        let details = details.unwrap_or_default();
        g.set_install_dep_summary(details.summary.clone().unwrap_or_default().into());
        g.set_install_changelog(details.changelog.as_str().into());
        let deps: Vec<ContentCard> = details.deps.iter().map(|m| self.card(m)).collect();
        let g = global(ui);
        g.set_install_deps(Rc::new(VecModel::from(deps)).into());
        g.set_install_open(true);
    }

    fn submit(&mut self, ui: &AppWindow) {
        let (Some(target), Some(api)) = (self.target.clone(), self.api.clone()) else { return };
        let (seq, tx) = (self.dseq, self.tx.clone());
        let Some(d) = &mut self.detail else { return };
        let Some(dialog) = &mut d.dialog else { return };
        if dialog.submitting || dialog.details.is_none() {
            return;
        }
        let mod_ = match content::to_legacy(&d.item, &dialog.file, &dialog.source.url) {
            Ok(m) => m,
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), "提交模组安装任务失败");
                dialog.error = "提交安装任务失败，请稍后重试".into();
                self.render_dialog(ui);
                return;
            }
        };
        match target {
            Target::Local { version_id, name, dir, .. } => {
                let Some(run) = self.submit_mod(mod_, version_id, name, dir) else { return };
                if let Some(d) = &mut self.detail {
                    d.dialog = None;
                }
                global(ui).set_install_open(false);
                self.snack(ui, "已加入任务列表", Some(run));
                self.render_detail(ui);
            }
            Target::Room { id, minecraft, .. } => {
                if let Err(error) = content::check_room_version(&minecraft, &dialog.file) {
                    dialog.error = error.to_string();
                    self.render_dialog(ui);
                    return;
                }
                dialog.submitting = true;
                dialog.error.clear();
                self.runtime.spawn(async move {
                    let result = crate::rooms::mods::add_extra(api.server(), &id, &[mod_]).await;
                    let _ = tx.send(Reply::RoomAdded(seq, result));
                });
                self.render_dialog(ui);
            }
        }
    }

    /// `ModCatalogDownloadService.downloadToLocalModpackTask2` for the chosen mod alone
    /// (Compose paused the automatic dependency install).
    fn submit_mod(&self, m: legacy::Mod, version_id: String, name: String, dir: std::path::PathBuf) -> Option<String> {
        let api = self.api.clone()?;
        let (server, http) = (api.server().clone(), api.http.clone());
        let (cell, runtime, sessions) = (self.catalog.clone(), self.runtime.clone(), self.sessions.clone());
        let key = status::mod_task_key(&version_id, &m.project_id);
        Some(self.tasks.submit(&self.runtime, format!("安装1个Mod到{name}"), key, move |ctx| {
            let catalog = runtime.block_on(catalog(cell, http.clone()))?;
            let data = crate::paths::data_dir();
            let store = content_store::Store { root: data.join("dlc"), downloader: Downloader { http } };
            let prefer = crate::catalog::transport::prefer_mirror(&data.join("config.toml"));
            let sources = legacy::sources(&m, None, prefer, &Headers { server: &server, catalog: &catalog })?;
            let request = legacy::request(&m, sources)?;
            let placement = Placement {
                kind: ContentType::Mod,
                source: m.platform.clone(),
                project_id: m.project_id.clone(),
                version_id: m.file_id.clone(),
                file_name: m.file_name(),
                hash: m.hash.clone(),
                algorithm: legacy::algorithm(&m.platform, &m.hash),
                dir: "mods",
                backup: format!("mod-{}-{}.backup", m.platform, install::safe_segment(&m.project_id)),
                label: "Mod",
            };
            let running = sessions.alive_count(&version_id) > 0;
            let outcome = install::place(&dir, &placement, running, |staging| {
                runtime.block_on(cancellable(
                    &ctx.cancel,
                    store.materialize(std::slice::from_ref(&request), staging, &ctx.cancel, &|p| {
                        let (message, fraction) = crate::pack_install::bytes_message(&p);
                        ctx.progress(message, fraction);
                    }),
                ))
            })?;
            ctx.finish(match outcome {
                Placed::AlreadyThere => "所有Mod均已安装".to_owned(),
                Placed::Installed if running => "已安装1个Mod，重启游戏后生效".to_owned(),
                Placed::Installed => format!("已安装1个Mod到{name}"),
            });
            Ok(())
        }))
    }

    // ---- 资源包/光影包 details ----

    fn load_resource(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        self.dseq += 1;
        self.project = None;
        self.project_loading = true;
        self.detail_error.clear();
        self.notice.clear();
        self.preview = None;
        self.records.clear();
        let id = self.project_id.clone();
        let (seq, tx, cell, http) = (self.dseq, self.tx.clone(), self.catalog.clone(), api.http.clone());
        self.runtime.spawn(async move {
            let result = async {
                let catalog = catalog(cell, http).await?;
                let path = format!("project/{}", crate::pack_install::encode_segment(&id));
                let mut project = modrinth::parse_project(&catalog.modrinth_get(&path, vec![]).await?)?;
                project.versions = modrinth::parse_versions(&catalog.modrinth_get(&format!("{path}/version"), vec![]).await?)?;
                // `ModCatalogLocalization.title`: the Chinese name when the index knows the slug.
                if let Ok(found) = catalog.get_metadata(&[(Platform::Modrinth, project.slug.clone())])
                    && let Some(name) = found.values().next().and_then(|m| m.name_cn.clone()).filter(|n| !n.trim().is_empty())
                {
                    project.title = name.trim().to_owned();
                }
                Ok(project)
            }
            .await;
            let _ = tx.send(Reply::Project(seq, result));
        });
        self.load_records();
        self.render_resource(ui);
    }

    fn resource_task(&self) -> Option<u8> {
        let (version_id, _, _) = self.target.as_ref().and_then(Target::local)?;
        self.tasks.latest_status(&status::resource_task_key(self.kind.dir(), version_id, &self.project_id))
    }

    fn render_resource(&mut self, ui: &AppWindow) {
        let g = global(ui);
        g.set_notice(self.notice.as_str().into());
        g.set_detail_error(self.detail_error.as_str().into());
        g.set_detail_loading(self.project_loading);
        g.set_include_alpha(self.include_alpha);
        let Some(project) = self.project.clone() else {
            g.set_title(self.kind.label().into());
            g.set_has_source(false);
            g.set_versions(Rc::new(VecModel::from(Vec::<ContentFile>::new())).into());
            g.set_gallery(Rc::new(VecModel::from(Vec::<GalleryItem>::new())).into());
            return;
        };
        g.set_title(project.title.as_str().into());
        g.set_has_source(true);
        let record = records::find(&self.records, self.kind.record_type(), "modrinth", &project.id).cloned();
        let downloading = status::task_status(self.resource_task()) == Some(status::DOWNLOADING);
        let downloading_version = self
            .target
            .as_ref()
            .and_then(Target::local)
            .and_then(|(version_id, _, _)| self.resource_versions.get(&status::resource_task_key(self.kind.dir(), version_id, &project.id)).cloned());
        let minecraft = self.target.as_ref().map(|t| t.minecraft().to_owned()).unwrap_or_default();
        let rows: Vec<ContentFile> = modrinth::compatible(&project.versions, &minecraft, self.include_alpha)
            .into_iter()
            .map(|v| {
                let detail = if record.as_ref().is_some_and(|r| r.version_id == v.id) {
                    status::INSTALLED
                } else if downloading && downloading_version.as_deref() == Some(v.id.as_str()) {
                    status::DOWNLOADING
                } else {
                    ""
                };
                ContentFile { key: v.id.as_str().into(), name: v.number.as_str().into(), detail: detail.into(), enabled: record.is_none() && !downloading && v.file.is_some() }
            })
            .collect();
        g.set_versions(Rc::new(VecModel::from(rows)).into());
        let gallery: Vec<GalleryItem> = project
            .gallery
            .iter()
            .enumerate()
            .map(|(i, image)| {
                let thumb = self.image(Art::Thumb, &image.url);
                GalleryItem { key: i.to_string().into(), loaded: thumb.is_some(), image: thumb.unwrap_or_default() }
            })
            .collect();
        global(ui).set_gallery(Rc::new(VecModel::from(gallery)).into());
        self.render_preview(ui);
    }

    fn render_preview(&mut self, ui: &AppWindow) {
        let Some(image) = self.preview.and_then(|i| self.project.as_ref().and_then(|p| p.gallery.get(i).cloned())) else {
            global(ui).set_preview_open(false);
            return;
        };
        let url = image.raw_url.clone().unwrap_or_else(|| image.url.clone());
        let full = self.image(Art::Preview, &url);
        let g = global(ui);
        g.set_preview_url(url.into());
        g.set_preview_name(image.name.unwrap_or_default().into());
        g.set_preview_loaded(full.is_some());
        g.set_preview_image(full.unwrap_or_default());
        g.set_preview_open(true);
    }

    /// `ModrinthProjectDownloadService.downloadProjectFileTask2`.
    fn install_version(&mut self, ui: &AppWindow, version_id: &str) {
        let Some((pack_version, name, dir)) = self.target.as_ref().and_then(Target::local).map(|(v, n, d)| (v.to_owned(), n.to_owned(), d.to_owned())) else { return };
        let Some(project) = &self.project else { return };
        let Some(version) = project.versions.iter().find(|v| v.id == version_id).cloned() else { return };
        let Some(file) = version.file.clone() else {
            self.notice = "该版本没有可安装文件".into();
            self.render_resource(ui);
            return;
        };
        if !dir.is_dir() {
            self.snack(ui, "目标整合包已不存在", None);
            return;
        }
        let (kind, project_id, sessions) = (self.kind, project.id.clone(), self.sessions.clone());
        let Some(http) = self.api.as_ref().map(|a| a.http.clone()) else { return };
        let runtime = self.runtime.clone();
        let key = status::resource_task_key(kind.dir(), &pack_version, &project_id);
        if self.tasks.active(&key).is_none() {
            self.resource_versions.insert(key.clone(), version_id.to_owned());
        }
        let run = self.tasks.submit(&self.runtime, format!("下载{} {}", kind.label(), file.file_name), key, move |ctx| {
            let file_name = install::safe_file_name(&file.file_name);
            let sha1 = file.sha1.clone().context("当前文件缺少SHA1，无法安全安装")?;
            let request = content_store::Request {
                id: format!("modrinth:{project_id}:{}:{file_name}", version.id),
                relative_path: file_name.clone(),
                size: file.size,
                digests: vec![Digest::new(Algorithm::Sha1, &sha1)?],
                sources: vec![Source { url: file.url.clone(), headers: Default::default() }],
            };
            let store = content_store::Store { root: crate::paths::data_dir().join("dlc"), downloader: Downloader { http } };
            let existing = records::find(&records::read(&dir)?, kind.record_type(), "modrinth", &project_id).is_some();
            let running = sessions.alive_count(&pack_version) > 0;
            let placement = Placement {
                kind: kind.record_type(),
                source: "modrinth".into(),
                project_id: project_id.clone(),
                version_id: version.id.clone(),
                file_name: file_name.clone(),
                hash: sha1,
                algorithm: Algorithm::Sha1,
                dir: kind.dir(),
                backup: format!("{}.backup", install::safe_segment(&project_id)),
                label: kind.label(),
            };
            let outcome = install::place(&dir, &placement, running, |staging| {
                runtime.block_on(cancellable(
                    &ctx.cancel,
                    store.materialize(std::slice::from_ref(&request), staging, &ctx.cancel, &|p| {
                        let (message, fraction) = crate::pack_install::bytes_message(&p);
                        ctx.progress(message, fraction);
                    }),
                ))
            })?;
            ctx.finish(match outcome {
                Placed::AlreadyThere => format!("{}已存在: {file_name}", kind.label()),
                Placed::Installed if !existing && running => "已安装，重启游戏后生效".to_owned(),
                Placed::Installed => format!("已下载到{name}的{}目录", kind.dir()),
            });
            Ok(())
        });
        self.snack(ui, "已加入任务列表", Some(run));
        self.render_resource(ui);
    }

    // ---- replies ----

    fn reply(&mut self, ui: &AppWindow, reply: Reply) {
        match reply {
            Reply::Search(seq, reset, result) if seq == self.seq => {
                self.searching = false;
                self.more_loading = false;
                match result {
                    Ok(outcome) => {
                        if reset {
                            self.mods.clear();
                        }
                        let mut seen: HashSet<String> = self.mods.iter().map(|m| m.identity.clone()).collect();
                        self.mods.extend(outcome.value.items.into_iter().filter(|m| seen.insert(m.identity.clone())));
                        self.cursor = outcome.value.cursor;
                        self.has_more = self.cursor.is_some();
                        if !outcome.issues.is_empty() {
                            self.list_error = "部分目录信息暂时不可用，已显示可用结果".into();
                        }
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "加载模组目录失败");
                        if reset {
                            self.mods.clear();
                            self.cursor = None;
                            self.has_more = false;
                        }
                        self.list_error = "加载模组失败，请稍后重试".into();
                    }
                }
                self.render_list(ui);
            }
            Reply::Resources(seq, reset, result) if seq == self.seq => {
                self.searching = false;
                self.more_loading = false;
                match result {
                    Ok(page) => {
                        if reset {
                            self.resources.clear();
                        }
                        self.resources.extend(page.cards);
                        self.has_more = page.has_more;
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "加载资源包或光影包失败");
                        if reset {
                            self.resources.clear();
                            self.has_more = false;
                        }
                        self.list_error = format!("加载{}失败: {}", self.kind.label(), shown(&error));
                    }
                }
                self.render_list(ui);
            }
            Reply::Mod(seq, result) if seq == self.dseq => {
                match result {
                    Ok(item) => {
                        self.detail = Some(ModDetail {
                            item,
                            files: vec![],
                            cursor: None,
                            include_alpha: false,
                            show_other: false,
                            files_loading: false,
                            more_loading: false,
                            latest: None,
                            latest_loading: false,
                            resolving: false,
                            dialog: None,
                        });
                        self.load_files(ui, true);
                        self.load_records();
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "加载模组详情失败");
                        self.detail_error = shown(&error);
                    }
                }
                self.render_detail(ui);
            }
            Reply::Files(seq, reset, result) if seq == self.dseq => {
                let Some(d) = &mut self.detail else { return };
                d.files_loading = false;
                d.more_loading = false;
                let before = content::latest(&d.files).map(|f| f.reference.clone());
                match result {
                    Ok(outcome) => {
                        if reset {
                            d.files.clear();
                        }
                        d.files.extend(outcome.value.items);
                        content::sort_files(&mut d.files);
                        d.cursor = outcome.value.cursor;
                        if !outcome.issues.is_empty() {
                            self.notice = "部分目录信息暂时不可用，已自动选择可用结果".into();
                        }
                        let after = content::latest(&d.files).map(|f| f.reference.clone());
                        if reset || after != before {
                            self.load_latest(ui);
                        }
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "加载模组可用版本失败");
                        self.notice = "加载可用版本失败，请稍后重试".into();
                    }
                }
                self.render_detail(ui);
            }
            Reply::Latest(seq, file, details) if seq == self.dseq => {
                if let Some(problem) = details.problems.first() {
                    self.notice = (*problem).into();
                }
                if let Some(d) = &mut self.detail {
                    d.latest_loading = false;
                    d.latest = Some((file, details));
                }
                self.render_detail(ui);
            }
            Reply::Installed(seq, result) if seq == self.dseq => {
                match result {
                    Ok((list, health)) => {
                        self.records = list;
                        self.health = health;
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "读取本地安装状态失败");
                        self.notice = format!("读取安装状态失败: {}", shown(&error));
                    }
                }
                self.render_current(ui);
            }
            Reply::Resolved(seq, file, result) if seq == self.dseq => {
                let Some(d) = &mut self.detail else { return };
                d.resolving = false;
                match result {
                    Ok(source) => {
                        d.dialog = Some(Dialog { file: file.clone(), source, details: None, submitting: false, error: String::new() });
                        if let (Some(api), Some(target)) = (self.api.clone(), self.target.as_ref().map(Target::catalog)) {
                            let (tx, cell, http) = (self.tx.clone(), self.catalog.clone(), api.http.clone());
                            self.runtime.spawn(async move {
                                let details = match catalog(cell, http).await {
                                    Ok(catalog) => file_details(&catalog, &file, &target).await,
                                    Err(_) => Details { problems: vec!["加载模组附加信息失败"], ..Default::default() },
                                };
                                let _ = tx.send(Reply::DialogDetails(seq, file.reference, details));
                            });
                        }
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "解析模组下载信息失败");
                        self.snack(ui, "无法获取下载信息，请稍后重试", None);
                    }
                }
                self.render_detail(ui);
            }
            Reply::DialogDetails(seq, file, details) if seq == self.dseq => {
                if let Some(dialog) = self.detail.as_mut().and_then(|d| d.dialog.as_mut())
                    && dialog.file.reference == file
                {
                    if let Some(problem) = details.problems.first() {
                        dialog.error = (*problem).into();
                    }
                    dialog.details = Some(details);
                }
                self.render_dialog(ui);
            }
            Reply::RoomAdded(seq, result) if seq == self.dseq => {
                let Some(dialog) = self.detail.as_mut().and_then(|d| d.dialog.as_mut()) else { return };
                match result {
                    Ok(()) => {
                        if let Some(d) = &mut self.detail {
                            d.dialog = None;
                        }
                        global(ui).set_install_open(false);
                        self.snack(ui, "已提交附加Mod添加任务，请在邮件中查看进度", None);
                    }
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "提交房间附加Mod失败");
                        dialog.submitting = false;
                        dialog.error = format!("提交安装任务失败: {}", shown(&error));
                        self.render_dialog(ui);
                    }
                }
            }
            Reply::Project(seq, result) if seq == self.dseq => {
                self.project_loading = false;
                match result {
                    Ok(project) => self.project = Some(project),
                    Err(error) => {
                        tracing::warn!(error = %format!("{error:#}"), "加载资源详情失败");
                        self.detail_error = format!("加载{}详情失败: {}", self.kind.label(), shown(&error));
                    }
                }
                self.render_resource(ui);
            }
            Reply::Image(key, Some(image)) => {
                self.images.insert(key, images::to_slint(&image));
                self.images_dirty = true;
            }
            _ => {}
        }
    }

    fn render_current(&mut self, ui: &AppWindow) {
        match global(ui).get_screen() {
            1 | 3 => self.render_list(ui),
            2 => self.render_detail(ui),
            4 => self.render_resource(ui),
            _ => {}
        }
    }

    /// Re-renders the state lines when a task or a game changes, and rereads the records once
    /// a task of this page ends (`observeTaskEntries`).
    fn watch_tasks(&mut self, ui: &AppWindow) {
        if self.last_check.elapsed() < Duration::from_millis(500) {
            return;
        }
        self.last_check = Instant::now();
        let screen = global(ui).get_screen();
        let Some((version_id, _, _)) = self.target.as_ref().and_then(Target::local) else { return };
        let version_id = version_id.to_owned();
        let keys: Vec<String> = match screen {
            2 => self.detail.as_ref().map(|d| d.files.iter().map(|f| status::mod_task_key(&version_id, &f.project.id)).collect::<HashSet<_>>().into_iter().collect()).unwrap_or_default(),
            4 => vec![status::resource_task_key(self.kind.dir(), &version_id, &self.project_id)],
            _ => return,
        };
        let now: Vec<Option<u8>> = keys.iter().map(|k| self.tasks.latest_status(k)).collect();
        let running = self.sessions.alive_count(&version_id) > 0;
        if now == self.observed && running == self.observed_running {
            return;
        }
        let ended = now.iter().zip(self.observed.iter().chain(std::iter::repeat(&None))).any(|(n, o)| matches!(n, Some(s) if *s >= 2) && n != o);
        self.observed = now;
        self.observed_running = running;
        if ended {
            self.load_records();
        }
        self.render_current(ui);
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        let screen = global(&ui).get_screen();
        if screen == 0 && self.shown_screen != 0 && self.pending_nav.is_empty() {
            self.leave();
        }
        self.shown_screen = screen;
        for action in std::mem::take(&mut self.pending_nav) {
            if ui.get_modal_open() {
                self.pending_nav.push(action);
                continue;
            }
            ui.invoke_navigate(action.into());
            if action == "tasks"
                && let Some(run) = self.watch.take()
            {
                ui.invoke_pack_action("tasks.watch".into(), run.into());
            }
        }
        while let Ok(reply) = self.rx.try_recv() {
            self.reply(&ui, reply);
        }
        if std::mem::take(&mut self.images_dirty) {
            self.render_current(&ui);
        }
        self.watch_tasks(&ui);
        if self.snack_since.is_some_and(|t| t.elapsed() >= Duration::from_secs(5)) {
            global(&ui).set_snack("".into());
            self.snack_since = None;
            self.snack_run = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mod_keys_round_trip() {
        let r = ProjectRef { platform: Platform::CurseForge, id: "238222".into() };
        assert_eq!(mod_key(&r), "cf|238222");
        assert_eq!(parse_key(&mod_key(&r)), Some(r));
        assert_eq!(parse_key("gh|x"), None);
        assert_eq!(parse_key("mr|"), None);
        assert_eq!(initial("jei"), "J");
        assert_eq!(initial(""), "M");
    }
}
