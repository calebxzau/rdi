//! J5b: the RDI core mod of a room game (`UpdateService.prepareMcCore`, `McCoreUpdater`).
//! The server names the current SHA-1; the fixed file in `mods` is replaced only when it differs,
//! through the shared content cache, with the same staging and checks as Kotlin.
use crate::api::server::ServerApi;
use crate::content_store::{Request, Store};
use crate::digests::{Algorithm, Digest};
use crate::download::{Progress, Source};
use crate::tasks::Cancellation;
use anyhow::{Context, Result, ensure};
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

/// `McCoreUpdater.slug`: `1.20.1-forge`, `1.21.1-neoforge`.
pub fn slug(mc_version: &str, loader: &str) -> String {
    format!("{mc_version}-{}", loader.to_lowercase())
}

pub fn file_name(slug: &str) -> String {
    format!("rdi-5-mc-client-{slug}.jar")
}

fn valid_sha1(value: &str) -> Option<String> {
    let value = value.trim();
    (value.len() == 40 && value.chars().all(|c| c.is_ascii_hexdigit())).then(|| value.to_lowercase())
}

/// One check per slug at a time; a second start of the same version waits for the first.
fn slug_lock(slug: &str) -> Arc<tokio::sync::Mutex<()>> {
    static LOCKS: OnceLock<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> = OnceLock::new();
    LOCKS.get_or_init(Default::default).lock().unwrap().entry(slug.to_owned()).or_default().clone()
}

/// Checks and, if needed, replaces `mods/rdi-5-mc-client-<slug>.jar` (`slug` from [`slug`]). `status` gets the Kotlin
/// status lines; returns whether the file was replaced.
pub async fn prepare(
    server: &ServerApi,
    store: &Store,
    mods_dir: &Path,
    slug: &str,
    cancel: &Cancellation,
    status: &(dyn Fn(String) + Sync),
    progress: &(dyn Fn(Progress) + Sync),
) -> Result<bool> {
    let lock = slug_lock(slug);
    let _guard = match lock.try_lock() {
        Ok(guard) => guard,
        Err(_) => {
            status("等待相同RDI核心检查...".into());
            lock.lock().await
        }
    };
    status("检查RDI核心版本...".into());
    let hash: String = server
        .get(&format!("/update/mc/{slug}/hash"))
        .await
        .with_context(|| format!("获取MC核心版本信息失败: {slug}"))?;
    let expected = valid_sha1(&hash).with_context(|| format!("获取MC核心版本信息失败: {slug}"))?;
    let name = file_name(slug);
    status(format!("准备下载{name}..."));
    let target = mods_dir.join(&name);
    if is_current(&target, &expected) {
        return Ok(false);
    }
    let url = server.url(&format!("/update/mc/{slug}"));
    let request = Request {
        id: format!("rdi-mc-core:{slug}"),
        relative_path: name.clone(),
        size: None,
        digests: vec![Digest::new(Algorithm::Sha1, &expected)?],
        sources: vec![Source { headers: server.auth_headers(&url)?, url }],
    };
    let fetched = store.fetch(&request, cancel, progress).await?;
    let (source, target_path) = (fetched.path.clone(), target.clone());
    let expected_sha1 = expected.clone();
    tokio::task::spawn_blocking(move || replace(&source, &target_path, &expected_sha1)).await??;
    drop(fetched);
    status(format!("{name}已同步"));
    Ok(true)
}

fn is_current(target: &Path, expected: &str) -> bool {
    std::fs::symlink_metadata(target).is_ok_and(|m| m.is_file())
        && crate::mc_install::sha1_file(target).is_ok_and(|actual| actual.eq_ignore_ascii_case(expected))
}

/// `McCoreUpdater.replaceInstalledCore`: link or copy into a staging file beside the target,
/// check it, then move it over the target.
pub fn replace(source: &Path, target: &Path, expected: &str) -> Result<()> {
    ensure!(std::fs::symlink_metadata(source).is_ok_and(|m| m.is_file()), "核心源文件不存在: {}", source.display());
    ensure!(crate::mc_install::sha1_file(source)?.eq_ignore_ascii_case(expected), "核心源文件校验失败");
    let parent = target.parent().with_context(|| format!("核心目标目录缺失: {}", target.display()))?;
    std::fs::create_dir_all(parent)?;
    let name = target.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let staging = parent.join(format!("{name}.install-{}.tmp", uuid::Uuid::now_v7()));
    let result = (|| -> Result<()> {
        if std::fs::hard_link(source, &staging).is_err() {
            std::fs::copy(source, &staging)?;
        }
        ensure!(crate::mc_install::sha1_file(&staging)?.eq_ignore_ascii_case(expected), "核心暂存文件校验失败");
        std::fs::rename(&staging, target)?;
        ensure!(std::fs::symlink_metadata(target).is_ok_and(|m| m.is_file()), "核心目标文件不存在");
        ensure!(crate::mc_install::sha1_file(target)?.eq_ignore_ascii_case(expected), "核心文件替换后校验失败");
        Ok(())
    })();
    let _ = std::fs::remove_file(&staging);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("rdi-j5b-core-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn names_follow_kotlin() {
        assert_eq!(slug("1.21.1", "neoforge"), "1.21.1-neoforge");
        assert_eq!(file_name(&slug("1.20.1", "forge")), "rdi-5-mc-client-1.20.1-forge.jar");
        assert_eq!(valid_sha1(" ABCDEF0123456789abcdef0123456789ABCDEF01 ").unwrap(), "abcdef0123456789abcdef0123456789abcdef01");
        assert!(valid_sha1("abc").is_none() && valid_sha1(&"g".repeat(40)).is_none());
    }

    #[test]
    fn replace_checks_the_hash_and_leaves_no_staging_file() {
        let dir = temp();
        let source = dir.join("cache.jar");
        std::fs::write(&source, b"new core").unwrap();
        let sha = crate::mc_install::sha1_file(&source).unwrap();
        let target = dir.join("mods/rdi-5-mc-client-1.21.1-neoforge.jar");
        std::fs::create_dir_all(target.parent().unwrap()).unwrap();
        std::fs::write(&target, b"old core").unwrap();
        assert!(!is_current(&target, &sha));
        replace(&source, &target, &sha).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), b"new core");
        assert!(is_current(&target, &sha));
        assert_eq!(replace(&source, &target, &"0".repeat(40)).unwrap_err().to_string(), "核心源文件校验失败");
        assert_eq!(std::fs::read_dir(target.parent().unwrap()).unwrap().count(), 1, "no staging file left");
        std::fs::remove_dir_all(dir).unwrap();
    }
}
