//! Kotlin `ChunkedUploader`: uploads the parts the server is still missing, with bounded
//! parallelism and retries. The caller supplies the part request; cancellation is the caller's
//! `crate::download::cancellable`, which drops this future and with it every running part.
use super::api::Rejected;
use anyhow::{Context, Result, ensure};
use sha1::{Digest, Sha1};
use std::future::Future;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::Duration;

pub const MAX_PART_SIZE: u64 = 4 * 1024 * 1024;
/// `ChunkedUploadErrorCodes.PART_RETRYABLE`.
pub const PART_RETRYABLE: &str = "chunked-upload-part-retryable";

/// What the server knows about the upload (`ChunkedUploadDescriptor`).
#[derive(Clone, Debug)]
pub struct Descriptor {
    pub size: u64,
    pub part_size: u64,
    pub part_count: u64,
    pub uploaded_parts: Vec<u64>,
}

#[derive(Clone, Debug)]
pub struct Options {
    pub parallelism: usize,
    pub retries: u32,
    pub retry_delay: Duration,
    pub max_retry_delay: Duration,
}

impl Default for Options {
    fn default() -> Self {
        Self { parallelism: 8, retries: 6, retry_delay: Duration::from_secs(1), max_retry_delay: Duration::from_secs(30) }
    }
}

/// Transport failures and server errors marked retryable are retried; other server rejections
/// are final (`isRetryableChunkedUploadError`).
pub fn is_retryable(error: &anyhow::Error) -> bool {
    match error.downcast_ref::<Rejected>() {
        Some(rejected) => rejected.error_code.as_deref() == Some(PART_RETRYABLE),
        None => true,
    }
}

fn validate(file: &Path, d: &Descriptor) -> Result<()> {
    let meta = std::fs::symlink_metadata(file).ok().filter(|m| m.is_file()).context("分片上传文件无效")?;
    ensure!(d.size > 0 && d.size == meta.len(), "服务器返回的上传文件大小不匹配");
    ensure!((1..=MAX_PART_SIZE).contains(&d.part_size) && d.part_count > 0, "服务器返回的上传分片信息无效");
    ensure!(d.part_count == (d.size - 1) / d.part_size + 1, "服务器返回的分片数量不匹配");
    ensure!(d.uploaded_parts.iter().all(|p| *p < d.part_count), "服务器返回的已上传分片序号无效");
    Ok(())
}

fn part_length(d: &Descriptor, index: u64) -> u64 {
    d.part_size.min(d.size - index * d.part_size)
}

fn read_part(file: &Path, offset: u64, length: u64) -> Result<Vec<u8>> {
    let mut input = std::fs::File::open(file).context("读取压缩包分片失败")?;
    input.seek(SeekFrom::Start(offset)).context("读取压缩包分片失败")?;
    let mut bytes = vec![0; length as usize];
    input.read_exact(&mut bytes).context("读取压缩包分片失败")?;
    Ok(bytes)
}

fn hex_sha1(bytes: &[u8]) -> String {
    Sha1::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// Uploads every part of `file` that `descriptor` does not list as uploaded. `on_progress`
/// receives the acknowledged bytes and parts after each completed part, and once at the start.
pub async fn upload<F, Fut>(
    file: &Path,
    descriptor: &Descriptor,
    options: &Options,
    upload_part: F,
    on_progress: impl Fn(u64, u64) + Send + Sync + 'static,
) -> Result<()>
where
    F: Fn(u64, Vec<u8>, String) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<()>> + Send + 'static,
{
    ensure!((1..=8).contains(&options.parallelism), "分片并发数必须在1到8之间");
    validate(file, descriptor)?;
    let acknowledged: std::collections::BTreeSet<u64> = descriptor.uploaded_parts.iter().copied().collect();
    let missing: Vec<u64> = (0..descriptor.part_count).filter(|i| !acknowledged.contains(i)).collect();
    let done_bytes = Arc::new(AtomicU64::new(acknowledged.iter().map(|i| part_length(descriptor, *i)).sum()));
    let done_parts = Arc::new(AtomicU64::new(acknowledged.len() as u64));
    on_progress(done_bytes.load(Ordering::Acquire), done_parts.load(Ordering::Acquire));
    if missing.is_empty() {
        return Ok(());
    }
    struct Shared<F, P> {
        file: PathBuf,
        descriptor: Descriptor,
        options: Options,
        missing: Vec<u64>,
        next: AtomicUsize,
        upload_part: F,
        on_progress: P,
    }
    let shared = Arc::new(Shared {
        file: file.to_owned(),
        descriptor: descriptor.clone(),
        options: options.clone(),
        next: AtomicUsize::new(0),
        missing,
        upload_part,
        on_progress,
    });
    let mut workers = tokio::task::JoinSet::new();
    for _ in 0..shared.options.parallelism.min(shared.missing.len()) {
        let (shared, done_bytes, done_parts) = (shared.clone(), done_bytes.clone(), done_parts.clone());
        workers.spawn(async move {
            loop {
                let position = shared.next.fetch_add(1, Ordering::AcqRel);
                let Some(&index) = shared.missing.get(position) else {
                    return Ok::<(), anyhow::Error>(());
                };
                let length = part_length(&shared.descriptor, index);
                let (file, offset) = (shared.file.clone(), index * shared.descriptor.part_size);
                let bytes = tokio::task::spawn_blocking(move || read_part(&file, offset, length)).await??;
                let sha1 = hex_sha1(&bytes);
                let mut retries = 0;
                let mut delay = shared.options.retry_delay.min(shared.options.max_retry_delay);
                loop {
                    match (shared.upload_part)(index, bytes.clone(), sha1.clone()).await {
                        Ok(()) => break,
                        Err(error) if is_retryable(&error) && retries < shared.options.retries => {
                            tracing::warn!(index, retries, error = %format!("{error:#}"), "上传分片失败，稍后重试");
                            retries += 1;
                            tokio::time::sleep(delay).await;
                            delay = if delay > shared.options.max_retry_delay / 2 { shared.options.max_retry_delay } else { delay * 2 };
                        }
                        Err(error) => return Err(error),
                    }
                }
                let bytes_now = done_bytes.fetch_add(length, Ordering::AcqRel) + length;
                let parts_now = done_parts.fetch_add(1, Ordering::AcqRel) + 1;
                (shared.on_progress)(bytes_now, parts_now);
            }
        });
    }
    while let Some(joined) = workers.join_next().await {
        // Dropping the set on return aborts the other workers.
        joined.context("上传任务意外中断")??;
    }
    Ok(())
}
