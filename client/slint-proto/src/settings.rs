//! U4 batch b2: 设置 (Compose SettingScreen, SettingsViewModel, SettingsService, AppConfig,
//! DownloadCacheCleanupService, `getInvalidLocalPackDirs`). No Slint types.
use crate::{
    api::Client,
    digests::{self, Algorithm, Digest},
    tasks::Context,
};
use anyhow::{Context as _, Result, ensure};
use serde::Deserialize;
use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
};

pub fn config_path() -> PathBuf {
    crate::paths::data_dir().join("config.toml")
}

/// Kotlin `SettingsDraft`, with `AppConfig.toSettingsDraft` defaults.
#[derive(Clone, Debug, PartialEq)]
pub struct Draft {
    pub prefer_mod_mirror: bool,
    pub prefer_mc_mirror: bool,
    pub max_memory_text: String,
    pub proxy_enabled: bool,
    pub proxy_system: bool,
    pub proxy_host: String,
    pub proxy_port_text: String,
    pub proxy_usr: String,
    pub proxy_pwd: String,
    pub solid_window: bool,
}

#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct Proxy {
    enabled: Option<bool>,
    system_proxy: Option<bool>,
    host: Option<String>,
    port: Option<i64>,
    usr: Option<String>,
    pwd: Option<String>,
}

/// Kotlin `AppConfig`; absent keys take its defaults.
#[derive(Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct AppConfig {
    prefer_mod_mirror: bool,
    prefer_mc_mirror: bool,
    max_memory: i64,
    proxy_config: Option<Proxy>,
    pinyin_name: bool,
    solid_window: bool,
}
impl Default for AppConfig {
    fn default() -> Self {
        Self { prefer_mod_mirror: true, prefer_mc_mirror: true, max_memory: 0, proxy_config: None, pinyin_name: false, solid_window: false }
    }
}

/// ktoml writes absent values as bare `null`, which TOML does not allow.
fn parse(text: &str) -> Result<AppConfig> {
    let text: String = text
        .lines()
        .filter(|l| !l.split_once('=').is_some_and(|(_, v)| v.trim() == "null"))
        .collect::<Vec<_>>()
        .join("\n");
    Ok(toml::from_str(&text)?)
}

fn read(path: &Path) -> Result<AppConfig> {
    match std::fs::read_to_string(path) {
        Ok(text) => parse(&text).context("设置文件格式无效"),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(AppConfig::default()),
        Err(e) => Err(e).context("读取设置失败"),
    }
}

/// Blocking. Kotlin replaces an unreadable file with the defaults; this shows the defaults and
/// leaves the file alone until the player saves.
pub fn load(path: &Path) -> Draft {
    let config = read(path).unwrap_or_else(|error| {
        tracing::warn!(error = %format!("{error:#}"), "读取设置失败，使用默认值");
        AppConfig::default()
    });
    let proxy = config.proxy_config.unwrap_or_default();
    Draft {
        prefer_mod_mirror: config.prefer_mod_mirror,
        prefer_mc_mirror: config.prefer_mc_mirror,
        max_memory_text: if config.max_memory > 0 { config.max_memory.to_string() } else { String::new() },
        proxy_enabled: proxy.enabled.unwrap_or(false),
        proxy_system: proxy.system_proxy.unwrap_or(false),
        proxy_host: proxy.host.unwrap_or_else(|| "127.0.0.1".into()),
        proxy_port_text: proxy.port.unwrap_or(10808).to_string(),
        proxy_usr: proxy.usr.unwrap_or_default(),
        proxy_pwd: proxy.pwd.unwrap_or_default(),
        solid_window: config.solid_window,
    }
}

/// The saved 实心窗口 choice; the window reads it once at start.
pub fn solid_window(path: &Path) -> bool {
    read(path).map(|c| c.solid_window).unwrap_or(false)
}

/// `SettingsService.validateMemory` then `validateProxyPort`.
pub fn validate(draft: &Draft, total_memory_mb: i32) -> Result<(), String> {
    let memory = draft.max_memory_text.trim();
    if !memory.is_empty() {
        let value: i64 = memory.parse().map_err(|_| "最大内存格式不正确".to_owned())?;
        if value != 0 {
            if value <= 4096 {
                return Err("最大内存必须大于4096MB".into());
            }
            if total_memory_mb > 0 && value >= i64::from(total_memory_mb) {
                return Err(format!("最大内存必须小于总内存 {total_memory_mb}MB"));
            }
        }
    }
    let port = draft.proxy_port_text.trim();
    if !port.is_empty() {
        let value: i64 = port.parse().map_err(|_| "代理端口格式不正确".to_owned())?;
        if !(1..=65535).contains(&value) {
            return Err("代理端口必须在1-65535之间".into());
        }
    }
    Ok(())
}

fn quoted(text: &str) -> String {
    toml::Value::String(text.to_owned()).to_string()
}

fn optional(text: &str) -> String {
    if text.trim().is_empty() { "null".into() } else { quoted(text) }
}

/// `SettingsService.saveSettings` written the way ktoml writes `AppConfig`, so Compose reads it.
fn render(draft: &Draft, pinyin_name: bool) -> String {
    let memory: i64 = draft.max_memory_text.trim().parse().unwrap_or(0);
    let port: i64 = draft.proxy_port_text.trim().parse().unwrap_or(10808);
    format!(
        "preferModMirror = {}\npreferMcMirror = {}\nmaxMemory = {memory}\npinyinName = {pinyin_name}\nsolidWindow = {}\n\n\
         [proxyConfig]\nenabled = {}\nsystemProxy = {}\nhost = {}\nport = {port}\nusr = {}\npwd = {}\n",
        draft.prefer_mod_mirror,
        draft.prefer_mc_mirror,
        draft.solid_window,
        draft.proxy_enabled,
        draft.proxy_system,
        quoted(&draft.proxy_host),
        optional(&draft.proxy_usr),
        optional(&draft.proxy_pwd),
    )
}

/// Blocking: keeps `pinyinName`, which the settings screen does not show.
pub fn save(path: &Path, draft: &Draft) -> Result<()> {
    let pinyin_name = read(path).map(|c| c.pinyin_name).unwrap_or(false);
    let parent = path.parent().context("设置目录无效")?;
    std::fs::create_dir_all(parent).context("创建设置目录失败")?;
    let temp = parent.join(format!(".config.toml.{}.tmp", std::process::id()));
    std::fs::write(&temp, render(draft, pinyin_name)).context("保存设置失败")?;
    std::fs::rename(&temp, path).inspect_err(|_| {
        let _ = std::fs::remove_file(&temp);
    }).context("保存设置失败")
}

/// Kotlin `DownloadQuota.Vo`.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Quota {
    pub limit_bytes: i64,
    pub remaining_bytes: i64,
}
impl Quota {
    pub fn text(&self) -> String {
        let size = |b: i64| crate::packs::human_file_size(b.max(0) as u64);
        format!("今日剩余{}/{}", size(self.remaining_bytes), size(self.limit_bytes))
    }
}

pub async fn quota(api: &Client) -> Result<Quota> {
    api.get("/download/quota").await.map_err(|e| anyhow::anyhow!("下载额度读取失败：{e:#}"))
}

/// `^([0-9a-fA-F]{24})_(.+)$`: an installed RDI pack directory, with its modpack id.
fn pack_id(name: &str) -> Option<&str> {
    let (id, version) = name.split_once('_')?;
    (id.len() == 24 && id.bytes().all(|b| b.is_ascii_hexdigit()) && !version.is_empty()).then_some(id)
}

/// An installed pack whose modpack the server no longer has.
#[derive(Clone, Debug)]
pub struct InvalidPack {
    pub dir: PathBuf,
    pub version_id: String,
}

/// `findInvalidLocalPackDirs`: asks `modpack/missing` in batches of 512, newest first.
pub async fn invalid_packs(api: &Client, versions: PathBuf) -> Result<Vec<InvalidPack>> {
    let mut refs: Vec<(PathBuf, String, String, std::time::SystemTime)> = tokio::task::spawn_blocking(move || {
        let Ok(entries) = std::fs::read_dir(&versions) else { return Vec::new() };
        entries
            .flatten()
            .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
            .filter_map(|e| {
                let name = e.file_name().to_string_lossy().into_owned();
                let id = pack_id(&name)?.to_ascii_lowercase();
                let meta = e.metadata().ok();
                let time = meta.and_then(|m| m.created().or_else(|_| m.modified()).ok()).unwrap_or(std::time::UNIX_EPOCH);
                Some((e.path(), name, id, time))
            })
            .collect()
    })
    .await?;
    if refs.is_empty() {
        return Ok(Vec::new());
    }
    let ids: Vec<String> = refs.iter().map(|r| r.2.clone()).collect::<HashSet<_>>().into_iter().collect();
    let mut missing = HashSet::new();
    for chunk in ids.chunks(512) {
        let found: Vec<String> = api.post("/modpack/missing", &serde_json::json!(chunk)).await.map_err(|e| anyhow::anyhow!("检查无效整合包失败：{e:#}"))?;
        missing.extend(found.into_iter().map(|id| id.to_ascii_lowercase()));
    }
    refs.retain(|r| missing.contains(&r.2));
    refs.sort_by_key(|r| std::cmp::Reverse(r.3));
    Ok(refs.into_iter().map(|(dir, version_id, _, _)| InvalidPack { dir, version_id }).collect())
}

/// Blocking `ModpackService.deleteLocalPack`: to the recycle bin, then its options.
pub fn trash_pack(pack: &InvalidPack) -> Result<()> {
    trash::delete(&pack.dir).map_err(|e| anyhow::anyhow!("移入回收站失败：{e}"))?;
    ensure!(!pack.dir.exists(), "无法确认整合包目录已移入回收站");
    crate::pack_options::forget(&crate::pack_options::database_path(), &pack.version_id)
}

/// One cache entry: `<value>.<sha1|sha256|murmur2>` directly in the cache directory.
fn cache_entry(name: &str) -> Option<Digest> {
    let (value, suffix) = name.rsplit_once('.')?;
    let algorithm = match suffix.to_ascii_lowercase().as_str() {
        "sha1" => Algorithm::Sha1,
        "sha256" => Algorithm::Sha256,
        "murmur2" => Algorithm::Murmur2,
        _ => return None,
    };
    if algorithm == Algorithm::Murmur2 && !value.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    Digest::new(algorithm, value).ok()
}

/// Blocking task body of `DownloadCacheCleanupService.cleanup`: removes cache entries that no
/// installed RDI pack's mods, resource packs or shader packs still use.
pub fn clean_cache(ctx: &Context, cache: &Path, versions: &Path) -> Result<String> {
    ctx.progress("正在读取下载缓存", Some(0.));
    let mut candidates: Vec<(PathBuf, Digest, u64)> = Vec::new();
    if let Ok(entries) = std::fs::read_dir(cache) {
        for entry in entries.flatten() {
            if !entry.file_type().is_ok_and(|t| t.is_file()) {
                continue;
            }
            if let Some(digest) = cache_entry(&entry.file_name().to_string_lossy()) {
                candidates.push((entry.path(), digest, entry.metadata().map(|m| m.len()).unwrap_or(0)));
            }
        }
    }
    let mut by_size: HashMap<u64, Vec<&Digest>> = HashMap::new();
    for (_, digest, size) in &candidates {
        by_size.entry(*size).or_default().push(digest);
    }
    let packs: Vec<PathBuf> = std::fs::read_dir(versions)
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()) && pack_id(&e.file_name().to_string_lossy()).is_some())
                .map(|e| e.path())
                .collect()
        })
        .unwrap_or_default();
    // Digests have no Hash; key them as `suffix:value`.
    let key = |d: &Digest| format!("{}:{}", d.algorithm.suffix(), d.value);
    let mut retained: HashSet<String> = HashSet::new();
    for (index, pack) in packs.iter().enumerate() {
        ctx.cancel.check()?;
        let name = pack.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        ctx.progress(format!("正在扫描本地整合包：{name}"), Some(0.1 + 0.4 * index as f32 / packs.len().max(1) as f32));
        for folder in ["mods", "resourcepacks", "shaderpacks"] {
            let mut stack = vec![pack.join(folder)];
            while let Some(dir) = stack.pop() {
                let Ok(entries) = std::fs::read_dir(&dir) else { continue };
                for entry in entries.flatten() {
                    ctx.cancel.check()?;
                    let Ok(kind) = entry.file_type() else { continue };
                    if kind.is_dir() {
                        stack.push(entry.path());
                        continue;
                    }
                    if !kind.is_file() {
                        continue;
                    }
                    let Some(same) = entry.metadata().ok().and_then(|m| by_size.get(&m.len())) else { continue };
                    let mut algorithms: Vec<Algorithm> = same.iter().map(|d| d.algorithm).collect();
                    algorithms.sort_by_key(|a| a.suffix());
                    algorithms.dedup();
                    for algorithm in algorithms {
                        let Ok(value) = digests::hash(&entry.path(), algorithm, &ctx.cancel) else { continue };
                        if let Some(d) = same.iter().find(|d| d.algorithm == algorithm && d.value == value) {
                            retained.insert(key(d));
                        }
                    }
                }
            }
        }
    }
    let (mut deleted, mut released, mut failed) = (0usize, 0u64, 0usize);
    let total = candidates.len().max(1);
    for (index, (path, digest, size)) in candidates.iter().enumerate() {
        ctx.cancel.check()?;
        if !retained.contains(&key(digest)) {
            // Downloads hold this lock while they write or verify the entry.
            let lock = crate::content_store::digest_lock(digest);
            let _guard = lock.blocking_lock();
            match std::fs::remove_file(path) {
                Ok(()) => {
                    deleted += 1;
                    released += size;
                }
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => {
                    tracing::warn!(%error, "删除下载缓存失败");
                    failed += 1;
                }
            }
        }
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        ctx.progress(format!("正在删除未使用下载缓存：{name}"), Some(0.5 + 0.5 * (index + 1) as f32 / total as f32));
    }
    ensure!(failed == 0, "清除下载缓存时有{failed}个文件删除失败");
    Ok(format!("已清除{deleted}个下载缓存，释放{}", crate::packs::human_file_size(released)))
}

/// The value as Kotlin `RServer.routeState` shows it next to the node buttons.
pub fn route_line(state: &crate::route::RouteState) -> String {
    let entry = if state.use_backup_node { "加速入口" } else { "主入口" };
    match &state.node_name {
        Some(name) => format!("当前节点 {name}  {entry}"),
        None => entry.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn draft() -> Draft {
        load(Path::new("does-not-exist/config.toml"))
    }
    #[test]
    fn defaults_and_kotlin_files() {
        let d = draft();
        assert!(d.prefer_mod_mirror && d.prefer_mc_mirror && !d.proxy_enabled && !d.solid_window);
        assert_eq!((d.proxy_host.as_str(), d.proxy_port_text.as_str(), d.max_memory_text.as_str()), ("127.0.0.1", "10808", ""));
        let text = "preferModMirror = false\npreferMcMirror = false\nmaxMemory = 6000\npinyinName = true\nsolidWindow = true\n\n[proxyConfig]\nenabled = true\nsystemProxy = false\nhost = \"10.0.0.1\"\nport = 7890\nusr = null\npwd = \"p\\\"w\"\n";
        let config = parse(text).unwrap();
        assert!(config.pinyin_name && config.solid_window);
        let p = config.proxy_config.unwrap();
        assert_eq!((p.host.as_deref(), p.port, p.usr, p.pwd.as_deref()), (Some("10.0.0.1"), Some(7890), None, Some("p\"w")));
    }
    #[test]
    fn rendered_files_round_trip() {
        let mut d = draft();
        d.max_memory_text = "6000".into();
        d.proxy_enabled = true;
        d.proxy_pwd = "p\"w".into();
        let text = render(&d, true);
        assert!(text.starts_with("preferModMirror = true\npreferMcMirror = true\nmaxMemory = 6000\npinyinName = true\nsolidWindow = false\n\n[proxyConfig]\n"));
        assert!(text.contains("usr = null\n"));
        let path = std::env::temp_dir().join(format!("rdi-settings-{}.toml", std::process::id()));
        std::fs::write(&path, &text).unwrap();
        assert_eq!(load(&path), d);
        std::fs::remove_file(&path).unwrap();
    }
    #[test]
    fn validation_follows_settings_service() {
        let mut d = draft();
        assert!(validate(&d, 16000).is_ok());
        d.max_memory_text = "abc".into();
        assert_eq!(validate(&d, 16000).unwrap_err(), "最大内存格式不正确");
        d.max_memory_text = "0".into();
        assert!(validate(&d, 16000).is_ok());
        d.max_memory_text = "4096".into();
        assert_eq!(validate(&d, 16000).unwrap_err(), "最大内存必须大于4096MB");
        d.max_memory_text = "16000".into();
        assert_eq!(validate(&d, 16000).unwrap_err(), "最大内存必须小于总内存 16000MB");
        d.max_memory_text = "8000".into();
        d.proxy_port_text = "70000".into();
        assert_eq!(validate(&d, 16000).unwrap_err(), "代理端口必须在1-65535之间");
        d.proxy_port_text = "x".into();
        assert_eq!(validate(&d, 16000).unwrap_err(), "代理端口格式不正确");
    }
    #[test]
    fn cache_names_and_pack_dirs() {
        assert_eq!(cache_entry(&format!("{}.sha1", "A".repeat(40))).unwrap().value, "a".repeat(40));
        assert!(cache_entry("123.murmur2").is_some());
        assert!(cache_entry("abc.murmur2").is_none() && cache_entry("x.jar").is_none() && cache_entry("12.sha1").is_none());
        assert_eq!(pack_id("6650a1b2c3d4e5f601234567_1.0"), Some("6650a1b2c3d4e5f601234567"));
        assert!(pack_id("local_0195").is_none() && pack_id("6650a1b2c3d4e5f601234567_").is_none());
        let q = Quota { limit_bytes: 2 * 1024 * 1024 * 1024, remaining_bytes: 1536 * 1024 * 1024 };
        assert_eq!(q.text(), "今日剩余1.5GB/2.0GB");
    }
}
