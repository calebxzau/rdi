//! Kotlin `ModpackModProcessor`, `isClientInstallableMod`, `Mod.fileName` and the mod source order
//! of `ModService.downloadSingleModFromSources`. `protocol/fixtures/j4c.json` guards the lists.
use crate::{
    content_store::Request,
    digests::{Algorithm, Digest},
    download::Source,
};
use anyhow::Result;
use reqwest::header::HeaderMap;
use serde::{Deserialize, Serialize};

/// `ModpackModProcessor.removedSlugs`.
pub const REMOVED_SLUGS: [&str; 14] = [
    "powerful-dummy",
    "spark",
    "essential-mod",
    "default-server-properties",
    "skybox-loader-forge",
    "customskinloader",
    "chunky",
    "log-deduplicator",
    "lazyyyyy",
    "zstd-net",
    "zstdnet",
    "mcwifipnp",
    "what-can-i-see",
    "tab-list",
];
/// Removed for Forge and NeoForge only.
pub const FORGE_ONLY_REMOVED_SLUGS: [&str; 2] = ["skybox-loader-forge", "lazyyyyy"];
pub const CLIENT_SIDE_SLUGS: [&str; 7] = [
    "status-effect-bars-reforged",
    "mafglib",
    "flighthud-reborn",
    "i18nupdatemod",
    "modern-ui",
    "controllable",
    "mekalus-oculus-fork-with-fixed-mekanism-mekasuit",
];
pub const BOTH_SIDE_SLUGS: [&str; 9] = [
    "loot-beams-refork",
    "particular-reforged",
    "inventory-profiles-next",
    "inventory-tweaks-refoxed",
    "just-enough-resources-jer",
    "radiant-gear",
    "fusion-connected-textures",
    "oh-the-trees-youll-grow",
    "smartbrainlib",
];
/// `MOD_FILE_SLUG_ALIASES`.
pub const MOD_FILE_SLUG_ALIASES: [(&str, &str); 1] = [("true-ending", "trueending")];

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Side {
    Client,
    Server,
    #[default]
    Both,
    Unknown,
}

/// Kotlin `Mod`; unknown fields from the server are ignored.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Mod {
    pub platform: String,
    pub project_id: String,
    pub slug: String,
    pub file_id: String,
    pub hash: String,
    #[serde(default)]
    pub side: Side,
    #[serde(default)]
    pub download_urls: Vec<String>,
    #[serde(default)]
    pub client_only_override: bool,
    #[serde(default)]
    pub client_override_replaced: bool,
}

impl Mod {
    pub fn file_slug(&self) -> String {
        let normalized = self.slug.trim().to_lowercase();
        MOD_FILE_SLUG_ALIASES
            .iter()
            .find(|(from, _)| *from == normalized)
            .map(|(_, to)| (*to).to_owned())
            .unwrap_or_else(|| self.slug.trim().to_owned())
    }
    pub fn file_name(&self) -> String {
        format!("{}_{}_{}.jar", self.file_slug(), self.platform, self.hash)
    }
    /// `Mod.legacyFileName`: the slug as stored, without trimming or alias.
    pub fn legacy_file_name(&self) -> String {
        format!("{}_{}_{}.jar", self.slug, self.platform, self.hash)
    }
    /// `Mod.fileNames`: current then legacy name, without duplicates.
    pub fn file_names(&self) -> Vec<String> {
        let (current, legacy) = (self.file_name(), self.legacy_file_name());
        if current == legacy { vec![current] } else { vec![current, legacy] }
    }
    pub(crate) fn normalized_slug(&self) -> String {
        self.slug.trim().to_lowercase()
    }
    fn with_side(&self, side: Side) -> Self {
        Self {
            side,
            ..self.clone()
        }
    }
    /// `Mod.toClientContentRequest` digest choice.
    pub fn digest(&self) -> Result<Digest> {
        digest_for(&self.platform.to_lowercase(), &self.hash)
    }
    pub fn request_id(&self) -> String {
        format!(
            "{}:{}:{}:{}",
            self.platform, self.project_id, self.file_id, self.hash
        )
    }
    /// `ModService.trustedCurseForgeFile`: a URL and a numeric fingerprint need no file lookup.
    pub fn trusted_curse_forge(&self) -> bool {
        self.platform.eq_ignore_ascii_case("cf")
            && self.file_id.parse::<i32>().is_ok()
            && self.hash.trim().parse::<i64>().is_ok()
            && self.download_urls.iter().any(|u| !u.trim().is_empty())
    }
}

/// Platform codes: `cf` Murmur2, `mr` SHA-1, `github` SHA-1 for 40 hex digits else SHA-256.
pub fn algorithm(platform: &str, hash: &str) -> Algorithm {
    match platform {
        "cf" => Algorithm::Murmur2,
        "github" if !github_uses_sha1(hash) => Algorithm::Sha256,
        _ => Algorithm::Sha1,
    }
}
pub fn digest_for(platform: &str, hash: &str) -> Result<Digest> {
    Digest::new(algorithm(platform, hash), hash)
}

/// `ModService.githubUsesSha1`.
pub fn github_uses_sha1(hash: &str) -> bool {
    let hash = hash.trim();
    hash.len() == 40 && hash.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Kotlin `sameMod`.
pub(crate) fn same_mod(a: &Mod, b: &Mod) -> bool {
    let (ap, bp) = (a.project_id.trim(), b.project_id.trim());
    if a.platform == b.platform && !ap.is_empty() && !bp.is_empty() {
        return ap == bp;
    }
    let (asl, bsl) = (a.normalized_slug(), b.normalized_slug());
    if !asl.is_empty() && !bsl.is_empty() {
        return asl == bsl;
    }
    if !a.hash.trim().is_empty() && !b.hash.trim().is_empty() {
        return a.hash.eq_ignore_ascii_case(&b.hash);
    }
    a.platform == b.platform
        && !a.file_id.trim().is_empty()
        && !b.file_id.trim().is_empty()
        && a.file_id == b.file_id
}

/// `ModpackModProcessor.processMods`; `fabric` follows `loader == ModLoader.Fabric`.
pub fn process_mods(mods: &[Mod], fabric: bool) -> Vec<Mod> {
    let removed = |slug: &str| {
        REMOVED_SLUGS.contains(&slug) && !(fabric && FORGE_ONLY_REMOVED_SLUGS.contains(&slug))
    };
    let processed: Vec<Mod> = mods
        .iter()
        .filter_map(|m| {
            let slug = m.normalized_slug();
            if slug.contains("backup") || removed(&slug) {
                None
            } else if m.client_only_override {
                Some(m.with_side(Side::Client))
            } else if m.client_override_replaced && m.side == Side::Client {
                None
            } else if m.client_override_replaced {
                Some(m.with_side(if m.side == Side::Both {
                    Side::Server
                } else {
                    m.side
                }))
            } else if !fabric && CLIENT_SIDE_SLUGS.contains(&slug.as_str()) {
                Some(m.with_side(Side::Client))
            } else if !fabric && BOTH_SIDE_SLUGS.contains(&slug.as_str()) {
                Some(m.with_side(Side::Both))
            } else {
                Some(m.clone())
            }
        })
        .collect();
    let overrides: Vec<Mod> = processed
        .iter()
        .filter(|m| m.client_only_override)
        .cloned()
        .collect();
    processed
        .into_iter()
        .filter_map(|m| {
            let shadowed = !m.client_only_override
                && overrides
                    .iter()
                    .any(|o| same_mod(o, &m) && !o.hash.eq_ignore_ascii_case(&m.hash));
            if !shadowed {
                return Some(m);
            }
            match m.side {
                Side::Both => Some(m.with_side(Side::Server)),
                Side::Client => None,
                _ => Some(m),
            }
        })
        .collect()
}

/// `installVersionTask2`: process only when a client override exists, then drop server and unknown mods.
pub fn client_installable(mods: &[Mod], fabric: bool) -> Vec<Mod> {
    let scoped = if mods
        .iter()
        .any(|m| m.client_only_override || m.client_override_replaced)
    {
        process_mods(mods, fabric)
    } else {
        mods.to_vec()
    };
    scoped
        .into_iter()
        .filter(|m| m.side != Side::Server && m.side != Side::Unknown)
        .collect()
}

/// `ModService.ofMirrorUrl`.
pub fn mirror_url(url: &str) -> String {
    url.replace("edge.forgecdn.net", "mod.mcimirror.top")
        .replace("mediafilez.forgecdn.net", "mod.mcimirror.top")
        .replace("media.forgecdn.net", "mod.mcimirror.top")
        .replace("api.modrinth.com", "mod.mcimirror.top/modrinth")
        .replace("staging-api.modrinth.com", "mod.mcimirror.top/modrinth")
        .replace("cdn.modrinth.com", "mod.mcimirror.top")
        .replace("api.curseforge.com", "mod.mcimirror.top/curseforge")
}

fn distinct(urls: impl IntoIterator<Item = String>) -> Vec<String> {
    let mut out: Vec<String> = vec![];
    for url in urls {
        if !out.contains(&url) {
            out.push(url);
        }
    }
    out
}

/// Header rules for the source list: the RDI copy gets the login token, every other URL the
/// CurseForge key rule (`CurseForgeService.downloadHeadersFor`).
pub trait SourceHeaders {
    fn rdi_mod_url(&self, file_name: &str) -> String;
    fn rdi_headers(&self, url: &str) -> Result<HeaderMap>;
    fn curse_forge_headers(&self, url: &str) -> Result<HeaderMap>;
}

/// Mod sources in Kotlin order. `real_url` is the CurseForge file's real download URL.
pub fn sources(
    m: &Mod,
    real_url: Option<&str>,
    prefer_mirror: bool,
    headers: &dyn SourceHeaders,
) -> Result<Vec<Source>> {
    let official = distinct(
        m.download_urls
            .iter()
            .map(String::as_str)
            .chain(real_url)
            .map(|u| u.trim().to_owned())
            .filter(|u| !u.is_empty()),
    );
    let mirrors = distinct(
        official
            .iter()
            .map(|u| mirror_url(u).trim().to_owned())
            .filter(|u| !u.is_empty() && !official.contains(u)),
    );
    let rdi = headers.rdi_mod_url(&m.file_name());
    let urls = if prefer_mirror {
        distinct(std::iter::once(rdi.clone()).chain(mirrors).chain(official))
    } else {
        distinct(official.into_iter().chain(std::iter::once(rdi.clone())))
    };
    urls.into_iter()
        .map(|url| {
            Ok(Source {
                headers: if url == rdi {
                    headers.rdi_headers(&url)?
                } else {
                    headers.curse_forge_headers(&url)?
                },
                url,
            })
        })
        .collect()
}

/// Cache request for one mod at `mods/<fileName>`.
pub fn request(m: &Mod, sources: Vec<Source>) -> Result<Request> {
    Ok(Request {
        id: m.request_id(),
        relative_path: m.file_name(),
        size: None,
        digests: vec![m.digest()?],
        sources,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    struct Fake;
    impl SourceHeaders for Fake {
        fn rdi_mod_url(&self, file_name: &str) -> String {
            format!("https://rdi.invalid/mod/download/{file_name}")
        }
        fn rdi_headers(&self, _: &str) -> Result<HeaderMap> {
            let mut h = HeaderMap::new();
            h.insert("authorization", "Bearer t".parse()?);
            Ok(h)
        }
        fn curse_forge_headers(&self, url: &str) -> Result<HeaderMap> {
            let mut h = HeaderMap::new();
            let host = reqwest::Url::parse(url)?.host_str().unwrap_or("").to_owned();
            if url.starts_with("https://")
                && (host == "mod.mcimirror.top" || host.ends_with(".forgecdn.net"))
            {
                h.insert("x-api-key", "k".parse()?);
            }
            Ok(h)
        }
    }

    fn fixture() -> Value {
        serde_json::from_str(include_str!("../../protocol/fixtures/j4c.json")).unwrap()
    }

    #[test]
    fn slug_lists_match_kotlin() {
        let lists = &fixture()["lists"];
        let set = |v: &Value| {
            let mut v: Vec<String> = serde_json::from_value(v.clone()).unwrap();
            v.sort();
            v
        };
        let ours = |v: &[&str]| {
            let mut v: Vec<String> = v.iter().map(|s| s.to_string()).collect();
            v.sort();
            v
        };
        assert_eq!(set(&lists["removedSlugs"]), ours(&REMOVED_SLUGS));
        assert_eq!(set(&lists["forgeOnlyRemovedSlugs"]), ours(&FORGE_ONLY_REMOVED_SLUGS));
        assert_eq!(set(&lists["clientSideSlugs"]), ours(&CLIENT_SIDE_SLUGS));
        assert_eq!(set(&lists["bothSideSlugs"]), ours(&BOTH_SIDE_SLUGS));
        let aliases: std::collections::BTreeMap<String, String> =
            serde_json::from_value(lists["fileSlugAliases"].clone()).unwrap();
        assert_eq!(
            aliases,
            MOD_FILE_SLUG_ALIASES
                .iter()
                .map(|(a, b)| (a.to_string(), b.to_string()))
                .collect()
        );
    }

    #[test]
    fn processing_and_file_names_match_kotlin_cases() {
        let cases = fixture()["cases"].as_array().unwrap().clone();
        assert!(cases.len() > 10);
        for case in cases {
            let mods: Vec<Mod> = serde_json::from_value(case["mods"].clone()).unwrap();
            let fabric = case["loader"] == "Fabric";
            let actual: Vec<Value> = client_installable(&mods, fabric)
                .iter()
                .map(|m| {
                    json!({"slug":m.slug,"platform":m.platform,"side":m.side,"fileName":m.file_name(),
                        "digest":algorithm(&m.platform.to_lowercase(), &m.hash).suffix()})
                })
                .collect();
            assert_eq!(Value::Array(actual), case["expected"], "{}", case["name"]);
        }
    }

    #[test]
    fn source_order_and_headers() {
        let m = Mod {
            platform: "cf".into(),
            project_id: "1".into(),
            slug: "a".into(),
            file_id: "4567890".into(),
            hash: "123".into(),
            side: Side::Both,
            download_urls: vec![" https://edge.forgecdn.net/files/4567/890/a.jar ".into(), "".into()],
            client_only_override: false,
            client_override_replaced: false,
        };
        let real = "https://mediafilez.forgecdn.net/files/4567/890/a.jar";
        let urls = |prefer| {
            sources(&m, Some(real), prefer, &Fake)
                .unwrap()
                .into_iter()
                .map(|s| (s.url, s.headers.keys().map(|k| k.to_string()).collect::<Vec<_>>()))
                .collect::<Vec<_>>()
        };
        let rdi = "https://rdi.invalid/mod/download/a_cf_123.jar".to_string();
        let mirror = "https://mod.mcimirror.top/files/4567/890/a.jar".to_string();
        assert_eq!(
            urls(true),
            [
                (rdi.clone(), vec!["authorization".to_string()]),
                (mirror, vec!["x-api-key".into()]),
                ("https://edge.forgecdn.net/files/4567/890/a.jar".into(), vec!["x-api-key".into()]),
                (real.into(), vec!["x-api-key".into()]),
            ]
        );
        assert_eq!(
            urls(false),
            [
                ("https://edge.forgecdn.net/files/4567/890/a.jar".into(), vec!["x-api-key".to_string()]),
                (real.into(), vec!["x-api-key".into()]),
                (rdi, vec!["authorization".into()]),
            ]
        );
        let mr = Mod {
            platform: "mr".into(),
            hash: "A".repeat(40),
            download_urls: vec!["https://cdn.modrinth.com/data/x/a.jar".into()],
            ..m.clone()
        };
        let s = sources(&mr, None, true, &Fake).unwrap();
        assert_eq!(s[1].url, "https://mod.mcimirror.top/data/x/a.jar");
        assert!(s[1].headers.contains_key("x-api-key"), "Kotlin applies the key rule to every host");
        assert!(s[2].headers.is_empty());
        assert_eq!(mr.digest().unwrap().algorithm, Algorithm::Sha1);
        assert!(m.trusted_curse_forge());
        assert!(!Mod { download_urls: vec![], ..m }.trusted_curse_forge());
    }
}
