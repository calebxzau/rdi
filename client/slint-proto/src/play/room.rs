//! J5b: starting a room game in Rust, the Rust form of Kotlin `Host.DetailVo.startPlay` followed by
//! `McPlayController.startSession` with `withRdiCore = true`, for Forge 1.20.1 and NeoForge 1.21.1.
//! Other rooms (GTNH, Fabric) still start through the service until J5c.
//! Console lines keep the Kotlin wording. No Slint types, so the planned launcher MCP can call it.
use super::{Admitted, Deps, JavaMissing, is_window_jvm_arg, mods_sync, root_message};
use crate::download::Progress;
use crate::mc_launch::{self, Account};
use crate::pack_install::mods::{Mod, Side};
use crate::route;
use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use std::path::{Path, PathBuf};

/// Kotlin `Modpack.BriefVo`; the server leaves out fields equal to their defaults.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Brief {
    pub id: String,
    #[serde(default = "default_name")]
    pub name: String,
    #[serde(default = "default_mc_ver")]
    pub mc_ver: String,
    #[serde(default = "default_loader")]
    pub modloader: String,
    /// The pack icon; the window icon comes with GJ5b2.
    #[serde(default)]
    #[allow(dead_code)]
    pub icon: Option<String>,
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

/// Kotlin `Host.DetailVo`, the fields a start needs.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Detail {
    #[serde(rename = "_id")]
    pub id: String,
    pub name: String,
    pub modpack: Brief,
    pub pack_ver: String,
    pub port: i64,
    #[serde(default)]
    pub extra_mods: Vec<Mod>,
    #[serde(default)]
    pub disabled_mods: Vec<Mod>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Version {
    name: String,
    #[serde(default)]
    mods: Vec<Mod>,
}

/// The Minecraft version and loader Rust starts rooms for; `None` leaves the room to the service.
pub fn supported(brief: &Brief) -> Option<(&'static str, &'static str)> {
    match (crate::local_packs::mc_version_name(&brief.mc_ver)?, brief.modloader.to_lowercase().as_str()) {
        ("1.20.1", "forge") => Some(("1.20.1", "forge")),
        ("1.21.1", "neoforge") => Some(("1.21.1", "neoforge")),
        _ => None,
    }
}

/// Everything a room start needs besides the request.
#[derive(Clone)]
pub struct RoomDeps {
    pub play: Deps,
    pub install: crate::pack_install::Deps,
    pub endpoint: route::Endpoint,
    pub runtime_dir: PathBuf,
    /// `<data>/tools`: thinning cache and media natives, shared with Compose.
    pub tools: PathBuf,
    pub account_name: String,
    /// The account's ObjectId.
    pub uid: String,
    pub is_dav: bool,
}

pub struct RoomStart {
    pub host_id: String,
    pub window: (u32, u32),
    pub allow_duplicate: bool,
    /// Games the service runs, per version id.
    pub external_alive: std::collections::HashMap<String, usize>,
}

pub enum RoomOutcome {
    Admitted(Admitted),
    /// Not a Forge 1.20.1 or NeoForge 1.21.1 room: the service starts it.
    Service,
}

/// `ObjectId.toUUID`: the twelve id bytes, then four zero bytes.
pub fn uuid_of(uid: &str) -> Result<String> {
    let uid = uid.trim().to_lowercase();
    ensure!(uid.len() == 24 && uid.chars().all(|c| c.is_ascii_hexdigit()), "账号信息无效");
    let hex = format!("{uid}00000000");
    Ok(format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..]))
}

/// The six lines of `playArg`; line 2 is the address the game connects to.
pub fn play_arg(server_url: &str, game_addr: &str, room: &str, port: i64, uuid: &str, name: &str) -> String {
    format!("{server_url}\n{game_addr}\n{room}\n{port}\n{uuid}\n{name}")
}

/// `withGameAddr`.
pub fn with_game_addr(play_arg: &str, addr: &str) -> Result<String> {
    let mut lines: Vec<&str> = play_arg.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).collect();
    ensure!(lines.len() >= 2, "RDI参数错误，请重新启动房间");
    lines[1] = addr;
    Ok(lines.join("\n"))
}

/// `isClientInstallableMod` after the room's disabled mods are taken out (`startPlay`).
pub fn active_base_mods(version_mods: &[Mod], disabled: &[Mod]) -> Vec<Mod> {
    version_mods
        .iter()
        .filter(|m| !disabled.iter().any(|d| crate::pack_install::mods::same_mod(d, m)))
        .filter(|m| !matches!(m.side, Side::Server | Side::Unknown))
        .cloned()
        .collect()
}

/// `dmGameJvmArgs`: the game's DM entry is only on for the test account.
pub fn dm_jvm_args(is_dav: bool, server_base: &str) -> Vec<String> {
    if !is_dav {
        return vec![];
    }
    let host = reqwest::Url::parse(server_base).ok().and_then(|u| u.host_str().map(str::to_owned)).unwrap_or_default();
    vec![format!("-Drdi.dm.server=http://{host}:15566")]
}

/// `Modpack.supportForgeGuard`.
pub fn supports_forgeguard(pack_name: &str) -> bool {
    pack_name.ends_with("GTO")
}

/// Kotlin passes the agent argument in literal quotes, which Java's `ProcessBuilder` drops on
/// Windows. Rust quotes arguments itself, so the quotes would reach the JVM; leave them out.
pub fn forgeguard_jvm_arg(agent: &Path) -> String {
    format!("-javaagent:{}", agent.to_string_lossy())
}

struct Prepared {
    detail: Detail,
    mc_version: &'static str,
    loader: &'static str,
    version_id: String,
    version_name: String,
    version_dir: PathBuf,
    active: Vec<Mod>,
    play_arg: String,
}

/// `startPlay` up to the session: node refresh, room and version, install check, room start,
/// play count. Then admits the session and prepares it in the background.
pub async fn start(deps: &RoomDeps, request: RoomStart) -> Result<RoomOutcome> {
    let server = deps.install.server.clone();
    if let Err(error) = route::refresh(&server, &deps.endpoint).await {
        tracing::warn!(error = %format!("{error:#}"), "启动游戏前刷新节点失败，继续使用当前节点");
    }
    let host = &request.host_id;
    let detail: Detail = server.get(&format!("/host/{host}/detail")).await.context("获取房间信息失败")?;
    let Some((mc_version, loader)) = supported(&detail.modpack) else {
        return Ok(RoomOutcome::Service);
    };
    let _status: serde_json::Value = server.get(&format!("/host/{host}/status")).await.context("获取房间状态失败")?;
    let pack_id = detail.modpack.id.clone();
    let version: Version = server.get(&format!("/modpack/{pack_id}/version/{}", detail.pack_ver)).await.context("获取整合包版本信息失败")?;
    match crate::pack_install::state_in(&deps.install.tasks, &deps.install.versions, &pack_id, &version.name) {
        crate::pack_install::State::Installed => {}
        crate::pack_install::State::Installing(_) => bail!("整合包正在安装，请稍候"),
        crate::pack_install::State::Missing => bail!("请先安装整合包"),
    }
    let active = active_base_mods(&version.mods, &detail.disabled_mods);
    let _: serde_json::Value = server.post_empty(&format!("/host/{host}/start")).await.map_err(|e| anyhow::anyhow!("启动房间失败: {e}"))?;
    let uuid = uuid_of(&deps.uid)?;
    let play_arg = play_arg(&server.base(), "127.0.0.1:55667", &detail.name, detail.port, &uuid, &deps.account_name);
    tracing::info!(room = %detail.name, "play arg prepared");
    if let Err(error) = server.post_empty::<serde_json::Value>(&format!("/modpack/{pack_id}/play")).await {
        tracing::warn!(error = %format!("{error:#}"), "记录游玩次数失败");
    }
    let version_id = crate::pack_install::version_id(&pack_id, &version.name);
    let title = format!("游玩 {}", detail.name);
    let external = request.external_alive.get(&version_id).copied().unwrap_or(0);
    let Some(session) = deps.play.sessions.admit_room(&title, &version_id, request.allow_duplicate, external) else {
        return Ok(RoomOutcome::Admitted(Admitted::Duplicate));
    };
    let prepared = Prepared {
        version_dir: deps.install.versions.join(&version_id),
        detail,
        mc_version,
        loader,
        version_id,
        version_name: version.name,
        active,
        play_arg,
    };
    let (deps, id) = (deps.clone(), session.clone());
    let window = request.window;
    deps.play.runtime.clone().spawn(async move {
        let sessions = deps.play.sessions.clone();
        sessions.append(&id, format!("[RDI] 准备启动 {title}"));
        match run(&deps, &id, &prepared, window).await {
            Ok(()) => {}
            Err(_) if sessions.stop_requested(&id) => {}
            Err(error) => {
                tracing::error!(error = %format!("{error:#}"), version = %prepared.version_id, "游戏启动失败");
                let message = root_message(&error);
                sessions.append(&id, format!("[RDI] 启动前检查失败: {message}"));
                sessions.mark_exited(&id, Some(message));
            }
        }
    });
    Ok(RoomOutcome::Admitted(Admitted::Started(session)))
}

/// `SyncProgressFilter`, simplified: a line per 8MiB downloaded, plus the first and the last.
fn progress_lines<'a>(sessions: &'a super::Sessions, id: &'a str, label: &'a str) -> impl Fn(Progress) + Sync + 'a {
    let last = std::sync::Mutex::new(None::<u64>);
    move |p: Progress| {
        let bucket = p.bytes / (8 * 1024 * 1024);
        let done = p.total.is_some_and(|t| p.bytes >= t);
        let mut last = last.lock().unwrap();
        if last.is_none() || *last != Some(bucket) || done {
            if done && *last == Some(u64::MAX) {
                return;
            }
            *last = Some(if done { u64::MAX } else { bucket });
            let total = p.total.map(|t| format!("/{}", crate::packs::human_file_size(t))).unwrap_or_default();
            sessions.append(id, format!("[RDI] {label} · {}{total}", crate::packs::human_file_size(p.bytes)));
        }
    }
}

async fn sync_mods(deps: &RoomDeps, id: &str, mods_dir: &Path, list: Vec<Mod>, extra: bool, label: &str) -> Result<()> {
    if list.is_empty() {
        deps.play.sessions.append(id, format!("[RDI] {}", if extra { "没有附加Mod需要同步" } else { "没有基础Mod需要同步" }));
        return Ok(());
    }
    let install = deps.install.clone();
    let mut requests = tokio::task::spawn_blocking(move || crate::pack_install::mod_download_requests(&install, &list)).await??;
    if extra {
        for r in &mut requests {
            r.relative_path = format!("{}{}", mods_sync::EXTRA_MOD_PREFIX, r.relative_path);
        }
    }
    let cancel = deps.play.sessions.cancel(id).context("该游戏已结束")?;
    let progress = progress_lines(&deps.play.sessions, id, label);
    deps.install.store.materialize(&requests, mods_dir, &cancel, &progress).await
}

async fn run(deps: &RoomDeps, session: &str, p: &Prepared, window: (u32, u32)) -> Result<()> {
    let sessions = &deps.play.sessions;
    let stopped = || sessions.stop_requested(session);
    let cancel = sessions.cancel(session).context("该游戏已结束")?;
    let (database, version_id) = (deps.play.database.clone(), p.version_id.clone());
    let snapshot = tokio::task::spawn_blocking(move || crate::pack_options::snapshot(&database, &version_id)).await??;
    ensure!(p.version_dir.exists(), "未找到整合包目录: {}", p.version_dir.display());
    let mods_dir = p.version_dir.join("mods");
    std::fs::create_dir_all(&mods_dir)?;

    sessions.append(session, "[RDI] 检查房间基础Mod...");
    let base = mods_sync::base_plan(&mods_dir, &p.active, &p.detail.disabled_mods, false);
    for name in &base.delete {
        sessions.append(session, format!("[RDI] 移除已禁用基础Mod {name}"));
    }
    mods_sync::delete(&mods_dir, &base.delete)?;
    sync_mods(deps, session, &mods_dir, base.download, false, "下载并同步基础Mod").await?;
    sessions.append(session, "[RDI] 房间基础Mod已同步");
    if stopped() {
        return Ok(());
    }

    sessions.append(session, "[RDI] 检查房间附加Mod...");
    let extra = mods_sync::extra_plan(&mods_dir, &p.detail.extra_mods, false)?;
    for name in &extra.delete {
        sessions.append(session, format!("[RDI] 移除旧附加Mod {name}"));
    }
    mods_sync::delete(&mods_dir, &extra.delete)?;
    sync_mods(deps, session, &mods_dir, extra.download, true, "下载并同步附加Mod").await?;
    sessions.append(session, "[RDI] 房间附加Mod已同步");
    if stopped() {
        return Ok(());
    }

    for name in mods_sync::cleanup_removed(&p.version_dir, false)? {
        sessions.append(session, format!("[RDI] 已移除不兼容Mod {name}"));
    }
    let status = |line: String| sessions.append(session, format!("[RDI] {line}"));
    let core_progress = progress_lines(sessions, session, "下载RDI核心");
    let slug = super::core::slug(p.mc_version, p.loader);
    super::core::prepare(&deps.install.server, &deps.install.store, &mods_dir, &slug, &cancel, &status, &core_progress).await?;
    sessions.append(session, "[RDI] RDI核心Mod已同步");
    if stopped() {
        return Ok(());
    }

    let jdk = crate::java_runtime::resolved().await.ok_or(JavaMissing)?;
    let mut install = deps.play.install.clone();
    install.java = jdk.java();
    sessions.append(session, "[RDI] 检查游戏安装...");
    let log = |line: String| sessions.append(session, format!("[RDI] {line}"));
    let (manifest, loader) = crate::mc_install::prepare_for_launch(&install, p.mc_version, p.loader, &cancel, &log).await?;
    sessions.append(session, "[RDI] 游戏安装已就绪");
    if stopped() {
        return Ok(());
    }
    if let Err(error) = super::clean_early_display(&p.version_dir) {
        tracing::warn!(error = %format!("{error:#}"), version = %p.version_id, "旧版加载界面清理失败");
        sessions.append(session, format!("[RDI] 旧版加载界面清理失败: {error}"));
    }
    // The icon arrives with GJ5b2; until then the game keeps its default icon.
    let window_args = vec![super::window::title_jvm_arg(&p.detail.modpack.name, Some(&p.detail.name), &p.version_name)];
    if stopped() {
        return Ok(());
    }

    let runtime = crate::play::runtime::GameRuntime::load(&deps.runtime_dir)?;
    let mut jvm: Vec<String> = snapshot.custom_jvm_args.iter().filter(|a| !is_window_jvm_arg(a)).cloned().collect();
    jvm.extend(window_args);
    if let Some(jdwp) = &snapshot.jdwp_jvm_arg {
        jvm.push("-Xlog:os+exit=trace".into());
        jvm.push(jdwp.clone());
    }
    if supports_forgeguard(&p.detail.modpack.name) {
        let agent = runtime.forgeguard.clone().context("缺少Forgeguard启动保护文件")?;
        jvm.push(forgeguard_jvm_arg(&agent));
    }
    let nodes = deps.endpoint.proxy_nodes(&route::state());
    let lines = sessions.clone();
    let address = route::link::start(nodes, move |line| lines.append_alive(line)).await?;
    let play_arg = with_game_addr(&p.play_arg, &address)?;
    use base64::Engine as _;
    jvm.push(format!("-Drdi.play={}", base64::engine::general_purpose::STANDARD.encode(play_arg.as_bytes())));
    jvm.push(format!("-Drdi.host.id={}", p.detail.id));
    jvm.extend(dm_jvm_args(deps.is_dav, &deps.install.server.base()));
    if stopped() {
        return Ok(());
    }

    // `GameKotlinRuntime.prepare` and the media runtime, as `launchInternal` does before the command.
    let (mods, cache) = (mods_dir.clone(), deps.tools.join("kotlin-runtime"));
    tokio::task::spawn_blocking(move || super::thin::thin_mods(&mods, &cache))
        .await?
        .map_err(|e| anyhow::anyhow!("Kotlin运行库准备失败：{e}"))?;
    let (rt, tools) = (runtime.clone(), deps.tools.clone());
    let natives = tokio::task::spawn_blocking(move || rt.prepare_media_natives(&tools)).await?.map_err(|e| {
        tracing::error!(error = %format!("{e:#}"), "Minecraft媒体模块不可用");
        anyhow::anyhow!("媒体模块缺失或损坏，请先修复")
    })?;
    let dirs = install.directories();
    let (m, l, libraries, host) = (manifest.clone(), loader.clone(), dirs.libraries.clone(), install.host.clone());
    tokio::task::spawn_blocking(move || mc_launch::validate_libraries(&m, &l, &libraries, &host)).await??;
    let account = Account {
        name: deps.account_name.clone(),
        uuid: uuid_of(&deps.uid)?,
        access_token: deps.install.server.jwt().unwrap_or_default().to_owned(),
        user_type: "msa".into(),
    };
    let java = jdk.java().to_string_lossy().into_owned();
    let classpath = runtime.classpath();
    let free = super::free_memory_mb;
    let command = mc_launch::command(
        &mc_launch::Request {
            mc_version: p.mc_version,
            version_id: &p.version_id,
            version_dir: &p.version_dir,
            manifest: &manifest,
            loader: &loader,
            account: &account,
            window,
            java_override: snapshot.java_path.as_deref(),
            max_memory_override: snapshot.max_memory_mb,
            extra_jvm_args: &jvm,
            rdi: Some(mc_launch::RdiRuntime { loader: p.loader, classpath: &classpath, media_natives: &natives }),
        },
        &mc_launch::Environment {
            directories: &dirs,
            host: &install.host,
            java: &java,
            java_major: 25,
            max_memory_mb: deps.play.max_memory_mb,
            launcher_version: &deps.play.launcher_version,
            free_memory_mb: &free,
        },
    )?;
    tracing::info!(command = %mc_launch::mask_secrets(&command).join(" "), "Launch Command");
    if stopped() {
        return Ok(());
    }
    super::spawn_and_watch(sessions, session, &command, &p.version_dir, &p.version_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(slug: &str, side: Side) -> Mod {
        serde_json::from_value(serde_json::json!({"platform":"cf","projectId":format!("p-{slug}"),"slug":slug,"fileId":"1","hash":"h","side":side})).unwrap()
    }

    #[test]
    fn account_and_play_arguments_follow_kotlin() {
        assert_eq!(uuid_of("66A000000000000000000001").unwrap(), "66a00000-0000-0000-0000-000100000000");
        assert!(uuid_of("xyz").is_err());
        let arg = play_arg("https://rdi.example:65331", "127.0.0.1:55667", "我的房间", 20001, "u", "玩家甲");
        assert_eq!(arg, "https://rdi.example:65331\n127.0.0.1:55667\n我的房间\n20001\nu\n玩家甲");
        assert_eq!(with_game_addr(&arg, "127.0.0.1:61234").unwrap().lines().nth(1), Some("127.0.0.1:61234"));
        assert_eq!(with_game_addr("one line", "x").unwrap_err().to_string(), "RDI参数错误，请重新启动房间");
        assert_eq!(dm_jvm_args(true, "https://rdi.example:443"), ["-Drdi.dm.server=http://rdi.example:15566"]);
        assert!(dm_jvm_args(false, "https://rdi.example:443").is_empty());
        assert!(supports_forgeguard("某某GTO") && !supports_forgeguard("GTO包"));
        assert_eq!(forgeguard_jvm_arg(Path::new("C:/rdi/lib/forgeguard.jar")), "-javaagent:C:/rdi/lib/forgeguard.jar");
    }

    #[test]
    fn active_mods_drop_disabled_server_and_unknown() {
        let mods = vec![m("jei", Side::Both), m("oculus", Side::Client), m("dynmap", Side::Server), m("x", Side::Unknown), m("journeymap", Side::Client)];
        let active = active_base_mods(&mods, &[m("journeymap", Side::Both)]);
        let slugs: Vec<&str> = active.iter().map(|m| m.slug.as_str()).collect();
        assert_eq!(slugs, ["jei", "oculus"]);
    }

    #[test]
    fn only_forge_1_20_1_and_neoforge_1_21_1_start_in_rust() {
        let brief = |mc: &str, loader: &str| -> Brief { serde_json::from_value(serde_json::json!({"id":"a","mcVer":mc,"modloader":loader})).unwrap() };
        assert_eq!(supported(&brief("V201", "forge")), Some(("1.20.1", "forge")));
        assert_eq!(supported(&brief("V211", "neoforge")), Some(("1.21.1", "neoforge")));
        assert_eq!(supported(&brief("V211", "fabric")), None);
        assert_eq!(supported(&brief("V071", "forge")), None);
        let defaults: Brief = serde_json::from_value(serde_json::json!({"id":"a"})).unwrap();
        assert_eq!(supported(&defaults), Some(("1.21.1", "neoforge")));
    }
}
