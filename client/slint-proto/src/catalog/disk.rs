//! 15-day disk cache of mod project metadata (owner decision 2026-11-23): names, intros and icon
//! URLs rarely change, and the Modrinth mirror needs about 0.27 s per id, so a large pack took
//! minutes after every launcher start. Only projects that were found are stored; absent ones
//! stay in the memory cache. A row that no longer deserializes counts as missing.
use super::models::{Project, ProjectRef};
use anyhow::{Context, Result};
use rusqlite::{Connection, OptionalExtension, params};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

pub const TTL: Duration = Duration::from_secs(15 * 24 * 3600);

pub struct ProjectDiskCache {
    db: Mutex<Connection>,
}

pub fn path(data: &Path) -> PathBuf {
    data.join("catalog-cache.db")
}

fn unix(time: SystemTime) -> i64 {
    time.duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn open_db(path: &Path) -> Result<Connection> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).with_context(|| format!("创建{}失败", dir.display()))?;
    }
    let db = Connection::open(path).with_context(|| format!("打开{}失败", path.display()))?;
    // Several catalog instances (pages, installs) and launcher processes share the file.
    db.busy_timeout(Duration::from_secs(5))?;
    db.execute_batch(
        "PRAGMA journal_mode=WAL;
         CREATE TABLE IF NOT EXISTS projects(
             platform TEXT NOT NULL, id TEXT NOT NULL, stored INTEGER NOT NULL, json TEXT NOT NULL,
             PRIMARY KEY(platform, id)) WITHOUT ROWID;",
    )?;
    Ok(db)
}

impl ProjectDiskCache {
    /// Opens the cache and drops expired rows.
    pub fn open(path: &Path) -> Result<Self> {
        let db = open_db(path)?;
        db.execute("DELETE FROM projects WHERE stored < ?1", params![unix(SystemTime::now() - TTL)])?;
        Ok(Self { db: Mutex::new(db) })
    }

    /// The projects among `refs` stored within the last 15 days.
    pub fn get_many(&self, refs: &[ProjectRef], now: SystemTime) -> HashMap<ProjectRef, Project> {
        let mut found = HashMap::new();
        let db = self.db.lock().unwrap_or_else(|e| e.into_inner());
        let result = (|| -> Result<()> {
            let mut query = db.prepare_cached("SELECT json FROM projects WHERE platform=?1 AND id=?2 AND stored>=?3")?;
            for r in refs {
                let json: Option<String> = query.query_row(params![r.platform.db(), r.id, unix(now - TTL)], |row| row.get(0)).optional()?;
                match json.map(|j| serde_json::from_str::<Project>(&j)) {
                    Some(Ok(project)) if project.reference == *r => {
                        found.insert(r.clone(), project);
                    }
                    Some(Ok(_)) | Some(Err(_)) => tracing::debug!(platform = ?r.platform, id = %r.id, "Mod资料缓存条目无效"),
                    None => {}
                }
            }
            Ok(())
        })();
        if let Err(error) = result {
            tracing::warn!(error = %format!("{error:#}"), "读取Mod资料缓存失败");
        }
        found
    }

    /// Stores (or refreshes) projects; a failure only loses the cache, so it is logged.
    pub fn put_many<'a>(&self, projects: impl IntoIterator<Item = &'a Project>, now: SystemTime) {
        let mut db = self.db.lock().unwrap_or_else(|e| e.into_inner());
        let result = (|| -> Result<()> {
            let tx = db.transaction()?;
            {
                let mut insert = tx.prepare_cached("INSERT OR REPLACE INTO projects(platform, id, stored, json) VALUES(?1, ?2, ?3, ?4)")?;
                for p in projects {
                    insert.execute(params![p.reference.platform.db(), p.reference.id, unix(now), serde_json::to_string(p)?])?;
                }
            }
            tx.commit()?;
            Ok(())
        })();
        if let Err(error) = result {
            tracing::warn!(error = %format!("{error:#}"), "写入Mod资料缓存失败");
        }
    }
}

/// 清理缓存: empties the table (the file may be open in other catalog instances, so it is not
/// deleted); returns how many projects were removed.
pub fn clear(path: &Path) -> Result<usize> {
    if !path.exists() {
        return Ok(0);
    }
    Ok(open_db(path)?.execute("DELETE FROM projects", [])?)
}
