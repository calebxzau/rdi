use super::models::{CatalogError, Platform};
use anyhow::Result;
use reqwest::{Client, Method, header::HeaderMap};
use serde_json::Value;
use std::{
    future::Future,
    pin::Pin,
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};
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
    /// The official API alone, Kotlin `ignoreMirror`: the upload asks it again for the ids a
    /// mirror answer left out. Transports without a mirror answer as `request`.
    fn request_official<'a>(
        &'a self,
        platform: Platform,
        method: Method,
        path: &'a str,
        query: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a> {
        self.request(platform, method, path, query, body)
    }
}
pub struct HttpTransport {
    pub http: Client,
    pub official: [String; 2],
    pub mirror: String,
    pub prefer_mirror: bool,
    pub cf_key: String,
    pub preference_file: Option<std::path::PathBuf>,
    /// How long the mirror has alone before the official API is asked too.
    pub hedge_delay: Duration,
    /// The mirror's own limit, longer than the shared client's 60s: it answers large batches
    /// slowly (about 0.27s per id), and players who cannot reach the official APIs depend on it.
    pub mirror_timeout: Duration,
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
            hedge_delay: Duration::from_secs(3),
            mirror_timeout: Duration::from_secs(180),
        }
    }
    #[allow(clippy::too_many_arguments)]
    async fn call(
        &self,
        p: Platform,
        base: &str,
        method: Method,
        path: &str,
        query: &[(String, String)],
        body: &Option<Value>,
        timeout: Option<Duration>,
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
        if let Some(timeout) = timeout {
            r = r.timeout(timeout);
        }
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
impl HttpTransport {
    fn uses_mirror(&self) -> bool {
        self.preference_file
            .as_ref()
            .map(|p| prefer_mirror(p))
            .unwrap_or(self.prefer_mirror)
    }
    /// A valid mirror answer, or `None` after logging why it is not usable.
    async fn ask_mirror(
        &self,
        p: Platform,
        method: &Method,
        path: &str,
        query: &[(String, String)],
        body: &Option<Value>,
    ) -> Option<Value> {
        let base = format!(
            "{}/{}",
            self.mirror,
            if p == Platform::CurseForge {
                "curseforge/v1"
            } else {
                "modrinth/v2"
            }
        );
        match self
            .call(p, &base, method.clone(), path, query, body, Some(self.mirror_timeout))
            .await
        {
            Ok((200..=299, _, v)) if valid_shape(p, path, &v) => Some(v),
            Ok(_) => {
                tracing::warn!(platform=?p,"Mod镜像响应无效，使用源站");
                None
            }
            Err(error) => {
                tracing::warn!(%error,"Mod镜像不可用，使用源站");
                None
            }
        }
    }
    async fn ask_official(
        &self,
        p: Platform,
        method: &Method,
        path: &str,
        query: &[(String, String)],
        body: &Option<Value>,
    ) -> Result<Value> {
        let base = &self.official[if p == Platform::CurseForge { 0 } else { 1 }];
        for attempt in 0..2 {
            let response = self
                .call(p, base, method.clone(), path, query, body, None)
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
    }
}
impl Transport for HttpTransport {
    /// With the mirror preferred, the mirror goes first and the official API joins after
    /// `hedge_delay`; the first valid answer wins. A failed mirror starts the official request at
    /// once, and a failed official request waits for the mirror.
    fn request<'a>(
        &'a self,
        p: Platform,
        method: Method,
        path: &'a str,
        query: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a> {
        Box::pin(async move {
            if !self.uses_mirror() {
                return self.ask_official(p, &method, path, &query, &body).await;
            }
            let official_started = AtomicBool::new(false);
            let mirror = self.ask_mirror(p, &method, path, &query, &body);
            let official = async {
                tokio::time::sleep(self.hedge_delay).await;
                official_started.store(true, Ordering::Relaxed);
                self.ask_official(p, &method, path, &query, &body).await
            };
            tokio::pin!(mirror, official);
            tokio::select! {
                answer = &mut mirror => match answer {
                    Some(v) => Ok(v),
                    // Still in its hedge delay: start now instead of waiting it out.
                    None if !official_started.load(Ordering::Relaxed) => {
                        self.ask_official(p, &method, path, &query, &body).await
                    }
                    None => official.await,
                },
                answer = &mut official => match answer {
                    Ok(v) => {
                        tracing::info!(platform=?p, "Mod镜像较慢，使用源站结果");
                        Ok(v)
                    }
                    Err(error) => {
                        tracing::warn!(%error, "Mod源站不可用，等待镜像");
                        mirror.await.ok_or(error)
                    }
                },
            }
        })
    }
    fn request_official<'a>(
        &'a self,
        p: Platform,
        method: Method,
        path: &'a str,
        query: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a> {
        self.official_only(p, method, path, query, body)
    }
}
impl HttpTransport {
    fn official_only<'a>(
        &'a self,
        p: Platform,
        method: Method,
        path: &'a str,
        query: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a> {
        Box::pin(async move { self.ask_official(p, &method, path, &query, &body).await })
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };
    use std::time::Instant;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// Answers every request with `status` and a one-project list whose id is `id`, after `delay`.
    async fn server(id: &'static str, status: u16, delay: Duration) -> (String, Arc<AtomicUsize>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let hits = Arc::new(AtomicUsize::new(0));
        let counter = hits.clone();
        tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else { return };
                counter.fetch_add(1, Ordering::SeqCst);
                tokio::spawn(async move {
                    let mut bytes = [0u8; 4096];
                    let _ = stream.read(&mut bytes).await;
                    tokio::time::sleep(delay).await;
                    let body = format!(r#"[{{"id":"{id}","slug":"{id}","title":"{id}"}}]"#);
                    let head = format!(
                        "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = stream.write_all(head.as_bytes()).await;
                    let _ = stream.write_all(body.as_bytes()).await;
                });
            }
        });
        (format!("http://{address}"), hits)
    }

    fn transport(mirror: String, official: String, hedge: Duration) -> HttpTransport {
        let http = Client::builder().no_proxy().build().unwrap();
        let mut t = HttpTransport::new(http, true);
        t.mirror = mirror;
        t.official[1] = official;
        t.hedge_delay = hedge;
        t
    }

    async fn ask(t: &HttpTransport) -> Result<String> {
        let v = t.request(Platform::Modrinth, Method::GET, "projects", vec![], None).await?;
        Ok(v[0]["id"].as_str().unwrap().to_owned())
    }

    #[tokio::test]
    async fn fast_mirror_wins_and_official_is_never_asked() {
        let (mirror, _) = server("mirror", 200, Duration::ZERO).await;
        let (official, official_hits) = server("official", 200, Duration::ZERO).await;
        let t = transport(mirror, official, Duration::from_millis(500));
        assert_eq!(ask(&t).await.unwrap(), "mirror");
        tokio::time::sleep(Duration::from_millis(700)).await;
        assert_eq!(official_hits.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn slow_mirror_loses_to_official_after_the_hedge_delay() {
        let (mirror, _) = server("mirror", 200, Duration::from_secs(5)).await;
        let (official, _) = server("official", 200, Duration::ZERO).await;
        let t = transport(mirror, official, Duration::from_millis(100));
        let start = Instant::now();
        assert_eq!(ask(&t).await.unwrap(), "official");
        assert!(start.elapsed() < Duration::from_secs(3));
    }

    #[tokio::test]
    async fn failed_mirror_starts_official_without_waiting_for_the_hedge() {
        let (official, _) = server("official", 200, Duration::ZERO).await;
        let t = transport("http://127.0.0.1:1".into(), official, Duration::from_secs(30));
        let start = Instant::now();
        assert_eq!(ask(&t).await.unwrap(), "official");
        assert!(start.elapsed() < Duration::from_secs(5));
    }

    #[tokio::test]
    async fn failed_official_waits_for_a_slow_mirror() {
        let (mirror, _) = server("mirror", 200, Duration::from_millis(800)).await;
        let (official, _) = server("official", 403, Duration::ZERO).await;
        let t = transport(mirror, official, Duration::ZERO);
        assert_eq!(ask(&t).await.unwrap(), "mirror");
    }

    #[tokio::test]
    async fn mirror_may_outlast_the_shared_client_timeout() {
        let (mirror, _) = server("mirror", 200, Duration::from_millis(600)).await;
        let (official, _) = server("official", 403, Duration::ZERO).await;
        let http = Client::builder().no_proxy().timeout(Duration::from_millis(200)).build().unwrap();
        let mut t = HttpTransport::new(http, true);
        t.mirror = mirror;
        t.official[1] = official;
        t.hedge_delay = Duration::ZERO;
        assert_eq!(ask(&t).await.unwrap(), "mirror");
    }

    #[tokio::test]
    async fn both_failing_reports_the_official_error() {
        let (mirror, _) = server("mirror", 500, Duration::ZERO).await;
        let (official, _) = server("official", 403, Duration::ZERO).await;
        let t = transport(mirror, official, Duration::ZERO);
        let error = ask(&t).await.unwrap_err();
        assert!(matches!(error.downcast_ref::<CatalogError>(), Some(CatalogError::Unauthorized(_))));
    }
}
