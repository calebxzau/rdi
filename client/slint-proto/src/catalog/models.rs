use crate::digests::Digest;
use serde::{Deserialize, Serialize};
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize)]
pub enum Platform {
    CurseForge,
    Modrinth,
}
impl Platform {
    pub fn db(self) -> &'static str {
        match self {
            Self::CurseForge => "CURSEFORGE",
            Self::Modrinth => "MODRINTH",
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize)]
pub struct ProjectRef {
    pub platform: Platform,
    pub id: String,
}
#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd, Hash, Serialize, Deserialize)]
pub struct FileRef {
    pub platform: Platform,
    pub id: String,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum Requirement {
    Required,
    Optional,
    Unsupported,
    Unknown,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Environment {
    pub client: Requirement,
    pub server: Requirement,
}
impl Default for Environment {
    fn default() -> Self {
        Self {
            client: Requirement::Unknown,
            server: Requirement::Unknown,
        }
    }
}
impl Environment {
    pub fn with_fallback(self, other: Self) -> Self {
        Self {
            client: if self.client == Requirement::Unknown {
                other.client
            } else {
                self.client
            },
            server: if self.server == Requirement::Unknown {
                other.server
            } else {
                self.server
            },
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum ContentType {
    Mod,
    ResourcePack,
    ShaderPack,
    Other,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Project {
    pub reference: ProjectRef,
    pub slug: String,
    pub name: String,
    pub summary: String,
    pub icon: Option<String>,
    pub downloads: u64,
    pub updated: String,
    pub environment: Environment,
    pub kind: ContentType,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize, Deserialize)]
pub enum Channel {
    Release,
    Beta,
    Alpha,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum DependencyRequirement {
    Required,
    Optional,
    Incompatible,
    Embedded,
    Tool,
    Included,
}
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum DependencyTarget {
    Project(ProjectRef),
    File(FileRef),
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Dependency {
    pub target: DependencyTarget,
    pub requirement: DependencyRequirement,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct File {
    pub reference: FileRef,
    pub project: ProjectRef,
    pub name: String,
    pub version: Option<String>,
    pub filename: String,
    pub channel: Channel,
    pub published: String,
    pub size: u64,
    pub minecraft: Vec<String>,
    pub loaders: Vec<String>,
    pub environment: Environment,
    pub digests: Vec<Digest>,
    pub dependencies: Vec<Dependency>,
    pub url: Option<String>,
}
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Target {
    pub minecraft: String,
    pub loader: String,
}
impl Target {
    pub fn validate(&self) -> anyhow::Result<()> {
        let v = crate::import::scanner::version_entry(&self.minecraft)?;
        anyhow::ensure!(
            crate::import::scanner::supported(v, &self.loader),
            "{} does not support {}",
            self.minecraft,
            self.loader
        );
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct IdentityProject {
    pub platform: Platform,
    pub slug: String,
    pub name_cn: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Metadata {
    pub mcmod_id: i64,
    pub name: String,
    pub name_cn: Option<String>,
    pub intro: Option<String>,
    pub icon: Option<String>,
    pub projects: Vec<IdentityProject>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Mod {
    pub identity: String,
    pub primary: ProjectRef,
    pub sources: Vec<Project>,
    pub name: String,
    pub name_cn: Option<String>,
    pub summary: String,
    pub icons: Vec<String>,
    pub downloads: u64,
    pub updated: String,
    pub environment: Environment,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum Issue {
    SourceFailed(Platform, String),
    IdentityIndexUnavailable(String),
    MissingProjects(Vec<ProjectRef>),
    MissingFiles(Vec<FileRef>),
}
#[derive(Clone, Debug)]
pub struct Outcome<T> {
    pub value: T,
    pub issues: Vec<Issue>,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum Sort {
    Relevance,
    Downloads,
    Updated,
}
#[derive(Clone, Debug)]
pub struct SearchRequest {
    pub query: String,
    pub target: Target,
    pub sort: Sort,
    pub page_size: usize,
    pub cursor: Option<SearchCursor>,
}
#[derive(Clone, Debug, Default)]
pub struct SearchCursor {
    pub request_key: String,
    pub local_offset: usize,
    pub local_exhausted: bool,
    pub offsets: std::collections::BTreeMap<Platform, usize>,
    pub exhausted: std::collections::BTreeSet<Platform>,
    pub buffer: Vec<Mod>,
    pub platform_buffers: std::collections::BTreeMap<Platform, Vec<Project>>,
    pub fallback_query: Option<String>,
    pub seen: std::collections::BTreeSet<String>,
}
#[derive(Clone, Debug)]
pub struct SearchPage {
    pub items: Vec<Mod>,
    pub cursor: Option<SearchCursor>,
    pub estimated_total: Option<u64>,
}
#[derive(Clone, Debug)]
pub struct Details {
    pub item: Mod,
    pub description: String,
    pub authors: Vec<String>,
    pub categories: Vec<String>,
    pub source_url: Option<String>,
    pub issues_url: Option<String>,
    pub wiki_url: Option<String>,
}
#[derive(Clone, Debug)]
pub struct FileRequest {
    pub item: Mod,
    pub target: Target,
    pub channels: Vec<Channel>,
    pub page_size: usize,
    pub cursor: Option<(Platform, usize)>,
}
#[derive(Clone, Debug)]
pub struct FilePage {
    pub items: Vec<File>,
    pub cursor: Option<(Platform, usize)>,
}
#[derive(Clone, Debug)]
pub struct FileHashes {
    pub key: String,
    pub sha1: String,
    pub murmur2: Option<u32>,
}
#[derive(Clone, Debug, Default)]
pub struct LocalMatchReport {
    pub matched: Vec<(std::path::PathBuf, Vec<File>, Option<String>)>,
    pub unmatched: Vec<std::path::PathBuf>,
    pub unreadable: std::collections::BTreeMap<std::path::PathBuf, String>,
}
#[derive(Clone, Debug, Default)]
pub struct UpdateReport {
    pub updates: Vec<(File, File)>,
    pub unchanged: Vec<FileRef>,
    pub unresolved: Vec<FileRef>,
}
#[derive(Clone, Debug, Default)]
pub struct DependencyGraph {
    pub roots: Vec<FileRef>,
    pub nodes: std::collections::BTreeMap<FileRef, (File, usize)>,
    pub edges: Vec<(FileRef, Dependency)>,
    pub unresolved: Vec<(FileRef, DependencyTarget, String)>,
    pub cycles: Vec<Vec<FileRef>>,
}
#[derive(Clone, Debug)]
pub enum CatalogError {
    Unauthorized(Platform),
    RateLimited(Platform, Option<u64>),
    Network(Platform),
    InvalidResponse(Platform),
    AllSourcesFailed,
    DownloadUnavailable(FileRef),
    IdentityDatabase(String),
}
impl std::fmt::Display for CatalogError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Unauthorized(p) => write!(f, "{} rejected the credentials", p.db()),
            Self::RateLimited(p, _) => write!(f, "{} rate limit exceeded", p.db()),
            Self::Network(p) => write!(f, "{} request failed", p.db()),
            Self::InvalidResponse(p) => write!(f, "{} returned an invalid response", p.db()),
            Self::AllSourcesFailed => write!(f, "All mod catalog sources failed"),
            Self::DownloadUnavailable(r) => write!(
                f,
                "Download is unavailable for {}:{}",
                r.platform.db(),
                r.id
            ),
            Self::IdentityDatabase(s) => f.write_str(s),
        }
    }
}
impl std::error::Error for CatalogError {}
