//! `McInstall.ensureDesktopLaunchAssets`: music and records become a silent stub, numbered
//! variants of one sound share a single download, and failed objects do not block the game.
use super::{Deps, Progress, human_file_size, meta, sha1_file};
use crate::mc_launch::manifest::Manifest;
use crate::tasks::Cancellation;
use anyhow::{Context, Result};
use serde::Deserialize;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

const PARALLELISM: usize = 16;

#[derive(Clone, Debug, Deserialize)]
pub struct Object {
    pub hash: String,
    pub size: u64,
}

#[derive(Deserialize)]
struct Index {
    #[serde(default)]
    objects: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Default, PartialEq)]
pub struct Plan {
    pub stub: Vec<(String, Object)>,
    pub download: Vec<(String, Object)>,
    /// (from hash, to hash): `from` is filled by copying `to`.
    pub links: Vec<(String, String)>,
}

impl PartialEq for Object {
    fn eq(&self, other: &Self) -> bool {
        self.hash == other.hash && self.size == other.size
    }
}

/// `shouldDownloadAsset`.
pub fn should_download(path: &str) -> bool {
    if path == "minecraft/resourcepacks/programmer_art.zip" {
        return false;
    }
    if path.starts_with("realms/") {
        return path == "realms/lang/en_us.json";
    }
    if path.starts_with("minecraft/lang/") {
        return path.eq_ignore_ascii_case("minecraft/lang/zh_cn.json") || path.eq_ignore_ascii_case("minecraft/lang/zh_cn.lang");
    }
    if let Some(rest) = path.strip_prefix("minecraft/sounds/")
        && (rest.starts_with("records/") || rest.starts_with("music/") || rest.starts_with("ambient/"))
    {
        return false;
    }
    true
}

/// `shouldUseEmptySound`.
pub fn should_stub(path: &str) -> bool {
    if !path.to_ascii_lowercase().ends_with(".ogg") {
        return false;
    }
    path.strip_prefix("minecraft/sounds/")
        .is_some_and(|rest| rest.starts_with("records/") || rest.starts_with("music/") || rest.starts_with("ambient/"))
}

/// `buildAssetRepairPlan`, in the index's key order.
pub fn plan(index_json: &str) -> Result<Plan> {
    let index: Index = serde_json::from_str(index_json).context("资源索引无效")?;
    let numbered = regex::Regex::new(r"^(.+?)(\d+)(\.[^./]+)$").unwrap();
    let mut plan = Plan::default();
    let mut normal = vec![];
    type Group = Vec<(String, Object, u32)>;
    let mut groups: Vec<(String, Group)> = vec![];
    for (path, value) in index.objects {
        let object: Object = serde_json::from_value(value).with_context(|| format!("资源索引项无效: {path}"))?;
        if should_download(&path) {
            match numbered.captures(&path).and_then(|c| Some((format!("{}{}", &c[1], &c[3]), c[2].parse::<u32>().ok()?))) {
                Some((key, number)) => match groups.iter_mut().find(|(k, _)| *k == key) {
                    Some((_, g)) => g.push((path, object, number)),
                    None => groups.push((key, vec![(path, object, number)])),
                },
                None => normal.push((path, object)),
            }
        } else if should_stub(&path) {
            plan.stub.push((path, object));
        }
    }
    plan.download = normal;
    for (_, group) in groups {
        if group.len() == 1 {
            let (p, o, _) = group.into_iter().next().unwrap();
            plan.download.push((p, o));
            continue;
        }
        let preferred = group.iter().position(|g| g.2 == 1).unwrap_or_else(|| {
            let min = group.iter().map(|g| g.2).min().unwrap();
            group.iter().position(|g| g.2 == min).unwrap()
        });
        let (pp, po, _) = group[preferred].clone();
        for (i, (_, o, _)) in group.iter().enumerate() {
            if i != preferred && !o.hash.eq_ignore_ascii_case(&po.hash) {
                plan.links.push((o.hash.clone(), po.hash.clone()));
            }
        }
        plan.download.push((pp, po));
    }
    Ok(plan)
}

fn object_file(deps: &Deps, hash: &str) -> PathBuf {
    let h = hash.to_ascii_lowercase();
    deps.assets().join("objects").join(&h[..2.min(h.len())]).join(&h)
}

fn valid(deps: &Deps, object: &Object) -> bool {
    let file = object_file(deps, &object.hash);
    std::fs::metadata(&file).is_ok_and(|m| m.is_file() && m.len() == object.size)
        && sha1_file(&file).is_ok_and(|s| s.eq_ignore_ascii_case(&object.hash))
}

fn write_stub(deps: &Deps, object: &Object) -> Result<()> {
    let file = object_file(deps, &object.hash);
    if std::fs::read(&file).is_ok_and(|b| b == meta::EMPTY_SOUND) {
        return Ok(());
    }
    std::fs::create_dir_all(file.parent().unwrap())?;
    std::fs::write(&file, meta::EMPTY_SOUND)?;
    Ok(())
}

fn link(deps: &Deps, from: &str, to: &str) -> Result<()> {
    if from.eq_ignore_ascii_case(to) {
        return Ok(());
    }
    let (from, to) = (object_file(deps, from), object_file(deps, to));
    if from.exists() || !to.exists() {
        return Ok(());
    }
    std::fs::create_dir_all(from.parent().unwrap())?;
    std::fs::copy(to, from)?;
    Ok(())
}

pub async fn ensure(deps: &Deps, manifest: &Manifest, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let Some(index_meta) = manifest.asset_index.clone() else {
        return Ok(());
    };
    let text = meta::asset_index(&index_meta.id)?;
    let indexes = deps.assets().join("indexes");
    std::fs::create_dir_all(&indexes)?;
    std::fs::write(indexes.join(format!("{}.json", index_meta.id)), text)?;
    let plan = plan(text)?;
    let d = deps.clone();
    let (broken, missing_links, plan) = tokio::task::spawn_blocking(move || -> Result<_> {
        for (_, o) in &plan.stub {
            write_stub(&d, o)?;
        }
        let mut seen = std::collections::HashSet::new();
        let unique: Vec<(String, Object)> = plan.download.iter().filter(|(_, o)| seen.insert(o.hash.to_ascii_lowercase())).cloned().collect();
        let broken = std::sync::Mutex::new(vec![]);
        let next = AtomicUsize::new(0);
        std::thread::scope(|s| {
            for _ in 0..PARALLELISM {
                s.spawn(|| loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    let Some(entry) = unique.get(i) else { break };
                    if !valid(&d, &entry.1) {
                        broken.lock().unwrap().push(entry.clone());
                    }
                });
            }
        });
        let missing: Vec<(String, String)> = plan.links.iter().filter(|(from, _)| !object_file(&d, from).is_file()).cloned().collect();
        Ok((broken.into_inner().unwrap(), missing, plan))
    })
    .await??;
    drop(plan);
    if broken.is_empty() && missing_links.is_empty() {
        progress("资源完整".into());
        return Ok(());
    }
    let failed = if broken.is_empty() { 0 } else { repair(deps, broken, cancel, progress).await? };
    for (from, to) in &missing_links {
        link(deps, from, to)?;
    }
    if failed > 0 {
        progress(format!("有{failed}个资源下载失败，游戏中可能缺少部分声音或贴图，下次启动会自动重试"));
    } else {
        progress("资源已就绪".into());
    }
    Ok(())
}

async fn repair(deps: &Deps, broken: Vec<(String, Object)>, cancel: &Cancellation, progress: Progress<'_>) -> Result<usize> {
    let total_bytes: u64 = broken.iter().map(|(_, o)| o.size).sum();
    let count = broken.len();
    progress(format!("修复资源 0/{count} (共{})", human_file_size(total_bytes)));
    let done = Arc::new(AtomicUsize::new(0));
    let failed = Arc::new(AtomicUsize::new(0));
    let done_bytes = Arc::new(AtomicU64::new(0));
    let semaphore = Arc::new(tokio::sync::Semaphore::new(PARALLELISM));
    let mut set = tokio::task::JoinSet::new();
    for (path, object) in broken {
        let (deps, cancel, semaphore) = (deps.clone(), cancel.clone(), semaphore.clone());
        let (done, failed, done_bytes) = (done.clone(), failed.clone(), done_bytes.clone());
        set.spawn(async move {
            let _permit = semaphore.acquire().await;
            let hash = object.hash.to_ascii_lowercase();
            let official = format!("https://resources.download.minecraft.net/{}/{hash}", &hash[..2]);
            let plan = super::source_plan(&deps, &official, None, "");
            let target = object_file(&deps, &hash);
            let size = object.size;
            let expected = hash.clone();
            let result = async {
                let sources: Vec<crate::download::Source> = plan.iter().map(|u| crate::download::Source { url: u.clone(), headers: Default::default() }).collect();
                let validate = move |p: &std::path::Path| -> Result<()> {
                    let len = std::fs::metadata(p)?.len();
                    anyhow::ensure!(len == size && sha1_file(p)?.eq_ignore_ascii_case(&expected), "资源校验失败: {path}");
                    Ok(())
                };
                deps.downloader.download(&sources, &target, Some(size), &cancel, &validate, &|_| {}).await
            }
            .await;
            match result {
                Ok(()) => {
                    done_bytes.fetch_add(object.size, Ordering::Relaxed);
                }
                Err(error) => {
                    if cancel.cancelled() {
                        return Err(error);
                    }
                    // A missing sound or texture must not block the game; the next launch retries it.
                    tracing::warn!(error = %format!("{error:#}"), hash, "资源下载失败，跳过");
                    failed.fetch_add(1, Ordering::Relaxed);
                }
            }
            done.fetch_add(1, Ordering::Relaxed);
            Ok(())
        });
    }
    let mut last = std::time::Instant::now();
    while let Some(joined) = set.join_next().await {
        joined.context("资源下载任务异常结束")??;
        if last.elapsed().as_millis() >= 500 && done.load(Ordering::Relaxed) < count {
            last = std::time::Instant::now();
            progress(format!(
                "修复资源 {}/{count} ({}/{})",
                done.load(Ordering::Relaxed),
                human_file_size(done_bytes.load(Ordering::Relaxed)),
                human_file_size(total_bytes)
            ));
        }
    }
    progress(format!(
        "修复资源 {}/{count} ({}/{})",
        done.load(Ordering::Relaxed),
        human_file_size(done_bytes.load(Ordering::Relaxed)),
        human_file_size(total_bytes)
    ));
    Ok(failed.load(Ordering::Relaxed))
}
