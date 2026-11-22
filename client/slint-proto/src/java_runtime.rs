//! J5 decision D5: the Java 25 that starts the game. At launcher start a background search uses
//! the updater's rules (`client/updater/src/java_discovery.rs` for candidates; `main.rs`
//! `find_best_jdk25`, `test_java_candidate`, `path_score` for validation and order, ported here
//! because they are private to the updater binary). Without a usable JDK 25 the player is told,
//! and on Windows offered the updater's automatic download (`JDK25_DOWNLOAD_URL`, MSI, elevated).
//! The per-pack Java setting still overrides the result.
use anyhow::{Context, Result, anyhow, bail};
use std::collections::{BTreeMap, HashSet};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use crate::api::java_discovery;

/// The updater's download address (`client/updater/src/main.rs`).
pub const JDK25_DOWNLOAD_URL: &str = "https://mirrors.huaweicloud.com/eclipse/temurin-compliance/temurin/25/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_windows_hotspot_25.0.3_9.msi";
pub const MISSING_MESSAGE: &str = "没找到可用的完整版64位JDK25";

#[derive(Clone, Debug, PartialEq)]
pub struct Jdk {
    pub java_home: PathBuf,
    pub version_text: String,
    path_score: i32,
}

impl Jdk {
    pub fn java(&self) -> PathBuf {
        self.java_home.join("bin").join(if cfg!(windows) { "java.exe" } else { "java" })
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum State {
    Searching,
    Found(Jdk),
    Missing,
}

static STATE: LazyLock<(Mutex<State>, tokio::sync::Notify)> = LazyLock::new(|| (Mutex::new(State::Missing), tokio::sync::Notify::new()));
static STARTED: std::sync::Once = std::sync::Once::new();

fn set(state: State) {
    *STATE.0.lock().unwrap() = state;
    STATE.1.notify_waiters();
}

pub fn state() -> State {
    STATE.0.lock().unwrap().clone()
}

/// Starts the search once per launcher run.
pub fn start(runtime: &tokio::runtime::Handle) {
    STARTED.call_once(|| {
        set(State::Searching);
        runtime.spawn_blocking(search);
    });
}

fn search() {
    let found = find_best(&launcher_root());
    match &found {
        Some(jdk) => tracing::info!(home = %jdk.java_home.display(), version = %jdk.version_text, "找到Java25"),
        None => tracing::warn!("{MISSING_MESSAGE}"),
    }
    set(found.map(State::Found).unwrap_or(State::Missing));
}

/// Waits for a running search; `None` when no JDK 25 was found.
pub async fn resolved() -> Option<Jdk> {
    loop {
        let notified = STATE.1.notified();
        match state() {
            State::Found(jdk) => return Some(jdk),
            State::Missing => return None,
            State::Searching => notified.await,
        }
    }
}

/// The directory holding the launcher executable; the updater keeps `available_jdks.txt` there.
fn launcher_root() -> PathBuf {
    std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf)).unwrap_or_default()
}

fn exe(name: &str) -> String {
    if cfg!(windows) { format!("{name}.exe") } else { name.to_owned() }
}

/// `find_best_jdk25`: the cached list first, then the full search, which refreshes the cache.
fn find_best(root: &Path) -> Option<Jdk> {
    let cache = root.join("available_jdks.txt");
    if let Ok(text) = std::fs::read_to_string(&cache) {
        let candidates = candidates_from_homes(text.trim_start_matches('\u{feff}').lines().map(str::trim).filter(|l| !l.is_empty()));
        if let Some(best) = select_best(validate_all(&candidates)) {
            return Some(best);
        }
    }
    let mut candidates: BTreeMap<String, PathBuf> = BTreeMap::new();
    for path in java_discovery::candidate_paths().into_iter().chain(unix_candidates()) {
        candidates.entry(path.to_string_lossy().to_ascii_lowercase()).or_insert(path);
    }
    let valid = validate_all(&candidates.into_values().collect::<Vec<_>>());
    if let Err(error) = write_cache(&cache, &valid) {
        tracing::warn!(error = %format!("{error:#}"), "保存Java缓存失败");
    }
    select_best(valid)
}

fn candidates_from_homes<'a>(homes: impl Iterator<Item = &'a str>) -> Vec<PathBuf> {
    let mut out = vec![];
    for home in homes {
        let home = Path::new(home);
        for javac in [home.join(exe("javac")), home.join("bin").join(exe("javac"))] {
            if javac.is_file() {
                out.push(javac.with_file_name(exe("java")));
            }
        }
    }
    out
}

/// macOS and Linux have no updater; these are the usual JDK locations (not verified on a real
/// machine in J5a).
fn unix_candidates() -> Vec<PathBuf> {
    if cfg!(windows) {
        return vec![];
    }
    let mut homes: Vec<PathBuf> = ["JAVA_HOME", "JDK_HOME"].iter().filter_map(std::env::var_os).map(PathBuf::from).collect();
    if let Some(path) = std::env::var_os("PATH") {
        homes.extend(std::env::split_paths(&path).filter_map(|d| d.parent().map(Path::to_path_buf)));
    }
    for (root, suffix) in [("/usr/lib/jvm", ""), ("/Library/Java/JavaVirtualMachines", "Contents/Home")] {
        if let Ok(entries) = std::fs::read_dir(root) {
            homes.extend(entries.flatten().map(|e| e.path().join(suffix)));
        }
    }
    if let Some(home) = std::env::var_os("HOME") {
        for relative in [".jdks", ".sdkman/candidates/java"] {
            if let Ok(entries) = std::fs::read_dir(Path::new(&home).join(relative)) {
                homes.extend(entries.flatten().map(|e| e.path()));
            }
        }
    }
    homes.into_iter().map(|h| h.join("bin").join("java")).filter(|j| j.is_file()).collect()
}

fn write_cache(cache: &Path, valid: &[Jdk]) -> Result<()> {
    let mut homes: Vec<String> = valid.iter().map(|j| j.java_home.to_string_lossy().into_owned()).collect();
    homes.sort_by_key(|h| h.to_ascii_lowercase());
    homes.dedup_by_key(|h| h.to_ascii_lowercase());
    std::fs::write(cache, homes.join("\n") + "\n")?;
    Ok(())
}

fn validate_all(candidates: &[PathBuf]) -> Vec<Jdk> {
    let next = std::sync::atomic::AtomicUsize::new(0);
    let found = Mutex::new(vec![]);
    std::thread::scope(|s| {
        for _ in 0..8.min(candidates.len()) {
            s.spawn(|| loop {
                let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let Some(java) = candidates.get(i) else { break };
                if let Some(jdk) = test_candidate(java) {
                    found.lock().unwrap().push(jdk);
                }
            });
        }
    });
    found.into_inner().unwrap()
}

fn release_field(text: &str, field: &str) -> Option<String> {
    text.lines().find_map(|l| Some(l.strip_prefix(field)?.trim().strip_prefix('"')?.strip_suffix('"')?.to_owned()))
}

/// `fs::canonicalize` gives `\\?\C:\...` on Windows; the game and its mods get the plain form.
fn plain_path(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        return PathBuf::from(format!(r"\\{rest}"));
    }
    match text.strip_prefix(r"\\?\") {
        Some(rest) if rest.as_bytes().get(1) == Some(&b':') => PathBuf::from(rest),
        _ => path.to_path_buf(),
    }
}

/// `parse_java_major_version`.
pub fn major(version: &str) -> Option<i32> {
    let v = version.strip_prefix("1.").unwrap_or(version);
    v.split(|c: char| !c.is_ascii_digit()).next()?.parse().ok()
}

fn is_64_bit(arch: &str) -> bool {
    matches!(arch.to_ascii_lowercase().as_str(), "amd64" | "x86_64" | "aarch64" | "arm64")
}

/// `test_java_candidate`: a full 64-bit JDK 25 whose `java` and `javac` both answer.
fn test_candidate(java: &Path) -> Option<Jdk> {
    if !java.is_file() {
        return None;
    }
    let home = plain_path(java.parent()?.parent()?);
    let javac = home.join("bin").join(exe("javac"));
    if !javac.is_file() || (cfg!(windows) && !home.join("bin").join("javaw.exe").is_file()) {
        return None;
    }
    let release = std::fs::read_to_string(home.join("release")).ok()?;
    let version = release_field(&release, "JAVA_VERSION=")?;
    let arch = release_field(&release, "OS_ARCH=")?;
    if major(&version) != Some(25) || !is_64_bit(&arch) {
        return None;
    }
    let probe = (|| -> Result<String> {
        let java_output = run_probe(java, &["-XshowSettings:properties", "-version"])?;
        let compiler_output = run_probe(&javac, &["-version"])?;
        validate_probe(&java_output, &compiler_output)
    })();
    match probe {
        Ok(version_text) => Some(Jdk { path_score: path_score(java, &launcher_root()), java_home: home, version_text }),
        Err(error) => {
            tracing::info!(java = %java.display(), error = %format!("{error:#}"), "跳过不可用的JDK");
            None
        }
    }
}

fn run_probe(executable: &Path, arguments: &[&str]) -> Result<String> {
    let mut command = Command::new(executable);
    command.args(arguments).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    let mut child = command.spawn().with_context(|| format!("无法运行{}", executable.display()))?;
    // Read both pipes while waiting: -XshowSettings output can exceed a pipe buffer.
    let readers: Vec<_> = [child.stdout.take().map(|s| Box::new(s) as Box<dyn std::io::Read + Send>), child.stderr.take().map(|s| Box::new(s) as Box<dyn std::io::Read + Send>)]
        .into_iter()
        .flatten()
        .map(|mut pipe| {
            std::thread::spawn(move || {
                let mut bytes = vec![];
                let _ = std::io::Read::read_to_end(&mut std::io::Read::take(pipe.as_mut(), 64 * 1024 + 1), &mut bytes);
                bytes
            })
        })
        .collect();
    let deadline = Instant::now() + Duration::from_secs(10);
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            bail!("Java探测超过10秒：{}", executable.display());
        }
        std::thread::sleep(Duration::from_millis(25));
    };
    if !status.success() {
        bail!("Java探测失败，退出码：{:?}，路径：{}", status.code(), executable.display());
    }
    let mut output = String::new();
    for reader in readers {
        let bytes = reader.join().map_err(|_| anyhow!("读取Java探测输出失败"))?;
        if bytes.len() > 64 * 1024 {
            bail!("Java探测输出超过64KiB");
        }
        output.push_str(&String::from_utf8_lossy(&bytes));
        output.push('\n');
    }
    Ok(output)
}

/// `validate_jdk25_probe`.
pub fn validate_probe(java_output: &str, compiler_output: &str) -> Result<String> {
    let property = |name: &str| {
        java_output.lines().find_map(|line| {
            let (key, value) = line.trim().split_once('=')?;
            (key.trim() == name).then(|| value.trim())
        })
    };
    let version = property("java.version").ok_or_else(|| anyhow!("Java未返回版本"))?;
    let arch = property("os.arch").ok_or_else(|| anyhow!("Java未返回架构"))?;
    if major(version) != Some(25) || !is_64_bit(arch) {
        bail!("需要64位JDK25，实际Java版本：{version}，架构：{arch}");
    }
    let compiler = compiler_output
        .lines()
        .find_map(|line| {
            let mut words = line.split_whitespace();
            (words.next()? == "javac").then(|| words.next()).flatten()
        })
        .ok_or_else(|| anyhow!("javac未返回版本"))?;
    if major(compiler) != Some(25) {
        bail!("需要JDK25编译器，实际javac版本：{compiler}");
    }
    Ok(format!("Java={version}，javac={compiler}，架构={arch}"))
}

/// `path_score`; the updater compares Windows paths, so separators are normalized first.
fn path_score(java: &Path, root: &Path) -> i32 {
    let path = java.to_string_lossy().to_ascii_lowercase().replace('/', "\\");
    let root = root.to_string_lossy().to_ascii_lowercase().replace('/', "\\");
    let mut score = 0;
    if !root.is_empty() && path.starts_with(&root) {
        score += 5000;
    }
    for (needle, points) in [("\\.jdks\\", 1000), ("\\program files\\", 600), ("\\users\\", 300)] {
        if path.contains(needle) {
            score += points;
        }
    }
    if path.contains("jdk-25") || path.contains("jdk25") {
        score += 400;
    }
    for (needle, points) in [("temurin", 120), ("microsoft", 100), ("oracle", 80)] {
        if path.contains(needle) {
            score += points;
        }
    }
    score
}

/// `select_best_jdk25_candidate`: highest score, then the shortest home, then by path.
fn select_best(mut candidates: Vec<Jdk>) -> Option<Jdk> {
    let mut seen = HashSet::new();
    candidates.retain(|c| seen.insert(c.java_home.to_string_lossy().to_ascii_lowercase()));
    candidates.sort_by(|a, b| {
        b.path_score
            .cmp(&a.path_score)
            .then_with(|| a.java_home.to_string_lossy().len().cmp(&b.java_home.to_string_lossy().len()))
            .then_with(|| a.java_home.cmp(&b.java_home))
    });
    candidates.into_iter().next()
}

/// Automatic install, as the updater's `install_jdk25`: download the MSI, run it elevated and
/// quietly, then look in the default Temurin folder. Windows only; the address is a Windows MSI.
pub async fn install(downloader: &crate::download::Downloader, cancel: &crate::tasks::Cancellation, progress: &(dyn Fn(String) + Sync)) -> Result<Jdk> {
    if !cfg!(windows) {
        bail!("请先安装64位JDK25，再重新启动");
    }
    let data = crate::paths::data_dir();
    std::fs::create_dir_all(&data)?;
    let installer = data.join("java25install.msi");
    let log = data.join("java25install.log");
    progress("正在下载Java25安装程序".into());
    let last = std::sync::atomic::AtomicU64::new(u64::MAX);
    let report = |p: crate::download::Progress| {
        let bucket = p.bytes / (8 * 1024 * 1024);
        if last.swap(bucket, std::sync::atomic::Ordering::Relaxed) != bucket {
            progress(format!("下载Java25 {}", crate::packs::human_file_size(p.bytes)));
        }
    };
    let source = crate::download::Source { url: JDK25_DOWNLOAD_URL.into(), headers: Default::default() };
    downloader.download(&[source], &installer, None, cancel, &|_| Ok(()), &report).await.context("Java25下载失败")?;
    progress("下载完成，请允许管理员授权，随后将自动安装Java25".into());
    let code = tokio::task::spawn_blocking(move || run_msi(&installer, &log)).await??;
    if code == 3010 {
        progress("Java25安装完成，安装程序提示需要重启；正在检查是否可以直接使用".into());
    }
    let found = tokio::task::spawn_blocking(find_installed_temurin25).await?;
    let jdk = found.with_context(|| {
        format!("安装结束但未在默认目录找到可用Java25。退出码：{code}{}", if code == 3010 { "。请重启电脑后再试" } else { "" })
    })?;
    if let Err(error) = write_cache(&launcher_root().join("available_jdks.txt"), std::slice::from_ref(&jdk)) {
        tracing::warn!(error = %format!("{error:#}"), "Java25已可用，但保存Java缓存失败");
    }
    set(State::Found(jdk.clone()));
    progress("Java25安装完成".into());
    Ok(jdk)
}

fn find_installed_temurin25() -> Option<Jdk> {
    let mut candidates = vec![];
    for variable in ["ProgramW6432", "ProgramFiles"] {
        let Some(base) = std::env::var_os(variable) else { continue };
        if let Ok(entries) = std::fs::read_dir(Path::new(&base).join("Eclipse Adoptium")) {
            candidates.extend(candidates_from_homes_owned(entries.flatten().map(|e| e.path())));
        }
    }
    select_best(validate_all(&candidates))
}

fn candidates_from_homes_owned(homes: impl Iterator<Item = PathBuf>) -> Vec<PathBuf> {
    let homes: Vec<String> = homes.map(|h| h.to_string_lossy().into_owned()).collect();
    candidates_from_homes(homes.iter().map(String::as_str))
}

#[cfg(windows)]
fn run_msi(installer: &Path, log: &Path) -> Result<u32> {
    use std::os::windows::ffi::OsStrExt;
    #[repr(C)]
    struct ShellExecuteInfoW {
        cb_size: u32,
        f_mask: u32,
        hwnd: *mut std::ffi::c_void,
        lp_verb: *const u16,
        lp_file: *const u16,
        lp_parameters: *const u16,
        lp_directory: *const u16,
        n_show: i32,
        h_inst_app: *mut std::ffi::c_void,
        lp_id_list: *mut std::ffi::c_void,
        lp_class: *const u16,
        hkey_class: *mut std::ffi::c_void,
        dw_hot_key: u32,
        h_icon_or_monitor: *mut std::ffi::c_void,
        h_process: *mut std::ffi::c_void,
    }
    #[link(name = "shell32")]
    unsafe extern "system" {
        fn ShellExecuteExW(info: *mut ShellExecuteInfoW) -> i32;
    }
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn WaitForSingleObject(handle: *mut std::ffi::c_void, milliseconds: u32) -> u32;
        fn GetExitCodeProcess(handle: *mut std::ffi::c_void, code: *mut u32) -> i32;
        fn CloseHandle(handle: *mut std::ffi::c_void) -> i32;
        fn GetLastError() -> u32;
    }
    fn wide(s: &std::ffi::OsStr) -> Vec<u16> {
        s.encode_wide().chain(Some(0)).collect()
    }
    let msiexec = PathBuf::from(std::env::var_os("SystemRoot").context("无法确定Windows系统目录")?).join("System32").join("msiexec.exe");
    let arguments = [
        "/i".to_owned(),
        installer.to_string_lossy().into_owned(),
        "/quiet".into(),
        "/norestart".into(),
        "ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome".into(),
        "/L*v".into(),
        log.to_string_lossy().into_owned(),
    ]
    .iter()
    .map(|a| quote(a))
    .collect::<Vec<_>>()
    .join(" ");
    let (verb, file, params) = (wide("runas".as_ref()), wide(msiexec.as_os_str()), wide(arguments.as_ref()));
    let mut info = ShellExecuteInfoW {
        cb_size: std::mem::size_of::<ShellExecuteInfoW>() as u32,
        f_mask: 0x40,
        hwnd: std::ptr::null_mut(),
        lp_verb: verb.as_ptr(),
        lp_file: file.as_ptr(),
        lp_parameters: params.as_ptr(),
        lp_directory: std::ptr::null(),
        n_show: 0,
        h_inst_app: std::ptr::null_mut(),
        lp_id_list: std::ptr::null_mut(),
        lp_class: std::ptr::null(),
        hkey_class: std::ptr::null_mut(),
        dw_hot_key: 0,
        h_icon_or_monitor: std::ptr::null_mut(),
        h_process: std::ptr::null_mut(),
    };
    // SAFETY: every pointer in `info` lives until the call returns.
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        let error = unsafe { GetLastError() };
        if error == 1223 {
            bail!("已取消Java25安装授权");
        }
        bail!("无法启动Java25安装程序，Windows错误码{error}");
    }
    ensure_handle(info.h_process)?;
    let mut code = 0u32;
    // SAFETY: the handle came from ShellExecuteExW with SEE_MASK_NOCLOSEPROCESS and is closed once.
    unsafe {
        WaitForSingleObject(info.h_process, 0xFFFF_FFFF);
        let ok = GetExitCodeProcess(info.h_process, &mut code);
        CloseHandle(info.h_process);
        if ok == 0 {
            bail!("读取Java25安装程序退出码失败");
        }
    }
    if !matches!(code, 0 | 3010) {
        bail!("安装程序退出码：{code}。日志：{}", log.display());
    }
    Ok(code)
}

#[cfg(windows)]
fn ensure_handle(handle: *mut std::ffi::c_void) -> Result<()> {
    if handle.is_null() {
        bail!("无法启动Java25安装程序");
    }
    Ok(())
}

#[cfg(not(windows))]
fn run_msi(_installer: &Path, _log: &Path) -> Result<u32> {
    bail!("请先安装64位JDK25，再重新启动")
}

/// The updater's `quote_windows_argument`.
#[cfg_attr(not(windows), allow(dead_code))]
fn quote(argument: &str) -> String {
    if argument.is_empty() {
        return "\"\"".into();
    }
    if !argument.chars().any(|c| c.is_whitespace() || c == '"') {
        return argument.into();
    }
    let mut result = String::from("\"");
    let mut backslashes = 0;
    for c in argument.chars() {
        match c {
            '\\' => backslashes += 1,
            '"' => {
                result.push_str(&"\\".repeat(backslashes * 2 + 1));
                result.push('"');
                backslashes = 0;
            }
            _ => {
                result.push_str(&"\\".repeat(backslashes));
                result.push(c);
                backslashes = 0;
            }
        }
    }
    result.push_str(&"\\".repeat(backslashes * 2));
    result.push('"');
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probes_follow_the_updater() {
        let java = "    java.version = 25.0.1\n    os.arch = amd64\n";
        assert_eq!(validate_probe(java, "javac 25.0.1").unwrap(), "Java=25.0.1，javac=25.0.1，架构=amd64");
        assert!(validate_probe("java.version = 21.0.2\nos.arch = amd64", "javac 21").is_err());
        assert!(validate_probe(java, "javac 17.0.1").is_err());
        assert!(validate_probe("java.version = 25\nos.arch = x86", "javac 25").is_err());
        assert_eq!(major("1.8.0_392"), Some(8));
        assert_eq!(major("25-ea"), Some(25));
    }

    #[test]
    fn best_candidate_prefers_the_updater_scores() {
        let root = Path::new(r"C:\rdi");
        let a = Jdk { java_home: r"C:\Program Files\Eclipse Adoptium\jdk-25.0.1".into(), version_text: String::new(), path_score: path_score(Path::new(r"C:\Program Files\Eclipse Adoptium\jdk-25.0.1\bin\java.exe"), root) };
        let b = Jdk { java_home: r"C:\Users\me\.jdks\temurin-25".into(), version_text: String::new(), path_score: path_score(Path::new(r"C:\Users\me\.jdks\temurin-25\bin\java.exe"), root) };
        let c = Jdk { java_home: r"C:\rdi\jre".into(), version_text: String::new(), path_score: path_score(Path::new(r"C:\rdi\jre\bin\java.exe"), root) };
        assert_eq!(a.path_score, 600 + 400);
        assert_eq!(b.path_score, 1000 + 300 + 120);
        assert_eq!(select_best(vec![a.clone(), b.clone()]).unwrap(), b);
        assert_eq!(select_best(vec![a, b, c.clone()]).unwrap(), c);
        assert_eq!(quote(r"C:\a b\x.msi"), "\"C:\\a b\\x.msi\"");
        assert_eq!(plain_path(Path::new(r"\\?\C:\jdk")), PathBuf::from(r"C:\jdk"));
        assert_eq!(plain_path(Path::new(r"\\?\UNC\srv\jdk")), PathBuf::from(r"\\srv\jdk"));
        assert_eq!(plain_path(Path::new(r"C:\jdk")), PathBuf::from(r"C:\jdk"));
    }
}
