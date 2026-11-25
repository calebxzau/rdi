use super::{models::*, transport::Transport};
use crate::digests::{Algorithm, Digest};
use anyhow::{Context, Result, ensure};
use reqwest::Method;
use serde_json::{Value, json};
use std::{collections::BTreeMap, sync::Arc};
pub fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or_default()
}
fn string(v: &Value) -> String {
    v.as_str()
        .map(str::to_owned)
        .or_else(|| v.as_i64().map(|n| n.to_string()))
        .unwrap_or_default()
}
fn strings(v: &Value) -> Vec<String> {
    v.as_array()
        .map(|v| {
            v.iter()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}
fn array(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or_default()
}
fn opt(v: &Value, k: &str) -> Option<String> {
    v[k].as_str()
        .filter(|s| !s.trim().is_empty())
        .map(|s| s.trim().into())
}
fn loader(v: &str) -> Option<String> {
    match v.to_lowercase().as_str() {
        "forge" => Some("forge".into()),
        "neoforge" => Some("neoforge".into()),
        "fabric" => Some("Fabric".into()),
        _ => None,
    }
}
fn requirement(s: &str) -> Requirement {
    match s {
        "required" => Requirement::Required,
        "optional" => Requirement::Optional,
        "unsupported" => Requirement::Unsupported,
        _ => Requirement::Unknown,
    }
}
fn valid_url(value: &str, p: Platform) -> Result<String> {
    let url = reqwest::Url::parse(value).map_err(|_| CatalogError::InvalidResponse(p))?;
    ensure!(
        url.scheme() == "https" && url.host_str().is_some(),
        CatalogError::InvalidResponse(p)
    );
    Ok(value.into())
}
/// Modrinth takes id lists in the URL (`?ids=["a","b"]`), so a long list is split only to keep
/// the URL short enough for the servers; there is no fixed number of ids per request. CurseForge
/// and Modrinth `version_files` send ids in the body and are never split.
const MAX_URL_IDS_BYTES: usize = 7000;

/// Consecutive slices of `ids` whose URL-encoded JSON list (`%5B%22id%22%2C…%5D`) stays within
/// `MAX_URL_IDS_BYTES`; a single id longer than that still gets its own batch.
pub(crate) fn url_batches(ids: &[String]) -> Vec<&[String]> {
    let mut batches = Vec::new();
    let (mut start, mut size) = (0, 6);
    for (i, id) in ids.iter().enumerate() {
        // `"id"` plus a separating `,`, each special character percent-encoded to 3 bytes.
        let cost = urlencoding_len(id) + 9;
        if i > start && size + cost > MAX_URL_IDS_BYTES {
            batches.push(&ids[start..i]);
            (start, size) = (i, 6);
        }
        size += cost;
    }
    if start < ids.len() {
        batches.push(&ids[start..]);
    }
    batches
}

fn urlencoding_len(id: &str) -> usize {
    id.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-._*".contains(&b) { 1 } else { 3 }).sum()
}

#[derive(Clone)]
pub struct Adapter {
    pub platform: Platform,
    pub transport: Arc<dyn Transport>,
}
impl Adapter {
    async fn get(&self, path: &str, query: Vec<(String, String)>) -> Result<Value> {
        self.transport
            .request(self.platform, Method::GET, path, query, None)
            .await
    }
    async fn post(&self, path: &str, body: Value) -> Result<Value> {
        self.transport
            .request(self.platform, Method::POST, path, vec![], Some(body))
            .await
    }
    pub fn project(&self, v: &Value) -> Result<Project> {
        let cf = self.platform == Platform::CurseForge;
        let id = if cf {
            string(&v["id"])
        } else {
            opt(v, "id")
                .or_else(|| opt(v, "project_id"))
                .unwrap_or_default()
        };
        ensure!(!id.is_empty(), CatalogError::InvalidResponse(self.platform));
        let kind = if cf {
            match v["classId"].as_u64() {
                Some(6) => ContentType::Mod,
                Some(12) => ContentType::ResourcePack,
                Some(6552) => ContentType::ShaderPack,
                _ => ContentType::Other,
            }
        } else {
            match text(v, "project_type") {
                "mod" => ContentType::Mod,
                "resourcepack" => ContentType::ResourcePack,
                "shader" => ContentType::ShaderPack,
                _ => ContentType::Other,
            }
        };
        Ok(Project {
            reference: ProjectRef {
                platform: self.platform,
                id,
            },
            slug: text(v, "slug").into(),
            name: text(v, if cf { "name" } else { "title" }).into(),
            summary: text(v, if cf { "summary" } else { "description" })
                .trim()
                .into(),
            icon: if cf {
                opt(&v["logo"], "url").or_else(|| opt(&v["logo"], "thumbnailUrl"))
            } else {
                opt(v, "icon_url")
            },
            downloads: v[if cf { "downloadCount" } else { "downloads" }]
                .as_u64()
                .unwrap_or(0),
            updated: opt(v, if cf { "dateModified" } else { "updated" })
                .or_else(|| opt(v, "date_modified"))
                .unwrap_or_else(|| "1970-01-01T00:00:00Z".into()),
            environment: if cf {
                Environment::default()
            } else {
                Environment {
                    client: requirement(text(v, "client_side")),
                    server: requirement(text(v, "server_side")),
                }
            },
            kind,
        })
    }
    pub fn file(&self, v: &Value, exact_sha1: Option<&str>) -> Result<File> {
        let cf = self.platform == Platform::CurseForge;
        let id = string(&v["id"]);
        let project = string(&v[if cf { "modId" } else { "project_id" }]);
        ensure!(
            !id.is_empty() && !project.is_empty(),
            CatalogError::InvalidResponse(self.platform)
        );
        let entry = if cf {
            v
        } else {
            let entries = array(&v["files"]);
            if let Some(sha1) = exact_sha1 {
                let matched: Vec<_> = entries
                    .iter()
                    .filter(|f| text(&f["hashes"], "sha1").trim().eq_ignore_ascii_case(sha1))
                    .collect();
                ensure!(
                    matched.len() == 1,
                    "Modrinth版本文件SHA-1匹配不唯一：{sha1}"
                );
                matched[0]
            } else {
                entries
                    .iter()
                    .find(|f| f["primary"] == true)
                    .or_else(|| entries.first())
                    .context("Modrinth returned an invalid response")?
            }
        };
        let filename = text(entry, if cf { "fileName" } else { "filename" })
            .trim()
            .to_owned();
        ensure!(
            !filename.is_empty(),
            CatalogError::InvalidResponse(self.platform)
        );
        let mut digests = vec![];
        if cf {
            for h in array(&v["hashes"]) {
                if h["algo"] == 1 {
                    digests.push(Digest::new(Algorithm::Sha1, text(h, "value"))?);
                    break;
                }
            }
            if let Some(n) = v["fileFingerprint"].as_u64() {
                digests.push(Digest::new(Algorithm::Murmur2, &n.to_string())?);
            }
        } else {
            digests.push(Digest::new(
                Algorithm::Sha1,
                text(&entry["hashes"], "sha1"),
            )?);
        }
        ensure!(
            !digests.is_empty(),
            CatalogError::InvalidResponse(self.platform)
        );
        let channel = if cf {
            match v["releaseType"].as_u64() {
                Some(1) => Channel::Release,
                Some(2) => Channel::Beta,
                Some(3) => Channel::Alpha,
                _ => return Err(CatalogError::InvalidResponse(self.platform).into()),
            }
        } else {
            match text(v, "version_type") {
                "release" => Channel::Release,
                "beta" => Channel::Beta,
                "alpha" => Channel::Alpha,
                _ => return Err(CatalogError::InvalidResponse(self.platform).into()),
            }
        };
        let versions = strings(&v[if cf { "gameVersions" } else { "game_versions" }]);
        let loaders = if cf {
            versions.iter().filter_map(|s| loader(s)).collect()
        } else {
            strings(&v["loaders"])
                .iter()
                .filter_map(|s| loader(s))
                .collect()
        };
        let mut dependencies = vec![];
        for dep in array(&v["dependencies"]) {
            let requirement = if cf {
                match dep["relationType"].as_u64() {
                    Some(1) => DependencyRequirement::Embedded,
                    Some(2) => DependencyRequirement::Optional,
                    Some(3) => DependencyRequirement::Required,
                    Some(4) => DependencyRequirement::Tool,
                    Some(5) => DependencyRequirement::Incompatible,
                    Some(6) => DependencyRequirement::Included,
                    _ => continue,
                }
            } else {
                match text(dep, "dependency_type") {
                    "required" => DependencyRequirement::Required,
                    "optional" => DependencyRequirement::Optional,
                    "incompatible" => DependencyRequirement::Incompatible,
                    "embedded" => DependencyRequirement::Embedded,
                    _ => continue,
                }
            };
            let target = if cf {
                let id = string(&dep["modId"]);
                if id.is_empty() {
                    continue;
                }
                DependencyTarget::Project(ProjectRef {
                    platform: self.platform,
                    id,
                })
            } else if let Some(id) = opt(dep, "version_id") {
                DependencyTarget::File(FileRef {
                    platform: self.platform,
                    id,
                })
            } else if let Some(id) = opt(dep, "project_id") {
                DependencyTarget::Project(ProjectRef {
                    platform: self.platform,
                    id,
                })
            } else {
                continue;
            };
            dependencies.push(Dependency {
                target,
                requirement,
            });
        }
        let raw_url = opt(entry, if cf { "downloadUrl" } else { "url" }).or_else(|| {
            if !cf || id.len() <= 4 {
                return None;
            }
            let (a, b) = id.split_at(4);
            let (Ok(a), Ok(b)) = (a.parse::<u64>(), b.parse::<u64>()) else {
                return None;
            };
            let encoded: String = filename
                .bytes()
                .map(|b| {
                    if b.is_ascii_alphanumeric() || b"-._*".contains(&b) {
                        (b as char).to_string()
                    } else {
                        format!("%{b:02X}")
                    }
                })
                .collect();
            Some(format!(
                "https://mediafilez.forgecdn.net/files/{a}/{b}/{encoded}"
            ))
        });
        let url = raw_url.map(|v| valid_url(&v, self.platform)).transpose()?;
        Ok(File {
            reference: FileRef {
                platform: self.platform,
                id,
            },
            project: ProjectRef {
                platform: self.platform,
                id: project,
            },
            name: opt(v, if cf { "displayName" } else { "name" })
                .unwrap_or_else(|| filename.clone()),
            version: opt(v, "version_number"),
            filename,
            channel,
            published: opt(v, if cf { "fileDate" } else { "date_published" })
                .unwrap_or_else(|| "1970-01-01T00:00:00Z".into()),
            size: entry[if cf { "fileLength" } else { "size" }]
                .as_u64()
                .or_else(|| entry["fileSizeOnDisk"].as_u64())
                .unwrap_or(0),
            minecraft: if cf {
                versions
                    .into_iter()
                    .filter(|v| v.starts_with(|c: char| c.is_ascii_digit()))
                    .collect()
            } else {
                versions
            },
            loaders,
            environment: if cf {
                let client = strings(&v["gameVersions"])
                    .iter()
                    .any(|s| s.trim().eq_ignore_ascii_case("Client"));
                let server = strings(&v["gameVersions"])
                    .iter()
                    .any(|s| s.trim().eq_ignore_ascii_case("Server"));
                if client || server {
                    Environment {
                        client: if client {
                            Requirement::Required
                        } else {
                            Requirement::Unsupported
                        },
                        server: if server {
                            Requirement::Required
                        } else {
                            Requirement::Unsupported
                        },
                    }
                } else {
                    Environment::default()
                }
            } else {
                Environment::default()
            },
            digests,
            dependencies,
            url,
        })
    }
    pub async fn projects(&self, ids: &[String]) -> Result<BTreeMap<String, Project>> {
        let mut result = BTreeMap::new();
        let batches = if self.platform == Platform::CurseForge { vec![ids] } else { url_batches(ids) };
        for ids in batches.into_iter().filter(|b| !b.is_empty()) {
            let response = if self.platform == Platform::CurseForge {
                self.post("mods",json!({"modIds":ids.iter().filter_map(|s|s.parse::<u64>().ok()).collect::<Vec<_>>()})).await?
            } else {
                self.get("projects", vec![("ids".into(), json!(ids).to_string())])
                    .await?
            };
            for v in array(if self.platform == Platform::CurseForge {
                &response["data"]
            } else {
                &response
            }) {
                if self.platform == Platform::CurseForge
                    && v["gameId"].as_u64().is_some_and(|n| n != 432)
                {
                    continue;
                }
                let p = self.project(v)?;
                result.insert(p.reference.id.clone(), p);
            }
        }
        Ok(result)
    }
    pub async fn files(&self, ids: &[String]) -> Result<BTreeMap<String, File>> {
        let mut result = BTreeMap::new();
        let batches = if self.platform == Platform::CurseForge { vec![ids] } else { url_batches(ids) };
        for ids in batches.into_iter().filter(|b| !b.is_empty()) {
            let response = if self.platform == Platform::CurseForge {
                self.post("mods/files",json!({"fileIds":ids.iter().filter_map(|s|s.parse::<u64>().ok()).collect::<Vec<_>>()})).await?
            } else {
                self.get("versions", vec![("ids".into(), json!(ids).to_string())])
                    .await?
            };
            for v in array(if self.platform == Platform::CurseForge {
                &response["data"]
            } else {
                &response
            }) {
                let f = self.file(v, None)?;
                result.insert(f.reference.id.clone(), f);
            }
        }
        Ok(result)
    }
    pub async fn match_files(&self, hashes: &[FileHashes]) -> Result<BTreeMap<String, File>> {
        let mut result = BTreeMap::new();
        if hashes.is_empty() {
            return Ok(result);
        }
        if self.platform == Platform::CurseForge {
            let fingerprints: Vec<_> = hashes.iter().filter_map(|h| h.murmur2).collect();
            if fingerprints.is_empty() {
                return Ok(result);
            }
            let response = self
                .post("fingerprints/432", json!({"fingerprints":fingerprints}))
                .await?;
            for m in array(&response["data"]["exactMatches"]) {
                let v = &m["file"];
                let Some(fingerprint) = v["fileFingerprint"].as_u64() else {
                    continue;
                };
                let file = self.file(v, None)?;
                for h in hashes {
                    if h.murmur2.map(u64::from) == Some(fingerprint) {
                        result.insert(h.key.clone(), file.clone());
                    }
                }
            }
        } else {
            // One request: the hashes are in the body.
            let response=self.post("version_files",json!({"hashes":hashes.iter().map(|h|h.sha1.to_lowercase()).collect::<Vec<_>>()})).await?;
            for (hash, v) in response
                .as_object()
                .context("MODRINTH returned an invalid response")?
            {
                let file = self.file(v, Some(hash))?;
                for h in hashes {
                    if h.sha1.eq_ignore_ascii_case(hash) {
                        result.insert(h.key.clone(), file.clone());
                    }
                }
            }
        }
        Ok(result)
    }
    pub async fn list_files(
        &self,
        project: &ProjectRef,
        target: &Target,
        offset: usize,
        limit: usize,
    ) -> Result<(Vec<File>, Option<usize>)> {
        let cf = self.platform == Platform::CurseForge;
        let path = if cf {
            format!("mods/{}/files", project.id)
        } else {
            format!("project/{}/version", project.id)
        };
        let query = if cf {
            vec![
                ("gameVersion".into(), target.minecraft.clone()),
                (
                    "modLoaderType".into(),
                    cf_loader(&target.loader).to_string(),
                ),
                ("index".into(), offset.to_string()),
                ("pageSize".into(), limit.to_string()),
            ]
        } else {
            vec![
                (
                    "game_versions".into(),
                    json!([target.minecraft]).to_string(),
                ),
                (
                    "loaders".into(),
                    json!([target.loader.to_lowercase()]).to_string(),
                ),
            ]
        };
        let response = self.get(&path, query).await?;
        let mut files = array(if cf { &response["data"] } else { &response })
            .iter()
            .map(|v| self.file(v, None))
            .collect::<Result<Vec<_>>>()?;
        files.sort_by(|a, b| b.published.cmp(&a.published));
        let next = if cf {
            let p = &response["pagination"];
            let next = p["index"].as_u64().unwrap_or(offset as u64)
                + p["resultCount"].as_u64().unwrap_or(0);
            (next > offset as u64 && next < p["totalCount"].as_u64().unwrap_or(0))
                .then_some(next as usize)
        } else {
            None
        };
        Ok((files, next))
    }
    pub async fn search(
        &self,
        query: &str,
        target: &Target,
        sort: Sort,
        offset: usize,
        limit: usize,
    ) -> Result<(Vec<Project>, Option<usize>, Option<u64>)> {
        let cf = self.platform == Platform::CurseForge;
        let parameters = if cf {
            vec![
                ("gameId".into(), "432".into()),
                ("classId".into(), "6".into()),
                ("searchFilter".into(), query.trim().into()),
                ("gameVersion".into(), target.minecraft.clone()),
                (
                    "modLoaderType".into(),
                    cf_loader(&target.loader).to_string(),
                ),
                ("sortOrder".into(), "desc".into()),
                (
                    "sortField".into(),
                    match sort {
                        Sort::Relevance => "1",
                        Sort::Downloads => "6",
                        Sort::Updated => "3",
                    }
                    .into(),
                ),
                ("index".into(), offset.to_string()),
                ("pageSize".into(), limit.to_string()),
            ]
        } else {
            vec![
                ("query".into(), query.trim().into()),
                (
                    "facets".into(),
                    json!([
                        ["project_type:mod"],
                        [format!("versions:{}", target.minecraft)],
                        [format!("categories:{}", target.loader.to_lowercase())]
                    ])
                    .to_string(),
                ),
                (
                    "index".into(),
                    match sort {
                        Sort::Relevance => "relevance",
                        Sort::Downloads => "downloads",
                        Sort::Updated => "updated",
                    }
                    .into(),
                ),
                ("offset".into(), offset.to_string()),
                ("limit".into(), limit.to_string()),
            ]
        };
        let response = self
            .get(if cf { "mods/search" } else { "search" }, parameters)
            .await?;
        let raw = array(&response[if cf { "data" } else { "hits" }]);
        let total = if cf {
            response["pagination"]["totalCount"].as_u64()
        } else {
            response["total_hits"].as_u64()
        };
        let mut projects = vec![];
        for v in raw {
            let p = self.project(v)?;
            if p.kind == ContentType::Mod {
                projects.push(p);
            }
        }
        let next = offset + raw.len();
        Ok((
            projects,
            (!raw.is_empty() && total.is_none_or(|n| next < (n as usize))).then_some(next),
            total,
        ))
    }
    pub async fn resolve_slugs(
        &self,
        slugs: &[String],
        target: &Target,
    ) -> Result<BTreeMap<String, Project>> {
        let mut result = BTreeMap::new();
        if self.platform == Platform::Modrinth {
            for chunk in slugs.chunks(50) {
                let response = self
                    .get("projects", vec![("ids".into(), json!(chunk).to_string())])
                    .await?;
                for v in array(&response) {
                    if strings(&v["game_versions"]).contains(&target.minecraft)
                        && strings(&v["loaders"]).contains(&target.loader.to_lowercase())
                    {
                        let p = self.project(v)?;
                        if p.kind == ContentType::Mod {
                            result.insert(p.slug.trim().to_lowercase(), p);
                        }
                    }
                }
            }
        } else {
            for slug in slugs {
                let response = self
                    .get(
                        "mods/search",
                        vec![
                            ("gameId".into(), "432".into()),
                            ("classId".into(), "6".into()),
                            ("slug".into(), slug.clone()),
                            ("gameVersion".into(), target.minecraft.clone()),
                            (
                                "modLoaderType".into(),
                                cf_loader(&target.loader).to_string(),
                            ),
                            ("pageSize".into(), "1".into()),
                        ],
                    )
                    .await?;
                for v in array(&response["data"]) {
                    let p = self.project(v)?;
                    if p.kind == ContentType::Mod && p.slug.trim().eq_ignore_ascii_case(slug.trim())
                    {
                        result.insert(slug.trim().to_lowercase(), p);
                        break;
                    }
                }
            }
        }
        Ok(result)
    }
    pub async fn details(&self, r: &ProjectRef) -> Result<Value> {
        let cf = self.platform == Platform::CurseForge;
        let path = if cf {
            format!("mods/{}", r.id)
        } else {
            format!("project/{}", r.id)
        };
        let response = self.get(&path, vec![]).await?;
        let mut value = if cf {
            response["data"].clone()
        } else {
            response
        };
        if cf {
            value["body"] = self
                .get(&format!("mods/{}/description", r.id), vec![])
                .await?["data"]
                .clone();
        }
        Ok(value)
    }
    pub async fn changelog(&self, file: &FileRef) -> Result<String> {
        if self.platform == Platform::Modrinth {
            return Ok(text(
                &self.get(&format!("version/{}", file.id), vec![]).await?,
                "changelog",
            )
            .into());
        }
        let f = self
            .files(std::slice::from_ref(&file.id))
            .await?
            .remove(&file.id)
            .context("CURSEFORGE returned an invalid response")?;
        Ok(text(
            &self
                .get(
                    &format!("mods/{}/files/{}/changelog", f.project.id, file.id),
                    vec![],
                )
                .await?,
            "data",
        )
        .into())
    }
}
fn cf_loader(loader: &str) -> u64 {
    match loader.to_lowercase().as_str() {
        "forge" => 1,
        "fabric" => 4,
        "neoforge" => 6,
        _ => 0,
    }
}

#[cfg(test)]
mod batch_tests {
    use super::*;
    use crate::catalog::transport::{Transport, TransportFuture};
    use std::sync::Mutex;

    /// Records (method, path, query `ids` length, body id count) and answers with no items.
    #[derive(Default)]
    struct Recorder(Mutex<Vec<(String, String, usize, usize)>>);
    impl Transport for Recorder {
        fn request<'a>(&'a self, _: Platform, method: Method, path: &'a str, query: Vec<(String, String)>, body: Option<Value>) -> TransportFuture<'a> {
            Box::pin(async move {
                let ids = query.iter().find(|(k, _)| k == "ids").map(|(_, v)| v.len()).unwrap_or(0);
                let listed = body.as_ref().and_then(|b| b.as_object()).and_then(|o| o.values().next()).and_then(Value::as_array).map_or(0, Vec::len);
                self.0.lock().unwrap().push((method.to_string(), path.to_owned(), ids, listed));
                Ok(match path {
                    "mods" | "mods/files" => json!({"data": []}),
                    "version_files" => json!({}),
                    _ => json!([]),
                })
            })
        }
    }
    fn adapter(platform: Platform) -> (Adapter, Arc<Recorder>) {
        let recorder = Arc::new(Recorder::default());
        (Adapter { platform, transport: recorder.clone() }, recorder)
    }
    fn ids(n: usize) -> Vec<String> {
        (0..n).map(|i| format!("{i:08}")).collect()
    }

    #[test]
    fn url_batches_have_no_fixed_count_and_stay_under_the_url_budget() {
        let few = ids(388);
        assert_eq!(url_batches(&few).len(), 1, "388 Modrinth ids fit one URL, as the live API accepted");
        let many = ids(2000);
        let batches = url_batches(&many);
        assert!(batches.len() > 1);
        assert_eq!(batches.iter().map(|b| b.len()).sum::<usize>(), 2000);
        for batch in &batches {
            let encoded = reqwest::Url::parse_with_params("https://x/projects", &[("ids", json!(batch).to_string())]).unwrap();
            assert!(encoded.query().unwrap().len() - "ids=".len() <= MAX_URL_IDS_BYTES, "{}", encoded.query().unwrap().len());
        }
        assert!(url_batches(&[]).is_empty());
        assert_eq!(url_batches(&["x".repeat(MAX_URL_IDS_BYTES)]).len(), 1);
    }

    #[tokio::test]
    async fn curseforge_and_hash_lookups_send_one_request() {
        let (cf, calls) = adapter(Platform::CurseForge);
        let many: Vec<String> = (0..500).map(|i| i.to_string()).collect();
        cf.projects(&many).await.unwrap();
        cf.files(&many).await.unwrap();
        let (mr, mr_calls) = adapter(Platform::Modrinth);
        let hashes: Vec<FileHashes> = (0..500)
            .map(|i| FileHashes { key: i.to_string(), sha1: format!("{i:040}"), murmur2: None })
            .collect();
        mr.match_files(&hashes).await.unwrap();
        mr.projects(&ids(388)).await.unwrap();
        let calls = calls.0.lock().unwrap();
        assert_eq!(calls.iter().map(|c| (c.1.as_str(), c.3)).collect::<Vec<_>>(), [("mods", 500), ("mods/files", 500)]);
        let mr_calls = mr_calls.0.lock().unwrap();
        assert_eq!(mr_calls.len(), 2);
        assert_eq!((mr_calls[0].1.as_str(), mr_calls[0].3), ("version_files", 500));
        assert_eq!(mr_calls[1].1, "projects");
    }
}
