//! U4e1: the mod catalog and the 材质/光影 pages (Compose `ModCatalogScreen`,
//! `ModCatalogInfoScreen`, `ResourceListScreen` and `ResourceInfoScreen`). Nothing here knows
//! Slint; `content_ui.rs` wires it to the `Content` global.
pub mod install;
pub mod modrinth;
pub mod records;
pub mod status;

use crate::{
    catalog::models::{self as catalog, Channel, DependencyGraph, DependencyRequirement, DependencyTarget, Environment, File, FileRef, Mod, Platform, Requirement},
    digests::Algorithm,
    pack_install::mods::{self as legacy, Side},
};
use anyhow::{Result, anyhow, ensure};
use std::{collections::BTreeSet, path::PathBuf};

/// The three entries of a pack's menu (Compose `ModpackContentType`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Mods,
    ResourcePacks,
    Shaders,
}
impl Kind {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "mods" => Some(Self::Mods),
            "resourcepacks" => Some(Self::ResourcePacks),
            "shaders" => Some(Self::Shaders),
            _ => None,
        }
    }
    /// The page title (`projectDisplayName`; 模组 for the catalog).
    pub fn label(self) -> &'static str {
        match self {
            Self::Mods => "模组",
            Self::ResourcePacks => "资源包",
            Self::Shaders => "光影包",
        }
    }
    /// The pack folder the files go to (`targetDirName`).
    pub fn dir(self) -> &'static str {
        match self {
            Self::Mods => "mods",
            Self::ResourcePacks => "resourcepacks",
            Self::Shaders => "shaderpacks",
        }
    }
    /// The Modrinth `project_type` of the search (`ResourcepackSearchService`, `ShaderSearchService`).
    pub fn project_type(self) -> &'static str {
        match self {
            Self::Mods => "mod",
            Self::ResourcePacks => "resourcepack",
            Self::Shaders => "shader",
        }
    }
    pub fn record_type(self) -> records::ContentType {
        match self {
            Self::Mods => records::ContentType::Mod,
            Self::ResourcePacks => records::ContentType::ResourcePack,
            Self::Shaders => records::ContentType::ShaderPack,
        }
    }
}

/// Where the catalog installs: an installed pack (Compose `CatalogInstallTarget.Local`) or a
/// room's 附加Mod (`CatalogInstallTarget.HostTarget`). Both entries fix the target, so the
/// dialog never offers a choice.
#[derive(Clone, Debug, PartialEq)]
pub enum Target {
    Local { version_id: String, name: String, version: String, dir: PathBuf, minecraft: String, loader: String },
    Room { id: String, name: String, minecraft: String, loader: String },
}
impl Target {
    /// `1.20.1`, as `McVersion.mcVer`.
    pub fn minecraft(&self) -> &str {
        match self {
            Self::Local { minecraft, .. } | Self::Room { minecraft, .. } => minecraft,
        }
    }
    /// `forge`, `neoforge` or `Fabric`, as `ModLoader.name`.
    pub fn loader(&self) -> &str {
        match self {
            Self::Local { loader, .. } | Self::Room { loader, .. } => loader,
        }
    }
    pub fn catalog(&self) -> catalog::Target {
        catalog::Target { minecraft: self.minecraft().to_owned(), loader: self.loader().to_owned() }
    }
    /// The catalog needs a modern version with a supported loader; 1.7.10 packs have none.
    pub fn check(&self) -> Result<catalog::Target> {
        let target = self.catalog();
        target.validate().map_err(|_| anyhow!("{} {}的整合包暂不支持模组库", self.minecraft(), self.loader()))?;
        Ok(target)
    }
    /// The line under the install dialog.
    pub fn install_line(&self) -> String {
        match self {
            Self::Local { name, version, .. } => format!("安装到整合包：{name} {version}"),
            Self::Room { name, .. } => format!("安装到房间：{name}"),
        }
    }
    pub fn local(&self) -> Option<(&str, &str, &std::path::Path)> {
        match self {
            Self::Local { version_id, name, dir, .. } => Some((version_id, name, dir)),
            Self::Room { .. } => None,
        }
    }
}

/// `String.toReadableDescription`: no tags or iframes, one line, at most 350 characters.
pub fn readable(text: &str) -> String {
    let mut out = String::new();
    let lower = text.to_lowercase();
    let mut i = 0;
    while i < text.len() {
        let rest = &lower[i..];
        if rest.starts_with("<iframe")
            && let Some(end) = rest.find("</iframe>")
        {
            i += end + "</iframe>".len();
            continue;
        }
        if rest.starts_with("<br") && let Some(end) = rest.find('>') {
            let tag = &rest[3..end];
            if tag.trim().trim_end_matches('/').trim().is_empty() {
                out.push(' ');
                i += end + 1;
                continue;
            }
        }
        if rest.starts_with('<') && let Some(end) = rest.find('>') {
            i += end + 1;
            continue;
        }
        let c = text[i..].chars().next().unwrap_or(' ');
        out.push(c);
        i += c.len_utf8();
    }
    let out = out.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    let collapsed = out.split_whitespace().collect::<Vec<_>>().join(" ");
    collapsed.chars().take(350).collect::<String>().trim().to_owned()
}

/// `Long.toSeparatedCountText`: 1,234,567.
pub fn separated_count(n: u64) -> String {
    let digits = n.to_string();
    let mut out = String::new();
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

/// `compactedCnCount` of the catalog card: two decimals, 1.20万, 3.45亿.
pub fn compact_cn(n: u64) -> String {
    if n >= 100_000_000 {
        format!("{:.2}亿", n as f64 / 100_000_000.0)
    } else if n >= 10_000 {
        format!("{:.2}万", n as f64 / 10_000.0)
    } else {
        n.to_string()
    }
}

/// `Instant.displayDate()`: the publish day in the local time zone.
pub fn display_date(published: &str) -> String {
    chrono::DateTime::parse_from_rfc3339(published)
        .map(|t| t.with_timezone(&chrono::Local).format("%Y-%m-%d").to_string())
        .unwrap_or_else(|_| published.get(..10).unwrap_or(published).to_owned())
}

/// The catalog name a card shows: the Chinese name, else the name.
pub fn display_name(m: &Mod) -> String {
    m.name_cn.as_deref().map(str::trim).filter(|n| !n.is_empty()).unwrap_or(m.name.trim()).to_owned()
}

/// The MC百科 class id of a mod grouped by the identity index (`identity` is `mcmod:<id>`).
pub fn mcmod_id(m: &Mod) -> Option<&str> {
    m.identity.strip_prefix("mcmod:").filter(|id| !id.is_empty() && id.bytes().all(|b| b.is_ascii_digit()))
}

/// A file's version line (`versionNumber ?: displayName`).
pub fn file_label(file: &File) -> String {
    file.version.as_deref().map(str::trim).filter(|v| !v.is_empty()).unwrap_or(&file.name).to_owned()
}

/// Compose sorts the files beta first, keeping the catalog order otherwise.
pub fn sort_files(files: &mut [File]) {
    files.sort_by_key(|f| if f.channel == Channel::Beta { 0 } else { 1 });
}

/// The newest file (`maxByOrNull { publishedAt }`); the first wins a tie.
pub fn latest(files: &[File]) -> Option<&File> {
    let mut best: Option<&File> = None;
    for f in files {
        if best.is_none_or(|b| published_key(&f.published) > published_key(&b.published)) {
            best = Some(f);
        }
    }
    best
}
fn published_key(text: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(text).map(|t| t.timestamp_millis()).unwrap_or(i64::MIN)
}

/// `requiredDependencyRefs`: the root and every file it needs, through required edges only.
pub fn required_refs(graph: &DependencyGraph, root: &FileRef) -> Vec<FileRef> {
    let mut found = vec![root.clone()];
    let mut seen: BTreeSet<FileRef> = found.iter().cloned().collect();
    loop {
        let mut changed = false;
        for (from, dep) in &graph.edges {
            if dep.requirement != DependencyRequirement::Required || !seen.contains(from) {
                continue;
            }
            let next = match &dep.target {
                DependencyTarget::File(r) => Some(r.clone()),
                DependencyTarget::Project(p) => graph.nodes.values().find(|(f, _)| &f.project == p).map(|(f, _)| f.reference.clone()),
            };
            if let Some(next) = next
                && seen.insert(next.clone())
            {
                found.push(next);
                changed = true;
            }
        }
        if !changed {
            return found;
        }
    }
}

/// `EnvironmentCompatibility.toLegacySide`.
pub fn legacy_side(env: Environment) -> Side {
    match (env.client, env.server) {
        (Requirement::Unsupported, s) if s != Requirement::Unsupported => Side::Server,
        (c, Requirement::Unsupported) if c != Requirement::Unsupported => Side::Client,
        _ => Side::Both,
    }
}

/// `CatalogMod.toLegacyMod`: the mod as rooms and the install records know it.
pub fn to_legacy(m: &Mod, file: &File, url: &str) -> Result<legacy::Mod> {
    let source = m.sources.iter().find(|s| s.reference == file.project).or_else(|| m.sources.first()).ok_or_else(|| anyhow!("模组来源信息缺失"))?;
    let (platform, algorithm) = match file.reference.platform {
        Platform::CurseForge => ("cf", Algorithm::Murmur2),
        Platform::Modrinth => ("mr", Algorithm::Sha1),
    };
    let hash = file.digests.iter().find(|d| d.algorithm == algorithm).ok_or_else(|| anyhow!("当前文件缺少校验信息"))?;
    Ok(serde_json::from_value(serde_json::json!({
        "platform": platform,
        "projectId": file.project.id,
        "slug": source.slug,
        "fileId": file.reference.id,
        "hash": hash.value,
        "side": legacy_side(file.environment.with_fallback(source.environment)),
        "downloadUrls": [url],
    }))?)
}

/// `validateTargetVersion`: a room only takes a file made for its Minecraft version.
pub fn check_room_version(minecraft: &str, file: &File) -> Result<()> {
    ensure!(file.minecraft.is_empty() || file.minecraft.iter().any(|v| v == minecraft), "目标MC版本与当前模组版本不匹配");
    Ok(())
}

#[cfg(test)]
mod tests;
