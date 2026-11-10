use anyhow::{Context, Result, bail, ensure};
use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Candidate {
    pub name: String,
    pub directory: PathBuf,
    pub game_directory: PathBuf,
    pub mc_version: String,
    pub mod_loader: String,
    #[serde(skip)]
    pub version_directory: PathBuf,
}
pub const RUNTIMES: [(&str, &str, &str); 3] = [
    ("V211", "neoforge", "1.21.1 NeoForge"),
    ("V201", "forge", "1.20.1 Forge"),
    ("V201", "Fabric", "1.20.1 Fabric"),
];
pub fn supported(version: &str, loader: &str) -> bool {
    RUNTIMES
        .iter()
        .any(|(v, l, _)| *v == version && *l == loader)
}
pub fn version_entry(version: &str) -> Result<&'static str> {
    match version {
        "1.21.1" => Ok("V211"),
        "1.20.1" => Ok("V201"),
        "1.7.10" => bail!("不是现代MC版本{version}"),
        _ => bail!("不支持MC版本{version}"),
    }
}
pub fn version_label(version: &str) -> &str {
    match version {
        "V211" => "1.21.1",
        "V201" => "1.20.1",
        v => v,
    }
}
pub fn detect(main: Option<&str>, libraries: &[String], version: &str) -> Result<String> {
    let mut loaders = Vec::new();
    for l in libraries {
        let loader = if l.starts_with("net.fabricmc:fabric-loader:") {
            "Fabric"
        } else if l.starts_with("net.minecraftforge:forge:") {
            "forge"
        } else if l.starts_with("net.neoforged:neoforge:") {
            "neoforge"
        } else if l.starts_with("org.quiltmc:quilt-loader:") {
            bail!("不支持Quilt启动器")
        } else {
            continue;
        };
        if !loaders.contains(&loader) {
            loaders.push(loader);
        }
    }
    ensure!(loaders.len() <= 1, "版本清单包含冲突的Mod加载器运行库");
    let library = loaders.first().copied();
    let entry = match main {
        Some(
            "net.fabricmc.loader.impl.launch.knot.KnotClient"
            | "net.fabricmc.loader.launch.knot.KnotClient",
        ) => Some("Fabric"),
        Some("cpw.mods.bootstraplauncher.BootstrapLauncher") => Some(
            library
                .filter(|l| *l == "forge" || *l == "neoforge")
                .unwrap_or(if version == "V211" {
                    "neoforge"
                } else {
                    "forge"
                }),
        ),
        Some("net.minecraft.launchwrapper.Launch") => Some("forge"),
        _ => None,
    };
    if entry.is_none() && library == Some("Fabric") {
        bail!(
            "Fabric运行库存在，但启动类{}不受支持",
            main.filter(|s| !s.trim().is_empty()).unwrap_or("缺失")
        );
    }
    if let (Some(l), Some(e)) = (library, entry) {
        ensure!(l == e, "版本清单的启动类与Mod加载器运行库冲突");
    }
    let loader = entry.with_context(|| format!("不支持启动类{}", main.unwrap_or_default()))?;
    ensure!(
        supported(version, loader),
        "不支持{}/{loader}",
        version_label(version)
    );
    Ok(loader.into())
}
fn has_jar(dir: &Path) -> Result<bool> {
    if !dir.is_dir() {
        return Ok(false);
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        if entry.path().is_file()
            && entry
                .file_name()
                .to_string_lossy()
                .to_lowercase()
                .ends_with(".jar")
        {
            return Ok(true);
        }
    }
    Ok(false)
}
pub fn candidate(directory: &Path) -> Result<Candidate> {
    let name = directory
        .file_name()
        .context("缺少版本目录名称")?
        .to_string_lossy()
        .into_owned();
    let manifest = directory.join(format!("{name}.json"));
    ensure!(manifest.is_file(), "缺少版本清单");
    let installation = directory
        .parent()
        .and_then(Path::parent)
        .unwrap_or(directory);
    let game = if has_jar(&directory.join("mods"))? {
        directory
    } else if has_jar(&installation.join("mods"))? {
        installation
    } else {
        bail!("没有Mod文件")
    };
    let m: Value = serde_json::from_str(&std::fs::read_to_string(manifest)?)?;
    let version = m["clientVersion"]
        .as_str()
        .or_else(|| m["inheritsFrom"].as_str())
        .or_else(|| m["id"].as_str())
        .context("缺少MC版本")?;
    let version = version_entry(version)?;
    let libraries = m["libraries"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|v| v["name"].as_str().map(str::to_owned))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let loader = detect(m["mainClass"].as_str(), &libraries, version)?;
    Ok(Candidate {
        name,
        directory: installation.into(),
        game_directory: game.into(),
        version_directory: directory.into(),
        mc_version: version_label(version).into(),
        mod_loader: loader,
    })
}
fn installation(root: &Path) -> Vec<Candidate> {
    let mut out = vec![];
    let versions = root.join("versions");
    if !versions.is_dir() {
        return out;
    }
    let result = (|| -> Result<()> {
        for entry in std::fs::read_dir(&versions)? {
            let path = entry?.path();
            if path.is_dir() {
                match candidate(&path) {
                    Ok(c) => out.push(c),
                    Err(error) => tracing::info!(dir=%path.display(),%error,"忽略外部整合包"),
                }
            }
        }
        Ok(())
    })();
    if let Err(error) = result {
        tracing::warn!(dir=%versions.display(),%error,"扫描外部整合包失败");
    }
    out
}
pub fn directory(path: &Path) -> Result<Vec<Candidate>> {
    let path = std::path::absolute(path)?;
    ensure!(path.is_dir(), "找不到文件夹：{}", path.display());
    if path.join("versions").is_dir() {
        Ok(installation(&path))
    } else if path
        .file_name()
        .is_some_and(|n| path.join(format!("{}.json", n.to_string_lossy())).is_file())
    {
        match candidate(&path) {
            Ok(c) => Ok(vec![c]),
            Err(error) => {
                tracing::info!(%error,dir=%path.display(),"忽略外部整合包");
                Ok(vec![])
            }
        }
    } else {
        Ok(vec![])
    }
}
pub fn scan(db: &Path, managed: &Path) -> Vec<Candidate> {
    let result = (|| -> Result<Vec<Candidate>> {
        let conn = Connection::open_with_flags(
            db,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        let app: i64 = conn.pragma_query_value(None, "application_id", |r| r.get(0))?;
        let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        ensure!(app == 0x524449 && version == 5, "启动器数据版本不兼容");
        let mut statement =
            conn.prepare("SELECT path FROM minecraft_installation ORDER BY path")?;
        let paths = statement
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let managed = path_key(managed);
        let mut seen = std::collections::HashSet::new();
        let mut all = vec![];
        for path in paths {
            let path = std::path::absolute(path)?;
            if path_key(&path) != managed {
                for c in installation(&path) {
                    if seen.insert(path_key(&c.version_directory)) {
                        all.push(c);
                    }
                }
            }
        }
        all.sort_by_key(|c| {
            (
                c.directory.to_string_lossy().to_lowercase(),
                c.name.to_lowercase(),
            )
        });
        Ok(all)
    })();
    result.unwrap_or_else(|error| {
        tracing::warn!(%error,"无法读取已记录的MC文件夹，跳过扫描");
        vec![]
    })
}
fn path_key(path: &Path) -> String {
    std::path::absolute(path)
        .unwrap_or_else(|_| path.into())
        .components()
        .collect::<PathBuf>()
        .to_string_lossy()
        .to_lowercase()
}
