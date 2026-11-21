//! Optional display enrichment. Failures are observable and never prevent a valid import.
use super::archive::Preview;
use crate::{
    catalog::{Catalog, ContentType, FileHashes, FileRef, Platform},
    digests,
    tasks::Cancellation,
};
use anyhow::Result;
use serde_json::{Value, json};
use std::{collections::BTreeMap, io::Read};
pub async fn resolve(
    preview: &Preview,
    catalog: &Catalog,
    cancel: &Cancellation,
) -> Result<BTreeMap<String, Value>> {
    preview.unchanged()?;
    let mut files = BTreeMap::new();
    for f in &preview.files {
        if f.kind != "Mod" {
            continue;
        }
        for source in &f.sources {
            let Ok(url) = reqwest::Url::parse(&source.url) else {
                continue;
            };
            let parts: Vec<_> = url.path_segments().into_iter().flatten().collect();
            let reference = if url
                .host_str()
                .is_some_and(|h| h == "modrinth.com" || h.ends_with(".modrinth.com"))
                && parts.len() == 5
                && parts[0] == "data"
                && parts[2] == "versions"
                && parts[4].to_lowercase().ends_with(".jar")
            {
                Some((
                    FileRef {
                        platform: Platform::Modrinth,
                        id: parts[3].into(),
                    },
                    Some(parts[1].to_owned()),
                ))
            } else if url.host_str().is_some_and(|h| h.ends_with(".forgecdn.net"))
                && parts.len() == 4
                && parts[0] == "files"
                && parts[2].len() <= 3
                && !parts[2].is_empty()
                && parts[3].to_lowercase().ends_with(".jar")
            {
                match (parts[1].parse::<u64>(), parts[2].parse::<u64>()) {
                    (Ok(a), Ok(b))
                        if a.checked_mul(1000)
                            .and_then(|a| a.checked_add(b))
                            .is_some_and(|id| id <= i32::MAX as u64) =>
                    {
                        Some((
                            FileRef {
                                platform: Platform::CurseForge,
                                id: (a * 1000 + b).to_string(),
                            },
                            None,
                        ))
                    }
                    _ => None,
                }
            } else {
                None
            };
            if let Some((r, project)) = reference {
                match catalog.get_files(std::slice::from_ref(&r)).await {
                    Ok(mut result) => {
                        if let Some(file) = result.value.remove(&r)
                            && project.is_none_or(|p| p == file.project.id)
                        {
                            files.insert(f.key.clone(), file);
                        }
                    }
                    Err(error) => tracing::warn!(%error,"归档下载地址识别失败"),
                };
                break;
            }
        }
    }
    cancel.check()?;
    preview.unchanged()?;
    let mut zip = zip::ZipArchive::new(std::fs::File::open(&preview.path)?)?;
    let mut hashes = vec![];
    for o in &preview.overrides {
        if !o.target.to_lowercase().starts_with("mods/")
            || !o.target.to_lowercase().ends_with(".jar")
        {
            continue;
        }
        cancel.check()?;
        let mut input = zip.by_index(o.index)?;
        let mut bytes = vec![];
        input
            .by_ref()
            .take(256 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)?;
        anyhow::ensure!(bytes.len() <= 256 * 1024 * 1024, "内嵌Mod过大");
        use sha1::Digest as _;
        hashes.push(FileHashes {
            key: format!("zip:{}", o.entry),
            sha1: format!("{:x}", sha1::Sha1::digest(&bytes)),
            murmur2: Some(digests::murmur2(&bytes)),
        });
    }
    if !hashes.is_empty() {
        match catalog.match_files(&hashes).await {
            Ok(matched) => files.extend(matched),
            Err(error) => tracing::warn!(%error,"内嵌Mod识别失败"),
        }
    }
    let projects = catalog
        .get_mods(
            &files
                .values()
                .map(|f| f.project.clone())
                .collect::<Vec<_>>(),
        )
        .await?
        .value;
    let mut result = BTreeMap::new();
    for (key, file) in files {
        if let Some(project) = projects.get(&file.project)
            && project
                .sources
                .iter()
                .any(|p| p.reference == file.project && p.kind == ContentType::Mod)
        {
            result.insert(key,json!({"name":project.name_cn.as_ref().unwrap_or(&project.name),"detail":project.summary,"icon":project.icons.first().cloned().unwrap_or_default()}));
        }
    }
    Ok(result)
}
