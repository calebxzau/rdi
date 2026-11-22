//! CurseForge/Modrinth preview and bounded extraction. No path is trusted from ZIP or catalog.
use crate::{
    catalog::{Catalog, ContentType, File, FileHashes, FileRef, Platform},
    content_store,
    digests::{Algorithm, Digest},
    download,
    tasks::Cancellation,
};
use anyhow::{Context, Result, ensure};
use serde_json::{Value, json};
use std::{
    collections::BTreeSet,
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Arc,
    time::SystemTime,
};
const MAX_BYTES: u64 = 20 * 1024 * 1024 * 1024;
const MAX_MANIFEST: u64 = 4 * 1024 * 1024;
#[derive(Clone)]
pub struct ArchiveFile {
    pub key: String,
    pub target: String,
    pub name: String,
    pub summary: String,
    pub icon: String,
    pub size: u64,
    pub sha1: String,
    pub sources: Vec<download::Source>,
    pub required: bool,
    pub kind: String,
}
#[derive(Clone)]
pub struct Override {
    pub index: usize,
    pub entry: String,
    pub target: String,
    pub size: u64,
    pub layer: u8,
}
#[derive(Clone)]
pub struct Preview {
    pub path: PathBuf,
    pub size: u64,
    pub modified: SystemTime,
    pub name: String,
    pub summary: String,
    pub version: String,
    pub loader: String,
    pub format: String,
    pub files: Vec<ArchiveFile>,
    pub overrides: Vec<Override>,
    pub unavailable: Vec<String>,
}
#[derive(Clone)]
pub struct Selection {
    pub preview: Arc<Preview>,
    pub optional: BTreeSet<String>,
    pub store: content_store::Store,
    pub runtime: tokio::runtime::Handle,
}
struct Entry {
    index: usize,
    name: String,
    size: u64,
    directory: bool,
}
pub(crate) fn decode_name(raw: &[u8]) -> String {
    match std::str::from_utf8(raw) {
        Ok(s) => s.into(),
        Err(_) => encoding_rs::GBK.decode(raw).0.into_owned(),
    }
}
pub fn safe_target(raw: &str) -> Result<String> {
    let normalized = raw.replace('\\', "/");
    let normalized = normalized.trim_start();
    ensure!(!normalized.is_empty(), "整合包文件路径为空");
    ensure!(
        !normalized.starts_with('/') && !normalized.as_bytes().get(1).is_some_and(|b| *b == b':'),
        "整合包路径越界：{raw}"
    );
    let parts: Vec<_> = normalized.split('/').collect();
    for segment in &parts {
        let base = segment
            .split('.')
            .next()
            .unwrap_or_default()
            .to_ascii_uppercase();
        let reserved = ["CON", "PRN", "AUX", "NUL"].contains(&base.as_str())
            || (base.len() == 4
                && (base.starts_with("COM") || base.starts_with("LPT"))
                && matches!(base.as_bytes()[3], b'1'..=b'9'));
        ensure!(
            !segment.is_empty()
                && *segment != "."
                && *segment != ".."
                && !segment.ends_with('.')
                && !segment.ends_with(' ')
                && !segment.contains(':')
                && !reserved
                && !segment.contains('\0'),
            "整合包路径无效：{raw}"
        );
    }
    Ok(parts.join("/"))
}
fn safe_filename(raw: &str) -> Result<String> {
    ensure!(
        !raw.contains(['\\', '/']) && !raw.trim().is_empty(),
        "整合包文件名无效：{raw}"
    );
    safe_target(raw)
}
pub fn encode_url(value: &str) -> String {
    let b = value.as_bytes();
    let mut out = String::new();
    for (i, &v) in b.iter().enumerate() {
        let escape = v == b'%'
            && b.get(i + 1).is_some_and(u8::is_ascii_hexdigit)
            && b.get(i + 2).is_some_and(u8::is_ascii_hexdigit);
        if v.is_ascii_alphanumeric() || b"-._~:/?#@!$&'()*+,;=".contains(&v) || escape {
            out.push(v as char);
        } else {
            out.push_str(&format!("%{v:02X}"));
        }
    }
    out
}
pub fn valid_url(raw: &str) -> Result<String> {
    let encoded = encode_url(raw.trim());
    // Kotlin URI validates authority before URL normalization; do not accept backslash hosts.
    ensure!(!raw.contains('\\'), "无效下载地址：{raw}");
    let url = reqwest::Url::parse(&encoded).map_err(|_| anyhow::anyhow!("无效下载地址：{raw}"))?;
    ensure!(
        url.scheme() == "https"
            && [
                "cdn.modrinth.com",
                "github.com",
                "raw.githubusercontent.com",
                "gitlab.com",
                "mediafilez.forgecdn.net",
                "edge.forgecdn.net",
                "media.forgecdn.net",
                "mod.mcimirror.top"
            ]
            .contains(&url.host_str().unwrap_or_default()),
        "不允许的下载地址：{raw}"
    );
    Ok(encoded)
}
fn text<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap_or_default()
}
fn arr(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or_default()
}
fn read_manifest(zip: &mut zip::ZipArchive<std::fs::File>, index: usize) -> Result<Value> {
    let f = zip.by_index(index)?;
    ensure!(f.size() <= MAX_MANIFEST, "整合包清单过大");
    let mut bytes = vec![];
    f.take(MAX_MANIFEST + 1).read_to_end(&mut bytes)?;
    ensure!(bytes.len() as u64 <= MAX_MANIFEST, "整合包清单过大");
    Ok(serde_json::from_slice(&bytes)?)
}
fn runtime(v: &Value, cf: bool) -> Result<(String, String)> {
    let mc = if cf {
        text(&v["minecraft"], "version")
    } else {
        text(&v["dependencies"], "minecraft")
    };
    let version = super::scanner::version_entry(mc)
        .map_err(|_| anyhow::anyhow!("暂不支持MC版本{}", mc.trim()))?
        .to_owned();
    let loader = if cf {
        let declarations = arr(&v["minecraft"]["modLoaders"]);
        let primary: Vec<_> = declarations
            .iter()
            .filter(|d| d["primary"] == true)
            .collect();
        ensure!(primary.len() <= 1, "CurseForge清单声明了多个主Mod加载器");
        let selected = if primary.len() == 1 {
            primary[0]
        } else {
            ensure!(!declarations.is_empty(), "CurseForge清单缺少Mod加载器声明");
            ensure!(
                declarations.len() == 1,
                "CurseForge清单包含多个Mod加载器且未指定唯一主加载器"
            );
            &declarations[0]
        };
        let id = text(selected, "id").trim();
        let (name, version) = id
            .split_once('-')
            .filter(|(a, b)| !a.is_empty() && !b.is_empty())
            .with_context(|| format!("无法识别CurseForge Mod加载器声明: {id}"))?;
        let declared = version.trim();
        ensure!(!declared.is_empty(), "CurseForge Mod加载器版本为空: {id}");
        match name.to_lowercase().as_str() {
            "forge" => "forge",
            "neoforge" => "neoforge",
            "fabric" => {
                let numeric = declared.split(['-', '+']).next().unwrap_or_default();
                ensure!(
                    numeric.contains('.')
                        && numeric
                            .split('.')
                            .all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_digit())),
                    "Fabric加载器版本格式无效: {declared}"
                );
                "Fabric"
            }
            _ => anyhow::bail!("暂不支持CurseForge Mod加载器: {}", name.to_lowercase()),
        }
    } else {
        let d = &v["dependencies"];
        let declared: Vec<_> = ["forge", "neoforge", "fabric-loader", "quilt-loader"]
            .into_iter()
            .filter(|k| d[*k].is_string())
            .collect();
        ensure!(
            declared.len() <= 1,
            "整合包声明了多个Mod加载器: {}",
            declared.join(", ")
        );
        let key = declared
            .first()
            .context("整合包缺少受支持的Mod加载器声明")?;
        ensure!(*key != "quilt-loader", "暂不支持Mod加载器: {key}");
        ensure!(
            !text(d, key).trim().is_empty(),
            "整合包缺少Mod加载器版本: {key}"
        );
        match *key {
            "forge" => "forge",
            "neoforge" => "neoforge",
            _ => "Fabric",
        }
    };
    ensure!(
        super::scanner::supported(&version, loader),
        "已识别为Minecraft{mc}／{loader}，当前版本暂不支持导入"
    );
    Ok((version, loader.into()))
}
fn kind(path: &str) -> String {
    let p = path.to_lowercase();
    if p.starts_with("mods/") {
        "Mod"
    } else if p.starts_with("resourcepacks/") {
        "资源包"
    } else if p.starts_with("shaderpacks/") {
        "光影"
    } else {
        "其他文件"
    }
    .into()
}
fn catalog_target(
    file: &File,
    kind: &str,
    version: &str,
    loader: &str,
    identity: &str,
) -> Result<()> {
    let mc = super::scanner::version_label(version);
    if kind == "Mod" || !file.minecraft.is_empty() {
        ensure!(
            file.minecraft.iter().any(|v| v == mc),
            "{identity}不支持整合包Minecraft版本{mc}"
        );
    }
    if kind == "Mod" || !file.loaders.is_empty() {
        ensure!(
            file.loaders.iter().any(|v| v == loader),
            "{identity}不支持整合包Mod加载器{loader}"
        );
    }
    Ok(())
}
impl Preview {
    pub fn unchanged(&self) -> Result<()> {
        let meta = std::fs::metadata(&self.path)?;
        ensure!(
            meta.is_file() && meta.len() == self.size && meta.modified()? == self.modified,
            "整合包安装包在确认后发生了变化，请重新选择"
        );
        Ok(())
    }
    pub fn contents(&self, selected: &BTreeSet<String>) -> Vec<Value> {
        let mut items:Vec<_>=self.files.iter().map(|f|json!({"key":f.key,"name":f.name,"detail":f.summary,"icon":f.icon,"size":f.size,"kind":f.kind,"optional":!f.required,"selected":f.required||selected.contains(&f.key)})).collect();
        for o in &self.overrides {
            if o.target.to_lowercase().starts_with("mods/")
                && o.target.to_lowercase().ends_with(".jar")
            {
                items.push(json!({"key":format!("zip:{}",o.entry),"name":o.target.rsplit('/').next().unwrap_or_default(),"detail":"内嵌Mod","icon":"","size":o.size,"kind":"Mod","optional":false,"selected":true}));
            }
        }
        items
    }
}
pub async fn inspect(
    path: &Path,
    catalog: &Catalog,
    cancel: &Cancellation,
    progress: &(dyn Fn(&str, Option<f32>) + Sync),
) -> Result<Preview> {
    let meta =
        std::fs::metadata(path).with_context(|| format!("找不到整合包文件：{}", path.display()))?;
    ensure!(meta.is_file(), "找不到整合包文件：{}", path.display());
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path)?)?;
    ensure!(zip.len() <= 100000, "压缩包条目超过100000个");
    let mut entries = vec![];
    let mut total = 0u64;
    progress("扫描压缩包条目", None);
    let total_entries = zip.len();
    for index in 0..zip.len() {
        cancel.check()?;
        let f = zip.by_index(index)?;
        let name = decode_name(f.name_raw());
        progress(
            "扫描压缩包条目",
            Some((index + 1) as f32 / total_entries.max(1) as f32),
        );
        let directory = f.is_dir();
        if !directory {
            total = total
                .checked_add(f.size())
                .context("压缩包解压后超过20GB")?;
        }
        ensure!(total <= MAX_BYTES, "压缩包解压后超过20GB");
        entries.push(Entry {
            index,
            name,
            size: f.size(),
            directory,
        });
    }
    let manifests: Vec<_> = entries
        .iter()
        .filter(|e| {
            !e.directory && ["manifest.json", "modrinth.index.json"].contains(&e.name.as_str())
        })
        .collect();
    ensure!(!manifests.is_empty(), "压缩包根目录缺少整合包清单");
    ensure!(manifests.len() == 1, "压缩包根目录存在多个整合包清单");
    let cf = manifests[0].name == "manifest.json";
    let v = read_manifest(&mut zip, manifests[0].index)?;
    let (version, loader) = runtime(&v, cf)?;
    if cf {
        ensure!(
            v["overrides"].is_null() || v["overrides"] == "overrides",
            "CurseForge整合包只支持根目录overrides"
        );
    } else {
        ensure!(
            v["formatVersion"].as_u64().is_some_and(|n| n > 0),
            "不支持的Modrinth整合包格式版本：{}",
            v["formatVersion"]
        );
        ensure!(
            text(&v, "game").eq_ignore_ascii_case("minecraft"),
            "不支持的游戏类型：{}",
            text(&v, "game")
        );
    }
    progress("解析整合包内容", None);
    let mut files = vec![];
    let mut unavailable = vec![];
    if cf {
        let refs: Vec<_> = arr(&v["files"])
            .iter()
            .map(|f| FileRef {
                platform: Platform::CurseForge,
                id: f["fileID"].as_u64().unwrap_or(0).to_string(),
            })
            .collect();
        let resolved = catalog.get_files(&refs).await?.value;
        let project_refs: Vec<_> = resolved.values().map(|f| f.project.clone()).collect();
        let projects = catalog.get_mods(&project_refs).await?.value;
        for item in arr(&v["files"]) {
            cancel.check()?;
            let pid = item["projectID"].as_u64().unwrap_or(0).to_string();
            let fid = item["fileID"].as_u64().unwrap_or(0).to_string();
            let key = format!("cf:{pid}:{fid}");
            let r = FileRef {
                platform: Platform::CurseForge,
                id: fid.clone(),
            };
            let Some(file) = resolved.get(&r) else {
                unavailable.push(format!("CurseForge项目{pid}／文件{fid}"));
                continue;
            };
            ensure!(
                file.project.id == pid,
                "CurseForge项目与文件不匹配：{pid}/{fid}"
            );
            let project = projects.get(&file.project);
            let name = project
                .and_then(|m| m.name_cn.clone())
                .or_else(|| project.map(|m| m.name.clone()))
                .unwrap_or_else(|| file.name.clone());
            let source = match catalog.resolve_download(file) {
                Ok(source) => source,
                Err(error) => {
                    tracing::warn!(%error,"CurseForge文件无法自动下载");
                    unavailable.push(if item["required"].as_bool().unwrap_or(true) {
                        name
                    } else {
                        format!("{name}（可选）")
                    });
                    continue;
                }
            };
            let ty = project
                .and_then(|m| m.sources.iter().find(|s| s.reference == file.project))
                .map(|s| s.kind)
                .unwrap_or(ContentType::Other);
            let dir = match ty {
                ContentType::Mod => "mods",
                ContentType::ResourcePack => "resourcepacks",
                ContentType::ShaderPack => "shaderpacks",
                _ => anyhow::bail!("不支持的CurseForge项目类型：{pid}"),
            };
            let target = safe_target(&format!("{dir}/{}", safe_filename(&file.filename)?))?;
            let kind = kind(&target);
            catalog_target(file, &kind, &version, &loader, &format!("{pid}/{fid}"))?;
            ensure!(file.size > 0, "整合包文件大小无效：{fid}");
            ensure!(
                file.digests
                    .iter()
                    .any(|d| d.algorithm == Algorithm::Murmur2),
                "整合包文件缺少Murmur2：{fid}"
            );
            let sha1 = file
                .digests
                .iter()
                .find(|d| d.algorithm == Algorithm::Sha1)
                .context("整合包文件校验信息不完整")?
                .value
                .clone();
            let mut source = source;
            source.url = valid_url(&source.url)?;
            files.push(ArchiveFile {
                key,
                target,
                name,
                summary: project.map(|m| m.summary.clone()).unwrap_or_default(),
                icon: project
                    .and_then(|m| m.icons.first().cloned())
                    .unwrap_or_default(),
                size: file.size,
                sha1,
                sources: vec![source],
                required: item["required"].as_bool().unwrap_or(true),
                kind,
            });
        }
    } else {
        let supported: Vec<_> = arr(&v["files"])
            .iter()
            .filter(|f| f["env"]["client"] != "unsupported")
            .collect();
        let mut hashes = vec![];
        for f in &supported {
            let target = safe_target(text(f, "path"))?;
            let sha1 = Digest::new(Algorithm::Sha1, text(&f["hashes"], "sha1"))?.value;
            hashes.push(FileHashes {
                key: target,
                sha1,
                murmur2: None,
            });
        }
        let resolved = catalog.match_files(&hashes).await?;
        let projects = catalog
            .get_mods(
                &resolved
                    .values()
                    .map(|f| f.project.clone())
                    .collect::<Vec<_>>(),
            )
            .await?
            .value;
        for f in supported {
            cancel.check()?;
            let target = safe_target(text(f, "path"))?;
            let size = f["fileSize"]
                .as_u64()
                .with_context(|| format!("Modrinth文件大小无效：{target}"))?;
            let raw = arr(&f["downloads"]);
            ensure!(!raw.is_empty(), "Modrinth文件缺少下载地址：{target}");
            let mut sources = vec![];
            for raw in raw {
                if let Some(raw) = raw.as_str() {
                    match valid_url(raw) {
                        Ok(url) => sources.push(download::Source {
                            url,
                            ..Default::default()
                        }),
                        Err(_) => tracing::warn!("跳过无效下载地址"),
                    }
                }
            }
            ensure!(
                !sources.is_empty(),
                "Modrinth文件缺少有效下载地址：{target}"
            );
            let sha1 = Digest::new(Algorithm::Sha1, text(&f["hashes"], "sha1"))?.value;
            let mut name = target.rsplit('/').next().unwrap_or_default().to_owned();
            let mut summary = String::new();
            let mut icon = String::new();
            let kind = kind(&target);
            if let Some(file) = resolved.get(&target) {
                ensure!(
                    file.digests
                        .iter()
                        .any(|d| d.algorithm == Algorithm::Sha1 && d.value == sha1),
                    "Modrinth文件SHA-1不匹配：{target}"
                );
                ensure!(file.filename == name, "Modrinth文件名不匹配：{target}");
                ensure!(
                    file.size > 0 && file.size == size,
                    "Modrinth文件大小不匹配：{target}"
                );
                catalog_target(file, &kind, &version, &loader, &target)?;
                let project = projects
                    .get(&file.project)
                    .context("找不到Modrinth项目资料")?;
                name = project
                    .name_cn
                    .clone()
                    .unwrap_or_else(|| project.name.clone());
                summary = project.summary.clone();
                icon = project.icons.first().cloned().unwrap_or_default();
                let source = catalog.resolve_download(file)?;
                valid_url(&source.url)?;
                sources = vec![source];
            }
            files.push(ArchiveFile {
                key: format!("mr:{}:{sha1}", target.to_lowercase()),
                target,
                name,
                summary,
                icon,
                size,
                sha1,
                sources,
                required: f["env"]["client"] != "optional",
                kind,
            });
        }
    }
    let mut targets = BTreeSet::new();
    for f in &files {
        ensure!(
            targets.insert(f.target.to_lowercase()),
            "清单存在重复目标路径：{}",
            f.target
        );
    }
    progress("读取覆盖文件", None);
    let mut overrides = vec![];
    let mut layers = BTreeSet::new();
    for e in entries {
        cancel.check()?;
        if e.directory {
            continue;
        }
        let normalized = e.name.replace('\\', "/");
        let (layer, target) = if let Some(t) = normalized.strip_prefix("overrides/") {
            (0, t)
        } else if !cf {
            if let Some(t) = normalized.strip_prefix("client-overrides/") {
                (1, t)
            } else {
                continue;
            }
        } else {
            continue;
        };
        let target = safe_target(target)?;
        ensure!(
            layers.insert((layer, target.to_lowercase())),
            "覆盖目录存在重复路径：{target}"
        );
        if target.to_lowercase().starts_with("mods/") && target.to_lowercase().ends_with(".jar") {
            ensure!(
                !targets.contains(&target.to_lowercase()),
                "清单Mod与覆盖目录Mod路径冲突：{target}"
            );
        }
        overrides.push(Override {
            index: e.index,
            entry: e.name,
            target,
            size: e.size,
            layer,
        });
    }
    overrides.sort_by(|a, b| a.layer.cmp(&b.layer).then(a.entry.cmp(&b.entry)));
    let name = text(&v, "name").trim();
    let preview = Preview {
        path: path.into(),
        size: meta.len(),
        modified: meta.modified()?,
        name: if name.is_empty() {
            path.file_stem()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned()
        } else {
            name.into()
        },
        summary: text(&v, "summary").trim().into(),
        version,
        loader,
        format: if cf { "CurseForge" } else { "Modrinth" }.into(),
        files,
        overrides,
        unavailable,
    };
    preview.unchanged()?;
    Ok(preview)
}
pub fn install(
    selection: &Selection,
    target: &Path,
    context: &crate::tasks::Context,
) -> Result<()> {
    let preview = &selection.preview;
    preview.unchanged()?;
    context.cancel.check()?;
    let requests: Vec<_> = preview
        .files
        .iter()
        .filter(|f| f.required || selection.optional.contains(&f.key))
        .map(|f| {
            Ok(content_store::Request {
                id: f.key.clone(),
                relative_path: f.target.clone(),
                size: Some(f.size),
                digests: vec![Digest::new(Algorithm::Sha1, &f.sha1)?],
                sources: {
                    let mut sources = Vec::new();
                    if crate::catalog::transport::prefer_mirror(
                        &crate::paths::data_dir().join("config.toml"),
                    ) {
                        for source in &f.sources {
                            let mut mirror = source.clone();
                            for host in [
                                "edge.forgecdn.net",
                                "mediafilez.forgecdn.net",
                                "media.forgecdn.net",
                                "cdn.modrinth.com",
                            ] {
                                mirror.url = mirror.url.replace(host, "mod.mcimirror.top");
                            }
                            if mirror.url != source.url {
                                sources.push(mirror);
                            }
                        }
                    }
                    sources.extend(f.sources.clone());
                    sources
                },
            })
        })
        .collect::<Result<_>>()?;
    selection.runtime.block_on(selection.store.materialize(
        &requests,
        target,
        &context.cancel,
        &|p| {
            context.progress(
                format!("正在下载{}字节 · {:.0}B/s", p.bytes, p.speed),
                p.total
                    .filter(|n| *n > 0)
                    .map(|n| p.bytes as f32 / n as f32),
            )
        },
    ))?;
    preview.unchanged()?;
    let mut zip = zip::ZipArchive::new(std::fs::File::open(&preview.path)?)?;
    let mut extracted = 0u64;
    for (i, o) in preview.overrides.iter().enumerate() {
        context.cancel.check()?;
        let mut f = zip.by_index(o.index)?;
        ensure!(
            decode_name(f.name_raw()) == o.entry,
            "覆盖文件已不存在：{}",
            o.entry
        );
        ensure!(
            f.unix_mode().is_none_or(|m| m & 0o170000 != 0o120000),
            "覆盖文件不能是符号链接：{}",
            o.entry
        );
        let output = super::installer::checked_target(target, Path::new(&safe_target(&o.target)?))?;
        std::fs::create_dir_all(output.parent().unwrap())?;
        let tmp = output.with_file_name(format!(
            ".{}.tmp",
            output.file_name().unwrap().to_string_lossy()
        ));
        super::installer::checked_target(target, tmp.strip_prefix(target)?)?;
        if tmp.try_exists()? {
            std::fs::remove_file(&tmp)?;
        }
        let mut out = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&tmp)?;
        let mut buffer = [0u8; 65536];
        loop {
            context.cancel.check()?;
            let n = f.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            extracted = extracted
                .checked_add(n as u64)
                .context("归档解压后超过20GB")?;
            ensure!(extracted <= MAX_BYTES, "归档解压后超过20GB");
            out.write_all(&buffer[..n])?;
        }
        out.sync_all()?;
        drop(out);
        std::fs::rename(&tmp, &output)?;
        context.progress(
            format!("正在应用覆盖文件{}/{}", i + 1, preview.overrides.len()),
            Some((i + 1) as f32 / preview.overrides.len().max(1) as f32),
        );
    }
    Ok(())
}
