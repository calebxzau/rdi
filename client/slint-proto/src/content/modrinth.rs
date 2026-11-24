//! Modrinth requests and answers of the 材质/光影 pages (`ModrinthProjectSearchService`,
//! `ModrinthProjectInfoService`). The requests go through the catalog transport, so they race
//! the mirror like every other catalog call; Compose asks v3 for the project, v2 answers the
//! same fields these pages show.
use anyhow::{Context, Result};
use serde_json::Value;

pub const PAGE_SIZE: usize = 20;

/// A search card (`ModrinthProjectCardVo` as `ModrinthProjectCard` shows it).
#[derive(Clone, Debug, PartialEq)]
pub struct Card {
    pub id: String,
    pub title: String,
    pub follows: u64,
    pub icon: Option<String>,
    pub banner: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SearchPage {
    pub cards: Vec<Card>,
    pub has_more: bool,
}

fn text(v: &Value, key: &str) -> Option<String> {
    v[key].as_str().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned)
}

/// `GET search`: one project type, the pack's Minecraft version, most downloads first.
pub fn search_query(project_type: &str, query: &str, minecraft: Option<&str>, offset: usize) -> Vec<(String, String)> {
    let mut facets = vec![vec![format!("project_type:{project_type}")]];
    if let Some(v) = minecraft.map(str::trim).filter(|v| !v.is_empty()) {
        facets.push(vec![format!("versions:{v}")]);
    }
    let mut out = Vec::new();
    if !query.trim().is_empty() {
        out.push(("query".into(), query.trim().to_owned()));
    }
    out.push(("facets".into(), serde_json::to_string(&facets).unwrap_or_default()));
    out.push(("index".into(), "downloads".into()));
    out.push(("offset".into(), offset.to_string()));
    out.push(("limit".into(), PAGE_SIZE.to_string()));
    out
}

pub fn parse_search(v: &Value, project_type: &str) -> Result<SearchPage> {
    let hits = v["hits"].as_array().context("Modrinth搜索结果无效")?;
    let cards = hits
        .iter()
        .filter(|h| h["project_type"].as_str() == Some(project_type))
        .filter_map(|h| {
            Some(Card {
                id: text(h, "project_id")?,
                title: text(h, "title").unwrap_or_default(),
                follows: h["follows"].as_u64().unwrap_or(0),
                icon: text(h, "icon_url"),
                banner: text(h, "featured_gallery").or_else(|| h["gallery"].as_array().and_then(|g| g.first()).and_then(|u| u.as_str()).map(str::to_owned)),
            })
        })
        .collect();
    let (offset, limit, total) = (v["offset"].as_u64().unwrap_or(0), v["limit"].as_u64().unwrap_or(0), v["total_hits"].as_u64().unwrap_or(0));
    Ok(SearchPage { cards, has_more: offset + limit < total })
}

#[derive(Clone, Debug, PartialEq)]
pub struct Image {
    pub url: String,
    pub raw_url: Option<String>,
    pub name: Option<String>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct VersionFile {
    pub url: String,
    pub file_name: String,
    pub size: Option<u64>,
    pub sha1: Option<String>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct Version {
    pub id: String,
    pub number: String,
    /// `release`, `beta` or `alpha`.
    pub channel: String,
    pub minecraft: Vec<String>,
    pub published: String,
    pub file: Option<VersionFile>,
}
#[derive(Clone, Debug, PartialEq)]
pub struct Project {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub source_url: String,
    pub gallery: Vec<Image>,
    pub versions: Vec<Version>,
}

/// `GET project/{id}`; the gallery featured first, then by `ordering`. The versions come from
/// their own request.
pub fn parse_project(v: &Value) -> Result<Project> {
    let id = text(v, "id").context("Modrinth项目信息无效")?;
    let slug = text(v, "slug").unwrap_or_else(|| id.clone());
    let title = text(v, "title").unwrap_or_else(|| slug.clone());
    let mut gallery: Vec<(bool, i64, Image)> = v["gallery"]
        .as_array()
        .map(|g| {
            g.iter()
                .filter_map(|i| {
                    Some((i["featured"].as_bool().unwrap_or(false), i["ordering"].as_i64().unwrap_or(0), Image { url: text(i, "url")?, raw_url: text(i, "raw_url"), name: text(i, "title") }))
                })
                .collect()
        })
        .unwrap_or_default();
    gallery.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    Ok(Project {
        source_url: format!("https://modrinth.com/mod/{slug}"),
        id,
        slug,
        title,
        gallery: gallery.into_iter().map(|(_, _, i)| i).collect(),
        versions: vec![],
    })
}

/// `GET project/{id}/version`, newest first; the primary file, else the first.
pub fn parse_versions(v: &Value) -> Result<Vec<Version>> {
    let list = v.as_array().context("Modrinth版本列表无效")?;
    let mut versions: Vec<Version> = list
        .iter()
        .filter_map(|v| {
            let files = v["files"].as_array()?;
            let file = files.iter().find(|f| f["primary"].as_bool() == Some(true)).or_else(|| files.first());
            Some(Version {
                id: text(v, "id")?,
                number: text(v, "version_number").or_else(|| text(v, "name")).unwrap_or_default(),
                channel: text(v, "version_type").unwrap_or_else(|| "release".into()),
                minecraft: v["game_versions"].as_array().map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_owned)).collect()).unwrap_or_default(),
                published: text(v, "date_published").unwrap_or_default(),
                file: file.and_then(|f| {
                    Some(VersionFile {
                        url: text(f, "url")?,
                        file_name: text(f, "filename")?,
                        size: f["size"].as_u64(),
                        sha1: f["hashes"]["sha1"].as_str().map(|s| s.trim().to_lowercase()).filter(|s| !s.is_empty()),
                    })
                }),
            })
        })
        .collect();
    versions.sort_by(|a, b| b.published.cmp(&a.published));
    Ok(versions)
}

/// `ShaderDownloadVersionRow`: the versions for the pack's Minecraft version, alpha only when
/// shown, betas first.
pub fn compatible<'a>(versions: &'a [Version], minecraft: &str, include_alpha: bool) -> Vec<&'a Version> {
    let mut out: Vec<&Version> = versions
        .iter()
        .filter(|v| v.minecraft.iter().any(|m| m == minecraft) && (include_alpha || !v.channel.eq_ignore_ascii_case("alpha")))
        .collect();
    out.sort_by_key(|v| if v.channel.eq_ignore_ascii_case("beta") { 0 } else { 1 });
    out
}
