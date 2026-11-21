use super::models::*;
use anyhow::{Result, ensure};
use rusqlite::{Connection, OpenFlags};
use std::{path::Path, sync::Mutex};
pub struct IdentityIndex {
    db: Option<Mutex<Connection>>,
    pub unavailable: Option<String>,
}
impl IdentityIndex {
    pub fn open(path: &Path) -> Self {
        match (|| -> Result<Connection> {
            let db = Connection::open_with_flags(
                path,
                OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
            )?;
            let v: i64 = db.query_row(
                "SELECT format_version FROM catalog_metadata WHERE id=1",
                [],
                |r| r.get(0),
            )?;
            ensure!(v == 1, "Unsupported mod catalog format {v}");
            let expected: (i64,i64,i64) = db.query_row("SELECT mod_count, project_count, search_term_count FROM catalog_metadata WHERE id=1", [], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?;
            let actual: (i64,i64,i64) = db.query_row("SELECT (SELECT count(*) FROM catalog_mod), (SELECT count(*) FROM catalog_project), (SELECT count(*) FROM catalog_search_term)", [], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?;
            ensure!(
                expected == actual,
                "Catalog metadata counts do not match the database"
            );
            Ok(db)
        })() {
            Ok(db) => Self {
                db: Some(Mutex::new(db)),
                unavailable: None,
            },
            Err(error) => {
                tracing::warn!(%error,"本地Mod中文目录不可用");
                Self {
                    db: None,
                    unavailable: Some(error.to_string()),
                }
            }
        }
    }
    fn records(&self, sql: &str, params: impl rusqlite::Params) -> Result<Vec<Metadata>> {
        let Some(db) = &self.db else {
            return Ok(vec![]);
        };
        let db = db.lock().unwrap();
        let mut statement = db.prepare(sql)?;
        let rows = statement.query_map(params, |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, Option<String>>(3)?,
                r.get::<_, Option<String>>(4)?,
            ))
        })?;
        let mut records = vec![];
        for row in rows {
            let (id, name, name_cn, intro, icon) = row?;
            let mut projects=db.prepare("SELECT platform,slug,name_cn_override FROM catalog_project WHERE mcmod_id=?1 ORDER BY project_order,platform")?;
            let projects = projects
                .query_map([id], |r| {
                    let p: String = r.get(0)?;
                    Ok(IdentityProject {
                        platform: if p == "CURSEFORGE" {
                            Platform::CurseForge
                        } else {
                            Platform::Modrinth
                        },
                        slug: r.get(1)?,
                        name_cn: r.get(2)?,
                    })
                })?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            records.push(Metadata {
                mcmod_id: id,
                name,
                name_cn,
                intro,
                icon,
                projects,
            });
        }
        Ok(records)
    }
    pub fn find(&self, p: Platform, slug: &str) -> Result<Option<Metadata>> {
        Ok(self.records("SELECT m.mcmod_id,m.name,m.name_cn,m.intro,m.logo_url FROM catalog_mod m JOIN catalog_project p ON p.mcmod_id=m.mcmod_id WHERE p.platform=?1 AND p.normalized_slug=?2",rusqlite::params![p.db(),slug.trim().to_lowercase()])?.into_iter().next())
    }
    pub fn search(&self, query: &str, offset: usize, limit: usize) -> Result<Vec<Metadata>> {
        let query = query
            .to_lowercase()
            .chars()
            .filter(|c| c.is_alphanumeric())
            .collect::<String>();
        if query.is_empty() {
            return Ok(vec![]);
        }
        self.records("SELECT m.mcmod_id,m.name,m.name_cn,m.intro,m.logo_url FROM catalog_mod m JOIN catalog_search_term t ON t.mcmod_id=m.mcmod_id WHERE t.normalized_term=?1 OR t.normalized_term LIKE ?2 GROUP BY m.mcmod_id ORDER BY max(CASE WHEN t.normalized_term=?1 THEN 1000+t.weight ELSE t.weight END) DESC,m.popularity DESC,m.mcmod_id LIMIT ?3 OFFSET ?4",rusqlite::params![query,format!("{query}%"),limit as i64,offset as i64])
    }
}
