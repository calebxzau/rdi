//! Reading a client pack for upload: Kotlin `ModpackProcessor.loadLocalModpack` with
//! `parseUploadPayload`, `loadUploadPayloadMods`, `matchLocalModFiles`,
//! `matchEmbeddedClientExtras`, `mergeEmbeddedClientExtras` and `containsExcludedMcaFiles`.
//! Blocking: file work runs here and platform requests through `Context::runtime`, so call it
//! from `spawn_blocking`.
use super::manifest::{self, CfManifest, Index};
use super::mods::{self as rules, IndexEntry, client_entry_path};
use super::platform::{CfMod, Platforms};
use super::policy::{self, SaveRegions};
use super::source::{self, Kind, Summary};
use crate::digests::{Algorithm, hash};
use crate::pack_install::extras::{self, Content};
use crate::pack_install::mods::{Mod, Side};
use crate::tasks::Cancellation;
use anyhow::{Context as _, Result, anyhow, bail, ensure};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

/// `LoadProgress`.
#[derive(Clone, Debug, PartialEq)]
pub enum Progress {
    Phase(String),
    Percent(String, f32),
    Warn(String),
}

pub struct Context<'a> {
    pub runtime: tokio::runtime::Handle,
    pub platforms: Platforms,
    /// Extracted packs and staged files live here until the upload ends.
    pub work_dir: PathBuf,
    pub cancel: Cancellation,
    pub progress: &'a (dyn Fn(Progress) + Sync),
    /// CurseForge slugs to Modrinth slugs, `manifest::catalog_mr_slugs` in the launcher.
    pub mr_slugs: &'a (dyn Fn(&BTreeSet<String>) -> BTreeMap<String, String> + Send + Sync),
}

impl Context<'_> {
    pub(super) fn phase(&self, text: impl Into<String>) {
        (self.progress)(Progress::Phase(text.into()));
    }
    pub(super) fn sha1(&self, path: &Path) -> Result<String> {
        hash(path, Algorithm::Sha1, &self.cancel)
    }
    pub(super) fn murmur2(&self, path: &Path) -> Result<u32> {
        Ok(hash(path, Algorithm::Murmur2, &self.cancel)?.parse()?)
    }
    pub(super) fn temp(&self, prefix: &str, suffix: &str) -> PathBuf {
        self.work_dir.join(format!("{prefix}{}{suffix}", uuid::Uuid::now_v7().simple()))
    }
}

/// `EmbeddedModSource`: a recognised jar of the pack, copied for the content store.
#[derive(Clone, Debug)]
pub struct EmbeddedMod {
    pub m: Mod,
    pub staged: PathBuf,
    pub original_name: String,
}

/// `EmbeddedClientExtraSource`: a recognised resource or shader pack of the pack.
#[derive(Clone, Debug)]
pub struct EmbeddedExtra {
    pub content: Content,
    pub staged: PathBuf,
    pub source_relative: String,
    /// CurseForge content carries a fingerprint, so the checked SHA-1 is kept here.
    pub sha1: String,
}

/// `LoadedLocalModpack`.
#[derive(Debug)]
pub struct Loaded {
    pub summary: Summary,
    /// The pack copied or extracted into the work folder, without its recognised jars.
    pub source_dir: PathBuf,
    pub mods: Vec<Mod>,
    /// `Mod.fileName` to the jar's name in the pack.
    pub embedded_names: BTreeMap<String, String>,
    pub embedded_mods: Vec<EmbeddedMod>,
    pub extras: Vec<Content>,
    pub embedded_extras: Vec<EmbeddedExtra>,
    /// The pack holds save region files, which upload as empty files (地图模板 warning).
    pub contains_mca: bool,
}

/// A recognised local jar: Kotlin `ModCardMatch` without the card.
#[derive(Clone, Debug)]
pub(super) struct Match {
    pub m: Mod,
    pub file: PathBuf,
}

#[derive(Debug, Default)]
pub(super) struct Matches {
    pub mods: Vec<Match>,
    pub matched: Vec<PathBuf>,
    pub unmatched: Vec<PathBuf>,
}

pub(super) fn relative(root: &Path, file: &Path) -> String {
    file.strip_prefix(root).unwrap_or(file).to_string_lossy().replace('\\', "/")
}

fn extension_is(path: &Path, wanted: &str) -> bool {
    path.extension().is_some_and(|e| e.to_string_lossy().eq_ignore_ascii_case(wanted))
}

fn name_of(path: &Path) -> String {
    path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

/// Entries of `dir` in name order without regard to case, as NTFS lists them; links are left out.
pub(super) fn children(dir: &Path) -> Result<Vec<(PathBuf, bool)>> {
    let mut out = Vec::new();
    for entry in std::fs::read_dir(dir).with_context(|| format!("读取{}失败", dir.display()))? {
        let entry = entry?;
        let kind = entry.file_type()?;
        if !kind.is_symlink() {
            out.push((entry.path(), kind.is_dir()));
        }
    }
    out.sort_by_cached_key(|(path, _)| (name_of(path).to_lowercase(), name_of(path)));
    Ok(out)
}

/// The files below `root` in `walkTopDown` order: a folder's entries by name, each folder
/// entered where it stands.
pub(super) fn walk_files(root: &Path) -> Result<Vec<PathBuf>> {
    fn visit(dir: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
        for (path, is_dir) in children(dir)? {
            if is_dir { visit(&path, files)? } else { files.push(path) }
        }
        Ok(())
    }
    let mut files = Vec::new();
    visit(root, &mut files)?;
    Ok(files)
}

fn copy_dir(ctx: &Context, from: &Path, to: &Path) -> Result<()> {
    std::fs::create_dir_all(to)?;
    for (path, is_dir) in children(from)? {
        ctx.cancel.check()?;
        let target = to.join(path.file_name().unwrap_or_default());
        if is_dir {
            copy_dir(ctx, &path, &target)?;
        } else {
            std::fs::copy(&path, &target).with_context(|| format!("复制{}失败", path.display()))?;
        }
    }
    Ok(())
}

/// `extractArchiveToDir` for a zip: entry names are UTF-8 or GBK, and none may leave `target`.
pub(super) fn extract_zip(ctx: &Context, archive: &Path, target: &Path, label: &str) -> Result<()> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(archive)?).context("整合包不是有效的ZIP文件")?;
    let mut entries = Vec::new();
    for index in 0..zip.len() {
        let entry = zip.by_index_raw(index)?;
        let name = crate::import::archive::decode_name(entry.name_raw()).replace('\\', "/");
        let name = name.trim_start_matches('/').to_owned();
        if name.trim().is_empty() {
            continue;
        }
        let directory = entry.is_dir() || name.ends_with('/');
        let mut parts: Vec<&str> = Vec::new();
        for part in name.split('/') {
            match part {
                "" | "." => {}
                ".." => ensure!(parts.pop().is_some(), "非法文件路径: {name}"),
                part => parts.push(part),
            }
        }
        entries.push((index, parts.join("/"), directory));
    }
    let total = entries.iter().filter(|e| !e.2).count().max(1);
    let mut done = 0;
    for (index, path, directory) in entries {
        ctx.cancel.check()?;
        if path.is_empty() {
            continue;
        }
        let resolved = target.join(&path);
        if directory {
            std::fs::create_dir_all(&resolved)?;
            continue;
        }
        if let Some(parent) = resolved.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut output = std::fs::File::create(&resolved).with_context(|| format!("写入{path}失败"))?;
        std::io::copy(&mut zip.by_index(index)?, &mut output).with_context(|| format!("解压{path}失败"))?;
        done += 1;
        (ctx.progress)(Progress::Percent(format!("{label}({done}/{total})"), done as f32 / total as f32));
    }
    Ok(())
}

/// `prepareModpackSource`: the pack in a new folder of the work folder.
fn prepare_source(ctx: &Context, input: &Path) -> Result<PathBuf> {
    ctx.cancel.check()?;
    let root = ctx.temp("pack-", "");
    std::fs::create_dir_all(&root)?;
    let copied = if input.is_dir() {
        ctx.phase("正在复制整合包目录");
        copy_dir(ctx, input, &root).map(|()| (ctx.progress)(Progress::Percent("整合包目录复制完成".into(), 1.0)))
    } else {
        ctx.phase("正在解压整合包");
        extract_zip(ctx, input, &root, "正在解压整合包").map(|()| (ctx.progress)(Progress::Percent("整合包解压完成".into(), 1.0)))
    };
    if let Err(error) = copied {
        remove_tree(&root);
        return Err(error);
    }
    Ok(root)
}

pub(super) fn remove_tree(path: &Path) {
    if let Err(error) = std::fs::remove_dir_all(path)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(path = %path.display(), %error, "清理上传临时目录失败");
    }
}

/// `findFile`: the first file of that name, parents first.
fn find_file(root: &Path, name: &str) -> Result<Option<PathBuf>> {
    Ok(walk_files(root)?.into_iter().find(|f| f.file_name().is_some_and(|n| n == name)))
}

/// `collectEmbeddedModFiles`: jars the game would load from `mods`, one per game path and, per
/// override layer, one per mod.
fn collect_embedded(root: &Path) -> Result<Vec<PathBuf>> {
    let candidates: Vec<(PathBuf, rules::EntryPath)> = walk_files(root)?
        .into_iter()
        .filter(|f| extension_is(f, "jar"))
        .filter_map(|f| {
            let mapped = client_entry_path(&relative(root, &f), true)?;
            mapped.relative.get(..5).is_some_and(|p| p.eq_ignore_ascii_case("mods/")).then_some((f, mapped))
        })
        .collect();
    let mut layers: Vec<(u8, Vec<PathBuf>)> = Vec::new();
    for (file, mapped) in rules::path_winners(candidates) {
        match layers.iter_mut().find(|(p, _)| *p == mapped.priority) {
            Some((_, files)) => files.push(file),
            None => layers.push((mapped.priority, vec![file])),
        }
    }
    Ok(layers.into_iter().flat_map(|(_, files)| rules::preferred(files).0).collect())
}

/// `matchEmbeddedModsMR`.
fn match_mr(ctx: &Context, files: &[PathBuf]) -> Result<Matches> {
    if files.is_empty() {
        return Ok(Matches::default());
    }
    let hashes: Vec<String> = files.iter().map(|f| ctx.sha1(f)).collect::<Result<_>>()?;
    let versions = ctx.runtime.block_on(ctx.platforms.mr_versions(&hashes))?;
    if versions.is_empty() {
        return Ok(Matches { unmatched: files.to_vec(), ..Default::default() });
    }
    let mut ids: Vec<String> = Vec::new();
    for version in versions.values() {
        if !ids.contains(&version.project_id) {
            ids.push(version.project_id.clone());
        }
    }
    let projects: BTreeMap<String, _> = ctx.runtime.block_on(ctx.platforms.mr_projects(&ids))?.into_iter().map(|p| (p.id.clone(), p)).collect();
    let mut out = Matches::default();
    for (file, sha1) in files.iter().zip(&hashes) {
        let Some(version) = versions.get(sha1) else {
            out.unmatched.push(file.clone());
            continue;
        };
        let project = projects.get(&version.project_id);
        let stem = file.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
        let slug = project
            .map(|p| p.slug.clone())
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| if stem.trim().is_empty() { version.project_id.clone() } else { stem });
        let url = version.files.iter().find(|f| f.hashes.get("sha1") == Some(sha1)).map(|f| f.url.clone());
        out.mods.push(Match {
            m: Mod {
                platform: "mr".into(),
                project_id: version.project_id.clone(),
                slug,
                file_id: version.id.clone(),
                hash: sha1.clone(),
                side: project.map(|p| p.side()).unwrap_or(Side::Both),
                download_urls: url.into_iter().collect(),
                client_only_override: false,
                client_override_replaced: false,
            },
            file: file.clone(),
        });
        out.matched.push(file.clone());
    }
    Ok(out)
}

/// `matchEmbeddedModsCF` with `loadInfoCurseForge` and `getInfosFromHash`.
fn match_cf(ctx: &Context, files: &[PathBuf]) -> Result<Matches> {
    if files.is_empty() {
        return Ok(Matches::default());
    }
    let nothing = || Matches { unmatched: files.to_vec(), ..Default::default() };
    // `associateBy`: of files with one fingerprint the last stays, at the first one's place.
    let mut by_print: Vec<(u32, PathBuf)> = Vec::new();
    for file in files {
        let print = ctx.murmur2(file)?;
        match by_print.iter_mut().find(|(p, _)| *p == print) {
            Some(slot) => slot.1 = file.clone(),
            None => by_print.push((print, file.clone())),
        }
    }
    let prints: Vec<u32> = by_print.iter().map(|(p, _)| *p).collect();
    let found = ctx.runtime.block_on(ctx.platforms.cf_fingerprints(&prints))?;
    struct Record {
        project: i64,
        file_id: String,
        print: String,
        file: PathBuf,
        side: Side,
    }
    let mut records: Vec<Record> = Vec::new();
    for hit in &found {
        if hit.id <= 0 {
            continue;
        }
        let Some((_, file)) = by_print.iter().find(|(p, _)| i64::from(*p) == hit.file.file_fingerprint) else { continue };
        records.push(Record {
            project: hit.id,
            file_id: hit.file.id.to_string(),
            print: hit.file.file_fingerprint.to_string(),
            file: file.clone(),
            side: hit.file.side().unwrap_or(Side::Both),
        });
    }
    let mut project_ids: Vec<i64> = Vec::new();
    for record in &records {
        if !project_ids.contains(&record.project) {
            project_ids.push(record.project);
        }
    }
    if project_ids.is_empty() {
        return Ok(nothing());
    }
    let projects: Vec<CfMod> = ctx.runtime.block_on(ctx.platforms.cf_mods(&project_ids, true))?;
    let mut out = Matches::default();
    for project in &projects {
        let slug = match (project.slug.trim(), project.name.as_deref().map(str::trim)) {
            (slug, _) if !slug.is_empty() => slug.to_owned(),
            (_, Some(name)) if !name.is_empty() => name.to_owned(),
            _ => project.id.to_string(),
        };
        for record in records.iter().filter(|r| r.project == project.id) {
            // A jar that declares its side (Fabric) is believed before the platform.
            let side = super::jar_meta::read(&record.file).and_then(|m| m.side).unwrap_or(record.side);
            out.mods.push(Match {
                m: Mod {
                    platform: "cf".into(),
                    project_id: project.id.to_string(),
                    slug: slug.clone(),
                    file_id: record.file_id.clone(),
                    hash: record.print.clone(),
                    side,
                    download_urls: vec![],
                    client_only_override: false,
                    client_override_replaced: false,
                },
                file: record.file.clone(),
            });
            if !out.matched.contains(&record.file) {
                out.matched.push(record.file.clone());
            }
        }
    }
    if out.mods.is_empty() {
        return Ok(nothing());
    }
    out.unmatched = by_print.into_iter().map(|(_, f)| f).filter(|f| !out.matched.contains(f)).collect();
    Ok(out)
}

/// `serverModMergeKey`.
fn merge_key(found: &Match) -> String {
    if let Some(id) = super::jar_meta::read(&found.file).and_then(|m| m.mod_id).filter(|id| !id.trim().is_empty()) {
        return format!("modid:{id}");
    }
    let m = &found.m;
    if !m.slug.trim().is_empty() {
        format!("slug:{}", m.slug.trim().to_lowercase())
    } else if !m.project_id.trim().is_empty() {
        format!("project:{}", m.project_id.trim())
    } else {
        format!("{}:{}:{}:{}", m.platform, m.project_id, m.file_id, m.hash)
    }
}

/// `distinctBy(::serverModMergeKey)`.
pub(super) fn distinct_matches(found: Vec<Match>) -> Vec<Match> {
    let mut keys: Vec<String> = Vec::new();
    found
        .into_iter()
        .filter(|m| {
            let key = merge_key(m);
            let new = !keys.contains(&key);
            keys.push(key);
            new
        })
        .collect()
}

/// `matchLocalModFiles`: Modrinth first, CurseForge for the rest.
pub(super) fn match_local(ctx: &Context, files: &[PathBuf]) -> Result<Matches> {
    if files.is_empty() {
        return Ok(Matches::default());
    }
    ctx.phase(format!("发现服务端目录中的mod{}个，上网搜索信息中", files.len()));
    let mr = match_mr(ctx, files).map_err(|error| anyhow!("Modrinth匹配内置mod失败: {error:#}"))?;
    ctx.phase(format!("Modrinth找到{}个 开始搜索CurseForge", mr.mods.len()));
    let cf = match_cf(ctx, &mr.unmatched).map_err(|error| anyhow!("CurseForge匹配内置mod失败: {error:#}"))?;
    ctx.phase(format!("匹配完成：MR{}个，CF{} 个，处理结果中，请等一分钟...", mr.mods.len(), cf.mods.len()));
    let mut matched = mr.matched;
    matched.extend(cf.matched);
    Ok(Matches { mods: distinct_matches(mr.mods.into_iter().chain(cf.mods).collect()), matched, unmatched: cf.unmatched })
}

/// `stageMatchedEmbeddedMods`: copies under the name the content store uses.
pub(super) fn stage_mods(ctx: &Context, found: &[Match]) -> Result<Vec<EmbeddedMod>> {
    if found.is_empty() {
        return Ok(vec![]);
    }
    let dir = ctx.temp("embedded-mods-", "");
    let staged = (|| -> Result<Vec<EmbeddedMod>> {
        std::fs::create_dir_all(&dir)?;
        found
            .iter()
            .map(|hit| {
                ensure!(hit.file.is_file(), "识别的内嵌mod文件不存在: {}", hit.file.display());
                let target = dir.join(hit.m.file_name());
                std::fs::copy(&hit.file, &target)?;
                Ok(EmbeddedMod { m: hit.m.clone(), staged: target, original_name: name_of(&hit.file) })
            })
            .collect()
    })();
    staged.map_err(|error| {
        remove_tree(&dir);
        anyhow!("暂存内嵌mod失败: {error:#}")
    })
}

/// A resource or shader pack lying in the pack: Kotlin `ExtraCandidate`.
#[derive(Clone, Debug, PartialEq)]
struct Candidate {
    file: PathBuf,
    directory: bool,
    source_relative: String,
    game_path: String,
    priority: u8,
}

impl Candidate {
    fn key(&self) -> String {
        policy::game_path_lower(&self.game_path)
    }
    /// `byteIdentity`.
    fn identity(&self, ctx: &Context) -> Result<String> {
        if !self.directory {
            return Ok(format!("file:{}", ctx.sha1(&self.file)?));
        }
        let mut parts: Vec<(String, String)> = Vec::new();
        for file in walk_files(&self.file)? {
            parts.push((relative(&self.file, &file), ctx.sha1(&file)?));
        }
        parts.sort();
        Ok(format!("directory:{}", parts.iter().map(|(p, h)| format!("{p}:{h}")).collect::<Vec<_>>().join("|")))
    }
    fn kind(&self) -> extras::Kind {
        if self.game_path.get(..14).is_some_and(|p| p.eq_ignore_ascii_case("resourcepacks/")) { extras::Kind::ResPack } else { extras::Kind::ShaderPack }
    }
}

/// `collectEffectiveLocalExtraCandidates`: per game path the highest layer; packs of one layer
/// that differ are a conflict.
fn extra_candidates(ctx: &Context, root: &Path) -> Result<Vec<Candidate>> {
    let roots = [
        ("resourcepacks", "resourcepacks"),
        ("shaderpacks", "shaderpacks"),
        ("overrides/resourcepacks", "resourcepacks"),
        ("overrides/shaderpacks", "shaderpacks"),
        ("client-overrides/resourcepacks", "resourcepacks"),
        ("client-overrides/shaderpacks", "shaderpacks"),
    ];
    let mut all: Vec<Candidate> = Vec::new();
    for (folder, game_root) in roots {
        let dir = root.join(folder);
        if !dir.is_dir() {
            continue;
        }
        for (child, directory) in children(&dir)? {
            if !directory && !extension_is(&child, "zip") {
                continue;
            }
            let source_relative = relative(root, &child);
            let Some(mapped) = client_entry_path(&source_relative, true) else { continue };
            let prefix = format!("{game_root}/");
            if !mapped.relative.get(..prefix.len()).is_some_and(|p| p.eq_ignore_ascii_case(&prefix)) {
                continue;
            }
            all.push(Candidate { file: child, directory, source_relative, game_path: mapped.relative, priority: mapped.priority });
        }
    }
    let mut keys: Vec<String> = Vec::new();
    for candidate in &all {
        let key = candidate.key();
        if !keys.contains(&key) {
            keys.push(key);
        }
    }
    let mut effective = Vec::new();
    for key in keys {
        let same: Vec<&Candidate> = all.iter().filter(|c| c.key() == key).collect();
        let top = same.iter().map(|c| c.priority).max().unwrap_or_default();
        let peers: Vec<&Candidate> = same.into_iter().filter(|c| c.priority == top).collect();
        let mut identities: Vec<String> = Vec::new();
        for peer in &peers {
            let identity = peer.identity(ctx)?;
            if !identities.contains(&identity) {
                identities.push(identity);
            }
        }
        ensure!(identities.len() == 1, "客户端额外内容路径冲突: {}", peers.iter().map(|p| name_of(&p.file)).collect::<Vec<_>>().join("、"));
        effective.push(peers[0].clone());
    }
    Ok(effective)
}

/// `stageEmbeddedClientExtra`: the copy is made only when the bytes still are the matched ones.
fn stage_extra(ctx: &Context, content: Content, candidate: &Candidate, expected_sha1: &str, staged: &Path) -> Result<EmbeddedExtra> {
    let relative = &candidate.source_relative;
    ensure!(candidate.file.is_file(), "客户端额外内容源文件不存在: {relative}");
    let expected = expected_sha1.trim().to_lowercase();
    ensure!(!expected.is_empty(), "客户端额外内容缺少SHA-1: {relative}");
    ensure!(ctx.sha1(&candidate.file)?.eq_ignore_ascii_case(&expected), "客户端额外内容在暂存前发生变化: {relative}");
    std::fs::copy(&candidate.file, staged)?;
    ensure!(staged.is_file() && ctx.sha1(staged)?.eq_ignore_ascii_case(&expected), "客户端额外内容暂存校验失败: {relative}");
    Ok(EmbeddedExtra { content, staged: staged.to_path_buf(), source_relative: relative.clone(), sha1: expected })
}

/// `matchEmbeddedClientExtras`: zip packs of the pack that Modrinth or CurseForge knows.
fn match_extras(ctx: &Context, root: &Path) -> Result<Vec<EmbeddedExtra>> {
    let candidates: Vec<Candidate> = extra_candidates(ctx, root)?
        .into_iter()
        .filter(|c| !c.directory && extension_is(&c.file, "zip") && !name_of(&c.file).to_lowercase().ends_with(".zip.txt"))
        .collect();
    if candidates.is_empty() {
        return Ok(vec![]);
    }
    let lookup = |label: &str, error: anyhow::Error| anyhow!("{label}失败: {error:#}");
    let hashes: Vec<String> = candidates.iter().map(|c| Ok(ctx.sha1(&c.file)?.to_lowercase())).collect::<Result<_>>()?;
    let versions = ctx.runtime.block_on(ctx.platforms.mr_versions(&hashes)).map_err(|e| lookup("Modrinth客户端额外内容匹配", e))?;
    let mut ids: Vec<String> = Vec::new();
    for version in versions.values() {
        if !ids.contains(&version.project_id) {
            ids.push(version.project_id.clone());
        }
    }
    let projects: BTreeMap<String, _> = if versions.is_empty() {
        BTreeMap::new()
    } else {
        let found = ctx.runtime.block_on(ctx.platforms.mr_projects(&ids)).map_err(|e| lookup("Modrinth客户端额外内容项目解析", e))?;
        found.into_iter().map(|p| (p.id.clone(), p)).collect()
    };
    // Content, the candidate's index and the SHA-1 the platform lists.
    let mut matched: Vec<(Content, usize, String)> = Vec::new();
    for (index, (candidate, sha1)) in candidates.iter().zip(&hashes).enumerate() {
        let Some(version) = versions.get(sha1) else { continue };
        let Some(file) = version.files.iter().find(|f| f.hashes.get("sha1").is_some_and(|h| h.eq_ignore_ascii_case(sha1))) else { continue };
        let expected = file.hashes.get("sha1").map(|h| h.trim().to_lowercase()).unwrap_or_default();
        if expected.is_empty() || !sha1.eq_ignore_ascii_case(&expected) {
            continue;
        }
        let slug = projects.get(&version.project_id).map(|p| p.slug.clone()).filter(|s| !s.trim().is_empty()).unwrap_or_else(|| version.project_id.clone());
        let content = Content {
            platform: extras::Platform::Modrinth,
            kind: candidate.kind(),
            project_id: version.project_id.clone(),
            file_id: version.id.clone(),
            slug,
            hash: sha1.clone(),
            path: Some(candidate.game_path.clone()),
            side: extras::Side::Client,
            required: true,
            download_urls: vec![file.url.clone()],
        };
        matched.push((content, index, expected));
    }
    let unresolved: Vec<usize> = (0..candidates.len()).filter(|i| !matched.iter().any(|m| m.1 == *i)).collect();
    if !unresolved.is_empty() {
        let prints: Vec<u32> = unresolved.iter().map(|i| ctx.murmur2(&candidates[*i].file)).collect::<Result<_>>()?;
        let mut asked: Vec<u32> = Vec::new();
        for print in &prints {
            if !asked.contains(print) {
                asked.push(*print);
            }
        }
        let found = ctx.runtime.block_on(ctx.platforms.cf_fingerprints(&asked)).map_err(|e| lookup("CurseForge客户端额外内容指纹匹配", e))?;
        let mut project_ids: Vec<i64> = Vec::new();
        for hit in &found {
            if hit.id > 0 && !project_ids.contains(&hit.id) {
                project_ids.push(hit.id);
            }
        }
        let cf_projects: BTreeMap<i64, CfMod> = if project_ids.is_empty() {
            BTreeMap::new()
        } else {
            let found = ctx.runtime.block_on(ctx.platforms.cf_mods(&project_ids, false)).map_err(|e| lookup("CurseForge客户端额外内容项目解析", e))?;
            found.into_iter().map(|m| (m.id, m)).collect()
        };
        for (index, print) in unresolved.iter().zip(&prints) {
            let candidate = &candidates[*index];
            let Some(hit) = found.iter().find(|h| h.file.file_fingerprint == i64::from(*print)) else { continue };
            let Some(project) = cf_projects.get(&hit.id) else { continue };
            // `mapCurseForgeExtraCandidate`.
            let kind = match project.class_id {
                Some(CfMod::CLASS_RESOURCE_PACK) => extras::Kind::ResPack,
                Some(CfMod::CLASS_SHADER_PACK) => extras::Kind::ShaderPack,
                _ => continue,
            };
            let expected = hit.file.sha1();
            if hit.file.mod_id != project.id || expected.is_empty() || expected != hashes[*index] {
                continue;
            }
            let content = Content {
                platform: extras::Platform::CurseForge,
                kind,
                project_id: project.id.to_string(),
                file_id: hit.file.id.to_string(),
                slug: if project.slug.trim().is_empty() { project.id.to_string() } else { project.slug.clone() },
                hash: hit.file.file_fingerprint.to_string(),
                path: Some(candidate.game_path.clone()),
                side: extras::Side::Client,
                required: true,
                download_urls: vec![hit.file.real_download_url()?],
            };
            matched.push((content, *index, expected));
        }
    }
    let mut staged: Vec<PathBuf> = Vec::new();
    let result: Result<Vec<EmbeddedExtra>> = matched
        .into_iter()
        .map(|(content, index, expected)| {
            let target = ctx.temp("embedded-extra-", ".zip");
            staged.push(target.clone());
            stage_extra(ctx, content, &candidates[index], &expected, &target)
        })
        .collect();
    result.map_err(|error| {
        for file in &staged {
            if let Err(cleanup) = std::fs::remove_file(file)
                && cleanup.kind() != std::io::ErrorKind::NotFound
            {
                tracing::warn!(path = %file.display(), error = %cleanup, "无法清理失败的客户端额外内容暂存");
            }
        }
        anyhow!("暂存客户端额外内容失败: {error:#}")
    })
}

/// `mergeEmbeddedClientExtras`: a pack lying in the pack replaces the manifest's entry for its
/// path; a recognised one takes the entry's place.
fn merge_extras(ctx: &Context, root: &Path, declared: Vec<Content>, matched: Vec<Content>) -> Result<Vec<Content>> {
    let local: Vec<(String, bool)> = extra_candidates(ctx, root)?.into_iter().map(|c| (c.key(), c.directory)).collect();
    let mut merged: Vec<(String, Content)> = Vec::new();
    for content in declared {
        let target = content.target()?;
        let key = policy::game_path_lower(&target);
        if local.iter().any(|(path, directory)| key == *path || (*directory && key.starts_with(&format!("{path}/")))) {
            continue;
        }
        match merged.iter().find(|(k, _)| *k == key) {
            Some((_, old)) if *old != content => bail!("客户端额外内容路径冲突: {target}"),
            Some(_) => {}
            None => merged.push((key, content)),
        }
    }
    for content in matched {
        let key = policy::game_path_lower(&content.target()?);
        match merged.iter_mut().find(|(k, _)| *k == key) {
            Some(slot) => slot.1 = content,
            None => merged.push((key, content)),
        }
    }
    Ok(merged.into_iter().map(|(_, c)| c).collect())
}

/// `containsExcludedMcaArchiveEntry`: a zip or jar that holds a save's region files.
pub(super) fn archive_has_save_regions(file: &Path) -> Result<bool> {
    if !extension_is(file, "zip") && !extension_is(file, "jar") {
        return Ok(false);
    }
    let mut zip = zip::ZipArchive::new(std::fs::File::open(file)?).with_context(|| format!("无法读取{}", name_of(file)))?;
    let mut entries: Vec<(String, bool)> = Vec::new();
    for index in 0..zip.len() {
        let entry = zip.by_index_raw(index)?;
        entries.push((crate::import::archive::decode_name(entry.name_raw()), entry.is_dir()));
    }
    let regions = SaveRegions::of(entries.iter().filter(|e| !e.1).map(|e| e.0.as_str()));
    Ok(entries.iter().any(|(name, directory)| regions.is_save_region(name, *directory)))
}

/// `containsExcludedMcaFiles`.
fn contains_mca(root: &Path) -> Result<bool> {
    let files: Vec<(PathBuf, String)> = walk_files(root)?.into_iter().map(|f| { let r = relative(root, &f); (f, r) }).collect();
    let regions = SaveRegions::of(files.iter().map(|f| f.1.as_str()));
    for (file, path) in &files {
        if regions.is_save_region(path, false) {
            return Ok(true);
        }
        if !policy::skip_entry(&path.to_lowercase(), false, true, &[]) && archive_has_save_regions(file)? {
            return Ok(true);
        }
    }
    Ok(false)
}

/// `loadUploadPayloadMods` and the rest of `loadLocalModpack`, on the extracted pack.
fn load_from(ctx: &Context, summary: Summary, root: &Path) -> Result<Loaded> {
    ctx.phase("整合包结构检查完成");
    // The strict parse of Kotlin's data classes: a manifest missing a required field stops here.
    let (index, cf_manifest) = match summary.kind {
        Kind::Modrinth => {
            ctx.phase("读取Modrinth概要文件");
            let file = find_file(root, "modrinth.index.json")?.context("整合包缺少文件：modrinth.index.json")?;
            let index: Index = serde_json::from_str(&std::fs::read_to_string(&file)?).map_err(|error| anyhow!("modrinth概要文件解析失败: {error}"))?;
            (Some(index), None)
        }
        Kind::CurseForge => {
            ctx.phase("读取CurseForge概要文件");
            let file = find_file(root, "manifest.json")?.context("整合包缺少文件：manifest.json")?;
            let manifest: CfManifest = serde_json::from_str(&std::fs::read_to_string(&file)?).map_err(|error| anyhow!("manifest.json 解析失败: {error}"))?;
            (None, Some(manifest))
        }
    };
    ctx.cancel.check()?;
    let fabric = summary.loader == "Fabric";
    let shown = |text: &str, default: &str| if text.trim().is_empty() { default.to_owned() } else { text.to_owned() };
    ctx.phase(format!("整合包概要读取完成 {} {}", shown(&summary.name, "未命名整合包"), shown(&summary.version, "1.0")));
    ctx.phase(format!("开始整理Mod列表 MC{} {}", crate::import::scanner::version_label(&summary.mc_version), summary.loader));

    ctx.phase("扫描包内内置mods");
    let embedded = collect_embedded(root)?;
    ctx.phase(if embedded.is_empty() { "未发现包内内置mods".to_owned() } else { format!("发现包内内置mods{}个", embedded.len()) });
    let found = match_local(ctx, &embedded)?;
    let client_override = |file: &Path| client_entry_path(&relative(root, file), false).is_some_and(|p| p.priority == 2);
    // Client override jars no platform knows: they replace the manifest mod at their game path.
    let raw_replacements: Vec<String> = found
        .unmatched
        .iter()
        .filter(|f| client_override(f))
        .filter_map(|f| client_entry_path(&relative(root, f), false).map(|p| p.relative.to_lowercase()))
        .collect();
    let resolved: Vec<Match> = found
        .mods
        .iter()
        .map(|hit| {
            let mut m = hit.m.clone();
            if fabric && let Some(side) = super::jar_meta::read(&hit.file).and_then(|meta| meta.side) {
                m.side = side;
            }
            if client_override(&hit.file) {
                m = rules::embedded_side(m, true);
            } else if client_entry_path(&relative(root, &hit.file), true).is_some_and(|p| raw_replacements.contains(&p.relative.to_lowercase())) {
                m.client_override_replaced = true;
            }
            Match { m, file: hit.file.clone() }
        })
        .collect();
    if !found.mods.is_empty() {
        ctx.phase(format!("已识别内置mods{}个", found.mods.len()));
    }
    // Kotlin maps the names of the matches as they came from the platforms.
    let embedded_names: BTreeMap<String, String> = found.mods.iter().filter(|hit| !name_of(&hit.file).trim().is_empty()).map(|hit| (hit.m.file_name(), name_of(&hit.file))).collect();
    let embedded_mods = resolved.iter().map(|hit| hit.m.clone());
    let (mut mods, declared) = match (&index, &cf_manifest) {
        (Some(index), _) => {
            ctx.phase("解析Modrinth整合包索引");
            let loaded = ctx.runtime.block_on(manifest::modrinth(&ctx.platforms, &index.files, fabric, ctx.mr_slugs))?;
            let entries: Vec<IndexEntry> = index.files.iter().map(|f| IndexEntry { path: f.path.clone(), sha1: f.hashes.sha1.clone(), downloads: f.downloads.clone() }).collect();
            let marked = rules::mark_raw_replacements(loaded.mods, &entries, &raw_replacements);
            (rules::distinct(marked.into_iter().chain(embedded_mods).collect()), loaded.extras)
        }
        (None, Some(manifest)) => {
            ctx.phase("解析CurseForge整合包清单");
            let base = ctx.runtime.block_on(manifest::curse_forge_mods(&ctx.platforms, &manifest.files))?;
            let declared = ctx.runtime.block_on(manifest::curse_forge_extras(&ctx.platforms, &manifest.files))?;
            (rules::distinct(base.into_iter().chain(embedded_mods).collect()), declared)
        }
        (None, None) => unreachable!("one manifest is read above"),
    };
    ctx.phase("整理Mod单双端属性");
    if !fabric {
        rules::post_process_sides(&mut mods);
    }
    let embedded_mods = stage_mods(ctx, &resolved)?;
    let embedded_extras = match_extras(ctx, root)?;
    let extras = merge_extras(ctx, root, declared, embedded_extras.iter().map(|e| e.content.clone()).collect())?;
    // Recognised jars come from the content store from now on.
    for file in &found.matched {
        if let Err(error) = std::fs::remove_file(file) {
            tracing::warn!(path = %file.display(), %error, "删除已识别的内置Mod失败");
        }
    }
    ctx.cancel.check()?;
    ctx.phase(format!("Mod列表整理完成，共{}个，准备进入编辑", mods.len()));
    let contains_mca = contains_mca(root)?;
    Ok(Loaded { summary, source_dir: root.to_path_buf(), mods, embedded_names, embedded_mods, extras, embedded_extras, contains_mca })
}

/// `loadLocalModpack`: `input` is a CurseForge or Modrinth zip or folder. On failure the
/// extracted copy is removed.
pub fn load(ctx: &Context, input: &Path) -> Result<Loaded> {
    std::fs::create_dir_all(&ctx.work_dir)?;
    ctx.phase("开始读取整合包");
    ctx.phase("准备解析整合包概要");
    ctx.phase("快速检查整合包结构");
    // The structure and manifest checks of stage A1; Kotlin reports manifest errors after the
    // extraction, with the same words.
    let summary = source::summary(input)?;
    let root = prepare_source(ctx, input).map_err(|error| if ctx.cancel.cancelled() { error } else { anyhow!("读取整合包文件失败: {error:#}") })?;
    load_from(ctx, summary, &root).inspect_err(|_| remove_tree(&root))
}
