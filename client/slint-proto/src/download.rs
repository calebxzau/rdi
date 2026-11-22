//! FileDownloader contract: invocation-owned partials, ordered sources, validated range queue.
use crate::{download_policy, tasks::Cancellation};
use anyhow::{Context, Result, ensure};
use reqwest::{
    Client, Response,
    header::{CONTENT_RANGE, ETAG, HeaderMap, LAST_MODIFIED},
};
use std::ops::{Deref, DerefMut};
use std::{
    collections::HashMap,
    io::{Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock, Weak},
    time::{Duration, Instant},
};
use tokio::sync::Semaphore;
const PIECE: u64 = 1024 * 1024;
static TRANSFERS: Semaphore = Semaphore::const_new(64);
#[derive(Debug)]
struct RetryAfter(u64);
impl std::fmt::Display for RetryAfter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("下载请求过于频繁")
    }
}
impl std::error::Error for RetryAfter {}
#[derive(Debug)]
struct ValidationFailure(String);
impl std::fmt::Display for ValidationFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for ValidationFailure {}
#[derive(Clone, Default)]
pub struct Source {
    pub url: String,
    pub headers: HeaderMap,
}
#[derive(Clone, Copy, Debug)]
pub struct Progress {
    pub bytes: u64,
    pub total: Option<u64>,
    pub speed: f64,
}
#[derive(Clone)]
pub struct Downloader {
    pub http: Client,
}
#[derive(Clone)]
struct Probe {
    source: Source,
    size: Option<u64>,
    ranges: bool,
    identity: Option<String>,
}
#[derive(Default)]
struct Resume {
    url: String,
    identity: Option<String>,
    total: Option<u64>,
}
static TARGETS: OnceLock<Mutex<HashMap<PathBuf, Weak<tokio::sync::Mutex<()>>>>> = OnceLock::new();
static HOSTS: OnceLock<Mutex<HashMap<String, Arc<Semaphore>>>> = OnceLock::new();
fn target_lock(path: &Path) -> Arc<tokio::sync::Mutex<()>> {
    let mut map = TARGETS.get_or_init(Default::default).lock().unwrap();
    map.retain(|_, v| v.strong_count() > 0);
    if let Some(lock) = map.get(path).and_then(Weak::upgrade) {
        return lock;
    }
    let lock = Arc::new(tokio::sync::Mutex::new(()));
    map.insert(path.into(), Arc::downgrade(&lock));
    lock
}
fn host_limit(url: &str) -> Arc<Semaphore> {
    let host = reqwest::Url::parse(url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_owned))
        .unwrap_or_default();
    let n = if host == "bmclapi2.bangbang93.com" {
        8
    } else {
        24
    };
    HOSTS
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .entry(host)
        .or_insert_with(|| Arc::new(Semaphore::new(n)))
        .clone()
}
pub async fn cancellable<T>(
    cancel: &Cancellation,
    future: impl std::future::Future<Output = Result<T>>,
) -> Result<T> {
    tokio::pin!(future);
    loop {
        cancel.check()?;
        tokio::select! {value=&mut future=>return value, _=tokio::time::sleep(Duration::from_millis(25))=>{}}
    }
}
fn identity(r: &Response) -> Option<String> {
    r.headers()
        .get(ETAG)
        .and_then(|v| v.to_str().ok())
        .filter(|v| !v.starts_with("W/") && !v.is_empty())
        .or_else(|| r.headers().get(LAST_MODIFIED).and_then(|v| v.to_str().ok()))
        .map(str::to_owned)
}
/// Kotlin `contentType.match(ContentType.Application.Json)`: only `application/json`, with any
/// parameters. CurseForge's CDN labels some zips `application/x-amz-json-1.0`; those are files.
pub fn json_response(content_type: Option<&str>) -> bool {
    content_type.is_some_and(|raw| {
        raw.split(';')
            .next()
            .unwrap_or("")
            .trim()
            .eq_ignore_ascii_case("application/json")
    })
}
pub fn content_range(raw: &str) -> Option<(u64, u64, u64)> {
    let (unit, rest) = raw.trim().split_once(' ')?;
    if !unit.eq_ignore_ascii_case("bytes") {
        return None;
    }
    let (range, total) = rest.split_once('/')?;
    let (a, b) = range.split_once('-')?;
    let (a, b, total) = (a.parse().ok()?, b.parse().ok()?, total.parse().ok()?);
    (a <= b && b < total).then_some((a, b, total))
}
fn response_range(r: &Response) -> Option<(u64, u64, u64)> {
    content_range(r.headers().get(CONTENT_RANGE)?.to_str().ok()?)
}
fn remove_temp(path: &Path) {
    if let Err(error) = std::fs::remove_file(path)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(%error,"清理下载临时文件失败");
    }
}
struct Temp(PathBuf);
impl Drop for Temp {
    fn drop(&mut self) {
        remove_temp(&self.0);
    }
}
struct LimitedResponse {
    response: Response,
    _permit: tokio::sync::OwnedSemaphorePermit,
    _global: tokio::sync::SemaphorePermit<'static>,
}
impl Deref for LimitedResponse {
    type Target = Response;
    fn deref(&self) -> &Response {
        &self.response
    }
}
impl DerefMut for LimitedResponse {
    fn deref_mut(&mut self) -> &mut Response {
        &mut self.response
    }
}
impl Downloader {
    async fn send(
        &self,
        source: &Source,
        method: reqwest::Method,
        range: Option<(u64, Option<u64>)>,
        if_range: Option<&str>,
        timeout: Duration,
    ) -> Result<LimitedResponse> {
        let _global = TRANSFERS.acquire().await?;
        let _permit = host_limit(&source.url).acquire_owned().await?;
        let mut request = self
            .http
            .request(method, &source.url)
            .headers(source.headers.clone())
            .header("Accept-Encoding", "identity")
            .timeout(timeout);
        if let Some((a, b)) = range {
            request = request.header(
                "Range",
                format!("bytes={a}-{}", b.map(|v| v.to_string()).unwrap_or_default()),
            );
        }
        if let Some(v) = if_range {
            request = request.header("If-Range", v);
        }
        let response = request.send().await.map_err(|_| {
            download_policy::failure(&source.url, true, None);
            std::io::Error::other("下载连接失败")
        })?;
        if response.status() == 429 {
            let delay = response
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(1)
                .saturating_mul(1000);
            download_policy::failure(&source.url, true, Some(delay));
            return Err(RetryAfter(delay).into());
        }
        Ok(LimitedResponse {
            response,
            _permit,
            _global,
        })
    }
    async fn probe(
        &self,
        source: &Source,
        known: Option<u64>,
        partial: u64,
        timeout: Duration,
    ) -> Result<Probe> {
        let mut size = known;
        let mut id = None;
        if !(known.is_some_and(|s| s > 0 && s < 4 * PIECE) && partial == 0) {
            match self
                .send(source, reqwest::Method::HEAD, None, None, timeout)
                .await
            {
                Ok(r) if r.status().is_success() => {
                    let actual = r.content_length().filter(|n| *n > 0);
                    ensure!(
                        known.is_none() || actual.is_none() || known == actual,
                        "下载源文件大小不匹配"
                    );
                    size = known.or(actual);
                    id = identity(&r);
                }
                Ok(_) => {}
                Err(error) => tracing::debug!(%error,"下载大小探测失败"),
            }
        }
        let mut ranges = false;
        if (size.is_none_or(|n| n >= 4 * PIECE) || partial > 0)
            && !source.url.contains("://bmclapi2.bangbang93.com/")
        {
            match self
                .send(
                    source,
                    reqwest::Method::GET,
                    Some((0, Some(0))),
                    None,
                    timeout,
                )
                .await
            {
                Ok(r) if r.status() == 206 => {
                    if let Some((0, 0, n)) = response_range(&r) {
                        ensure!(known.is_none_or(|k| k == n), "下载源文件大小不匹配");
                        size = Some(n);
                        ranges = true;
                        id = identity(&r).or(id);
                    }
                }
                Ok(_) => {}
                Err(error) => tracing::debug!(%error,"下载分段探测失败"),
            }
        }
        Ok(Probe {
            source: source.clone(),
            size,
            ranges,
            identity: id,
        })
    }
    async fn stream(
        &self,
        p: &Probe,
        path: &Path,
        resume: &mut Resume,
        cancel: &Cancellation,
        progress: &(dyn Fn(Progress) + Sync),
        timeout: Duration,
    ) -> Result<()> {
        let existing = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        let mut offset = if existing > 0
            && p.ranges
            && resume.url == p.source.url
            && resume.identity.is_some()
            && resume.identity == p.identity
            && resume.total == p.size
        {
            existing
        } else {
            0
        };
        for clean in 0..2 {
            let mut response = self
                .send(
                    &p.source,
                    reqwest::Method::GET,
                    (offset > 0).then_some((offset, None)),
                    if offset > 0 {
                        resume.identity.as_deref()
                    } else {
                        None
                    },
                    timeout,
                )
                .await?;
            let response_id = identity(&response);
            if offset > 0
                && (response.status() != 206
                    || response_range(&response) != p.size.map(|n| (offset, n - 1, n))
                    || response_id != resume.identity)
            {
                ensure!(clean == 0, "下载续传内容已变化");
                offset = 0;
                remove_temp(path);
                *resume = Resume::default();
                continue;
            }
            if !response.status().is_success() || (offset == 0 && response.status() == 206) {
                let status = response.status().as_u16();
                let host_failure = status >= 500;
                download_policy::failure(&p.source.url, host_failure, None);
                return Err(
                    std::io::Error::other(format!("下载服务器返回错误状态{status}")).into(),
                );
            }
            ensure!(
                !json_response(
                    response
                        .headers()
                        .get("content-type")
                        .and_then(|h| h.to_str().ok())
                ),
                "下载地址返回了JSON"
            );
            let total = if offset > 0 {
                p.size
            } else {
                response.content_length().or(p.size)
            };
            ensure!(
                p.size.is_none() || total.is_none() || p.size == total,
                "下载源文件大小不匹配"
            );
            *resume = Resume {
                url: p.source.url.clone(),
                identity: response_id,
                total,
            };
            let mut output = std::fs::OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(offset == 0)
                .open(path)?;
            output.seek(SeekFrom::Start(offset))?;
            let start = Instant::now();
            let initial = offset;
            while let Some(bytes) = response
                .chunk()
                .await
                .map_err(|_| anyhow::anyhow!("下载连接中断"))?
            {
                cancel.check()?;
                offset += bytes.len() as u64;
                ensure!(total.is_none_or(|n| offset <= n), "下载内容超过预期大小");
                output.write_all(&bytes)?;
                progress(Progress {
                    bytes: offset,
                    total,
                    speed: (offset - initial) as f64 / start.elapsed().as_secs_f64().max(0.001),
                });
            }
            output.sync_all()?;
            ensure!(total.is_none_or(|n| n == offset), "下载文件不完整");
            progress(Progress {
                bytes: offset,
                total,
                speed: 0.,
            });
            return Ok(());
        }
        anyhow::bail!("下载续传失败")
    }
    async fn ranges(
        &self,
        sources: &[Probe],
        path: &Path,
        total: u64,
        cancel: &Cancellation,
        progress: &(dyn Fn(Progress) + Sync),
    ) -> Result<()> {
        let file = Mutex::new(std::fs::File::create(path)?);
        file.lock().unwrap().set_len(total)?;
        let next = std::sync::atomic::AtomicU64::new(0);
        let completed = std::sync::atomic::AtomicU64::new(0);
        let start = Instant::now();
        let scheduler =
            download_policy::Scheduler::new(sources.iter().map(|s| s.source.url.clone()));
        let mut workers = Vec::new();
        for _ in 0..16 {
            let (file, next, completed, scheduler) = (&file, &next, &completed, &scheduler);
            workers.push(async move {
                loop {
                    cancel.check()?;
                    let a = next.fetch_add(PIECE, std::sync::atomic::Ordering::Relaxed);
                    if a >= total {
                        break;
                    }
                    let b = (a + PIECE).min(total) - 1;
                    let mut success = false;
                    let mut excluded = std::collections::BTreeSet::new();
                    let attempts = 5.max(sources.len());
                    for attempt in 0..attempts {
                        let selected = scheduler.acquire(&excluded);
                        excluded.insert(selected);
                        let p = &sources[selected];
                        let started = Instant::now();
                        let result = async {
                            let mut r = self
                                .send(
                                    &p.source,
                                    reqwest::Method::GET,
                                    Some((a, Some(b))),
                                    None,
                                    Duration::from_secs(
                                        (60. * 1.5_f64.powi(attempt as i32)).min(180.) as u64,
                                    ),
                                )
                                .await?;
                            ensure!(
                                r.status() == 206 && response_range(&r) == Some((a, b, total)),
                                "服务器分段响应无效"
                            );
                            let mut bytes = Vec::new();
                            while let Some(chunk) = r
                                .chunk()
                                .await
                                .map_err(|_| anyhow::anyhow!("下载分段连接中断"))?
                            {
                                cancel.check()?;
                                ensure!(
                                    bytes.len() + chunk.len() <= (b - a + 1) as usize,
                                    "下载分段过大"
                                );
                                bytes.extend_from_slice(&chunk);
                            }
                            ensure!(bytes.len() as u64 == b - a + 1, "下载分段不完整");
                            let mut out = file.lock().unwrap();
                            out.seek(SeekFrom::Start(a))?;
                            out.write_all(&bytes)?;
                            Ok::<_, anyhow::Error>(())
                        }
                        .await;
                        match result {
                            Ok(()) => {
                                scheduler.success(selected, b - a + 1, started.elapsed());
                                success = true;
                                break;
                            }
                            Err(error) => {
                                cancel.check()?;
                                scheduler.failure(
                                    selected,
                                    error.downcast_ref::<std::io::Error>().is_some()
                                        || error.downcast_ref::<RetryAfter>().is_some(),
                                    error.downcast_ref::<RetryAfter>().map(|e| e.0),
                                );
                                tracing::debug!(%error,"下载分段重试");
                                if excluded.len() >= sources.len() {
                                    excluded.clear();
                                }
                                if attempt + 1 < attempts
                                    && (error.downcast_ref::<RetryAfter>().is_some()
                                        || (attempt + 1) % sources.len() == 0)
                                {
                                    let delay = error
                                        .downcast_ref::<RetryAfter>()
                                        .map(|e| e.0)
                                        .unwrap_or((500u64 << attempt.min(5)).min(10000));
                                    tokio::time::sleep(Duration::from_millis(delay)).await;
                                }
                            }
                        }
                    }
                    ensure!(success, "下载分段失败");
                    let bytes =
                        completed.fetch_add(b - a + 1, std::sync::atomic::Ordering::Relaxed) + b
                            - a
                            + 1;
                    progress(Progress {
                        bytes,
                        total: Some(total),
                        speed: bytes as f64 / start.elapsed().as_secs_f64().max(0.001),
                    });
                }
                Ok::<_, anyhow::Error>(())
            });
        }
        // Futures share a work queue and bounded 1MiB buffers; a failure drops sibling requests.
        // Join without spawning: futures borrow the file, cancellation and callback.
        let mut pending: Vec<_> = workers.into_iter().map(Box::pin).collect();
        std::future::poll_fn(|cx| {
            let mut i = 0;
            while i < pending.len() {
                use std::future::Future;
                match pending[i].as_mut().poll(cx) {
                    std::task::Poll::Ready(Ok(())) => {
                        drop(pending.swap_remove(i));
                    }
                    std::task::Poll::Ready(Err(e)) => return std::task::Poll::Ready(Err(e)),
                    std::task::Poll::Pending => i += 1,
                }
            }
            if pending.is_empty() {
                std::task::Poll::Ready(Ok(()))
            } else {
                std::task::Poll::Pending
            }
        })
        .await?;
        file.lock().unwrap().sync_all()?;
        Ok(())
    }
    pub async fn download(
        &self,
        sources: &[Source],
        path: &Path,
        known: Option<u64>,
        cancel: &Cancellation,
        validate: &(dyn Fn(&Path) -> Result<()> + Sync),
        progress: &(dyn Fn(Progress) + Sync),
    ) -> Result<()> {
        let absolute = std::path::absolute(path)?;
        let lock = target_lock(&absolute);
        let _guard = cancellable(cancel, async { Ok(lock.lock().await) }).await?;
        std::fs::create_dir_all(absolute.parent().context("无法确定下载目录")?)?;
        let temp = Temp(absolute.with_file_name(format!(
            "{}.downloading",
            absolute.file_name().unwrap().to_string_lossy()
        )));
        remove_temp(&temp.0);
        ensure!(!sources.is_empty(), "下载链接为空");
        let mut resume = Resume::default();
        let mut disable_ranges = false;
        let mut last = anyhow::anyhow!("下载失败");
        for attempt in 0..5 {
            cancel.check()?;
            let timeout = Duration::from_secs((60. * 1.5_f64.powi(attempt)).min(180.) as u64);
            let result = cancellable(cancel, async {
                let partial = std::fs::metadata(&temp.0).map(|m| m.len()).unwrap_or(0);
                let mut probed = vec![];
                let any_available = sources.iter().any(|s| !download_policy::cooling(&s.url));
                for source in sources {
                    if any_available && download_policy::cooling(&source.url) {
                        continue;
                    }
                    match self.probe(source, known, partial, timeout).await {
                        Ok(p) => probed.push(p),
                        Err(error) => tracing::debug!(%error,"跳过不可用下载源"),
                    }
                }
                ensure!(!probed.is_empty(), "没有可用下载源");
                let total = known.or_else(|| probed.iter().find_map(|p| p.size));
                if !disable_ranges && partial == 0 && total.is_some_and(|n| n >= 4 * PIECE) {
                    let ranged: Vec<_> = probed
                        .iter()
                        .filter(|p| p.ranges && p.size == total)
                        .cloned()
                        .collect();
                    if !ranged.is_empty() {
                        match self
                            .ranges(&ranged, &temp.0, total.unwrap(), cancel, progress)
                            .await
                            .and_then(|()| validate(&temp.0))
                        {
                            Ok(()) => return Ok(()),
                            Err(error) => {
                                cancel.check()?;
                                tracing::warn!(%error,"分段下载失败，回退单连接");
                                disable_ranges = true;
                                remove_temp(&temp.0);
                                resume = Resume::default();
                            }
                        }
                    }
                }
                let mut errors = vec![];
                let mut retry_after = None;
                let mut validation_failed = false;
                probed.sort_by(|a, b| {
                    let owner = |p: &Probe| u8::from(p.source.url != resume.url);
                    owner(a).cmp(&owner(b)).then_with(|| {
                        download_policy::speed(&b.source.url)
                            .unwrap_or(1024. * 1024.)
                            .total_cmp(
                                &download_policy::speed(&a.source.url).unwrap_or(1024. * 1024.),
                            )
                    })
                });
                for p in &probed {
                    let started = Instant::now();
                    match self
                        .stream(p, &temp.0, &mut resume, cancel, progress, timeout)
                        .await
                    {
                        Ok(()) => match validate(&temp.0) {
                            Ok(()) => {
                                download_policy::success(
                                    &p.source.url,
                                    std::fs::metadata(&temp.0)?.len(),
                                    started.elapsed(),
                                );
                                return Ok(());
                            }
                            Err(e) => {
                                validation_failed = true;
                                download_policy::failure(&p.source.url, false, None);
                                remove_temp(&temp.0);
                                resume = Resume::default();
                                errors.push(e.to_string());
                            }
                        },
                        Err(e) => {
                            cancel.check()?;
                            if probed.len() > 1 || resume.identity.is_none() {
                                remove_temp(&temp.0);
                                resume = Resume::default();
                            }
                            if let Some(retry) = e.downcast_ref::<RetryAfter>() {
                                retry_after = Some(retry.0);
                            }
                            errors.push(e.to_string());
                        }
                    }
                }
                if let Some(delay) = retry_after {
                    return Err(RetryAfter(delay).into());
                }
                if validation_failed {
                    return Err(ValidationFailure(errors.join("；")).into());
                }
                anyhow::bail!("{}", errors.join("；"))
            })
            .await;
            match result {
                Ok(()) => {
                    std::fs::rename(&temp.0, &absolute)?;
                    return Ok(());
                }
                Err(error) => {
                    cancel.check()?;
                    if error.downcast_ref::<ValidationFailure>().is_some() {
                        return Err(error);
                    }
                    last = error;
                }
            }
            if attempt < 4 {
                cancellable(cancel, async {
                    let jitter = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)?
                        .subsec_nanos() as u64
                        % 251;
                    let delay = last
                        .downcast_ref::<RetryAfter>()
                        .map(|r| r.0)
                        .unwrap_or((500 * (1 << attempt)).min(10000) + jitter);
                    tokio::time::sleep(Duration::from_millis(delay)).await;
                    Ok(())
                })
                .await?;
            }
        }
        Err(last)
    }
}
