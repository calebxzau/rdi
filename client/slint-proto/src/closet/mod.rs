//! U4e2: what the wardrobe needs beyond browsing (Kotlin `SkinService`, `BlessingSkinClient.resolve`
//! and `MojangApi`): the texture behind a card, the clothes a preview shows, applying a texture
//! and importing a Mojang player's skin or cape. No Slint types; `closet_ui.rs` wires the pages.
#[cfg(test)]
mod tests;

use crate::api::model::Cloth;
use anyhow::{Context, Result, anyhow, bail, ensure};
use base64::Engine;
use serde::Deserialize;
use std::time::Duration;

pub const LITTLE_SKIN: &str = "https://littleskin.cn";
const MOJANG_NAMES: &str = "https://api.mojang.com/users/profiles/minecraft/";
const MOJANG_PROFILES: &str = "https://sessionserver.mojang.com/session/minecraft/profile/";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);

/// `BlessingTextureType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextureType {
    Steve,
    Alex,
    Cape,
}

/// `ResolvedBlessingTexture`, the parts the wardrobe uses.
#[derive(Clone, Debug, PartialEq)]
pub struct Texture {
    pub id: i32,
    pub name: String,
    pub kind: TextureType,
    pub url: String,
}

#[derive(Deserialize)]
struct TextureDto {
    tid: i32,
    name: String,
    #[serde(rename = "type")]
    kind: String,
    hash: String,
}

/// The skin, cape and model a player wears (`RAccount.Cloth`); `None` URLs are empty.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Clothes {
    pub skin: String,
    pub cape: Option<String>,
    pub slim: bool,
}

impl Clothes {
    pub fn from_cloth(cloth: Option<&Cloth>) -> Self {
        let text = |v: &Option<String>| v.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        Self {
            skin: cloth.and_then(|c| text(&c.skin)).unwrap_or_default(),
            cape: cloth.and_then(|c| text(&c.cape)),
            slim: cloth.and_then(|c| c.is_slim).unwrap_or(false),
        }
    }
    pub fn to_cloth(&self) -> Cloth {
        Cloth { skin: Some(self.skin.clone()).filter(|s| !s.is_empty()), cape: self.cape.clone(), is_slim: Some(self.slim) }
    }
}

/// `BlessingSkinClient.resolve`: `GET /texture/{tid}`; the file is `/textures/{hash}`.
pub async fn resolve(http: &reqwest::Client, base: &str, id: i32) -> Result<Texture> {
    ensure!(id > 0, "Blessing Skin材质ID必须大于0");
    let base = base.trim_end_matches('/');
    let response = http
        .get(format!("{base}/texture/{id}"))
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .context("请求皮肤库失败")?;
    ensure!(response.status().is_success(), "皮肤库返回错误：HTTP{}", response.status().as_u16());
    let dto: TextureDto = response.json().await.map_err(|_| anyhow!("无法解析Blessing Skin材质响应"))?;
    let kind = match dto.kind.to_lowercase().as_str() {
        "steve" => TextureType::Steve,
        "alex" => TextureType::Alex,
        "cape" => TextureType::Cape,
        other => bail!("未知的Blessing Skin材质类型: {other}"),
    };
    ensure!(!dto.hash.trim().is_empty(), "无法解析Blessing Skin材质响应");
    Ok(Texture { id: dto.tid, name: dto.name, kind, url: format!("{base}/textures/{}", dto.hash.trim()) })
}

/// `resolvePreviewCloth` and `applyBlessingTexture`: a cape keeps the current skin, a skin keeps
/// the current cape.
pub fn wearing(current: &Clothes, texture: &Texture) -> Clothes {
    match texture.kind {
        TextureType::Cape => Clothes { cape: Some(texture.url.clone()), ..current.clone() },
        kind => Clothes { skin: texture.url.clone(), slim: kind == TextureType::Alex, cape: current.cape.clone() },
    }
}

/// The textures of a Mojang profile (`MojangTexturesPayload.textures`).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct MojangTextures {
    pub skin: Option<(String, bool)>,
    pub cape: Option<String>,
}

/// `importMojangSkin` with the owner's D2 rule: each unchecked part keeps what the player wears
/// now, and only a checked part must exist. Compose replaces the skin and drops the cape even
/// when they are unchecked.
pub fn imported(current: &Clothes, name: &str, textures: &MojangTextures, skin: bool, cape: bool) -> Result<Clothes> {
    let mut out = current.clone();
    // The server needs a skin; a player without one takes the Mojang skin with a cape import.
    let skin = skin || (out.skin.is_empty() && textures.skin.is_some());
    if skin {
        let (url, slim) = textures.skin.clone().ok_or_else(|| anyhow!("玩家{name}没有设置过皮肤"))?;
        out.skin = url;
        out.slim = slim;
    }
    if cape {
        out.cape = Some(textures.cape.clone().ok_or_else(|| anyhow!("玩家{name}没有披风"))?);
    }
    Ok(out)
}

/// `importMojangSkin`'s input checks.
pub fn check_import(name: &str, skin: bool, cape: bool) -> Result<String> {
    let name = name.trim();
    ensure!(!name.is_empty(), "请输入玩家名");
    ensure!(skin || cape, "请选择皮肤或披风");
    Ok(name.to_owned())
}

#[derive(Deserialize)]
struct IdName {
    id: String,
}
#[derive(Deserialize)]
struct Profile {
    #[serde(default)]
    properties: Vec<Property>,
}
#[derive(Deserialize)]
struct Property {
    name: String,
    value: String,
}

/// Decodes the base64 `textures` property of a session profile.
pub fn profile_textures(profile_json: &[u8]) -> Result<MojangTextures> {
    let profile: Profile = serde_json::from_slice(profile_json).context("无法读取正版玩家资料")?;
    let Some(property) = profile.properties.iter().find(|p| p.name.eq_ignore_ascii_case("textures")) else {
        return Ok(MojangTextures::default());
    };
    let bytes = base64::engine::general_purpose::STANDARD.decode(property.value.trim()).context("无法读取正版玩家资料")?;
    let payload: serde_json::Value = serde_json::from_slice(&bytes).context("无法读取正版玩家资料")?;
    let textures = &payload["textures"];
    let url = |key: &str| textures[key]["url"].as_str().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
    let slim = textures["SKIN"]["metadata"]["model"].as_str().is_some_and(|m| m.eq_ignore_ascii_case("slim"));
    Ok(MojangTextures { skin: url("SKIN").map(|u| (u, slim)), cape: url("CAPE") })
}

/// `MojangApi.getUuidFromName` then `getProfile`: the player's textures.
pub async fn mojang_textures(http: &reqwest::Client, names_base: Option<&str>, profiles_base: Option<&str>, name: &str) -> Result<MojangTextures> {
    let names = names_base.unwrap_or(MOJANG_NAMES);
    let response = http
        .get(format!("{names}{}", crate::base_world::api::segment(name)))
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .map_err(|_| anyhow!("无法连接正版服务器，请检查网络"))?;
    if matches!(response.status().as_u16(), 204 | 404) {
        bail!("玩家{name}不存在");
    }
    ensure!(response.status().is_success(), "正版服务器返回错误：HTTP{}", response.status().as_u16());
    let id: IdName = response.json().await.map_err(|_| anyhow!("玩家{name}不存在"))?;
    let profiles = profiles_base.unwrap_or(MOJANG_PROFILES);
    let response = http
        .get(format!("{profiles}{}", id.id.trim()))
        .timeout(REQUEST_TIMEOUT)
        .send()
        .await
        .map_err(|_| anyhow!("无法连接正版服务器，请检查网络"))?;
    ensure!(response.status().is_success(), "正版服务器返回错误：HTTP{}", response.status().as_u16());
    profile_textures(&response.bytes().await.map_err(|_| anyhow!("无法连接正版服务器，请检查网络"))?)
}

/// `PlayerService.setCloth`: `POST player/skin` with `isSlim`, `skin` and an optional `cape`.
pub async fn set_clothes(api: &crate::api::Client, clothes: &Clothes) -> Result<()> {
    let mut body = serde_json::json!({ "isSlim": clothes.slim.to_string(), "skin": clothes.skin });
    if let Some(cape) = &clothes.cape {
        body["cape"] = serde_json::Value::String(cape.clone());
    }
    api.post::<serde_json::Value>("/player/skin", &body).await.map(|_| ())
}
