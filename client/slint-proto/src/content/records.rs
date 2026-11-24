//! `<pack>/.rdi/content-installations.json`, shared with Compose `LocalContentInstallStore`: which
//! mod, resource pack or shader file the launcher put into a pack.
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    path::{Path, PathBuf},
    sync::Mutex,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ContentType {
    Mod,
    ResourcePack,
    ShaderPack,
}
impl ContentType {
    fn name(self) -> &'static str {
        match self {
            Self::Mod => "MOD",
            Self::ResourcePack => "RESOURCE_PACK",
            Self::ShaderPack => "SHADER_PACK",
        }
    }
}

/// `LocalContentInstallRecord`. `source` is `cf` or `mr` for mods, `modrinth` for packs.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Record {
    #[serde(rename = "type")]
    pub kind: ContentType,
    pub source: String,
    pub project_id: String,
    pub version_id: String,
    pub file_name: String,
    #[serde(default)]
    pub hash: Option<String>,
    #[serde(default = "now")]
    pub installed_at: i64,
}
impl Record {
    /// `projectKey`: `MOD:mr:AANobbMI`.
    pub fn project_key(&self) -> String {
        key(self.kind, &self.source, &self.project_id)
    }
}
pub fn key(kind: ContentType, source: &str, project_id: &str) -> String {
    format!("{}:{source}:{project_id}", kind.name())
}
pub fn now() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

#[derive(Default, Serialize, Deserialize)]
struct Manifest {
    #[serde(default)]
    entries: Vec<Record>,
}

/// One writer at a time, as Kotlin's `@Synchronized replace`.
static WRITE: Mutex<()> = Mutex::new(());

pub fn path(pack_dir: &Path) -> PathBuf {
    pack_dir.join(".rdi").join("content-installations.json")
}

/// The records of a pack; none when the file does not exist yet.
pub fn read(pack_dir: &Path) -> Result<Vec<Record>> {
    let file = path(pack_dir);
    let text = match std::fs::read_to_string(&file) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(e) => return Err(e).with_context(|| format!("读取安装记录失败: {}", file.display())),
    };
    Ok(serde_json::from_str::<Manifest>(&text).context("安装记录格式无效")?.entries)
}

pub fn find<'a>(records: &'a [Record], kind: ContentType, source: &str, project_id: &str) -> Option<&'a Record> {
    records.iter().find(|r| r.kind == kind && r.source == source && r.project_id == project_id)
}

/// `replace`: these records take the place of the ones with the same project key.
pub fn replace(pack_dir: &Path, new: &[Record]) -> Result<()> {
    let _guard = WRITE.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    let keys: Vec<String> = new.iter().map(Record::project_key).collect();
    let mut entries: Vec<Record> = read(pack_dir)?.into_iter().filter(|r| !keys.contains(&r.project_key())).collect();
    entries.extend(new.iter().cloned());
    let file = path(pack_dir);
    let parent = file.parent().context("安装记录路径无效")?;
    std::fs::create_dir_all(parent)?;
    let temporary = parent.join("content-installations.json.tmp");
    std::fs::write(&temporary, serde_json::to_vec(&Manifest { entries })?)?;
    std::fs::rename(&temporary, &file).with_context(|| format!("写入安装记录失败: {}", file.display()))
}
