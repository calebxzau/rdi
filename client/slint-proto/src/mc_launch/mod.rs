//! J5a/J5b: the launch command and the game process, ported from `client/mclaunch`
//! (`MinecraftLauncher.launchInternal`) for Forge 1.20.1 and NeoForge 1.21.1, without RDI runtimes
//! (local packs) or with them (rooms). The command must equal Kotlin's; `protocol/fixtures/j5a.json`
//! and `j5b-launch.json` are written by the Kotlin tests `J5aLaunchCommandFixtureTest` and
//! `J5bLaunchCommandFixtureTest` and checked here (decision D6).
pub mod manifest;
#[cfg(test)]
mod tests;

use anyhow::{Context, Result, bail, ensure};
use manifest::{Host, Library, Manifest};
use std::path::{Path, PathBuf};

const JAVA4SHELL_MITIGATION: &str = "-XX:-UseBASE64Intrinsics";
const UTF8_LOGGING: [&str; 3] = ["-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8"];

#[derive(Clone, Debug)]
pub struct Directories {
    pub versions: PathBuf,
    pub libraries: PathBuf,
    pub assets: PathBuf,
}

/// The game identity. `Debug` is not derived: the token must never reach a log.
#[derive(Clone)]
pub struct Account {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    /// `msa` for RDI and Microsoft sessions, `legacy` for offline play.
    pub user_type: String,
}

/// Inputs of `launchInternal` for a pack started without the RDI core.
pub struct Request<'a> {
    pub mc_version: &'a str,
    pub version_id: &'a str,
    pub version_dir: &'a Path,
    pub manifest: &'a Manifest,
    /// The loader manifest after `normalizeLoaderManifest`.
    pub loader: &'a Manifest,
    pub account: &'a Account,
    pub window: (u32, u32),
    /// Per-pack Java; `None` uses [`Environment::java`].
    pub java_override: Option<&'a str>,
    pub max_memory_override: Option<i32>,
    pub extra_jvm_args: &'a [String],
    /// `rdiRuntime = true`: a room game. `None` for local packs.
    pub rdi: Option<RdiRuntime<'a>>,
}

/// The RDI runtimes of a room game on Forge 1.20.1 or NeoForge 1.21.1.
pub struct RdiRuntime<'a> {
    /// `forge` or `neoforge`, as `ModLoader.name`.
    pub loader: &'a str,
    /// Kotlin, zstd-jni and mediaproc jars; they go first on the class path.
    pub classpath: &'a [PathBuf],
    /// The unpacked FFmpeg natives (`MediaProcGameRuntime.nativeLibraryDir`).
    pub media_natives: &'a Path,
}

/// `neoForgeLoggingJvmArgs`: quiet FML's marker logging on NeoForge 1.21.1 unless the player set it.
pub fn neoforge_logging_jvm_args(mc_version: &str, loader: &str, extra_jvm_args: &[String]) -> Vec<String> {
    if mc_version != "1.21.1" || !loader.eq_ignore_ascii_case("neoforge") {
        return vec![];
    }
    let user: std::collections::HashSet<&str> = extra_jvm_args.iter().map(|a| a.split('=').next().unwrap_or(a)).collect();
    [
        "-Dforge.logging.marker.loading=NEUTRAL",
        "-Dforge.logging.marker.core=NEUTRAL",
        "-Dforge.logging.marker.forgemod=NEUTRAL",
        "-Drdi.logging.rootLevel=DEBUG",
    ]
    .into_iter()
    .filter(|a| !user.contains(a.split('=').next().unwrap_or(a)))
    .map(String::from)
    .collect()
}

pub struct Environment<'a> {
    pub directories: &'a Directories,
    pub host: &'a Host,
    /// The default Java 25 found at startup (D5), and its major version.
    pub java: &'a str,
    pub java_major: u32,
    /// `config.toml` `maxMemory`; 0 means "use the free memory, at least 8GiB".
    pub max_memory_mb: i32,
    pub launcher_version: &'a str,
    /// Free physical memory in MiB, read only when no maximum is configured.
    pub free_memory_mb: &'a dyn Fn() -> u64,
}

/// `maskLaunchSecrets`: players share launcher logs, so the token is replaced.
pub fn mask_secrets(arguments: &[String]) -> Vec<String> {
    arguments
        .iter()
        .enumerate()
        .map(|(i, a)| if i > 0 && arguments[i - 1] == "--accessToken" { "***".into() } else { a.clone() })
        .collect()
}

/// `readJdkReleaseMajor`: JAVA_VERSION in `<java>/../../release`.
pub fn release_major(java: &Path) -> Option<u32> {
    let java = std::path::absolute(java).ok()?;
    let release = java.parent()?.parent()?.join("release");
    let text = std::fs::read_to_string(release).ok()?;
    let version = text.lines().find_map(|l| l.strip_prefix("JAVA_VERSION="))?.trim().trim_matches(|c| c == '"' || c == ' ');
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 { parts.next()?.parse().ok() } else { Some(first) }
}

fn text(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

/// Builds the full command, `java` first. Pure apart from reading which files exist.
pub fn command(request: &Request, env: &Environment) -> Result<Vec<String>> {
    let host = env.host;
    let dirs = env.directories;
    let manifest = request.manifest;
    let mut loader = request.loader.clone();
    loader.id = request.version_id.into();
    let base_libraries: &[Library] = &manifest.libraries;
    let version_dir = request.version_dir;
    let natives = {
        let own = version_dir.join("natives");
        if own.exists() { own } else { dirs.versions.join(request.mc_version).join("natives") }
    };
    let assets_index = manifest
        .assets
        .clone()
        .or_else(|| manifest.asset_index.as_ref().map(|a| a.id.clone()))
        .unwrap_or_else(|| manifest.id.clone());
    let a = request.account;
    let mut game_args: Vec<String> = manifest
        .launch_game_arguments(&loader, host)
        .into_iter()
        .map(|arg| {
            arg.replace("${auth_player_name}", &a.name)
                .replace("${version_name}", request.version_id)
                .replace("${game_directory}", &text(version_dir))
                .replace("${assets_root}", &text(&dirs.assets))
                .replace("${assets_index_name}", &assets_index)
                .replace("${auth_uuid}", &a.uuid.replace('-', ""))
                .replace("${auth_access_token}", &a.access_token)
                .replace("${user_type}", &a.user_type)
                .replace("${version_type}", "RDI")
                .replace("${user_properties}", "{}")
        })
        .collect();
    game_args.extend(["--width".into(), request.window.0.to_string(), "--height".into(), request.window.1.to_string()]);

    let mut resolved_jvm = manifest.jvm_arguments(host);
    resolved_jvm.extend(loader.jvm_arguments(host));

    let mut classpath_entries: Vec<String> = vec![];
    for entry in request.rdi.iter().flat_map(|r| r.classpath) {
        let entry = text(entry);
        if !classpath_entries.contains(&entry) {
            classpath_entries.push(entry);
        }
    }
    for entry in manifest::classpath(base_libraries, &loader.libraries, &dirs.libraries, host) {
        let entry = text(&entry);
        if !classpath_entries.contains(&entry) {
            classpath_entries.push(entry);
        }
    }
    let bootstrap = loader.is_bootstrap_module_launch(host);
    if bootstrap {
        let name = manifest::first_not_blank(&[&loader.jar, &loader.inherits_from, &manifest.jar])
            .unwrap_or(&manifest.id)
            .to_owned();
        let found = [version_dir.join(format!("{name}.jar")), dirs.versions.join(&name).join(format!("{name}.jar"))]
            .into_iter()
            .any(|f| f.exists());
        ensure!(found, "缺少Minecraft客户端JAR: {name}");
    }
    for jar in manifest::version_jar_candidates(manifest, &loader, version_dir, &dirs.versions, request.version_id) {
        let jar_text = text(&jar);
        if jar.exists() && !classpath_entries.contains(&jar_text) {
            classpath_entries.push(jar_text);
        }
    }
    let classpath = classpath_entries.join(&host.path_separator);
    let legacy = resolved_jvm.is_empty()
        && (manifest.minecraft_arguments.as_deref().is_some_and(|m| !m.trim().is_empty())
            || loader.minecraft_arguments.as_deref().is_some_and(|m| !m.trim().is_empty()));
    let declares_classpath = resolved_jvm.iter().any(|a| a == "-cp" || a == "-classpath" || a.contains("${classpath}"));
    let jvm_version_name = if bootstrap {
        manifest::first_not_blank(&[&loader.jar, &loader.inherits_from]).unwrap_or(&manifest.id).to_owned()
    } else {
        request.version_id.to_owned()
    };
    let launcher_brand = "rdi";
    let mut jvm: Vec<String> = resolved_jvm
        .iter()
        .map(|raw| {
            raw.replace("${natives_directory}", &text(&natives))
                .replace("${game_directory}", &text(version_dir))
                .replace("${library_directory}", &text(&dirs.libraries))
                .replace("${libraries_directory}", &text(&dirs.libraries))
                .replace("${launcher_name}", launcher_brand)
                .replace("${launcher_version}", env.launcher_version)
                .replace("${version_name}", &jvm_version_name)
                .replace("${classpath}", &classpath)
                .replace("${classpath_separator}", &host.path_separator)
        })
        .collect();
    if legacy {
        jvm.push(format!("-Djava.library.path={}", text(&natives)));
        jvm.push(format!("-Dminecraft.launcher.brand={launcher_brand}"));
        jvm.push(format!("-Dminecraft.launcher.version={}", env.launcher_version));
    }
    if !classpath_entries.is_empty() && !declares_classpath {
        jvm.push("-cp".into());
        jvm.push(classpath.clone());
    }
    let java = request.java_override.map(str::trim).filter(|j| !j.is_empty()).unwrap_or(env.java).to_owned();
    let max_memory = match request.max_memory_override.unwrap_or(env.max_memory_mb) {
        m if m > 0 => m as u64,
        _ => {
            let free = (env.free_memory_mb)();
            if free > 8192 { free } else { 8192 }
        }
    };
    jvm.push(format!("-Xmx{max_memory}M"));
    jvm.extend([
        "-Xms512M".into(),
        "-XX:+UseZGC".into(),
        format!("-XX:SoftMaxHeapSize={}M", max_memory * 3 / 4),
        "-XX:ZUncommitDelay=60".into(),
    ]);
    let major = if java == env.java { Some(env.java_major) } else { release_major(Path::new(&java)) };
    if major.unwrap_or(0) >= 25 {
        jvm.push("-XX:+UseCompactObjectHeaders".into());
    }
    jvm.push(format!("-XX:ErrorFile={}", text(&version_dir.join("logs").join("hs_err_pid%p.log"))));
    jvm.extend(UTF8_LOGGING.iter().map(|s| s.to_string()));
    if let Some(rdi) = &request.rdi {
        jvm.extend(neoforge_logging_jvm_args(request.mc_version, rdi.loader, request.extra_jvm_args));
        jvm.extend([
            "-Dorg.bytedeco.javacpp.pathsFirst=true".into(),
            "-Dorg.bytedeco.javacpp.findLibraries=false".into(),
            "-Dorg.bytedeco.javacpp.maxPhysicalBytes=0".into(),
            format!("-Dorg.bytedeco.javacpp.platform.preloadpath={}", text(rdi.media_natives)),
        ]);
    }
    jvm.extend(request.extra_jvm_args.iter().cloned());
    jvm.push(JAVA4SHELL_MITIGATION.into());
    let main_class = loader.main_class.clone().context("缺少启动主类")?;
    let mut command = vec![java];
    command.extend(jvm);
    command.push(main_class);
    command.extend(game_args);
    Ok(command)
}

/// The libraries the launch checks before starting: main and native artifacts of the merged list.
pub fn launch_artifacts<'a>(base: &'a [Library], loader: &'a [Library], host: &Host) -> Vec<(&'a Library, manifest::Artifact, String)> {
    let mut seen = std::collections::HashSet::new();
    let mut out = vec![];
    for library in manifest::merge_libraries(base, loader, host) {
        if let Some(a) = library.main_artifact() {
            let path = a.path.clone().filter(|p| !p.trim().is_empty()).or_else(|| manifest::descriptor_path(&library.name));
            if let Some(path) = path
                && seen.insert(path.clone())
            {
                out.push((library, a, path));
            }
        }
        if let Some(a) = library.native_artifact(host)
            && let Some(path) = a.path.clone().filter(|p| !p.trim().is_empty())
            && seen.insert(path.clone())
        {
            out.push((library, a, path));
        }
    }
    out
}

/// `MinecraftLaunchLibraryPreparer.validate` for one file: the reason it is unusable, if any.
pub fn library_issue(file: &Path, sha1: &str, kind: &str) -> Option<String> {
    let Ok(meta) = std::fs::metadata(file) else {
        return Some(format!("{kind}缺失"));
    };
    if meta.len() == 0 {
        return Some(format!("{kind}文件为空"));
    }
    let expected = sha1.trim();
    if !expected.is_empty() {
        match crate::mc_install::sha1_file(file) {
            Ok(actual) if actual.eq_ignore_ascii_case(expected) => None,
            Ok(actual) => Some(format!("{kind}校验失败 expected={expected} actual={actual}")),
            Err(error) => Some(format!("无法计算{kind}校验值: {error}")),
        }
    } else if crate::mc_install::opens_as_zip(file) {
        None
    } else {
        Some(format!("{kind}无法打开jar"))
    }
}

/// Every library the launch needs is present and intact; Kotlin checks this right before starting.
pub fn validate_libraries(manifest: &Manifest, loader: &Manifest, libraries_dir: &Path, host: &Host) -> Result<()> {
    let issues: Vec<String> = launch_artifacts(&manifest.libraries, &loader.libraries, host)
        .into_iter()
        .filter_map(|(library, artifact, path)| {
            let file = libraries_dir.join(&path);
            let kind = if library.native_artifact(host).is_some_and(|n| n == artifact) { "原生库" } else { "运行库" };
            library_issue(&file, &artifact.sha1, kind).map(|reason| {
                format!("{}: {reason} ({})", library.name, file.file_name().map(|n| n.to_string_lossy()).unwrap_or_default())
            })
        })
        .collect();
    if !issues.is_empty() {
        bail!("{}", issues.join("; "));
    }
    Ok(())
}

/// A started game: lines arrive on `lines` until the process exits, then the exit line.
pub struct Started {
    pub child: std::process::Child,
}

/// Starts the command in the version directory with stdout and stderr on one pipe
/// (`redirectErrorStream(true)`). `on_line` receives every non-blank line, then
/// `MC已结束，退出代码: N` or `已退出` once the process ends.
pub fn spawn(
    command: &[String],
    working_dir: &Path,
    on_line: impl Fn(String) + Send + 'static,
) -> Result<Started> {
    let (reader, writer) = std::io::pipe().context("创建游戏输出管道失败")?;
    let mut process = std::process::Command::new(&command[0]);
    process
        .args(&command[1..])
        .current_dir(working_dir)
        .stdin(std::process::Stdio::null())
        .stdout(writer.try_clone().context("创建游戏输出管道失败")?)
        .stderr(writer);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // Java's ProcessBuilder does the same: no console window for the game.
        process.creation_flags(0x0800_0000);
    }
    let child = process.spawn().with_context(|| format!("无法启动游戏进程: {}", command[0]))?;
    // The Command still holds the write ends; drop it so the reader sees end of file.
    drop(process);
    std::thread::Builder::new()
        .name("mc-log-reader".into())
        .spawn(move || {
            use std::io::BufRead;
            let mut reader = std::io::BufReader::new(reader);
            let mut buffer = vec![];
            loop {
                buffer.clear();
                match reader.read_until(b'\n', &mut buffer) {
                    Ok(0) => break,
                    Ok(_) => {
                        let line = String::from_utf8_lossy(&buffer);
                        let line = line.trim_end_matches(['\r', '\n']);
                        if !line.trim().is_empty() {
                            on_line(line.to_owned());
                        }
                    }
                    Err(error) => {
                        tracing::warn!(%error, "读取游戏输出失败");
                        break;
                    }
                }
            }
            on_line(String::new());
        })
        .context("启动游戏输出读取失败")?;
    Ok(Started { child })
}

/// `readProcessOutput`'s final line for an exit status.
pub fn exit_line(code: Option<i32>) -> String {
    match code {
        Some(0) => "已退出".into(),
        Some(code) => format!("MC已结束，退出代码: {code}"),
        None => "MC已结束，退出代码: -1".into(),
    }
}
