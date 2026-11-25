//! A pack's manifest turned into its mod list and client extras (resource and shader packs):
//! Kotlin `ModrinthService.loadParsedModpack` with `resolveManifestEntries`, and
//! `CurseForgeService.mapManifestEntriesToMods` / `mapManifestEntriesToContents` with
//! `resolveManifestEntriesToContents`. The requests are in `platform`; the rules here are pure.
use super::platform::{CfFile, CfMod, MrProject, MrVersion, Platforms};
use crate::pack_install::extras::{self, Content, Kind};
use crate::pack_install::mods::{Mod, Side};
use anyhow::{Result, anyhow, bail, ensure};
use serde::{Deserialize, Deserializer};
use std::collections::{BTreeMap, BTreeSet};

/// kotlinx `coerceInputValues`: an explicit `null` takes the default.
fn null_default<'de, D: Deserializer<'de>, T: Default + Deserialize<'de>>(d: D) -> Result<T, D::Error> {
    Ok(Option::<T>::deserialize(d)?.unwrap_or_default())
}

fn yes() -> bool {
    true
}

#[derive(Clone, Copy, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EnvSide {
    Required,
    Optional,
    Unsupported,
}

#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct Env {
    pub client: EnvSide,
    #[serde(default)]
    pub server: Option<EnvSide>,
}

#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct Hashes {
    pub sha1: String,
    pub sha512: String,
}

#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexFile {
    pub path: String,
    pub hashes: Hashes,
    #[serde(default)]
    pub env: Option<Env>,
    #[serde(default, deserialize_with = "null_default")]
    pub downloads: Vec<String>,
    pub file_size: i64,
}

/// `ModrinthModpackIndex`, with the fields Kotlin requires.
#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Index {
    pub format_version: i64,
    pub game: String,
    pub version_id: String,
    pub name: String,
    #[serde(default, deserialize_with = "null_default")]
    pub files: Vec<IndexFile>,
    #[serde(default, deserialize_with = "null_default")]
    pub dependencies: BTreeMap<String, String>,
}

#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct CfManifestFile {
    #[serde(rename = "projectID")]
    pub project_id: i64,
    #[serde(rename = "fileID")]
    pub file_id: i64,
    #[serde(default = "yes")]
    pub required: bool,
}

#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfLoader {
    pub id: String,
    #[serde(default)]
    pub primary: bool,
}

#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfMinecraft {
    pub version: String,
    #[serde(default, deserialize_with = "null_default")]
    pub mod_loaders: Vec<CfLoader>,
}

/// `CurseForgePackManifest`.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct CfManifest {
    pub name: String,
    pub version: String,
    pub minecraft: CfMinecraft,
    #[serde(default, deserialize_with = "null_default")]
    pub files: Vec<CfManifestFile>,
}

#[derive(Debug, Default)]
pub struct Resolution {
    pub mods: Vec<Mod>,
    pub extras: Vec<Content>,
}

/// `normalizeManifestPath`.
pub fn normalize_path(raw: &str) -> Result<String> {
    let original = raw.replace('\\', "/");
    ensure!(
        !original.trim().is_empty() && !original.starts_with('/') && !original.contains(':') && !original.contains('\0'),
        "整合包路径必须是相对路径: {raw}"
    );
    ensure!(!original.split('/').any(|s| s == ".."), "整合包路径包含非法上级目录: {raw}");
    Ok(original.split('/').filter(|s| !s.trim().is_empty() && *s != ".").collect::<Vec<_>>().join("/"))
}

/// `manifestContentType`: only `mods`, `resourcepacks` and `shaderpacks` entries are content.
pub fn content_kind(path: &str) -> Option<Kind> {
    // Bytes, so a path of Chinese characters is never cut inside one.
    let under = |root: &str| {
        let (p, r) = (path.as_bytes(), root.as_bytes());
        p.len() >= r.len() && p[..r.len()].eq_ignore_ascii_case(r) && (p.len() == r.len() || p[r.len()] == b'/')
    };
    if under("resourcepacks") {
        Some(Kind::ResPack)
    } else if under("shaderpacks") {
        Some(Kind::ShaderPack)
    } else if under("mods") {
        Some(Kind::Mod)
    } else {
        None
    }
}

/// `normalizeManifestDownloadUrl`: literal spaces of the path become `%20`; nothing else changes.
fn normalize_download_url(raw: &str) -> String {
    let url = raw.trim();
    let re = regex::Regex::new(r"(?i)^(https?://[^/?#]+)([^?#]*)(.*)$").unwrap();
    match re.captures(url) {
        Some(c) => format!("{}{}{}", &c[1], c[2].replace(' ', "%20"), &c[3]),
        None => url.to_owned(),
    }
}

/// `parseCurseForgeFileId`: `/files/1234/567/` is file 1234567 (the second part padded to 3).
fn cf_file_id(url: &str) -> Option<i64> {
    let re = regex::Regex::new(r"/files/(\d+)/(\d+)/").unwrap();
    let c = re.captures(url)?;
    format!("{}{:0>3}", &c[1], &c[2]).parse::<i32>().ok().map(i64::from)
}

fn unresolved(path: &str) -> anyhow::Error {
    anyhow!("无法解析整合包文件: {path}")
}

fn trimmed_sha1(file: &IndexFile) -> String {
    file.hashes.sha1.trim().to_lowercase()
}

/// `Env.toFabricManifestSide`.
fn fabric_side(env: Option<&Env>) -> Option<Side> {
    let env = env?;
    if env.client == EnvSide::Unsupported {
        Some(Side::Server)
    } else if env.server == Some(EnvSide::Unsupported) {
        Some(Side::Client)
    } else {
        None
    }
}

fn client_unsupported(file: &IndexFile) -> bool {
    file.env.as_ref().is_some_and(|e| e.client == EnvSide::Unsupported)
}

/// What the platforms said about a Modrinth index.
#[derive(Default)]
pub struct Lookups {
    /// By SHA-1.
    pub versions: BTreeMap<String, MrVersion>,
    /// By project id.
    pub projects: BTreeMap<String, MrProject>,
    /// By index into the files: the CurseForge file and project of an entry Modrinth does not know.
    pub fallback: BTreeMap<usize, (CfFile, CfMod)>,
    pub cf_to_mr_slug: BTreeMap<String, String>,
    /// By lower case slug.
    pub mr_by_slug: BTreeMap<String, MrProject>,
}

/// `appendExtra`.
fn append_extra(target: &mut Vec<(String, Content)>, content: Content) -> Result<()> {
    let path = content.path.clone().unwrap_or_default();
    let key = path.to_lowercase();
    let Some((_, previous)) = target.iter_mut().find(|(k, _)| *k == key) else {
        target.push((key, content));
        return Ok(());
    };
    let same = previous.platform == content.platform
        && previous.kind == content.kind
        && previous.project_id == content.project_id
        && previous.file_id == content.file_id
        && previous.hash.eq_ignore_ascii_case(&content.hash);
    ensure!(same, "客户端资源安装路径冲突: {path}");
    previous.required |= content.required;
    for url in content.download_urls {
        if !previous.download_urls.contains(&url) {
            previous.download_urls.push(url);
        }
    }
    Ok(())
}

/// `resolveManifestEntries`.
pub fn resolve(files: &[IndexFile], lookups: &Lookups, fabric: bool) -> Result<Resolution> {
    let mut mods = Vec::new();
    let mut extras: Vec<(String, Content)> = Vec::new();
    for (index, entry) in files.iter().enumerate() {
        let path = normalize_path(&entry.path)?;
        let Some(kind) = content_kind(&path) else { continue };
        let urls: Vec<String> = entry.downloads.iter().map(|u| normalize_download_url(u)).collect();
        if kind != Kind::Mod && client_unsupported(entry) {
            continue;
        }
        let both_unsupported = entry.env.as_ref().is_some_and(|e| e.client == EnvSide::Unsupported && e.server == Some(EnvSide::Unsupported));
        if kind == Kind::Mod && fabric && both_unsupported {
            continue;
        }
        let required = !entry.env.as_ref().is_some_and(|e| e.client == EnvSide::Optional);
        let sha1 = trimmed_sha1(entry);
        if let Some(version) = lookups.versions.get(&sha1).filter(|v| v.has_sha1(&sha1)) {
            let project = lookups.projects.get(&version.project_id).ok_or_else(|| unresolved(&entry.path))?;
            ensure!(!project.slug.trim().is_empty(), unresolved(&entry.path));
            if kind == Kind::Mod {
                let side = if fabric { fabric_side(entry.env.as_ref()).unwrap_or(project.side()) } else { project.side() };
                mods.push(Mod {
                    platform: "mr".into(),
                    project_id: version.project_id.clone(),
                    slug: project.slug.clone(),
                    file_id: version.id.clone(),
                    hash: sha1,
                    side,
                    download_urls: urls,
                    client_only_override: false,
                    client_override_replaced: false,
                });
            } else {
                let content = Content {
                    platform: extras::Platform::Modrinth,
                    kind,
                    project_id: version.project_id.clone(),
                    file_id: version.id.clone(),
                    slug: project.slug.clone(),
                    hash: sha1,
                    path: Some(path),
                    side: extras::Side::Client,
                    required,
                    download_urls: urls,
                };
                append_extra(&mut extras, extras::validate(&content)?)?;
            }
            continue;
        }
        let (file, project) = lookups.fallback.get(&index).ok_or_else(|| unresolved(&entry.path))?;
        ensure!(file.sha1() == sha1, "客户端文件SHA-1不匹配: {} (file {})", entry.path, file.id);
        ensure!(kind != Kind::Mod || project.class_id == Some(CfMod::CLASS_MOD), unresolved(&entry.path));
        ensure!(!project.slug.trim().is_empty(), unresolved(&entry.path));
        let slug = &project.slug;
        let by_slug = |s: &str| lookups.mr_by_slug.get(&s.trim().to_lowercase()).map(MrProject::side);
        let side = file
            .side()
            .or_else(|| lookups.cf_to_mr_slug.get(slug).and_then(|s| by_slug(s)))
            .or_else(|| by_slug(slug))
            .unwrap_or(Side::Unknown);
        if kind == Kind::Mod {
            let side = if fabric { fabric_side(entry.env.as_ref()).unwrap_or(side) } else { side };
            mods.push(Mod {
                platform: "cf".into(),
                project_id: project.id.to_string(),
                slug: slug.clone(),
                file_id: file.id.to_string(),
                hash: file.file_fingerprint.to_string(),
                side,
                download_urls: urls,
                client_only_override: false,
                client_override_replaced: false,
            });
        } else {
            let content = Content {
                platform: extras::Platform::CurseForge,
                kind,
                project_id: project.id.to_string(),
                file_id: file.id.to_string(),
                slug: slug.clone(),
                hash: file.file_fingerprint.to_string(),
                path: Some(path),
                side: extras::Side::Client,
                required,
                download_urls: urls,
            };
            append_extra(&mut extras, extras::validate(&content)?)?;
        }
    }
    let extras: Vec<Content> = extras.into_iter().map(|(_, c)| c).collect();
    Ok(Resolution { mods, extras: extras::merge(&extras)? })
}

/// `loadParsedModpack` after the manifest checks: asks the platforms, then `resolve`.
/// `mr_slugs` maps CurseForge slugs to Modrinth slugs (the launcher's mod catalog).
pub async fn modrinth(
    platforms: &Platforms,
    files: &[IndexFile],
    fabric: bool,
    mr_slugs: &(dyn Fn(&BTreeSet<String>) -> BTreeMap<String, String> + Send + Sync),
) -> Result<Resolution> {
    let mut supported: Vec<usize> = Vec::new();
    for (index, entry) in files.iter().enumerate() {
        let path = normalize_path(&entry.path)?;
        let Some(kind) = content_kind(&path) else { continue };
        if kind == Kind::Mod || !client_unsupported(entry) {
            supported.push(index);
        }
    }
    let mut hashes: Vec<String> = Vec::new();
    for &index in &supported {
        let sha1 = trimmed_sha1(&files[index]);
        if !hashes.contains(&sha1) {
            hashes.push(sha1);
        }
    }
    let versions = platforms.mr_versions(&hashes).await?;
    let project_ids: Vec<String> = versions.values().map(|v| v.project_id.clone()).collect();
    let projects: BTreeMap<String, MrProject> = platforms.mr_projects(&project_ids).await?.into_iter().map(|p| (p.id.clone(), p)).collect();

    let first_cf_id = |entry: &IndexFile| entry.downloads.iter().find_map(|u| cf_file_id(u));
    // Kotlin compares the untrimmed SHA-1 here, and the trimmed one below.
    let mut cf_ids: Vec<i64> = Vec::new();
    for &index in &supported {
        let entry = &files[index];
        let known = versions.get(&trimmed_sha1(entry)).is_some_and(|v| v.has_sha1(&entry.hashes.sha1));
        if !known && let Some(id) = first_cf_id(entry) && !cf_ids.contains(&id) {
            cf_ids.push(id);
        }
    }
    let cf_files: BTreeMap<i64, CfFile> = platforms.cf_files(&cf_ids).await?.into_iter().map(|f| (f.id, f)).collect();
    let mut cf_project_ids: Vec<i64> = Vec::new();
    for file in cf_files.values() {
        if !cf_project_ids.contains(&file.mod_id) {
            cf_project_ids.push(file.mod_id);
        }
    }
    let cf_projects: BTreeMap<i64, CfMod> = platforms.cf_mods(&cf_project_ids, false).await?.into_iter().map(|m| (m.id, m)).collect();

    let mut lookups = Lookups { versions, projects, ..Default::default() };
    for &index in &supported {
        let entry = &files[index];
        let sha1 = trimmed_sha1(entry);
        if lookups.versions.get(&sha1).is_some_and(|v| v.has_sha1(&sha1)) {
            continue;
        }
        let id = first_cf_id(entry).ok_or_else(|| unresolved(&entry.path))?;
        let file = cf_files.get(&id).ok_or_else(|| unresolved(&entry.path))?;
        ensure!(file.sha1() == sha1, "客户端文件SHA-1不匹配: {} (file {})", entry.path, file.id);
        let project = cf_projects.get(&file.mod_id).ok_or_else(|| unresolved(&entry.path))?;
        lookups.fallback.insert(index, (file.clone(), project.clone()));
    }
    let cf_slugs: BTreeSet<String> = lookups.fallback.values().map(|(_, p)| p.slug.clone()).collect();
    lookups.cf_to_mr_slug = mr_slugs(&cf_slugs);
    let mut candidates: Vec<String> = Vec::new();
    for (_, project) in lookups.fallback.values() {
        for slug in [lookups.cf_to_mr_slug.get(&project.slug), Some(&project.slug)].into_iter().flatten() {
            if !slug.trim().is_empty() && !candidates.contains(slug) {
                candidates.push(slug.clone());
            }
        }
    }
    lookups.mr_by_slug = platforms.mr_projects(&candidates).await?.into_iter().map(|p| (p.slug.trim().to_lowercase(), p)).collect();
    resolve(files, &lookups, fabric)
}

/// `mapManifestEntriesToMods`: entries whose project is not a mod, or whose file is unknown, are
/// left out.
pub async fn curse_forge_mods(platforms: &Platforms, files: &[CfManifestFile]) -> Result<Vec<Mod>> {
    let project_ids: Vec<i64> = files.iter().map(|f| f.project_id).collect();
    let file_ids: Vec<i64> = files.iter().map(|f| f.file_id).collect();
    let projects: BTreeMap<i64, CfMod> = platforms.cf_mods(&project_ids, true).await?.into_iter().map(|m| (m.id, m)).collect();
    let infos: BTreeMap<i64, CfFile> = platforms.cf_files(&file_ids).await?.into_iter().map(|f| (f.id, f)).collect();
    let mut hashes: Vec<String> = Vec::new();
    for info in infos.values() {
        let sha1 = info.sha1();
        if !sha1.trim().is_empty() && !hashes.contains(&sha1) {
            hashes.push(sha1);
        }
    }
    let versions = platforms.mr_versions(&hashes).await?;
    let mut mr_ids: Vec<String> = Vec::new();
    for version in versions.values() {
        if !mr_ids.contains(&version.project_id) {
            mr_ids.push(version.project_id.clone());
        }
    }
    let mr_projects: BTreeMap<String, MrProject> = platforms.mr_projects(&mr_ids).await?.into_iter().map(|p| (p.id.clone(), p)).collect();
    let mut mods = Vec::new();
    for entry in files {
        let Some(project) = projects.get(&entry.project_id) else {
            tracing::warn!(project = entry.project_id, file = entry.file_id, "CurseForge项目没有Mod信息，已跳过");
            continue;
        };
        let Some(info) = infos.get(&entry.file_id) else {
            tracing::error!(project = entry.project_id, file = entry.file_id, "CurseForge文件没有信息，已跳过");
            continue;
        };
        let mr_side = Some(info.sha1())
            .filter(|s| !s.trim().is_empty())
            .and_then(|s| versions.get(&s))
            .and_then(|v| mr_projects.get(&v.project_id))
            .map(MrProject::side);
        mods.push(Mod {
            platform: "cf".into(),
            project_id: project.id.to_string(),
            slug: project.slug.clone(),
            file_id: info.id.to_string(),
            hash: info.file_fingerprint.to_string(),
            side: info.side().or(mr_side).unwrap_or(Side::Unknown),
            download_urls: vec![],
            client_only_override: false,
            client_override_replaced: false,
        });
    }
    Ok(mods)
}

/// `resolveManifestEntriesToContents`: the resource packs (class 12) and shader packs (6552).
pub fn resolve_cf_extras(files: &[CfManifestFile], projects: &BTreeMap<i64, CfMod>, infos: &BTreeMap<i64, CfFile>) -> Result<Vec<Content>> {
    let mut out = Vec::new();
    for entry in files {
        let (project_id, file_id) = (entry.project_id, entry.file_id);
        let Some(project) = projects.get(&project_id) else {
            bail!("无法解析客户端资源项目: {project_id}/{file_id}");
        };
        let kind = match project.class_id {
            Some(CfMod::CLASS_RESOURCE_PACK) => Kind::ResPack,
            Some(CfMod::CLASS_SHADER_PACK) => Kind::ShaderPack,
            _ => continue,
        };
        let info = infos.get(&file_id).ok_or_else(|| anyhow!("无法解析客户端资源文件: {file_id}"))?;
        ensure!(info.mod_id == project_id, "客户端资源文件不属于声明的项目: {project_id}/{file_id}");
        let name = info.file_name.as_deref().map(str::trim).unwrap_or_default();
        let parts: Vec<&str> = name.split(['/', '\\']).collect();
        ensure!(
            !name.is_empty() && parts.len() == 1 && !parts.iter().any(|p| *p == "." || *p == ".." || p.trim().is_empty()),
            "客户端资源文件缺少文件名: {project_id}/{file_id}"
        );
        let root = if kind == Kind::ResPack { "resourcepacks" } else { "shaderpacks" };
        let content = Content {
            platform: extras::Platform::CurseForge,
            kind,
            project_id: project.id.to_string(),
            file_id: info.id.to_string(),
            slug: if project.slug.trim().is_empty() { project.id.to_string() } else { project.slug.clone() },
            // The CurseForge identity is the fingerprint; the SHA-1 stays with the file record.
            hash: info.file_fingerprint.to_string(),
            path: Some(format!("{root}/{name}")),
            side: extras::Side::Client,
            required: entry.required,
            download_urls: vec![info.real_download_url()?],
        };
        out.push(extras::validate(&content)?);
    }
    extras::merge(&out)
}

/// `mapManifestEntriesToContents`: asked without the mod class filter.
pub async fn curse_forge_extras(platforms: &Platforms, files: &[CfManifestFile]) -> Result<Vec<Content>> {
    if files.is_empty() {
        return Ok(vec![]);
    }
    let project_ids: Vec<i64> = files.iter().map(|f| f.project_id).collect();
    let file_ids: Vec<i64> = files.iter().map(|f| f.file_id).collect();
    let projects: BTreeMap<i64, CfMod> = platforms.cf_mods(&project_ids, false).await?.into_iter().map(|m| (m.id, m)).collect();
    let infos: BTreeMap<i64, CfFile> = platforms.cf_files(&file_ids).await?.into_iter().map(|f| (f.id, f)).collect();
    resolve_cf_extras(files, &projects, &infos)
}

/// The slug map `modrinth` needs, from the launcher's mod catalog: Kotlin
/// `modCatalog.getMetadataOrEmpty`, so a catalog failure is an empty map.
pub fn catalog_mr_slugs(catalog: &crate::catalog::Catalog, cf_slugs: &BTreeSet<String>) -> BTreeMap<String, String> {
    use crate::catalog::models::Platform;
    let refs: Vec<(Platform, String)> = cf_slugs.iter().map(|s| (Platform::CurseForge, s.clone())).collect();
    let metadata = match catalog.get_metadata(&refs) {
        Ok(metadata) => metadata,
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "读取Mod对照表失败，按没有对照处理");
            return BTreeMap::new();
        }
    };
    cf_slugs
        .iter()
        .filter_map(|slug| {
            let found = metadata.get(&(Platform::CurseForge, slug.clone()))?;
            let mr = found.projects.iter().find(|p| p.platform == Platform::Modrinth)?;
            Some((slug.clone(), mr.slug.clone()))
        })
        .collect()
}

#[cfg(test)]
pub(super) fn url_helpers_for_test(url: &str) -> (String, Option<i64>) {
    (normalize_download_url(url), cf_file_id(url))
}
