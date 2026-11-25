//! Stage A5a: the upload draft of owner decision E6. `upload_modpack_check` makes one and a
//! launcher task reads the pack into it (Kotlin `loadClientPack` with `prepareClientPack`);
//! then the agent reads the mods with their jar files, sets sides (`updateModSide`), adds a
//! server pack (`loadServerPack`) and downloads the jars that are not local yet
//! (`queueMissingModDownload`).
//!
//! Why the draft exists (owner, 2026-11-25): authors' side tags are often wrong, so the agent
//! checks each jar's bytecode with its own JDK tools to tell client and server mods apart. The
//! draft therefore names every jar's file in the content store.
//!
//! One draft at a time, as the Compose page holds one pack. A draft untouched for `IDLE` goes,
//! and so does its work folder.
use super::reader::{self, Loaded};
use super::server_pack::LoadedServer;
use super::source::Summary;
use super::{api, draft, jar_meta, manifest, platform, prepare};
use crate::pack_install::Deps;
use crate::pack_install::mods::{Mod, Side};
use anyhow::{Result, bail};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

pub const IDLE: Duration = Duration::from_secs(60 * 60);
const DIR_PREFIX: &str = "upload-draft-";
pub const GONE: &str = "上传草稿不存在或已过期，请重新调用upload_modpack_check";
/// `MODPACK_UPLOAD_MCA_WARNING`.
pub const MCA_WARNING: &str = "可能含有内置地图，请创建地图模板，详见说明书。";
const READING: &str = "整合包正在读取中，请等读取任务完成后再试";

#[derive(Clone, Debug, PartialEq)]
pub enum Phase {
    /// A launcher task reads the client pack, or the server pack when `server`.
    Reading { task: String, server: bool },
    Ready,
    /// Reading the client pack failed; only a new check helps.
    Failed(String),
}

#[derive(Debug)]
pub struct ServerPack {
    /// `buildServerPackName`.
    pub name: String,
    /// Its extra files go into the archive under `server/`.
    pub loaded: LoadedServer,
}

#[derive(Debug)]
pub struct Pack {
    pub loaded: Loaded,
    /// The page's `uiMods`: the client pack's mods, merged with the server pack's once one is
    /// read, with the sides the agent set.
    pub mods: Vec<Mod>,
    pub fabric: bool,
    pub server: Option<ServerPack>,
    pub warnings: Vec<String>,
    /// `fabricEnvironmentSide`: Fabric mods whose jar declares its side; theirs stays.
    pub locked: BTreeSet<String>,
    /// Jar facts by `stable_key`, read once the jar is in the content store.
    meta: BTreeMap<String, jar_meta::JarMeta>,
}

/// Fabric mods whose cached jar declares `client` or `server` (`availableFabricSides`).
fn declared_sides(cache: &Path, mods: &[Mod], fabric: bool) -> BTreeSet<String> {
    if !fabric {
        return BTreeSet::new();
    }
    mods.iter()
        .filter(|m| prepare::cached(cache, m).and_then(|file| jar_meta::read(&file)).and_then(|meta| meta.side).is_some())
        .map(prepare::stable_key)
        .collect()
}

impl Pack {
    fn new(loaded: Loaded, cache: &Path, warnings: Vec<String>) -> Self {
        let fabric = loaded.summary.loader == "Fabric";
        let mut all = warnings;
        if loaded.contains_mca {
            all.insert(0, MCA_WARNING.to_owned());
        }
        let locked = declared_sides(cache, &loaded.mods, fabric);
        Self { mods: loaded.mods.clone(), loaded, fabric, server: None, warnings: all, locked, meta: BTreeMap::new() }
    }

    /// `applyServerPack`.
    fn apply_server(&mut self, cache: &Path, loaded: LoadedServer, merged: Vec<Mod>) {
        if loaded.contains_mca && !self.warnings.iter().any(|w| w == MCA_WARNING) {
            self.warnings.insert(0, MCA_WARNING.to_owned());
        }
        let mut name = format!("已选择服务端({}个mod", loaded.mods.len());
        if !loaded.extras.is_empty() {
            name.push_str(&format!("+{}个额外文件", loaded.extras.len()));
        }
        name.push(')');
        self.locked = declared_sides(cache, &merged, self.fabric);
        self.mods = merged;
        self.server = Some(ServerPack { name, loaded });
    }
}

pub struct Draft {
    pub id: String,
    pub path: PathBuf,
    pub summary: Summary,
    pub form: draft::Draft,
    pub update: Option<api::Target>,
    pub problems: Vec<String>,
    pub preflight: Option<Result<(), String>>,
    pub work_dir: PathBuf,
    pub phase: Phase,
    pub pack: Option<Pack>,
    /// Why the last server pack read failed.
    pub notice: Option<String>,
    /// The content store's folder, where the jars are.
    pub cache: PathBuf,
    touched: Instant,
}

/// Side names of the tools.
pub fn side_name(side: Side) -> &'static str {
    match side {
        Side::Client => "client",
        Side::Server => "server",
        Side::Both => "both",
        Side::Unknown => "unknown",
    }
}

pub fn parse_side(text: &str) -> Result<Side> {
    Ok(match text.trim().to_ascii_lowercase().as_str() {
        "client" => Side::Client,
        "server" => Side::Server,
        "both" => Side::Both,
        other => bail!("运行端必须是client、server或both：{other}"),
    })
}

impl Draft {
    pub(super) fn pack_mut(&mut self) -> Result<&mut Pack> {
        match &self.phase {
            Phase::Reading { .. } => bail!(READING),
            Phase::Failed(reason) => bail!("读取整合包失败：{reason}，请重新调用upload_modpack_check"),
            Phase::Ready => {}
        }
        self.pack.as_mut().ok_or_else(|| anyhow::anyhow!(READING))
    }

    /// `updateModSide` for each change; all of them are checked before any is applied. A mod is
    /// named by its `id` from the draft, or by its slug when no other mod has it.
    pub fn set_sides(&mut self, changes: &[(String, Side)]) -> Result<usize> {
        let pack = self.pack_mut()?;
        let mut found: Vec<(usize, Side)> = Vec::new();
        let mut problems: Vec<String> = Vec::new();
        for (name, side) in changes {
            let by_id: Vec<usize> = (0..pack.mods.len()).filter(|i| prepare::stable_key(&pack.mods[*i]) == *name).collect();
            let candidates = if by_id.is_empty() {
                (0..pack.mods.len()).filter(|i| pack.mods[*i].slug.eq_ignore_ascii_case(name.trim())).collect()
            } else {
                by_id
            };
            match candidates.as_slice() {
                [] => problems.push(format!("找不到Mod：{name}")),
                [index] if pack.locked.contains(&prepare::stable_key(&pack.mods[*index])) => {
                    problems.push(format!("{}的运行端由Mod文件(fabric.mod.json)声明，不能修改", pack.mods[*index].slug))
                }
                [index] => found.push((*index, *side)),
                _ => problems.push(format!("有多个Mod叫{name}，请用id指定")),
            }
        }
        if !problems.is_empty() {
            bail!(problems.join("；"));
        }
        let before: Vec<Side> = pack.mods.iter().map(|m| m.side).collect();
        for (index, side) in &found {
            pack.mods[*index].side = *side;
        }
        let touched: BTreeSet<usize> = found.iter().map(|(index, _)| *index).collect();
        Ok(touched.into_iter().filter(|index| pack.mods[*index].side != before[*index]).count())
    }

    /// The draft for the agent. Jar facts are read here once per jar that is local.
    pub fn view(&mut self) -> Value {
        let cache = self.cache.clone();
        let cache = cache.as_path();
        let status = match &self.phase {
            Phase::Reading { task, server } => json!({"reading": {"task_id": task, "what": if *server { "server_pack" } else { "modpack" }}}),
            Phase::Ready => json!("ready"),
            Phase::Failed(reason) => json!({"failed": reason}),
        };
        let preflight = match &self.preflight {
            None => json!("not_run"),
            Some(Ok(())) => json!("passed"),
            Some(Err(message)) => json!({"failed": message}),
        };
        let s = &self.summary;
        let mut value = json!({
            "draft_id": self.id,
            "status": status,
            "pack": {"format": s.kind.name(), "name": s.name, "version": s.version,
                "minecraft": crate::import::scanner::version_label(&s.mc_version), "loader": s.loader, "path": self.path.display().to_string()},
            "upload_as": match &self.update {
                Some(target) => json!({"new_version_of": {"id": target.id, "name": target.name}}),
                None => json!("new_modpack"),
            },
            "form": {"name": self.form.name, "version": self.form.version, "categories": self.form.categories,
                "info": self.form.info, "icon_url": self.form.icon_url, "source_url": self.form.source_url},
            "problems": self.problems,
            "preflight": preflight,
            "notice": self.notice,
        });
        if let Some(pack) = &mut self.pack {
            let mut missing = 0;
            let mods: Vec<Value> = pack
                .mods
                .iter()
                .map(|m| {
                    let key = prepare::stable_key(m);
                    let jar = prepare::cached(cache, m);
                    if jar.is_none() {
                        missing += 1;
                    }
                    if let Some(file) = &jar
                        && !pack.meta.contains_key(&key)
                        && let Some(meta) = jar_meta::read(file)
                    {
                        pack.meta.insert(key.clone(), meta);
                    }
                    let meta = pack.meta.get(&key);
                    json!({
                        "id": key,
                        "slug": m.slug,
                        "platform": m.platform,
                        "side": side_name(m.side),
                        "side_locked": pack.locked.contains(&key),
                        "mod_id": meta.and_then(|meta| meta.mod_id.clone()),
                        "mod_version": meta.and_then(|meta| meta.version.clone()),
                        "jar": jar.map(|file| file.display().to_string()),
                        "file_in_pack": pack.loaded.embedded_names.get(&m.file_name()),
                        "client_only_override": m.client_only_override,
                    })
                })
                .collect();
            value["mods"] = json!(mods);
            value["jars_missing"] = json!(missing);
            value["warnings"] = json!(pack.warnings);
            value["extras"] = json!(pack.loaded.extras.len());
            value["server_pack"] = json!(pack.server.as_ref().map(|s| s.name.clone()));
        }
        value
    }
}

#[derive(Clone)]
pub struct Drafts {
    slot: Arc<Mutex<Option<Draft>>>,
    /// Work folders are `upload-draft-<id>` here.
    root: PathBuf,
}

/// What the caller must do after a draft went away: cancel its running read, whose task then
/// removes the work folder.
#[must_use]
pub struct Dropped(pub Option<String>);

fn remove_dir(path: &Path) {
    reader::remove_tree(path);
}

impl Drafts {
    pub fn new(root: PathBuf) -> Self {
        Self { slot: Arc::new(Mutex::new(None)), root }
    }

    /// The launcher's drafts, under `pack-proc` as Kotlin's work files. Folders of drafts from
    /// an earlier run are removed: no task of this run uses them yet.
    pub fn launcher() -> Self {
        let root = crate::paths::data_dir().join("pack-proc");
        if let Ok(entries) = std::fs::read_dir(&root) {
            for entry in entries.flatten() {
                if entry.file_name().to_string_lossy().starts_with(DIR_PREFIX) {
                    remove_dir(&entry.path());
                }
            }
        }
        Self::new(root)
    }

    fn lock(&self) -> MutexGuard<'_, Option<Draft>> {
        let mut slot = self.slot.lock().unwrap_or_else(PoisonError::into_inner);
        let idle = slot.as_ref().is_some_and(|d| d.touched.elapsed() > IDLE && !matches!(d.phase, Phase::Reading { .. }));
        if idle && let Some(draft) = slot.take() {
            tracing::info!(draft = %draft.id, "上传草稿已过期");
            remove_dir(&draft.work_dir);
        }
        slot
    }

    fn release(draft: Draft) -> Dropped {
        match draft.phase {
            Phase::Reading { task, .. } => Dropped(Some(task)),
            _ => {
                remove_dir(&draft.work_dir);
                Dropped(None)
            }
        }
    }

    /// A new draft from a check; the one before goes.
    pub fn create(&self, check: super::Check, path: PathBuf, cache: PathBuf) -> (String, Dropped) {
        let id = uuid::Uuid::now_v7().simple().to_string();
        let draft = Draft {
            work_dir: self.root.join(format!("{DIR_PREFIX}{id}")),
            id: id.clone(),
            path,
            summary: check.summary,
            form: check.draft,
            update: check.update,
            problems: check.problems,
            preflight: check.preflight,
            phase: Phase::Ready,
            pack: None,
            notice: None,
            cache,
            touched: Instant::now(),
        };
        let old = self.lock().replace(draft);
        (id, old.map(Self::release).unwrap_or(Dropped(None)))
    }

    /// Runs `f` on the draft `id`.
    pub fn with<T>(&self, id: &str, f: impl FnOnce(&mut Draft) -> Result<T>) -> Result<T> {
        let mut slot = self.lock();
        match slot.as_mut() {
            Some(draft) if draft.id == id => {
                draft.touched = Instant::now();
                f(draft)
            }
            _ => bail!(GONE),
        }
    }

    /// Hands a ready draft to the upload task, which owns its work folder from then on. `form`
    /// is the form the caller checked (`isCurrentUploadSubmission`).
    pub fn take(&self, id: &str, form: &draft::Draft) -> Result<Draft> {
        let mut slot = self.lock();
        match slot.as_mut() {
            Some(draft) if draft.id == id => {
                draft.pack_mut()?;
                if draft.form != *form {
                    bail!("上传信息已更改，请重新提交");
                }
                Ok(slot.take().expect("checked above"))
            }
            _ => bail!(GONE),
        }
    }

    pub fn discard(&self, id: &str) -> Result<Dropped> {
        let mut slot = self.lock();
        match slot.as_ref() {
            Some(draft) if draft.id == id => Ok(Self::release(slot.take().expect("checked above"))),
            _ => bail!(GONE),
        }
    }

    /// A read task ended. When its draft is gone, so is the work folder.
    fn finish(&self, id: &str, server: bool, apply: impl FnOnce(&mut Draft)) {
        let mut slot = self.lock();
        match slot.as_mut() {
            Some(draft) if draft.id == id && matches!(&draft.phase, Phase::Reading { server: s, .. } if *s == server) => apply(draft),
            Some(draft) if draft.id == id => {}
            _ => remove_dir(&self.root.join(format!("{DIR_PREFIX}{id}"))),
        }
    }

    /// Starts the task that reads the client pack of a new draft.
    pub fn start_read(&self, deps: Deps, id: &str) -> Result<String> {
        let mut slot = self.lock();
        let draft = match slot.as_mut() {
            Some(draft) if draft.id == id => draft,
            _ => bail!(GONE),
        };
        if draft.pack.is_some() || matches!(draft.phase, Phase::Reading { .. }) {
            bail!("这个草稿已经读取过整合包");
        }
        let (path, work_dir, this, draft_id) = (draft.path.clone(), draft.work_dir.clone(), self.clone(), draft.id.clone());
        let title = format!("读取整合包 {}", draft.summary.name);
        // The lock is held across the submit, so the task cannot finish before the phase says
        // that it runs.
        let runtime = deps.runtime.clone();
        let tasks = deps.tasks.clone();
        let task = tasks.submit(&runtime, title, format!("modpack-upload-read:{draft_id}"), move |ctx| {
            let cache = deps.store.root.clone();
            let (result, warnings) = read(&deps, &ctx, &work_dir, |rctx| prepare::client(rctx, &cache, &path));
            let failed = result.as_ref().err().map(|error| format!("{error:#}"));
            this.finish(&draft_id, false, |draft| match result {
                Ok(loaded) => {
                    draft.pack = Some(Pack::new(loaded, &cache, warnings));
                    draft.phase = Phase::Ready;
                }
                Err(_) => draft.phase = Phase::Failed(failed.clone().unwrap_or_default()),
            });
            match failed {
                Some(message) => bail!(message),
                None => {
                    ctx.finish("整合包读取完成");
                    Ok(())
                }
            }
        });
        draft.phase = Phase::Reading { task: task.clone(), server: false };
        Ok(task)
    }

    /// Starts the task that reads a server pack into a ready draft (`loadServerPack`).
    pub fn start_server_read(&self, deps: Deps, id: &str, file: PathBuf) -> Result<String> {
        let mut slot = self.lock();
        let draft = match slot.as_mut() {
            Some(draft) if draft.id == id => draft,
            _ => bail!(GONE),
        };
        let pack = draft.pack_mut()?;
        let (client_mods, fabric) = (pack.mods.clone(), pack.fabric);
        let (work_dir, this, draft_id) = (draft.work_dir.clone(), self.clone(), draft.id.clone());
        let title = format!("读取服务端整合包 {}", file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
        let runtime = deps.runtime.clone();
        let tasks = deps.tasks.clone();
        let task = tasks.submit(&runtime, title, format!("modpack-upload-server:{draft_id}"), move |ctx| {
            let cache = deps.store.root.clone();
            let (result, _) = read(&deps, &ctx, &work_dir, |rctx| prepare::server(rctx, &cache, &file, &client_mods, fabric));
            let failed = result.as_ref().err().map(|error| format!("读取服务端ZIP失败：{error:#}"));
            this.finish(&draft_id, true, |draft| {
                draft.phase = Phase::Ready;
                match result {
                    Ok((loaded, merged)) => {
                        draft.notice = None;
                        if let Some(pack) = &mut draft.pack {
                            pack.apply_server(&cache, loaded, merged);
                        }
                    }
                    Err(_) => draft.notice = failed.clone(),
                }
            });
            match failed {
                Some(message) => bail!(message),
                None => {
                    ctx.finish("服务端整合包读取完成");
                    Ok(())
                }
            }
        });
        draft.phase = Phase::Reading { task: task.clone(), server: true };
        Ok(task)
    }

    /// Starts a download of the jars the content store does not hold yet, so the agent can
    /// inspect every mod (`queueMissingModDownload`). `None` when every jar is local.
    pub fn start_download(&self, deps: Deps, id: &str) -> Result<Option<(String, usize)>> {
        let missing = self.with(id, |draft| Ok(prepare::missing_mods(&deps.store.root, &draft.pack_mut()?.mods)))?;
        if missing.is_empty() {
            return Ok(None);
        }
        let count = missing.len();
        let runtime = deps.runtime.clone();
        let tasks = deps.tasks.clone();
        let task = tasks.submit(&runtime, format!("下载{count}个Mod"), format!("modpack-upload-mods:{id}"), move |ctx| download(&deps, &ctx, missing));
        Ok(Some((task, count)))
    }
}

/// Runs a pack reader with the launcher's catalog; returns the reader's warnings too.
pub(super) fn read<T>(deps: &Deps, ctx: &crate::tasks::Context, work_dir: &Path, run: impl FnOnce(&reader::Context) -> Result<T>) -> (Result<T>, Vec<String>) {
    let warnings = Mutex::new(Vec::new());
    let progress = |p: reader::Progress| match p {
        reader::Progress::Phase(text) => ctx.progress(text, None),
        reader::Progress::Percent(text, fraction) => ctx.progress(text, Some(fraction)),
        reader::Progress::Warn(text) => {
            tracing::warn!(warning = %text, "读取整合包警告");
            warnings.lock().unwrap_or_else(PoisonError::into_inner).push(text);
        }
    };
    let catalog = deps.catalog.clone();
    let slugs = move |asked: &BTreeSet<String>| manifest::catalog_mr_slugs(&catalog, asked);
    let rctx = reader::Context {
        runtime: deps.runtime.clone(),
        platforms: platform::Platforms::new(deps.catalog.transport()),
        work_dir: work_dir.to_path_buf(),
        cancel: ctx.cancel.clone(),
        progress: &progress,
        mr_slugs: &slugs,
    };
    let result = run(&rctx);
    (result, warnings.into_inner().unwrap_or_else(PoisonError::into_inner))
}

/// `createUploadClientModDownloadTask2`: the jars into the content store, eight at a time.
pub(super) fn download(deps: &Deps, ctx: &crate::tasks::Context, mods: Vec<Mod>) -> Result<()> {
    ctx.progress(format!("开始下载剩余Mod，共{}个", mods.len()), Some(0.));
    let requests = crate::pack_install::mod_download_requests(deps, &mods)?;
    let total = requests.len();
    deps.runtime.block_on(async {
        let gate = Arc::new(tokio::sync::Semaphore::new(8));
        let mut set = tokio::task::JoinSet::new();
        for request in requests {
            let (store, cancel, gate) = (deps.store.clone(), ctx.cancel.clone(), gate.clone());
            set.spawn(async move {
                let _permit = gate.acquire_owned().await?;
                store.fetch(&request, &cancel, &|_| {}).await.map(drop).map_err(|error| error.context(format!("下载{}失败", request.id)))
            });
        }
        let mut done = 0;
        while let Some(joined) = set.join_next().await {
            joined.map_err(|error| anyhow::anyhow!("下载任务意外中断: {error}"))??;
            done += 1;
            ctx.progress(format!("已下载{done}/{total}个Mod"), Some(done as f32 / total.max(1) as f32));
        }
        anyhow::Ok(())
    })?;
    ctx.finish(format!("{total}个Mod已下载"));
    Ok(())
}

#[cfg(test)]
impl Drafts {
    /// A draft whose client pack is read, as the read task leaves it.
    pub(super) fn insert_read(&self, loaded: Loaded, cache: &Path) -> String {
        let summary = loaded.summary.clone();
        let check = super::Check { summary, draft: draft::Draft::default(), update: None, problems: vec![], preflight: None };
        let (id, _) = self.create(check, PathBuf::from("pack.zip"), cache.to_path_buf());
        self.with(&id, |draft| {
            std::fs::create_dir_all(&draft.work_dir)?;
            draft.pack = Some(Pack::new(loaded, cache, vec![]));
            Ok(())
        })
        .unwrap();
        id
    }

    pub(super) fn age(&self, by: Duration) {
        if let Some(draft) = self.slot.lock().unwrap().as_mut() {
            draft.touched -= by;
        }
    }

    pub(super) fn set_phase(&self, phase: Phase) {
        if let Some(draft) = self.slot.lock().unwrap().as_mut() {
            draft.phase = phase;
        }
    }

    pub(super) fn finish_for_test(&self, id: &str, server: bool) {
        self.finish(id, server, |draft| draft.phase = Phase::Ready);
    }

    pub(super) fn work_dir_of(&self, id: &str) -> PathBuf {
        self.root.join(format!("{DIR_PREFIX}{id}"))
    }
}
