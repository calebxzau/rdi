//! J2: the installed pack list, read from `mc/versions` like Kotlin `ModpackService.getLocalPackDirs`.
use crate::api::Client;
use anyhow::{Context, Result};
use serde::Deserialize;
use serde_json::json;
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    time::UNIX_EPOCH,
};

/// Shown when `POST /modpack/infos` fails (J2 D3); local-only packs still show.
pub const INFO_ERROR: &str = "读取整合包信息失败";
const LOCAL_PREFIX: &str = "local_";
const INSTALLING_SUFFIX: &str = ".installing";
const LOCAL_INFO_FILE: &str = "rdi-local-pack.json";

#[derive(Clone, Debug, PartialEq)]
pub struct InstalledPack {
    /// The version directory name, which Kotlin calls `versionId`.
    pub id: String,
    pub name: String,
    pub icon: Option<String>,
    /// Remote packs: the directory suffix. Local packs: the Minecraft version, as Kotlin shows it.
    pub version: String,
    pub local: bool,
    pub directory: PathBuf,
    created_at: i64,
}

impl InstalledPack {
    pub(crate) fn install_time(&self) -> Option<chrono::DateTime<chrono::Local>> {
        chrono::DateTime::from_timestamp_millis(self.created_at)
            .filter(|_| self.created_at > 0)
            .map(|time| time.with_timezone(&chrono::Local))
    }
}

#[derive(Debug, Default)]
pub struct PackList {
    pub packs: Vec<InstalledPack>,
    pub error: String,
}

/// `rdi-local-pack.json`, written by Kotlin `LocalModpackStore`; unknown fields are ignored.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalInfo {
    name: String,
    icon_url: Option<String>,
    mc_version: String,
    created_at: i64,
}
/// The fields of Kotlin `Modpack.BriefVo` this list needs.
#[derive(Deserialize)]
struct Brief {
    id: String,
    name: String,
    icon: Option<String>,
}
#[derive(Debug, PartialEq)]
struct RemoteRef {
    version_id: String,
    modpack_id: String,
    ver_name: String,
    directory: PathBuf,
    created_at: i64,
}
#[derive(Debug, Default)]
struct Scan {
    remote: Vec<RemoteRef>,
    local: Vec<InstalledPack>,
}

pub fn versions_dir() -> PathBuf {
    let dir = crate::paths::data_dir().join("mc").join("versions");
    std::path::absolute(&dir).unwrap_or(dir)
}

pub async fn load(api: &Client) -> PackList {
    let root = versions_dir();
    let scan = match tokio::task::spawn_blocking(move || scan(&root)).await {
        Ok(Ok(scan)) => scan,
        Ok(Err(error)) => {
            tracing::warn!(error = %format!("{error:#}"), "读取已安装整合包失败");
            return PackList { packs: vec![], error: format!("{error}") };
        }
        Err(error) => {
            tracing::error!(%error, "读取已安装整合包的任务失败");
            return PackList { packs: vec![], error: "读取整合包失败".into() };
        }
    };
    let mut ids: Vec<&str> = scan.remote.iter().map(|r| r.modpack_id.as_str()).collect();
    ids.sort_unstable();
    ids.dedup();
    let briefs = if ids.is_empty() {
        Ok(vec![])
    } else {
        api.post::<Vec<Brief>>("/modpack/infos", &json!(ids)).await
    };
    merge(scan, briefs)
}

fn merge(scan: Scan, briefs: Result<Vec<Brief>>) -> PackList {
    let mut error = String::new();
    let briefs: HashMap<String, Brief> = match briefs {
        Ok(briefs) => briefs.into_iter().map(|b| (b.id.to_ascii_lowercase(), b)).collect(),
        Err(cause) => {
            tracing::warn!(error = %format!("{cause:#}"), "读取已安装的RDI整合包信息失败");
            error = INFO_ERROR.into();
            HashMap::new()
        }
    };
    let mut packs: Vec<InstalledPack> = scan
        .remote
        .into_iter()
        .filter_map(|r| {
            let Some(brief) = briefs.get(&r.modpack_id) else {
                if error.is_empty() {
                    tracing::warn!(dir = %r.version_id, "本地整合包在服务器不存在，将跳过该目录");
                }
                return None;
            };
            Some(InstalledPack {
                id: r.version_id,
                name: brief.name.clone(),
                icon: brief.icon.clone(),
                version: r.ver_name,
                local: false,
                directory: r.directory,
                created_at: r.created_at,
            })
        })
        .chain(scan.local)
        .collect();
    // Stable, newest first, as Kotlin `sortedByDescending(ModpackLocalDir::createTime)`.
    packs.sort_by_key(|p| std::cmp::Reverse(p.created_at));
    PackList { packs, error }
}

fn scan(root: &Path) -> Result<Scan> {
    let entries = match std::fs::read_dir(root) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Scan::default()),
        Err(e) => return Err(e).context("读取整合包目录失败"),
    };
    let mut scan = Scan::default();
    let mut local_dirs = vec![];
    for entry in entries {
        let entry = entry.context("读取整合包目录失败")?;
        if !entry.file_type().is_ok_and(|t| t.is_dir()) {
            continue;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let path = entry.path();
        if let Some((modpack_id, ver_name)) = remote_name(&name) {
            scan.remote.push(RemoteRef {
                created_at: created_at(&path),
                modpack_id,
                ver_name,
                version_id: name,
                directory: path,
            });
        } else if name.starts_with(LOCAL_PREFIX) && !name.ends_with(INSTALLING_SUFFIX) {
            local_dirs.push((name, path));
        }
    }
    // Kotlin `LocalModpackStore.scan` sorts by name before reading.
    local_dirs.sort();
    for (name, path) in local_dirs {
        match read_local(&path) {
            Ok(info) => match mc_version_name(&info.mc_version) {
                Some(version) => scan.local.push(InstalledPack {
                    id: name,
                    name: info.name,
                    icon: info.icon_url,
                    version: version.into(),
                    local: true,
                    directory: path,
                    created_at: info.created_at,
                }),
                None => tracing::warn!(dir = %name, version = %info.mc_version, "本地整合包的游戏版本未知，将跳过该目录"),
            },
            Err(error) => tracing::warn!(dir = %name, error = %format!("{error:#}"), "读取本地整合包失败，将跳过该目录"),
        }
    }
    Ok(scan)
}

/// `^([0-9a-fA-F]{24})_(.+)$`, with the id lowercased as Kotlin `ObjectId.toHexString` does.
pub(crate) fn remote_name(name: &str) -> Option<(String, String)> {
    let (id, rest) = (name.get(..24)?, name.get(24..)?);
    let ver = rest.strip_prefix('_').filter(|v| !v.is_empty())?;
    id.bytes()
        .all(|b| b.is_ascii_hexdigit())
        .then(|| (id.to_ascii_lowercase(), ver.to_owned()))
}

fn read_local(dir: &Path) -> Result<LocalInfo> {
    let file = dir.join(LOCAL_INFO_FILE);
    let text = std::fs::read_to_string(&file)
        .with_context(|| format!("缺少本地整合包信息: {}", file.display()))?;
    serde_json::from_str(&text).context("本地整合包信息格式无效")
}

/// Kotlin `McVersion` entry names and their `mcVer`.
pub(crate) fn mc_version_name(entry: &str) -> Option<&'static str> {
    match entry {
        "V211" => Some("1.21.1"),
        "V201" => Some("1.20.1"),
        "V071" => Some("1.7.10"),
        _ => None,
    }
}

/// Kotlin `localPackCreateTime`: creation time, else last modified, in milliseconds.
fn created_at(path: &Path) -> i64 {
    let Ok(meta) = std::fs::metadata(path) else {
        return 0;
    };
    meta.created()
        .or_else(|_| meta.modified())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or(0, |d| d.as_millis() as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID: &str = "68b314bbadaf52ddab96b5ed";

    fn temp() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rdi-j2-packs-{}-{}",
            std::process::id(),
            std::time::SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn remote_names_follow_the_kotlin_pattern() {
        assert_eq!(remote_name(&format!("{ID}_1.0")), Some((ID.into(), "1.0".into())));
        assert_eq!(
            remote_name("68B314BBADAF52DDAB96B5ED_v 2_beta"),
            Some((ID.into(), "v 2_beta".into()))
        );
        assert_eq!(remote_name(&format!("{ID}_")), None);
        assert_eq!(remote_name(&format!("{ID}-1.0")), None);
        assert_eq!(remote_name("68b314bbadaf52ddab96b5eg_1.0"), None);
        assert_eq!(remote_name("1.21.1"), None);
        assert_eq!(remote_name("local_019a55b0"), None);
    }

    #[test]
    fn kotlin_written_local_pack_info_is_read() {
        let info: LocalInfo =
            serde_json::from_str(include_str!("../protocol/fixtures/rdi-local-pack.json")).unwrap();
        assert_eq!(info.name, "测试本地整合包");
        assert_eq!(info.icon_url.as_deref(), Some("https://example.com/icon.png"));
        assert_eq!(mc_version_name(&info.mc_version), Some("1.21.1"));
        assert_eq!(info.created_at, 1762732800000);
    }

    #[test]
    fn scan_keeps_rdi_and_local_packs_and_skips_the_rest() {
        let root = temp();
        let fixture = include_str!("../protocol/fixtures/rdi-local-pack.json");
        for dir in [
            format!("{ID}_1.0"),
            "local_good".into(),
            "local_good.installing".into(),
            "local_broken".into(),
            "local_unknown_version".into(),
            "1.21.1".into(),
        ] {
            std::fs::create_dir_all(root.join(dir)).unwrap();
        }
        std::fs::write(root.join("local_good").join(LOCAL_INFO_FILE), fixture).unwrap();
        std::fs::write(root.join("local_good.installing").join(LOCAL_INFO_FILE), fixture).unwrap();
        std::fs::write(root.join("local_broken").join(LOCAL_INFO_FILE), "{").unwrap();
        std::fs::write(
            root.join("local_unknown_version").join(LOCAL_INFO_FILE),
            fixture.replace("V211", "V999"),
        )
        .unwrap();
        std::fs::write(root.join(format!("{ID}_file")), "").unwrap();

        let scan = scan(&root).unwrap();
        assert_eq!(scan.remote.len(), 1);
        assert_eq!(scan.remote[0].modpack_id, ID);
        assert_eq!(scan.remote[0].ver_name, "1.0");
        assert_eq!(scan.local.len(), 1);
        assert_eq!(scan.local[0].id, "local_good");
        assert_eq!(scan.local[0].version, "1.21.1");
        assert!(scan.local[0].local);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_missing_versions_directory_is_an_empty_list() {
        let scan = scan(&temp().join("absent")).unwrap();
        assert!(scan.remote.is_empty() && scan.local.is_empty());
    }

    fn sample() -> Scan {
        let remote = |id: &str, ver: &str, created_at| RemoteRef {
            version_id: format!("{id}_{ver}"),
            modpack_id: id.into(),
            ver_name: ver.into(),
            directory: PathBuf::from(format!("{id}_{ver}")),
            created_at,
        };
        Scan {
            remote: vec![remote(ID, "1.0", 10), remote("aaaaaaaaaaaaaaaaaaaaaaaa", "2.0", 30)],
            local: vec![InstalledPack {
                id: "local_a".into(),
                name: "本地".into(),
                icon: None,
                version: "1.20.1".into(),
                local: true,
                directory: PathBuf::from("local_a"),
                created_at: 20,
            }],
        }
    }

    #[test]
    fn merge_sorts_newest_first_and_skips_packs_the_server_does_not_know() {
        let list = merge(
            sample(),
            Ok(vec![Brief { id: ID.to_uppercase(), name: "测试包".into(), icon: Some("i".into()) }]),
        );
        assert_eq!(list.error, "");
        let ids: Vec<&str> = list.packs.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["local_a", "68b314bbadaf52ddab96b5ed_1.0"]);
        assert_eq!(list.packs[1].name, "测试包");
        assert_eq!(list.packs[1].version, "1.0");
        assert!(!list.packs[1].local);
    }

    #[test]
    fn a_failed_info_request_keeps_local_packs_and_reports_it() {
        let list = merge(sample(), Err(anyhow::anyhow!("无法连接服务器")));
        assert_eq!(list.error, INFO_ERROR);
        assert_eq!(list.packs.len(), 1);
        assert!(list.packs[0].local);
    }
}
