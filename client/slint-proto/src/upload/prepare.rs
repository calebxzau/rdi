//! What the upload does around the pack readers: Kotlin `RdiModpackUploadGateway`
//! `prepareClientPack` and `prepareServerPack` with `commitEmbeddedModSources`,
//! `commitEmbeddedClientExtraSources`, `defaultCurseForgeUnknownMods`, `availableFabricSides`,
//! `mergeClientAndServerMods`, `mergeAsBoth`, `sameModContent` and `findMissingMods`.
//! The Kotlin page keeps no local file per mod, so a mod's jar is the content store's cache
//! file here, as `useCached` gives it there. Blocking, like `reader`.
use super::jar_meta;
use super::reader::{self, Context, EmbeddedExtra, EmbeddedMod, Loaded};
use super::server_pack::{self, LoadedServer};
use crate::content_store::{adopt, cache_file};
use crate::digests::{Algorithm, Digest, hash};
use crate::pack_install::mods::{Mod, Side, digest_for, process_mods};
use crate::tasks::Cancellation;
use anyhow::{Result, ensure};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// `modStableKey`.
fn stable_key(m: &Mod) -> String {
    format!("{}:{}:{}:{}", m.platform, m.project_id, m.file_id, m.hash)
}

/// The cache file of a mod whose record names a usable digest (`trustedModContentRequest`
/// through `useCached`), when it is there.
pub fn cached(cache: &Path, m: &Mod) -> Option<PathBuf> {
    let digest = m.digest().ok()?;
    Some(cache_file(cache, &digest)).filter(|p| p.is_file())
}

fn matches(file: &Path, digest: &Digest, cancel: &Cancellation) -> Result<bool> {
    let actual = hash(file, digest.algorithm, cancel)?;
    Ok(if digest.algorithm == Algorithm::Murmur2 { actual.parse::<u64>().ok() == digest.value.parse::<u64>().ok() } else { actual.eq_ignore_ascii_case(&digest.value) })
}

/// Staged files are removed whatever happened, with their folder once it is empty.
fn remove_staged(files: &[&Path]) {
    for file in files {
        let _ = std::fs::remove_file(file);
        if let Some(parent) = file.parent() {
            let _ = std::fs::remove_dir(parent);
        }
    }
}

/// `commitEmbeddedModSources`: the recognised jars of a pack enter the content store, so the
/// tests and the local install find them without a download.
pub fn commit_mods(cache: &Path, cancel: &Cancellation, staged: &[EmbeddedMod]) -> Result<()> {
    let result = staged.iter().try_for_each(|source| {
        ensure!(source.staged.is_file(), "暂存内嵌mod不存在: {}", source.staged.display());
        let digest = source.m.digest()?;
        ensure!(matches(&source.staged, &digest, cancel)?, "内嵌mod校验失败: {}", source.original_name);
        adopt(cache, &source.staged, &[digest])
    });
    remove_staged(&staged.iter().map(|s| s.staged.as_path()).collect::<Vec<_>>());
    result
}

/// `commitEmbeddedClientExtraSources`: the same for recognised resource and shader packs. A
/// CurseForge pack is stored under its fingerprint and under the SHA-1 checked at matching.
pub fn commit_extras(cache: &Path, cancel: &Cancellation, staged: &[EmbeddedExtra]) -> Result<()> {
    let result = (|| -> Result<()> {
        let sha1_of = |source: &EmbeddedExtra| Digest::new(Algorithm::Sha1, &source.sha1);
        for source in staged {
            ensure!(source.staged.is_file(), "暂存客户端额外内容不存在: {}", source.staged.display());
            ensure!(matches(&source.staged, &sha1_of(source)?, cancel)?, "客户端额外内容在缓存提交前发生变化: {}", source.source_relative);
        }
        for source in staged {
            let platform = match source.content.platform {
                crate::pack_install::extras::Platform::CurseForge => "cf",
                crate::pack_install::extras::Platform::Modrinth => "mr",
                crate::pack_install::extras::Platform::GitHub => "github",
            };
            let mut digests = vec![digest_for(platform, &source.content.hash)?];
            if digests[0].algorithm != Algorithm::Sha1 {
                ensure!(matches(&source.staged, &digests[0], cancel)?, "客户端额外内容校验失败: {}", source.source_relative);
                digests.push(sha1_of(source)?);
            }
            adopt(cache, &source.staged, &digests)?;
        }
        Ok(())
    })();
    remove_staged(&staged.iter().map(|s| s.staged.as_path()).collect::<Vec<_>>());
    result
}

/// `defaultCurseForgeUnknownMods`: a CurseForge mod with no known side starts as both.
pub fn default_unknown_cf(mods: &mut [Mod]) {
    for m in mods {
        if m.platform.eq_ignore_ascii_case("cf") && m.side == Side::Unknown {
            m.side = Side::Both;
        }
    }
}

/// `applyAvailableFabricSides`: on Fabric, a cached jar that declares its side decides.
pub fn apply_fabric_sides(cache: &Path, mods: &mut [Mod], fabric: bool) {
    if !fabric {
        return;
    }
    for m in mods {
        if let Some(side) = cached(cache, m).and_then(|file| jar_meta::read(&file)).and_then(|meta| meta.side) {
            m.side = side;
        }
    }
}

/// `findMissingMods`: the mods whose jars the content store does not hold yet.
pub fn missing_mods(cache: &Path, mods: &[Mod]) -> Vec<Mod> {
    mods.iter().filter(|m| cached(cache, m).is_none()).cloned().collect()
}

/// `prepareClientPack`: the pack read, its recognised files committed, the sides defaulted and
/// the upload's mod rules applied.
pub fn client(ctx: &Context, cache: &Path, input: &Path) -> Result<Loaded> {
    let mut loaded = reader::load(ctx, input)?;
    commit_mods(cache, &ctx.cancel, &loaded.embedded_mods)?;
    commit_extras(cache, &ctx.cancel, &loaded.embedded_extras)?;
    let fabric = loaded.summary.loader == "Fabric";
    default_unknown_cf(&mut loaded.mods);
    apply_fabric_sides(cache, &mut loaded.mods, fabric);
    loaded.mods = process_mods(&loaded.mods, fabric);
    Ok(loaded)
}

/// `Mod.contentDigest`: the algorithm and normalized value, when the record's hash is usable.
fn content_digest(m: &Mod) -> Option<(Algorithm, String)> {
    let value = m.hash.trim().to_lowercase();
    let is_hex = |length: usize| value.len() == length && value.bytes().all(|b| b.is_ascii_hexdigit());
    match m.platform.trim().to_lowercase().as_str() {
        "cf" => value.parse::<u64>().ok().filter(|v| *v != 0 && *v <= u64::from(u32::MAX)).map(|v| (Algorithm::Murmur2, v.to_string())),
        "mr" if is_hex(40) => Some((Algorithm::Sha1, value)),
        "github" if is_hex(40) => Some((Algorithm::Sha1, value)),
        "github" if is_hex(64) => Some((Algorithm::Sha256, value)),
        _ => None,
    }
}

/// `sameModContent` without local files: only hashes of one algorithm can be compared.
fn same_content(a: &Mod, b: &Mod) -> bool {
    matches!((content_digest(a), content_digest(b)), (Some(x), Some(y)) if x == y)
}

#[derive(Clone, PartialEq)]
struct Entry {
    m: Mod,
    strict: String,
    slug: Option<String>,
}

/// `strictModMergeKey`: the jar's mod id when the jar is cached, else slug, project or record.
fn strict_key(m: &Mod, mod_ids: &BTreeMap<String, Option<String>>) -> String {
    if let Some(Some(id)) = mod_ids.get(&stable_key(m)).filter(|id| id.as_deref().is_some_and(|id| !id.trim().is_empty())) {
        format!("modid:{id}")
    } else if !m.slug.trim().is_empty() {
        format!("slug:{}", m.slug.to_lowercase())
    } else if !m.project_id.trim().is_empty() {
        format!("project:{}", m.project_id)
    } else {
        stable_key(m)
    }
}

fn with_side(m: &Mod, side: Side) -> Mod {
    Mod { side, ..m.clone() }
}

/// `mergeAsBoth`: one mod on both sides. Different bytes keep the client's addresses and its
/// client override mark.
fn merge_as_both(client: &Mod, server: &Mod, fabric: bool) -> Mod {
    let same = same_content(client, server);
    let mut urls = client.download_urls.clone();
    if same {
        for url in &server.download_urls {
            if !urls.contains(url) {
                urls.push(url.clone());
            }
        }
    }
    Mod { side: if fabric { client.side } else { Side::Both }, download_urls: urls, client_only_override: client.client_only_override && !same, ..client.clone() }
}

/// `mergeClientAndServerMods`: a server mod is the client mod with the same mod id (or the
/// same slug, when that slug is unique on both sides); the rest is client only or server only.
pub fn merge_client_server(cache: &Path, client: &[Mod], server: &[Mod], fabric: bool) -> Vec<Mod> {
    let mut mod_ids: BTreeMap<String, Option<String>> = BTreeMap::new();
    for m in client.iter().chain(server) {
        if m.digest().is_ok() {
            let id = cached(cache, m).and_then(|file| jar_meta::read(&file)).and_then(|meta| meta.mod_id).filter(|id| !id.trim().is_empty());
            mod_ids.entry(stable_key(m)).or_insert(id);
        }
    }
    let entry = |m: &Mod| Entry { m: m.clone(), strict: strict_key(m, &mod_ids), slug: Some(m.slug.trim().to_lowercase()).filter(|s| !s.is_empty()).map(|s| format!("slug:{s}")) };
    let clients: Vec<Entry> = client.iter().map(entry).collect();
    let servers: Vec<Entry> = server.iter().map(entry).collect();
    let once = |entries: &[Entry], key: &str| entries.iter().filter(|e| e.slug.as_deref() == Some(key)).count() == 1;
    let mut matched: Vec<Entry> = Vec::new();
    let mut merged: Vec<Mod> = Vec::new();
    for c in &clients {
        // `associateBy`: of server mods with one key the last stands for it.
        let strict = servers.iter().rev().find(|s| s.strict == c.strict).filter(|s| !matched.contains(s));
        let by_slug = c
            .slug
            .as_deref()
            .filter(|key| once(&clients, key) && once(&servers, key))
            .and_then(|key| servers.iter().find(|s| s.slug.as_deref() == Some(key)))
            .filter(|s| !matched.contains(s));
        let found = strict.or(by_slug);
        match found {
            Some(s) if c.m.client_only_override && !same_content(&c.m, &s.m) => merged.push(with_side(&c.m, Side::Client)),
            Some(s) => {
                if strict.is_none() {
                    tracing::info!(client = %format!("{}:{} {}", c.m.platform, c.m.project_id, c.m.slug), server = %format!("{}:{} {}", s.m.platform, s.m.project_id, s.m.slug), "按slug合并跨来源mod");
                }
                matched.push(s.clone());
                merged.push(merge_as_both(&c.m, &s.m, fabric));
            }
            None => merged.push(if fabric { c.m.clone() } else { with_side(&c.m, Side::Client) }),
        }
    }
    for s in &servers {
        if !matched.contains(s) {
            merged.push(if fabric { s.m.clone() } else { with_side(&s.m, Side::Server) });
        }
    }
    let override_keys: Vec<String> = merged.iter().filter(|m| m.client_only_override).map(|m| strict_key(m, &mod_ids)).collect();
    let mut seen: Vec<String> = Vec::new();
    let mut distinct: Vec<Mod> = Vec::new();
    for m in merged {
        let strict = strict_key(&m, &mod_ids);
        let key = if override_keys.contains(&strict) { format!("{strict}:{}", stable_key(&m)) } else { strict };
        if !seen.contains(&key) {
            seen.push(key);
            distinct.push(m);
        }
    }
    distinct.sort_by_key(|m| m.slug.to_lowercase());
    distinct
}

/// `prepareServerPack`: the server pack read against the client mods whose jars are cached,
/// its recognised jars committed, and the merged mod list of both packs.
pub fn server(ctx: &Context, cache: &Path, file: &Path, client_mods: &[Mod], fabric: bool) -> Result<(LoadedServer, Vec<Mod>)> {
    ctx.phase("正在检查本地Mod");
    let sources: Vec<(Mod, PathBuf)> = client_mods.iter().filter_map(|m| Some((m.clone(), cached(cache, m)?))).collect();
    (ctx.progress)(reader::Progress::Percent("本地Mod检查完成".into(), 1.0));
    let loaded = server_pack::load(ctx, file, client_mods, &sources, fabric)?;
    commit_mods(cache, &ctx.cancel, &loaded.embedded_mods)?;
    let (mut client, mut server) = (client_mods.to_vec(), loaded.mods.clone());
    apply_fabric_sides(cache, &mut client, fabric);
    apply_fabric_sides(cache, &mut server, fabric);
    let mut merged = merge_client_server(cache, &client, &server, fabric);
    apply_fabric_sides(cache, &mut merged, fabric);
    Ok((loaded, process_mods(&merged, fabric)))
}
