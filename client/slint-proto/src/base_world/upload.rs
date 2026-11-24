//! `BaseWorldUploadService`: creates a generated template, or packs a world folder into
//! `world.tar.zst` and uploads it in parts. Runs inside a launcher task worker.
use super::api::{CreateDto, UploadStatus, WorldApi};
use super::{MAX_SIZE, validate_level_type, validate_name};
use crate::download::cancellable;
use crate::packs::human_file_size;
use crate::save_import::upload;
use crate::tasks::Context;
use anyhow::{Context as _, Result, anyhow, bail, ensure};
use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

const MAX_ENTRIES: usize = 100_000;

/// `BaseWorldUploadRequest`, already trimmed as `uploadTask` does.
#[derive(Clone, Debug, PartialEq)]
pub struct Request {
    /// `None` creates a template the host generates on its first start.
    pub directory: Option<PathBuf>,
    pub name: String,
    pub level_type: String,
    pub generator_settings: Option<String>,
}

impl Request {
    pub fn new(directory: Option<PathBuf>, name: &str, level_type: &str, generator_settings: &str) -> Self {
        Self {
            directory: directory.map(|d| std::path::absolute(&d).unwrap_or(d)),
            name: name.to_owned(),
            level_type: level_type.trim().to_owned(),
            generator_settings: Some(generator_settings.trim().to_owned()).filter(|s| !s.is_empty()),
        }
    }
    pub fn title(&self) -> String {
        format!("上传地图模板 {}", self.name)
    }
}

/// Where and how the task works.
#[derive(Clone, Debug)]
pub struct Env {
    /// `<data>/pack-proc/baseworld-uploads`, as Kotlin `ClientDirs.packProcDir`.
    pub temp_root: PathBuf,
    pub max_size: u64,
    pub upload: upload::Options,
}

impl Env {
    pub fn new(data_dir: &Path) -> Self {
        Self { temp_root: data_dir.join("pack-proc").join("baseworld-uploads"), max_size: MAX_SIZE, upload: upload::Options::default() }
    }
}

/// Starts the upload as a background launcher task; returns its run id.
pub fn submit<A: WorldApi>(tasks: &crate::tasks::Manager, runtime: &tokio::runtime::Handle, api: Arc<A>, request: Request, key: String, env: Env) -> String {
    let handle = runtime.clone();
    tasks.submit(runtime, request.title(), key, move |context| run(api, &request, &env, &handle, &context))
}

#[derive(Clone, Debug, PartialEq)]
struct Entry {
    directory: bool,
    size: u64,
    modified: Option<SystemTime>,
}

#[derive(Debug, PartialEq)]
struct Snapshot {
    entries: BTreeMap<String, Entry>,
    total_bytes: u64,
    file_count: usize,
}

fn read_entry(path: &Path, relative: &str) -> Result<Entry> {
    let meta = std::fs::symlink_metadata(path).with_context(|| format!("读取地图文件失败：{relative}"))?;
    ensure!(!meta.file_type().is_symlink(), "世界目录包含不支持的符号链接：{relative}");
    ensure!(meta.is_dir() || meta.is_file(), "世界目录包含不支持的特殊文件：{relative}");
    Ok(Entry { directory: meta.is_dir(), size: if meta.is_dir() { 0 } else { meta.len() }, modified: meta.modified().ok() })
}

/// `scanSource`: every entry under `root`, without following links.
fn scan(root: &Path, max_size: u64, context: &Context) -> Result<Snapshot> {
    let mut entries = BTreeMap::new();
    let (mut total_bytes, mut count) = (0u64, 0usize);
    let mut stack = vec![root.to_owned()];
    while let Some(dir) = stack.pop() {
        for item in std::fs::read_dir(&dir).with_context(|| format!("读取地图文件夹失败：{}", dir.display()))? {
            context.cancel.check()?;
            let path = item?.path();
            count += 1;
            ensure!(count <= MAX_ENTRIES, "地图文件数量超过限制");
            let parts: Vec<String> = path.strip_prefix(root)?.components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect();
            let relative = parts.join("/");
            ensure!(
                !relative.trim().is_empty() && parts.iter().all(|p| !p.is_empty() && !p.contains(':') && !p.contains('\\')),
                "世界目录包含服务器不支持的路径：{relative}"
            );
            let entry = read_entry(&path, &relative)?;
            if entry.directory {
                stack.push(path);
            } else {
                total_bytes = total_bytes.checked_add(entry.size).context("地图文件总大小超过限制")?;
                ensure!(total_bytes <= max_size, "地图文件总大小超过{}限制", human_file_size(max_size));
            }
            entries.insert(relative, entry);
        }
    }
    let level = entries.get("level.dat");
    ensure!(level.is_some_and(|l| !l.directory && l.size > 0), "世界目录缺少非空level.dat");
    let file_count = entries.values().filter(|e| !e.directory).count();
    Ok(Snapshot { entries, total_bytes, file_count })
}

/// `writeArchive`: entries sorted by path; a file that changes while it is read stops the upload.
fn pack(root: &Path, snapshot: &Snapshot, archive: &Path, context: &Context) -> Result<()> {
    let file = std::fs::File::create(archive).context("创建地图压缩包失败")?;
    let mut builder = tar::Builder::new(zstd::stream::write::Encoder::new(file, 9)?);
    let (mut done_bytes, mut done_files) = (0u64, 0usize);
    let fraction = |done: u64| 0.05 + 0.25 * if snapshot.total_bytes == 0 { 1. } else { done as f32 / snapshot.total_bytes as f32 };
    for (relative, entry) in &snapshot.entries {
        context.cancel.check()?;
        let time = entry.modified.and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0);
        if entry.directory {
            crate::rdipack2::append(&mut builder, &format!("{relative}/"), true, 0, time, std::io::empty())?;
            continue;
        }
        context.progress(format!("正在打包 {relative}"), Some(fraction(done_bytes)));
        let path = root.join(relative);
        let source = std::fs::File::open(&path).with_context(|| format!("读取地图文件失败：{relative}"))?;
        let reader = crate::rdipack2::CancelRead { inner: source, ctx: context }.take(entry.size);
        let appended = crate::rdipack2::append(&mut builder, relative, false, entry.size, time, reader);
        context.cancel.check()?;
        appended.with_context(|| format!("打包地图文件失败：{relative}"))?;
        let after = read_entry(&path, relative)?;
        ensure!(!after.directory && after.size == entry.size && after.modified == entry.modified, "地图文件在打包期间发生变化：{relative}");
        done_bytes += entry.size;
        done_files += 1;
        context.progress(format!("正在打包 {relative} ({done_files}/{})", snapshot.file_count), Some(fraction(done_bytes)));
    }
    builder.into_inner()?.finish()?.sync_all()?;
    Ok(())
}

fn sha1_file(file: &Path, context: &Context) -> Result<String> {
    use sha1::Digest;
    let mut digest = sha1::Sha1::new();
    let mut input = std::fs::File::open(file)?;
    let mut buffer = vec![0; 1024 * 1024];
    loop {
        context.cancel.check()?;
        let read = input.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(digest.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// `upload` inside a task worker. A template created on the server is deleted again when the
/// upload fails before it was submitted; the temp dir is always removed.
pub fn run<A: WorldApi>(api: Arc<A>, request: &Request, env: &Env, runtime: &tokio::runtime::Handle, context: &Context) -> Result<()> {
    validate_name(&request.name).map_err(|e| anyhow!(e))?;
    validate_level_type(&request.level_type).map_err(|e| anyhow!(e))?;
    let cancel = &context.cancel;
    let create = |size: u64, generated: bool| CreateDto {
        name: request.name.clone(),
        level_type: request.level_type.clone(),
        generator_settings: request.generator_settings.clone(),
        size,
        generated,
    };
    let Some(directory) = &request.directory else {
        context.progress("正在创建生成地图模板", Some(0.));
        runtime.block_on(cancellable(cancel, api.create(&create(0, true))))?;
        context.progress("地图模板已创建", Some(1.));
        context.finish("地图模板已创建");
        return Ok(());
    };
    let source = std::fs::canonicalize(directory).map_err(|_| anyhow!("世界目录无效"))?;
    ensure!(std::fs::symlink_metadata(directory).is_ok_and(|m| m.is_dir()), "世界目录无效");
    std::fs::create_dir_all(&env.temp_root).with_context(|| format!("创建临时目录失败: {}", env.temp_root.display()))?;
    let temp_root = std::fs::canonicalize(&env.temp_root)?;
    ensure!(!source.starts_with(&temp_root) && !temp_root.starts_with(&source), "地图模板临时目录不能与世界目录重叠");
    let temporary = temp_root.join(format!("baseworld-upload-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir(&temporary).with_context(|| format!("创建临时目录失败: {}", temporary.display()))?;
    let mut world_id: Option<String> = None;
    let mut submitted = false;
    let result = steps(&api, env, runtime, context, &source, &temporary, &create, &mut world_id, &mut submitted);
    if result.is_err()
        && let Some(id) = world_id.as_deref()
        && !submitted
    {
        match runtime.block_on(async { tokio::time::timeout(Duration::from_secs(10), api.delete(id)).await }) {
            Ok(Ok(())) => {}
            Ok(Err(error)) => tracing::error!(error = %format!("{error:#}"), world = id, "清理失败的地图模板元数据失败"),
            Err(_) => tracing::error!(world = id, "清理失败的地图模板元数据超时"),
        }
    }
    if let Err(error) = std::fs::remove_dir_all(&temporary) {
        tracing::warn!(%error, path = %temporary.display(), "清理地图模板上传临时目录失败");
    }
    result
}

#[allow(clippy::too_many_arguments)]
fn steps<A: WorldApi>(
    api: &Arc<A>,
    env: &Env,
    runtime: &tokio::runtime::Handle,
    context: &Context,
    source: &Path,
    temporary: &Path,
    create: &dyn Fn(u64, bool) -> CreateDto,
    world_id: &mut Option<String>,
    submitted: &mut bool,
) -> Result<()> {
    let cancel = &context.cancel;
    context.progress("正在扫描地图文件", Some(0.));
    let initial = scan(source, env.max_size, context)?;
    let archive = temporary.join("world.tar.zst");
    pack(source, &initial, &archive, context)?;
    cancel.check()?;
    let after = scan(source, env.max_size, context)?;
    ensure!(after == initial, "世界目录在打包期间发生变化，请重试");
    let size = std::fs::metadata(&archive)?.len();
    ensure!((1..=env.max_size).contains(&size), "世界压缩包大小超过{}限制", human_file_size(env.max_size));
    context.progress("正在校验地图压缩包", Some(0.3));
    let sha1 = sha1_file(&archive, context)?;

    let world = runtime.block_on(cancellable(cancel, api.create(&create(initial.total_bytes, false))))?;
    *world_id = Some(world.id.clone());
    let session = runtime.block_on(cancellable(cancel, api.create_upload(&world.id, size, &sha1)))?;
    let descriptor = upload::Descriptor {
        size: session.size,
        part_size: session.part_size,
        part_count: session.part_count,
        uploaded_parts: session.uploaded_parts.clone(),
    };
    let (part_api, world_part, upload_id) = (api.clone(), world.id.clone(), session.id.clone());
    let progress_context = context.clone();
    let part_count = session.part_count;
    runtime.block_on(cancellable(
        cancel,
        upload::upload(
            &archive,
            &descriptor,
            &env.upload,
            move |index, bytes, sha1| {
                let (api, world, upload) = (part_api.clone(), world_part.clone(), upload_id.clone());
                async move { api.upload_part(&world, &upload, index, bytes, sha1).await }
            },
            move |done, parts| {
                let fraction = 0.35 + 0.6 * done as f32 / size.max(1) as f32;
                progress_context.progress(format!("正在上传分片{parts}/{part_count}"), Some(fraction));
            },
        ),
    ))?;
    cancel.check()?;
    *submitted = true;
    context.progress("正在提交地图模板校验", Some(0.99));
    let completion = runtime.block_on(cancellable(cancel, api.complete(&world.id, &session.id)))?;
    context.progress("地图模板已提交", Some(1.));
    match completion.status {
        UploadStatus::Queued | UploadStatus::Processing => context.finish("上传已提交，校验结果将通过邮件通知"),
        UploadStatus::Ready => context.finish("地图模板已准备好，请查看邮件"),
        UploadStatus::Failed => bail!("{}", completion.error_message.unwrap_or_else(|| "地图模板校验失败".into())),
        UploadStatus::Uploading => bail!("服务器未接受地图模板上传完成请求"),
    }
    Ok(())
}
