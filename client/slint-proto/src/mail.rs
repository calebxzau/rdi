//! U4 batch b2: 信箱 (Compose MailScreen, MailDetailScreen). No Slint types.
use crate::api::Client;
use anyhow::Result;
use chrono::{DateTime, Datelike, FixedOffset, Timelike};
use serde::Deserialize;
use serde_json::{Value, json};

/// Kotlin `Mail.Vo`.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Brief {
    pub id: String,
    #[serde(default)]
    pub sender_name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub intro: String,
    #[serde(default)]
    pub unread: bool,
}

/// Kotlin `Mail`.
#[derive(Clone, Debug, Deserialize)]
pub struct Mail {
    #[serde(rename = "_id")]
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub content: String,
}

pub async fn list(api: &Client) -> Result<Vec<Brief>> {
    api.get("/mail").await
}

pub async fn detail(api: &Client, id: &str) -> Result<Mail> {
    api.get(&format!("/mail/{id}")).await
}

pub async fn delete(api: &Client, id: &str) -> Result<()> {
    api.server().delete::<Value>(&format!("/mail/{id}"), None).await?;
    Ok(())
}

pub async fn delete_many(api: &Client, ids: &[String]) -> Result<()> {
    api.server().delete::<Value>("/mail", Some(&json!(ids))).await?;
    Ok(())
}

/// The creation time of an ObjectId, in seconds.
pub fn id_seconds(id: &str) -> i64 {
    id.get(..8).and_then(|h| i64::from_str_radix(h, 16).ok()).unwrap_or_default()
}

fn local(seconds: i64, offset: FixedOffset) -> DateTime<FixedOffset> {
    DateTime::from_timestamp(seconds, 0).unwrap_or_default().with_timezone(&offset)
}

/// Kotlin `Long.toFriendlyDateTime`: 今天9:05, 昨天, 前天, 周三, 3月5日, 2025年3月5日.
pub fn friendly(seconds: i64, now: i64, offset: FixedOffset) -> String {
    if seconds <= 0 {
        return "--".into();
    }
    let target = local(seconds, offset);
    let today = local(now, offset);
    let days = (today.date_naive() - target.date_naive()).num_days();
    let clock = format!("{}:{:02}", target.hour(), target.minute());
    const WEEK: [&str; 7] = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
    match days {
        0 => format!("今天{clock}"),
        1 => format!("昨天{clock}"),
        2 => format!("前天{clock}"),
        -1 => format!("明天{clock}"),
        -2 => format!("后天{clock}"),
        -6..=6 => format!("{}{clock}", WEEK[target.weekday().num_days_from_monday() as usize]),
        _ if target.year() == today.year() => format!("{}月{}日{clock}", target.month(), target.day()),
        _ => format!("{}年{}月{}日{clock}", target.year(), target.month(), target.day()),
    }
}

/// Kotlin `secondsToHumanDateTime`: `yyyy-MM-dd HH:mm:ss`.
pub fn full_time(seconds: i64, offset: FixedOffset) -> String {
    local(seconds, offset).format("%Y-%m-%d %H:%M:%S").to_string()
}

/// MailDetailPanel drops leading blank lines.
pub fn body(content: &str) -> String {
    content.trim_start_matches(['\n', '\r']).replace("\r\n", "\n")
}

pub fn local_offset() -> FixedOffset {
    *chrono::Local::now().offset()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn at(y: i32, m: u32, d: u32, h: u32, min: u32) -> i64 {
        chrono::NaiveDate::from_ymd_opt(y, m, d).unwrap().and_hms_opt(h, min, 0).unwrap()
            .and_local_timezone(zone()).unwrap().timestamp()
    }
    fn zone() -> FixedOffset {
        FixedOffset::east_opt(8 * 3600).unwrap()
    }
    #[test]
    fn friendly_times_follow_kotlin() {
        let now = at(2026, 11, 23, 15, 0); // a Monday
        assert_eq!(friendly(at(2026, 11, 23, 9, 5), now, zone()), "今天9:05");
        assert_eq!(friendly(at(2026, 11, 22, 23, 0), now, zone()), "昨天23:00");
        assert_eq!(friendly(at(2026, 11, 21, 8, 0), now, zone()), "前天8:00");
        assert_eq!(friendly(at(2026, 11, 19, 8, 0), now, zone()), "周四8:00");
        assert_eq!(friendly(at(2026, 3, 5, 8, 0), now, zone()), "3月5日8:00");
        assert_eq!(friendly(at(2025, 3, 5, 8, 0), now, zone()), "2025年3月5日8:00");
        assert_eq!(friendly(0, now, zone()), "--");
        assert_eq!(full_time(at(2026, 3, 5, 8, 7), zone()), "2026-03-05 08:07:00");
    }
    #[test]
    fn ids_bodies_and_json() {
        assert_eq!(id_seconds("6650a1b2c3d4e5f601234567"), 0x6650a1b2);
        assert_eq!(id_seconds("x"), 0);
        assert_eq!(body("\r\n\nhello\r\nworld"), "hello\nworld");
        let brief: Vec<Brief> = serde_json::from_value(json!([{"id":"6650a1b2c3d4e5f601234567","senderName":"RDI","title":"t","intro":"i","unread":true}])).unwrap();
        assert!(brief[0].unread && brief[0].sender_name == "RDI");
        let mail: Mail = serde_json::from_value(json!({"_id":"6650a1b2c3d4e5f601234567","senderId":"x","receiverId":"y","title":"t","content":"c","unread":false})).unwrap();
        assert_eq!(mail.content, "c");
    }
}
