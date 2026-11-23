//! J5b: the room game's window title (`GameWindowService` / `gameWindowTitle`). Lengths count
//! UTF-16 units like Kotlin, and a cut never splits a surrogate pair.
//! `protocol/fixtures/j5b-window.json` (Kotlin `J5bWindowTitleFixtureTest`) guards it.
//! GJ5b2: the window icon, `<version>/icon.png`, which Compose reads and writes too.
use anyhow::{Context, Result, anyhow, bail, ensure};
use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

const LIMIT: usize = 64;
const SEPARATOR: &str = " · ";

fn clean(value: &str) -> String {
    value.chars().map(|c| if c.is_ascii_control() { ' ' } else { c }).collect::<String>().trim().to_owned()
}

fn units(value: &str) -> usize {
    value.encode_utf16().count()
}

fn shorten(value: &str, limit: usize) -> String {
    let utf16: Vec<u16> = value.encode_utf16().collect();
    if utf16.len() <= limit {
        return value.to_owned();
    }
    let mut end = limit.saturating_sub(1);
    if end > 0 && (0xD800..0xDC00).contains(&utf16[end - 1]) {
        end -= 1;
    }
    format!("{}…", String::from_utf16_lossy(&utf16[..end]))
}

/// `gameWindowTitle`.
pub fn title(pack_name: &str, host_name: Option<&str>, version_name: &str) -> String {
    let name = Some(clean(pack_name)).filter(|n| !n.is_empty()).unwrap_or_else(|| "Minecraft".into());
    let version = clean(version_name);
    let pack = if version.is_empty() { name } else { format!("{name} {version}") };
    let Some(host) = host_name.map(clean).filter(|h| !h.is_empty()) else {
        return shorten(&pack, LIMIT);
    };
    let budget = LIMIT - units(SEPARATOR);
    let pack_limit = units(&pack).min((budget / 2).max(budget.saturating_sub(units(&host))));
    format!("{}{SEPARATOR}{}", shorten(&pack, pack_limit), shorten(&host, budget - pack_limit))
}

/// The window title argument (`-Drdi.window.title=...`).
pub fn title_jvm_arg(pack_name: &str, host_name: Option<&str>, version_name: &str) -> String {
    format!("-Drdi.window.title={}", title(pack_name, host_name, version_name))
}

/// The window icon argument (`-Drdi.window.icon=...`).
pub fn icon_jvm_arg(icon: &Path) -> String {
    format!("-Drdi.window.icon={}", icon.display())
}

/// Kotlin's download timeout for the pack icon.
const ICON_TIMEOUT: Duration = Duration::from_secs(3);

/// One writer per icon file at a time (`iconLocks`).
fn icon_lock(icon: &Path) -> Arc<tokio::sync::Mutex<()>> {
    static LOCKS: LazyLock<Mutex<HashMap<PathBuf, Arc<tokio::sync::Mutex<()>>>>> = LazyLock::new(Default::default);
    LOCKS.lock().unwrap_or_else(|e| e.into_inner()).entry(icon.to_owned()).or_default().clone()
}

/// `GameWindowService.prepareIcon`: `<version>/icon.png`, shared with Compose. A readable file is
/// used as it is; otherwise the pack icon is downloaded (AVIF or any image format the launcher
/// reads), stored as PNG and used. `Ok(None)` when there is neither a file nor an icon address.
/// `launcher_dir` is where an undecodable old icon is archived, under `DEL/`.
pub async fn prepare_icon<F, Fut>(version_dir: &Path, launcher_dir: &Path, icon_url: Option<&str>, download: F) -> Result<Option<PathBuf>>
where
    F: FnOnce(String) -> Fut,
    Fut: std::future::Future<Output = Result<Vec<u8>>>,
{
    prepare_icon_within(ICON_TIMEOUT, version_dir, launcher_dir, icon_url, download).await
}

async fn prepare_icon_within<F, Fut>(timeout: Duration, version_dir: &Path, launcher_dir: &Path, icon_url: Option<&str>, download: F) -> Result<Option<PathBuf>>
where
    F: FnOnce(String) -> Fut,
    Fut: std::future::Future<Output = Result<Vec<u8>>>,
{
    let icon = std::path::absolute(version_dir)?.join("icon.png");
    let lock = icon_lock(&icon);
    let _guard = lock.lock().await;
    let existing = icon.clone();
    let (exists, readable) = tokio::task::spawn_blocking(move || -> Result<(bool, bool)> {
        let exists = existing.symlink_metadata().is_ok();
        Ok((exists, exists && is_readable_icon(&existing)?))
    })
    .await??;
    if readable {
        return Ok(Some(icon));
    }
    let Some(url) = icon_url.map(str::trim).filter(|u| !u.is_empty()) else {
        if exists {
            bail!("本地图标无法解码，且未提供整合包图标地址：{}", icon.display());
        }
        return Ok(None);
    };
    let bytes = tokio::time::timeout(timeout, download(url.to_owned())).await.map_err(|_| anyhow!("整合包图标下载超时"))??;
    let (target, launcher_dir) = (icon.clone(), launcher_dir.to_owned());
    tokio::task::spawn_blocking(move || {
        let png = encode_png(&bytes)?;
        publish_icon(&target, &launcher_dir, &png)
    })
    .await??;
    Ok(Some(icon))
}

/// `isReadableIcon`: a path that is not a regular file and read errors are errors; only a file
/// that was read but does not decode may be replaced.
fn is_readable_icon(icon: &Path) -> Result<bool> {
    let metadata = icon.symlink_metadata()?;
    ensure!(metadata.is_file(), "图标路径不是普通文件：{}", icon.display());
    let bytes = std::fs::read(icon)?;
    Ok(crate::images::decode(&bytes).is_ok())
}

/// `encodeWindowIconPng`.
fn encode_png(bytes: &[u8]) -> Result<Vec<u8>> {
    let image = crate::images::decode(bytes).context("无法解码整合包图标")?;
    let mut png = std::io::Cursor::new(Vec::new());
    image.to_rgba8().write_to(&mut png, image::ImageFormat::Png).context("无法编码整合包PNG图标")?;
    Ok(png.into_inner())
}

/// `publishIcon`: write a private scratch file, check it, then move it into place. A readable icon
/// another launcher put there meanwhile is kept; an undecodable one is archived first.
fn publish_icon(icon: &Path, launcher_dir: &Path, png: &[u8]) -> Result<()> {
    let parent = icon.parent().context("图标路径无效")?;
    let staging = parent.join(format!(".rdi-window-icon-{}.tmp", uuid::Uuid::now_v7()));
    let result = (|| {
        std::fs::write(&staging, png)?;
        ensure!(is_readable_icon(&staging)?, "转换后的整合包图标无法解码");
        if icon.symlink_metadata().is_ok() {
            if is_readable_icon(icon)? {
                return Ok(());
            }
            archive_invalid_icon(icon, launcher_dir)?;
        }
        std::fs::rename(&staging, icon)?;
        Ok(())
    })();
    // Only the scratch file created above is removed, never an existing pack file.
    if staging.exists()
        && let Err(error) = std::fs::remove_file(&staging)
    {
        tracing::warn!(%error, path = %staging.display(), "删除临时图标文件失败");
    }
    result
}

/// `archiveInvalidIcon`: `<launcher>/DEL/window-icon-<unique>/<path relative to the launcher>`.
fn archive_invalid_icon(icon: &Path, launcher_dir: &Path) -> Result<()> {
    let root = std::path::absolute(launcher_dir)?;
    let relative = match icon.strip_prefix(&root) {
        Ok(relative) => relative.to_owned(),
        Err(_) => Path::new("external").join(icon.components().filter(|c| matches!(c, Component::Normal(_))).collect::<PathBuf>()),
    };
    let archived = root.join("DEL").join(format!("window-icon-{}", uuid::Uuid::now_v7())).join(relative);
    std::fs::create_dir_all(archived.parent().context("图标归档路径无效")?)?;
    std::fs::rename(icon, &archived)?;
    tracing::info!(from = %icon.display(), to = %archived.display(), "已归档无法解码的窗口图标");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURE: &str = include_str!("../../protocol/fixtures/j5b-window.json");

    #[test]
    fn titles_match_the_kotlin_fixture() {
        let cases: Vec<serde_json::Value> = serde_json::from_str(FIXTURE).unwrap();
        assert!(cases.len() >= 9);
        for case in cases {
            let built = title(case["pack"].as_str().unwrap(), case["host"].as_str(), case["version"].as_str().unwrap());
            assert_eq!(built, case["title"].as_str().unwrap(), "{case}");
        }
    }

    fn scratch() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rdi-window-icon-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(dir.join("versions").join("pack")).unwrap();
        dir
    }

    fn png(size: u32) -> Vec<u8> {
        let mut out = std::io::Cursor::new(Vec::new());
        image::RgbaImage::from_pixel(size, size, image::Rgba([10, 20, 30, 255])).write_to(&mut out, image::ImageFormat::Png).unwrap();
        out.into_inner()
    }

    fn prepare(root: &Path, url: Option<&str>, bytes: Result<Vec<u8>>) -> Result<Option<PathBuf>> {
        let version = root.join("versions").join("pack");
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(prepare_icon(&version, root, url, |_| async move { bytes }))
    }

    #[test]
    fn a_readable_icon_is_used_without_downloading() {
        let root = scratch();
        let icon = root.join("versions").join("pack").join("icon.png");
        std::fs::write(&icon, png(4)).unwrap();
        let used = prepare(&root, Some("https://example.invalid/i.png"), Err(anyhow!("must not download"))).unwrap();
        assert_eq!(used.as_deref(), Some(icon.as_path()));
        assert_eq!(icon_jvm_arg(&icon), format!("-Drdi.window.icon={}", icon.display()));
    }

    #[test]
    fn no_icon_and_no_address_gives_none() {
        let root = scratch();
        assert_eq!(prepare(&root, Some("  "), Err(anyhow!("unused"))).unwrap(), None);
    }

    #[test]
    fn an_avif_download_becomes_a_png_icon() {
        let root = scratch();
        let avif = std::fs::read(Path::new(env!("CARGO_MANIFEST_DIR")).join("assets/icons/mouse_left.avif")).unwrap();
        let icon = prepare(&root, Some("https://example.invalid/i.avif"), Ok(avif)).unwrap().unwrap();
        let bytes = std::fs::read(&icon).unwrap();
        assert_eq!(image::guess_format(&bytes).unwrap(), image::ImageFormat::Png);
        let leftovers: Vec<_> = std::fs::read_dir(icon.parent().unwrap()).unwrap().filter_map(|e| e.ok()).filter(|e| e.file_name().to_string_lossy().ends_with(".tmp")).collect();
        assert!(leftovers.is_empty());
    }

    #[test]
    fn an_undecodable_icon_is_archived_and_replaced() {
        let root = scratch();
        let icon = root.join("versions").join("pack").join("icon.png");
        std::fs::write(&icon, b"not an image").unwrap();
        let used = prepare(&root, Some("https://example.invalid/i.png"), Ok(png(8))).unwrap().unwrap();
        assert!(is_readable_icon(&used).unwrap());
        let archives: Vec<_> = std::fs::read_dir(root.join("DEL")).unwrap().filter_map(|e| e.ok()).collect();
        assert_eq!(archives.len(), 1);
        let archived = archives[0].path().join("versions").join("pack").join("icon.png");
        assert_eq!(std::fs::read(archived).unwrap(), b"not an image");
    }

    #[test]
    fn an_undecodable_icon_without_address_is_an_error() {
        let root = scratch();
        std::fs::write(root.join("versions").join("pack").join("icon.png"), b"broken").unwrap();
        let error = prepare(&root, None, Err(anyhow!("unused"))).unwrap_err();
        assert!(error.to_string().starts_with("本地图标无法解码，且未提供整合包图标地址"), "{error}");
    }

    #[test]
    fn failed_or_undecodable_downloads_leave_no_icon() {
        let root = scratch();
        let icon = root.join("versions").join("pack").join("icon.png");
        assert!(prepare(&root, Some("https://example.invalid/i.png"), Err(anyhow!("HTTP404"))).is_err());
        assert!(prepare(&root, Some("https://example.invalid/i.png"), Ok(b"<html>".to_vec())).is_err());
        assert!(!icon.exists());
    }

    #[test]
    fn a_slow_download_times_out() {
        let root = scratch();
        let version = root.join("versions").join("pack");
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let error = runtime
            .block_on(prepare_icon_within(Duration::from_millis(50), &version, &root, Some("https://example.invalid/i.png"), |_| async {
                tokio::time::sleep(Duration::from_secs(5)).await;
                Ok(png(4))
            }))
            .unwrap_err();
        assert_eq!(error.to_string(), "整合包图标下载超时");
    }
}
