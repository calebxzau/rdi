//! J5b: starting a room game in Rust, the Rust form of Kotlin `Host.DetailVo.startPlay` followed by
//! `McPlayController.startSession` with `withRdiCore = true`, for Forge 1.20.1, NeoForge 1.21.1
//! (J5b), Fabric 1.20.1 and GTNH (J5c).
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
    /// The pack icon's address, also the game's window icon.
    #[serde(default)]
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

/// The Minecraft version and loader of a room or RDI pack; `None` when the launcher cannot start it.
pub fn supported(brief: &Brief) -> Option<(&'static str, &'static str)> {
    let mc = crate::local_packs::mc_version_name(&brief.mc_ver)?;
    crate::mc_install::meta::runtime(mc, &brief.modloader).ok().map(|r| (mc, r.loader_name()))
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
}

pub enum RoomOutcome {
    Admitted(Admitted),
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
    pack: Brief,
    /// `None` for an RDI pack played alone (`rdiPackPlayArgs`): no room mods, no host id.
    room: Option<Detail>,
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
        bail!("暂不支持这个房间的游戏版本: {} {}", detail.modpack.mc_ver, detail.modpack.modloader);
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
    let Some(session) = deps.play.sessions.admit_room(&title, &version_id, request.allow_duplicate) else {
        return Ok(RoomOutcome::Admitted(Admitted::Duplicate));
    };
    let prepared = Prepared {
        version_dir: deps.install.versions.join(&version_id),
        pack: detail.modpack.clone(),
        room: Some(detail),
        mc_version,
        loader,
        version_id,
        version_name: version.name,
        active,
        play_arg,
    };
    spawn_run(deps, &session, title, prepared, request.window);
    Ok(RoomOutcome::Admitted(Admitted::Started(session)))
}

fn spawn_run(deps: &RoomDeps, session: &str, title: String, prepared: Prepared, window: (u32, u32)) {
    let (deps, id) = (deps.clone(), session.to_owned());
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
}

/// Playing an installed RDI pack alone from the 整合包 tab (`rdiPackPlayArgs`).
pub struct SoloStart {
    pub version_id: String,
    pub window: (u32, u32),
    pub allow_duplicate: bool,
}

/// `rdiPackPlayArgs` + `startSession`: the room flow with the RDI core and the room connection, but
/// no room mods, no room start and no host id. The room line of `-Drdi.play` is `test`, port 55555.
pub async fn start_solo(deps: &RoomDeps, request: SoloStart) -> Result<Admitted> {
    let (pack_id, version_name) = crate::local_packs::remote_name(&request.version_id).context("请选择RDI整合包")?;
    let server = deps.install.server.clone();
    let briefs: Vec<Brief> = server.post("/modpack/infos", &serde_json::json!([pack_id])).await.context("获取整合包信息失败")?;
    let pack = briefs.into_iter().find(|b| b.id.eq_ignore_ascii_case(&pack_id)).context("整合包在服务器不存在")?;
    let Some((mc_version, loader)) = supported(&pack) else {
        bail!("暂不支持这个整合包的游戏版本: {} {}", pack.mc_ver, pack.modloader);
    };
    let uuid = uuid_of(&deps.uid)?;
    let play_arg = play_arg(&server.base(), "127.0.0.1:55667", "test", 55555, &uuid, &deps.account_name);
    let title = format!("单机 - {}", pack.name);
    let Some(session) = deps.play.sessions.admit_room(&title, &request.version_id, request.allow_duplicate) else {
        return Ok(Admitted::Duplicate);
    };
    let prepared = Prepared {
        version_dir: deps.install.versions.join(&request.version_id),
        pack,
        room: None,
        mc_version,
        loader,
        version_id: request.version_id,
        version_name,
        active: vec![],
        play_arg,
    };
    spawn_run(deps, &session, title, prepared, request.window);
    Ok(Admitted::Started(session))
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

/// `syncHostManagedBaseMods` and `syncHostExtraMods` for a room game.
async fn sync_room_mods(deps: &RoomDeps, session: &str, p: &Prepared, room: &Detail, mods_dir: &Path, fabric: bool) -> Result<()> {
    let sessions = &deps.play.sessions;
    let stopped = || sessions.stop_requested(session);
    sessions.append(session, "[RDI] 检查房间基础Mod...");
    let base = mods_sync::base_plan(mods_dir, &p.active, &room.disabled_mods, fabric);
    for name in &base.delete {
        sessions.append(session, format!("[RDI] 移除已禁用基础Mod {name}"));
    }
    mods_sync::delete(mods_dir, &base.delete)?;
    sync_mods(deps, session, mods_dir, base.download, false, "下载并同步基础Mod").await?;
    sessions.append(session, "[RDI] 房间基础Mod已同步");
    if stopped() {
        return Ok(());
    }

    sessions.append(session, "[RDI] 检查房间附加Mod...");
    let extra = mods_sync::extra_plan(mods_dir, &room.extra_mods, fabric)?;
    for name in &extra.delete {
        sessions.append(session, format!("[RDI] 移除旧附加Mod {name}"));
    }
    mods_sync::delete(mods_dir, &extra.delete)?;
    sync_mods(deps, session, mods_dir, extra.download, true, "下载并同步附加Mod").await?;
    sessions.append(session, "[RDI] 房间附加Mod已同步");
    Ok(())
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
    let fabric = p.loader == "Fabric";

    if let Some(room) = &p.room {
        sync_room_mods(deps, session, p, room, &mods_dir, fabric).await?;
        if stopped() {
            return Ok(());
        }
    }

    for name in mods_sync::cleanup_removed(&p.version_dir, fabric)? {
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
    let target = crate::mc_install::Target { mc_version: p.mc_version, loader: p.loader, version_id: &p.version_id, version_dir: &p.version_dir };
    let launch = crate::mc_install::prepare_for_launch(&install, &target, &cancel, &log).await?;
    sessions.append(session, "[RDI] 游戏安装已就绪");
    if stopped() {
        return Ok(());
    }
    if let Err(error) = super::clean_early_display(&p.version_dir) {
        tracing::warn!(error = %format!("{error:#}"), version = %p.version_id, "旧版加载界面清理失败");
        sessions.append(session, format!("[RDI] 旧版加载界面清理失败: {error}"));
    }
    let mut window_args = vec![super::window::title_jvm_arg(&p.pack.name, p.room.as_ref().map(|r| r.name.as_str()), &p.version_name)];
    let http = deps.play.install.downloader.http.clone();
    let icon = super::window::prepare_icon(&p.version_dir, &crate::paths::data_dir(), p.pack.icon.as_deref(), |url| async move {
        crate::images::download(&http, &url).await
    })
    .await;
    match icon {
        Ok(Some(icon)) => window_args.push(super::window::icon_jvm_arg(&icon)),
        Ok(None) => {}
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), version = %p.version_id, "整合包图标准备失败");
            sessions.append(session, "[RDI] 整合包图标准备失败，将使用游戏默认图标");
        }
    }
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
    if supports_forgeguard(&p.pack.name) {
        let agent = runtime.forgeguard.clone().context("缺少Forgeguard启动保护文件")?;
        jvm.push(forgeguard_jvm_arg(&agent));
    }
    let nodes = deps.endpoint.proxy_nodes(&route::state());
    let lines = sessions.clone();
    let address = route::link::start(nodes, move |line| lines.append_alive(line)).await?;
    let play_arg = with_game_addr(&p.play_arg, &address)?;
    use base64::Engine as _;
    jvm.push(format!("-Drdi.play={}", base64::engine::general_purpose::STANDARD.encode(play_arg.as_bytes())));
    if let Some(room) = &p.room {
        jvm.push(format!("-Drdi.host.id={}", room.id));
    }
    jvm.extend(dm_jvm_args(deps.is_dav, &deps.install.server.base()));
    if stopped() {
        return Ok(());
    }

    // `GameKotlinRuntime.prepare` and the media runtime, as `launchInternal` does before the command.
    let selection = crate::play::runtime::Selection::for_room(p.mc_version, p.loader);
    if selection.kotlin {
        let (mods, cache) = (mods_dir.clone(), deps.tools.join("kotlin-runtime"));
        tokio::task::spawn_blocking(move || super::thin::thin_mods(&mods, &cache))
            .await?
            .map_err(|e| anyhow::anyhow!("Kotlin运行库准备失败：{e}"))?;
    }
    let natives = if selection.media {
        let (rt, tools) = (runtime.clone(), deps.tools.clone());
        Some(tokio::task::spawn_blocking(move || rt.prepare_media_natives(&tools)).await?.map_err(|e| {
            tracing::error!(error = %format!("{e:#}"), "Minecraft媒体模块不可用");
            anyhow::anyhow!("媒体模块缺失或损坏，请先修复")
        })?)
    } else {
        None
    };
    let dirs = install.directories();
    super::check_launch(&launch, &p.version_dir, &dirs, &install.host).await?;
    let account = Account {
        name: deps.account_name.clone(),
        uuid: uuid_of(&deps.uid)?,
        access_token: deps.install.server.jwt().unwrap_or_default().to_owned(),
        user_type: "msa".into(),
    };
    let java = jdk.java().to_string_lossy().into_owned();
    let classpath = runtime.classpath_for(selection);
    let free = super::free_memory_mb;
    let command = mc_launch::command(
        &mc_launch::Request {
            mc_version: p.mc_version,
            version_id: &p.version_id,
            version_dir: &p.version_dir,
            manifest: &launch.manifest,
            loader: &launch.loader,
            account: &account,
            window,
            java_override: snapshot.java_path.as_deref(),
            max_memory_override: snapshot.max_memory_mb,
            extra_jvm_args: &jvm,
            rdi: Some(mc_launch::RdiRuntime { loader: p.loader, classpath: &classpath, media_natives: natives.as_deref() }),
            gtnh_root: launch.gtnh_root.as_deref(),
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
    let room_port = address.rsplit_once(':').and_then(|(_, port)| port.parse().ok());
    super::spawn_and_watch(sessions, session, &command, &p.version_dir, &p.version_id, room_port)
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
    fn every_room_version_starts_in_rust() {
        let brief = |mc: &str, loader: &str| -> Brief { serde_json::from_value(serde_json::json!({"id":"a","mcVer":mc,"modloader":loader})).unwrap() };
        assert_eq!(supported(&brief("V201", "forge")), Some(("1.20.1", "forge")));
        assert_eq!(supported(&brief("V211", "neoforge")), Some(("1.21.1", "neoforge")));
        assert_eq!(supported(&brief("V201", "Fabric")), Some(("1.20.1", "Fabric")));
        assert_eq!(supported(&brief("V071", "forge")), Some(("1.7.10", "forge")));
        assert_eq!(supported(&brief("V211", "fabric")), None);
        let defaults: Brief = serde_json::from_value(serde_json::json!({"id":"a"})).unwrap();
        assert_eq!(supported(&defaults), Some(("1.21.1", "neoforge")));
    }
}
