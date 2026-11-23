//! Room DTOs as Kotlin `Host`, `Role`, `BaseWorld` and `RAccount.Dto` put them on the wire.
//! The server omits fields equal to their Kotlin defaults, so every defaulted field has a serde default.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Kotlin `Role`; the level orders members (owner first).
#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Role {
    Owner,
    Admin,
    #[default]
    Member,
    #[serde(other)]
    Guest,
}
impl Role {
    pub fn level(self) -> i32 {
        match self {
            Role::Owner => 0,
            Role::Admin => 1,
            Role::Member => 2,
            Role::Guest => 10,
        }
    }
    /// The path segment of `PUT /host/{id}/member/{uid}/role/{role}`.
    pub fn wire(self) -> &'static str {
        match self {
            Role::Owner => "OWNER",
            Role::Admin => "ADMIN",
            Role::Member => "MEMBER",
            Role::Guest => "GUEST",
        }
    }
    /// `HostMembersScreen.roleLabel`.
    pub fn label(self) -> &'static str {
        match self {
            Role::Owner => "所有者",
            Role::Admin => "管理员",
            _ => "成员",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct Member {
    pub id: String,
    #[serde(default)]
    pub role: Role,
}

/// The `Modpack.BriefVo` inside a room detail.
#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct RoomPack {
    pub id: String,
    pub name: String,
    pub mc_ver: String,
    pub modloader: String,
    pub icon: Option<String>,
}
impl Default for RoomPack {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: "未知整合包".into(),
            mc_ver: "V211".into(),
            modloader: "neoforge".into(),
            icon: None,
        }
    }
}

/// Kotlin `Host.DetailVo`; `members` is empty for players outside the room.
#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HostDetail {
    #[serde(rename = "_id")]
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub intro: Option<String>,
    #[serde(default)]
    pub icon_url: Option<String>,
    pub owner_id: String,
    #[serde(default)]
    pub modpack: RoomPack,
    pub pack_ver: String,
    /// Room generation; 2 and later always delete their save with the room.
    pub version: i32,
    #[serde(default)]
    pub world_id: Option<String>,
    pub difficulty: i32,
    pub game_mode: i32,
    pub level_type: String,
    #[serde(default)]
    pub game_rules: BTreeMap<String, String>,
    #[serde(default)]
    pub whitelist: bool,
    #[serde(default)]
    pub allow_cheats: bool,
    #[serde(default)]
    pub members: Vec<Member>,
    /// The room's own mods (附加Mod) and the version mods it turned off (停用).
    #[serde(default)]
    pub extra_mods: Vec<crate::pack_install::mods::Mod>,
    #[serde(default)]
    pub disabled_mods: Vec<crate::pack_install::mods::Mod>,
}

/// Kotlin `BaseWorld` (map template). Ids are UUID strings.
#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BaseWorld {
    pub id: String,
    #[serde(default)]
    pub owner_id: String,
    pub name: String,
    pub level_type: String,
    #[serde(default)]
    pub size: u64,
}

/// `Modpack.Version.BaseWorldBinding`.
#[derive(Clone, Debug, Deserialize, PartialEq)]
pub struct BaseWorldBinding {
    pub id: String,
    #[serde(default)]
    pub required: bool,
}

/// `RAccount.Dto` from `GET /player/by-qq/{qq}`.
#[derive(Clone, Debug, Deserialize, PartialEq)]
pub struct Friend {
    pub id: String,
    pub name: String,
}

/// Kotlin `Host.CreateDto`, serialized as `serdesJson` does (defaults omitted).
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CreateRequest {
    pub name: String,
    pub modpack_id: String,
    pub pack_ver: String,
    pub difficulty: i32,
    pub game_mode: i32,
    pub level_type: String,
    pub allow_cheats: bool,
    pub whitelist: bool,
    pub game_rules: BTreeMap<String, String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub base_world_id: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub initial_member_ids: Vec<String>,
}

/// Kotlin `Host.OptionsDto` as the edit form sends it: never `modpackId` or `packVer`.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct OptionsRequest {
    pub name: String,
    pub difficulty: i32,
    pub game_mode: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub level_type: Option<String>,
    pub whitelist: bool,
    pub allow_cheats: bool,
    pub game_rules: BTreeMap<String, String>,
}

/// Kotlin `Host.DeleteDto`; `false` is its default, so Kotlin sends `{}` for it.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DeleteRequest {
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub delete_world: bool,
}

/// The Minecraft minor version of a Kotlin `McVersion` name such as `V201` (1.20.1 → 20).
pub fn mc_minor(mc_ver: &str) -> Option<u32> {
    crate::local_packs::mc_version_name(mc_ver)
        .unwrap_or(mc_ver)
        .split('.')
        .nth(1)?
        .parse()
        .ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detail_with_omitted_defaults_and_unknown_role() {
        let detail: HostDetail = serde_json::from_str(
            r#"{"_id":"66a000000000000000000001","name":"房间","ownerId":"66a000000000000000000002",
            "modpack":{"id":"66a000000000000000000003","mcVer":"V201"},"packVer":"1.0","version":2,
            "port":1,"difficulty":3,"gameMode":0,"levelType":"minecraft:normal",
            "members":[{"id":"66a000000000000000000002","role":"OWNER"},{"id":"x","role":"FUTURE"}]}"#,
        )
        .unwrap();
        assert_eq!(detail.modpack.name, "未知整合包");
        assert_eq!(detail.modpack.modloader, "neoforge");
        assert!(!detail.whitelist && !detail.allow_cheats && detail.world_id.is_none());
        assert_eq!(detail.members[1].role, Role::Guest);
        let world: BaseWorld = serde_json::from_str(
            r#"{"id":"0193d3a4-1111-7000-8000-000000000001","ownerId":"0193d3a4-1111-7000-8000-000000000002",
            "name":"模板","levelType":"minecraft:flat","generatorSettings":null,"size":1024}"#,
        )
        .unwrap();
        assert_eq!(world.size, 1024);
    }

    #[test]
    fn minor_versions_from_kotlin_names() {
        assert_eq!(mc_minor("V201"), Some(20));
        assert_eq!(mc_minor("V071"), Some(7));
        assert_eq!(mc_minor("1.16.5"), Some(16));
        assert_eq!(mc_minor(""), None);
    }
}
