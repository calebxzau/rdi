//! Disk cache for downloaded images (IMAGE-CACHE-PLAN.md, owner decisions 2026-11-23).
//!
//! `http-cache-semantics` decides freshness and builds revalidation requests; this module stores
//! the bodies. Each entry is `<sha256(url)>.img` plus `<sha256(url)>.json`. Images from
//! forgecdn, mcmod and Modrinth count as fresh for 15 days (D2), other hosts follow the
//! response headers. A stale image is used when the network fails (D3). The directory is
//! kept under 1 GiB by deleting the least recently used entries (D1). No Slint types.
use anyhow::{Context, Result, bail};
use http_cache_semantics::{AfterResponse, BeforeRequest, CacheOptions, CachePolicy};
use reqwest::{StatusCode, header::AUTHORIZATION};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        Arc, LazyLock, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

/// D1: 1 GiB; eviction goes down to 90% so it does not run on every store.
pub const CAPACITY: u64 = 1024 * 1024 * 1024;
/// D2: pack and mod icons rarely change.
const PINNED_FRESH: Duration = Duration::from_secs(15 * 24 * 3600);
/// The same limit `images::download` had before the cache.
pub const MAX_IMAGE_BYTES: usize = 16 * 1024 * 1024;
const CONNECT_RETRIES: u32 = 2;
/// Files this young may belong to a write in progress (another launcher process).
const ORPHAN_GRACE: Duration = Duration::from_secs(60);

#[derive(Serialize, Deserialize)]
struct Meta {
    url: String,
    size: u64,
    /// Unix seconds when the body was stored or last revalidated (D2's 15 days start here).
    checked: u64,
    policy: CachePolicy,
}

/// Where a returned body came from; used by tests and the debug log.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Source {
    Fresh,
    Revalidated,
    Downloaded,
    /// The network failed and a stale entry was used (D3).
    Stale,
}

pub struct ImageCache {
    dir: PathBuf,
    capacity: u64,
    /// Bytes on disk as last counted; `u64::MAX` until the first scan.
    total: AtomicU64,
    scanning: AtomicBool,
    flights: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
}

#[cfg(not(test))]
static GLOBAL: LazyLock<Arc<ImageCache>> =
    LazyLock::new(|| Arc::new(ImageCache::new(dir(&crate::paths::data_dir()), CAPACITY)));
// Unit tests that download images must not write into the real `client/ui/run`; one fixed temp
// folder, so repeated test runs do not pile up directories.
#[cfg(test)]
static GLOBAL: LazyLock<Arc<ImageCache>> =
    LazyLock::new(|| Arc::new(ImageCache::new(std::env::temp_dir().join("rdi-image-cache-test"), CAPACITY)));

/// The launcher's image cache in `<data_dir>/image-cache`.
pub fn global() -> Arc<ImageCache> {
    GLOBAL.clone()
}

pub fn dir(data: &Path) -> PathBuf {
    data.join("image-cache")
}

fn key(url: &str) -> String {
    Sha256::digest(url.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

fn unix(time: SystemTime) -> u64 {
    time.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

/// D2: forgecdn, mcmod and Modrinth images.
fn pinned(url: &str) -> bool {
    let Some(host) = reqwest::Url::parse(url).ok().and_then(|u| u.host_str().map(str::to_ascii_lowercase)) else {
        return false;
    };
    let under = |domain: &str| host == domain || host.ends_with(&format!(".{domain}"));
    host == "cdn.modrinth.com" || under("forgecdn.net") || under("mcmod.cn")
}

/// Only real images are stored, so an error page sent with status 200 is never kept for days.
fn looks_like_image(bytes: &[u8]) -> bool {
    crate::avif::is_avif(bytes) || image::guess_format(bytes).is_ok()
}

fn options() -> CacheOptions {
    // The launcher is a single-user cache: `private` responses may be stored.
    CacheOptions { shared: false, ..CacheOptions::default() }
}

impl ImageCache {
    pub fn new(dir: PathBuf, capacity: u64) -> Self {
        Self {
            dir,
            capacity,
            total: AtomicU64::new(u64::MAX),
            scanning: AtomicBool::new(false),
            flights: Mutex::new(HashMap::new()),
        }
    }

    fn paths(&self, key: &str) -> (PathBuf, PathBuf) {
        (self.dir.join(format!("{key}.img")), self.dir.join(format!("{key}.json")))
    }

    /// The image at `url`, from disk when fresh, revalidated when stale, downloaded otherwise.
    pub async fn get(self: &Arc<Self>, http: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
        Ok(self.get_with_source(http, url).await?.0)
    }

    pub async fn get_with_source(self: &Arc<Self>, http: &reqwest::Client, url: &str) -> Result<(Vec<u8>, Source)> {
        self.start_scan_once();
        let key = key(url);
        let flight = self.flights.lock().unwrap_or_else(|e| e.into_inner()).entry(key.clone()).or_default().clone();
        let result = {
            let _one = flight.lock().await;
            self.get_locked(http, url, &key).await
        };
        // Drop the flight entry once nobody else waits on it.
        let mut flights = self.flights.lock().unwrap_or_else(|e| e.into_inner());
        if flights.get(&key).is_some_and(|f| Arc::strong_count(f) <= 2) {
            flights.remove(&key);
        }
        if let Ok((_, source)) = &result {
            tracing::debug!(?source, "图片缓存");
        }
        result
    }

    async fn get_locked(self: &Arc<Self>, http: &reqwest::Client, url: &str, key: &str) -> Result<(Vec<u8>, Source)> {
        let request = http.get(url).build().map_err(|e| anyhow::Error::new(e.without_url()).context("图片地址无效"))?;
        anyhow::ensure!(!request.headers().contains_key(AUTHORIZATION), "图片请求不应带登录信息");
        let stored = self.load(key, url).await;
        let now = SystemTime::now();
        let mut conditional = None;
        if let Some((meta, body)) = &stored {
            if pinned(url) && now < UNIX_EPOCH + Duration::from_secs(meta.checked) + PINNED_FRESH {
                self.touch(key);
                return Ok((body.clone(), Source::Fresh));
            }
            match meta.policy.before_request(&request, now) {
                BeforeRequest::Fresh(_) => {
                    self.touch(key);
                    return Ok((body.clone(), Source::Fresh));
                }
                BeforeRequest::Stale { request, .. } => conditional = Some(request.headers),
            }
        }
        let mut builder = http.get(url);
        if let Some(headers) = conditional.clone() {
            builder = builder.headers(headers);
        }
        let response = match send(builder).await {
            Ok(response) if !response.status().is_server_error() => response,
            failed => {
                if let Some((_, body)) = stored {
                    match &failed {
                        Ok(response) => tracing::debug!(status = %response.status(), "图片服务器出错，使用过期缓存"),
                        Err(error) => tracing::debug!(error = %format!("{error:#}"), "图片下载失败，使用过期缓存"),
                    }
                    return Ok((body, Source::Stale));
                }
                return match failed {
                    Ok(response) => Err(anyhow::anyhow!("图片HTTP错误: {}", response.status())),
                    Err(error) => Err(error),
                };
            }
        };
        let response_time = SystemTime::now();
        if response.status() == StatusCode::NOT_MODIFIED {
            let Some((meta, body)) = stored else { bail!("图片服务器返回了未请求的304") };
            match meta.policy.after_response(&request, &response, response_time) {
                AfterResponse::NotModified(policy, _) => {
                    let meta = Meta { url: url.to_owned(), size: body.len() as u64, checked: unix(response_time), policy };
                    self.write_meta(key, meta).await;
                    return Ok((body, Source::Revalidated));
                }
                AfterResponse::Modified(..) => {
                    // A 304 for different validators: the stored body cannot be trusted.
                    self.remove_key(key).await;
                    let response = send(http.get(url)).await?;
                    return self.store_response(&request, response, url, key).await.map(|b| (b, Source::Downloaded));
                }
            }
        }
        let body = self.store_response(&request, response, url, key).await?;
        Ok((body, Source::Downloaded))
    }

    async fn store_response(self: &Arc<Self>, request: &reqwest::Request, response: reqwest::Response, url: &str, key: &str) -> Result<Vec<u8>> {
        let mut response = response
            .error_for_status()
            .map_err(|e| anyhow::Error::new(e.without_url()).context("图片HTTP错误"))?;
        let policy = CachePolicy::new_options(request, &response, SystemTime::now(), options());
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            anyhow::ensure!(bytes.len() + chunk.len() <= MAX_IMAGE_BYTES, "图片超过16MiB");
            bytes.extend_from_slice(&chunk);
        }
        if policy.is_storable() && looks_like_image(&bytes) {
            let meta = Meta { url: url.to_owned(), size: bytes.len() as u64, checked: unix(SystemTime::now()), policy };
            self.write(key, bytes.clone(), meta).await;
        }
        Ok(bytes)
    }

    /// The stored entry when its metadata is readable, names this URL and matches the body.
    async fn load(&self, key: &str, url: &str) -> Option<(Meta, Vec<u8>)> {
        let (body_path, meta_path) = self.paths(key);
        let url = url.to_owned();
        tokio::task::spawn_blocking(move || -> Option<(Meta, Vec<u8>)> {
            let meta: Meta = serde_json::from_slice(&std::fs::read(&meta_path).ok()?).ok()?;
            let body = std::fs::read(&body_path).ok()?;
            (meta.url == url && meta.size == body.len() as u64).then_some((meta, body))
        })
        .await
        .ok()
        .flatten()
    }

    /// A hit marks the body as recently used for eviction.
    fn touch(&self, key: &str) {
        let (body, _) = self.paths(key);
        tokio::task::spawn_blocking(move || {
            if let Err(error) = std::fs::File::options().write(true).open(&body).and_then(|f| f.set_modified(SystemTime::now())) {
                tracing::debug!(%error, "更新图片缓存使用时间失败");
            }
        });
    }

    /// Body first, metadata last, each through a temp file and rename, so a crash never leaves
    /// metadata pointing at a partial body. Failures only lose the cache entry.
    async fn write(self: &Arc<Self>, key: &str, body: Vec<u8>, meta: Meta) {
        let (body_path, meta_path) = self.paths(key);
        let dir = self.dir.clone();
        let written = tokio::task::spawn_blocking(move || -> Result<i64> {
            std::fs::create_dir_all(&dir)?;
            let old = file_len(&body_path) + file_len(&meta_path);
            let _ = std::fs::remove_file(&meta_path);
            replace(&body_path, &body)?;
            let meta = serde_json::to_vec(&meta)?;
            replace(&meta_path, &meta)?;
            Ok((body.len() + meta.len()) as i64 - old as i64)
        })
        .await;
        match written {
            Ok(Ok(delta)) => self.grew(delta),
            Ok(Err(error)) => tracing::warn!(error = %format!("{error:#}"), "写入图片缓存失败"),
            Err(error) => tracing::warn!(%error, "写入图片缓存任务失败"),
        }
    }

    async fn write_meta(self: &Arc<Self>, key: &str, meta: Meta) {
        let (_, meta_path) = self.paths(key);
        let written = tokio::task::spawn_blocking(move || -> Result<()> { replace(&meta_path, &serde_json::to_vec(&meta)?) }).await;
        match written {
            Ok(Ok(())) => self.touch(key),
            Ok(Err(error)) => tracing::warn!(error = %format!("{error:#}"), "更新图片缓存失败"),
            Err(error) => tracing::warn!(%error, "更新图片缓存任务失败"),
        }
    }

    /// Drops the entry of `url`, e.g. when its bytes cannot be decoded.
    pub async fn forget(&self, url: &str) {
        self.remove_key(&key(url)).await;
    }

    async fn remove_key(&self, key: &str) {
        let (body, meta) = self.paths(key);
        let _ = tokio::task::spawn_blocking(move || {
            let _ = std::fs::remove_file(meta);
            let _ = std::fs::remove_file(body);
        })
        .await;
    }

    fn grew(self: &Arc<Self>, delta: i64) {
        let total = self
            .total
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |t| (t != u64::MAX).then(|| t.saturating_add_signed(delta)))
            .map(|old| old.saturating_add_signed(delta));
        if let Ok(total) = total
            && total > self.capacity
        {
            self.spawn_scan();
        }
    }

    fn start_scan_once(self: &Arc<Self>) {
        if self.total.load(Ordering::Acquire) == u64::MAX {
            self.spawn_scan();
        }
    }

    fn spawn_scan(self: &Arc<Self>) {
        if self.scanning.swap(true, Ordering::AcqRel) {
            return;
        }
        let cache = self.clone();
        tokio::task::spawn_blocking(move || {
            match evict(&cache.dir, cache.capacity, SystemTime::now()) {
                Ok(total) => cache.total.store(total, Ordering::Release),
                Err(error) => {
                    tracing::warn!(error = %format!("{error:#}"), "整理图片缓存失败");
                    cache.total.store(0, Ordering::Release);
                }
            }
            cache.scanning.store(false, Ordering::Release);
        });
    }
}

async fn send(builder: reqwest::RequestBuilder) -> Result<reqwest::Response> {
    let mut attempt = 0;
    loop {
        let Some(request) = builder.try_clone() else { bail!("图片请求无法重试") };
        match request.send().await {
            Err(e) if (e.is_connect() || e.is_timeout()) && attempt < CONNECT_RETRIES => {
                attempt += 1;
                tracing::debug!(attempt, error = %e.without_url(), "Image request retry");
                tokio::time::sleep(Duration::from_millis(400 * u64::from(attempt))).await;
            }
            result => return result.map_err(|e| anyhow::Error::new(e.without_url()).context("图片请求失败")),
        }
    }
}

fn file_len(path: &Path) -> u64 {
    std::fs::metadata(path).map(|m| m.len()).unwrap_or(0)
}

fn replace(path: &Path, bytes: &[u8]) -> Result<()> {
    let temp = path.with_extension(format!("{}.tmp{}", path.extension().and_then(|e| e.to_str()).unwrap_or(""), uuid::Uuid::now_v7().simple()));
    std::fs::write(&temp, bytes).with_context(|| format!("写入{}失败", temp.display()))?;
    if let Err(error) = std::fs::rename(&temp, path) {
        let _ = std::fs::remove_file(&temp);
        return Err(error).with_context(|| format!("替换{}失败", path.display()));
    }
    Ok(())
}

/// Counts the cache, deletes orphans and temp files older than a minute, and when the total is
/// over `capacity` deletes the least recently used entries down to 90%. Returns the new total.
pub fn evict(dir: &Path, capacity: u64, now: SystemTime) -> Result<u64> {
    let Ok(read) = std::fs::read_dir(dir) else { return Ok(0) };
    // key -> (body modified, body len, meta len)
    let mut entries: HashMap<String, (Option<SystemTime>, u64, Option<u64>)> = HashMap::new();
    let old = |modified: Option<SystemTime>| modified.is_none_or(|m| now.duration_since(m).unwrap_or_default() >= ORPHAN_GRACE);
    for item in read {
        let item = item?;
        let name = item.file_name().to_string_lossy().into_owned();
        let Ok(meta) = item.metadata() else { continue };
        let modified = meta.modified().ok();
        match name.rsplit_once('.') {
            Some((key, "img")) => {
                let e = entries.entry(key.to_owned()).or_default();
                e.0 = modified;
                e.1 = meta.len();
            }
            Some((key, "json")) => entries.entry(key.to_owned()).or_default().2 = Some(meta.len()),
            _ if name.contains(".tmp") && old(modified) => {
                let _ = std::fs::remove_file(item.path());
            }
            _ => {}
        }
    }
    let mut live = Vec::new();
    for (key, (modified, body, meta)) in entries {
        match (modified, meta) {
            (Some(modified), Some(meta)) => live.push((modified, key, body + meta)),
            (modified, meta) if old(modified) => {
                // An orphan: a body without metadata or metadata without a body.
                let _ = std::fs::remove_file(dir.join(format!("{key}.img")));
                if meta.is_some() {
                    let _ = std::fs::remove_file(dir.join(format!("{key}.json")));
                }
            }
            (_, _) => {}
        }
    }
    let mut total: u64 = live.iter().map(|(_, _, size)| size).sum();
    if total <= capacity {
        return Ok(total);
    }
    live.sort();
    let target = capacity / 10 * 9;
    let mut removed = 0usize;
    for (_, key, size) in live {
        if total <= target {
            break;
        }
        let _ = std::fs::remove_file(dir.join(format!("{key}.json")));
        let _ = std::fs::remove_file(dir.join(format!("{key}.img")));
        total = total.saturating_sub(size);
        removed += 1;
    }
    tracing::info!(removed, total, "图片缓存超过上限，已删除最久未用的图片");
    Ok(total)
}

/// 清理缓存 (D4): deletes every file in the cache; returns (images, bytes) freed.
pub fn clear(dir: &Path) -> Result<(usize, u64)> {
    let Ok(read) = std::fs::read_dir(dir) else { return Ok((0, 0)) };
    let (mut images, mut bytes, mut failed) = (0, 0, 0);
    for item in read {
        let item = item?;
        let len = item.metadata().map(|m| m.len()).unwrap_or(0);
        match std::fs::remove_file(item.path()) {
            Ok(()) => {
                bytes += len;
                images += usize::from(item.file_name().to_string_lossy().ends_with(".img"));
            }
            Err(error) => {
                tracing::warn!(%error, "删除图片缓存失败");
                failed += 1;
            }
        }
    }
    anyhow::ensure!(failed == 0, "清除图片缓存时有{failed}个文件删除失败");
    Ok((images, bytes))
}

#[cfg(test)]
mod tests;
