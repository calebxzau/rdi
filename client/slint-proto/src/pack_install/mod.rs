//! J4c: install a room modpack from the RDI server, as Kotlin `ModpackService.startInstallTask2`
//! does, minus the node refresh (D6). The public surface has no UI types, so a launcher MCP tool
//! can call `start` and `state` the same way the Slint screens do.
pub mod archive;
pub mod extras;
pub mod mods;

use crate::{
    api::server::ServerApi,
    catalog::{Catalog, FileRef, Platform},
    content_store::{self, Request},
    digests::{Algorithm, Digest},
    download::{Progress, Source, cancellable},
    packs::human_file_size,
    tasks::{self, Context},
};
use anyhow::{Context as _, Result, ensure};
use reqwest::header::HeaderMap;
use serde::Deserialize;
use serde_json::json;
use std::{
    collections::{BTreeMap, HashSet},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

/// Visible steps; Kotlin's titles without 刷新节点.
pub const STEPS: [&str; 7] = [
    "下载客户端整合包",
    "准备安装目录",
    "解压客户端整合包",
    "写入菜单",
    "补充模组",
    "补充资源包和光影包",
    "补充配置",
];
const FANCYMENU_OPTIONS: &[u8] = include_bytes!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../ui/assets/src/main/resources/overrides/fancymenu-options.txt"
));
static COMMIT: Mutex<()> = Mutex::new(());

pub struct InstallRequest {
    pub modpack_id: String,
    pub version: String,
    pub include_extras: bool,
}
#[derive(Clone, Debug, PartialEq)]
pub enum State {
    Missing,
    /// The run id of the install task.
    Installing(String),
    Installed,
}

/// Version ids of running or preparing games, fed from the Rust sessions.
#[derive(Clone, Default)]
pub struct Running(Arc<Mutex<HashSet<String>>>);
impl Running {
    pub fn update(&self, versions: HashSet<String>) {
        *self.0.lock().unwrap() = versions;
    }
    fn blocks(&self, version_id: &str) -> bool {
        self.0.lock().unwrap().contains(version_id)
    }
}

#[derive(Clone)]
pub struct Deps {
    pub server: ServerApi,
    pub tasks: tasks::Manager,
    pub store: content_store::Store,
    pub catalog: Arc<Catalog>,
    pub runtime: tokio::runtime::Handle,
    pub versions: PathBuf,
    pub prefer_mirror: bool,
    pub running: Running,
}

/// `ModpackService.modpackInstallTaskKey`.
pub fn task_key(modpack_id: &str, version: &str) -> String {
    format!("modpack-install:{}:{version}", modpack_id.to_ascii_lowercase())
}
/// `ModpackService.getVersionDir` name.
pub fn version_id(modpack_id: &str, version: &str) -> String {
    format!("{}_{version}", modpack_id.to_ascii_lowercase())
}
fn check_ids(modpack_id: &str, version: &str) -> Result<()> {
    ensure!(
        modpack_id.len() == 24 && modpack_id.bytes().all(|b| b.is_ascii_hexdigit()),
        "整合包编号无效"
    );
    ensure!(
        !version.trim().is_empty()
            && version != "."
            && version != ".."
            && !version.contains(['/', '\\', ':', '\0']),
        "整合包版本名称无效"
    );
    Ok(())
}

// The planned launcher MCP tool calls this; the screens use `state_in` without full deps.
#[allow(dead_code)]
pub fn state(deps: &Deps, modpack_id: &str, version: &str) -> State {
    state_in(&deps.tasks, &deps.versions, modpack_id, version)
}
/// `state` from only the task list and the versions folder, for screens without full deps.
pub fn state_in(tasks: &tasks::Manager, versions: &Path, modpack_id: &str, version: &str) -> State {
    if let Some(id) = tasks.active(&task_key(modpack_id, version)) {
        return State::Installing(id);
    }
    // Kotlin treats a version as installed when `<version dir>/mods` is a directory.
    if versions
        .join(version_id(modpack_id, version))
        .join("mods")
        .is_dir()
    {
        State::Installed
    } else {
        State::Missing
    }
}

/// Kotlin `Modpack.BriefVo`. The server omits fields equal to their defaults, so they are
/// restored here: `未知整合包`, `V211`, `neoforge`.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Brief {
    id: String,
    #[serde(default = "default_name")]
    name: String,
    #[serde(default = "default_mc_ver")]
    mc_ver: String,
    #[serde(default = "default_loader")]
    modloader: String,
}
fn default_name() -> String {
    "未知整合包".into()
}
fn default_mc_ver() -> String {
    "V211".into()
}
fn default_loader() -> String {
    "neoforge".into()
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Version {
    status: String,
    total_size: Option<u64>,
    #[serde(default)]
    mods: Vec<mods::Mod>,
    #[serde(default)]
    client_extras: Vec<extras::Content>,
}

struct Plan {
    modpack_id: String,
    version: String,
    mc_version: String,
    mods: Vec<mods::Mod>,
    extras: Vec<extras::Content>,
}

/// `完整下载整合包 <name> <size>`, as Kotlin `modpackInstallTaskTitle`.
fn title(name: &str, size: Option<u64>) -> String {
    let mut title = "完整下载整合包".to_string();
    if !name.trim().is_empty() {
        title.push(' ');
        title.push_str(name);
    }
    if let Some(size) = size {
        title.push(' ');
        title.push_str(&human_file_size(size));
    }
    title
}

/// Starts the install, or returns the id of the one already running. Blocks on two server
/// requests, so call it off the UI thread.
pub fn start(deps: &Deps, request: InstallRequest) -> Result<String> {
    let modpack_id = request.modpack_id.to_ascii_lowercase();
    check_ids(&modpack_id, &request.version)?;
    let key = task_key(&modpack_id, &request.version);
    if let Some(id) = deps.tasks.active(&key) {
        return Ok(id);
    }
    let (brief, version) = deps.runtime.block_on(async {
        let briefs: Vec<Brief> = deps
            .server
            .post("/modpack/infos", &json!([modpack_id]))
            .await?;
        let brief = briefs
            .into_iter()
            .find(|b| b.id.eq_ignore_ascii_case(&modpack_id))
            .context("这个整合包在服务器不存在")?;
        let version: Version = deps
            .server
            .get(&format!("/modpack/{modpack_id}/version/{}", request.version))
            .await?;
        anyhow::Ok((brief, version))
    })?;
    ensure!(version.status == "OK", "该版本尚未构建完成，暂不能安装");
    let fabric = brief.modloader.eq_ignore_ascii_case("fabric");
    let plan = Plan {
        modpack_id,
        version: request.version,
        mc_version: brief.mc_ver,
        mods: mods::client_installable(&version.mods, fabric),
        extras: if request.include_extras {
            version.client_extras
        } else {
            vec![]
        },
    };
    let worker = deps.clone();
    Ok(deps.tasks.submit_steps(
        &deps.runtime,
        title(&brief.name, version.total_size),
        key,
        &STEPS,
        move |context| install(&worker, &plan, &context),
    ))
}

struct Headers<'a>(&'a Deps);
impl mods::SourceHeaders for Headers<'_> {
    fn rdi_mod_url(&self, file_name: &str) -> String {
        self.0
            .server
            .url(&format!("/mod/download/{}", encode_segment(file_name)))
    }
    fn rdi_headers(&self, url: &str) -> Result<HeaderMap> {
        self.0.server.auth_headers(url)
    }
    fn curse_forge_headers(&self, url: &str) -> Result<HeaderMap> {
        self.0.catalog.download_headers_for(url)
    }
}

/// Percent-encodes one URL path segment, as Ktor `encodeURLPathPart` does for file names.
pub(crate) fn encode_segment(value: &str) -> String {
    let mut out = String::new();
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~!$&'()*+,;=:@".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Removes a tree without following symbolic links or junctions.
fn remove_tree(path: &Path) -> Result<()> {
    match std::fs::symlink_metadata(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
        Ok(m) if m.file_type().is_symlink() => {
            // A directory link on Windows needs remove_dir; neither call touches the referent.
            std::fs::remove_file(path).or_else(|_| std::fs::remove_dir(path))?;
            Ok(())
        }
        Ok(m) if m.is_dir() => {
            for entry in std::fs::read_dir(path)? {
                remove_tree(&entry?.path())?;
            }
            std::fs::remove_dir(path)?;
            Ok(())
        }
        Ok(_) => Ok(std::fs::remove_file(path)?),
    }
}

/// Leftover staging and replaced folders of this version from older runs.
fn remove_leftovers(versions: &Path, version_id: &str, run: &str) {
    let Ok(entries) = std::fs::read_dir(versions) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        for prefix in [".installing-", ".replaced-"] {
            if let Some(rest) = name.strip_prefix(&format!("{prefix}{version_id}-"))
                && uuid::Uuid::parse_str(rest).is_ok()
                && rest < run
                && let Err(error) = remove_tree(&entry.path())
            {
                tracing::error!(error=%format!("{error:#}"),dir=%name,"清理旧安装临时目录失败");
            }
        }
    }
}

pub(crate) fn bytes_message(p: &Progress) -> (String, Option<f32>) {
    let mut message = format!("下载中 {}", human_file_size(p.bytes));
    if let Some(total) = p.total.filter(|t| *t > 0) {
        message.push_str(&format!("/{}", human_file_size(total)));
    }
    if p.speed > 0. {
        message.push_str(&format!(" · {}/s", human_file_size(p.speed as u64)));
    }
    (
        message,
        p.total
            .filter(|t| *t > 0)
            .map(|t| (p.bytes as f32 / t as f32).clamp(0., 1.)),
    )
}

/// Kotlin `mergeMinecraftOptions`.
pub fn merge_options(original: &str, overrides: &[(&str, &str)]) -> String {
    if original.trim().is_empty() {
        return overrides
            .iter()
            .map(|(k, v)| format!("{k}:{v}"))
            .collect::<Vec<_>>()
            .join("\n");
    }
    let separator = if original.contains("\r\n") { "\r\n" } else { "\n" };
    let mut updated = HashSet::new();
    let normalized = original.replace("\r\n", "\n").replace('\r', "\n");
    let mut lines: Vec<String> = normalized
        .split('\n')
        .map(|line| match line.find(':') {
            Some(i) if i > 0 => match overrides.iter().find(|(k, _)| *k == &line[..i]) {
                Some((k, v)) => {
                    updated.insert(*k);
                    format!("{k}:{v}")
                }
                None => line.to_owned(),
            },
            _ => line.to_owned(),
        })
        .collect();
    for (k, v) in overrides {
        if !updated.contains(k) {
            lines.push(format!("{k}:{v}"));
        }
    }
    lines.join(separator)
}
/// `writeMinecraftOptions` overrides by Minecraft version.
pub fn option_overrides(mc_version: &str) -> Vec<(&'static str, &'static str)> {
    let mut out = if mc_version == "V071" {
        vec![("lang", "zh_CN")]
    } else {
        vec![("darkMojangStudiosBackground", "true"), ("lang", "zh_cn")]
    };
    out.push(("forceUnicodeFont", "true"));
    out
}

fn install(deps: &Deps, plan: &Plan, ctx: &Context) -> Result<()> {
    let version_id = version_id(&plan.modpack_id, &plan.version);
    std::fs::create_dir_all(&deps.versions)?;
    let run = uuid::Uuid::now_v7().to_string();
    remove_leftovers(&deps.versions, &version_id, &run);
    let staging = deps.versions.join(format!(".installing-{version_id}-{run}"));
    let result = install_into(deps, plan, ctx, &version_id, &staging, &run);
    // On failure or cancellation the staging folder goes; the cache keeps what was downloaded.
    if let Err(error) = remove_tree(&staging) {
        tracing::error!(error=%format!("{error:#}"),"清理安装临时目录失败");
        if result.is_ok() {
            return Err(error);
        }
    }
    result
}

/// Starts a visible step; cancellation is checked at every step boundary.
fn begin(ctx: &Context, version_id: &str, index: usize) -> Result<()> {
    ctx.step(index);
    #[cfg(test)]
    if tests::cancel_at(version_id) == Some(index) {
        ctx.cancel.cancel();
    }
    #[cfg(not(test))]
    let _ = version_id;
    ctx.cancel.check()
}

fn install_into(
    deps: &Deps,
    plan: &Plan,
    ctx: &Context,
    version_id: &str,
    staging: &Path,
    run: &str,
) -> Result<()> {
    let cancel = &ctx.cancel;
    let headers = Headers(deps);

    begin(ctx, version_id, 0)?;
    ctx.progress("读取客户端整合包", None);
    let path = format!(
        "/modpack/{}/version/{}/client",
        plan.modpack_id, plan.version
    );
    let hash: String = deps.runtime.block_on(cancellable(
        cancel,
        deps.server.get::<String>(&format!("{path}/hash")),
    ))
    .context("客户端包hash为空")?;
    let url = deps.server.url(&path);
    let pack = Request {
        id: format!("modpack-client:{}:{}", plan.modpack_id, plan.version),
        relative_path: format!("{version_id}.client-pack"),
        size: None,
        digests: vec![Digest::new(Algorithm::Sha1, &hash)?],
        sources: vec![Source {
            headers: deps.server.auth_headers(&url)?,
            url,
        }],
    };
    let fetched = deps.runtime.block_on(cancellable(
        cancel,
        deps.store.fetch(&pack, cancel, &|p| {
            let (message, fraction) = bytes_message(&p);
            ctx.progress(message, fraction);
        }),
    ))?;
    ctx.progress("客户端整合包已准备", Some(1.));

    begin(ctx, version_id, 1)?;
    ctx.progress("创建安装目录", None);
    std::fs::create_dir(staging)?;
    ctx.progress("目录已就绪", Some(1.));

    begin(ctx, version_id, 2)?;
    ctx.progress("扫描压缩包内容...", Some(0.));
    archive::extract(&fetched.path, staging, cancel, &|done, total, current| {
        let name = current.rsplit('/').next().unwrap_or(current);
        ctx.progress(
            format!("解压中 {name}({done}/{total})"),
            Some(done as f32 / total as f32),
        );
    })?;
    drop(fetched);
    ctx.progress("解压完成", Some(1.));

    begin(ctx, version_id, 3)?;
    let menu = crate::import::installer::checked_target(
        staging,
        Path::new("config/fancymenu/options.txt"),
    )?;
    std::fs::create_dir_all(menu.parent().unwrap())?;
    std::fs::write(&menu, FANCYMENU_OPTIONS)?;
    ctx.progress("写入完成", Some(1.));

    begin(ctx, version_id, 4)?;
    let mods_dir = crate::import::installer::checked_target(staging, Path::new("mods"))?;
    std::fs::create_dir_all(&mods_dir)?;
    let requests = mod_requests(deps, plan, &headers)?;
    deps.runtime.block_on(cancellable(
        cancel,
        deps.store.materialize(&requests, &mods_dir, cancel, &|p| {
            let (message, fraction) = bytes_message(&p);
            ctx.progress(message, fraction);
        }),
    ))?;
    ctx.progress("完成", Some(1.));

    begin(ctx, version_id, 5)?;
    let extras = extras::merge(&plan.extras)?;
    let mut missing = vec![];
    for extra in &extras {
        let relative = extras::normalize_path(&extra.path.clone().unwrap_or_default())?;
        let target = crate::import::installer::checked_target(staging, Path::new(&relative))?;
        match std::fs::symlink_metadata(&target) {
            Ok(m) => ensure!(
                m.is_file() && !m.file_type().is_symlink(),
                "客户端额外内容目标不是安全的普通文件: {relative}"
            ),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => missing.push(extra.clone()),
            Err(e) => return Err(e.into()),
        }
    }
    let extra_requests = extra_requests(deps, &missing, &headers)?;
    if !extra_requests.is_empty() {
        deps.runtime.block_on(cancellable(
            cancel,
            deps.store.materialize(&extra_requests, staging, cancel, &|p| {
                let (message, fraction) = bytes_message(&p);
                ctx.progress(message, fraction);
            }),
        ))?;
    }
    ctx.progress("完成", Some(1.));

    begin(ctx, version_id, 6)?;
    let options = crate::import::installer::checked_target(staging, Path::new("options.txt"))?;
    let original = match std::fs::read_to_string(&options) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e.into()),
    };
    std::fs::write(
        &options,
        merge_options(&original, &option_overrides(&plan.mc_version)),
    )?;
    cancel.check()?;
    ctx.progress("正在保存", None);
    commit(deps, version_id, staging, run, cancel)?;
    ctx.progress("写入完成", Some(1.));
    Ok(())
}

fn mod_requests(deps: &Deps, plan: &Plan, headers: &Headers) -> Result<Vec<Request>> {
    requests_for(deps, &plan.mods, headers)
}

/// Download requests for mods outside a pack install (J5b room base and extra mods), each at
/// `mods/<fileName>`; the caller may change `relative_path`. Blocks on CurseForge lookups, so
/// async callers run it on a blocking thread.
pub fn mod_download_requests(deps: &Deps, mods: &[mods::Mod]) -> Result<Vec<Request>> {
    requests_for(deps, mods, &Headers(deps))
}

fn requests_for(deps: &Deps, list: &[mods::Mod], headers: &Headers) -> Result<Vec<Request>> {
    // Look up real CurseForge URLs once, only for mods the cache does not already hold.
    let mut lookups = vec![];
    for m in list {
        if m.platform.eq_ignore_ascii_case("cf") && !m.trusted_curse_forge() {
            let probe = mods::request(m, vec![])?;
            if !deps.store.has_cached(&probe) {
                lookups.push(FileRef {
                    platform: Platform::CurseForge,
                    id: m.file_id.trim().to_owned(),
                });
            }
        }
    }
    let found: BTreeMap<FileRef, crate::catalog::File> = if lookups.is_empty() {
        BTreeMap::new()
    } else {
        deps.runtime
            .block_on(deps.catalog.get_files(&lookups))?
            .value
    };
    list.iter()
        .map(|m| {
            let real = if m.trusted_curse_forge() {
                m.download_urls
                    .iter()
                    .find(|u| !u.trim().is_empty())
                    .cloned()
            } else if m.platform.eq_ignore_ascii_case("cf") {
                let key = FileRef {
                    platform: Platform::CurseForge,
                    id: m.file_id.trim().to_owned(),
                };
                match found.get(&key) {
                    Some(file) => file.url.clone(),
                    None if lookups.contains(&key) => {
                        anyhow::bail!("未找到文件信息: {}", m.slug)
                    }
                    None => None,
                }
            } else {
                None
            };
            mods::request(
                m,
                mods::sources(m, real.as_deref(), deps.prefer_mirror, headers)?,
            )
        })
        .collect()
}

fn extra_requests(
    deps: &Deps,
    extras: &[extras::Content],
    headers: &Headers,
) -> Result<Vec<Request>> {
    extras
        .iter()
        .map(|c| {
            let fallback = if c.download_urls.is_empty() {
                let m = extras::fallback_mod(c);
                let real = if m.platform == "cf" {
                    let key = FileRef {
                        platform: Platform::CurseForge,
                        id: m.file_id.trim().to_owned(),
                    };
                    let files = deps
                        .runtime
                        .block_on(deps.catalog.get_files(std::slice::from_ref(&key)))?;
                    Some(
                        files
                            .value
                            .get(&key)
                            .and_then(|f| f.url.clone())
                            .with_context(|| format!("未找到文件信息: {}", m.slug))?,
                    )
                } else {
                    None
                };
                Some(mods::sources(&m, real.as_deref(), deps.prefer_mirror, headers)?)
            } else {
                None
            };
            extras::request(c, deps.prefer_mirror, headers, fallback)
        })
        .collect()
}

/// D5: move the finished folder into place, keeping the old copy until the new one is there.
fn commit(
    deps: &Deps,
    version_id: &str,
    staging: &Path,
    run: &str,
    cancel: &tasks::Cancellation,
) -> Result<()> {
    let _lock = COMMIT
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    cancel.check()?;
    ensure!(!deps.running.blocks(version_id), "整合包正在运行，不能安装");
    let target = deps.versions.join(version_id);
    let old = deps.versions.join(format!(".replaced-{version_id}-{run}"));
    let had_old = match std::fs::symlink_metadata(&target) {
        Ok(_) => {
            std::fs::rename(&target, &old).context("无法移开旧版本目录，请关闭占用该整合包的程序")?;
            true
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
        Err(e) => return Err(e.into()),
    };
    if let Err(error) = std::fs::rename(staging, &target) {
        if had_old && let Err(back) = std::fs::rename(&old, &target) {
            tracing::error!(error=%back,dir=%old.display(),"恢复旧版本目录失败");
        }
        return Err(anyhow::Error::from(error).context("无法完成安装"));
    }
    if had_old && let Err(error) = remove_tree(&old) {
        // The new version is in place; a leftover hidden folder is removed by the next install.
        tracing::error!(error=%format!("{error:#}"),dir=%old.display(),"清理旧版本目录失败");
    }
    Ok(())
}

#[cfg(test)]
mod tests;
