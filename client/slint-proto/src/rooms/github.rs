//! 添加附加Mod（高级模式）, GitHub only (owner decision D3): Compose `GithubExtraModService`.
//! The manual pj/slug/hash form of Compose is a developer screen and is not ported.
use super::mods::jar_mod_id;
use crate::{
    download::{Downloader, Source},
    pack_install::mods::{Mod, Side},
    tasks::Cancellation,
};
use anyhow::{Result, bail, ensure};
use serde::Deserialize;
use std::path::Path;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Repo {
    pub owner: String,
    pub name: String,
}
impl Repo {
    pub fn project_id(&self) -> String {
        format!("{}/{}", self.owner, self.name)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Asset {
    pub release_tag: String,
    pub name: String,
    pub size: u64,
    pub url: String,
    pub digest: Option<String>,
}
impl Asset {
    pub fn key(&self) -> String {
        format!("{}/{}", self.release_tag, self.name)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Release {
    pub tag: String,
    pub name: String,
    pub assets: Vec<Asset>,
}

/// `parseRepo`: `https://github.com/owner/repo`, also without the scheme; `.git` is dropped.
/// Other hosts are refused (Compose would misread them as a path).
pub fn parse_repo(url: &str) -> Result<Repo> {
    let raw = url.trim().trim_end_matches('/');
    ensure!(!raw.is_empty(), "GitHub repo URL不能为空");
    let rest = match reqwest::Url::parse(raw) {
        Ok(u) if u.host_str().is_some_and(|h| h.eq_ignore_ascii_case("github.com") || h.eq_ignore_ascii_case("www.github.com")) => u.path().to_owned(),
        Ok(u) if u.host_str().is_some() => bail!("只支持github.com仓库链接"),
        _ => raw.strip_prefix("github.com").unwrap_or(raw).to_owned(),
    };
    let parts: Vec<&str> = rest.split('/').filter(|p| !p.trim().is_empty()).collect();
    ensure!(parts.len() >= 2, "GitHub repo URL格式应为https://github.com/owner/repo");
    let name = parts[1].strip_suffix(".git").unwrap_or(parts[1]);
    let safe = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'));
    ensure!(safe(parts[0]) && safe(name), "GitHub repo URL格式应为https://github.com/owner/repo");
    Ok(Repo { owner: parts[0].to_owned(), name: name.to_owned() })
}

#[derive(Deserialize)]
struct ReleaseResp {
    #[serde(default)]
    tag_name: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    assets: Vec<AssetResp>,
}
#[derive(Deserialize)]
struct AssetResp {
    #[serde(default)]
    name: String,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    browser_download_url: String,
    #[serde(default)]
    digest: Option<String>,
}

/// Releases that hold at least one jar, newest first as GitHub lists them.
pub fn releases_from(body: &str) -> Result<Vec<Release>> {
    let list: Vec<ReleaseResp> = serde_json::from_str(body).map_err(|_| anyhow::anyhow!("读取GitHub Release失败: 数据格式不兼容"))?;
    let releases: Vec<Release> = list
        .into_iter()
        .map(|r| {
            let name = r.name.filter(|n| !n.trim().is_empty()).unwrap_or_else(|| r.tag_name.clone());
            let assets = r
                .assets
                .into_iter()
                .filter(|a| a.name.to_lowercase().ends_with(".jar") && !a.browser_download_url.trim().is_empty())
                .map(|a| Asset { release_tag: r.tag_name.clone(), name: a.name, size: a.size, url: a.browser_download_url, digest: a.digest })
                .collect();
            Release { tag: r.tag_name, name, assets }
        })
        .filter(|r: &Release| !r.assets.is_empty())
        .collect();
    ensure!(!releases.is_empty(), "该仓库没有包含jar文件的Release");
    Ok(releases)
}

pub async fn fetch_releases(http: &reqwest::Client, repo: &Repo) -> Result<Vec<Release>> {
    fetch_releases_from(http, "https://api.github.com", repo).await
}
async fn fetch_releases_from(http: &reqwest::Client, base: &str, repo: &Repo) -> Result<Vec<Release>> {
    let response = http
        .get(format!("{base}/repos/{}/{}/releases", repo.owner, repo.name))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send()
        .await
        .map_err(|e| {
            tracing::warn!(error = %e.without_url(), "GitHub Release读取失败");
            anyhow::anyhow!("读取GitHub Release失败: 无法连接GitHub")
        })?;
    let status = response.status();
    if !status.is_success() {
        let remaining = response.headers().get("X-RateLimit-Remaining").and_then(|v| v.to_str().ok()).map(str::to_owned);
        let text = format!("{} {}", status.as_u16(), status.canonical_reason().unwrap_or(""));
        match status.as_u16() {
            404 => bail!("无法读取{}的Release，确认仓库存在且是公开仓库", repo.project_id()),
            403 if remaining.as_deref() == Some("0") => bail!("GitHub API请求次数已用完，稍后再试"),
            403 => bail!("GitHub拒绝请求: {text}"),
            _ => {
                let body: String = response.text().await.unwrap_or_default().chars().take(200).collect();
                bail!("读取GitHub Release失败: {text} {body}")
            }
        }
    }
    releases_from(&response.text().await.map_err(|_| anyhow::anyhow!("读取GitHub Release失败: 连接中断"))?)
}

/// GitHub's `sha256:<hex>` asset digest.
pub fn sha256_of(digest: Option<&str>) -> Option<String> {
    let hex = digest?.trim().strip_prefix("sha256:").or_else(|| digest?.trim().strip_prefix("SHA256:"))?;
    (hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit())).then(|| hex.to_lowercase())
}

/// `buildExtraModFromAsset`: downloads the jar into `work`, reads its mod id and hashes it.
pub async fn build_mod(http: &reqwest::Client, repo: &Repo, asset: &Asset, side: Side, work: &Path, cancel: &Cancellation) -> Result<Mod> {
    std::fs::create_dir_all(work)?;
    let jar = work.join(sanitize(&asset.name));
    let expected = sha256_of(asset.digest.as_deref());
    let validate = |file: &Path| -> Result<()> {
        if let Some(expected) = &expected {
            let actual = crate::digests::hash(file, crate::digests::Algorithm::Sha256, cancel)?;
            ensure!(actual.eq_ignore_ascii_case(expected), "{}校验失败，请重试", asset.name);
        }
        Ok(())
    };
    let source = Source { url: asset.url.clone(), headers: Default::default() };
    let known = (asset.size > 0).then_some(asset.size);
    Downloader { http: http.clone() }.download(&[source], &jar, known, cancel, &validate, &|_| {}).await?;
    let (mod_id, hash) = {
        let (jar, cancel) = (jar.clone(), cancel.clone());
        tokio::task::spawn_blocking(move || -> Result<(String, String)> {
            Ok((jar_mod_id(&jar)?, crate::digests::hash(&jar, crate::digests::Algorithm::Sha256, &cancel)?.to_lowercase()))
        })
        .await??
    };
    Ok(Mod {
        platform: "github".into(),
        project_id: repo.project_id(),
        slug: mod_id,
        file_id: asset.key(),
        hash,
        side,
        download_urls: vec![asset.url.clone()],
        client_only_override: false,
        client_override_replaced: false,
    })
}

fn sanitize(name: &str) -> String {
    let cleaned: String = name.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+') { c } else { '_' }).collect();
    if cleaned.trim_matches('.').is_empty() { "mod.jar".into() } else { cleaned }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::server::tests::read_request;
    use tokio::io::AsyncWriteExt;

    #[test]
    fn repo_urls() {
        let r = |s: &str| parse_repo(s).map(|r| r.project_id()).map_err(|e| e.to_string());
        assert_eq!(r("https://github.com/owner/repo/"), Ok("owner/repo".into()));
        assert_eq!(r("https://github.com/owner/repo.git"), Ok("owner/repo".into()));
        assert_eq!(r("github.com/owner/repo/releases"), Ok("owner/repo".into()));
        assert_eq!(r("owner/repo"), Ok("owner/repo".into()));
        assert_eq!(r(" "), Err("GitHub repo URL不能为空".into()));
        assert_eq!(r("https://gitlab.com/a/b"), Err("只支持github.com仓库链接".into()));
        assert_eq!(r("https://github.com/owner"), Err("GitHub repo URL格式应为https://github.com/owner/repo".into()));
        assert_eq!(r("https://github.com/o/r?x"), Ok("o/r".into()));
    }

    #[test]
    fn releases_keep_only_jars() {
        let body = r#"[
            {"tag_name":"v2","name":"","assets":[{"name":"m-2.jar","size":5,"browser_download_url":"https://x/m-2.jar","digest":"sha256:ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"},{"name":"src.zip","size":1,"browser_download_url":"https://x/s"}]},
            {"tag_name":"v1","name":"First","assets":[{"name":"notes.txt","browser_download_url":"https://x/n"}]}
        ]"#;
        let releases = releases_from(body).unwrap();
        assert_eq!(releases.len(), 1);
        assert_eq!((releases[0].name.as_str(), releases[0].assets.len()), ("v2", 1));
        assert_eq!(releases[0].assets[0].key(), "v2/m-2.jar");
        assert_eq!(sha256_of(releases[0].assets[0].digest.as_deref()).unwrap(), "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        assert_eq!(sha256_of(Some("sha1:abc")), None);
        assert_eq!(releases_from("[]").unwrap_err().to_string(), "该仓库没有包含jar文件的Release");
        assert_eq!(sanitize("../a b.jar"), ".._a_b.jar");
    }

    async fn github(status: &str, headers: &str, body: &str) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let response = format!("HTTP/1.1 {status}\r\nContent-Type: application/json\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let request = read_request(&mut socket).await;
            socket.write_all(response.as_bytes()).await.unwrap();
            request
        });
        let repo = Repo { owner: "o".into(), name: "r".into() };
        let result = fetch_releases_from(&reqwest::Client::new(), &base, &repo).await;
        let request = server.await.unwrap();
        assert!(request.starts_with("GET /repos/o/r/releases "));
        assert!(request.to_lowercase().contains("x-github-api-version: 2022-11-28"));
        match result {
            Ok(list) => format!("ok {}", list.len()),
            Err(e) => e.to_string(),
        }
    }

    #[tokio::test]
    async fn github_errors_match_compose() {
        assert_eq!(github("404 Not Found", "", "{}").await, "无法读取o/r的Release，确认仓库存在且是公开仓库");
        assert_eq!(github("403 Forbidden", "X-RateLimit-Remaining: 0\r\n", "{}").await, "GitHub API请求次数已用完，稍后再试");
        assert_eq!(github("403 Forbidden", "X-RateLimit-Remaining: 9\r\n", "{}").await, "GitHub拒绝请求: 403 Forbidden");
        assert_eq!(github("200 OK", "", r#"[{"tag_name":"v1","assets":[{"name":"a.jar","browser_download_url":"https://x/a.jar"}]}]"#).await, "ok 1");
    }
}
