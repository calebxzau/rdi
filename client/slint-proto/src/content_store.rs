//! Shared dlc layout. Never publish an unverified cache entry or overwrite a different target.
use crate::{
    digests::{Algorithm, Digest, hash},
    download::{Downloader, Progress, Source, cancellable},
    tasks::Cancellation,
};
use anyhow::{Context as _, Result, ensure};
use std::{
    collections::{BTreeSet, HashMap},
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock, Weak},
};
use tokio::sync::Semaphore;
#[derive(Clone)]
pub struct Request {
    pub id: String,
    pub relative_path: String,
    pub size: Option<u64>,
    pub digests: Vec<Digest>,
    pub sources: Vec<Source>,
}
#[derive(Clone)]
pub struct Store {
    pub root: PathBuf,
    pub downloader: Downloader,
}
static LOCKS: OnceLock<Mutex<HashMap<String, Weak<tokio::sync::Mutex<()>>>>> = OnceLock::new();
static NETWORK: Semaphore = Semaphore::const_new(32);
static VERIFY: Semaphore = Semaphore::const_new(32);
static COPY: Semaphore = Semaphore::const_new(32);
fn locks(digests: &[Digest], target: Option<&Path>) -> Vec<Arc<tokio::sync::Mutex<()>>> {
    let mut keys: BTreeSet<_> = digests
        .iter()
        .map(|d| format!("{}:{}", d.algorithm.suffix(), d.value))
        .collect();
    if let Some(target) = target {
        keys.insert(format!("target:{}", target.display()));
    }
    let mut map = LOCKS.get_or_init(Default::default).lock().unwrap();
    map.retain(|_, v| v.strong_count() > 0);
    keys.into_iter()
        .map(|k| {
            if let Some(v) = map.get(&k).and_then(Weak::upgrade) {
                v
            } else {
                let v = Arc::new(tokio::sync::Mutex::new(()));
                map.insert(k, Arc::downgrade(&v));
                v
            }
        })
        .collect()
}
/// U4b2: the lock downloads hold on a digest, so 清除下载缓存 never removes an entry in use.
pub fn digest_lock(digest: &Digest) -> Arc<tokio::sync::Mutex<()>> {
    locks(std::slice::from_ref(digest), None).remove(0)
}
/// U4b2 导入rdipack2: blocking. Puts an already verified file into the cache under each digest
/// it lacks, linked when possible, as `materialize` would after a download. Each entry is
/// written under its digest lock and renamed into place, so readers never see a partial file.
pub fn adopt(root: &Path, file: &Path, digests: &[Digest]) -> Result<()> {
    no_symlink(root)?;
    std::fs::create_dir_all(root.join(".tmp"))?;
    for digest in digests {
        let lock = digest_lock(digest);
        let _guard = lock.blocking_lock();
        let path = root.join(format!("{}.{}", digest.value, digest.algorithm.suffix()));
        no_symlink(&path)?;
        if path.try_exists()? {
            continue;
        }
        let temp = Temp(root.join(".tmp").join(format!("adopt-{}", uuid::Uuid::now_v7())));
        link_or_copy(file, &temp.0)?;
        std::fs::rename(&temp.0, &path)?;
    }
    Ok(())
}
pub fn no_symlink(path: &Path) -> Result<()> {
    let mut current = PathBuf::new();
    for part in std::path::absolute(path)?.components() {
        current.push(part);
        match std::fs::symlink_metadata(&current) {
            Ok(m) => ensure!(
                !m.file_type().is_symlink(),
                "内容路径不能穿过符号链接: {}",
                current.display()
            ),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}
pub fn relative(raw: &str) -> Result<String> {
    let path = raw.replace('\\', "/");
    ensure!(!path.is_empty(), "内容目标路径不能为空");
    ensure!(
        !path.starts_with('/') && !path.contains(':'),
        "内容目标路径不能是绝对路径: {raw}"
    );
    ensure!(
        path.split('/')
            .all(|p| !p.is_empty() && p != "." && p != ".."),
        "内容目标路径包含非法段: {raw}"
    );
    Ok(path)
}
fn verify(path: &Path, request: &Request, cancel: &Cancellation) -> Result<()> {
    let m = std::fs::symlink_metadata(path)?;
    ensure!(
        m.is_file() && !m.file_type().is_symlink(),
        "内容文件不存在: {}",
        path.display()
    );
    ensure!(
        request.size.is_none_or(|n| m.len() == n),
        "内容大小不匹配: {}",
        path.display()
    );
    for d in &request.digests {
        ensure!(
            hash(path, d.algorithm, cancel)? == d.value,
            "内容{}不匹配: {}",
            d.algorithm.suffix(),
            path.display()
        );
    }
    Ok(())
}
fn remove(path: &Path) {
    if let Err(error) = std::fs::remove_file(path)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(%error,"清理内容临时文件失败");
    }
}
struct Temp(PathBuf);
/// A verified cache entry, or a verified temporary file when the cache cannot be written.
/// A temporary file is deleted when this is dropped.
pub struct Fetched {
    pub path: PathBuf,
    _temporary: Option<Temp>,
}
impl Drop for Temp {
    fn drop(&mut self) {
        remove(&self.0);
    }
}
fn link_or_copy(source: &Path, target: &Path) -> Result<()> {
    if let Err(error) = std::fs::hard_link(source, target) {
        tracing::debug!(%error,"硬链接失败，复制客户端内容");
        std::fs::copy(source, target)?;
    }
    Ok(())
}
impl Store {
    pub fn cache_path(&self, d: &Digest) -> PathBuf {
        self.root
            .join(format!("{}.{}", d.value, d.algorithm.suffix()))
    }
    pub async fn materialize(
        &self,
        requests: &[Request],
        root: &Path,
        cancel: &Cancellation,
        progress: &(dyn Fn(Progress) + Sync),
    ) -> Result<()> {
        let requests: Vec<Request> = requests
            .iter()
            .map(|r| {
                let mut r = r.clone();
                r.relative_path = relative(&r.relative_path)?;
                r.digests = r
                    .digests
                    .iter()
                    .map(|d| Digest::new(d.algorithm, &d.value))
                    .collect::<Result<_>>()?;
                Ok(r)
            })
            .collect::<Result<_>>()?;
        let mut ids = BTreeSet::new();
        let mut targets = HashMap::new();
        for r in &requests {
            ensure!(!r.id.trim().is_empty(), "内容id不能为空");
            ensure!(ids.insert(&r.id), "内容id重复: {}", r.id);
            let path = relative(&r.relative_path)?;
            if let Some(old) = targets.insert(path, r) {
                ensure!(
                    old.size == r.size && old.digests == r.digests,
                    "同一目标路径对应不同内容: {}",
                    r.relative_path
                );
            }
            let mut algorithms = BTreeSet::new();
            for d in &r.digests {
                Digest::new(d.algorithm, &d.value)?;
                ensure!(
                    algorithms.insert(d.algorithm),
                    "同一内容摘要算法重复: {}",
                    r.id
                );
            }
        }
        no_symlink(root)?;
        std::fs::create_dir_all(root)?;
        no_symlink(root)?;
        let states = Mutex::new(vec![
            Progress {
                bytes: 0,
                total: None,
                speed: 0.
            };
            requests.len()
        ]);
        let indexed: Vec<_> = requests.iter().enumerate().collect();
        // Bounded batches run concurrently; per-digest locks coalesce overlapping requests.
        for chunk in indexed.chunks(32) {
            let mut pending: Vec<_> = chunk
                .iter()
                .map(|(index, r)| {
                    let states = &states;
                    Box::pin(async move {
                        let emit = |value: Progress| {
                            let mut states = states.lock().unwrap();
                            states[*index] = value;
                            let total = states
                                .iter()
                                .map(|s| s.total)
                                .collect::<Option<Vec<_>>>()
                                .map(|s| s.into_iter().sum());
                            progress(Progress {
                                bytes: states.iter().map(|s| s.bytes).sum(),
                                total,
                                speed: states.iter().map(|s| s.speed).sum(),
                            });
                        };
                        self.one(r, root, cancel, &emit).await?;
                        let bytes = r
                            .size
                            .unwrap_or(std::fs::metadata(root.join(&r.relative_path))?.len());
                        emit(Progress {
                            bytes,
                            total: Some(bytes),
                            speed: 0.,
                        });
                        Ok::<(), anyhow::Error>(())
                    })
                })
                .collect();
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
        }
        Ok(())
    }
    /// Cache hit, or download, verify and commit. The caller holds the digest locks.
    async fn obtain(
        &self,
        r: &Request,
        c: &Cancellation,
        progress: &(dyn Fn(Progress) + Sync),
    ) -> Result<(PathBuf, Option<Temp>)> {
        let mut cached = None;
        for d in &r.digests {
            let path = self.cache_path(d);
            if path.try_exists()? {
                match self.checked(&path, r, c).await {
                    Ok(()) => {
                        cached = Some(path);
                        break;
                    }
                    Err(error) => {
                        c.check()?;
                        tracing::warn!(%error,"忽略损坏内容缓存");
                    }
                }
            }
        }
        let mut temporary = None;
        let mut commit_digests = r.digests.clone();
        let source = if let Some(path) = cached {
            path
        } else {
            no_symlink(&self.root)?;
            let temporary_root = match std::fs::create_dir_all(self.root.join(".tmp")) {
                Ok(()) => {
                    no_symlink(&self.root.join(".tmp"))?;
                    self.root.join(".tmp")
                }
                Err(error) => {
                    tracing::warn!(%error,"客户端内容缓存不可写，回退系统临时目录");
                    std::env::temp_dir()
                }
            };
            let tmp =
                Temp(temporary_root.join(format!("rdi-content-{}.tmp", uuid::Uuid::now_v7())));
            let _permit = cancellable(c, async { Ok(NETWORK.acquire().await?) }).await?;
            self.downloader
                .download(
                    &r.sources,
                    &tmp.0,
                    r.size,
                    c,
                    &|path| verify(path, r, c),
                    progress,
                )
                .await?;
            self.checked(&tmp.0, r, c).await?;
            if !commit_digests
                .iter()
                .any(|d| d.algorithm == Algorithm::Sha1)
            {
                commit_digests.push(Digest::new(
                    Algorithm::Sha1,
                    &hash(&tmp.0, Algorithm::Sha1, c)?,
                )?);
            }
            let primary = commit_digests
                .iter()
                .min_by_key(|d| match d.algorithm {
                    Algorithm::Sha256 => 0,
                    Algorithm::Sha1 => 1,
                    _ => 2,
                })
                .unwrap();
            let path = self.cache_path(primary);
            no_symlink(&path)?;
            let committed = (|| -> Result<()> {
                if path.try_exists()? && verify(&path, r, c).is_ok() {
                    remove(&tmp.0);
                } else {
                    std::fs::rename(&tmp.0, &path)?;
                }
                Ok(())
            })();
            match committed {
                Ok(()) => path,
                Err(error) => {
                    c.check()?;
                    tracing::warn!(%error,"客户端内容缓存不可写，使用已校验临时文件");
                    let path = tmp.0.clone();
                    temporary = Some(tmp);
                    path
                }
            }
        };
        if temporary.is_none() {
            for digest in &commit_digests {
                let alias = self.cache_path(digest);
                if alias == source {
                    continue;
                }
                let alias_result = (|| -> Result<()> {
                    no_symlink(&alias)?;
                    if !alias.try_exists()? || verify(&alias, r, c).is_err() {
                        c.check()?;
                        let temp = Temp(
                            self.root
                                .join(".tmp")
                                .join(format!("alias-{}", uuid::Uuid::now_v7())),
                        );
                        std::fs::create_dir_all(temp.0.parent().unwrap())?;
                        link_or_copy(&source, &temp.0)?;
                        std::fs::rename(&temp.0, &alias)?;
                    }
                    Ok(())
                })();
                if let Err(error) = alias_result {
                    c.check()?;
                    tracing::warn!(%error,"无法创建内容摘要别名");
                }
            }
        }
        Ok((source, temporary))
    }
    /// The verified cache path for one request, with the same single flight, digest check and
    /// commit as `materialize`, but without linking it anywhere.
    pub async fn fetch(
        &self,
        request: &Request,
        cancel: &Cancellation,
        progress: &(dyn Fn(Progress) + Sync),
    ) -> Result<Fetched> {
        ensure!(!request.id.trim().is_empty(), "内容id不能为空");
        ensure!(!request.digests.is_empty(), "内容缺少校验信息: {}", request.id);
        let mut r = request.clone();
        r.digests = r
            .digests
            .iter()
            .map(|d| Digest::new(d.algorithm, &d.value))
            .collect::<Result<_>>()?;
        let mut algorithms = BTreeSet::new();
        for d in &r.digests {
            ensure!(algorithms.insert(d.algorithm), "同一内容摘要算法重复: {}", r.id);
        }
        cancel.check()?;
        let locks = locks(&r.digests, None);
        let mut guards = vec![];
        for lock in &locks {
            guards.push(cancellable(cancel, async { Ok(lock.lock().await) }).await?);
        }
        let (path, temporary) = self.obtain(&r, cancel, progress).await?;
        let bytes = std::fs::metadata(&path)?.len();
        progress(Progress {
            bytes,
            total: Some(bytes),
            speed: 0.,
        });
        Ok(Fetched {
            path,
            _temporary: temporary,
        })
    }
    /// Whether a cache file exists for any digest; cheap, without verifying it.
    pub fn has_cached(&self, r: &Request) -> bool {
        r.digests.iter().any(|d| {
            Digest::new(d.algorithm, &d.value)
                .is_ok_and(|d| self.cache_path(&d).is_file())
        })
    }
    async fn checked(&self, path: &Path, r: &Request, c: &Cancellation) -> Result<()> {
        let _permit = cancellable(c, async { Ok(VERIFY.acquire().await?) }).await?;
        verify(path, r, c)
    }
    async fn one(
        &self,
        r: &Request,
        root: &Path,
        c: &Cancellation,
        progress: &(dyn Fn(Progress) + Sync),
    ) -> Result<()> {
        c.check()?;
        let target = root.join(relative(&r.relative_path)?);
        no_symlink(target.parent().unwrap())?;
        let target_link =
            std::fs::symlink_metadata(&target).is_ok_and(|m| m.file_type().is_symlink());
        if !target_link && target.try_exists()? {
            self.checked(&target, r, c)
                .await
                .map_err(|_| anyhow::anyhow!("目标文件内容不同，拒绝覆盖: {}", target.display()))?;
            return Ok(());
        }
        let locks = locks(&r.digests, Some(&target));
        let mut guards = vec![];
        for lock in &locks {
            guards.push(cancellable(c, async { Ok(lock.lock().await) }).await?);
        }
        let (source, temporary) = self
            .obtain(r, c, progress)
            .await
            .with_context(|| format!("下载失败: {}", r.relative_path))?;
        let _permit = cancellable(c, async { Ok(COPY.acquire().await?) }).await?;
        c.check()?;
        no_symlink(target.parent().unwrap())?;
        std::fs::create_dir_all(target.parent().unwrap())?;
        no_symlink(target.parent().unwrap())?;
        if std::fs::symlink_metadata(&target).is_ok_and(|m| m.file_type().is_symlink()) {
            // Remove only the link itself; never read or modify its referent.
            std::fs::remove_file(&target)?;
            if let Err(error) = link_or_copy(&source, &target).and_then(|()| verify(&target, r, c))
            {
                remove(&target);
                return Err(error);
            }
        } else if target.try_exists()? {
            self.checked(&target, r, c).await?;
        } else if let Err(error) =
            link_or_copy(&source, &target).and_then(|()| verify(&target, r, c))
        {
            remove(&target);
            return Err(error);
        }
        drop(temporary);
        Ok(())
    }
}
