#[cfg(windows)]
mod callbacks;
#[cfg(windows)]
mod download;
#[cfg(windows)]
mod http;
#[cfg(windows)]
mod java_discovery;
#[cfg(windows)]
mod library_switch_recovery;
#[cfg(windows)]
mod ui_library_updater;
#[cfg(windows)]
mod win32;

#[cfg(windows)]
use std::collections::{HashMap, HashSet};
#[cfg(windows)]
use std::fs;
#[cfg(windows)]
use std::io::{BufRead, BufReader, Read, Write};
#[cfg(windows)]
use std::net::Ipv4Addr;
#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
use std::path::{Path, PathBuf};
#[cfg(windows)]
use std::process::{Command, Stdio};
#[cfg(windows)]
use std::sync::Arc;
#[cfg(windows)]
use std::thread;
#[cfg(windows)]
use std::time::{Duration, Instant};

#[cfg(windows)]
use anyhow::{Context, Result, anyhow, bail};
#[cfg(windows)]
use chrono::Local;
#[cfg(windows)]
use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind};
#[cfg(windows)]
use crossterm::terminal::{disable_raw_mode, enable_raw_mode};
#[cfg(windows)]
use rayon::prelude::*;

#[cfg(windows)]
use callbacks::MessageCallback;
#[cfg(windows)]
use download::{FileDownloadProgress, ProgressCallback};
#[cfg(windows)]
use library_switch_recovery::{
    LibraryRecoveryPrompt, LibraryRecoveryPromptKind, PromptCallback, UiLibraryUpdateResult,
};

#[cfg(windows)]
const UNINSTALL_CONFIRMATION: &str = "confirm";
#[cfg(windows)]
const JVM_ARGUMENT_PREFIX: &str = "--jvmArg=";
#[cfg(windows)]
const MAX_PARALLEL_JDK_CHECKS: usize = 8;
#[cfg(windows)]
const DEBUG_R_SERVER_URL: &str = "http://127.0.0.1:65231";
#[cfg(windows)]
const OFFICIAL_R_SERVER_URL: &str = "https://rdi.calebxzhou.cn:65331";
#[cfg(windows)]
const JDK25_DOWNLOAD_URL: &str = "https://mirrors.huaweicloud.com/eclipse/temurin-compliance/temurin/25/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_windows_hotspot_25.0.3_9.msi";

#[cfg(windows)]
fn main() {
    std::process::exit(run(std::env::args().skip(1).collect()));
}

#[cfg(not(windows))]
fn main() {
    eprintln!("RDI updater仅支持Windows");
    std::process::exit(1);
}

#[cfg(windows)]
fn run(args: Vec<String>) -> i32 {
    match run_inner(args) {
        Ok(exit_code) => exit_code,
        Err(error) => fail(&format!("启动失败。\r\n错误: {error}")),
    }
}

#[cfg(windows)]
fn run_inner(args: Vec<String>) -> Result<i32> {
    let launcher_root = std::env::current_exe()?
        .parent()
        .ok_or_else(|| anyhow!("无法确定updater目录"))?
        .to_path_buf();
    if args.first().map(String::as_str) == Some(library_switch_recovery::ELEVATED_KILL_ARGUMENT) {
        return Ok(library_switch_recovery::run_elevated_kill(
            &launcher_root,
            &args[1..],
        ));
    }
    if args.first().map(String::as_str) == Some(library_switch_recovery::ELEVATED_SWITCH_ARGUMENT)
        && args.len() == 2
    {
        return Ok(library_switch_recovery::run_elevated_switch(
            &launcher_root,
            &args[1],
        ));
    }

    std::env::set_current_dir(&launcher_root)?;
    let mutex_name = updater_mutex_name(&launcher_root);
    let (_mutex, owns_mutex) = win32::mutex(&mutex_name)?;
    if !owns_mutex {
        return Ok(fail("另一个启动程序正在更新，请稍后重试。"));
    }

    let launch_options = parse_launch_options(&args)?;
    let startup_selection = if win32::is_left_shift_pressed() {
        show_startup_options(&launcher_root)?
    } else {
        StartupSelection::default()
    };
    if startup_selection.uninstall {
        return Ok(start_uninstall(&launcher_root));
    }

    let launch_options = LaunchOptions {
        debug: launch_options.debug || startup_selection.debug,
        no_update: launch_options.no_update || startup_selection.no_update,
        ..launch_options
    };
    let r_server_url = if launch_options.debug {
        DEBUG_R_SERVER_URL
    } else {
        OFFICIAL_R_SERVER_URL
    };

    if launch_options.no_update {
        write_info("已关闭自动更新");
    } else {
        let player_ipv4 = if launch_options.debug {
            None
        } else {
            get_public_ipv4()
        };
        let update_result = ui_library_updater::try_update(
            r_server_url,
            &launcher_root,
            info_callback(),
            warning_callback(),
            progress_callback(),
            !launch_options.debug,
            player_ipv4.as_deref(),
            Some(Arc::new(prompt_library_recovery) as PromptCallback),
        );
        if update_result == UiLibraryUpdateResult::LaunchAborted {
            return Ok(0);
        }
    }

    let lib_directory = launcher_root.join("lib");
    let has_jar = lib_directory.is_dir()
        && fs::read_dir(&lib_directory)
            .map(|entries| {
                entries.flatten().any(|entry| {
                    entry.path().extension().is_some_and(|extension| {
                        extension.to_string_lossy().eq_ignore_ascii_case("jar")
                    })
                })
            })
            .unwrap_or(false);
    if !has_jar {
        return Ok(fail(
            "缺少UI库文件。\r\n请确认客户端已完整解压，或检查网络后重试。",
        ));
    }

    let best_java = match startup_selection.jdk {
        Some(java) => java,
        None => match resolve_best_jdk25(&launcher_root) {
            Some(java) => java,
            None => return Ok(fail("未找到可用Java25，客户端无法启动。")),
        },
    };
    let java_name = if launch_options.app_logs {
        "java.exe"
    } else {
        "javaw.exe"
    };
    let java_exe = best_java.java_home.join("bin").join(java_name);
    if !java_exe.is_file() {
        return Ok(fail(&format!(
            "找到的Java25缺少{java_name}：\r\n{}",
            best_java.java_home.display()
        )));
    }

    Ok(start_client(
        &launcher_root,
        &java_exe,
        &launch_options,
        startup_selection.solid_window,
        r_server_url,
    ))
}

#[cfg(windows)]
fn write_info(message: &str) {
    println!("[{}] {message}", Local::now().format("%H:%M:%S"));
}

#[cfg(windows)]
fn write_warning(message: &str) {
    write_info(&format!("警告: {message}"));
}

#[cfg(windows)]
fn info_callback() -> MessageCallback {
    Arc::new(|message| write_info(&message))
}

#[cfg(windows)]
fn warning_callback() -> MessageCallback {
    Arc::new(|message| write_warning(&message))
}

#[cfg(windows)]
fn progress_callback() -> ProgressCallback {
    Arc::new(render_download_progress)
}

#[cfg(windows)]
fn render_download_progress(progress: FileDownloadProgress) {
    let progress_text = if let Some(total) = progress.total_bytes {
        format!(
            "下载进度: {:>6.2}%  {}/{}  {}/s",
            progress.downloaded_bytes as f64 * 100.0 / total as f64,
            format_bytes(progress.downloaded_bytes as f64),
            format_bytes(total as f64),
            format_bytes(progress.bytes_per_second),
        )
    } else {
        format!(
            "下载进度: {}  {}/s",
            format_bytes(progress.downloaded_bytes as f64),
            format_bytes(progress.bytes_per_second),
        )
    };
    print!("\r{progress_text:<80}");
    if progress.completed {
        println!();
    }
    let _ = std::io::stdout().flush();
}

#[cfg(windows)]
fn format_bytes(bytes: f64) -> String {
    if bytes >= 1024.0 * 1024.0 * 1024.0 {
        format!("{:.2}GB", bytes / 1024.0 / 1024.0 / 1024.0)
    } else if bytes >= 1024.0 * 1024.0 {
        format!("{:.2}MB", bytes / 1024.0 / 1024.0)
    } else if bytes >= 1024.0 {
        format!("{:.2}KB", bytes / 1024.0)
    } else {
        format!("{bytes:.0}B")
    }
}

#[cfg(windows)]
fn get_public_ipv4() -> Option<String> {
    let result = (|| -> Result<String> {
        let client = http::Client::with_timeout(Duration::from_secs(5));
        let text = client
            .get("https://ip.3322.net/")
            .send()?
            .error_for_status()?
            .text()?
            .trim()
            .to_owned();
        text.parse::<Ipv4Addr>()
            .map(|_| text)
            .context("服务未返回有效IPv4")
    })();
    match result {
        Ok(ipv4) => Some(ipv4),
        Err(error) => {
            write_warning(&format!("获取IPv4失败，将继续尝试更新: {error}"));
            None
        }
    }
}

#[cfg(windows)]
fn fail(message: &str) -> i32 {
    show_start_error(message);
    println!();
    print!("按回车键关闭窗口");
    let _ = std::io::stdout().flush();
    let mut ignored = String::new();
    let _ = std::io::stdin().read_line(&mut ignored);
    1
}

#[cfg(windows)]
fn show_start_error(message: &str) {
    win32::message_box(message, "RDI启动失败", win32::MB_ICONERROR);
    eprintln!("{message}");
}

#[cfg(windows)]
fn updater_mutex_name(launcher_root: &Path) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(launcher_root.to_string_lossy().to_uppercase().as_bytes());
    let hash = hex::encode_upper(hasher.finalize());
    format!("Local\\RDI-Updater-{}", &hash[..16])
}

#[cfg(windows)]
fn prompt_library_recovery(prompt: LibraryRecoveryPrompt) -> bool {
    let occupiers = if prompt.occupiers.is_empty() {
        String::new()
    } else {
        format!(
            "\r\n\r\n占用程序: {}",
            prompt
                .occupiers
                .iter()
                .map(|occupier| format!("{}(PID{})", occupier.name, occupier.process_id))
                .collect::<Vec<_>>()
                .join("、")
        )
    };
    let (message, title) = match prompt.kind {
        LibraryRecoveryPromptKind::ForceCloseOccupiers => (
            format!(
                "以下程序占用RDI的UI库，无法更新，要使用管理员权限强制关闭再更新吗？{occupiers}"
            ),
            "UI库被占用",
        ),
        LibraryRecoveryPromptKind::ElevateSwitch => (
            "没有找到占用程序，但RDI目录权限不足。是否请求管理员权限完成更新？".to_owned(),
            "需要管理员权限",
        ),
    };
    win32::message_box(&message, title, win32::MB_YESNO | win32::MB_ICONQUESTION) == win32::IDYES
}

#[cfg(windows)]
#[derive(Clone, Default)]
struct LaunchOptions {
    debug: bool,
    app_logs: bool,
    no_update: bool,
    jvm_arguments: Vec<String>,
}

#[cfg(windows)]
#[derive(Clone, Default)]
struct StartupSelection {
    jdk: Option<JdkCandidate>,
    solid_window: bool,
    debug: bool,
    no_update: bool,
    uninstall: bool,
}

#[cfg(windows)]
fn parse_launch_options(args: &[String]) -> Result<LaunchOptions> {
    let mut options = LaunchOptions::default();
    for argument in args {
        if argument.eq_ignore_ascii_case("--debug") {
            options.debug = true;
        } else if argument.eq_ignore_ascii_case("--app-logs") {
            options.app_logs = true;
        } else if argument.eq_ignore_ascii_case("--no-update") {
            options.no_update = true;
        } else if argument.len() >= JVM_ARGUMENT_PREFIX.len()
            && argument[..JVM_ARGUMENT_PREFIX.len()].eq_ignore_ascii_case(JVM_ARGUMENT_PREFIX)
        {
            let value = &argument[JVM_ARGUMENT_PREFIX.len()..];
            options.jvm_arguments.extend(parse_jvm_arguments(value)?);
        } else {
            bail!("未知启动参数: {argument}");
        }
    }
    Ok(options)
}

#[cfg(windows)]
fn show_startup_options(launcher_root: &Path) -> Result<StartupSelection> {
    let can_uninstall = launcher_root.join("lib").join("rdi-ui.jar").is_file();
    let mut options = vec![
        "重新手动选择Java25".to_owned(),
        "本次以实心窗口启动".to_owned(),
        "launch w/o upd".to_owned(),
        "launch w/ dbg".to_owned(),
    ];
    if can_uninstall {
        options.push("卸载".to_owned());
    }
    let mut selected_index = 0;
    let mut arrow_key_held = false;

    loop {
        clear_console();
        println!("启动选项（使用↑/↓选择，按Enter确认）");
        println!("按Esc键正常启动，不要选择你看不懂的选项");
        println!();
        for (index, option) in options.iter().enumerate() {
            println!(
                "{} {}.{}",
                if index == selected_index { ">" } else { " " },
                index + 1,
                option
            );
        }

        let Some(key) = read_menu_event()? else {
            continue;
        };
        if key.kind == KeyEventKind::Release {
            if matches!(key.code, KeyCode::Up | KeyCode::Down) {
                arrow_key_held = false;
            }
            continue;
        }
        if key.kind != KeyEventKind::Press {
            continue;
        }
        match key.code {
            KeyCode::Up if !arrow_key_held => {
                arrow_key_held = true;
                selected_index = (selected_index + options.len() - 1) % options.len();
            }
            KeyCode::Down if !arrow_key_held => {
                arrow_key_held = true;
                selected_index = (selected_index + 1) % options.len();
            }
            KeyCode::Esc => {
                clear_console();
                return Ok(StartupSelection::default());
            }
            KeyCode::Enter => {
                if can_uninstall && selected_index == options.len() - 1 {
                    if confirm_uninstall()? {
                        clear_console();
                        return Ok(StartupSelection {
                            uninstall: true,
                            ..StartupSelection::default()
                        });
                    }
                    continue;
                }
                clear_console();
                return match selected_index {
                    0 => match select_manual_jdk25(launcher_root)? {
                        ManualJdkSelectionResult::Selected(jdk) => Ok(StartupSelection {
                            jdk: Some(jdk),
                            ..StartupSelection::default()
                        }),
                        ManualJdkSelectionResult::Failed => Ok(StartupSelection::default()),
                    },
                    1 => Ok(StartupSelection {
                        solid_window: true,
                        ..StartupSelection::default()
                    }),
                    2 => Ok(StartupSelection {
                        no_update: true,
                        ..StartupSelection::default()
                    }),
                    _ => Ok(StartupSelection {
                        debug: true,
                        ..StartupSelection::default()
                    }),
                };
            }
            _ => {}
        }
    }
}

#[cfg(windows)]
fn clear_console() {
    print!("\x1b[2J\x1b[H");
    let _ = std::io::stdout().flush();
}

#[cfg(windows)]
fn read_menu_event() -> Result<Option<KeyEvent>> {
    enable_raw_mode().context("启用启动菜单输入失败")?;
    let event = event::read().context("读取启动菜单输入失败");
    let _ = disable_raw_mode();
    match event? {
        Event::Key(key) => Ok(Some(key)),
        _ => Ok(None),
    }
}

#[cfg(windows)]
fn read_menu_key() -> Result<KeyCode> {
    Ok(read_menu_event()?.map_or(KeyCode::Null, |key| key.code))
}

#[cfg(windows)]
fn confirm_uninstall() -> Result<bool> {
    clear_console();
    println!("卸载会把当前RDI目录下的文件和子目录，以及本地数据移入回收站。\r\n");
    print!("请输入{UNINSTALL_CONFIRMATION}并按回车开始卸载: ");
    std::io::stdout().flush()?;
    let mut confirmation = String::new();
    std::io::stdin().read_line(&mut confirmation)?;
    let confirmed = confirmation.trim_end_matches(&['\r', '\n'][..]) == UNINSTALL_CONFIRMATION;
    if !confirmed {
        println!("确认文本不正确，已取消卸载。");
    }
    Ok(confirmed)
}

#[cfg(windows)]
fn start_uninstall(launcher_root: &Path) -> i32 {
    let result = (|| -> Result<()> {
        let local_app_data =
            std::env::var_os("LOCALAPPDATA").ok_or_else(|| anyhow!("无法确定本地应用数据目录"))?;
        let rdi_ui_jar = launcher_root.join("lib").join("rdi-ui.jar");
        if rdi_ui_jar.is_file() {
            let occupiers = library_switch_recovery::find_file_occupiers(&rdi_ui_jar)
                .context("检查lib/rdi-ui.jar占用失败")?;
            if !occupiers.is_empty() {
                let occupier_names = occupiers
                    .iter()
                    .map(|occupier| format!("{}(PID{})", occupier.name, occupier.process_id))
                    .collect::<Vec<_>>()
                    .join("、");
                bail!(
                    "无法继续卸载，lib/rdi-ui.jar正在被以下程序使用：{occupier_names}。\r\n请先终止占用程序，然后重新选择卸载。"
                );
            }
        }

        let current_exe = std::env::current_exe().ok();
        let mut skipped = false;
        let entries = fs::read_dir(launcher_root).context("读取当前RDI目录内容失败")?;
        for entry in entries {
            let path = match entry {
                Ok(entry) => entry.path(),
                Err(error) => {
                    skipped = true;
                    write_info(&format!("跳过无法读取的目录项，错误: {error}"));
                    continue;
                }
            };
            if current_exe.as_deref() == Some(path.as_path()) {
                write_info(&format!("保留正在运行的Updater: {}", path.display()));
                continue;
            }
            write_info(&format!("正在移入回收站: {}", path.display()));
            if let Err(error) = win32::move_to_recycle_bin(&path) {
                skipped = true;
                write_info(&format!(
                    "跳过无法移入回收站: {}，错误: {error}",
                    path.display()
                ));
            }
        }

        let local_rdi = PathBuf::from(local_app_data).join(".rdi");
        if local_rdi.exists() {
            write_info(&format!("正在移入回收站: {}", local_rdi.display()));
            if let Err(error) = win32::move_to_recycle_bin(&local_rdi) {
                skipped = true;
                write_info(&format!(
                    "跳过无法移入回收站: {}，错误: {error}",
                    local_rdi.display()
                ));
            }
        }
        if skipped {
            write_info("卸载完成，但部分目录未能移入回收站。");
        } else {
            write_info(
                "已确认卸载，当前目录下的可删除内容和本地数据已移入回收站，当前目录本身已保留。",
            );
        }
        Ok(())
    })();
    match result {
        Ok(()) => 0,
        Err(error) => fail(&format!("卸载失败。\r\n错误: {error}")),
    }
}

#[cfg(windows)]
fn parse_jvm_arguments(arguments: &str) -> Result<Vec<String>> {
    if arguments.trim().is_empty() {
        Ok(Vec::new())
    } else {
        win32::parse_windows_command_line(arguments)
    }
}

#[cfg(windows)]
fn resolve_best_jdk25(launcher_root: &Path) -> Option<JdkCandidate> {
    if let Some(java) = find_best_jdk25(launcher_root) {
        return Some(java);
    }
    loop {
        show_no_java_options();
        match read_simple_key() {
            Ok(key) => match java_menu_choice(key) {
                Some(JavaMenuChoice::Download) => {
                    if let Some(java) = install_jdk25(launcher_root) {
                        return Some(java);
                    }
                }
                Some(JavaMenuChoice::Manual) => match select_manual_jdk25(launcher_root) {
                    Ok(ManualJdkSelectionResult::Selected(java)) => return Some(java),
                    Ok(ManualJdkSelectionResult::Failed) => {}
                    Err(error) => write_info(&format!("手动选择Java25失败：{error:#}")),
                },
                None => {}
            },
            Err(error) => {
                write_info(&format!("读取选择失败：{error}"));
                return None;
            }
        }
    }
}

#[cfg(windows)]
fn read_simple_key() -> Result<KeyCode> {
    read_menu_key()
}

#[cfg(windows)]
#[derive(Debug, PartialEq, Eq)]
enum JavaMenuChoice {
    Download,
    Manual,
}

#[cfg(windows)]
fn java_menu_choice(key: KeyCode) -> Option<JavaMenuChoice> {
    match key {
        KeyCode::Char('y' | 'Y' | ' ') => Some(JavaMenuChoice::Download),
        KeyCode::Char('m' | 'M') => Some(JavaMenuChoice::Manual),
        _ => None,
    }
}

#[cfg(windows)]
fn show_no_java_options() {
    println!();
    println!("没找到可用的完整版64位JDK25");
    println!("按Y键自动下载安装java25 按M键手动选择目录");
    println!("如果你不懂前面在说什么 按空格键（键盘底下最大最长的那个）");
}

#[cfg(windows)]
fn install_jdk25(launcher_root: &Path) -> Option<JdkCandidate> {
    let result = (|| -> Result<Option<JdkCandidate>> {
        let installer_path = std::env::current_dir()?.join("java25install.msi");
        let log_path = launcher_root.join("java25install.log");
        write_info("正在下载Java25安装程序");
        download::file(
            JDK25_DOWNLOAD_URL,
            &installer_path,
            8,
            Some(progress_callback()),
            Some(info_callback()),
        )?;
        write_info("下载完成，请允许管理员授权，随后将自动安装Java25");
        let msiexec = PathBuf::from(
            std::env::var_os("SystemRoot").ok_or_else(|| anyhow!("无法确定Windows系统目录"))?,
        )
        .join("System32")
        .join("msiexec.exe");
        let arguments = vec![
            "/i".to_owned(),
            installer_path.to_string_lossy().into_owned(),
            "/quiet".to_owned(),
            "/norestart".to_owned(),
            "ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome"
                .to_owned(),
            "/L*v".to_owned(),
            log_path.to_string_lossy().into_owned(),
        ];
        let process = match win32::start_elevated_with_error_code(&msiexec, &arguments) {
            Ok(process) => process,
            Err(win32::ERROR_CANCELLED) => {
                write_info("已取消Java25安装授权");
                return Ok(None);
            }
            Err(code) => bail!("无法启动Java25安装程序，Windows错误码{code}"),
        };
        write_info("正在自动安装Java25，请稍候");
        if !win32::wait_process(process.0, win32::INFINITE) {
            bail!(
                "等待Java25安装程序失败，安装可能仍在进行。日志：{}",
                log_path.display()
            );
        }
        let exit_code = win32::process_exit_code(process.0)?;
        if !matches!(exit_code, 0 | 3010) {
            bail!("安装程序退出码：{exit_code}。日志：{}", log_path.display());
        }
        if exit_code == 3010 {
            write_info("Java25安装完成，安装程序提示需要重启；正在检查是否可以直接使用");
        }
        let java = find_installed_temurin25().ok_or_else(|| {
            anyhow!(
                "安装结束但未在默认目录找到可用Java25。退出码：{exit_code}。日志：{}{}",
                log_path.display(),
                if exit_code == 3010 {
                    "。请重启电脑后再试"
                } else {
                    "。可返回菜单手动选择Java目录"
                },
            )
        })?;
        if let Err(error) = write_cached_jdk_list(launcher_root, std::slice::from_ref(&java)) {
            write_info(&format!("Java25已可用，但保存Java缓存失败：{error:#}"));
        }
        write_info("Java25安装完成");
        Ok(Some(java))
    })();
    match result {
        Ok(java) => java,
        Err(error) => {
            show_start_error(&format!("Java25下载安装失败。\r\n错误: {error}"));
            None
        }
    }
}

#[cfg(windows)]
fn find_installed_temurin25() -> Option<JdkCandidate> {
    // Do not rely on this process's environment reflecting the MSI's JAVA_HOME/PATH changes.
    let mut candidates = HashMap::new();
    for variable in ["ProgramW6432", "ProgramFiles"] {
        let Some(program_files) = std::env::var_os(variable) else {
            continue;
        };
        let root = PathBuf::from(program_files).join("Eclipse Adoptium");
        match fs::read_dir(&root) {
            Ok(entries) => {
                for entry in entries {
                    match entry {
                        Ok(entry) => add_jdk_path(&mut candidates, &entry.path()),
                        Err(error) => write_info(&format!("读取Java安装目录项失败：{error}")),
                    }
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => write_info(&format!("读取Java安装目录{}失败：{error}", root.display())),
        }
    }
    select_best_jdk25_candidate(find_valid_jdk25_candidates(&candidates, "安装后检查"))
}

#[cfg(windows)]
fn run_java_probe(executable: &Path, arguments: &[&str]) -> Result<String> {
    const OUTPUT_LIMIT: u64 = 64 * 1024;
    let mut child = Command::new(executable)
        .args(arguments)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
        .spawn()
        .with_context(|| format!("无法运行{}", executable.display()))?;
    let deadline = Instant::now() + Duration::from_secs(10);
    let (sender, receiver) = std::sync::mpsc::channel();
    let readers: [Box<dyn Read + Send>; 2] = [
        Box::new(child.stdout.take().expect("Java探测stdout已重定向")),
        Box::new(child.stderr.take().expect("Java探测stderr已重定向")),
    ];
    for reader in readers {
        let sender = sender.clone();
        thread::spawn(move || {
            let mut bytes = Vec::new();
            let result = reader
                .take(OUTPUT_LIMIT + 1)
                .read_to_end(&mut bytes)
                .map(|_| bytes);
            let _ = sender.send(result);
        });
    }
    drop(sender);
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(25)),
            result => {
                if let Err(error) = child.kill() {
                    write_info(&format!("结束Java探测进程失败：{error}"));
                }
                child.wait().context("等待Java探测进程退出失败")?;
                match result {
                    Err(error) => return Err(error).context("读取Java探测进程状态失败"),
                    _ => bail!("Java探测超过10秒：{}", executable.display()),
                }
            }
        }
    };
    if !status.success() {
        bail!(
            "Java探测失败，退出码：{:?}，路径：{}",
            status.code(),
            executable.display()
        );
    }
    let mut output = String::new();
    for _ in 0..2 {
        let bytes = receiver
            .recv_timeout(deadline.saturating_duration_since(Instant::now()))
            .context("读取Java探测输出超时或失败")?
            .context("读取Java探测输出失败")?;
        if bytes.len() as u64 > OUTPUT_LIMIT {
            bail!("Java探测输出超过64KiB");
        }
        output.push_str(&String::from_utf8_lossy(&bytes));
        output.push('\n');
    }
    Ok(output)
}

#[cfg(windows)]
fn validate_jdk25_probe(java_output: &str, compiler_output: &str) -> Result<String> {
    let property = |name: &str| {
        java_output.lines().find_map(|line| {
            let (key, value) = line.trim().split_once('=')?;
            (key.trim() == name).then(|| value.trim())
        })
    };
    let version = property("java.version").ok_or_else(|| anyhow!("Java未返回版本"))?;
    let architecture = property("os.arch").ok_or_else(|| anyhow!("Java未返回架构"))?;
    if parse_java_major_version(version) != Some(25) || !is_64_bit_architecture(architecture) {
        bail!("需要64位JDK25，实际Java版本：{version}，架构：{architecture}");
    }
    let compiler_version = compiler_output
        .lines()
        .find_map(|line| {
            let mut words = line.split_whitespace();
            (words.next()? == "javac").then(|| words.next()).flatten()
        })
        .ok_or_else(|| anyhow!("javac未返回版本"))?;
    if parse_java_major_version(compiler_version) != Some(25) {
        bail!("需要JDK25编译器，实际javac版本：{compiler_version}");
    }
    Ok(format!(
        "Java={version}，javac={compiler_version}，架构={architecture}"
    ))
}

#[cfg(windows)]
enum ManualJdkSelectionResult {
    Selected(JdkCandidate),
    Failed,
}

#[cfg(windows)]
fn select_manual_jdk25(launcher_root: &Path) -> Result<ManualJdkSelectionResult> {
    write_info("请手动选择JDK25安装目录");
    let Some(selected_path) = win32::pick_folder_path("选择JDK25安装目录")? else {
        write_info("未选择目录，返回Java选项");
        return Ok(ManualJdkSelectionResult::Failed);
    };
    write_info(&format!("已选择目录: {selected_path}"));
    let mut candidates = HashMap::new();
    add_jdk_path(&mut candidates, Path::new(&selected_path));
    let valid_candidates = find_valid_jdk25_candidates(&candidates, "手动选择");
    if valid_candidates.is_empty() {
        show_start_error(&format!(
            "所选目录不是可用的完整版64位JDK25：\r\n{selected_path}"
        ));
        return Ok(ManualJdkSelectionResult::Failed);
    }
    cache_jdk_candidates(launcher_root, &valid_candidates);
    print_available_jdk_list(&valid_candidates, "手动选择");
    Ok(select_best_jdk25_candidate(valid_candidates)
        .map(ManualJdkSelectionResult::Selected)
        .unwrap_or(ManualJdkSelectionResult::Failed))
}

#[cfg(windows)]
fn find_best_jdk25(launcher_root: &Path) -> Option<JdkCandidate> {
    write_info("开始搜索JDK25");
    if let Some(cached_java_homes) = read_cached_jdk_list(launcher_root) {
        let mut cached_candidates = HashMap::new();
        for java_home in cached_java_homes {
            add_jdk_path(&mut cached_candidates, Path::new(&java_home));
        }
        if let Some(java) = find_first_valid_jdk25_candidate(&cached_candidates, "缓存校验") {
            return Some(java);
        }
    }
    write_info("正在检查环境变量、注册表和常见Java安装目录");
    let mut candidates = HashMap::new();
    for path in java_discovery::candidate_paths() {
        insert_candidate(&mut candidates, path);
    }
    let valid = find_valid_jdk25_candidates(&candidates, "JDK25校验");
    cache_jdk_candidates(launcher_root, &valid);
    print_available_jdk_list(&valid, "JDK25搜索");
    select_best_jdk25_candidate(valid)
}

#[cfg(windows)]
fn cache_jdk_candidates(launcher_root: &Path, candidates: &[JdkCandidate]) {
    if let Err(error) = write_cached_jdk_list(launcher_root, candidates) {
        write_info(&format!("保存Java缓存失败：{error:#}"));
    }
}

#[cfg(windows)]
fn read_cached_jdk_list(launcher_root: &Path) -> Option<Vec<String>> {
    let cache_file = launcher_root.join("available_jdks.txt");
    if !cache_file.is_file() {
        write_info("未发现Java缓存文件avaliable_jdks.txt");
        return Some(Vec::new());
    }
    let content = fs::read_to_string(&cache_file).ok()?;
    let has_utf8_bom = content.starts_with('\u{feff}');
    let content = content.strip_prefix('\u{feff}').unwrap_or(&content);
    let mut seen = HashSet::new();
    let values = content
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .filter(|line| seen.insert(line.to_ascii_lowercase()))
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();
    if has_utf8_bom {
        let normalized_content = if values.is_empty() {
            String::new()
        } else {
            values.join("\n") + "\n"
        };
        match fs::write(&cache_file, normalized_content) {
            Ok(()) => write_info("已将Java缓存转换为无BOMUTF-8"),
            Err(error) => write_info(&format!("读取Java缓存成功，但移除BOM失败: {error}")),
        }
    }
    write_info(&format!("读取Java缓存成功，共{}条", values.len()));
    Some(values)
}

#[cfg(windows)]
fn write_cached_jdk_list(launcher_root: &Path, candidates: &[JdkCandidate]) -> Result<()> {
    let mut java_homes = candidates
        .iter()
        .map(|candidate| candidate.java_home.to_string_lossy().to_string())
        .collect::<Vec<_>>();
    java_homes.sort_by_key(|value| value.to_ascii_lowercase());
    java_homes.dedup_by_key(|value| value.to_ascii_lowercase());
    fs::write(
        launcher_root.join("available_jdks.txt"),
        java_homes.join("\n") + "\n",
    )?;
    write_info(&format!(
        "已写入Java缓存到avaliable_jdks.txt，共{}条",
        java_homes.len()
    ));
    Ok(())
}

#[cfg(windows)]
fn add_jdk_path(candidates: &mut HashMap<String, String>, path: &Path) {
    let normalized = normalize_path(path);
    if normalized.is_dir() {
        for javac_executable in [
            normalized.join("javac.exe"),
            normalized.join("bin").join("javac.exe"),
        ] {
            if javac_executable.is_file() {
                insert_java_candidate(candidates, javac_executable);
            }
        }
    } else if normalized.is_file()
        && normalized
            .file_name()
            .is_some_and(|name| name.to_string_lossy().eq_ignore_ascii_case("javac.exe"))
    {
        insert_java_candidate(candidates, normalized);
    }
}

#[cfg(windows)]
fn insert_java_candidate(candidates: &mut HashMap<String, String>, javac_executable: PathBuf) {
    let Some(java_executable) = javac_executable
        .parent()
        .map(|parent| parent.join("java.exe"))
    else {
        return;
    };
    insert_candidate(candidates, java_executable);
}

#[cfg(windows)]
fn insert_candidate(candidates: &mut HashMap<String, String>, path: PathBuf) {
    let key = path.to_string_lossy().to_ascii_lowercase();
    candidates
        .entry(key)
        .or_insert(path.to_string_lossy().to_string());
}

#[cfg(windows)]
fn find_valid_jdk25_candidates(
    candidates: &HashMap<String, String>,
    stage_name: &str,
) -> Vec<JdkCandidate> {
    let values = candidates.values().cloned().collect::<Vec<_>>();
    let valid = validate_jdk25_candidates_parallel(&values, stage_name);
    for candidate in &valid {
        write_info(&format!(
            "发现可用Java25: {}",
            candidate.java_home.display()
        ));
    }
    valid
}

#[cfg(windows)]
fn find_first_valid_jdk25_candidate(
    candidates: &HashMap<String, String>,
    stage_name: &str,
) -> Option<JdkCandidate> {
    let valid = validate_jdk25_candidates_parallel(
        &candidates.values().cloned().collect::<Vec<_>>(),
        stage_name,
    );
    let best = select_best_jdk25_candidate(valid)?;
    write_info(&format!(
        "阶段[{stage_name}]发现可用Java25，直接使用: {}",
        best.java_home.display()
    ));
    Some(best)
}

#[cfg(windows)]
fn validate_jdk25_candidates_parallel(
    candidates: &[String],
    stage_name: &str,
) -> Vec<JdkCandidate> {
    if candidates.is_empty() {
        return Vec::new();
    }
    let thread_count = MAX_PARALLEL_JDK_CHECKS.min(candidates.len());
    rayon::ThreadPoolBuilder::new()
        .num_threads(thread_count)
        .build()
        .expect("创建Java校验线程池失败")
        .install(|| {
            candidates
                .par_iter()
                .enumerate()
                .filter_map(|(index, java_exe)| {
                    write_info(&format!(
                        "并行校验阶段[{stage_name}] {}/{}: {java_exe}",
                        index + 1,
                        candidates.len()
                    ));
                    test_java_candidate(java_exe)
                })
                .collect()
        })
}

#[cfg(windows)]
fn test_java_candidate(java_executable: &str) -> Option<JdkCandidate> {
    let java_exe = Path::new(java_executable);
    if !java_exe.is_file() {
        return None;
    }
    let java_home = java_exe.parent()?.parent()?.to_path_buf();
    let javac_exe = java_home.join("bin").join("javac.exe");
    if !javac_exe.is_file() || !java_home.join("bin/javaw.exe").is_file() {
        write_info(&format!("跳过该Java，不是JDK: {}", java_home.display()));
        return None;
    }
    let Some(release) = read_jdk_release_info(&java_home) else {
        write_info(&format!(
            "跳过该JDK，缺少有效release信息: {}",
            java_home.display()
        ));
        return None;
    };
    let Some(release_major_version) = parse_java_major_version(&release.java_version) else {
        write_info(&format!(
            "跳过该JDK，无法解析release版本: {}",
            java_home.display()
        ));
        return None;
    };
    if release_major_version != 25 {
        write_info(&format!(
            "跳过该JDK，release主版本不是25: {}",
            java_home.display()
        ));
        return None;
    }
    if !is_64_bit_architecture(&release.os_arch) {
        write_info(&format!("跳过该Java，不是64位: {java_executable}"));
        return None;
    }
    let probe = (|| -> Result<String> {
        let java_output = run_java_probe(java_exe, &["-XshowSettings:properties", "-version"])?;
        let compiler_output = run_java_probe(&javac_exe, &["-version"])?;
        validate_jdk25_probe(&java_output, &compiler_output)
    })();
    match probe {
        Ok(version_text) => {
            write_info(&format!(
                "验证JDK25成功：{java_executable} -> {version_text}"
            ));
            Some(JdkCandidate {
                path_score: path_score(java_exe),
                java_home,
                version_text,
            })
        }
        Err(error) => {
            write_info(&format!(
                "跳过不可用的JDK：{java_executable}，原因：{error:#}"
            ));
            None
        }
    }
}

#[cfg(windows)]
struct JdkReleaseInfo {
    java_version: String,
    os_arch: String,
}

#[cfg(windows)]
fn read_jdk_release_info(java_home: &Path) -> Option<JdkReleaseInfo> {
    let content = fs::read_to_string(java_home.join("release")).ok()?;
    Some(JdkReleaseInfo {
        java_version: read_release_field(&content, "JAVA_VERSION=")?,
        os_arch: read_release_field(&content, "OS_ARCH=")?,
    })
}

#[cfg(windows)]
fn read_release_field(content: &str, field: &str) -> Option<String> {
    content.lines().find_map(|line| {
        let value = line.strip_prefix(field)?.trim();
        Some(value.strip_prefix('"')?.strip_suffix('"')?.to_owned())
    })
}

#[cfg(windows)]
fn parse_java_major_version(version: &str) -> Option<i32> {
    let version = version.strip_prefix("1.").unwrap_or(version);
    version
        .split(|ch: char| !ch.is_ascii_digit())
        .next()?
        .parse()
        .ok()
}

#[cfg(windows)]
fn is_64_bit_architecture(architecture: &str) -> bool {
    matches!(
        architecture.to_ascii_lowercase().as_str(),
        "amd64" | "x86_64" | "aarch64" | "arm64"
    )
}

#[cfg(windows)]
fn select_best_jdk25_candidate(mut candidates: Vec<JdkCandidate>) -> Option<JdkCandidate> {
    candidates.sort_by(|left, right| {
        right
            .path_score
            .cmp(&left.path_score)
            .then_with(|| {
                left.java_home
                    .to_string_lossy()
                    .len()
                    .cmp(&right.java_home.to_string_lossy().len())
            })
            .then_with(|| left.java_home.cmp(&right.java_home))
    });
    candidates.into_iter().next()
}

#[cfg(windows)]
fn print_available_jdk_list(candidates: &[JdkCandidate], source_name: &str) {
    write_info(&format!(
        "可用Java25列表[{source_name}]，共{}项:",
        candidates.len()
    ));
    let mut sorted = candidates.to_vec();
    sorted.sort_by(|left, right| {
        right
            .path_score
            .cmp(&left.path_score)
            .then_with(|| {
                left.java_home
                    .to_string_lossy()
                    .len()
                    .cmp(&right.java_home.to_string_lossy().len())
            })
            .then_with(|| left.java_home.cmp(&right.java_home))
    });
    for (index, candidate) in sorted.iter().enumerate() {
        write_info(&format!(
            "  [{}] {}",
            index + 1,
            candidate.java_home.display()
        ));
        write_info(&format!("       {}", candidate.version_text));
    }
}

#[cfg(windows)]
fn path_score(java_exe: &Path) -> i32 {
    let path = java_exe.to_string_lossy().to_ascii_lowercase();
    let mut score = 0;
    if path.starts_with(&launcher_root_string().to_ascii_lowercase()) {
        score += 5000;
    }
    if path.contains("\\.jdks\\") {
        score += 1000;
    }
    if path.contains("\\program files\\") {
        score += 600;
    }
    if path.contains("\\users\\") {
        score += 300;
    }
    if path.contains("jdk-25") || path.contains("jdk25") {
        score += 400;
    }
    if path.contains("temurin") {
        score += 120;
    }
    if path.contains("microsoft") {
        score += 100;
    }
    if path.contains("oracle") {
        score += 80;
    }
    score
}

#[cfg(windows)]
fn launcher_root_string() -> String {
    std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .unwrap_or_default()
        .to_string_lossy()
        .to_string()
}

#[cfg(windows)]
fn start_client(
    launcher_root: &Path,
    java_exe: &Path,
    options: &LaunchOptions,
    solid_window: bool,
    r_server_url: &str,
) -> i32 {
    let mut command = Command::new(java_exe);
    command.current_dir(launcher_root);
    command.creation_flags(0x0800_0000);
    command.arg("-Dfile.encoding=UTF-8");
    for argument in &options.jvm_arguments {
        command.arg(argument);
    }
    command.arg(format!("-Drdi.debug={}", options.debug));
    if options.no_update {
        command.arg("-Drdi.noUpdate=true");
    }
    if solid_window {
        command.arg("-Drdi.window.transparent=false");
    }
    command.arg(format!("-Drdi.updater.pid={}", win32::process_id()));
    command.arg(format!("-Drdi.updater.islogmode={}", options.app_logs));
    command.arg(format!("-Drserverurl={r_server_url}"));
    command.args([
        "-cp",
        "lib/*",
        "--enable-native-access=ALL-UNNAMED",
        "calebxzau.rdi.client.MainKt",
    ]);
    if options.app_logs {
        command.stdout(Stdio::piped()).stderr(Stdio::piped());
    }

    write_info(&format!(
        "将使用Java25启动: {}",
        java_exe
            .parent()
            .and_then(Path::parent)
            .unwrap_or(java_exe)
            .display()
    ));
    let mut process = match command.spawn() {
        Ok(process) => process,
        Err(error) => return fail(&format!("无法启动RDI客户端。\r\n错误: {error}")),
    };

    if options.app_logs {
        write_info(&format!("已启动进程PID: {}，正在接收RDI日志", process.id()));
        let output_thread = process
            .stdout
            .take()
            .map(|stream| thread::spawn(move || forward_app_logs(stream)));
        let error_thread = process
            .stderr
            .take()
            .map(|stream| thread::spawn(move || forward_app_logs(stream)));
        let status = process
            .wait()
            .ok()
            .and_then(|status| status.code())
            .unwrap_or(1);
        if let Some(thread) = output_thread {
            let _ = thread.join();
        }
        if let Some(thread) = error_thread {
            let _ = thread.join();
        }
        write_info(&format!("RDI已退出，退出码: {status}"));
        return status;
    }

    write_info("已启动，正在观察确认是否稳定启动");
    let started_at = Instant::now();
    loop {
        match process.try_wait() {
            Ok(Some(status)) => {
                return fail(&format!(
                    "程序在启动后3秒内退出\r\n退出码: {:?}\r\nJava: {}",
                    status.code(),
                    java_exe.display()
                ));
            }
            Ok(None) if started_at.elapsed() >= Duration::from_secs(3) => break,
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(error) => return fail(&format!("观察RDI启动状态失败。\r\n错误: {error}")),
        }
    }
    write_info("启动成功，2秒后关闭本窗口");
    thread::sleep(Duration::from_secs(2));
    0
}

#[cfg(windows)]
fn forward_app_logs<R: Read>(reader: R) {
    for line in BufReader::new(reader).lines().map_while(|line| line.ok()) {
        println!("{line}");
    }
}

#[cfg(windows)]
#[derive(Clone)]
struct JdkCandidate {
    java_home: PathBuf,
    version_text: String,
    path_score: i32,
}

#[cfg(windows)]
fn normalize_path(path: &Path) -> PathBuf {
    let path = PathBuf::from(path.to_string_lossy().trim().trim_matches('"'));
    if path.is_absolute() {
        path
    } else {
        std::env::current_dir()
            .map(|current| current.join(&path))
            .unwrap_or(path)
    }
}

#[cfg(all(test, windows))]
mod jdk_tests {
    use super::*;

    #[test]
    fn java_menu_accepts_only_download_or_manual_keys() {
        for key in ['y', 'Y', ' '] {
            assert_eq!(
                java_menu_choice(KeyCode::Char(key)),
                Some(JavaMenuChoice::Download)
            );
        }
        for key in ['m', 'M'] {
            assert_eq!(
                java_menu_choice(KeyCode::Char(key)),
                Some(JavaMenuChoice::Manual)
            );
        }
        assert_eq!(java_menu_choice(KeyCode::Enter), None);
        assert_eq!(java_menu_choice(KeyCode::Char('r')), None);
    }

    #[test]
    fn requires_both_java25_and_javac25() {
        let properties = "Property settings:\n    java.version = 25.0.3\n    os.arch = amd64\n";
        assert!(validate_jdk25_probe(properties, "javac 25.0.3\n").is_ok());
        assert!(validate_jdk25_probe(properties, "javac 21.0.8\n").is_err());
        assert!(validate_jdk25_probe(properties, "").is_err());
        assert!(
            validate_jdk25_probe(&properties.replace("25.0.3", "21.0.8"), "javac 25.0.3").is_err()
        );
    }

    #[test]
    fn rejects_32_bit_unknown_architecture_and_incomplete_probe() {
        for architecture in ["x86", "i386", "arm", "unknown", ""] {
            let properties = format!("java.version = 25\nos.arch = {architecture}");
            assert!(validate_jdk25_probe(&properties, "javac 25").is_err());
        }
        assert!(validate_jdk25_probe("java.version = 25", "javac 25").is_err());
        assert!(validate_jdk25_probe("os.arch = amd64", "javac 25").is_err());
    }

    #[test]
    fn recognizes_major_version_without_accepting_other_releases() {
        assert_eq!(parse_java_major_version("25"), Some(25));
        assert_eq!(parse_java_major_version("25.0.3+9"), Some(25));
        assert_eq!(parse_java_major_version("25-ea"), Some(25));
        assert_eq!(parse_java_major_version("1.8.0_451"), Some(8));
        assert_eq!(parse_java_major_version("invalid"), None);
    }
}
