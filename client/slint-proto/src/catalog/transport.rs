use super::models::{CatalogError, Platform};
use anyhow::Result;
use reqwest::{Client, Method, header::HeaderMap};
use serde_json::Value;
use std::{future::Future, pin::Pin, time::Duration};
pub type TransportFuture<'a> = Pin<Box<dyn Future<Output = Result<Value>> + Send + 'a>>;
pub trait Transport: Send + Sync {
    fn request<'a>(
        &'a self,
        platform: Platform,
        method: Method,
        path: &'a str,
        query: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a>;
}
pub struct HttpTransport {
    pub http: Client,
    pub official: [String; 2],
    pub mirror: String,
    pub prefer_mirror: bool,
    pub cf_key: String,
    pub preference_file: Option<std::path::PathBuf>,
}
impl HttpTransport {
    pub fn new(http: Client, prefer_mirror: bool) -> Self {
        let cf_key = [
            36u8, 50, 97, 36, 49, 48, 36, 55, 87, 87, 86, 49, 87, 69, 76, 99, 119, 88, 56, 88, 112,
            55, 100, 54, 56, 77, 72, 115, 46, 53, 103, 114, 84, 121, 90, 86, 97, 54, 83, 121, 110,
            121, 101, 83, 121, 77, 104, 49, 114, 115, 69, 56, 57, 110, 73, 97, 48, 57, 122, 79,
        ]
        .iter()
        .map(|b| *b as char)
        .collect();
        Self {
            http,
            official: [
                "https://api.curseforge.com/v1".into(),
                "https://api.modrinth.com/v2".into(),
            ],
            mirror: "https://mod.mcimirror.top".into(),
            prefer_mirror,
            cf_key,
            preference_file: None,
        }
    }
    async fn call(
        &self,
        p: Platform,
        base: &str,
        method: Method,
        path: &str,
        query: &[(String, String)],
        body: &Option<Value>,
    ) -> Result<(u16, Option<u64>, Value)> {
        let mut r = self
            .http
            .request(
                method,
                format!(
                    "{}/{}",
                    base.trim_end_matches('/'),
                    path.trim_start_matches('/')
                ),
            )
            .query(query)
            .header("Accept", "application/json");
        if p == Platform::CurseForge {
            r = r.header("x-api-key", &self.cf_key);
        } else {
            r = r.header("User-Agent", "rdi5-mod-catalog");
        }
        if let Some(body) = body {
            r = r.json(body);
        }
        let response = r.send().await.map_err(|_| CatalogError::Network(p))?;
        let status = response.status().as_u16();
        let retry = response
            .headers()
            .get("retry-after")
            .and_then(|h| h.to_str().ok())
            .and_then(|v| v.parse::<u64>().ok())
            .map(|v| v * 1000);
        let json = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|s| {
                s.split(';')
                    .next()
                    .is_some_and(|v| v.trim().eq_ignore_ascii_case("application/json"))
            });
        if !(200..300).contains(&status) {
            return Ok((status, retry, Value::Null));
        }
        if !json {
            return Err(CatalogError::InvalidResponse(p).into());
        }
        Ok((
            status,
            retry,
            response
                .json()
                .await
                .map_err(|_| CatalogError::InvalidResponse(p))?,
        ))
    }
    pub fn download_headers(&self, p: Platform, url: &str) -> Result<HeaderMap> {
        let mut headers = HeaderMap::new();
        if p == Platform::CurseForge {
            let url = reqwest::Url::parse(url)?;
            let host = url.host_str().unwrap_or_default();
            let mirror = reqwest::Url::parse(&self.mirror)?;
            if host.ends_with(".forgecdn.net") || Some(host) == mirror.host_str() {
                let mut key: reqwest::header::HeaderValue = self.cf_key.parse()?;
                key.set_sensitive(true);
                headers.insert("x-api-key", key);
            }
        }
        Ok(headers)
    }
}
impl Transport for HttpTransport {
    fn request<'a>(
        &'a self,
        p: Platform,
        method: Method,
        path: &'a str,
        query: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a> {
        Box::pin(async move {
            if self
                .preference_file
                .as_ref()
                .map(|p| prefer_mirror(p))
                .unwrap_or(self.prefer_mirror)
            {
                let base = format!(
                    "{}/{}/{}",
                    self.mirror,
                    if p == Platform::CurseForge {
                        "curseforge"
                    } else {
                        "modrinth"
                    },
                    if p == Platform::CurseForge {
                        "v1"
                    } else {
                        "v2"
                    }
                );
                match self
                    .call(p, &base, method.clone(), path, &query, &body)
                    .await
                {
                    Ok((200..=299, _, v)) if valid_shape(p, path, &v) => return Ok(v),
                    Ok(_) => tracing::warn!(platform=?p,"Mod镜像响应无效，使用源站"),
                    Err(error) => tracing::warn!(%error,"Mod镜像不可用，使用源站"),
                }
            }
            let base = &self.official[if p == Platform::CurseForge { 0 } else { 1 }];
            for attempt in 0..2 {
                let response = self
                    .call(p, base, method.clone(), path, &query, &body)
                    .await;
                match response {
                    Ok((200..=299, _, v)) if valid_shape(p, path, &v) => return Ok(v),
                    Ok((401 | 403, _, _)) => return Err(CatalogError::Unauthorized(p).into()),
                    Ok((429, retry, _)) => return Err(CatalogError::RateLimited(p, retry).into()),
                    Ok((502..=504, _, _)) if attempt == 0 => {}
                    Err(ref e)
                        if attempt == 0
                            && e.downcast_ref::<CatalogError>()
                                .is_some_and(|e| matches!(e, CatalogError::Network(_))) => {}
                    Err(e) => return Err(e),
                    _ => return Err(CatalogError::InvalidResponse(p).into()),
                }
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
            Err(CatalogError::Network(p).into())
        })
    }
}
pub fn prefer_mirror(path: &std::path::Path) -> bool {
    match std::fs::read_to_string(path) {
        Ok(text) => {
            let text = text
                .lines()
                .filter(|line| {
                    !line
                        .split_once('=')
                        .is_some_and(|(_, v)| v.trim() == "null")
                })
                .collect::<Vec<_>>()
                .join("\n");
            match toml::from_str::<toml::Value>(&text) {
                Ok(v) => v
                    .get("preferModMirror")
                    .and_then(toml::Value::as_bool)
                    .unwrap_or(true),
                Err(error) => {
                    tracing::warn!(%error,"读取Mod镜像设置失败");
                    true
                }
            }
        }
        Err(error) => {
            if error.kind() != std::io::ErrorKind::NotFound {
                tracing::warn!(%error,"读取Mod镜像设置失败");
            }
            true
        }
    }
}

fn valid_shape(platform: Platform, path: &str, value: &Value) -> bool {
    fn project(v: &Value, cf: bool) -> bool {
        v.is_object()
            && !v["id"].is_null()
            && v["slug"].is_string()
            && v[if cf { "name" } else { "title" }].is_string()
    }
    fn file(v: &Value, cf: bool) -> bool {
        v.is_object()
            && !v["id"].is_null()
            && !v[if cf { "modId" } else { "project_id" }].is_null()
            && if cf {
                v["fileName"].is_string() && v["hashes"].is_array()
            } else {
                v["files"].is_array() && v["version_type"].is_string()
            }
    }
    if platform == Platform::CurseForge {
        let data = &value["data"];
        if path == "mods" || path == "mods/search" {
            return data
                .as_array()
                .is_some_and(|a| a.iter().all(|v| project(v, true)));
        }
        if path == "mods/files" || path.ends_with("/files") {
            return data
                .as_array()
                .is_some_and(|a| a.iter().all(|v| file(v, true)));
        }
        if path.starts_with("fingerprints/") {
            return data["exactMatches"].is_array();
        }
        if path.ends_with("/description") || path.ends_with("/changelog") {
            return data.is_string();
        }
        return project(data, true);
    }
    if path == "projects" {
        value
            .as_array()
            .is_some_and(|a| a.iter().all(|v| project(v, false)))
    } else if path == "versions" || path.ends_with("/version") {
        value
            .as_array()
            .is_some_and(|a| a.iter().all(|v| file(v, false)))
    } else if path == "search" {
        value["hits"].is_array() && value["total_hits"].is_number()
    } else if path == "version_files" {
        value
            .as_object()
            .is_some_and(|a| a.values().all(|v| file(v, false)))
    } else if path.starts_with("version/") {
        file(value, false)
    } else {
        project(value, false)
    }
}
