//! 存档 (U4 batch c2a): Compose `HostWorldScreen`, `HostWorldViewModel`, `HostWorldDownloadApi`,
//! `HostWorldDownloadTask` and `HostWorldSaveInstaller`. No Slint types.
use super::{
    model::{HostDetail, Role},
    permissions::Me,
    requests::segment,
};
use crate::{api::server::ServerApi, tasks::Cancellation};
use anyhow::{Context, Result, bail, ensure};
use chrono::{DateTime, FixedOffset, NaiveDate};
use serde::Deserialize;
use std::{
    path::{Path, PathBuf},
    time::Duration,
};

/// Which save actions the player has (`HostWorldUiState`).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Access {
    pub is_owner: bool,
    /// A member who is not a guest; only they may download the save.
    pub is_member: bool,
    pub can_import: bool,
    pub can_rollback: bool,
    pub can_reset: bool,
    pub can_download: bool,
}
pub fn access(host: &HostDetail, me: Me) -> Access {
    let is_owner = me.is_dav || host.owner_id == me.id;
    let is_member = me.is_dav || host.members.iter().any(|m| m.id == me.id && m.role != Role::Guest);
    let pack = &host.modpack;
    Access {
        is_owner,
        is_member,
        // Save import, rollback and reset are owner (or dav) only on the server.
        can_import: is_owner,
        // 回档 is supported on 1.20.1 Forge rooms for now.
        can_rollback: is_owner && pack.mc_ver == "V201" && pack.modloader.eq_ignore_ascii_case("forge"),
        can_reset: is_owner && host.version == 2,
        can_download: is_member && host.version == 2,
    }
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Record {
    pub player_id: String,
    pub player_name: Option<String>,
    pub finished_at: i64,
    pub bytes: i64,
}

/// `HostWorldDownloadStatusVo`.
#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct DownloadStatus {
    pub limit: i64,
    pub remaining: i64,
    pub today: Vec<Record>,
    pub reusable: bool,
    pub blocked_reason: Option<String>,
}
impl DownloadStatus {
    /// 下载房间存档 is enabled.
    pub fn can_start(&self) -> bool {
        self.reusable || (self.blocked_reason.is_none() && self.remaining > 0)
    }
    /// The line under the buttons.
    pub fn hint(&self) -> String {
        if self.reusable {
            "存档已经打包好，这次下载不占次数".into()
        } else if let Some(reason) = &self.blocked_reason {
            reason.clone()
        } else {
            format!("今天还能下载{}次（所有成员共用{}次）", self.remaining, self.limit)
        }
    }
    /// 今天已下载：…, or empty.
    pub fn today_line(&self, offset: FixedOffset) -> String {
        if self.today.is_empty() {
            return String::new();
        }
        let list: Vec<String> = self
            .today
            .iter()
            .map(|r| {
                let at = DateTime::from_timestamp_millis(r.finished_at).unwrap_or_default().with_timezone(&offset);
                format!("{} {}", r.player_name.as_deref().unwrap_or("玩家"), at.format("%H:%M"))
            })
            .collect();
        format!("今天已下载：{}", list.join("、"))
    }
    /// The checks `downloadSave` makes before handing the download to a task.
    pub fn check(&self) -> Result<()> {
        if !self.reusable {
            if let Some(reason) = &self.blocked_reason {
                bail!("{reason}");
            }
            ensure!(self.remaining > 0, "今天的{}次下载已经用完，明天再来", self.limit);
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Started {
    pub build_id: String,
    pub reused: bool,
}
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
pub enum Stage {
    #[default]
    SyncChunks,
    Files,
    Compress,
}
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct PackProgress {
    pub stage: Stage,
    pub done: i64,
    pub total: i64,
}
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct Ready {
    pub build_id: String,
    pub bytes: i64,
    pub sha1: String,
    pub chunk_count: i64,
}

pub async fn reset_world(api: &ServerApi, host: &str) -> Result<()> {
    let _: serde_json::Value = api.post_empty(&format!("/host/{}/reset-world", segment(host)?)).await?;
    Ok(())
}
pub async fn download_status(api: &ServerApi, host: &str) -> Result<DownloadStatus> {
    api.get(&format!("/host/{}/world-download", segment(host)?)).await
}
pub async fn start(api: &ServerApi, host: &str) -> Result<Started> {
    let started: Option<Started> = api.post_empty(&format!("/host/{}/world-download", segment(host)?)).await?;
    started.filter(|s| !s.build_id.is_empty()).context("服务器响应缺少打包信息")
}

/// Reads packing events until `ready` or `error` (`awaitReadyEvent`).
pub async fn await_ready(api: &ServerApi, host: &str, build: &str, mut on_progress: impl FnMut(PackProgress)) -> Result<Ready> {
    let path = format!("/host/{}/world-download/{}/events", segment(host)?, segment(build)?);
    let mut last: Option<Result<Ready>> = None;
    let ended = api.sse(&path, Duration::from_secs(120), |event| match event.event.as_str() {
        "progress" => {
            if let Ok(p) = serde_json::from_str(&event.data) {
                on_progress(p);
            }
            true
        }
        "ready" => {
            last = Some(serde_json::from_str(&event.data).map_err(|_| anyhow::anyhow!("打包结果为空")));
            false
        }
        "error" => {
            let text = if event.data.trim().is_empty() { "打包存档失败".into() } else { event.data };
            last = Some(Err(anyhow::anyhow!("{text}")));
            false
        }
        _ => true,
    });
    match ended.await {
        Ok(_) => last.unwrap_or_else(|| Err(anyhow::anyhow!("打包进度连接中断，请重试"))),
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "存档打包进度读取失败");
            bail!("打包进度连接中断，请重试")
        }
    }
}

pub fn progress_text(p: &PackProgress) -> &'static str {
    match p.stage {
        Stage::SyncChunks => "正在读取同步区块",
        Stage::Files => "正在收集玩家和其他数据",
        Stage::Compress => "正在压缩存档",
    }
}
/// Share of the server's packing done: sync chunks first half, other files up to 90%, then compression.
pub fn pack_fraction(p: &PackProgress) -> f32 {
    let step = if p.total > 0 { p.done as f32 / p.total as f32 } else { 0. };
    match p.stage {
        Stage::SyncChunks => 0.5 * step,
        Stage::Files => 0.5 + 0.4 * step,
        Stage::Compress => 0.9 + 0.1 * step,
    }
}
/// Packing 0–40%, downloading 40–90%, extracting 90–100%.
pub const PACK_SHARE: f32 = 0.4;
pub const DOWNLOAD_SHARE: f32 = 0.5;

/// The room's version folder holds its singleplayer `saves`.
pub fn saves_dir(versions: &Path, pack_id: &str, version: &str) -> PathBuf {
    versions.join(crate::pack_install::version_id(pack_id, version)).join("saves")
}

/// The day used in the folder name (Kotlin `LocalDate.now(Asia/Shanghai)`).
pub fn today_in_china() -> NaiveDate {
    chrono::Utc::now().with_timezone(&FixedOffset::east_opt(8 * 3600).unwrap()).date_naive()
}

/// `<房间名>-<日期>`, safe as a Windows folder name; `(2)`, `(3)`… when the name is taken.
pub fn folder_name(saves: &Path, host_name: &str, day: NaiveDate) -> String {
    let replaced: String = host_name
        .chars()
        .map(|c| if matches!(c, '\\' | '/' | ':' | '*' | '?' | '"' | '<' | '>' | '|') || c.is_control() { '_' } else { c })
        .collect();
    let trimmed = replaced.trim().trim_end_matches(['.', ' ']);
    let safe = if trimmed.is_empty() { "房间存档" } else { trimmed };
    let base = format!("{safe}-{}", day.format("%Y-%m-%d"));
    let taken = |name: &str| std::fs::symlink_metadata(saves.join(name)).is_ok();
    if !taken(&base) {
        return base;
    }
    (2..).map(|i| format!("{base}({i})")).find(|n| !taken(n)).unwrap()
}

/// Extracts `archive` into a hidden folder of `saves` first and renames it when complete, so an
/// interrupted extraction never shows up as a broken save. Existing saves are never overwritten.
pub fn install(
    archive: &Path,
    saves: &Path,
    host_name: &str,
    day: NaiveDate,
    cancel: &Cancellation,
    progress: &dyn Fn(usize, usize),
) -> Result<PathBuf> {
    std::fs::create_dir_all(saves)?;
    let staging = saves.join(format!(".rdi-download-{}", uuid::Uuid::now_v7().simple()));
    std::fs::create_dir(&staging)?;
    let result = (|| {
        crate::pack_install::archive::extract(archive, &staging, cancel, &|done, total, _| progress(done, total))?;
        ensure!(staging.join("level.dat").is_file(), "存档包里没有level.dat");
        let target = saves.join(folder_name(saves, host_name, day));
        std::fs::rename(&staging, &target)?;
        Ok(target)
    })();
    if result.is_err()
        && std::fs::symlink_metadata(&staging).is_ok()
        && let Err(error) = std::fs::remove_dir_all(&staging)
    {
        // Only the staging folder made above is removed; nothing else in saves is touched.
        tracing::warn!(%error, path = %staging.display(), "无法清理临时文件夹");
    }
    result
}

/// The archive file inside `saves` while it downloads; removed afterwards in every case.
pub fn archive_path(saves: &Path, build: &str) -> PathBuf {
    saves.join(format!(".rdi-download-{build}.tar.zst"))
}

pub fn check_sha1(file: &Path, expected: &str, cancel: &Cancellation) -> Result<()> {
    let actual = crate::digests::hash(file, crate::digests::Algorithm::Sha1, cancel)?;
    ensure!(actual.eq_ignore_ascii_case(expected), "存档包校验失败，请重新下载");
    Ok(())
}

/// The task title and its dedupe key, so a second click joins the running download.
pub fn task_title(host_name: &str) -> String {
    format!("下载房间存档{host_name}")
}
pub fn task_key(host: &str) -> String {
    format!("host-world-download:{host}")
}

/// `HostWorldDownloadTask.run` inside a task worker: the server packs the save, the archive is
/// downloaded into `saves` and extracted there as a new singleplayer save. Returns the new folder.
pub fn run_download(
    api: &ServerApi,
    runtime: &tokio::runtime::Handle,
    host: &str,
    host_name: &str,
    saves: &Path,
    context: &crate::tasks::Context,
) -> Result<PathBuf> {
    use crate::download::{Downloader, Source, cancellable};
    let cancel = &context.cancel;
    context.progress("正在请求打包存档", Some(0.));
    let started = runtime.block_on(cancellable(cancel, start(api, host)))?;
    cancel.check()?;
    let ready = runtime.block_on(cancellable(
        cancel,
        await_ready(api, host, &started.build_id, |p| context.progress(progress_text(&p), Some(PACK_SHARE * pack_fraction(&p)))),
    ))?;
    // A modpack that was never played in singleplayer has no saves folder yet.
    std::fs::create_dir_all(saves)?;
    let archive = archive_path(saves, &ready.build_id);
    let result = (|| {
        let url = api.url(&format!("/host/{}/world-download/{}/file", segment(host)?, segment(&ready.build_id)?));
        let source = Source { headers: api.auth_headers(&url)?, url };
        let known = u64::try_from(ready.bytes).ok().filter(|b| *b > 0);
        let validate = |file: &Path| check_sha1(file, &ready.sha1, cancel);
        let progress = |p: crate::download::Progress| {
            let fraction = p.total.filter(|t| *t > 0).map(|t| p.bytes as f32 / t as f32).unwrap_or(0.);
            context.progress("正在下载存档", Some(PACK_SHARE + DOWNLOAD_SHARE * fraction.clamp(0., 1.)));
        };
        runtime.block_on(Downloader { http: api.http.clone() }.download(&[source], &archive, known, cancel, &validate, &progress))?;
        cancel.check()?;
        let start = PACK_SHARE + DOWNLOAD_SHARE;
        context.progress("正在解压存档", Some(start));
        let saved = install(&archive, saves, host_name, today_in_china(), cancel, &|done, total| {
            context.progress("正在解压存档", Some(start + (1. - start) * done as f32 / total.max(1) as f32));
        })?;
        let name = saved.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        context.finish(format!("已保存为单人存档「{name}」"));
        Ok(saved)
    })();
    // Also runs when the task is cancelled, so no archive is left in saves.
    if let Err(error) = std::fs::remove_file(&archive)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(%error, path = %archive.display(), "删除下载的存档包失败");
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rooms::model::{Member, RoomPack};

    fn host(owner: &str, version: i32, members: Vec<Member>, mc: &str, loader: &str) -> HostDetail {
        serde_json::from_value::<HostDetail>(serde_json::json!({
            "_id":"66a000000000000000000001","name":"房间","ownerId":owner,"packVer":"1.0","version":version,
            "difficulty":3,"gameMode":0,"levelType":"minecraft:normal"
        }))
        .map(|mut h| {
            h.members = members;
            h.modpack = RoomPack { mc_ver: mc.into(), modloader: loader.into(), ..Default::default() };
            h
        })
        .unwrap()
    }
    const ME: &str = "66a0000000000000000000aa";
    const OTHER: &str = "66a0000000000000000000bb";
    fn me(dav: bool) -> Me<'static> {
        Me { id: ME, is_dav: dav }
    }

    #[test]
    fn access_matches_host_world_ui_state() {
        let owned = access(&host(ME, 2, vec![Member { id: ME.into(), role: Role::Owner }], "V201", "forge"), me(false));
        assert_eq!(
            owned,
            Access { is_owner: true, is_member: true, can_import: true, can_rollback: true, can_reset: true, can_download: true }
        );
        let neo = access(&host(ME, 2, vec![], "V211", "neoforge"), me(false));
        assert!(!neo.can_rollback && neo.can_reset && !neo.can_download, "owner outside members cannot download");
        let old = access(&host(ME, 1, vec![Member { id: ME.into(), role: Role::Owner }], "V201", "forge"), me(false));
        assert!(!old.can_reset && !old.can_download && old.can_rollback);
        let member = access(&host(OTHER, 2, vec![Member { id: ME.into(), role: Role::Member }], "V201", "forge"), me(false));
        assert!(!member.is_owner && member.can_download && !member.can_reset && !member.can_rollback);
        let guest = access(&host(OTHER, 2, vec![Member { id: ME.into(), role: Role::Guest }], "V201", "forge"), me(false));
        assert!(!guest.is_member && !guest.can_download);
        let dav = access(&host(OTHER, 2, vec![], "V201", "forge"), me(true));
        assert!(dav.is_owner && dav.is_member && dav.can_rollback && dav.can_download);
    }

    #[test]
    fn download_texts_and_checks() {
        let base = DownloadStatus { limit: 3, remaining: 2, ..Default::default() };
        assert_eq!(base.hint(), "今天还能下载2次（所有成员共用3次）");
        assert!(base.can_start() && base.check().is_ok());
        let used = DownloadStatus { remaining: 0, ..base.clone() };
        assert!(!used.can_start());
        assert_eq!(used.check().unwrap_err().to_string(), "今天的3次下载已经用完，明天再来");
        let blocked = DownloadStatus { blocked_reason: Some("房间正在运行".into()), ..base.clone() };
        assert_eq!((blocked.hint().as_str(), blocked.can_start()), ("房间正在运行", false));
        assert_eq!(blocked.check().unwrap_err().to_string(), "房间正在运行");
        let reusable = DownloadStatus { reusable: true, remaining: 0, blocked_reason: Some("x".into()), ..base.clone() };
        assert_eq!(reusable.hint(), "存档已经打包好，这次下载不占次数");
        assert!(reusable.can_start() && reusable.check().is_ok());
        let zone = FixedOffset::east_opt(8 * 3600).unwrap();
        let today = DownloadStatus {
            today: vec![
                Record { player_name: Some("甲".into()), finished_at: 0, ..Default::default() },
                Record { player_name: None, finished_at: 3_600_000, ..Default::default() },
            ],
            ..base
        };
        assert_eq!(today.today_line(zone), "今天已下载：甲 08:00、玩家 09:00");
        assert_eq!(DownloadStatus::default().today_line(zone), "");
    }

    #[test]
    fn pack_progress_maps_like_kotlin() {
        let p = |stage, done, total| PackProgress { stage, done, total };
        assert_eq!(pack_fraction(&p(Stage::SyncChunks, 5, 10)), 0.25);
        assert_eq!(pack_fraction(&p(Stage::Files, 10, 10)), 0.9);
        assert_eq!(pack_fraction(&p(Stage::Compress, 0, 0)), 0.9);
        assert_eq!(progress_text(&p(Stage::Files, 0, 0)), "正在收集玩家和其他数据");
        let parsed: PackProgress = serde_json::from_str(r#"{"stage":"Compress","done":1,"total":2}"#).unwrap();
        assert_eq!(parsed, p(Stage::Compress, 1, 2));
    }

    /// A test folder removed when dropped.
    struct Temp(PathBuf);
    impl Temp {
        fn new() -> Self {
            let p = std::env::temp_dir().join(format!("rdi-world-{}", uuid::Uuid::now_v7()));
            std::fs::create_dir_all(&p).unwrap();
            Self(p)
        }
        fn path(&self) -> &Path {
            &self.0
        }
    }
    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn day() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 10, 13).unwrap()
    }

    #[test]
    fn folder_names_are_safe_and_never_reuse_a_save() {
        let dir = Temp::new();
        let saves = dir.path();
        assert_eq!(folder_name(saves, "我的/房间?", day()), "我的_房间_-2026-10-13");
        assert_eq!(folder_name(saves, " .. ", day()), "房间存档-2026-10-13");
        std::fs::create_dir(saves.join("房间-2026-10-13")).unwrap();
        std::fs::create_dir(saves.join("房间-2026-10-13(2)")).unwrap();
        assert_eq!(folder_name(saves, "房间", day()), "房间-2026-10-13(3)");
    }

    fn tar_zst(files: &[(&str, &str)]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, body) in files {
            let mut header = tar::Header::new_gnu();
            header.set_size(body.len() as u64);
            header.set_mode(0o644);
            // Written by hand: `set_path` refuses the `..` a hostile archive would hold.
            header.as_old_mut().name[..name.len()].copy_from_slice(name.as_bytes());
            header.set_cksum();
            builder.append(&header, body.as_bytes()).unwrap();
        }
        zstd::encode_all(&builder.into_inner().unwrap()[..], 3).unwrap()
    }

    #[test]
    fn install_extracts_beside_existing_saves() {
        let dir = Temp::new();
        let saves = dir.path().join("saves");
        std::fs::create_dir_all(saves.join("房间-2026-10-13")).unwrap();
        std::fs::write(saves.join("房间-2026-10-13/level.dat"), "old").unwrap();
        let archive = dir.path().join("a.tar.zst");
        std::fs::write(&archive, tar_zst(&[("level.dat", "new"), ("region/r.0.0.mca", "chunks")])).unwrap();
        let seen = std::sync::Mutex::new(vec![]);
        let installed = install(&archive, &saves, "房间", day(), &Cancellation::default(), &|d, t| seen.lock().unwrap().push((d, t))).unwrap();
        assert_eq!(installed.file_name().unwrap().to_str().unwrap(), "房间-2026-10-13(2)");
        assert_eq!(std::fs::read_to_string(installed.join("level.dat")).unwrap(), "new");
        assert_eq!(std::fs::read_to_string(installed.join("region/r.0.0.mca")).unwrap(), "chunks");
        assert_eq!(std::fs::read_to_string(saves.join("房间-2026-10-13/level.dat")).unwrap(), "old");
        assert_eq!(seen.into_inner().unwrap().last(), Some(&(2, 2)));
    }

    #[test]
    fn install_without_level_dat_leaves_nothing() {
        let dir = Temp::new();
        let saves = dir.path().join("saves");
        let archive = dir.path().join("a.tar.zst");
        std::fs::write(&archive, tar_zst(&[("region/r.0.0.mca", "chunks")])).unwrap();
        let error = install(&archive, &saves, "房间", day(), &Cancellation::default(), &|_, _| {}).unwrap_err();
        assert_eq!(error.to_string(), "存档包里没有level.dat");
        assert_eq!(std::fs::read_dir(&saves).unwrap().count(), 0);
        let archive = dir.path().join("b.tar.zst");
        std::fs::write(&archive, tar_zst(&[("../escape", "x")])).unwrap();
        assert!(install(&archive, &saves, "房间", day(), &Cancellation::default(), &|_, _| {}).is_err());
        assert!(!dir.path().join("escape").exists());
        assert_eq!(std::fs::read_dir(&saves).unwrap().count(), 0);
    }

    #[test]
    fn sha1_mismatch_is_reported() {
        let dir = Temp::new();
        let file = dir.path().join("a");
        std::fs::write(&file, "abc").unwrap();
        check_sha1(&file, "A9993E364706816ABA3E25717850C26C9CD0D89D", &Cancellation::default()).unwrap();
        assert_eq!(check_sha1(&file, "00", &Cancellation::default()).unwrap_err().to_string(), "存档包校验失败，请重新下载");
    }

    #[test]
    fn saves_live_in_the_version_folder() {
        assert_eq!(
            saves_dir(Path::new("v"), "66A000000000000000000002", "1.0"),
            Path::new("v").join("66a000000000000000000002_1.0").join("saves")
        );
    }
}
