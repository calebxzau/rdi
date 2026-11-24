//! The first look at a pack to upload: Kotlin `preflightPackStructure`,
//! `inspectCurseForgeUploadPayload`, `inspectModrinthUploadPayload` (with
//! `ModrinthService.parseIndex`) and `requireModpackUploadRuntime`. Reads only the manifest;
//! the full read (mods, extras) comes with stage A2.
use anyhow::{Context, Result, bail, ensure};
use serde_json::Value;
use std::io::Read;
use std::path::Path;

const MAX_MANIFEST: u64 = 4 * 1024 * 1024;
const MANIFEST_CF: &str = "manifest.json";
const MANIFEST_MR: &str = "modrinth.index.json";

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Kind {
    CurseForge,
    Modrinth,
}

impl Kind {
    pub fn name(self) -> &'static str {
        match self {
            Kind::CurseForge => "curseforge",
            Kind::Modrinth => "modrinth",
        }
    }
}

/// What the manifest says.
#[derive(Clone, Debug, PartialEq)]
pub struct Summary {
    pub kind: Kind,
    pub name: String,
    /// The manifest's version, or `1.0` when it has none.
    pub version: String,
    /// `McVersion` name: `V201` or `V211`.
    pub mc_version: String,
    /// `ModLoader` name: `forge`, `neoforge` or `Fabric`.
    pub loader: String,
}

fn file_name(path: &str) -> &str {
    let path = path.trim_start_matches(['/', '\\']);
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}

/// `detectPackType`: a Modrinth index anywhere wins over a CurseForge manifest.
fn detect(names: &[String]) -> Option<Kind> {
    let has = |wanted: &str| names.iter().any(|n| file_name(n) == wanted);
    if has(MANIFEST_MR) {
        Some(Kind::Modrinth)
    } else if has(MANIFEST_CF) {
        Some(Kind::CurseForge)
    } else {
        None
    }
}

/// `hasOverridesDir(entryNames, packType)`: an `overrides` folder beside the first manifest.
fn has_overrides(names: &[String], kind: Kind) -> bool {
    let meta = match kind {
        Kind::Modrinth => MANIFEST_MR,
        Kind::CurseForge => MANIFEST_CF,
    };
    let Some(path) = names.iter().find(|n| file_name(n) == meta) else {
        return false;
    };
    let path = path.replace('\\', "/");
    let path = path.trim_start_matches('/');
    let prefix = match path.rsplit_once('/') {
        Some((dir, _)) if !dir.trim().is_empty() => format!("{dir}/"),
        _ => String::new(),
    };
    let overrides = format!("{prefix}overrides");
    names.iter().any(|raw| {
        let name = raw.replace('\\', "/");
        let name = name.trim_start_matches('/');
        name == overrides || name.starts_with(&format!("{overrides}/"))
    })
}

/// Entry names of a zip, or relative paths under a folder (directories end without `/`).
fn list(path: &Path) -> Result<Vec<String>> {
    if path.is_dir() {
        let mut names = Vec::new();
        let mut stack = vec![path.to_path_buf()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir).with_context(|| format!("读取{}失败", dir.display()))? {
                let entry = entry?;
                let kind = entry.file_type()?;
                if kind.is_symlink() {
                    continue;
                }
                let relative = entry.path().strip_prefix(path).unwrap_or(&entry.path()).to_string_lossy().replace('\\', "/");
                if kind.is_dir() {
                    stack.push(entry.path());
                }
                names.push(relative);
            }
        }
        names.sort();
        return Ok(names);
    }
    let file = std::fs::File::open(path)?;
    let mut zip = zip::ZipArchive::new(file).context("整合包不是有效的ZIP文件")?;
    let mut names = Vec::with_capacity(zip.len());
    for index in 0..zip.len() {
        let entry = zip.by_index_raw(index)?;
        names.push(crate::import::archive::decode_name(entry.name_raw()));
    }
    Ok(names)
}

/// The text of the first entry named `wanted`, as `findFile` takes the first match.
fn read_meta(path: &Path, names: &[String], wanted: &str) -> Result<String> {
    let entry = names.iter().find(|n| file_name(n) == wanted).with_context(|| format!("整合包缺少文件：{wanted}"))?;
    let mut bytes = Vec::new();
    if path.is_dir() {
        let file = std::fs::File::open(path.join(entry))?;
        file.take(MAX_MANIFEST + 1).read_to_end(&mut bytes)?;
    } else {
        let mut zip = zip::ZipArchive::new(std::fs::File::open(path)?)?;
        let index = (0..zip.len())
            .find(|i| zip.by_index_raw(*i).is_ok_and(|e| crate::import::archive::decode_name(e.name_raw()) == *entry))
            .with_context(|| format!("整合包缺少文件：{wanted}"))?;
        zip.by_index(index)?.take(MAX_MANIFEST + 1).read_to_end(&mut bytes)?;
    }
    ensure!(bytes.len() as u64 <= MAX_MANIFEST, "整合包清单过大");
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

/// `McVersion.from` for the versions this launcher knows.
fn mc_version(text: &str) -> Option<&'static str> {
    match text {
        "1.21.1" => Some("V211"),
        "1.20.1" => Some("V201"),
        "1.7.10" => Some("V071"),
        _ => None,
    }
}

/// `requireModpackUploadVersion`.
fn require_upload_version(version: &str) -> Result<()> {
    ensure!(version == "V201" || version == "V211", "目前只支持上传MC1.20.1和MC1.21.1整合包");
    Ok(())
}

fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or_default()
}

/// Reads the manifest of the pack at `path` (a zip or a folder) and checks it can be uploaded.
pub fn summary(path: &Path) -> Result<Summary> {
    ensure!(path.exists(), "找不到整合包文件: {}", path.display());
    let names = list(path).map_err(|error| anyhow::anyhow!("读取整合包文件失败: {error:#}"))?;
    let Some(kind) = detect(&names) else {
        bail!("找不到此包的概要文件(manifest.json/modrinth.index.json)");
    };
    let overrides = if path.is_dir() {
        // `hasOverridesDir(rootDir)`: any folder named overrides.
        names.iter().any(|n| file_name(n).eq_ignore_ascii_case("overrides") && path.join(n).is_dir())
    } else {
        has_overrides(&names, kind)
    };
    ensure!(kind != Kind::CurseForge || overrides, "找不到包中overrides目录");
    let summary = match kind {
        Kind::CurseForge => {
            let raw = read_meta(path, &names, MANIFEST_CF)?;
            let manifest: Value = serde_json::from_str(&raw).map_err(|error| anyhow::anyhow!("manifest.json 解析失败: {error}"))?;
            let mc = text(&manifest["minecraft"], "version");
            let version = mc_version(mc).with_context(|| format!("暂不支持MC版本{mc}"))?;
            require_upload_version(version)?;
            let loader = crate::import::archive::recognize_loader(&manifest, true)?;
            Summary {
                kind,
                name: text(&manifest, "name").to_owned(),
                version: Some(text(&manifest, "version")).filter(|v| !v.trim().is_empty()).unwrap_or("1.0").to_owned(),
                mc_version: version.to_owned(),
                loader: loader.to_owned(),
            }
        }
        Kind::Modrinth => {
            let raw = read_meta(path, &names, MANIFEST_MR)?;
            let index: Value = serde_json::from_str(&raw).map_err(|_| anyhow::anyhow!("modrinth概要文件解析失败"))?;
            let game = text(&index, "game");
            ensure!(game.eq_ignore_ascii_case("minecraft"), "不支持的游戏类型: {game}");
            let format = index["formatVersion"].as_i64().unwrap_or(0);
            ensure!(format > 0, "不支持的整合包格式版本: {format}");
            let mc = text(&index["dependencies"], "minecraft").trim();
            ensure!(!mc.is_empty(), "整合包缺少MC版本");
            let version = mc_version(mc).with_context(|| format!("不支持的MC版本: {mc}"))?;
            let loader = crate::import::archive::recognize_loader(&index, false)?;
            require_upload_version(version)?;
            Summary {
                kind,
                name: text(&index, "name").to_owned(),
                version: Some(text(&index, "versionId")).filter(|v| !v.trim().is_empty()).unwrap_or("1.0").to_owned(),
                mc_version: version.to_owned(),
                loader: loader.to_owned(),
            }
        }
    };
    ensure!(
        crate::import::scanner::supported(&summary.mc_version, &summary.loader),
        "已识别为Minecraft{}／{}，当前版本暂不支持导入",
        crate::import::scanner::version_label(&summary.mc_version),
        summary.loader
    );
    Ok(summary)
}

#[cfg(test)]
pub(super) fn detect_for_test(names: &[&str]) -> (Option<Kind>, bool) {
    let names: Vec<String> = names.iter().map(|n| (*n).to_owned()).collect();
    let kind = detect(&names);
    (kind, kind.is_some_and(|k| has_overrides(&names, k)))
}
