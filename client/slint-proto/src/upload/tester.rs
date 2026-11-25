//! Stage A6c of the launcher MCP plan: the processes of the upload tests, ported from
//! `ModpackTestSession` (`start`, `runClientTest`, `runServerTest`, `handleClientLine`,
//! `handleServerLine`, `stop`, `close`), `McModpackTestLauncher` and `DesktopModpackTestProcess`
//! (`ModpackTestAdapter.kt`) and `MinecraftLauncher.launchServer`.
//!
//! Each run is a launcher task (owner decision E6). The client starts like a room game with the
//! game runtime (Kotlin `rdiRuntime = true`) and the RDI account, but without the RDI core, the
//! room link or the window title; the server is the local test server of `mc_install::server`,
//! installed first when it is missing (Kotlin's separate 下载测试服务端 task, inline here).
use super::testrun::{self, Line, State, Status, Target};
use crate::mc_install::server::{self as test_server, ServerRuntime};
use crate::pack_install::extras::Content;
use crate::pack_install::mods::Mod;
use crate::play::room::RoomDeps;
use crate::tasks::{Cancellation, Context};
use anyhow::{Result, anyhow, bail, ensure};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

const FABRIC_STOP_WAIT: Duration = Duration::from_secs(20);
const FORCE_STOP_WAIT: Duration = Duration::from_secs(5);
const POLL: Duration = Duration::from_millis(200);

/// A started test process (`DesktopModpackTestProcess`).
pub struct Proc {
    child: Mutex<std::process::Child>,
    stdin: Mutex<Option<std::process::ChildStdin>>,
    /// The Fabric server is asked to `stop` before it is killed.
    graceful: bool,
}

impl Proc {
    pub(super) fn new(mut child: std::process::Child, graceful: bool) -> Self {
        let stdin = child.stdin.take();
        Self { child: Mutex::new(child), stdin: Mutex::new(stdin), graceful }
    }

    /// `Some(code)` once the process has ended.
    fn exited(&self) -> Option<Option<i32>> {
        match self.child.lock().unwrap_or_else(PoisonError::into_inner).try_wait() {
            Ok(Some(status)) => Some(status.code()),
            Ok(None) => None,
            Err(error) => {
                tracing::warn!(%error, "读取整合包测试进程状态失败");
                Some(None)
            }
        }
    }

    pub(super) fn alive(&self) -> bool {
        self.exited().is_none()
    }

    fn ended_within(&self, limit: Duration) -> bool {
        let until = Instant::now() + limit;
        while self.alive() {
            if Instant::now() >= until {
                return false;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        true
    }

    fn kill(&self) {
        if let Err(error) = self.child.lock().unwrap_or_else(PoisonError::into_inner).kill() {
            tracing::debug!(%error, "结束整合包测试进程");
        }
    }

    /// `DesktopModpackTestProcess.stop`.
    pub fn stop(&self) -> Result<()> {
        if !self.alive() {
            return Ok(());
        }
        let mut input_failure = None;
        if self.graceful {
            use std::io::Write;
            let sent = match self.stdin.lock().unwrap_or_else(PoisonError::into_inner).take() {
                Some(mut stdin) => stdin.write_all(b"stop\n").and_then(|()| stdin.flush()),
                None => Err(std::io::Error::other("标准输入已关闭")),
            };
            match sent {
                Ok(()) if self.ended_within(FABRIC_STOP_WAIT) => return Ok(()),
                Ok(()) => {}
                Err(error) => input_failure = Some(error),
            }
        }
        self.kill();
        ensure!(self.ended_within(FORCE_STOP_WAIT), "无法停止测试服务端进程");
        if let Some(error) = input_failure {
            bail!("无法向Fabric测试服务端发送stop命令: {error}");
        }
        Ok(())
    }
}

/// One target's test: its state and the current run (`activeRun`, `process`, `testDir`).
#[derive(Default)]
struct Slot {
    state: State,
    /// The number of the current run; output and results of older runs are ignored.
    run: u64,
    active: bool,
    crashed: bool,
    stop_requested: bool,
    started: Option<Instant>,
    /// The run's mods signature, recorded when it passes.
    signature: String,
    process: Option<Arc<Proc>>,
    cancel: Option<Cancellation>,
    dir: Option<PathBuf>,
}

impl Slot {
    fn running(&self) -> bool {
        self.active || self.process.as_ref().is_some_and(|p| p.alive())
    }
}

#[derive(Default)]
struct Inner {
    client: Slot,
    server: Slot,
    closed: bool,
}

impl Inner {
    fn slot(&mut self, target: Target) -> &mut Slot {
        match target {
            Target::Client => &mut self.client,
            Target::Server => &mut self.server,
        }
    }
}

/// The client and server tests of one draft; cheap to clone.
#[derive(Clone, Default)]
pub struct Tests(Arc<Mutex<Inner>>);

impl Tests {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub fn state(&self, target: Target) -> State {
        self.lock().slot(target).state.clone()
    }

    pub fn running(&self, target: Target) -> bool {
        self.lock().slot(target).running()
    }

    pub fn busy(&self) -> bool {
        self.running(Target::Client) || self.running(Target::Server)
    }

    /// `onModsChanged` of both tests.
    pub fn mods_changed(&self) {
        let mut inner = self.lock();
        inner.client.state.mods_changed();
        inner.server.state.mods_changed();
    }

    /// `replaceTesters`: a new server pack starts both tests over.
    pub fn reset(&self) {
        let mut inner = self.lock();
        for target in [Target::Client, Target::Server] {
            let slot = inner.slot(target);
            let task = slot.state.task.take();
            slot.state = State::default();
            slot.state.task = task;
        }
    }

    pub fn view(&self) -> Value {
        let inner = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        json!({"client": inner.client.state.view(), "server": inner.server.state.view(), "client_test_hint": testrun::CLIENT_HINT})
    }

    /// `close`: no new run starts, running ones are cancelled and their processes stopped.
    /// Returns once nothing runs or after `limit`; `true` when nothing runs any more.
    pub fn close(&self, limit: Duration) -> bool {
        let processes: Vec<Arc<Proc>> = {
            let mut inner = self.lock();
            inner.closed = true;
            [Target::Client, Target::Server]
                .into_iter()
                .filter_map(|target| {
                    let slot = inner.slot(target);
                    slot.stop_requested = true;
                    if let Some(cancel) = &slot.cancel {
                        cancel.cancel();
                    }
                    slot.process.clone()
                })
                .collect()
        };
        for process in processes {
            if let Err(error) = process.stop() {
                tracing::warn!(error = %format!("{error:#}"), "关闭整合包测试进程失败");
            }
        }
        let until = Instant::now() + limit;
        while self.busy() && Instant::now() < until {
            std::thread::sleep(POLL);
        }
        !self.busy()
    }

    /// `start` up to the job: refuses a second run, then marks a new one running.
    pub(super) fn begin(&self, target: Target, signature: String) -> Result<u64> {
        let mut inner = self.lock();
        ensure!(!inner.closed, "整合包测试会话已关闭");
        let slot = inner.slot(target);
        ensure!(!slot.running(), "{}", if target == Target::Client { "测试客户端已经在运行中" } else { "测试服务器已经在运行中" });
        slot.run += 1;
        slot.active = true;
        slot.crashed = false;
        slot.stop_requested = false;
        slot.started = Some(Instant::now());
        slot.signature = signature;
        slot.process = None;
        slot.cancel = None;
        slot.state = State::default();
        slot.state.status = Status::Running;
        slot.state.push_line(if target == Target::Client { "[RDI] 启动客户端测试..." } else { "[RDI] 启动测试服务器..." });
        Ok(slot.run)
    }

    fn current(&self, target: Target, run: u64) -> bool {
        let mut inner = self.lock();
        let closed = inner.closed;
        let slot = inner.slot(target);
        !closed && slot.run == run && slot.active
    }

    /// `ensureRunActive`.
    fn check(&self, target: Target, run: u64, cancel: &Cancellation) -> Result<()> {
        cancel.check()?;
        ensure!(self.current(target, run) && !self.lock().slot(target).stop_requested, "整合包测试已结束");
        Ok(())
    }

    /// `emitLog`: only the current run reaches the console.
    fn log(&self, target: Target, run: u64, line: &str) {
        let mut inner = self.lock();
        let slot = inner.slot(target);
        if slot.run == run && slot.active {
            slot.state.push_line(line);
        }
    }

    fn set_task(&self, target: Target, run: u64, task: &str, cancel: Cancellation) {
        let mut inner = self.lock();
        let closed = inner.closed;
        let slot = inner.slot(target);
        if slot.run == run {
            slot.state.task = Some(task.to_owned());
            slot.cancel = Some(cancel.clone());
        }
        if closed || slot.stop_requested {
            cancel.cancel();
        }
    }

    /// The run's new folder; the target's folder of the run before goes first.
    fn new_dir(&self, target: Target, run: u64, make: impl FnOnce() -> Result<PathBuf>) -> Result<PathBuf> {
        let previous = self.lock().slot(target).dir.take();
        if let Some(previous) = previous {
            super::reader::remove_tree(&previous);
        }
        let dir = make()?;
        let mut inner = self.lock();
        let slot = inner.slot(target);
        slot.dir = Some(dir.clone());
        ensure!(slot.run == run && slot.active, "整合包测试已结束");
        Ok(dir)
    }

    /// `handleClientLine` and `handleServerLine`.
    pub(super) fn line(&self, target: Target, run: u64, line: &str, hint: &str) {
        let verdict = {
            let mut inner = self.lock();
            let closed = inner.closed;
            let slot = inner.slot(target);
            if closed || slot.run != run || !slot.active {
                return;
            }
            slot.state.push_line(line);
            if slot.state.status != Status::Running {
                return;
            }
            let verdict = if target == Target::Client { testrun::client_line(line) } else { testrun::server_line(line) };
            match &verdict {
                Line::Passed(seconds) => {
                    let elapsed = slot.started.map(|s| s.elapsed().as_secs_f64()).unwrap_or_default();
                    slot.state.status = Status::Passed;
                    slot.state.pass_seconds = Some(seconds.clone().unwrap_or_else(|| format!("{elapsed:.1}")));
                    slot.state.signature = Some(slot.signature.clone());
                    slot.state.error = None;
                    if target == Target::Client {
                        slot.state.push_line("[RDI] 客户端测试通过");
                    }
                }
                Line::Crashed => {
                    slot.crashed = true;
                    slot.state.status = Status::Failed;
                }
                Line::MissingServerFiles => slot.state.push_line(hint),
                Line::Other => {}
            }
            verdict
        };
        match verdict {
            Line::Passed(_) => self.terminate_after(target, run, if target == Target::Client { 500 } else { 1000 }),
            Line::Crashed => self.terminate_after(target, run, 1000),
            _ => {}
        }
    }

    /// `terminateProcessAfter`.
    fn terminate_after(&self, target: Target, run: u64, millis: u64) {
        let tests = self.clone();
        let spawned = std::thread::Builder::new().name("modpack-test-stop".into()).spawn(move || {
            std::thread::sleep(Duration::from_millis(millis));
            let process = {
                let mut inner = tests.lock();
                let slot = inner.slot(target);
                if slot.run == run { slot.process.clone() } else { None }
            };
            if let Some(process) = process
                && let Err(error) = process.stop()
            {
                tracing::warn!(error = %format!("{error:#}"), "终止整合包测试进程失败");
            }
        });
        if let Err(error) = spawned {
            tracing::warn!(%error, "终止整合包测试进程失败");
        }
    }

    /// `launchAndRegisterProcess`: `false` when the run ended meanwhile (the caller stops it).
    pub(super) fn attach(&self, target: Target, run: u64, process: Arc<Proc>) -> bool {
        let decided = {
            let mut inner = self.lock();
            let closed = inner.closed;
            let slot = inner.slot(target);
            if closed || slot.run != run || !slot.active || slot.stop_requested {
                return false;
            }
            slot.process = Some(process);
            slot.state.status != Status::Running
        };
        // The marker or a crash line came before the process was registered.
        if decided {
            self.terminate_after(target, run, 500);
        }
        true
    }

    /// `stop` of a running run, for a cancelled task.
    pub(super) fn request_stop(&self, target: Target, run: u64) {
        let mut inner = self.lock();
        let slot = inner.slot(target);
        if slot.run != run {
            return;
        }
        slot.stop_requested = true;
        if slot.state.status == Status::Running {
            slot.state.status = Status::Stopped;
        }
        slot.state.push_line(if target == Target::Client { "[RDI] 已发送停止客户端测试指令" } else { "[RDI] 已发送停止测试服务器指令" });
    }

    /// `completeProcess` and the end of the job: the final state of the run.
    pub(super) fn end(&self, target: Target, run: u64, exit: Option<Option<i32>>, failure: Option<String>) -> State {
        let mut inner = self.lock();
        let slot = inner.slot(target);
        if slot.run != run {
            return slot.state.clone();
        }
        slot.process = None;
        slot.cancel = None;
        slot.active = false;
        if let Some(message) = failure {
            if slot.state.status != Status::Passed {
                slot.state.status = Status::Failed;
                slot.state.error = Some(message);
            }
        } else if slot.state.status != Status::Passed {
            let failed = slot.crashed;
            if let Some(code) = exit
                && failed
                && code != Some(0)
            {
                let label = if target == Target::Client { "客户端测试异常退出" } else { "测试服务器异常退出" };
                slot.state.error = Some(format!("{label}: {}", code.unwrap_or(-1)));
            }
            slot.state.status = if failed { Status::Failed } else { Status::Stopped };
        }
        slot.state.clone()
    }
}

/// What one run needs from the draft.
pub struct Input {
    pub target: Target,
    /// The draft, for the task's dedupe key.
    pub draft_id: String,
    /// The pack's name, for the task title.
    pub name: String,
    /// `LoadedLocalModpack.sourceDir`.
    pub source: PathBuf,
    /// Where the test folders go (inside the draft's work folder).
    pub work: PathBuf,
    /// The draft's mods; the run processes them as `start` does.
    pub mods: Vec<Mod>,
    pub fabric: bool,
    /// The client extras (resource and shader packs).
    pub extras: Vec<Content>,
    /// `V201`, as the draft names the version.
    pub mc_label: String,
    /// `1.20.1`.
    pub mc_version: String,
    /// `forge`, `neoforge` or `Fabric`.
    pub loader: String,
}

/// What the processes need from the launcher: the room deps (install, content store, game
/// runtime, account) and the window size a game started from the UI gets.
pub struct Needs {
    pub room: RoomDeps,
    pub window: (u32, u32),
}

pub fn task_key(draft_id: &str, target: Target) -> String {
    format!("modpack-test:{draft_id}:{}", target.name())
}

fn steps(target: Target) -> &'static [&'static str] {
    match target {
        Target::Client => &["准备测试客户端", "运行客户端测试"],
        Target::Server => &["下载测试服务端", "准备测试服务器", "运行服务端测试"],
    }
}

/// `start`: marks the run running and submits its task; returns the task id.
pub fn start(tests: &Tests, input: Input, needs: Needs) -> Result<String> {
    let target = input.target;
    let mods = testrun::run_mods(&input.mods, input.fabric);
    let run = tests.begin(target, testrun::signature(&mods))?;
    let title = format!("{} {}", target.label(), input.name);
    let key = task_key(&input.draft_id, target);
    let (tasks, handle) = (needs.room.install.tasks.clone(), needs.room.install.runtime.clone());
    let worker = tests.clone();
    let task = tasks.submit_steps(&handle, title, key, steps(target), move |ctx| {
        let result = execute(&worker, run, &input, &mods, &needs, &ctx);
        let cancelled = ctx.cancel.cancelled();
        let failure = match &result {
            Ok(_) => None,
            Err(_) if cancelled => None,
            Err(error) => {
                tracing::error!(error = %format!("{error:#}"), "整合包测试失败");
                let prefix = if target == Target::Client { "启动客户端测试失败" } else { "启动测试服务器失败" };
                Some(format!("{prefix}: {error}"))
            }
        };
        let exit = result.as_ref().ok().copied();
        if cancelled && result.is_err() {
            worker.request_stop(target, run);
        }
        let state = worker.end(target, run, exit, failure);
        match state.status {
            Status::Passed => {
                ctx.finish(format!("{}通过，用时{}秒", target.label(), state.pass_seconds.unwrap_or_default()));
                Ok(())
            }
            _ if cancelled => Err(anyhow!("任务已取消")),
            _ => Err(anyhow!(state.error.unwrap_or_else(|| format!("{}已停止，未通过", target.label())))),
        }
    });
    // Closing the draft cancels the run through its task's cancellation.
    tests.set_task(target, run, &task, tasks.cancellation(&task).unwrap_or_default());
    Ok(task)
}

/// The run up to the process exit; returns the exit code.
fn execute(tests: &Tests, run: u64, input: &Input, mods: &[Mod], needs: &Needs, ctx: &Context) -> Result<Option<i32>> {
    let target = input.target;
    let deps = &needs.room;
    let cancel = &ctx.cancel;
    let handle = deps.install.runtime.clone();
    let say = |line: String| {
        tests.log(target, run, &format!("[RDI] {line}"));
        ctx.progress(line, None);
    };
    tests.check(target, run, cancel)?;
    let jdk = handle.block_on(crate::java_runtime::resolved()).ok_or_else(|| anyhow!(crate::java_runtime::MISSING_MESSAGE))?;
    let mut install = deps.play.install.clone();
    install.java = jdk.java();

    let (command, dir, graceful) = match target {
        Target::Client => {
            ctx.step(0);
            let dir = tests.new_dir(target, run, || testrun::create_client_dir(&input.source, &input.work, &input.mc_label))?;
            materialize_mods(deps, &dir, mods, cancel, &say)?;
            testrun::prepare_client_content(&dir, &input.source, mods, &input.mc_label)?;
            materialize_extras(deps, &dir, &input.extras, cancel, &say)?;
            testrun::write_options(&dir, &input.mc_label)?;
            tests.check(target, run, cancel)?;
            let command = client_command(input, needs, &install, &dir, cancel, &say)?;
            (command, dir, false)
        }
        Target::Server => {
            ctx.step(0);
            let runtime = ServerRuntime::resolve(&input.mc_version, &input.loader)
                .ok_or_else(|| anyhow!("不支持{} {}测试服务端", input.mc_version, input.loader))?;
            let (i, r) = (install.clone(), runtime);
            if handle.block_on(tokio::task::spawn_blocking(move || test_server::installed(&i, &r)))? {
                say("测试服务端已就绪".into());
            } else {
                say(runtime.task_title());
                let titles = runtime.steps();
                let step = |index: usize| say(format!("== {}", titles[index]));
                handle.block_on(test_server::install(&install, &runtime, cancel, &step, &say))?;
                let (i, r) = (install.clone(), runtime);
                ensure!(handle.block_on(tokio::task::spawn_blocking(move || test_server::installed(&i, &r)))?, "测试服务端下载后仍不完整，请重试");
            }
            tests.check(target, run, cancel)?;
            ctx.step(1);
            let libraries = install.libraries();
            let dir = tests.new_dir(target, run, || testrun::create_server_dir(&input.source, &input.work, &libraries))?;
            materialize_mods(deps, &dir, mods, cancel, &say)?;
            testrun::prepare_server_content(&dir, &input.source, mods)?;
            tests.check(target, run, cancel)?;
            let command = server_command(&runtime, &install, &dir)?;
            (command, dir, runtime == ServerRuntime::Fabric)
        }
    };
    tracing::info!(command = %crate::mc_launch::mask_secrets(&command).join(" "), "整合包测试启动命令");
    ctx.step(steps(target).len() - 1);
    ctx.progress(if target == Target::Client { testrun::CLIENT_HINT } else { "测试服务器启动中" }, None);

    let hint = format!("{}-{}缺少测试服务端文件，请重新开始服务端测试", input.mc_version, input.loader);
    let (lines, id) = (tests.clone(), run);
    let on_line = move |line: String| {
        if !line.is_empty() {
            lines.line(target, id, &line, &hint);
        }
    };
    let started = if graceful { crate::mc_launch::spawn_with_input(&command, &dir, on_line)? } else { crate::mc_launch::spawn(&command, &dir, on_line)? };
    let process = Arc::new(Proc::new(started.child, graceful));
    if !tests.attach(target, run, process.clone()) {
        process.stop()?;
        bail!("整合包测试已取消");
    }
    let mut stopping = false;
    loop {
        if let Some(code) = process.exited() {
            return Ok(code);
        }
        if cancel.cancelled() && !stopping {
            stopping = true;
            tests.request_stop(target, run);
            if let Err(error) = process.stop() {
                tracing::warn!(error = %format!("{error:#}"), "停止整合包测试失败");
            }
        }
        std::thread::sleep(POLL);
    }
}

/// `modpackTestModSourceResolver`: the run's mods linked from the content store.
fn materialize_mods(deps: &RoomDeps, dir: &Path, mods: &[Mod], cancel: &Cancellation, say: &(dyn Fn(String) + Sync)) -> Result<()> {
    let requests = crate::pack_install::mod_download_requests(&deps.install, mods)?;
    let progress = |_: crate::download::Progress| {};
    deps.install.runtime.block_on(deps.install.store.materialize(&requests, &dir.join(testrun::MOD_SOURCES), cancel, &progress))?;
    say(format!("已准备{}个Mod", mods.len()));
    Ok(())
}

/// `modpackTestClientExtraResolver` (`materializeClientExtras`): extras whose file is already in
/// the test folder are kept.
fn materialize_extras(deps: &RoomDeps, dir: &Path, extras: &[Content], cancel: &Cancellation, say: &(dyn Fn(String) + Sync)) -> Result<()> {
    if extras.is_empty() {
        return Ok(());
    }
    let mut missing = Vec::new();
    for extra in crate::pack_install::extras::merge(extras)? {
        let relative = crate::pack_install::extras::normalize_path(&extra.target()?)?;
        match std::fs::symlink_metadata(dir.join(&relative)) {
            Ok(meta) => ensure!(meta.is_file(), "客户端额外内容目标不是安全的普通文件: {relative}"),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => missing.push(extra),
            Err(error) => return Err(error.into()),
        }
    }
    if !missing.is_empty() {
        say(format!("准备{}个资源包和光影", missing.len()));
        let requests = crate::pack_install::extra_download_requests(&deps.install, &missing)?;
        let progress = |_: crate::download::Progress| {};
        deps.install.runtime.block_on(deps.install.store.materialize(&requests, dir, cancel, &progress))?;
    }
    Ok(())
}

/// `prepareClientLoader` and `prepareClientLaunch`: the install, the game runtime and the
/// command of a room game without the RDI core.
fn client_command(input: &Input, needs: &Needs, install: &crate::mc_install::Deps, dir: &Path, cancel: &Cancellation, say: &(dyn Fn(String) + Sync)) -> Result<Vec<String>> {
    use crate::mc_launch;
    let deps = &needs.room;
    let handle = &deps.install.runtime;
    let version_id = dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let target = crate::mc_install::Target { mc_version: &input.mc_version, loader: &input.loader, version_id: &version_id, version_dir: dir };
    let launch = handle.block_on(crate::mc_install::prepare_for_launch(install, &target, cancel, say))?;
    if let Err(error) = crate::play::clean_early_display(dir) {
        tracing::warn!(error = %format!("{error:#}"), "旧版加载界面清理失败");
    }
    let runtime = crate::play::runtime::GameRuntime::load(&deps.runtime_dir)?;
    let selection = crate::play::runtime::Selection::for_room(&input.mc_version, &input.loader);
    if selection.kotlin {
        crate::play::thin::thin_mods(&dir.join("mods"), &deps.tools.join("kotlin-runtime")).map_err(|e| anyhow!("Kotlin运行库准备失败：{e}"))?;
    }
    say("准备游戏媒体运行库".into());
    let natives = if selection.media {
        Some(runtime.prepare_media_natives(&deps.tools).map_err(|e| {
            tracing::error!(error = %format!("{e:#}"), "Minecraft媒体模块不可用");
            anyhow!("媒体模块缺失或损坏，请先修复")
        })?)
    } else {
        None
    };
    let dirs = install.directories();
    handle.block_on(crate::play::check_launch(&launch, dir, &dirs, &install.host))?;
    let account = mc_launch::Account {
        name: deps.account_name.clone(),
        uuid: crate::play::room::uuid_of(&deps.uid)?,
        access_token: deps.install.server.jwt().unwrap_or_default().to_owned(),
        user_type: "msa".into(),
    };
    let java = install.java.to_string_lossy().into_owned();
    let classpath = runtime.classpath_for(selection);
    let free = || crate::play::free_memory_mb();
    mc_launch::command(
        &mc_launch::Request {
            mc_version: &input.mc_version,
            version_id: &version_id,
            version_dir: dir,
            manifest: &launch.manifest,
            loader: &launch.loader,
            account: &account,
            window: needs.window,
            java_override: None,
            max_memory_override: None,
            extra_jvm_args: &[],
            extra_game_args: &[],
            rdi: Some(mc_launch::RdiRuntime { loader: &input.loader, classpath: &classpath, media_natives: natives.as_deref() }),
            gtnh_root: None,
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
    )
}

/// `MinecraftLauncher.launchServer` and `startServerDesktop`; writes `eula.txt`.
pub fn server_command(runtime: &ServerRuntime, install: &crate::mc_install::Deps, dir: &Path) -> Result<Vec<String>> {
    let mut command = vec![install.java.to_string_lossy().into_owned(), crate::mc_launch::JAVA4SHELL_MITIGATION.into(), "-Xmx6G".into(), "-Xms6G".into()];
    command.extend(crate::mc_launch::UTF8_LOGGING.iter().map(|s| s.to_string()));
    match runtime {
        ServerRuntime::Fabric => {
            test_server::stage_fabric_runtime(install, dir)?;
            ensure!(
                dir.join(test_server::FABRIC_SERVER_LAUNCHER_JAR).is_file() && dir.join(test_server::FABRIC_SERVER_JAR).is_file(),
                "Fabric测试服务端运行文件不完整，请先下载测试服务端"
            );
            command.extend(["--enable-native-access=ALL-UNNAMED".into(), "-jar".into(), test_server::FABRIC_SERVER_LAUNCHER_JAR.into(), "nogui".into()]);
        }
        ServerRuntime::Installer(_) => {
            let args = runtime.args_file(test_server::unix(install)).unwrap_or_default();
            command.extend([format!("@libraries/{args}"), "%*".into(), "--nogui".into()]);
        }
    }
    std::fs::write(dir.join("eula.txt"), "eula=true")?;
    Ok(command)
}
