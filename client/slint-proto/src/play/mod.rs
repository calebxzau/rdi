//! J5a: game sessions in Rust (`McPlayStore`, `McGameSession`, `RunningGameGuard`) and the local
//! pack start of `McPlayController.startSession` for `withRdiCore = false` with an offline
//! identity. Rooms start here too (`room`, J5b; GTNH and Fabric since J5c). Microsoft online play
//! still starts through the service until GJ5c2; its sessions are mirrored in from
//! `game.state` by the app so one list covers both. Rust games survive a launcher crash
//! (`recovery`) and are never handed to the service's `game.restore`.
//! No Slint types here, so the planned launcher MCP can call these functions.
pub mod core;
pub mod mods_sync;
pub mod recovery;
pub mod room;
pub mod runtime;
pub mod thin;
pub mod window;
#[cfg(test)]
mod tests;

use crate::mc_launch::{self, Account};
use crate::tasks::Cancellation;
use anyhow::{Context, Result, ensure};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

const MAX_LOG_LINES: usize = 5000;

/// A game process: started by this launcher, or recovered after a launcher crash.
#[derive(Clone)]
enum Proc {
    Child(Arc<Mutex<std::process::Child>>),
    /// Only the identity is known: no output, no exit code (`RecoveredGameProcess`).
    Recovered { pid: u32, started_at: u64 },
}

/// One game: preparing until its process is attached, then running until it exits.
struct Session {
    id: String,
    title: String,
    version_id: String,
    preparing: bool,
    stop_requested: bool,
    child: Option<Proc>,
    exit_message: Option<String>,
    console: std::collections::VecDeque<String>,
    cancel: Cancellation,
    /// A room game: it needs the room connection while it is alive.
    room: bool,
    /// What `running_games.json` keeps while the process lives.
    record: Option<recovery::Entry>,
}

impl Session {
    fn alive(&self) -> bool {
        self.preparing || self.child.is_some()
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct SessionView {
    pub id: String,
    pub title: String,
    pub version_id: String,
    pub preparing: bool,
    pub running: bool,
    pub exit_message: Option<String>,
}

#[derive(Default)]
struct Inner {
    list: Vec<Session>,
    revision: u64,
    /// Called once no room game is alive any more (`stopLocalMcProxy`).
    rooms_idle: Option<Arc<dyn Fn() + Send + Sync>>,
    /// `running_games.json`; `None` keeps nothing (tests).
    journal: Option<PathBuf>,
}

/// The Rust session store; cheap to clone.
#[derive(Clone, Default)]
pub struct Sessions(Arc<Mutex<Inner>>);

impl Sessions {
    fn with<T>(&self, f: impl FnOnce(&mut Inner) -> T) -> T {
        let mut inner = self.0.lock().unwrap();
        let value = f(&mut inner);
        inner.revision += 1;
        value
    }

    /// Changes whenever a session or its console changes.
    pub fn revision(&self) -> u64 {
        self.0.lock().unwrap().revision
    }

    /// `McPlayStore.admitSession`: `None` when the version already runs and no duplicate is allowed.
    /// `external` counts this version's sessions that live in the service.
    pub fn admit(&self, title: &str, version_id: &str, allow_duplicate: bool, external: usize) -> Option<String> {
        self.admit_kind(title, version_id, allow_duplicate, external, false)
    }

    /// `admit` for a room game, which keeps the room connection alive.
    pub fn admit_room(&self, title: &str, version_id: &str, allow_duplicate: bool, external: usize) -> Option<String> {
        self.admit_kind(title, version_id, allow_duplicate, external, true)
    }

    fn admit_kind(&self, title: &str, version_id: &str, allow_duplicate: bool, external: usize, room: bool) -> Option<String> {
        self.with(|inner| {
            let alive = inner.list.iter().filter(|s| s.version_id == version_id && s.alive()).count() + external;
            if !allow_duplicate && alive > 0 {
                return None;
            }
            let id = uuid::Uuid::now_v7().to_string();
            inner.list.push(Session {
                id: id.clone(),
                title: title.into(),
                version_id: version_id.into(),
                preparing: true,
                stop_requested: false,
                child: None,
                exit_message: None,
                console: Default::default(),
                cancel: Cancellation::default(),
                room,
                record: None,
            });
            Some(id)
        })
    }

    pub fn append(&self, id: &str, line: impl Into<String>) {
        let line = line.into();
        self.with(|inner| {
            if let Some(s) = inner.list.iter_mut().find(|s| s.id == id) {
                if s.console.len() >= MAX_LOG_LINES {
                    s.console.pop_front();
                }
                s.console.push_back(line);
            }
        });
    }

    /// `McPlayStore.appendProxyLog`: a line for every game that is preparing or running.
    pub fn append_alive(&self, line: impl Into<String>) {
        let line = line.into();
        let ids: Vec<String> = self.0.lock().unwrap().list.iter().filter(|s| s.alive()).map(|s| s.id.clone()).collect();
        for id in ids {
            self.append(&id, line.clone());
        }
    }

    pub fn set_rooms_idle(&self, hook: Arc<dyn Fn() + Send + Sync>) {
        self.0.lock().unwrap().rooms_idle = Some(hook);
    }

    fn check_rooms_idle(&self) {
        let hook = {
            let inner = self.0.lock().unwrap();
            if inner.list.iter().any(|s| s.room && s.alive()) { None } else { inner.rooms_idle.clone() }
        };
        if let Some(hook) = hook {
            hook();
        }
    }

    fn cancel(&self, id: &str) -> Option<Cancellation> {
        self.0.lock().unwrap().list.iter().find(|s| s.id == id).map(|s| s.cancel.clone())
    }

    pub fn stop_requested(&self, id: &str) -> bool {
        self.0.lock().unwrap().list.iter().find(|s| s.id == id).is_none_or(|s| s.stop_requested)
    }

    /// `McGameSession.attachProcess`: a session stopped meanwhile kills the new process instead.
    /// The process is recorded for recovery; `room_port` is the room connection it uses.
    fn attach(&self, id: &str, child: std::process::Child, room_port: Option<u16>) -> Option<Arc<Mutex<std::process::Child>>> {
        let pid = child.id();
        let child = Arc::new(Mutex::new(child));
        let identity = recovery::identity(pid);
        if identity.is_none() {
            tracing::warn!(pid, "读取游戏进程身份失败，启动器崩溃后无法找回此游戏");
        }
        let attached = self.with(|inner| match inner.list.iter_mut().find(|s| s.id == id) {
            Some(s) if !s.stop_requested => {
                s.child = Some(Proc::Child(child.clone()));
                s.preparing = false;
                s.record = identity.map(|(started_at, _)| recovery::Entry {
                    session_id: s.id.clone(),
                    title: s.title.clone(),
                    version_id: s.version_id.clone(),
                    pid,
                    started_at,
                    room: s.room,
                    room_port: room_port.filter(|_| s.room),
                });
                true
            }
            _ => false,
        });
        if attached {
            self.persist();
            Some(child)
        } else {
            kill(&child, true);
            None
        }
    }

    /// Uses `path` for `running_games.json` from now on.
    pub fn set_journal(&self, path: PathBuf) {
        self.0.lock().unwrap().journal = Some(path);
    }

    /// Rewrites `running_games.json` with every live process. A failure only costs recovery.
    fn persist(&self) {
        let (path, games) = {
            let inner = self.0.lock().unwrap();
            let Some(path) = inner.journal.clone() else {
                return;
            };
            (path, inner.list.iter().filter(|s| s.child.is_some()).filter_map(|s| s.record.clone()).collect::<Vec<_>>())
        };
        if let Err(error) = recovery::write(&path, &games) {
            tracing::warn!(error = %format!("{error:#}"), "保存运行中的游戏记录失败");
        }
    }

    /// Takes the games in `running_games.json` that still run back into the list, then rewrites
    /// the file with only those. Each one is watched until it exits. Returns how many came back.
    pub fn recover(&self) -> usize {
        self.recover_matching(recovery::matches)
    }

    fn recover_matching(&self, matches: impl Fn(&recovery::Entry) -> bool) -> usize {
        let Some(path) = self.0.lock().unwrap().journal.clone() else {
            return 0;
        };
        let entries = recovery::read(&path).unwrap_or_else(|error| {
            tracing::warn!(error = %format!("{error:#}"), "读取运行中的游戏记录失败，跳过找回游戏");
            vec![]
        });
        let mut recovered = vec![];
        for entry in entries {
            if self.contains(&entry.session_id) || !matches(&entry) {
                continue;
            }
            let (pid, started_at) = (entry.pid, entry.started_at);
            self.with(|inner| {
                let mut console = std::collections::VecDeque::new();
                console.push_back("[RDI] 已恢复对此游戏的管理，之前的游戏输出无法显示".to_owned());
                inner.list.push(Session {
                    id: entry.session_id.clone(),
                    title: entry.title.clone(),
                    version_id: entry.version_id.clone(),
                    preparing: false,
                    stop_requested: false,
                    child: Some(Proc::Recovered { pid, started_at }),
                    exit_message: None,
                    console,
                    cancel: Cancellation::default(),
                    room: entry.room,
                    record: Some(entry.clone()),
                });
            });
            tracing::info!(pid, version = %entry.version_id, room = entry.room, "已找回运行中的游戏");
            recovered.push(entry);
        }
        self.persist();
        for entry in &recovered {
            self.watch_recovered(entry.session_id.clone(), entry.pid, entry.started_at);
        }
        recovered.len()
    }

    /// The port a recovered, still running room game connects to.
    pub fn recovered_room_port(&self) -> Option<u16> {
        let inner = self.0.lock().unwrap();
        inner
            .list
            .iter()
            .filter(|s| s.room && matches!(s.child, Some(Proc::Recovered { .. })))
            .find_map(|s| s.record.as_ref().and_then(|r| r.room_port))
    }

    fn watch_recovered(&self, id: String, pid: u32, started_at: u64) {
        let sessions = self.clone();
        let spawned = std::thread::Builder::new().name("mc-recovered-watch".into()).spawn(move || {
            while sessions.contains(&id) && recovery::identity(pid).is_some_and(|(started, _)| started == started_at) {
                std::thread::sleep(std::time::Duration::from_secs(1));
            }
            // No exit code is known; Kotlin's recovered process reports 0.
            let line = mc_launch::exit_line(Some(0));
            sessions.append(&id, line.clone());
            if !sessions.stop_requested(&id) {
                sessions.mark_exited(&id, Some(line));
            }
        });
        if let Err(error) = spawned {
            tracing::warn!(%error, "启动找回游戏的状态监视失败");
        }
    }

    /// `McGameSession.markExited`.
    pub fn mark_exited(&self, id: &str, message: Option<String>) {
        let had_process = self.with(|inner| {
            let s = inner.list.iter_mut().find(|s| s.id == id)?;
            s.preparing = false;
            s.exit_message = message;
            Some(s.child.take().is_some())
        });
        if had_process == Some(true) {
            self.persist();
        }
        self.check_rooms_idle();
    }

    /// `McGameSession.requestStop`.
    pub fn stop(&self, id: &str, force: bool) -> Result<()> {
        let (child, cancel) = self.with(|inner| {
            let s = inner.list.iter_mut().find(|s| s.id == id).context("该游戏已结束")?;
            s.stop_requested = true;
            Ok::<_, anyhow::Error>((s.child.clone(), s.cancel.clone()))
        })?;
        cancel.cancel();
        match &child {
            Some(Proc::Child(child)) => kill(child, force),
            Some(Proc::Recovered { pid, started_at }) => recovery::kill(*pid, *started_at, force),
            None => {}
        }
        self.mark_exited(id, Some(if force { "已强制结束" } else { "已停止" }.into()));
        Ok(())
    }

    pub fn stop_all(&self, force: bool) {
        let ids: Vec<String> = self.0.lock().unwrap().list.iter().filter(|s| s.alive()).map(|s| s.id.clone()).collect();
        for id in ids {
            if let Err(error) = self.stop(&id, force) {
                tracing::warn!(error = %format!("{error:#}"), "结束游戏失败");
            }
        }
    }

    /// `McPlayStore.aliveCount`, also the running-game guard of installs (`RunningGameGuard`).
    pub fn alive_count(&self, version_id: &str) -> usize {
        self.0.lock().unwrap().list.iter().filter(|s| s.version_id == version_id && s.alive()).count()
    }

    pub fn views(&self) -> Vec<SessionView> {
        self.0
            .lock()
            .unwrap()
            .list
            .iter()
            .map(|s| SessionView {
                id: s.id.clone(),
                title: s.title.clone(),
                version_id: s.version_id.clone(),
                preparing: s.preparing,
                running: s.child.is_some(),
                exit_message: s.exit_message.clone(),
            })
            .collect()
    }

    pub fn contains(&self, id: &str) -> bool {
        self.0.lock().unwrap().list.iter().any(|s| s.id == id)
    }

    /// Console lines with the service's snapshot bound (`boundedConsoleLines`).
    pub fn lines(&self, id: &str) -> Vec<String> {
        let inner = self.0.lock().unwrap();
        let Some(s) = inner.list.iter().find(|s| s.id == id) else {
            return vec![];
        };
        bounded_lines(s.console.iter().cloned().collect())
    }

    pub fn clear(&self, id: &str) {
        self.with(|inner| {
            if let Some(s) = inner.list.iter_mut().find(|s| s.id == id) {
                s.console.clear();
            }
        });
    }
}

/// `boundedConsoleLines`: the newest lines, each cut to 4096 characters, about 1MB in total.
pub fn bounded_lines(lines: Vec<String>) -> Vec<String> {
    let mut remaining: i64 = 1_000_000;
    let mut out = std::collections::VecDeque::new();
    for line in lines.into_iter().rev() {
        let units: Vec<u16> = line.encode_utf16().take(4096).collect();
        let bytes = units.len() as i64 * 6 + 8;
        let bounded = String::from_utf16_lossy(&units);
        if bytes > remaining {
            break;
        }
        remaining -= bytes;
        out.push_front(bounded);
    }
    out.into()
}

/// Java's `destroy` asks the game to end (SIGTERM on macOS/Linux); `destroyForcibly` kills it.
/// On Windows both terminate the process, as in Java.
fn kill(child: &Arc<Mutex<std::process::Child>>, force: bool) {
    let mut child = child.lock().unwrap();
    #[cfg(unix)]
    if !force {
        let pid = child.id().to_string();
        match std::process::Command::new("kill").args(["-TERM", &pid]).status() {
            Ok(status) if status.success() => return,
            Ok(status) => tracing::warn!(?status, "请求结束游戏失败，改为强制结束"),
            Err(error) => tracing::warn!(%error, "请求结束游戏失败，改为强制结束"),
        }
    }
    let _ = force;
    if let Err(error) = child.kill() {
        tracing::warn!(%error, "结束游戏进程失败");
    }
}

/// `OfflineGameAccount.create`: vanilla's `OfflinePlayer:<name>` UUID, token `0`, user type `legacy`.
pub fn offline_account(nickname: &str) -> Result<Account> {
    let name = nickname.trim();
    ensure!(!name.is_empty(), "请输入游戏昵称");
    ensure!(name.encode_utf16().count() <= 16, "游戏昵称最多16个字符");
    ensure!(!name.chars().any(char::is_whitespace), "游戏昵称不能包含空格");
    use md5::Digest as _;
    let digest: [u8; 16] = md5::Md5::digest(format!("OfflinePlayer:{name}").as_bytes()).into();
    let uuid = uuid::Builder::from_md5_bytes(digest).into_uuid();
    Ok(Account { name: name.into(), uuid: uuid.to_string(), access_token: "0".into(), user_type: "legacy".into() })
}

/// `isGameWindowJvmArg`: the window title and icon belong to the launcher.
pub fn is_window_jvm_arg(argument: &str) -> bool {
    matches!(argument.split('=').next(), Some("-Drdi.window.icon" | "-Drdi.window.title"))
}

/// `EarlyDisplayCleanup.clean`: what old launchers left for the removed RDI loading screen.
pub fn clean_early_display(version_dir: &Path) -> Result<()> {
    let config = version_dir.join("config").join("fml.toml");
    if config.is_file() {
        let text = std::fs::read_to_string(&config)?;
        let re = regex::Regex::new(r#"(?m)^([ \t]*earlyWindowProvider[ \t]*=[ \t]*)"rdiearlywindow""#).unwrap();
        let updated = re.replace_all(&text, r#"${1}"fmlearlywindow""#);
        if updated != text {
            std::fs::write(&config, updated.as_bytes())?;
        }
    }
    match std::fs::remove_file(version_dir.join("mods").join("rdi-early-display.jar")) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => Err(error.into()),
        _ => Ok(()),
    }
}

/// `LocalModpackStore.update` for the last play mode; other fields are kept as written.
pub fn remember_offline_name(version_dir: &Path, name: &str) -> Result<()> {
    let file = version_dir.join("rdi-local-pack.json");
    let mut info: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&file).with_context(|| format!("缺少本地整合包信息: {}", file.display()))?)?;
    let object = info.as_object_mut().context("本地整合包信息格式无效")?;
    object.insert("lastAccountMode".into(), "Offline".into());
    object.insert("lastOfflineName".into(), name.into());
    let temporary = version_dir.join(".rdi-local-pack.json.tmp");
    std::fs::write(&temporary, serde_json::to_string(&info)?)?;
    std::fs::rename(&temporary, &file)?;
    Ok(())
}

/// What the start of a local pack needs besides the request.
#[derive(Clone)]
pub struct Deps {
    pub sessions: Sessions,
    pub runtime: tokio::runtime::Handle,
    pub install: crate::mc_install::Deps,
    /// `data.db`, for the pack's launch options.
    pub database: PathBuf,
    /// `config.toml` `maxMemory`.
    pub max_memory_mb: i32,
    pub launcher_version: String,
}

pub struct LocalStart {
    pub version_id: String,
    pub name: String,
    pub mc_version: String,
    pub loader: String,
    pub version_dir: PathBuf,
    pub nickname: String,
    /// Physical pixels of the launcher window, as the service received them.
    pub window: (u32, u32),
    pub allow_duplicate: bool,
    /// The version's sessions that run in the service.
    pub external_alive: usize,
}

pub enum Admitted {
    Started(String),
    Duplicate,
}

/// Starts a local pack offline: validates the name, admits a session and runs the preparation in
/// the background. Errors before admission are returned; later ones go to the session's console.
pub fn start_local(deps: &Deps, request: LocalStart) -> Result<Admitted> {
    let account = offline_account(&request.nickname)?;
    crate::mc_install::meta::runtime(&request.mc_version, &request.loader)?;
    if let Err(error) = remember_offline_name(&request.version_dir, &account.name) {
        tracing::warn!(error = %format!("{error:#}"), version = %request.version_id, "保存整合包的游玩方式失败");
    }
    let title = format!("单机 - {}", request.name);
    let Some(id) = deps.sessions.admit(&title, &request.version_id, request.allow_duplicate, request.external_alive) else {
        return Ok(Admitted::Duplicate);
    };
    let (deps, session) = (deps.clone(), id.clone());
    deps.runtime.clone().spawn(async move {
        let sessions = deps.sessions.clone();
        sessions.append(&session, format!("[RDI] 准备启动 {title}"));
        match run(&deps, &session, &request, &account).await {
            Ok(()) => {}
            Err(_) if sessions.stop_requested(&session) => {}
            Err(error) => {
                tracing::error!(error = %format!("{error:#}"), version = %request.version_id, "游戏启动失败");
                let message = root_message(&error);
                sessions.append(&session, format!("[RDI] 启动前检查失败: {message}"));
                sessions.mark_exited(&session, Some(message));
            }
        }
    });
    Ok(Admitted::Started(id))
}

/// The innermost cause, as Kotlin's `e.message` shows it.
pub fn root_message(error: &anyhow::Error) -> String {
    error.to_string()
}

/// No Java 25 was found; the UI offers the automatic download before starting.
#[derive(Debug)]
pub struct JavaMissing;
impl std::fmt::Display for JavaMissing {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(crate::java_runtime::MISSING_MESSAGE)
    }
}
impl std::error::Error for JavaMissing {}

async fn run(deps: &Deps, session: &str, request: &LocalStart, account: &Account) -> Result<()> {
    let sessions = &deps.sessions;
    let cancel = sessions.cancel(session).context("该游戏已结束")?;
    let stopped = || sessions.stop_requested(session);
    let (database, version_id) = (deps.database.clone(), request.version_id.clone());
    let snapshot = tokio::task::spawn_blocking(move || crate::pack_options::snapshot(&database, &version_id)).await??;
    if stopped() {
        return Ok(());
    }
    // The installer and, without a per-pack Java, the game both need the Java 25 found at startup.
    let jdk = crate::java_runtime::resolved().await.ok_or(JavaMissing)?;
    let mut install = deps.install.clone();
    install.java = jdk.java();
    sessions.append(session, "[RDI] 检查游戏安装...");
    let log = |line: String| sessions.append(session, format!("[RDI] {line}"));
    let target = crate::mc_install::Target {
        mc_version: &request.mc_version,
        loader: &request.loader,
        version_id: &request.version_id,
        version_dir: &request.version_dir,
    };
    let launch = crate::mc_install::prepare_for_launch(&install, &target, &cancel, &log).await?;
    sessions.append(session, "[RDI] 游戏安装已就绪");
    if stopped() {
        return Ok(());
    }
    if let Err(error) = clean_early_display(&request.version_dir) {
        tracing::warn!(error = %format!("{error:#}"), version = %request.version_id, "旧版加载界面清理失败");
        sessions.append(session, format!("[RDI] 旧版加载界面清理失败: {error}"));
    }
    let mut jvm: Vec<String> = snapshot.custom_jvm_args.iter().filter(|a| !is_window_jvm_arg(a)).cloned().collect();
    if let Some(jdwp) = &snapshot.jdwp_jvm_arg {
        jvm.push("-Xlog:os+exit=trace".into());
        jvm.push(jdwp.clone());
    }
    let dirs = install.directories();
    check_launch(&launch, &request.version_dir, &dirs, &install.host).await?;
    let java = jdk.java().to_string_lossy().into_owned();
    let free = || free_memory_mb();
    let command = mc_launch::command(
        &mc_launch::Request {
            mc_version: &request.mc_version,
            version_id: &request.version_id,
            version_dir: &request.version_dir,
            manifest: &launch.manifest,
            loader: &launch.loader,
            account,
            window: request.window,
            java_override: snapshot.java_path.as_deref(),
            max_memory_override: snapshot.max_memory_mb,
            extra_jvm_args: &jvm,
            rdi: None,
            gtnh_root: launch.gtnh_root.as_deref(),
        },
        &mc_launch::Environment {
            directories: &dirs,
            host: &install.host,
            java: &java,
            java_major: 25,
            max_memory_mb: deps.max_memory_mb,
            launcher_version: &deps.launcher_version,
            free_memory_mb: &free,
        },
    )?;
    let masked = mc_launch::mask_secrets(&command);
    tracing::info!(command = %masked.join(" "), "Launch Command");
    if stopped() {
        return Ok(());
    }
    spawn_and_watch(sessions, session, &command, &request.version_dir, &request.version_id, None)
}

/// The start of `launchInternal`: GTNH's lwjgl3ify mod into `mods`, then every library checked.
async fn check_launch(launch: &crate::mc_install::Launch, version_dir: &Path, dirs: &mc_launch::Directories, host: &mc_launch::manifest::Host) -> Result<()> {
    let (m, l, libraries, h) = (launch.manifest.clone(), launch.loader.clone(), dirs.libraries.clone(), host.clone());
    let (root, dir) = (launch.gtnh_root.clone(), version_dir.to_owned());
    tokio::task::spawn_blocking(move || {
        if let Some(root) = &root {
            mc_launch::gtnh::ensure_runtime_mods(&dir, root, &h)?;
        }
        mc_launch::validate_libraries(&m, &l, &libraries, &h)
    })
    .await?
}

/// Starts the command, attaches the process to the session and adds the exit line when it ends
/// (`readProcessOutput`). A session stopped meanwhile kills the new process instead.
fn spawn_and_watch(sessions: &Sessions, session: &str, command: &[String], version_dir: &Path, version_id: &str, room_port: Option<u16>) -> Result<()> {
    let (lines, id) = (sessions.clone(), session.to_owned());
    let started = mc_launch::spawn(command, version_dir, move |line| {
        if !line.is_empty() {
            lines.append(&id, line);
        }
    })?;
    let pid = started.child.id();
    let Some(child) = sessions.attach(session, started.child, room_port) else {
        return Ok(());
    };
    tracing::info!(pid, version = %version_id, "游戏已启动");
    let (sessions, id) = (sessions.clone(), session.to_owned());
    std::thread::Builder::new()
        .name("mc-exit-watch".into())
        .spawn(move || {
            let status = loop {
                match child.lock().unwrap().try_wait() {
                    Ok(Some(status)) => break Some(status),
                    Ok(None) => {}
                    Err(error) => {
                        tracing::warn!(%error, "读取游戏进程状态失败");
                        break None;
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(200));
            };
            let line = mc_launch::exit_line(status.and_then(|s| s.code()));
            sessions.append(&id, line.clone());
            if !sessions.stop_requested(&id) {
                sessions.mark_exited(&id, Some(line));
            }
        })
        .context("启动游戏状态监视失败")?;
    Ok(())
}

fn free_memory_mb() -> u64 {
    match sysinfo::System::new() {
        Ok(mut system) => {
            system.refresh_memory();
            system.available_memory() / (1024 * 1024)
        }
        Err(error) => {
            tracing::warn!(%error, "读取剩余内存失败，按8GiB计算");
            8192
        }
    }
}
