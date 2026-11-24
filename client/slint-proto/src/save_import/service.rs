//! Kotlin `HostSaveImportService` and the gateway parts of `HostSaveImportViewModel`: the
//! preparation lookups, the background import task (remap the marked copy into a temp dir, pack
//! it, upload it in parts, wait for the server) and the marking launch.
use super::api::{CreateDto, ImportApi, ImportStatus, MemberWarning, MsidMatchVo, PrecheckDto};
use super::marking::{MarkingRecord, MarkingService, player_error};
use super::upload;
use crate::download::cancellable;
use crate::mc_launch::Account;
use crate::play::Admitted;
use crate::play::room::{self, LaunchOverrides, MarkingStart, RoomDeps};
use crate::tasks::Context;
use anyhow::{Context as _, Result, anyhow, bail};
use rdi_anvilrw::{
    RemapError, SYNC_CHUNKS_RELATIVE_PATH, SaveLoaderDetection, SavePlayerKind, SaveScanResult, SaveSnapshot, SaveSourceLock, SaveUuidRemapper,
    UnhandledFile, parse_sync_chunks, scan_save,
};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};
use uuid::Uuid;

/// The largest processed save the server accepts.
pub const MAX_UPLOAD_BYTES: u64 = 2 * 1024 * 1024 * 1024;

/// What the server and Mojang say about the players of a save, for the mapping step.
#[derive(Clone, Debug)]
pub struct Preparation {
    pub scan: SaveScanResult,
    /// Current Mojang names of online players.
    pub mojang_names: HashMap<Uuid, String>,
    /// Online players whose Microsoft profile is bound to an RDI account.
    pub msid_matches: HashMap<Uuid, MsidMatchVo>,
    /// RDI players of the save whose accounts still exist.
    pub existing_rdi_players: HashSet<Uuid>,
}

/// Everything the owner confirmed; the import task runs from this alone.
#[derive(Clone, Debug)]
pub struct ImportPlan {
    pub host_id: String,
    pub host_name: String,
    pub record: MarkingRecord,
    pub mapping: HashMap<Uuid, Uuid>,
    /// ObjectIds of the accounts that become members.
    pub member_player_ids: Vec<String>,
    pub mc_version_name: String,
    /// `forge`, `neoforge` or `fabric` ([`loader_name`]).
    pub loader: String,
    /// Membership warnings the owner saw; new ones stop the import before upload.
    pub confirmed_warnings: Vec<MemberWarning>,
}

/// Where and how fast the import task works.
#[derive(Clone, Debug)]
pub struct ImportEnv {
    /// `<data>/pack-proc/save-imports`, as Kotlin `ClientDirs.packProcDir`.
    pub temp_root: PathBuf,
    pub poll_interval: Duration,
    pub poll_timeout: Duration,
    pub upload: upload::Options,
}

impl ImportEnv {
    pub fn new(data_dir: &Path) -> Self {
        Self {
            temp_root: data_dir.join("pack-proc").join("save-imports"),
            poll_interval: Duration::from_secs(3),
            poll_timeout: Duration::from_secs(60 * 60),
            upload: upload::Options::default(),
        }
    }
}

/// `SaveLoaderDetection.toModLoader`, as `ModLoader` serializes.
pub fn loader_name(detection: SaveLoaderDetection) -> Result<&'static str> {
    Ok(match detection {
        SaveLoaderDetection::Forge => "forge",
        SaveLoaderDetection::NeoForge => "neoforge",
        SaveLoaderDetection::FabricHistory => "fabric",
        SaveLoaderDetection::Vanilla => bail!("暂不支持导入原版存档"),
        SaveLoaderDetection::Unknown => bail!("无法识别该存档使用的加载器"),
        SaveLoaderDetection::Conflicting => bail!("该存档的加载器记录互相冲突"),
    })
}

/// `UUID.toObjectId`: the first twelve bytes of an ObjectId-derived UUID, whose last four are zero.
pub fn object_id_of(uuid: Uuid) -> Result<String> {
    let bytes = uuid.as_bytes();
    if bytes[12..].iter().any(|b| *b != 0) {
        bail!("UUID {uuid} is not an ObjectId-derived UUID: trailing bytes must be zero");
    }
    Ok(bytes[..12].iter().map(|b| format!("{b:02x}")).collect())
}

/// The scan of the copy and the lookups the mapping step needs (`prepare`).
pub async fn prepare<A: ImportApi>(api: &A, marking: &MarkingService, record: &MarkingRecord) -> Result<Preparation> {
    let (m, r) = (marking.clone(), record.clone());
    let actual = tokio::task::spawn_blocking(move || -> Result<SaveScanResult> {
        m.check_identity(&r)?;
        scan_save(&r.copy()).map_err(player_error)
    })
    .await??;
    let original_owner = if record.identity_version >= 1 {
        record.original_owner_uuid.as_deref().map(Uuid::parse_str).transpose().map_err(|_| anyhow!("副本角色记录不一致，请重新创建副本"))?
    } else {
        actual.singleplayer_host_uuid
    };
    // Keep the scan's actual Data.Player identity for remapping, but show the original owner badge.
    let mut scan = actual;
    for player in &mut scan.players {
        player.is_singleplayer_host = Some(player.uuid) == original_owner;
    }
    let online: Vec<Uuid> = scan.players.iter().filter(|p| p.kind == SavePlayerKind::Online).map(|p| p.uuid).collect();
    let mut names = HashMap::new();
    for uuid in &online {
        match api.mojang_name(*uuid).await {
            Ok(name) => {
                names.insert(*uuid, name);
            }
            Err(error) => tracing::warn!(error = %format!("{error:#}"), %uuid, "查询正版玩家名失败"),
        }
    }
    let mut matches = HashMap::new();
    for found in api.msid_match(&online).await? {
        if let Ok(uuid) = Uuid::parse_str(&found.msid) {
            matches.insert(uuid, found);
        }
    }
    let rdi: Vec<Uuid> = scan.players.iter().filter(|p| p.kind == SavePlayerKind::Rdi).map(|p| p.uuid).collect();
    let ids = rdi.iter().map(|u| object_id_of(*u)).collect::<Result<Vec<_>>>()?;
    let existing = api.existing_accounts(&ids).await?;
    let existing_rdi_players = rdi.into_iter().zip(ids).filter(|(_, id)| existing.contains(id)).map(|(u, _)| u).collect();
    Ok(Preparation { scan, mojang_names: names, msid_matches: matches, existing_rdi_players })
}

/// The task title and its dedupe key; a second submit for the same host joins the running import.
pub fn task_title(host_name: &str) -> String {
    format!("导入存档到房间{host_name}")
}
pub fn task_key(host_id: &str) -> String {
    format!("host-save-import:{host_id}")
}

/// Starts the import as a background launcher task; returns its run id.
pub fn submit<A: ImportApi>(
    tasks: &crate::tasks::Manager,
    runtime: &tokio::runtime::Handle,
    api: Arc<A>,
    marking: MarkingService,
    plan: ImportPlan,
    env: ImportEnv,
) -> String {
    let handle = runtime.clone();
    tasks.submit(runtime, task_title(&plan.host_name), task_key(&plan.host_id), move |context| {
        run_import(api, &marking, &plan, &env, &handle, &context)
    })
}

/// `runImport` inside a task worker. The temp dir is always removed; a server session that was
/// created but not completed is cancelled when the import fails.
pub fn run_import<A: ImportApi>(
    api: Arc<A>,
    marking: &MarkingService,
    plan: &ImportPlan,
    env: &ImportEnv,
    runtime: &tokio::runtime::Handle,
    context: &Context,
) -> Result<()> {
    marking.check_identity(&plan.record)?;
    std::fs::create_dir_all(&env.temp_root).with_context(|| format!("创建临时目录失败: {}", env.temp_root.display()))?;
    let temporary = env.temp_root.join(format!("import-{}", Uuid::now_v7()));
    std::fs::create_dir(&temporary).with_context(|| format!("创建临时目录失败: {}", temporary.display()))?;
    let mut import_id: Option<String> = None;
    let mut completed = false;
    let result = import_steps(&api, marking, plan, env, runtime, context, &temporary, &mut import_id, &mut completed);
    if result.is_err()
        && let Some(id) = import_id.as_deref()
        && !completed
    {
        let cancelled = runtime.block_on(async { tokio::time::timeout(Duration::from_secs(10), api.cancel(&plan.host_id, id)).await });
        match cancelled {
            Ok(Ok(())) => {}
            Ok(Err(error)) => tracing::warn!(error = %format!("{error:#}"), import = id, "取消存档导入任务失败"),
            Err(_) => tracing::warn!(import = id, "取消存档导入任务超时"),
        }
    }
    if let Err(error) = std::fs::remove_dir_all(&temporary) {
        tracing::warn!(%error, path = %temporary.display(), "清理存档导入临时目录失败");
    }
    result
}

#[allow(clippy::too_many_arguments)]
fn import_steps<A: ImportApi>(
    api: &Arc<A>,
    marking: &MarkingService,
    plan: &ImportPlan,
    env: &ImportEnv,
    runtime: &tokio::runtime::Handle,
    context: &Context,
    temporary: &Path,
    import_id: &mut Option<String>,
    completed: &mut bool,
) -> Result<()> {
    let cancel = &context.cancel;
    let remapped = temporary.join("remapped");
    let unhandled = remap(plan, &remapped, context)?;

    let archive = temporary.join("world.tar.zst");
    pack(&remapped, &archive, context)?;
    let size = std::fs::metadata(&archive)?.len();
    if size > MAX_UPLOAD_BYTES {
        bail!(
            "处理后的存档有{}，超过{}的上限，请减少标记的区块",
            crate::packs::human_file_size(size),
            crate::packs::human_file_size(MAX_UPLOAD_BYTES)
        );
    }
    context.progress("正在校验存档", Some(0.5));
    let sha1 = sha1_file(&archive, context)?;

    let fresh = runtime.block_on(cancellable(
        cancel,
        api.precheck(&plan.host_id, &PrecheckDto {
            mc_version_name: plan.mc_version_name.clone(),
            loader: plan.loader.clone(),
            member_player_ids: plan.member_player_ids.clone(),
        }),
    ))?;
    let new_warnings: Vec<&MemberWarning> = fresh.member_warnings.iter().filter(|w| !plan.confirmed_warnings.contains(w)).collect();
    if !new_warnings.is_empty() {
        let reasons = new_warnings.iter().map(|w| w.reason.as_str()).collect::<Vec<_>>().join("；");
        bail!("房间成员情况有变化（{reasons}），请重新确认后导入");
    }
    let session = runtime.block_on(cancellable(
        cancel,
        api.create(&plan.host_id, &CreateDto {
            size,
            sha1,
            mc_version_name: plan.mc_version_name.clone(),
            loader: plan.loader.clone(),
            member_player_ids: plan.member_player_ids.clone(),
        }),
    ))?;
    *import_id = Some(session.id.clone());
    let descriptor = upload::Descriptor {
        size: session.size,
        part_size: session.part_size,
        part_count: session.part_count,
        uploaded_parts: session.uploaded_parts.clone(),
    };
    let (part_api, host, id) = (api.clone(), plan.host_id.clone(), session.id.clone());
    let progress_context = context.clone();
    let part_count = session.part_count;
    runtime.block_on(cancellable(
        cancel,
        upload::upload(
            &archive,
            &descriptor,
            &env.upload,
            move |index, bytes, sha1| {
                let (api, host, id) = (part_api.clone(), host.clone(), id.clone());
                async move { api.upload_part(&host, &id, index, bytes, sha1).await }
            },
            move |done, parts| {
                let fraction = 0.6 + 0.3 * done as f32 / size.max(1) as f32;
                progress_context.progress(format!("正在上传存档{parts}/{part_count}"), Some(fraction));
            },
        ),
    ))?;
    cancel.check()?;
    runtime.block_on(cancellable(cancel, api.complete(&plan.host_id, &session.id)))?;
    *completed = true;

    let deadline = Instant::now() + env.poll_timeout;
    while Instant::now() < deadline {
        cancel.check()?;
        let status = runtime.block_on(cancellable(cancel, api.status(&plan.host_id, &session.id)))?;
        match status.status {
            ImportStatus::Ready => {
                if let Err(error) = marking.delete(&plan.record) {
                    tracing::warn!(error = %format!("{error:#}"), copy = %plan.record.copy_path, "清理存档导入副本失败");
                }
                let note = if unhandled.is_empty() { String::new() } else { format!("，有{}个文件可能还有未迁移的玩家数据", unhandled.len()) };
                context.finish(format!("存档已导入房间{}{note}", plan.host_name));
                return Ok(());
            }
            ImportStatus::Failed => bail!("{}", status.error_message.unwrap_or_else(|| "存档导入失败".into())),
            _ => context.progress("服务器正在处理存档", Some(0.95)),
        }
        runtime.block_on(cancellable(cancel, async {
            tokio::time::sleep(env.poll_interval).await;
            Ok(())
        }))?;
    }
    context.finish("服务器仍在处理存档，结果将通过邮件通知");
    Ok(())
}

/// Remaps the copy under its lock (the game must be closed). Returns the files that may hold
/// unmigrated player data.
fn remap(plan: &ImportPlan, target: &Path, context: &Context) -> Result<Vec<UnhandledFile>> {
    let copy = plan.record.copy();
    let _lock = SaveSourceLock::acquire(&copy).map_err(|error| match error {
        RemapError::SaveInUse { .. } => {
            tracing::warn!(error = %error, "请先退出游戏再导入");
            anyhow!("请先退出游戏再导入")
        }
        other => player_error(other),
    })?;
    let snapshot = SaveSnapshot::take(&copy).map_err(player_error)?;
    let sync_file = copy.join(SYNC_CHUNKS_RELATIVE_PATH);
    if !std::fs::symlink_metadata(&sync_file).is_ok_and(|m| m.is_file()) {
        bail!("请至少标记一个要保留的区块");
    }
    let bytes = snapshot.read_file(SYNC_CHUNKS_RELATIVE_PATH).map_err(player_error)?;
    let chunks = parse_sync_chunks(&bytes).map_err(|error| {
        tracing::warn!(error = %error, "同步区块列表无效");
        anyhow!("同步区块列表无效")
    })?;
    if chunks.is_empty() {
        bail!("请至少标记一个要保留的区块");
    }
    let cancel = context.cancel.clone();
    let ensure_active = move || if cancel.cancelled() { Err(RemapError::Cancelled) } else { Ok(()) };
    let progress = |p: rdi_anvilrw::RemapProgress| {
        let fraction = if p.bytes_total == 0 { 0. } else { p.bytes_done as f32 / p.bytes_total as f32 };
        context.progress("正在处理存档", Some(0.4 * fraction));
    };
    let report = SaveUuidRemapper::new(plan.mapping.clone(), Some(chunks))
        .map_err(player_error)?
        .remap_tree(&copy, target, &snapshot, &progress, &ensure_active)
        .map_err(player_error)?;
    for file in &report.possibly_unmigrated {
        tracing::warn!(path = %file.relative_path, reason = %file.reason, "可能未迁移的玩家数据");
    }
    Ok(report.possibly_unmigrated)
}

/// `world.tar.zst` of the remapped tree, entries sorted by path like Kotlin.
fn pack(root: &Path, archive: &Path, context: &Context) -> Result<()> {
    let mut entries: Vec<(String, PathBuf, bool)> = vec![];
    let mut stack = vec![root.to_owned()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir)? {
            let path = entry?.path();
            let meta = std::fs::symlink_metadata(&path)?;
            let relative = path.strip_prefix(root)?.components().map(|c| c.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("/");
            if meta.is_dir() {
                stack.push(path.clone());
            }
            entries.push((relative, path, meta.is_dir()));
        }
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    let file = std::fs::File::create(archive).context("创建存档压缩包失败")?;
    let mut builder = tar::Builder::new(zstd::stream::write::Encoder::new(file, 9)?);
    let total = entries.len();
    for (index, (relative, path, directory)) in entries.iter().enumerate() {
        context.cancel.check()?;
        let meta = std::fs::symlink_metadata(path)?;
        let time = crate::rdipack2::seconds(&meta);
        if *directory {
            crate::rdipack2::append(&mut builder, &format!("{relative}/"), true, 0, time, std::io::empty())?;
        } else {
            let source = std::fs::File::open(path).with_context(|| format!("读取文件失败：{relative}"))?;
            crate::rdipack2::append(&mut builder, relative, false, meta.len(), time, crate::rdipack2::CancelRead { inner: source, ctx: context })?;
        }
        context.progress("正在打包存档", Some(0.4 + 0.1 * (index + 1) as f32 / total.max(1) as f32));
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

/// The `saves` folder of the host's installed pack, where the marking copy goes (Kotlin
/// `createCopy` through `playArgs`).
pub async fn host_saves_dir(deps: &RoomDeps, host_id: &str) -> Result<PathBuf> {
    let pack = room::host_pack(deps, host_id).await?;
    room::ensure_installed(&pack.state)?;
    Ok(pack.version_dir.join("saves"))
}

fn is_game_name(name: &str) -> bool {
    (1..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

/// The identity and arguments of a marking launch (Kotlin `markingLaunch`): the selected role's
/// name when it is a valid game name, else the RDI account's; the recorded launch UUID; a
/// placeholder token; marking mode on for the copy, which the game opens directly.
pub fn marking_overrides(record: &MarkingRecord, rdi_account_name: &str) -> LaunchOverrides {
    let name = record.selected_player_name.as_deref().filter(|n| is_game_name(n)).unwrap_or(rdi_account_name);
    let world = record.copy_folder_name();
    LaunchOverrides {
        account: Some(Account { name: name.into(), uuid: record.launch_uuid.clone(), access_token: "0".into(), user_type: "msa".into() }),
        extra_jvm_args: vec!["-Drdi.syncChunkMarking=true".into(), format!("-Drdi.syncChunkMarkingWorld={world}")],
        extra_game_args: vec!["--quickPlaySingleplayer".into(), world],
    }
}

/// Opens the marking copy with the host's pack after `check_identity` and `check_readable`. The
/// host is not started. The returned session ends when the player leaves the world; the caller
/// watches `Sessions::views` for it and then shows the summary.
pub async fn marking_launch(deps: &RoomDeps, marking: &MarkingService, record: &MarkingRecord, window: (u32, u32)) -> Result<Admitted> {
    let (m, r) = (marking.clone(), record.clone());
    tokio::task::spawn_blocking(move || -> Result<()> {
        m.check_identity(&r)?;
        m.check_readable(&r)
    })
    .await??;
    room::start_marking(deps, MarkingStart {
        host_id: record.host_id.clone(),
        overrides: marking_overrides(record, &deps.account_name),
        window,
        allow_duplicate: false,
    })
    .await
}
