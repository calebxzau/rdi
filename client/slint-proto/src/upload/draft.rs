//! What the uploader types: Kotlin `ModpackUploadDraft`, `validateModpackUploadDraft`, the name
//! and version checks of `submitUpload`, `validateIconUrlAddress` and `validateIconUrl`.
use anyhow::{Result, bail};

/// `MODPACK_INFO_MIN_CHARACTERS` / `MODPACK_INFO_MAX_CHARACTERS`, counted in code points.
pub const INFO_MIN: usize = 10;
pub const INFO_MAX: usize = 100;
/// `Modpack.MAX_CATEGORY_COUNT`.
pub const MAX_CATEGORIES: usize = 4;

/// `Modpack.Category`: the enum names the server takes, with the Chinese label (its Nerd Font
/// glyph left out).
pub const CATEGORIES: &[(&str, &str)] = &[
    ("LARGE", "大型"),
    ("MEDIUM", "中型"),
    ("SMALL", "小型"),
    ("MAGIC", "魔法"),
    ("HARDCORE", "硬核"),
    ("SKYBLOCK", "空岛"),
    ("VANILLA", "纯净"),
    ("TECH", "科技"),
    ("STORY", "剧情"),
    ("ADVENTURE", "探险"),
    ("CASUAL", "休闲"),
    ("MANAGE", "经营"),
    ("APOCALYPSE", "末日"),
    ("WAR", "战争"),
    ("HEAVY_MOD", "魔改"),
    ("RPG", "RPG"),
    ("COMBAT", "战斗"),
    ("LIGHT_MOD", "轻量"),
    ("OPTIMIZE", "优化"),
    ("OTHER", "其他"),
];

/// `MODPACK_ICON_ALLOWED_DOMAINS`.
const ICON_DOMAINS: &[&str] = &[
    "forgecdn.net",
    "curseforge.com",
    "modrinth.com",
    "xyeidc.com",
    "bbsmc.net",
    "mcmod.cn",
    "candycake.cloud",
    "hdslb.com",
    "bilibili.com",
];

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Draft {
    pub name: String,
    pub version: String,
    /// Enum names, as `CATEGORIES` lists them.
    pub categories: Vec<String>,
    pub icon_url: String,
    pub source_url: String,
    pub info: String,
}

/// `updateVersionName`: spaces become underscores as the uploader types.
pub fn version_name(text: &str) -> String {
    text.replace(' ', "_")
}

/// `Modpack.normalizeCategories`: distinct, at most four, in the given order.
pub fn normalize_categories(categories: &[String]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for category in categories {
        if !out.contains(category) {
            out.push(category.clone());
        }
    }
    out.truncate(MAX_CATEGORIES);
    out
}

/// Category names that are not `Modpack.Category` entries.
pub fn unknown_categories(categories: &[String]) -> Vec<String> {
    categories.iter().filter(|c| !CATEGORIES.iter().any(|(name, _)| name == c)).cloned().collect()
}

/// Every reason `submitUpload` would refuse the draft, in its order: name, version, then the
/// field errors of a new pack (an update keeps the pack's own fields, so it has none).
pub fn problems(draft: &Draft, update: bool) -> Vec<String> {
    let mut out = Vec::new();
    if draft.name.trim().is_empty() {
        out.push("整合包名称不能为空".to_owned());
    }
    if draft.version.trim().is_empty() {
        out.push("版本号不能为空".to_owned());
    }
    if update {
        return out;
    }
    let info = draft.info.trim();
    let length = info.chars().count();
    if info.is_empty() {
        out.push("简介不能为空".into());
    } else if length < INFO_MIN {
        out.push(format!("简介至少需要{INFO_MIN}个字符"));
    } else if length > INFO_MAX {
        out.push(format!("简介最多{INFO_MAX}个字符"));
    }
    let icon = draft.icon_url.trim();
    if icon.is_empty() {
        out.push("图标链接不能为空".into());
    } else if let Err(error) = icon_address(icon) {
        out.push(error.to_string());
    }
    let unknown = unknown_categories(&draft.categories);
    if !unknown.is_empty() {
        // Kotlin's enum cannot hold an unknown value; the agent gets the names to fix.
        out.push(format!("未知的分类：{}", unknown.join("、")));
    }
    let mut distinct = draft.categories.clone();
    distinct.sort();
    distinct.dedup();
    if draft.categories.is_empty() {
        out.push("至少选择1个分类".into());
    } else if distinct.len() > MAX_CATEGORIES {
        out.push(format!("分类最多选择{MAX_CATEGORIES}个"));
    }
    out
}

/// `validateIconUrlAddress`: an http(s) address on one of the pack publishing sites.
pub fn icon_address(url: &str) -> Result<()> {
    let url = url.trim();
    if url.is_empty() {
        return Ok(());
    }
    let Ok(parsed) = reqwest::Url::parse(url) else {
        // Java's URI takes a text without a scheme as a relative address, which Kotlin then
        // refuses for its scheme; only a malformed absolute address is 链接无效.
        if !url.contains("://") {
            bail!("链接必须以http或https开头");
        }
        bail!("链接无效");
    };
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        bail!("链接必须以http或https开头");
    }
    let Some(host) = parsed.host_str().map(str::to_lowercase) else {
        bail!("图标链接无效");
    };
    if !ICON_DOMAINS.iter().any(|d| host == *d || host.ends_with(&format!(".{d}"))) {
        bail!("只接受整合包发布网页的图标链接");
    }
    Ok(())
}

/// `validateIconUrl`: the address rule, then the server must say the link is an image (HEAD,
/// or a one-byte GET when HEAD gives no type).
pub async fn icon_is_image(http: &reqwest::Client, url: &str) -> Result<()> {
    let url = url.trim();
    if url.is_empty() {
        return Ok(());
    }
    icon_address(url)?;
    let content_type = |response: reqwest::Response| {
        response.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).map(str::to_owned)
    };
    let head = http.head(url).header(reqwest::header::ACCEPT_ENCODING, "identity").send().await.ok().and_then(content_type);
    let kind = match head {
        Some(kind) => Some(kind),
        None => http
            .get(url)
            .header(reqwest::header::ACCEPT_ENCODING, "identity")
            .header(reqwest::header::RANGE, "bytes=0-0")
            .send()
            .await
            .ok()
            .and_then(content_type),
    };
    let Some(kind) = kind else {
        bail!("无法获取图标类型");
    };
    if !kind.to_lowercase().starts_with("image/") {
        bail!("图标链接不是图片");
    }
    Ok(())
}
