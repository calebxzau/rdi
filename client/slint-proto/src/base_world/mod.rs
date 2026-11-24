//! U4e3: 地图模板 (Kotlin `BaseWorldApi`, `BaseWorldUploadService` and the rules of
//! `BaseWorldViewModels.kt`). No Slint types here; `base_world_ui.rs` wires the pages.
pub mod api;
pub mod upload;
#[cfg(test)]
mod tests;

use serde::Deserialize;
use sha1::{Digest, Sha1};
use std::path::Path;

/// `BaseWorld.MaxSize`: the largest world folder and the largest archive.
pub const MAX_SIZE: u64 = 2 * 1024 * 1024 * 1024;

/// Kotlin `BaseWorld`; ids are UUID strings.
#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BaseWorld {
    pub id: String,
    #[serde(default)]
    pub owner_id: String,
    pub name: String,
    pub level_type: String,
    #[serde(default)]
    pub generator_settings: Option<String>,
    #[serde(default)]
    pub size: u64,
}

/// The terrain cards of 创建地图模板 (`BaseWorldLevelTypeChoice`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LevelChoice {
    Normal,
    Flat,
    Skyblock,
    Custom,
}
impl LevelChoice {
    pub fn index(self) -> i32 {
        self as i32
    }
    pub fn from_index(index: i32) -> Option<Self> {
        [Self::Normal, Self::Flat, Self::Skyblock, Self::Custom].get(usize::try_from(index).ok()?).copied()
    }
    /// The level type a preset card stands for; `Custom` has none of its own.
    pub fn preset(self) -> Option<&'static str> {
        match self {
            Self::Normal => Some("minecraft:normal"),
            Self::Flat => Some("minecraft:flat"),
            Self::Skyblock => Some("skyblockbuilder:skyblock"),
            Self::Custom => None,
        }
    }
}

fn name_char(c: char) -> bool {
    let cp = c as u32;
    matches!(c, ' ' | '.' | '_' | '-')
        || c.is_ascii_alphanumeric()
        || [
            (0x4E00, 0x9FFF),
            (0x3400, 0x4DBF),
            (0x20000, 0x2A6DF),
            (0x2A700, 0x2B73F),
            (0x2B740, 0x2B81F),
            (0x2B820, 0x2CEAF),
            (0xF900, 0xFAFF),
            (0x2F800, 0x2FA1F),
        ]
        .iter()
        .any(|&(start, end)| (start..=end).contains(&cp))
}

/// `validateBaseWorldName`: `validateModpackName` with 整合包 read as 地图模板.
pub fn validate_name(name: &str) -> Result<(), String> {
    let invalid = "地图模板名称只能包含字母、数字、汉字、空格或._-";
    if name.is_empty() || name.starts_with(' ') || name.ends_with(' ') || !name.chars().all(name_char) {
        return Err(invalid.into());
    }
    let len = crate::account::validate::display_length(name);
    if !(3..=32).contains(&len) {
        return Err(format!("地图模板名称长度需在3~32个字符，当前为{len}（一个汉字算两个）"));
    }
    Ok(())
}

/// `validateBaseWorldLevelType`: `foo:bar` as `^[a-z0-9_.-]+:[a-z0-9/._-]+$` after trimming.
pub fn validate_level_type(value: &str) -> Result<(), String> {
    let ok = value.trim().split_once(':').is_some_and(|(space, path)| {
        !space.is_empty()
            && !path.is_empty()
            && space.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_.-".contains(&b))
            && path.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"/._-".contains(&b))
    });
    if ok { Ok(()) } else { Err("地形类型必须是foo:bar格式".into()) }
}

/// `validateBaseWorldGeneratorSettings`: blank, or a JSON object.
pub fn validate_generator_settings(value: &str) -> Result<(), String> {
    if value.trim().is_empty() {
        return Ok(());
    }
    match serde_json::from_str::<serde_json::Value>(value) {
        Ok(serde_json::Value::Object(_)) => Ok(()),
        _ => Err("生成器设置必须是JSON对象".into()),
    }
}

/// `validateBaseWorldDirectory`: a Minecraft world folder with a non-empty `level.dat`.
pub fn validate_directory(directory: &Path) -> Result<(), String> {
    let meta = std::fs::metadata(directory).map_err(|_| "选择的路径不存在".to_owned())?;
    if !meta.is_dir() {
        return Err("请选择地图文件夹".into());
    }
    let level = std::fs::metadata(directory.join("level.dat")).ok().filter(|m| m.is_file()).ok_or("不是有效的MC地图文件夹")?;
    if level.len() == 0 {
        return Err("没有成功读取地图摘要文件".into());
    }
    Ok(())
}

fn hex_sha1(bytes: &[u8]) -> String {
    Sha1::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// `baseWorldUploadTaskKey`: Windows paths compare without case.
pub fn upload_task_key(owner_id: &str, directory: &Path) -> String {
    let path = std::path::absolute(directory).unwrap_or_else(|_| directory.to_owned());
    let path = path.to_string_lossy();
    let path = if cfg!(windows) { path.to_lowercase() } else { path.into_owned() };
    format!("baseworld-upload:{}:{path}", owner_id.trim())
}

/// `baseWorldGeneratedTaskKey`: SHA-1 of name, level type and generator settings.
pub fn generated_task_key(owner_id: &str, name: &str, level_type: &str, generator_settings: Option<&str>) -> String {
    let joined = [name.trim(), level_type.trim(), generator_settings.map(str::trim).unwrap_or_default()].join("\u{0}");
    format!("baseworld-generated:{}:{}", owner_id.trim(), hex_sha1(joined.as_bytes()))
}

/// `formatBaseWorldUploadTime`: the UUIDv7 timestamp as 「yyyy年MM月dd日 HH:mm:ss」 local time.
pub fn upload_time(id: &str) -> String {
    let Ok(uuid) = uuid::Uuid::parse_str(id) else { return String::new() };
    let bytes = uuid.as_bytes();
    let millis = bytes[..6].iter().fold(0i64, |acc, b| (acc << 8) | i64::from(*b));
    chrono::DateTime::from_timestamp_millis(millis)
        .map(|t| t.with_timezone(&chrono::Local).format("%Y年%m月%d日 %H:%M:%S").to_string())
        .unwrap_or_default()
}

/// 生成器设置 in the details dialog.
pub fn generator_text(settings: Option<&str>) -> String {
    match settings {
        None => "未设置".into(),
        Some("") => "空字符串".into(),
        Some(text) => text.into(),
    }
}
