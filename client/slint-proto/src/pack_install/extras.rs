//! Kotlin `validateClientExtra`, `validateAndMergeClientExtras` and `Content.toClientContentRequest`.
use super::mods::{self, Mod, SourceHeaders};
use crate::{content_store::Request, digests::Digest, download::Source};
use anyhow::{Result, ensure};
use serde::Deserialize;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Deserialize)]
pub enum Platform {
    CurseForge,
    Modrinth,
    GitHub,
}
impl Platform {
    fn code(self) -> &'static str {
        match self {
            Self::CurseForge => "cf",
            Self::Modrinth => "mr",
            Self::GitHub => "github",
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Deserialize)]
pub enum Kind {
    Mod,
    ShaderPack,
    ResPack,
    DataPack,
    Other,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Deserialize)]
pub enum Side {
    Client,
    Server,
    Both,
}
fn yes() -> bool {
    true
}
#[derive(Clone, Debug, Eq, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Content {
    pub platform: Platform,
    #[serde(rename = "type")]
    pub kind: Kind,
    pub project_id: String,
    pub file_id: String,
    pub slug: String,
    pub hash: String,
    #[serde(default)]
    pub path: Option<String>,
    pub side: Side,
    #[serde(default = "yes")]
    pub required: bool,
    #[serde(default)]
    pub download_urls: Vec<String>,
}

impl Content {
    /// `targetRelativePath`.
    pub(crate) fn target(&self) -> Result<String> {
        if let Some(path) = &self.path {
            return Ok(path.clone());
        }
        let (dir, ext) = match self.kind {
            Kind::Mod => ("mods", "jar"),
            Kind::ResPack => ("resourcepacks", "zip"),
            Kind::ShaderPack => ("shaderpacks", "zip"),
            _ => anyhow::bail!("此内容类型必须指定安装路径"),
        };
        Ok(format!(
            "{dir}/{}_{}_{}.{ext}",
            self.slug,
            self.platform.code(),
            self.hash
        ))
    }
}

/// `normalizeClientExtraPath`.
pub fn normalize_path(raw: &str) -> Result<String> {
    ensure!(!raw.is_empty(), "客户端额外内容路径不能为空");
    ensure!(
        !raw.starts_with('/') && !raw.starts_with('\\'),
        "客户端额外内容路径不能是绝对路径"
    );
    let b = raw.as_bytes();
    ensure!(
        !(b.len() >= 2 && b[0].is_ascii_alphabetic() && b[1] == b':') && !raw.starts_with("\\\\"),
        "客户端额外内容路径不能是Windows绝对路径"
    );
    ensure!(
        !raw.contains('\0') && !raw.contains(':'),
        "客户端额外内容路径包含非法字符"
    );
    let normalized = raw.replace('\\', "/");
    ensure!(
        normalized
            .split('/')
            .all(|s| !s.trim().is_empty() && s != "." && s != ".."),
        "客户端额外内容路径无效: {raw}"
    );
    ensure!(
        normalized.starts_with("resourcepacks/") || normalized.starts_with("shaderpacks/"),
        "客户端额外内容必须位于resourcepacks或shaderpacks: {raw}"
    );
    Ok(normalized)
}

fn hex(s: &str, lengths: &[usize]) -> bool {
    lengths.contains(&s.len()) && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// `Content.validateClientExtra`.
pub fn validate(c: &Content) -> Result<Content> {
    ensure!(
        matches!(c.kind, Kind::ResPack | Kind::ShaderPack),
        "客户端额外内容类型无效"
    );
    let path = normalize_path(&c.target()?)?;
    let root = if c.kind == Kind::ResPack {
        "resourcepacks/"
    } else {
        "shaderpacks/"
    };
    ensure!(
        path.starts_with(root),
        "客户端额外内容类型与安装路径不匹配: {path}"
    );
    ensure!(
        !c.project_id.trim().is_empty() && !c.file_id.trim().is_empty() && !c.slug.trim().is_empty(),
        "客户端额外内容缺少平台身份"
    );
    let hash = c.hash.trim();
    match c.platform {
        Platform::Modrinth => ensure!(hex(hash, &[40]), "Modrinth客户端额外内容SHA-1无效"),
        Platform::CurseForge => ensure!(
            hash.parse::<u64>().is_ok_and(|v| v <= u64::from(u32::MAX)),
            "CurseForge客户端额外内容指纹无效"
        ),
        Platform::GitHub => ensure!(hex(hash, &[40, 64]), "GitHub客户端额外内容摘要无效"),
    }
    let mut urls: Vec<String> = vec![];
    for url in c.download_urls.iter().filter(|u| !u.trim().is_empty()) {
        let ok = reqwest::Url::parse(url.trim()).is_ok_and(|u| {
            matches!(u.scheme(), "http" | "https") && u.host_str().is_some_and(|h| !h.is_empty())
        });
        ensure!(ok, "客户端额外内容下载地址无效: {url}");
        if !urls.contains(&url.trim().to_owned()) {
            urls.push(url.trim().to_owned());
        }
    }
    Ok(Content {
        path: Some(path),
        download_urls: urls,
        ..c.clone()
    })
}

/// `validateAndMergeClientExtras`, then drop server-side entries as `materializeClientExtras` does.
pub fn merge(extras: &[Content]) -> Result<Vec<Content>> {
    let mut merged: Vec<(String, Content)> = vec![];
    for raw in extras {
        let c = validate(raw)?;
        let key = c.target()?.to_lowercase();
        if let Some((_, prev)) = merged.iter_mut().find(|(k, _)| *k == key) {
            ensure!(
                prev.platform == c.platform
                    && prev.project_id == c.project_id
                    && prev.file_id == c.file_id
                    && prev.hash.eq_ignore_ascii_case(&c.hash),
                "客户端额外内容目标路径冲突: {}",
                c.target()?
            );
            if prev.side != c.side {
                prev.side = Side::Both;
            }
            prev.required |= c.required;
            for url in c.download_urls {
                if !prev.download_urls.contains(&url) {
                    prev.download_urls.push(url);
                }
            }
        } else {
            merged.push((key, c));
        }
    }
    Ok(merged
        .into_iter()
        .map(|(_, c)| c)
        .filter(|c| c.side != Side::Server)
        .collect())
}

/// The same-shaped `Mod` Kotlin falls back to when an extra has no URLs.
pub fn fallback_mod(c: &Content) -> Mod {
    Mod {
        platform: c.platform.code().into(),
        project_id: c.project_id.clone(),
        slug: c.slug.clone(),
        file_id: c.file_id.clone(),
        hash: c.hash.clone(),
        side: mods::Side::Both,
        download_urls: c.download_urls.clone(),
        client_only_override: false,
        client_override_replaced: false,
    }
}

/// Request for a validated extra. With no URLs, `fallback` supplies the mod-style sources.
pub fn request(
    c: &Content,
    prefer_mirror: bool,
    headers: &dyn SourceHeaders,
    fallback: Option<Vec<Source>>,
) -> Result<Request> {
    let path = normalize_path(&c.target()?)?;
    let digest: Digest = mods::digest_for(c.platform.code(), &c.hash)?;
    let urls = &c.download_urls;
    let candidates: Vec<String> = if prefer_mirror {
        let mut out: Vec<String> = urls
            .iter()
            .map(|u| mods::mirror_url(u))
            .filter(|u| !urls.contains(u))
            .collect();
        out.extend(urls.iter().cloned());
        let mut distinct = vec![];
        for u in out {
            if !distinct.contains(&u) {
                distinct.push(u);
            }
        }
        distinct
    } else {
        urls.clone()
    };
    let sources = if candidates.is_empty() {
        fallback.unwrap_or_default()
    } else {
        candidates
            .into_iter()
            .map(|url| {
                Ok(Source {
                    headers: if c.platform == Platform::CurseForge {
                        headers.curse_forge_headers(&url)?
                    } else {
                        Default::default()
                    },
                    url,
                })
            })
            .collect::<Result<_>>()?
    };
    let platform = match c.platform {
        Platform::CurseForge => "CurseForge",
        Platform::Modrinth => "Modrinth",
        Platform::GitHub => "GitHub",
    };
    Ok(Request {
        id: format!(
            "{platform}:{}:{}:{}:{path}",
            c.project_id, c.file_id, c.hash
        ),
        relative_path: path,
        size: None,
        digests: vec![digest],
        sources,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn extra(v: serde_json::Value) -> Content {
        serde_json::from_value(v).unwrap()
    }

    #[test]
    fn validation_merge_and_server_filter() {
        let a = extra(json!({"platform":"Modrinth","type":"ResPack","projectId":"p","fileId":"f","slug":"s",
            "hash":"a".repeat(40),"side":"Client","downloadUrls":["https://cdn.modrinth.com/x.zip"," "]}));
        let b = extra(json!({"platform":"Modrinth","type":"ResPack","projectId":"p","fileId":"f","slug":"s",
            "hash":"A".repeat(40),"side":"Server","required":false,"path":"ResourcePacks/../x","downloadUrls":[]}));
        assert!(merge(std::slice::from_ref(&b)).unwrap_err().to_string().contains("路径无效"));
        let b = Content { path: None, ..b };
        let merged = merge(&[a.clone(), b]).unwrap();
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].side, Side::Both);
        assert_eq!(merged[0].target().unwrap(), format!("resourcepacks/s_mr_{}.zip", "a".repeat(40)));
        let server = Content { side: Side::Server, ..a.clone() };
        assert!(merge(&[server]).unwrap().is_empty());
        let conflict = Content { file_id: "g".into(), ..a.clone() };
        assert!(merge(&[a.clone(), conflict]).unwrap_err().to_string().contains("目标路径冲突"));
        let shader_in_res = Content { kind: Kind::ShaderPack, path: Some("resourcepacks/x.zip".into()), ..a.clone() };
        assert!(validate(&shader_in_res).is_err());
        let bad_cf = Content { platform: Platform::CurseForge, hash: "4294967296".into(), ..a };
        assert!(validate(&bad_cf).unwrap_err().to_string().contains("指纹无效"));
    }
}
