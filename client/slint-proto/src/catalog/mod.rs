//! Complete ModCatalog surface. Operations not yet reached from Slint stay available for later screens.
#![allow(dead_code)]
mod adapter;
mod cache;
pub mod disk;
mod identity;
pub mod models;
pub mod transport;
use crate::{
    digests::{Algorithm, hash},
    tasks::Cancellation,
};
use adapter::Adapter;
pub(crate) use adapter::url_batches;
use anyhow::{Context, Result, ensure};
use cache::MemoryCache;
use identity::IdentityIndex;
pub use models::*;
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
pub struct Catalog {
    adapters: BTreeMap<Platform, Adapter>,
    index: IdentityIndex,
    projects: MemoryCache<ProjectRef, Project>,
    files: MemoryCache<FileRef, File>,
    file_lists: MemoryCache<String, (Vec<File>, Option<usize>)>,
    slugs: MemoryCache<String, Project>,
    details: MemoryCache<ProjectRef, serde_json::Value>,
    changelogs: MemoryCache<FileRef, String>,
    file_loads: BTreeMap<Platform, tokio::sync::Mutex<()>>,
    project_loads: BTreeMap<Platform, tokio::sync::Mutex<()>>,
    /// Found projects for 15 days across launcher starts; `None` in tests and when it cannot open.
    disk: Option<disk::ProjectDiskCache>,
    http: transport::HttpTransport,
}
impl Catalog {
    pub fn new(http: reqwest::Client, data: &Path) -> Self {
        let mut http = transport::HttpTransport::new(
            http,
            transport::prefer_mirror(&data.join("config.toml")),
        );
        http.preference_file = Some(data.join("config.toml"));
        let mut network = transport::HttpTransport::new(http.http.clone(), http.prefer_mirror);
        network.preference_file = http.preference_file.clone();
        let tr: Arc<dyn transport::Transport> = Arc::new(network);
        let mut catalog = Self::with_transport(
            tr,
            http,
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../ui/mod-catalog/src/main/resources/mod_catalog.db"),
        );
        catalog.disk = match disk::ProjectDiskCache::open(&disk::path(data)) {
            Ok(cache) => Some(cache),
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), "Mod资料缓存不可用，仅使用内存缓存");
                None
            }
        };
        catalog
    }
    /// Tests: a disk cache at `path`.
    #[cfg(test)]
    pub fn with_disk_cache(mut self, path: &Path) -> Self {
        self.disk = Some(disk::ProjectDiskCache::open(path).unwrap());
        self
    }
    pub fn with_transport(
        tr: Arc<dyn transport::Transport>,
        http: transport::HttpTransport,
        path: PathBuf,
    ) -> Self {
        Self {
            adapters: [Platform::Modrinth, Platform::CurseForge]
                .into_iter()
                .map(|platform| {
                    (
                        platform,
                        Adapter {
                            platform,
                            transport: tr.clone(),
                        },
                    )
                })
                .collect(),
            index: IdentityIndex::open(&path),
            file_loads: [Platform::CurseForge, Platform::Modrinth]
                .into_iter()
                .map(|p| (p, tokio::sync::Mutex::new(())))
                .collect(),
            project_loads: [Platform::CurseForge, Platform::Modrinth]
                .into_iter()
                .map(|p| (p, tokio::sync::Mutex::new(())))
                .collect(),
            projects: MemoryCache::new(
                Duration::from_secs(600),
                Duration::from_secs(30),
                usize::MAX,
            ),
            files: MemoryCache::new(
                Duration::from_secs(600),
                Duration::from_secs(30),
                usize::MAX,
            ),
            file_lists: MemoryCache::new(Duration::from_secs(600), Duration::from_secs(30), 64),
            slugs: MemoryCache::new(
                Duration::from_secs(600),
                Duration::from_secs(30),
                usize::MAX,
            ),
            changelogs: MemoryCache::new(
                Duration::from_secs(600),
                Duration::from_secs(30),
                usize::MAX,
            ),
            details: MemoryCache::new(
                Duration::from_secs(600),
                Duration::from_secs(30),
                usize::MAX,
            ),
            disk: None,
            http,
        }
    }
    fn issues(&self) -> Vec<Issue> {
        self.index
            .unavailable
            .as_ref()
            .map(|s| vec![Issue::IdentityIndexUnavailable(s.clone())])
            .unwrap_or_default()
    }
    pub fn get_metadata(
        &self,
        refs: &[(Platform, String)],
    ) -> Result<BTreeMap<(Platform, String), Metadata>> {
        let mut result = BTreeMap::new();
        for (p, slug) in refs {
            if let Some(mut m) = self.index.find(*p, slug)? {
                if let Some(name) = m
                    .projects
                    .iter()
                    .find(|r| r.platform == *p)
                    .and_then(|r| r.name_cn.clone())
                {
                    m.name_cn = Some(name);
                }
                result.insert((*p, slug.clone()), m);
            }
        }
        Ok(result)
    }
    pub fn search_metadata(&self, query: &str, limit: usize) -> Result<Vec<Metadata>> {
        ensure!(limit > 0, "Metadata search limit must be positive");
        self.index.search(query, 0, limit)
    }
    fn group(&self, projects: Vec<Project>) -> Result<Vec<Mod>> {
        let mut groups: BTreeMap<String, (Option<Metadata>, Vec<Project>)> = BTreeMap::new();
        for p in projects {
            let record = if p.kind == ContentType::Mod {
                self.index.find(p.reference.platform, &p.slug)?
            } else {
                None
            };
            let key = record
                .as_ref()
                .map(|m| format!("mcmod:{}", m.mcmod_id))
                .unwrap_or_else(|| {
                    format!(
                        "{}:{}",
                        p.reference.platform.db().to_lowercase(),
                        p.reference.id
                    )
                });
            groups
                .entry(key)
                .or_insert_with(|| (record, vec![]))
                .1
                .push(p);
        }
        Ok(groups
            .into_iter()
            .map(|(identity, (metadata, mut sources))| {
                sources.sort_by_key(|s| {
                    if s.reference.platform == Platform::Modrinth {
                        0
                    } else {
                        1
                    }
                });
                sources.dedup_by(|a, b| a.reference == b.reference);
                let primary = &sources[0];
                let name_cn = metadata.as_ref().and_then(|m| {
                    m.projects
                        .iter()
                        .find(|p| p.platform == primary.reference.platform)
                        .and_then(|p| p.name_cn.clone())
                        .or_else(|| m.name_cn.clone())
                });
                let mut icons: Vec<_> = sources.iter().filter_map(|p| p.icon.clone()).collect();
                if let Some(icon) = metadata.as_ref().and_then(|m| m.icon.clone()) {
                    icons.push(icon);
                }
                icons.retain(|s| !s.trim().is_empty());
                let mut seen = BTreeSet::new();
                icons.retain(|s| seen.insert(s.clone()));
                Mod {
                    identity,
                    primary: primary.reference.clone(),
                    name: metadata
                        .as_ref()
                        .map(|m| m.name.clone())
                        .unwrap_or_else(|| primary.name.clone()),
                    name_cn,
                    summary: metadata
                        .as_ref()
                        .and_then(|m| m.intro.clone())
                        .filter(|s| !s.is_empty())
                        .unwrap_or_else(|| primary.summary.clone()),
                    icons,
                    downloads: sources.iter().map(|p| p.downloads).sum(),
                    updated: sources
                        .iter()
                        .map(|p| p.updated.clone())
                        .max()
                        .unwrap_or_default(),
                    environment: primary.environment,
                    sources,
                }
            })
            .collect())
    }
    pub async fn get_mods(
        &self,
        refs: &[ProjectRef],
    ) -> Result<Outcome<BTreeMap<ProjectRef, Mod>>> {
        let mut sources = vec![];
        let mut issues = self.issues();
        let mut successes = 0;
        for platform in [Platform::Modrinth, Platform::CurseForge] {
            let _guard = self.project_loads[&platform].lock().await;
            let mut missing = vec![];
            for r in refs.iter().filter(|r| r.platform == platform) {
                if let Some(cached) = self.projects.get(r) {
                    successes += 1;
                    if let Some(p) = cached {
                        sources.push(p);
                    }
                } else {
                    missing.push(r.id.clone());
                }
            }
            if let Some(disk) = self.disk.as_ref().filter(|_| !missing.is_empty()) {
                let wanted: Vec<_> = missing.iter().map(|id| ProjectRef { platform, id: id.clone() }).collect();
                let stored = disk.get_many(&wanted, std::time::SystemTime::now());
                missing.retain(|id| {
                    let r = ProjectRef { platform, id: id.clone() };
                    let Some(p) = stored.get(&r) else { return true };
                    successes += 1;
                    self.projects.put(r, Some(p.clone()));
                    sources.push(p.clone());
                    false
                });
            }
            if !missing.is_empty() {
                match self.adapters[&platform].projects(&missing).await {
                    Ok(found) => {
                        successes += 1;
                        if let Some(disk) = &self.disk {
                            disk.put_many(found.values(), std::time::SystemTime::now());
                        }
                        let mut absent = vec![];
                        for id in missing {
                            let r = ProjectRef {
                                platform,
                                id: id.clone(),
                            };
                            let p = found.get(&id).cloned();
                            self.projects.put(r.clone(), p.clone());
                            if let Some(p) = p {
                                sources.push(p);
                            } else {
                                absent.push(r);
                            }
                        }
                        if !absent.is_empty() {
                            issues.push(Issue::MissingProjects(absent));
                        }
                    }
                    Err(error) => {
                        tracing::warn!(%error,"Mod项目资料读取失败");
                        issues.push(Issue::SourceFailed(platform, error.to_string()));
                    }
                }
            }
        }
        ensure!(
            refs.is_empty() || successes > 0,
            CatalogError::AllSourcesFailed
        );
        let mut result = BTreeMap::new();
        for item in self.group(sources)? {
            for source in &item.sources {
                if refs.contains(&source.reference) {
                    result.insert(source.reference.clone(), item.clone());
                }
            }
        }
        Ok(Outcome {
            value: result,
            issues,
        })
    }
    pub async fn get_files(&self, refs: &[FileRef]) -> Result<Outcome<BTreeMap<FileRef, File>>> {
        let mut result = BTreeMap::new();
        let mut issues = self.issues();
        let mut successes = 0;
        for platform in [Platform::Modrinth, Platform::CurseForge] {
            let _guard = self.file_loads[&platform].lock().await;
            let mut missing = vec![];
            for r in refs.iter().filter(|r| r.platform == platform) {
                if let Some(cached) = self.files.get(r) {
                    successes += 1;
                    if let Some(f) = cached {
                        result.insert(r.clone(), f);
                    }
                } else {
                    missing.push(r.id.clone());
                }
            }
            if !missing.is_empty() {
                match self.adapters[&platform].files(&missing).await {
                    Ok(found) => {
                        successes += 1;
                        let mut absent = vec![];
                        for id in missing {
                            let r = FileRef {
                                platform,
                                id: id.clone(),
                            };
                            let f = found.get(&id).cloned();
                            self.files.put(r.clone(), f.clone());
                            if let Some(f) = f {
                                result.insert(r, f);
                            } else {
                                absent.push(r);
                            }
                        }
                        if !absent.is_empty() {
                            issues.push(Issue::MissingFiles(absent));
                        }
                    }
                    Err(error) => {
                        tracing::warn!(%error,"Mod文件资料读取失败");
                        issues.push(Issue::SourceFailed(platform, error.to_string()));
                    }
                }
            }
        }
        ensure!(
            refs.is_empty() || successes > 0,
            CatalogError::AllSourcesFailed
        );
        Ok(Outcome {
            value: result,
            issues,
        })
    }
    pub async fn match_files(&self, hashes: &[FileHashes]) -> Result<BTreeMap<String, File>> {
        let mut result = BTreeMap::new();
        let mut successful = false;
        for p in [Platform::CurseForge, Platform::Modrinth] {
            match self.adapters[&p].match_files(hashes).await {
                Ok(files) => {
                    successful = true;
                    for (k, f) in files {
                        self.files.put(f.reference.clone(), Some(f.clone()));
                        result.insert(k, f);
                    }
                }
                Err(error) => tracing::warn!(%error,"Mod文件匹配失败"),
            }
        }
        ensure!(successful, CatalogError::AllSourcesFailed);
        Ok(result)
    }
    pub async fn match_local_files(
        &self,
        paths: &[PathBuf],
        cancel: &Cancellation,
    ) -> Result<Outcome<LocalMatchReport>> {
        let mut hashes = vec![];
        let mut report = LocalMatchReport::default();
        for (i, path) in paths.iter().enumerate() {
            cancel.check()?;
            match (|| -> Result<FileHashes> {
                let meta = std::fs::symlink_metadata(path)?;
                ensure!(
                    meta.is_file() && !meta.file_type().is_symlink(),
                    "不是普通文件"
                );
                Ok(FileHashes {
                    key: i.to_string(),
                    sha1: hash(path, Algorithm::Sha1, cancel)?,
                    murmur2: Some(hash(path, Algorithm::Murmur2, cancel)?.parse()?),
                })
            })() {
                Ok(h) => hashes.push(h),
                Err(error) => {
                    cancel.check()?;
                    report.unreadable.insert(path.clone(), error.to_string());
                }
            }
        }
        let matched = self.match_files(&hashes).await?;
        for h in hashes {
            let path = paths[h.key.parse::<usize>()?].clone();
            if let Some(f) = matched.get(&h.key) {
                let mods = self.get_mods(std::slice::from_ref(&f.project)).await?;
                report.matched.push((
                    path,
                    vec![f.clone()],
                    mods.value.get(&f.project).map(|m| m.identity.clone()),
                ));
            } else {
                report.unmatched.push(path);
            }
        }
        Ok(Outcome {
            value: report,
            issues: self.issues(),
        })
    }
    /// `CurseForgeService.downloadHeadersFor`: the API key for HTTPS forgecdn and mirror hosts.
    /// U4e1: one Modrinth v2 GET through the catalog transport (mirror race, retries), for the
    /// 材质/光影 pages: `search`, `project/{id}` and `project/{id}/version`.
    pub async fn modrinth_get(&self, path: &str, query: Vec<(String, String)>) -> Result<serde_json::Value> {
        self.adapters[&Platform::Modrinth]
            .transport
            .request(Platform::Modrinth, reqwest::Method::GET, path, query, None)
            .await
    }
    /// M4: the transport itself (mirror race, API key, retries) for the upload's raw platform
    /// requests in `upload::platform`, which need fields the normalized models leave out.
    pub fn transport(&self) -> Arc<dyn transport::Transport> {
        self.adapters[&Platform::Modrinth].transport.clone()
    }
    pub fn download_headers_for(&self, url: &str) -> Result<reqwest::header::HeaderMap> {
        if !url.get(..8).is_some_and(|s| s.eq_ignore_ascii_case("https://")) {
            return Ok(Default::default());
        }
        self.http.download_headers(Platform::CurseForge, url)
    }
    pub fn resolve_download(&self, file: &File) -> Result<crate::download::Source> {
        let url = file
            .url
            .clone()
            .ok_or_else(|| CatalogError::DownloadUnavailable(file.reference.clone()))?;
        let parsed = reqwest::Url::parse(&url)?;
        ensure!(
            parsed.scheme() == "https" && parsed.host_str().is_some(),
            CatalogError::InvalidResponse(file.reference.platform)
        );
        Ok(crate::download::Source {
            headers: self.http.download_headers(file.reference.platform, &url)?,
            url,
        })
    }
    async fn file_list(
        &self,
        p: &ProjectRef,
        t: &Target,
        offset: usize,
        limit: usize,
    ) -> Result<(Vec<File>, Option<usize>)> {
        let key = format!(
            "{:?}|{}|{}|{}|{}|{}",
            p.platform,
            p.id,
            t.minecraft,
            t.loader,
            if p.platform == Platform::Modrinth {
                0
            } else {
                offset
            },
            if p.platform == Platform::Modrinth {
                0
            } else {
                limit
            }
        );
        let list = self
            .file_lists
            .get_or_load(key, async {
                Ok(Some(
                    self.adapters[&p.platform]
                        .list_files(p, t, offset, limit)
                        .await?,
                ))
            })
            .await?
            .context("File list cache loader returned null")?;
        for f in &list.0 {
            self.files.put(f.reference.clone(), Some(f.clone()));
        }
        Ok(list)
    }
    pub async fn list_files(&self, r: &FileRequest) -> Result<Outcome<FilePage>> {
        r.target.validate()?;
        ensure!(
            (1..=50).contains(&r.page_size) && !r.channels.is_empty(),
            "Invalid file page request"
        );
        let mut issues = Vec::new();
        let mut successful = false;
        let offset = r.cursor.map(|(_, n)| n).unwrap_or(0);
        let mut selected = FilePage {
            items: Vec::new(),
            cursor: None,
        };
        for p in [Platform::Modrinth, Platform::CurseForge] {
            if r.cursor.is_some_and(|(platform, _)| platform != p) {
                continue;
            }
            let Some(source) = r.item.sources.iter().find(|s| s.reference.platform == p) else {
                continue;
            };
            match self
                .file_list(
                    &source.reference,
                    &r.target,
                    if p == Platform::Modrinth { 0 } else { offset },
                    r.page_size,
                )
                .await
            {
                Ok((files, next)) => {
                    successful = true;
                    let compatible: Vec<_> = files
                        .into_iter()
                        .filter(|f| r.channels.contains(&f.channel))
                        .collect();
                    if p == Platform::Modrinth {
                        let items: Vec<_> = compatible
                            .iter()
                            .skip(offset)
                            .take(r.page_size)
                            .cloned()
                            .collect();
                        let next = offset + items.len();
                        selected = FilePage {
                            cursor: (!items.is_empty() && next < compatible.len())
                                .then_some((p, next)),
                            items,
                        };
                    } else {
                        selected = FilePage {
                            items: compatible,
                            cursor: next.map(|n| (p, n)),
                        };
                    }
                    if !selected.items.is_empty() || r.cursor.is_some() {
                        break;
                    }
                }
                Err(error) => issues.push(Issue::SourceFailed(p, error.to_string())),
            }
        }
        ensure!(successful, CatalogError::AllSourcesFailed);
        Ok(Outcome {
            value: selected,
            issues,
        })
    }
    pub async fn get_details(&self, item: &Mod) -> Result<Outcome<Details>> {
        let mut selected = None;
        let mut failures = Vec::new();
        for source in &item.sources {
            match self
                .details
                .get_or_load(source.reference.clone(), async {
                    Ok(Some(
                        self.adapters[&source.reference.platform]
                            .details(&source.reference)
                            .await?,
                    ))
                })
                .await
            {
                Ok(Some(value)) => {
                    selected = Some(value);
                    break;
                }
                Ok(None) => {}
                Err(error) => {
                    tracing::warn!(%error, "Mod详情来源失败");
                    failures.push(Issue::SourceFailed(
                        source.reference.platform,
                        error.to_string(),
                    ));
                }
            }
        }
        let v = selected.ok_or(CatalogError::AllSourcesFailed)?;
        let cf = v["id"].is_number();
        let names = |key: &str| {
            v[key]
                .as_array()
                .map(|a| {
                    a.iter()
                        .filter_map(|v| v["name"].as_str().or_else(|| v.as_str()))
                        .map(str::to_owned)
                        .collect()
                })
                .unwrap_or_default()
        };
        let string = |v: &serde_json::Value, key: &str| v[key].as_str().map(str::to_owned);
        Ok(Outcome {
            value: Details {
                item: item.clone(),
                description: string(&v, "body").unwrap_or_default(),
                authors: if cf { names("authors") } else { vec![] },
                categories: names("categories"),
                source_url: if cf {
                    string(&v["links"], "websiteUrl")
                } else {
                    Some(format!(
                        "https://modrinth.com/mod/{}",
                        adapter::text(&v, "slug")
                    ))
                },
                issues_url: string(
                    if cf { &v["links"] } else { &v },
                    if cf { "issuesUrl" } else { "issues_url" },
                ),
                wiki_url: string(
                    if cf { &v["links"] } else { &v },
                    if cf { "wikiUrl" } else { "wiki_url" },
                ),
            },
            issues: {
                let mut issues = self.issues();
                issues.extend(failures);
                issues
            },
        })
    }
    pub async fn get_changelog(&self, file: &FileRef) -> Result<String> {
        Ok(self
            .changelogs
            .get_or_load(file.clone(), async {
                Ok(Some(self.adapters[&file.platform].changelog(file).await?))
            })
            .await?
            .unwrap_or_default())
    }
    pub async fn search(&self, r: &SearchRequest) -> Result<Outcome<SearchPage>> {
        r.target.validate()?;
        ensure!(
            (1..=50).contains(&r.page_size),
            "Page size must be between 1 and 50"
        );
        let key = format!(
            "{}|{}|{}|{:?}|{}",
            r.query.trim(),
            r.target.minecraft,
            r.target.loader,
            r.sort,
            r.page_size
        );
        let has_query = !r.query.trim().is_empty();
        let mut cursor = r.cursor.clone().unwrap_or_default();
        ensure!(
            cursor.request_key.is_empty() || cursor.request_key == key,
            "Search cursor belongs to a different request"
        );
        cursor.request_key = key;
        if !has_query {
            cursor.local_exhausted = true;
        }
        let mut issues = self.issues();
        if has_query && cursor.buffer.len() < r.page_size && !cursor.local_exhausted {
            let limit = (r.page_size * 2).clamp(20, 50);
            let records = self.index.search(&r.query, cursor.local_offset, limit)?;
            cursor.local_offset += records.len();
            cursor.local_exhausted = records.len() < limit;
            if cursor.fallback_query.is_none() {
                cursor.fallback_query = Some(
                    if r.query
                        .chars()
                        .any(|c| matches!(c as u32,0x3400..=0x9fff|0x20000..=0x2ffff))
                    {
                        records
                            .first()
                            .map(|m| m.name.clone())
                            .unwrap_or_else(|| r.query.clone())
                    } else {
                        r.query.clone()
                    },
                );
            }
            let mut by_identity: BTreeMap<i64, Vec<Project>> = BTreeMap::new();
            for p in [Platform::Modrinth, Platform::CurseForge] {
                let mut budget = if p == Platform::CurseForge {
                    3
                } else {
                    usize::MAX
                };
                for record in &records {
                    for alias in record.projects.iter().filter(|a| a.platform == p) {
                        let key = format!(
                            "{:?}|{}|{}|{}",
                            p,
                            alias.slug.trim().to_lowercase(),
                            r.target.minecraft,
                            r.target.loader
                        );
                        let cached = self.slugs.get(&key);
                        let project = if let Some(value) = cached {
                            value
                        } else {
                            if budget == 0 {
                                break;
                            }
                            budget -= 1;
                            match self.adapters[&p]
                                .resolve_slugs(std::slice::from_ref(&alias.slug), &r.target)
                                .await
                            {
                                Ok(found) => {
                                    let value =
                                        found.get(&alias.slug.trim().to_lowercase()).cloned();
                                    self.slugs.put(key, value.clone());
                                    value
                                }
                                Err(error) => {
                                    issues.push(Issue::SourceFailed(p, error.to_string()));
                                    cursor.exhausted.insert(p);
                                    break;
                                }
                            }
                        };
                        if let Some(project) = project {
                            self.projects
                                .put(project.reference.clone(), Some(project.clone()));
                            by_identity
                                .entry(record.mcmod_id)
                                .or_default()
                                .push(project);
                            break;
                        }
                    }
                }
            }
            let mut buffered: BTreeSet<_> =
                cursor.buffer.iter().map(|m| m.identity.clone()).collect();
            for record in records {
                if let Some(projects) = by_identity.remove(&record.mcmod_id) {
                    for item in self.group(projects)? {
                        if !cursor.seen.contains(&item.identity)
                            && buffered.insert(item.identity.clone())
                        {
                            cursor.buffer.push(item);
                        }
                    }
                }
            }
        }
        let mut items = Vec::new();
        let mut retained = Vec::new();
        for item in std::mem::take(&mut cursor.buffer) {
            if cursor.seen.contains(&item.identity) {
                continue;
            }
            if items.len() < r.page_size {
                cursor.seen.insert(item.identity.clone());
                items.push(item);
            } else {
                retained.push(item);
            }
        }
        cursor.buffer = retained;
        let mut estimated = 0;
        let mut successful = false;
        if items.len() < r.page_size {
            for p in [Platform::Modrinth, Platform::CurseForge] {
                if cursor.exhausted.contains(&p)
                    || cursor
                        .platform_buffers
                        .get(&p)
                        .is_some_and(|v| !v.is_empty())
                {
                    continue;
                }
                let offset = *cursor.offsets.get(&p).unwrap_or(&0);
                match self.adapters[&p]
                    .search(
                        cursor.fallback_query.as_deref().unwrap_or(&r.query),
                        &r.target,
                        r.sort,
                        offset,
                        r.page_size,
                    )
                    .await
                {
                    Ok((sources, next, total)) => {
                        successful = true;
                        estimated += total.unwrap_or(0);
                        cursor
                            .platform_buffers
                            .entry(p)
                            .or_default()
                            .extend(sources);
                        if let Some(next) = next {
                            ensure!(next > offset, "Search offset did not advance");
                            cursor.offsets.insert(p, next);
                        } else {
                            cursor.exhausted.insert(p);
                        }
                    }
                    Err(error) => {
                        issues.push(Issue::SourceFailed(p, error.to_string()));
                        cursor.exhausted.insert(p);
                    }
                }
            }
        }
        let mut ranks = BTreeMap::new();
        let mut sources = Vec::new();
        for buffer in cursor.platform_buffers.values() {
            for (index, source) in buffer.iter().enumerate() {
                ranks.insert(source.reference.clone(), index);
                sources.push(source.clone());
            }
        }
        let mut remote = self.group(sources)?;
        remote.sort_by(|a, b| match r.sort {
            Sort::Relevance => {
                let rank = |m: &Mod| {
                    m.sources
                        .iter()
                        .filter_map(|s| ranks.get(&s.reference))
                        .min()
                        .copied()
                        .unwrap_or(usize::MAX)
                };
                rank(a)
                    .cmp(&rank(b))
                    .then(b.downloads.cmp(&a.downloads))
                    .then(a.identity.cmp(&b.identity))
            }
            Sort::Downloads => b
                .downloads
                .cmp(&a.downloads)
                .then(a.identity.cmp(&b.identity)),
            Sort::Updated => b.updated.cmp(&a.updated).then(a.identity.cmp(&b.identity)),
        });
        let local_keys: BTreeSet<_> = cursor.buffer.iter().map(|m| m.identity.clone()).collect();
        let mut retained_refs = BTreeSet::new();
        for item in remote {
            if cursor.seen.contains(&item.identity) || local_keys.contains(&item.identity) {
                continue;
            }
            if items.len() < r.page_size {
                cursor.seen.insert(item.identity.clone());
                items.push(item);
            } else {
                retained_refs.extend(item.sources.into_iter().map(|s| s.reference));
            }
        }
        for buffer in cursor.platform_buffers.values_mut() {
            buffer.retain(|s| retained_refs.contains(&s.reference));
        }
        ensure!(
            r.cursor.is_some()
                || !items.is_empty()
                || successful
                || !issues
                    .iter()
                    .any(|i| matches!(i, Issue::SourceFailed(_, _))),
            CatalogError::AllSourcesFailed
        );
        let more = !cursor.buffer.is_empty()
            || !cursor.local_exhausted
            || cursor.platform_buffers.values().any(|b| !b.is_empty())
            || cursor.exhausted.len() < 2;
        Ok(Outcome {
            value: SearchPage {
                items,
                cursor: more.then_some(cursor),
                estimated_total: (!has_query && estimated > 0).then_some(estimated),
            },
            issues,
        })
    }
    async fn compatible(
        &self,
        p: &ProjectRef,
        t: &Target,
        channels: &[Channel],
    ) -> Result<Option<File>> {
        let mut offset = 0;
        loop {
            let (files, next) = self.file_list(p, t, offset, 50).await?;
            if let Some(f) = files.into_iter().find(|f| {
                channels.contains(&f.channel)
                    && f.minecraft.contains(&t.minecraft)
                    && f.loaders.iter().any(|l| l.eq_ignore_ascii_case(&t.loader))
            }) {
                return Ok(Some(f));
            }
            let Some(next) = next else { return Ok(None) };
            ensure!(next > offset, "File list offset did not advance");
            offset = next;
        }
    }
    pub async fn find_updates(
        &self,
        installed: &[(File, Target)],
    ) -> Result<Outcome<UpdateReport>> {
        let mut report = UpdateReport::default();
        let mut issues = self.issues();
        let mut successful = 0;
        for (file, target) in installed {
            let channels = if file.channel == Channel::Alpha {
                vec![Channel::Release, Channel::Beta, Channel::Alpha]
            } else {
                vec![Channel::Release, Channel::Beta]
            };
            let result = self.compatible(&file.project, target, &channels).await;
            if result.is_ok() {
                successful += 1;
            }
            match result {
                Ok(Some(latest))
                    if latest.reference != file.reference && latest.published > file.published =>
                {
                    report.updates.push((file.clone(), latest))
                }
                Ok(Some(_)) => report.unchanged.push(file.reference.clone()),
                Ok(None) => report.unresolved.push(file.reference.clone()),
                Err(e) => {
                    issues.push(Issue::SourceFailed(file.reference.platform, e.to_string()));
                    report.unresolved.push(file.reference.clone());
                }
            }
        }
        ensure!(
            installed.is_empty() || successful > 0,
            CatalogError::AllSourcesFailed
        );
        Ok(Outcome {
            value: report,
            issues,
        })
    }
    pub async fn resolve_dependencies(
        &self,
        roots: &[File],
        target: &Target,
        requirements: &[DependencyRequirement],
    ) -> Result<Outcome<DependencyGraph>> {
        target.validate()?;
        let mut graph = DependencyGraph {
            roots: roots.iter().map(|f| f.reference.clone()).collect(),
            ..Default::default()
        };
        let mut queue: VecDeque<_> = roots
            .iter()
            .map(|f| (f.clone(), vec![f.reference.clone()]))
            .collect();
        while let Some((file, path)) = queue.pop_front() {
            let depth = path.len() - 1;
            if graph.nodes.contains_key(&file.reference) {
                continue;
            }
            graph
                .nodes
                .insert(file.reference.clone(), (file.clone(), depth));
            for dep in &file.dependencies {
                if !requirements.contains(&dep.requirement) {
                    continue;
                }
                graph.edges.push((file.reference.clone(), dep.clone()));
                let resolved = match &dep.target {
                    DependencyTarget::File(r) => self
                        .get_files(std::slice::from_ref(r))
                        .await
                        .map(|o| o.value.get(r).cloned()),
                    DependencyTarget::Project(p) => {
                        self.compatible(
                            p,
                            target,
                            if file.channel == Channel::Alpha {
                                &[Channel::Release, Channel::Beta, Channel::Alpha]
                            } else {
                                &[Channel::Release, Channel::Beta]
                            },
                        )
                        .await
                    }
                };
                match resolved {
                    Ok(Some(next)) => {
                        if let Some(start) = path.iter().position(|r| r == &next.reference) {
                            let mut cycle = path[start..].to_vec();
                            cycle.push(next.reference.clone());
                            graph.cycles.push(cycle);
                        } else {
                            let mut path = path.clone();
                            path.push(next.reference.clone());
                            queue.push_back((next, path));
                        }
                    }
                    Ok(None) => graph.unresolved.push((
                        file.reference.clone(),
                        dep.target.clone(),
                        "找不到兼容版本".into(),
                    )),
                    Err(e) => {
                        tracing::warn!(error=%e,"依赖解析失败");
                        graph.unresolved.push((
                            file.reference.clone(),
                            dep.target.clone(),
                            e.to_string(),
                        ));
                    }
                }
            }
        }
        Ok(Outcome {
            value: graph,
            issues: self.issues(),
        })
    }
}

#[cfg(test)]
mod domain_tests {
    use super::*;
    use serde_json::{Value, json};
    struct Fake {
        calls: std::sync::Mutex<Vec<String>>,
    }
    impl transport::Transport for Fake {
        fn request<'a>(
            &'a self,
            p: Platform,
            _: reqwest::Method,
            path: &'a str,
            _: Vec<(String, String)>,
            _: Option<Value>,
        ) -> transport::TransportFuture<'a> {
            Box::pin(async move {
                self.calls.lock().unwrap().push(format!("{p:?}:{path}"));
                let version = |id: &str, channel: &str, date: &str, deps: Value| json!({"id":id,"project_id":"project","name":id,"version_number":id,"version_type":channel,"date_published":date,"game_versions":["1.21.1"],"loaders":["neoforge"],"files":[{"filename":format!("{id}.jar"),"size":3,"url":"https://cdn.modrinth.com/a.jar","hashes":{"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d"}}],"dependencies":deps});
                Ok(match path {
                    "project/project/version" => json!([
                        version("release", "release", "2026-02-01T00:00:00Z", json!([])),
                        version("beta", "beta", "2026-03-01T00:00:00Z", json!([])),
                        version("alpha", "alpha", "2026-04-01T00:00:00Z", json!([]))
                    ]),
                    "versions" => json!([
                        version(
                            "dep",
                            "release",
                            "2026-01-01T00:00:00Z",
                            json!([{"version_id":"root","dependency_type":"required"}])
                        ),
                        version(
                            "root",
                            "release",
                            "2026-01-01T00:00:00Z",
                            json!([{"version_id":"dep","dependency_type":"required"}])
                        )
                    ]),
                    "search" => {
                        json!({"hits":[{"project_id":"a","slug":"a","title":"A","project_type":"mod","downloads":5},{"project_id":"b","slug":"b","title":"B","project_type":"mod","downloads":4}],"total_hits":2})
                    }
                    "mods/search" => json!({"data":[],"pagination":{"totalCount":0}}),
                    "project/project" => {
                        json!({"id":"project","slug":"project","title":"Project","project_type":"mod","body":"details","categories":["utility"]})
                    }
                    "version/root" => {
                        let mut v = version("root", "release", "2026-01-01T00:00:00Z", json!([]));
                        v["changelog"] = json!("changes");
                        v
                    }
                    _ => json!([]),
                })
            })
        }
    }
    fn setup() -> (Catalog, Arc<Fake>) {
        let tr = Arc::new(Fake {
            calls: Default::default(),
        });
        let http = reqwest::Client::builder().no_proxy().build().unwrap();
        (
            Catalog::with_transport(
                tr.clone(),
                transport::HttpTransport::new(http, false),
                std::env::temp_dir().join(format!("missing-{}", uuid::Uuid::now_v7())),
            ),
            tr,
        )
    }
    fn target() -> Target {
        Target {
            minecraft: "1.21.1".into(),
            loader: "neoforge".into(),
        }
    }
    fn project() -> Project {
        Project {
            reference: ProjectRef {
                platform: Platform::Modrinth,
                id: "project".into(),
            },
            slug: "project".into(),
            name: "Project".into(),
            summary: String::new(),
            icon: None,
            downloads: 1,
            updated: String::new(),
            environment: Default::default(),
            kind: ContentType::Mod,
        }
    }
    #[tokio::test]
    async fn complete_file_list_is_cached_across_pages_and_channels() {
        let (c, tr) = setup();
        let item = c.group(vec![project()]).unwrap().remove(0);
        let mut r = FileRequest {
            item,
            target: target(),
            channels: vec![Channel::Release, Channel::Beta],
            page_size: 1,
            cursor: None,
        };
        let first = c.list_files(&r).await.unwrap().value;
        assert_eq!(first.items[0].reference.id, "beta");
        r.cursor = first.cursor;
        assert_eq!(
            c.list_files(&r).await.unwrap().value.items[0].reference.id,
            "release"
        );
        r.channels = vec![Channel::Alpha];
        r.cursor = None;
        assert_eq!(
            c.list_files(&r).await.unwrap().value.items[0].reference.id,
            "alpha"
        );
        assert_eq!(
            tr.calls
                .lock()
                .unwrap()
                .iter()
                .filter(|p| p.ends_with("project/project/version"))
                .count(),
            1
        );
    }
    #[tokio::test]
    async fn dependency_cycles_and_requirements() {
        let (c, _) = setup();
        let root = c
            .get_files(&[FileRef {
                platform: Platform::Modrinth,
                id: "root".into(),
            }])
            .await
            .unwrap()
            .value
            .into_values()
            .next()
            .unwrap();
        let graph = c
            .resolve_dependencies(
                std::slice::from_ref(&root),
                &target(),
                &[DependencyRequirement::Required],
            )
            .await
            .unwrap()
            .value;
        assert_eq!(graph.nodes.len(), 2);
        assert_eq!(graph.cycles.len(), 1);
        let empty = c
            .resolve_dependencies(&[root], &target(), &[])
            .await
            .unwrap()
            .value;
        assert_eq!(empty.nodes.len(), 1);
        assert!(empty.edges.is_empty());
    }
    #[tokio::test]
    async fn search_cursor_has_no_duplicates_and_rejects_changed_query() {
        let (c, _) = setup();
        let mut r = SearchRequest {
            query: "query".into(),
            target: target(),
            sort: Sort::Relevance,
            page_size: 1,
            cursor: None,
        };
        let a = c.search(&r).await.unwrap().value;
        assert_eq!(a.items.len(), 1);
        r.cursor = a.cursor;
        let b = c.search(&r).await.unwrap().value;
        assert_eq!(b.items.len(), 1);
        assert_ne!(a.items[0].identity, b.items[0].identity);
        assert!(b.cursor.is_none());
        r.query = "changed".into();
        assert!(c.search(&r).await.is_err());
    }
    #[tokio::test]
    async fn details_changelog_and_release_to_beta() {
        let (c, tr) = setup();
        let item = c.group(vec![project()]).unwrap().remove(0);
        assert_eq!(
            c.get_details(&item).await.unwrap().value.description,
            "details"
        );
        assert_eq!(
            c.get_changelog(&FileRef {
                platform: Platform::Modrinth,
                id: "root".into()
            })
            .await
            .unwrap(),
            "changes"
        );
        c.get_changelog(&FileRef {
            platform: Platform::Modrinth,
            id: "root".into(),
        })
        .await
        .unwrap();
        assert_eq!(
            tr.calls
                .lock()
                .unwrap()
                .iter()
                .filter(|s| s.ends_with("version/root"))
                .count(),
            1
        );
        let mut installed = c
            .get_files(&[FileRef {
                platform: Platform::Modrinth,
                id: "root".into(),
            }])
            .await
            .unwrap()
            .value
            .into_values()
            .next()
            .unwrap();
        installed.published = "2026-01-01T00:00:00Z".into();
        let updates = c
            .find_updates(&[(installed, target())])
            .await
            .unwrap()
            .value;
        assert_eq!(updates.updates[0].1.reference.id, "beta");
    }
}

#[cfg(test)]
mod disk_tests {
    use super::*;
    use serde_json::{Value, json};
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Answers Modrinth `projects` with one project per requested id and counts the calls.
    #[derive(Default)]
    struct Projects(AtomicUsize);
    impl transport::Transport for Projects {
        fn request<'a>(&'a self, _: Platform, _: reqwest::Method, path: &'a str, query: Vec<(String, String)>, _: Option<Value>) -> transport::TransportFuture<'a> {
            Box::pin(async move {
                assert_eq!(path, "projects");
                self.0.fetch_add(1, Ordering::SeqCst);
                let ids: Vec<String> = serde_json::from_str(&query[0].1).unwrap();
                Ok(Value::Array(
                    ids.iter()
                        .map(|id| json!({"id": id, "slug": format!("slug-{id}"), "title": format!("Mod {id}"), "project_type": "mod", "description": "intro", "icon_url": "https://cdn.modrinth.com/i.png"}))
                        .collect(),
                ))
            })
        }
    }
    fn catalog(tr: Arc<Projects>, db: &Path) -> Catalog {
        let http = reqwest::Client::builder().no_proxy().build().unwrap();
        Catalog::with_transport(tr, transport::HttpTransport::new(http, false), std::env::temp_dir().join(format!("missing-{}", uuid::Uuid::now_v7())))
            .with_disk_cache(db)
    }
    fn refs() -> Vec<ProjectRef> {
        ["AANobbMI", "P7dR8mSH"].map(|id| ProjectRef { platform: Platform::Modrinth, id: id.into() }).to_vec()
    }

    #[tokio::test]
    async fn projects_survive_a_restart_for_15_days() {
        let dir = std::env::temp_dir().join(format!("rdi-catalog-disk-{}", uuid::Uuid::now_v7().simple()));
        let db = disk::path(&dir);
        let tr = Arc::new(Projects::default());
        let first = catalog(tr.clone(), &db).get_mods(&refs()).await.unwrap();
        assert_eq!(first.value.len(), 2);
        assert_eq!(tr.0.load(Ordering::SeqCst), 1);

        // A new catalog (the launcher restarted) reads the same file without a request.
        let again = catalog(tr.clone(), &db).get_mods(&refs()).await.unwrap();
        assert_eq!(tr.0.load(Ordering::SeqCst), 1);
        assert_eq!(again.value[&refs()[0]].name, "Mod AANobbMI");

        // 16 days later the rows are expired and the network is asked again.
        rusqlite::Connection::open(&db).unwrap().execute("UPDATE projects SET stored = stored - 16*24*3600", []).unwrap();
        catalog(tr.clone(), &db).get_mods(&refs()).await.unwrap();
        assert_eq!(tr.0.load(Ordering::SeqCst), 2);

        // 清理缓存 empties it.
        assert_eq!(disk::clear(&db).unwrap(), 2);
        catalog(tr.clone(), &db).get_mods(&refs()).await.unwrap();
        assert_eq!(tr.0.load(Ordering::SeqCst), 3);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn unreadable_rows_count_as_missing() {
        let dir = std::env::temp_dir().join(format!("rdi-catalog-disk-{}", uuid::Uuid::now_v7().simple()));
        let db = disk::path(&dir);
        let cache = disk::ProjectDiskCache::open(&db).unwrap();
        rusqlite::Connection::open(&db)
            .unwrap()
            .execute("INSERT INTO projects VALUES('MODRINTH', 'AANobbMI', ?1, '{broken')", [i64::MAX / 2])
            .unwrap();
        assert!(cache.get_many(&refs(), std::time::SystemTime::now()).is_empty());
        drop(cache);
        std::fs::remove_dir_all(&dir).ok();
    }
}
