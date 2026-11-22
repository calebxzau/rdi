//! J5a: game sessions in Rust (`McPlayStore`, `McGameSession`, `RunningGameGuard`) and the local
//! pack start of `McPlayController.startSession` for `withRdiCore = false` with an offline
//! identity. Rooms, RDI packs and Microsoft online play still start through the service; their
//! sessions are mirrored in from `game.state` by the app so one list covers both.
//! No Slint types here, so the planned launcher MCP can call these functions.
#[cfg(test)]
mod tests;

use crate::mc_launch::{self, Account};
use crate::tasks::Cancellation;
use anyhow::{Context, Result, ensure};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

const MAX_LOG_LINES: usize = 5000;

/// One game: preparing until its process is attached, then running until it exits.
struct Session {
    id: String,
    title: String,
    version_id: String,
    preparing: bool,
    stop_requested: bool,
    child: Option<Arc<Mutex<std::process::Child>>>,
    exit_message: Option<String>,
    console: std::collections::VecDeque<String>,
    cancel: Cancellation,
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

    fn cancel(&self, id: &str) -> Option<Cancellation> {
        self.0.lock().unwrap().list.iter().find(|s| s.id == id).map(|s| s.cancel.clone())
    }

    pub fn stop_requested(&self, id: &str) -> bool {
        self.0.lock().unwrap().list.iter().find(|s| s.id == id).is_none_or(|s| s.stop_requested)
    }

    /// `McGameSession.attachProcess`: a session stopped meanwhile kills the new process instead.
    fn attach(&self, id: &str, child: std::process::Child) -> Option<Arc<Mutex<std::process::Child>>> {
        let child = Arc::new(Mutex::new(child));
        let attached = self.with(|inner| match inner.list.iter_mut().find(|s| s.id == id) {
            Some(s) if !s.stop_requested => {
                s.child = Some(child.clone());
                s.preparing = false;
                true
            }
            _ => false,
        });
        if attached {
            Some(child)
        } else {
            kill(&child, true);
            None
        }
    }

    /// `McGameSession.markExited`.
    pub fn mark_exited(&self, id: &str, message: Option<String>) {
        self.with(|inner| {
            if let Some(s) = inner.list.iter_mut().find(|s| s.id == id) {
                s.preparing = false;
                s.child = None;
                s.exit_message = message;
            }
        });
    }

    /// `McGameSession.requestStop`.
    pub fn stop(&self, id: &str, force: bool) -> Result<()> {
        let (child, cancel) = self.with(|inner| {
            let s = inner.list.iter_mut().find(|s| s.id == id).context("该游戏已结束")?;
            s.stop_requested = true;
            Ok::<_, anyhow::Error>((s.child.clone(), s.cancel.clone()))
        })?;
        cancel.cancel();
        if let Some(child) = &child {
            kill(child, force);
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
    crate::mc_install::meta::loader(&request.mc_version, &request.loader)?;
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
    let (manifest, loader) = crate::mc_install::prepare_for_launch(&install, &request.mc_version, &request.loader, &cancel, &log).await?;
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
    let (m, l, libraries, host) = (manifest.clone(), loader.clone(), dirs.libraries.clone(), install.host.clone());
    tokio::task::spawn_blocking(move || mc_launch::validate_libraries(&m, &l, &libraries, &host)).await??;
    let java = jdk.java().to_string_lossy().into_owned();
    let free = || free_memory_mb();
    let command = mc_launch::command(
        &mc_launch::Request {
            mc_version: &request.mc_version,
            version_id: &request.version_id,
            version_dir: &request.version_dir,
            manifest: &manifest,
            loader: &loader,
            account,
            window: request.window,
            java_override: snapshot.java_path.as_deref(),
            max_memory_override: snapshot.max_memory_mb,
            extra_jvm_args: &jvm,
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
    let (lines, id) = (sessions.clone(), session.to_owned());
    let started = mc_launch::spawn(&command, &request.version_dir, move |line| {
        if !line.is_empty() {
            lines.append(&id, line);
        }
    })?;
    let pid = started.child.id();
    let Some(child) = sessions.attach(session, started.child) else {
        return Ok(());
    };
    tracing::info!(pid, version = %request.version_id, "游戏已启动");
    // `readProcessOutput`: the exit line once the process ends.
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
