use serde::Deserialize;
#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum HostStatus {
    Playable,
    Started,
    Stopped,
    #[default]
    #[serde(other)]
    Other,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostBrief {
    #[serde(rename = "_id")]
    pub id: String,
    pub name: String,
    pub modpack_name: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub owner_id: Option<String>,
    #[serde(default)]
    pub playable: bool,
    #[serde(default)]
    pub online_player_ids: Vec<String>,
    #[serde(default)]
    pub status: HostStatus,
}
#[derive(Clone, Debug, Deserialize)]
pub struct ModpackBrief {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub icon: Option<String>,
}
#[derive(Deserialize)]
pub struct ModpackPage {
    pub items: Vec<ModpackBrief>,
}
#[derive(Clone, Deserialize)]
pub struct PlayerInfo {
    pub name: String,
    #[serde(default)]
    pub cloth: Option<Cloth>,
}
#[derive(Clone, Deserialize)]
pub struct Cloth {
    pub skin: Option<String>,
    #[serde(default)]
    pub cape: Option<String>,
    #[serde(default, rename = "isSlim")]
    pub is_slim: Option<bool>,
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn account_appearance_fields_keep_explicit_classic_and_optional_cape() {
        let player: PlayerInfo = serde_json::from_str(
            r#"{"name":"测试","cloth":{"skin":"skin","cape":"cape","isSlim":false}}"#,
        )
        .unwrap();
        let cloth = player.cloth.unwrap();
        assert_eq!(cloth.is_slim, Some(false));
        assert_eq!(cloth.cape.as_deref(), Some("cape"));
        let player: PlayerInfo =
            serde_json::from_str(r#"{"name":"测试","cloth":{"skin":null}}"#).unwrap();
        let cloth = player.cloth.unwrap();
        assert!(cloth.is_slim.is_none() && cloth.cape.is_none());
    }
    #[test]
    fn host_optional_and_unknown_status() {
        let h: HostBrief = serde_json::from_str(r#"{"_id":"abc","name":"房间","modpackName":"包","packVer":"1","version":2,"port":42,"status":"FUTURE"}"#).unwrap();
        assert_eq!(h.status, HostStatus::Other);
        assert!(!h.playable);
        assert!(h.icon_url.is_none());
        let h: HostBrief =
            serde_json::from_str(r#"{"_id":"abc","name":"房间","modpackName":"包"}"#).unwrap();
        assert!(h.online_player_ids.is_empty());
    }
    #[test]
    fn samples() {
        let h: HostBrief = serde_json::from_str(r#"{"_id":"6ae30c","name":"KDFT","intro":null,"iconUrl":"https://example.org/a.png","ownerId":"6ade15","modpackName":"FTB空岛飞艇2","packVer":"1.13.0","version":2,"port":55981,"playable":false,"isMember":false,"role":null,"onlinePlayerIds":["p"],"status":"STOPPED"}"#).unwrap();
        assert_eq!(h.status, HostStatus::Stopped);
        let m: ModpackBrief = serde_json::from_str(r#"{"id":"6abde2","name":"无限遗产2私货","authorId":"68fdb0","authorName":"davickk","modCount":393,"fileSize":78182782,"playCount":64,"lastUpdatedTime":1792899698303,"icon":"https://example.org/a.jpeg","info":"","categories":["MEDIUM"]}"#).unwrap();
        assert_eq!(m.id, "6abde2");
    }
}
