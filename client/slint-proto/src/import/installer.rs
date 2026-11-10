use super::scanner::{supported, version_label};
use crate::tasks::Context;
use anyhow::{Context as _, Result, ensure};
use serde_json::{Value, json};
use std::{
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    sync::Mutex,
};
static COMMIT: Mutex<()> = Mutex::new(());
#[derive(Clone)]
pub struct Request {
    pub source: Option<PathBuf>,
    pub include_saves: bool,
    pub version: String,
    pub loader: String,
    pub name: String,
    pub comment: String,
    pub icon: String,
}
impl Request {
    pub fn validate(&self) -> Result<()> {
        ensure!(!self.name.trim().is_empty(), "整合包名称不能为空");
        ensure!(
            supported(&self.version, &self.loader),
            "暂不支持{}/{}",
            version_label(&self.version),
            self.loader
        );
        if !self.icon.trim().is_empty() {
            let url = reqwest::Url::parse(self.icon.trim())
                .map_err(|_| anyhow::anyhow!("图标链接无效"))?;
            ensure!(
                url.scheme() == "https" && url.host_str().is_some(),
                "图标链接必须是HTTPS网络地址"
            );
        }
        if let Some(dir) = &self.source {
            ensure!(dir.is_dir(), "找不到要导入的整合包目录");
        }
        Ok(())
    }
}
pub fn should_copy(relative: &str, directory_name: &str, saves: bool) -> bool {
    let segments = relative
        .split('/')
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>();
    let Some(first) = segments.first() else {
        return false;
    };
    let first = first.to_lowercase();
    if segments.len() > 1 {
        if [
            "logs",
            "crash-reports",
            "natives",
            "versions",
            "libraries",
            "assets",
            "runtime",
            ".cache",
            "cache",
            "downloads",
            "debug",
            ".mixin.out",
            "pcl",
            ".fabric",
        ]
        .contains(&first.as_str())
        {
            return false;
        }
        return first != "saves" || saves;
    }
    ![
        "launcher_profiles.json",
        "launcher_accounts.json",
        "launcher_settings.json",
        "launcher_log.txt",
        "hmclversion.cfg",
        "usercache.json",
        "usernamecache.json",
        "rdi-local-pack.json",
    ]
    .contains(&first.as_str())
        && !first.ends_with(".log")
        && !first.ends_with(".jar")
        && first != format!("{}.json", directory_name.to_lowercase())
}
pub fn unique_name(name: &str, existing: &[String]) -> String {
    let base = name.trim();
    let taken = existing
        .iter()
        .map(|n| n.trim().to_lowercase())
        .collect::<std::collections::HashSet<_>>();
    let mut name = base.to_owned();
    let mut index = 2;
    while taken.contains(&name.to_lowercase()) {
        name = format!("{base} ({index})");
        index += 1;
    }
    name
}
pub fn checked_target(root: &Path, relative: &Path) -> Result<PathBuf> {
    ensure!(
        !relative.as_os_str().is_empty()
            && relative
                .components()
                .all(|c| matches!(c, Component::Normal(_))),
        "整合包路径越界：{}",
        relative.display()
    );
    let mut path = root.to_path_buf();
    ensure!(
        !std::fs::symlink_metadata(root)?.file_type().is_symlink(),
        "整合包路径越界：{}",
        root.display()
    );
    for component in relative.components() {
        path.push(component);
        match std::fs::symlink_metadata(&path) {
            Ok(m) => ensure!(
                !m.file_type().is_symlink(),
                "整合包路径越界：{}",
                relative.display()
            ),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
    }
    Ok(path)
}
pub fn options(version: &str) -> &'static str {
    if version == "V071" {
        "lang:zh_CN\nforceUnicodeFont:true"
    } else {
        "darkMojangStudiosBackground:true\nlang:zh_cn\nforceUnicodeFont:true"
    }
}
// Staging files are generated runtime data, never project source files.
fn cleanup(path: &Path) -> Result<()> {
    match std::fs::symlink_metadata(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
        Ok(m) if m.file_type().is_symlink() || !m.is_dir() => {
            std::fs::remove_file(path)?;
            Ok(())
        }
        Ok(_) => {
            for entry in std::fs::read_dir(path)? {
                cleanup(&entry?.path())?;
            }
            std::fs::remove_dir(path)?;
            Ok(())
        }
    }
}
fn collect(
    root: &Path,
    dir: &Path,
    request: &Request,
    context: &Context,
    files: &mut Vec<PathBuf>,
) -> Result<()> {
    context.cancel.check()?;
    for entry in std::fs::read_dir(dir)? {
        context.cancel.check()?;
        let entry = entry?;
        let ty = entry.file_type()?;
        if ty.is_symlink() {
            continue;
        }
        let path = entry.path();
        let relative = path.strip_prefix(root)?;
        if ty.is_dir() {
            collect(root, &path, request, context, files)?;
        } else if ty.is_file()
            && should_copy(
                &relative.to_string_lossy().replace('\\', "/"),
                &root.file_name().unwrap_or_default().to_string_lossy(),
                request.include_saves,
            )
        {
            files.push(path);
        }
    }
    Ok(())
}
pub fn install(versions: &Path, id: &str, request: &Request, context: &Context) -> Result<()> {
    request.validate()?;
    std::fs::create_dir_all(versions)?;
    let staging = versions.join(format!("local_{id}.installing"));
    let target = versions.join(format!("local_{id}"));
    ensure!(!target.try_exists()?, "整合包目录已存在");
    cleanup(&staging)?;
    let result = (|| -> Result<()> {
        context.cancel.check()?;
        context.progress("正在准备", Some(0.));
        std::fs::create_dir(&staging)?;
        if let Some(source) = &request.source {
            let root = std::path::absolute(source)?;
            let canonical_root = std::fs::canonicalize(&root)?;
            let canonical_versions = std::fs::canonicalize(versions)?;
            ensure!(
                !canonical_versions.starts_with(&canonical_root),
                "不能从整合包目录的上级文件夹导入"
            );
            let mut files = vec![];
            collect(&root, &root, request, context, &mut files)?;
            for (index, file) in files.iter().enumerate() {
                context.cancel.check()?;
                ensure!(
                    !std::fs::symlink_metadata(file)?.file_type().is_symlink(),
                    "源文件已发生变化"
                );
                let output = checked_target(&staging, file.strip_prefix(&root)?)?;
                std::fs::create_dir_all(output.parent().context("缺少目标目录")?)?;
                let mut input = std::fs::File::open(file)?;
                let mut output = std::fs::File::create(&output)?;
                let mut buffer = [0u8; 65536];
                loop {
                    context.cancel.check()?;
                    let count = input.read(&mut buffer)?;
                    if count == 0 {
                        break;
                    }
                    output.write_all(&buffer[..count])?;
                }
                context.progress(
                    format!(
                        "正在复制{}",
                        file.file_name().unwrap_or_default().to_string_lossy()
                    ),
                    Some((index + 1) as f32 / files.len().max(1) as f32),
                );
            }
        }
        std::fs::create_dir_all(staging.join("mods"))?;
        if request.source.is_none() || !staging.join("options.txt").is_file() {
            std::fs::write(staging.join("options.txt"), options(&request.version))?;
        }
        context.cancel.check()?;
        context.progress("正在保存", None);
        let _lock = COMMIT
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        context.cancel.check()?;
        let mut names = vec![];
        for entry in std::fs::read_dir(versions)? {
            let path = entry?.path();
            let name = path.file_name().unwrap_or_default().to_string_lossy();
            if name.starts_with("local_") && !name.ends_with(".installing") && path.is_dir() {
                match std::fs::read_to_string(path.join("rdi-local-pack.json"))
                    .map_err(anyhow::Error::from)
                    .and_then(|s| serde_json::from_str::<Value>(&s).map_err(Into::into))
                {
                    Ok(v) => {
                        if let Some(name) = v["name"].as_str() {
                            names.push(name.to_owned());
                        }
                    }
                    Err(error) => tracing::warn!(%error,dir=%path.display(),"读取本地整合包失败"),
                }
            }
        }
        let info = json!({"id":id,"name":unique_name(&request.name,&names),"comment":request.comment.trim(),"iconUrl":if request.icon.trim().is_empty(){Value::Null}else{json!(request.icon.trim())},"mcVersion":request.version,"modLoader":request.loader,"createdAt":std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_millis() as u64,"source":if request.source.is_some(){"Directory"}else{"Empty"}});
        crate::credentials::write_atomic(
            &staging.join("rdi-local-pack.json"),
            serde_json::to_string(&info)?.as_bytes(),
        )?;
        ensure!(!target.try_exists()?, "整合包目录已存在");
        std::fs::rename(&staging, &target)?;
        Ok(())
    })();
    // Preserve the original error while making cleanup failures observable.
    if let Err(error) = cleanup(&staging) {
        tracing::error!(%error,"清理导入临时文件失败");
        if result.is_ok() {
            return Err(error);
        }
    }
    // Only staging is owned until the final rename; a pre-existing target is never removed.
    result
}
