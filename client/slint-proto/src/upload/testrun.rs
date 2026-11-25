//! Stage A6b of the launcher MCP plan: the client and server test runs of an upload draft,
//! without their processes. Ported from the file functions of `ModpackTestSession.kt`
//! (`client/modpack-test`): the test folders, the line rules that pass or fail a run, and the
//! run state with its mods signature.
//!
//! The client test is as Kotlin's (owner decision E10): the pack starts without the RDI core,
//! and it passes once the player has created a singleplayer world and sent
//! `开始运行客户端测试` in chat, whose chat line reaches the game output. The server test passes on
//! the dedicated server's `Done (<s>s)! For help`.
//!
//! Test folders live under the draft's work folder (Kotlin: `pack-proc` itself), so they go with
//! the draft. Kotlin makes a new folder for every run and removes only the last one when the page
//! closes; Rust removes a target's previous folder when its next run starts.
use crate::pack_install::mods::{Mod, Side, process_mods};
use anyhow::{Context, Result, bail, ensure};
use serde_json::{Value, json};
use std::collections::{BTreeSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

/// `CLIENT_TEST_SUCCESS_MARKER`.
pub const CLIENT_SUCCESS_MARKER: &str = "开始运行客户端测试";
/// What the player is asked to do during the client test (the Compose page's hint).
pub const CLIENT_HINT: &str = "创建单机存档，在聊天框发送开始运行客户端测试";
const CLIENT_ONLY_MARK_PREFIX: &str = "C$$_";
const CLIENT_VERSION_PREFIX: &str = "_rdi_client_test_";
const SERVER_DIR_PREFIX: &str = "servertest-";
/// The mods of a run, linked from the content store (`modpackTestModSourceResolver`).
pub const MOD_SOURCES: &str = ".rdi-mod-sources";
/// Console lines kept per test for the agent (owner decision E12).
pub const KEPT_LINES: usize = 40;
const LINE_LIMIT: usize = 2000;

const SERVER_CRASH_WORDS: [&str; 4] = ["Preparing crash report", "Failed to start the minecraft server", "Minecraft Crash Report", "Missing or unsupported mandatory dependencies"];
const CLIENT_CRASH_WORDS: [&str; 4] = ["Preparing crash report", "MixinTransformerError", "Mod Loading has failed", "Missing mandatory dependencies"];
static SERVER_PASS: LazyLock<regex::Regex> = LazyLock::new(|| regex::Regex::new(r"Done \((\d+(?:\.\d+)?)s\)! For help").unwrap());

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Target {
    Client,
    Server,
}

impl Target {
    pub fn parse(text: &str) -> Result<Self> {
        Ok(match text.trim().to_ascii_lowercase().as_str() {
            "client" => Self::Client,
            "server" => Self::Server,
            other => bail!("测试类型必须是client或server：{other}"),
        })
    }
    pub fn name(self) -> &'static str {
        match self {
            Self::Client => "client",
            Self::Server => "server",
        }
    }
    /// `客户端测试`, `服务端测试`.
    pub fn label(self) -> &'static str {
        match self {
            Self::Client => "客户端测试",
            Self::Server => "服务端测试",
        }
    }
}

/// `ModpackTestStatus`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Status {
    #[default]
    NotRun,
    Running,
    Passed,
    Failed,
    Stopped,
}

impl Status {
    pub fn name(self) -> &'static str {
        match self {
            Self::NotRun => "not_run",
            Self::Running => "running",
            Self::Passed => "passed",
            Self::Failed => "failed",
            Self::Stopped => "stopped",
        }
    }
}

/// What one output line means for a running test.
#[derive(Clone, Debug, PartialEq)]
pub enum Line {
    /// Passed; the server says its own start time.
    Passed(Option<String>),
    Crashed,
    /// `Error: could not open`: the args file of the test server is missing.
    MissingServerFiles,
    Other,
}

fn contains_any(line: &str, words: &[&str]) -> bool {
    let lower = line.to_lowercase();
    words.iter().any(|w| lower.contains(&w.to_lowercase()))
}

/// `handleClientLine`.
pub fn client_line(line: &str) -> Line {
    if line.contains(CLIENT_SUCCESS_MARKER) {
        Line::Passed(None)
    } else if contains_any(line, &CLIENT_CRASH_WORDS) {
        Line::Crashed
    } else {
        Line::Other
    }
}

/// `handleServerLine`.
pub fn server_line(line: &str) -> Line {
    if let Some(found) = SERVER_PASS.captures(line) {
        Line::Passed(found.get(1).map(|m| m.as_str().to_owned()))
    } else if line.contains("Error: could not open") {
        Line::MissingServerFiles
    } else if contains_any(line, &SERVER_CRASH_WORDS) {
        Line::Crashed
    } else {
        Line::Other
    }
}

/// `ModpackTestState` plus the console the agent reads.
#[derive(Clone, Debug, Default)]
pub struct State {
    pub status: Status,
    pub pass_seconds: Option<String>,
    /// `testedModsSignature`: the mods and sides of the passed run.
    pub signature: Option<String>,
    pub error: Option<String>,
    /// The launcher task of the latest run.
    pub task: Option<String>,
    lines: VecDeque<String>,
}

impl State {
    pub fn push_line(&mut self, line: &str) {
        let line = if line.chars().count() > LINE_LIMIT { format!("{}…", line.chars().take(LINE_LIMIT).collect::<String>()) } else { line.to_owned() };
        if self.lines.len() == KEPT_LINES {
            self.lines.pop_front();
        }
        self.lines.push_back(line);
    }

    pub fn lines(&self) -> Vec<String> {
        self.lines.iter().cloned().collect()
    }

    /// `onModsChanged`: a passed result no longer counts.
    pub fn mods_changed(&mut self) {
        if self.status == Status::Passed {
            self.status = Status::NotRun;
            self.pass_seconds = None;
        }
        self.signature = None;
    }

    /// Passed with these mods and sides (Kotlin `testedModsSignature`, compared at submit).
    pub fn passed_for(&self, mods: &[Mod], fabric: bool) -> bool {
        self.status == Status::Passed && self.signature.as_deref() == Some(signature(&run_mods(mods, fabric)).as_str())
    }

    pub fn view(&self) -> Value {
        json!({
            "status": self.status.name(),
            "pass_seconds": self.pass_seconds,
            "error": self.error,
            "task_id": self.task,
            "last_lines": self.lines(),
        })
    }
}

/// The mods of a run (`start`): processed when a client override is in the pack.
pub fn run_mods(mods: &[Mod], fabric: bool) -> Vec<Mod> {
    if mods.iter().any(|m| m.client_only_override || m.client_override_replaced) { process_mods(mods, fabric) } else { mods.to_vec() }
}

/// `currentModsSignature`.
pub fn signature(mods: &[Mod]) -> String {
    let side = |s: Side| match s {
        Side::Client => "CLIENT",
        Side::Server => "SERVER",
        Side::Both => "BOTH",
        Side::Unknown => "UNKNOWN",
    };
    let mut keys: Vec<(String, Side)> = mods.iter().map(|m| (super::prepare::stable_key(m), m.side)).collect();
    keys.sort_by(|a, b| a.0.cmp(&b.0));
    keys.iter().map(|(k, s)| format!("{k}:{}", side(*s))).collect::<Vec<_>>().join("|")
}

fn is_client_only_marked_name(name: &str) -> bool {
    name.starts_with(CLIENT_ONLY_MARK_PREFIX)
}

fn has_extension(path: &Path, extension: &str) -> bool {
    path.extension().is_some_and(|e| e.to_string_lossy().eq_ignore_ascii_case(extension))
}

fn file_name(path: &Path) -> String {
    path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

/// `isClientOnlyMarkedModFile`.
fn is_client_only_marked_file(path: &Path) -> bool {
    path.is_file() && is_client_only_marked_name(&file_name(path)) && has_extension(path, "jar")
}

/// `shouldHardLinkTestContent`: media and resource or shader packs are never rewritten by the
/// game, so tests share them with the pack; everything else is copied, since mods rewrite configs
/// in place (shader settings are `.txt` files under `shaderpacks`).
pub fn should_hard_link(relative: &str) -> bool {
    let normalized = relative.replace('\\', "/").trim_start_matches('/').to_lowercase();
    let name = normalized.rsplit('/').next().unwrap_or("");
    let extension = name.rsplit_once('.').map(|(_, e)| e).unwrap_or("");
    if super::server_pack::MEDIA_EXTENSIONS.contains(&extension) || normalized.starts_with("resourcepacks/") {
        return true;
    }
    normalized.starts_with("shaderpacks/") && extension != "txt"
}

/// Entries of a folder, empty when it cannot be listed (`listFiles()` returning null).
fn children(dir: &Path) -> Vec<PathBuf> {
    match std::fs::read_dir(dir) {
        Ok(entries) => entries.filter_map(|e| e.ok()).map(|e| e.path()).collect(),
        Err(_) => Vec::new(),
    }
}

fn remove_any(path: &Path) -> Result<()> {
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.is_dir() => std::fs::remove_dir_all(path)?,
        Ok(_) => std::fs::remove_file(path)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

/// `hardLinkFile`: replaces the target with a hard link to the source.
fn hard_link(source: &Path, target: &Path) -> Result<()> {
    ensure!(source.is_file(), "源文件不存在: {}", source.display());
    remove_any(target)?;
    std::fs::hard_link(source, target).map_err(|e| anyhow::anyhow!("无法创建文件硬链接: {e}"))
}

/// `hardLinkDirectory`: the folder tree with every file hard linked.
fn hard_link_dir(source: &Path, target: &Path) -> Result<()> {
    ensure!(source.is_dir(), "源目录不存在: {}", source.display());
    std::fs::create_dir_all(target)?;
    for entry in std::fs::read_dir(source)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let to = target.join(entry.file_name());
        if kind.is_dir() {
            hard_link_dir(&entry.path(), &to)?;
        } else if kind.is_file() {
            hard_link(&entry.path(), &to)?;
        } else {
            bail!("不支持链接此文件: {}", entry.path().display());
        }
    }
    Ok(())
}

fn copy_replacing(source: &Path, target: &Path) -> Result<()> {
    if std::fs::symlink_metadata(target).is_ok_and(|m| !m.is_dir()) {
        std::fs::remove_file(target)?;
    }
    std::fs::copy(source, target).with_context(|| format!("复制文件失败: {}", source.display()))?;
    Ok(())
}

/// `copyFileOrDirectory`.
fn copy_entry(source: &Path, target: &Path, root: &Path, skip_mods_dirs: bool) -> Result<()> {
    if source.is_dir() {
        std::fs::create_dir_all(target)?;
        return copy_dir_content(source, target, root, skip_mods_dirs);
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let relative = target.strip_prefix(root).unwrap_or(target).to_string_lossy().replace('\\', "/");
    if should_hard_link(&relative) {
        if let Err(error) = hard_link(source, target) {
            tracing::debug!(error = %format!("{error:#}"), target = %target.display(), "硬链接测试文件失败，改为复制");
            copy_replacing(source, target)?;
        }
        return Ok(());
    }
    copy_replacing(source, target)
}

/// `copyDirectoryContent`.
fn copy_dir_content(source: &Path, target: &Path, root: &Path, skip_mods_dirs: bool) -> Result<()> {
    for child in children(source) {
        if skip_mods_dirs && child.is_dir() && file_name(&child).eq_ignore_ascii_case("mods") {
            continue;
        }
        if is_client_only_marked_file(&child) {
            continue;
        }
        copy_entry(&child, &target.join(child.file_name().unwrap_or_default()), root, skip_mods_dirs)?;
    }
    Ok(())
}

/// `copyTestPackBaseContent`.
fn copy_base_content(source: &Path, target: &Path, skip_root_child: impl Fn(&Path) -> bool, skip_mods_dirs: bool, client_overrides: bool) -> Result<()> {
    const SKIPPED: [&str; 6] = ["mods", "overrides", "client-overrides", "server", "manifest.json", "modrinth.index.json"];
    for child in children(source) {
        let name = file_name(&child);
        if skip_root_child(&child) || SKIPPED.iter().any(|s| s.eq_ignore_ascii_case(&name)) {
            continue;
        }
        copy_entry(&child, &target.join(&name), target, skip_mods_dirs)?;
    }
    let overrides = source.join("overrides");
    if overrides.is_dir() {
        copy_dir_content(&overrides, target, target, skip_mods_dirs)?;
    }
    let client = source.join("client-overrides");
    if client_overrides && client.is_dir() {
        copy_dir_content(&client, target, target, skip_mods_dirs)?;
    }
    Ok(())
}

fn clean_runtime_output(dir: &Path, names: &[&str]) -> Result<()> {
    for name in names {
        remove_any(&dir.join(name))?;
    }
    Ok(())
}

fn recreate(dir: &Path) -> Result<()> {
    remove_any(dir)?;
    std::fs::create_dir_all(dir)?;
    Ok(())
}

fn same_content(a: &Path, b: &Path) -> std::io::Result<bool> {
    use std::io::Read;
    if std::fs::metadata(a)?.len() != std::fs::metadata(b)?.len() {
        return Ok(false);
    }
    let (mut x, mut y) = (std::fs::File::open(a)?, std::fs::File::open(b)?);
    let (mut bx, mut by) = (vec![0u8; 1 << 16], vec![0u8; 1 << 16]);
    loop {
        let n = x.read(&mut bx)?;
        if n == 0 {
            return Ok(true);
        }
        y.read_exact(&mut by[..n])?;
        if bx[..n] != by[..n] {
            return Ok(false);
        }
    }
}

/// `stageModFile`.
fn stage_mod_file(source: &Path, target: &Path, replace: bool) -> Result<()> {
    ensure!(source.is_file(), "源Mod文件不存在: {}", source.display());
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let exists = std::fs::symlink_metadata(target).is_ok();
    if replace && exists {
        return copy_replacing(source, target);
    }
    if !exists {
        return hard_link(source, target);
    }
    let same = same_content(source, target)
        .with_context(|| format!("检查Mod文件冲突失败: 源文件{}，目标文件{}", source.display(), target.display()))?;
    ensure!(same, "Mod文件目标冲突: 目标文件{}已存在，源文件{}，目标文件{}", file_name(target), source.display(), target.display());
    Ok(())
}

/// `findMatchedSourceMod`: by file name, then by SHA-1.
fn matched_mod<'a>(source: &Path, mods: &'a [Mod]) -> Result<Option<&'a Mod>> {
    let name = file_name(source);
    if let Some(m) = mods.iter().find(|m| m.file_names().iter().any(|n| n.eq_ignore_ascii_case(&name))) {
        return Ok(Some(m));
    }
    let hash = crate::mc_install::sha1_file(source).with_context(|| format!("读取源Mod SHA-1失败: {}", source.display()))?;
    Ok(mods.iter().find(|m| m.hash.eq_ignore_ascii_case(&hash)))
}

fn jars(dir: &Path) -> Vec<PathBuf> {
    if !dir.is_dir() {
        return Vec::new();
    }
    children(dir).into_iter().filter(|f| f.is_file() && has_extension(f, "jar") && !is_client_only_marked_file(f)).collect()
}

/// `stageSourceModFiles`: jars left in the pack's `mods` and `overrides/mods`.
fn stage_source_mod_files(mods_dir: &Path, source: &Path, mods: &[Mod], include: impl Fn(&Mod) -> bool) -> Result<()> {
    for dir in [source.join("mods"), source.join("overrides").join("mods")] {
        for jar in jars(&dir) {
            let matched = matched_mod(&jar, mods)?;
            if matched.is_some_and(|m| !include(m)) {
                continue;
            }
            let name = matched.map(Mod::file_name).unwrap_or_else(|| file_name(&jar));
            stage_mod_file(&jar, &mods_dir.join(name), false)?;
        }
    }
    Ok(())
}

/// `stageClientOverrideModFiles`: returns the names it placed.
fn stage_client_override_mod_files(mods_dir: &Path, source: &Path, mods: &[Mod], include: impl Fn(&Mod) -> bool) -> Result<BTreeSet<String>> {
    let mut names = BTreeSet::new();
    for jar in jars(&source.join("client-overrides").join("mods")) {
        let matched = matched_mod(&jar, mods)?;
        if matched.is_some_and(|m| !include(m)) {
            continue;
        }
        let name = matched.map(Mod::file_name).unwrap_or_else(|| file_name(&jar));
        stage_mod_file(&jar, &mods_dir.join(&name), true)?;
        names.insert(name);
    }
    Ok(names)
}

/// `stageSourceModDirectories` and `stageClientOverrideModDirectories`.
fn stage_mod_directories(mods_dir: &Path, dirs: &[PathBuf]) -> Result<()> {
    let root = mods_dir.parent().unwrap_or(mods_dir);
    for dir in dirs {
        if !dir.is_dir() {
            continue;
        }
        for child in children(dir).into_iter().filter(|c| c.is_dir()) {
            copy_entry(&child, &mods_dir.join(child.file_name().unwrap_or_default()), root, false)?;
        }
    }
    Ok(())
}

/// `stageDownloadedMods`: the run's mods from `MOD_SOURCES`.
fn stage_downloaded_mods(mods_dir: &Path, sources: &Path, mods: &[Mod], keep: &BTreeSet<String>, include: impl Fn(&Mod) -> bool) -> Result<()> {
    for m in mods.iter().filter(|m| include(m)) {
        let Some(source) = m.file_names().iter().map(|n| sources.join(n)).find(|p| p.is_file()) else { continue };
        let name = m.file_name();
        if keep.contains(&name) {
            continue;
        }
        stage_mod_file(&source, &mods_dir.join(name), false)?;
    }
    Ok(())
}

fn not_server(m: &Mod) -> bool {
    m.side != Side::Server && m.side != Side::Unknown
}

fn not_client(m: &Mod) -> bool {
    m.side != Side::Client && m.side != Side::Unknown
}

/// `writeMinecraftOptions`; `mc_version` as the draft names it (`V201`).
pub fn write_options(dir: &Path, mc_version: &str) -> Result<()> {
    let file = dir.join("options.txt");
    let original = match std::fs::read_to_string(&file) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    std::fs::write(&file, crate::pack_install::merge_options(&original, &crate::pack_install::option_overrides(mc_version)))?;
    Ok(())
}

fn unique_suffix() -> String {
    uuid::Uuid::now_v7().simple().to_string()
}

/// `createServerTestBaseDir`: a new folder under `work`, the shared libraries hard linked in, the
/// pack's files without mods, client-only marked jars and client overrides.
pub fn create_server_dir(source: &Path, work: &Path, libraries: &Path) -> Result<PathBuf> {
    std::fs::create_dir_all(work)?;
    let dir = work.join(format!("{SERVER_DIR_PREFIX}{}", unique_suffix()));
    std::fs::create_dir(&dir)?;
    if libraries.exists() {
        hard_link_dir(libraries, &dir.join("libraries")).map_err(|e| anyhow::anyhow!("创建测试目录libraries硬链接失败: {e:#}"))?;
    }
    copy_base_content(source, &dir, is_client_only_marked_file, true, false)?;
    Ok(dir)
}

/// `prepareServerTestRunContent`: a fresh `mods` with the run's server and both-side mods.
pub fn prepare_server_content(dir: &Path, source: &Path, mods: &[Mod]) -> Result<()> {
    clean_runtime_output(dir, &["logs", "crash-reports", "world"])?;
    let mods_dir = dir.join("mods");
    recreate(&mods_dir)?;
    stage_source_mod_files(&mods_dir, source, mods, not_client)?;
    stage_downloaded_mods(&mods_dir, &dir.join(MOD_SOURCES), mods, &BTreeSet::new(), |m| not_client(m) && !is_client_only_marked_name(&m.file_name()))?;
    for file in children(&mods_dir).into_iter().filter(|f| is_client_only_marked_file(f)) {
        std::fs::remove_file(file)?;
    }
    Ok(())
}

/// `createClientTestBaseDir`: `_rdi_client_test_<millis>_<n>` under `work`, with the pack's
/// resource and shader packs, its files and the client overrides, without mods.
pub fn create_client_dir(source: &Path, work: &Path, mc_version: &str) -> Result<PathBuf> {
    let millis = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or_default();
    let n = 1000 + (uuid::Uuid::now_v7().as_u128() % 9000) as u32;
    std::fs::create_dir_all(work)?;
    let dir = work.join(format!("{CLIENT_VERSION_PREFIX}{millis}_{n}"));
    remove_any(&dir)?;
    std::fs::create_dir_all(&dir)?;
    for name in ["resourcepacks", "shaderpacks"] {
        let from = source.join(name);
        if from.exists() {
            copy_entry(&from, &dir.join(name), &dir, false)?;
        }
    }
    copy_base_content(source, &dir, |_| false, true, true)?;
    write_options(&dir, mc_version)?;
    Ok(dir)
}

/// `prepareClientTestRunContent`: a fresh `mods` with the run's client and both-side mods, the
/// client overrides winning over the same names.
pub fn prepare_client_content(dir: &Path, source: &Path, mods: &[Mod], mc_version: &str) -> Result<()> {
    clean_runtime_output(dir, &["logs", "crash-reports", "saves"])?;
    write_options(dir, mc_version)?;
    let mods_dir = dir.join("mods");
    recreate(&mods_dir)?;
    stage_mod_directories(&mods_dir, &[source.join("mods"), source.join("overrides").join("mods")])?;
    stage_source_mod_files(&mods_dir, source, mods, not_server)?;
    stage_mod_directories(&mods_dir, &[source.join("client-overrides").join("mods")])?;
    let kept = stage_client_override_mod_files(&mods_dir, source, mods, not_server)?;
    stage_downloaded_mods(&mods_dir, &dir.join(MOD_SOURCES), mods, &kept, not_server)
}
