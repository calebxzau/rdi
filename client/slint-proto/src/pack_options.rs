//! J2: per-pack launch options in `data.db`, ported from Kotlin `ModpackOptionViewModel`.
//! Kotlin owns the schema until J6 (D4): this module never creates or migrates the file.
use anyhow::{Context, Result, bail, ensure};
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use std::{
    path::{Component, Path, PathBuf},
    time::Duration,
};

pub const DEFAULT_JDWP_PARAM: &str = "transport=dt_socket,server=y,suspend=y,address=*:5005";
/// Kotlin `MinecraftInstallationDatabase.APPLICATION_ID` and `SCHEMA_VERSION`.
const APPLICATION_ID: i64 = 0x524449;
const SCHEMA_VERSION: i64 = 5;
const RESERVED_JVM_ARGUMENTS: [&str; 4] = ["-Xmx", "-agentlib:jdwp", "-Drdi.play=", "-Drdi.dm.server="];

/// Kotlin `ModpackOptionDraft`.
#[derive(Clone, Debug, PartialEq)]
pub struct Draft {
    pub java_custom_enabled: bool,
    pub java_path: String,
    pub memory_custom_enabled: bool,
    pub max_memory_text: String,
    pub jdwp_enabled: bool,
    pub jdwp_param: String,
    pub custom_jvm_params: String,
    pub forgeguard_disabled: bool,
}
impl Default for Draft {
    fn default() -> Self {
        Self {
            java_custom_enabled: false,
            java_path: String::new(),
            memory_custom_enabled: false,
            max_memory_text: String::new(),
            jdwp_enabled: false,
            jdwp_param: DEFAULT_JDWP_PARAM.into(),
            custom_jvm_params: String::new(),
            forgeguard_disabled: false,
        }
    }
}
/// A `modpack_launch_options` row, Kotlin `ModpackLaunchOptionsRecord`.
#[derive(Clone, Debug, PartialEq)]
pub struct Record {
    pub version_id: String,
    pub java_path: Option<String>,
    pub max_memory_mb: Option<i32>,
    pub jdwp_enabled: bool,
    pub jdwp_param: Option<String>,
    pub custom_jvm_params: String,
    pub forgeguard_disabled: bool,
}
impl Record {
    /// Kotlin `ModpackLaunchOptionsRecord?.toDraft()`.
    fn draft(record: Option<&Record>) -> Draft {
        let Some(r) = record else {
            return Draft::default();
        };
        Draft {
            java_custom_enabled: r.java_path.is_some(),
            java_path: r.java_path.clone().unwrap_or_default(),
            memory_custom_enabled: r.max_memory_mb.is_some(),
            max_memory_text: r.max_memory_mb.map(|m| m.to_string()).unwrap_or_default(),
            jdwp_enabled: r.jdwp_enabled,
            jdwp_param: r
                .jdwp_param
                .clone()
                .filter(|p| !p.trim().is_empty())
                .unwrap_or_else(|| DEFAULT_JDWP_PARAM.into()),
            custom_jvm_params: r.custom_jvm_params.clone(),
            forgeguard_disabled: r.forgeguard_disabled,
        }
    }
}
pub struct Loaded {
    pub draft: Draft,
    pub total_memory_mb: i32,
}

pub fn database_path() -> PathBuf {
    crate::paths::data_dir().join("data.db")
}

/// Blocking: reads the saved row. Errors carry the reason only; the caller adds 读取整合包设置失败.
pub fn load(db: &Path, version_id: &str) -> Result<Loaded> {
    let total_memory_mb = total_memory_mb();
    let record = find(&open(db)?, version_id)?;
    Ok(Loaded { draft: Record::draft(record.as_ref()), total_memory_mb })
}

/// Blocking, since it may run the chosen `java`: validates and writes, or deletes an all-default row.
pub fn save(db: &Path, version_id: &str, draft: &Draft, total_memory_mb: i32) -> Result<()> {
    let record = build_record(version_id, draft, total_memory_mb, validate_jdk_path)?;
    let connection = open(db)?;
    match record {
        Some(record) => upsert(&connection, &record),
        None => delete(&connection, version_id),
    }
}

fn open(db: &Path) -> Result<Connection> {
    ensure!(db.is_file(), "启动器数据文件不存在");
    let connection = Connection::open_with_flags(
        db,
        OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    // Slint, ui-service and Compose may hold the file at the same time.
    connection.busy_timeout(Duration::from_secs(5))?;
    let application_id: i64 = connection.pragma_query_value(None, "application_id", |r| r.get(0))?;
    ensure!(application_id == APPLICATION_ID, "启动器数据文件不属于RDI");
    let version: i64 = connection.pragma_query_value(None, "user_version", |r| r.get(0))?;
    ensure!(version == SCHEMA_VERSION, "启动器数据版本不兼容，请更新启动器");
    Ok(connection)
}

fn find(connection: &Connection, version_id: &str) -> Result<Option<Record>> {
    Ok(connection
        .query_row(
            "SELECT version_id, java_path, max_memory_mb, jdwp_enabled, jdwp_param, \
             custom_jvm_params, forgeguard_disabled \
             FROM modpack_launch_options WHERE version_id = ?1",
            [version_id],
            |row| {
                Ok(Record {
                    version_id: row.get(0)?,
                    java_path: row.get(1)?,
                    max_memory_mb: row.get::<_, Option<i64>>(2)?.map(|m| m as i32),
                    jdwp_enabled: row.get::<_, i64>(3)? != 0,
                    jdwp_param: row.get(4)?,
                    custom_jvm_params: row.get(5)?,
                    forgeguard_disabled: row.get::<_, i64>(6)? != 0,
                })
            },
        )
        .optional()?)
}

/// The same statement as `ModpackLaunchOptions.sq` `upsert`.
fn upsert(connection: &Connection, r: &Record) -> Result<()> {
    connection.execute(
        "INSERT OR REPLACE INTO modpack_launch_options(version_id, java_path, max_memory_mb, \
         jdwp_enabled, jdwp_param, custom_jvm_params, forgeguard_disabled) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            r.version_id,
            r.java_path,
            r.max_memory_mb.map(i64::from),
            i64::from(r.jdwp_enabled),
            r.jdwp_param,
            r.custom_jvm_params,
            i64::from(r.forgeguard_disabled),
        ],
    )?;
    Ok(())
}

fn delete(connection: &Connection, version_id: &str) -> Result<()> {
    connection.execute("DELETE FROM modpack_launch_options WHERE version_id = ?1", [version_id])?;
    Ok(())
}

/// Kotlin `ModpackOptionViewModel.buildRecord`; `None` means every value is the default.
fn build_record(
    version_id: &str,
    draft: &Draft,
    total_memory_mb: i32,
    validate_jdk: impl Fn(&str) -> Result<String>,
) -> Result<Option<Record>> {
    let java_path = if draft.java_custom_enabled {
        Some(validate_jdk(kt_trim(&draft.java_path))?)
    } else {
        None
    };
    let max_memory_mb = if draft.memory_custom_enabled {
        Some(validate_max_memory(&draft.max_memory_text, total_memory_mb)?)
    } else {
        None
    };
    let jdwp_param = Some(kt_trim(&draft.jdwp_param)).filter(|p| !is_blank(p));
    if draft.jdwp_enabled {
        let Some(param) = jdwp_param else {
            bail!("请输入JDWP参数");
        };
        ensure!(!param.contains(['\n', '\r']), "JDWP参数不能包含换行");
        ensure!(!param.starts_with("-agentlib:jdwp="), "JDWP参数只填写等号右侧内容");
    }
    let jdwp_override = draft.jdwp_enabled || jdwp_param.is_some_and(|p| p != DEFAULT_JDWP_PARAM);
    let custom_jvm_params = parse_jvm_params(&draft.custom_jvm_params)?.join("\n");
    if java_path.is_none()
        && max_memory_mb.is_none()
        && !jdwp_override
        && custom_jvm_params.is_empty()
        && !draft.forgeguard_disabled
    {
        return Ok(None);
    }
    Ok(Some(Record {
        version_id: version_id.into(),
        java_path,
        max_memory_mb,
        jdwp_enabled: draft.jdwp_enabled,
        jdwp_param: jdwp_param.map(str::to_owned),
        custom_jvm_params,
        forgeguard_disabled: draft.forgeguard_disabled,
    }))
}

/// Kotlin `SettingsService.validateMemory` followed by `SettingsModpackOptionRuntime.validateMaxMemory`.
fn validate_max_memory(text: &str, total_memory_mb: i32) -> Result<i32> {
    let trimmed = kt_trim(text);
    if !is_blank(text) {
        let value: i32 = trimmed.parse().ok().context("最大内存格式不正确")?;
        if value != 0 {
            ensure!(value > 4096, "最大内存必须大于4096MB");
            ensure!(
                !(1..=value).contains(&total_memory_mb),
                "最大内存必须小于总内存 {total_memory_mb}MB"
            );
        }
    }
    trimmed
        .parse::<i32>()
        .ok()
        .filter(|v| *v > 4096)
        .context("自定义最大内存必须大于4096MB")
}

/// Kotlin `parseModpackJvmParams`: one argument per line.
fn parse_jvm_params(raw: &str) -> Result<Vec<String>> {
    let normalized = raw.replace("\r\n", "\n").replace('\r', "\n");
    let arguments: Vec<String> = normalized
        .split('\n')
        .map(kt_trim)
        .filter(|a| !a.is_empty())
        .map(str::to_owned)
        .collect();
    for argument in &arguments {
        ensure!(argument.starts_with('-'), "自定义JVM参数必须以-开头：{argument}");
        ensure!(
            !RESERVED_JVM_ARGUMENTS.iter().any(|r| argument.starts_with(r)),
            "该JVM参数由RDI设置管理：{argument}"
        );
        ensure!(
            !matches!(argument.as_str(), "-cp" | "-classpath" | "-jar"),
            "xtra jvm param不能修改游戏启动结构：{argument}"
        );
    }
    Ok(arguments)
}

/// Kotlin `validateJdkExecutablePath`: a full JDK of this machine's architecture.
fn validate_jdk_path(raw: &str) -> Result<String> {
    ensure!(!is_blank(raw), "请输入JDK路径");
    let java = resolve_java_executable(raw).with_context(|| format!("JDK路径无效: {raw}"))?;
    let java_name = java.file_name().and_then(|n| n.to_str()).unwrap_or_default();
    let javac_name = if java_name.eq_ignore_ascii_case("java.exe") { "javac.exe" } else { "javac" };
    ensure!(
        java.with_file_name(javac_name).is_file(),
        "选择的目录不是完整JDK，缺少{javac_name}"
    );
    let mut command = std::process::Command::new(&java);
    command.args(["-XshowSettings:properties", "-version"]);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // CREATE_NO_WINDOW: no console flashes over the launcher.
        command.creation_flags(0x0800_0000);
    }
    let output = command.output()?;
    if !output.status.success() {
        let code = output.status.code().map_or_else(|| "未知".into(), |c| c.to_string());
        bail!("JDK无法运行java -version，退出代码{code}");
    }
    let text = String::from_utf8_lossy(&output.stderr) + String::from_utf8_lossy(&output.stdout);
    if let Some(message) = arch_mismatch(&text, host_java_arch(), cfg!(target_os = "macos")) {
        bail!("{message}");
    }
    Ok(normalize(&std::path::absolute(&java)?).to_string_lossy().into_owned())
}

/// Kotlin `resolveJavaExecutable`.
fn resolve_java_executable(raw: &str) -> Option<PathBuf> {
    let windows = cfg!(windows);
    let java_name = if windows { "java.exe" } else { "java" };
    let input = PathBuf::from(kt_trim(raw));
    if input.is_dir() {
        // A Mac JDK bundle keeps its home in Contents/Home; the player may pick the .jdk bundle or Contents.
        let homes = if cfg!(target_os = "macos") {
            vec![input.clone(), input.join("Contents/Home"), input.join("Home")]
        } else {
            vec![input]
        };
        return homes.into_iter().map(|h| h.join("bin").join(java_name)).find(|j| j.is_file());
    }
    if input.exists() {
        let name = input.file_name()?.to_string_lossy().to_lowercase();
        if name == java_name {
            return Some(input);
        }
        if windows && name == "javaw.exe" {
            let java = input.with_file_name("java.exe");
            return Some(if java.exists() { java } else { input });
        }
        return None;
    }
    if !windows {
        return None;
    }
    Some(PathBuf::from(format!("{}.exe", kt_trim(raw)))).filter(|exe| exe.exists())
}

/// Kotlin `jdkArchMismatchMessage`; `host` uses Java `os.arch` names.
fn arch_mismatch(settings: &str, host: &str, mac: bool) -> Option<String> {
    let jdk = settings.lines().find_map(|line| {
        line.trim_start().strip_prefix("os.arch = ")?.split_whitespace().next()
    })?;
    if normalize_arch(jdk) == normalize_arch(host) || host.is_empty() {
        return None;
    }
    Some(if mac {
        format!("所选JDK是{jdk}版，请选择arm64版（Apple Silicon）JDK")
    } else {
        format!("所选JDK是{jdk}版，与本机架构{host}不一致")
    })
}

fn normalize_arch(arch: &str) -> String {
    match arch.to_lowercase().as_str() {
        "amd64" | "x86_64" => "x64".into(),
        "aarch64" | "arm64" => "arm64".into(),
        other => other.into(),
    }
}

/// What a Java process reports as `os.arch` on this machine.
fn host_java_arch() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64" if cfg!(target_os = "macos") => "x86_64",
        "x86_64" => "amd64",
        other => other,
    }
}

/// `Path.normalize()`: removes `.` and `..` without touching the file system.
fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if !out.pop() {
                    out.push(component);
                }
            }
            other => out.push(other),
        }
    }
    out
}

/// Kotlin `OperatingSystemMXBean.totalPhysicalMemorySize` in MiB; 0, as in Kotlin, when unknown.
fn total_memory_mb() -> i32 {
    match sysinfo::System::new() {
        Ok(mut system) => {
            system.refresh_memory();
            (system.total_memory() / (1024 * 1024)) as i32
        }
        Err(error) => {
            tracing::warn!(%error, "读取物理内存大小失败");
            0
        }
    }
}

/// Kotlin `String.trim()` removes Unicode whitespace, like Rust `trim`.
fn kt_trim(text: &str) -> &str {
    text.trim()
}
fn is_blank(text: &str) -> bool {
    text.trim().is_empty()
}

/// Kotlin `ModpackLaunchSnapshot`: what a launch takes from the saved options.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Snapshot {
    pub java_path: Option<String>,
    pub max_memory_mb: Option<i32>,
    pub jdwp_jvm_arg: Option<String>,
    pub custom_jvm_args: Vec<String>,
    pub forgeguard_disabled: bool,
}

/// Kotlin `ModpackLaunchOptionsService.loadSnapshot`. Blocking. Rust never creates `data.db`, so
/// a missing file means no saved options, as for a pack that never had any.
pub fn snapshot(db: &Path, version_id: &str) -> Result<Snapshot> {
    if !db.exists() {
        tracing::info!(version_id, "启动器数据文件不存在，使用默认启动设置");
        return Ok(Snapshot::default());
    }
    let Some(record) = find(&open(db)?, version_id)? else {
        return Ok(Snapshot::default());
    };
    snapshot_of(record)
}

fn snapshot_of(record: Record) -> Result<Snapshot> {
    ensure!(record.java_path.as_deref().is_none_or(|j| !is_blank(j)), "整合包自定义JDK路径为空");
    ensure!(record.max_memory_mb.is_none_or(|m| m > 4096), "整合包自定义最大内存无效");
    let jdwp_jvm_arg = if record.jdwp_enabled {
        let param = record.jdwp_param.as_deref().map(kt_trim).unwrap_or("");
        ensure!(!param.is_empty(), "整合包JDWP参数为空");
        ensure!(!param.contains('\n') && !param.contains('\r'), "整合包JDWP参数包含换行");
        ensure!(!param.starts_with("-agentlib:jdwp="), "整合包JDWP参数格式无效");
        Some(format!("-agentlib:jdwp={param}"))
    } else {
        None
    };
    Ok(Snapshot {
        java_path: record.java_path,
        max_memory_mb: record.max_memory_mb,
        jdwp_jvm_arg,
        custom_jvm_args: parse_jvm_params(&record.custom_jvm_params)?,
        forgeguard_disabled: record.forgeguard_disabled,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn cases() -> Value {
        serde_json::from_str(include_str!("../protocol/fixtures/pack_options_cases.json")).unwrap()
    }
    fn draft(value: &Value) -> Draft {
        let text = |key: &str, default: &str| value[key].as_str().unwrap_or(default).to_owned();
        Draft {
            java_custom_enabled: value["javaCustomEnabled"] == true,
            java_path: text("javaPath", ""),
            memory_custom_enabled: value["memoryCustomEnabled"] == true,
            max_memory_text: text("maxMemoryText", ""),
            jdwp_enabled: value["jdwpEnabled"] == true,
            jdwp_param: text("jdwpParam", DEFAULT_JDWP_PARAM),
            custom_jvm_params: text("customJvmParams", ""),
            forgeguard_disabled: value["forgeguardDisabled"] == true,
        }
    }
    fn temp_db() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rdi-j2-options-{}-{}",
            std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let db = dir.join("data.db");
        std::fs::copy(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/data_v5.db"),
            &db,
        )
        .unwrap();
        db
    }

    #[test]
    fn shared_cases_give_the_same_records_and_messages_as_kotlin() {
        let cases = cases();
        for case in cases["records"].as_array().unwrap() {
            let name = case["name"].as_str().unwrap();
            let result = build_record("v1", &draft(&case["draft"]), 16384, |raw| Ok(raw.to_owned()));
            match (&case["error"], result) {
                (Value::String(message), Err(error)) => assert_eq!(&error.to_string(), message, "{name}"),
                (Value::String(message), Ok(record)) => panic!("{name}: expected {message}, got {record:?}"),
                (_, Err(error)) => panic!("{name}: {error}"),
                (_, Ok(None)) => assert!(case["record"].is_null(), "{name}"),
                (_, Ok(Some(record))) => {
                    let expected = &case["record"];
                    assert_eq!(record.java_path.as_deref(), expected["javaPath"].as_str(), "{name}");
                    assert_eq!(
                        record.max_memory_mb.map(i64::from),
                        expected["maxMemoryMb"].as_i64(),
                        "{name}"
                    );
                    assert_eq!(record.jdwp_enabled, expected["jdwpEnabled"] == true, "{name}");
                    assert_eq!(record.jdwp_param.as_deref(), expected["jdwpParam"].as_str(), "{name}");
                    assert_eq!(record.custom_jvm_params, expected["customJvmParams"].as_str().unwrap(), "{name}");
                    assert_eq!(record.forgeguard_disabled, expected["forgeguardDisabled"] == true, "{name}");
                }
            }
        }
        for case in cases["memory"].as_array().unwrap() {
            let result = validate_max_memory(case["text"].as_str().unwrap(), case["total"].as_i64().unwrap() as i32);
            match case["error"].as_str() {
                Some(message) => assert_eq!(result.unwrap_err().to_string(), message, "{case}"),
                None => assert_eq!(i64::from(result.unwrap()), case["value"].as_i64().unwrap(), "{case}"),
            }
        }
    }

    #[test]
    fn kotlin_written_rows_are_read_and_rust_writes_round_trip() {
        let db = temp_db();
        let loaded = load(&db, "fixture_custom").unwrap();
        assert_eq!(
            loaded.draft,
            Draft {
                java_custom_enabled: true,
                java_path: "C:\\Java\\jdk-21\\bin\\java.exe".into(),
                memory_custom_enabled: true,
                max_memory_text: "8192".into(),
                jdwp_enabled: true,
                jdwp_param: "transport=dt_socket,server=y,suspend=n,address=*:5006".into(),
                custom_jvm_params: "-Dexample=true\n-XX:+UseG1GC".into(),
                forgeguard_disabled: true,
            }
        );
        assert_eq!(load(&db, "fixture_blank_jdwp").unwrap().draft.jdwp_param, DEFAULT_JDWP_PARAM);
        assert_eq!(load(&db, "absent").unwrap().draft, Draft::default());

        let custom = Draft { forgeguard_disabled: true, custom_jvm_params: " -Da=1 \r\n\r\n-Db=2".into(), ..Draft::default() };
        save(&db, "rust", &custom, 16384).unwrap();
        let connection = open(&db).unwrap();
        let record = find(&connection, "rust").unwrap().unwrap();
        assert_eq!(record.custom_jvm_params, "-Da=1\n-Db=2");
        assert_eq!(record.jdwp_param.as_deref(), Some(DEFAULT_JDWP_PARAM));
        save(&db, "rust", &Draft::default(), 16384).unwrap();
        assert_eq!(find(&connection, "rust").unwrap(), None);
    }

    #[test]
    fn foreign_missing_or_newer_databases_are_refused_and_left_alone() {
        let db = temp_db();
        let missing = db.with_file_name("absent.db");
        assert_eq!(load(&missing, "v").err().unwrap().to_string(), "启动器数据文件不存在");
        assert!(!missing.exists());
        let connection = Connection::open(&db).unwrap();
        connection.pragma_update(None, "user_version", 6).unwrap();
        assert_eq!(load(&db, "v").err().unwrap().to_string(), "启动器数据版本不兼容，请更新启动器");
        connection.pragma_update(None, "user_version", 5).unwrap();
        connection.pragma_update(None, "application_id", 1).unwrap();
        assert_eq!(load(&db, "v").err().unwrap().to_string(), "启动器数据文件不属于RDI");
    }

    #[test]
    fn jdk_checks_match_kotlin() {
        assert_eq!(validate_jdk_path("  ").unwrap_err().to_string(), "请输入JDK路径");
        assert!(validate_jdk_path("Z:/no/such/jdk").unwrap_err().to_string().starts_with("JDK路径无效: "));
        let settings = "Property settings:\n    os.arch = amd64\n    os.name = Windows 11\n";
        assert_eq!(arch_mismatch(settings, "x86_64", false), None);
        assert_eq!(
            arch_mismatch(settings, "aarch64", false).as_deref(),
            Some("所选JDK是amd64版，与本机架构aarch64不一致")
        );
        assert_eq!(
            arch_mismatch(settings, "aarch64", true).as_deref(),
            Some("所选JDK是amd64版，请选择arm64版（Apple Silicon）JDK")
        );
        assert_eq!(arch_mismatch("no arch here", "aarch64", false), None);
        assert_eq!(normalize(Path::new("/a/b/../c/./java")), PathBuf::from("/a/c/java"));
    }

    #[test]
    fn launch_snapshots_follow_load_snapshot() {
        let record = |java: Option<&str>, memory: Option<i32>, jdwp: bool, param: Option<&str>, jvm: &str| Record {
            version_id: "v".into(),
            java_path: java.map(str::to_owned),
            max_memory_mb: memory,
            jdwp_enabled: jdwp,
            jdwp_param: param.map(str::to_owned),
            custom_jvm_params: jvm.into(),
            forgeguard_disabled: false,
        };
        let s = snapshot_of(record(Some("C:/jdk/bin/java.exe"), Some(8192), true, Some(" transport=dt_socket "), "-Da=1\r\n\n-Db=2")).unwrap();
        assert_eq!(s.jdwp_jvm_arg.as_deref(), Some("-agentlib:jdwp=transport=dt_socket"));
        assert_eq!(s.custom_jvm_args, ["-Da=1", "-Db=2"]);
        assert!(snapshot_of(record(Some(" "), None, false, None, "")).is_err());
        assert!(snapshot_of(record(None, Some(4096), false, None, "")).is_err());
        assert!(snapshot_of(record(None, None, true, Some("-agentlib:jdwp=x"), "")).is_err());
        assert!(snapshot_of(record(None, None, true, Some(""), "")).is_err());
        assert!(snapshot_of(record(None, None, false, None, "x")).is_err());
        assert_eq!(snapshot(Path::new("missing/data.db"), "v").unwrap(), Snapshot::default());
    }
}
