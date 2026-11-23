//! 回档 (U4 batch c2a): Compose `HostRollbackScreen` and `HostRollbackViewModel`, owner only,
//! 1.20.1 Forge rooms only. DTOs follow `common/model/.../HostRollback.kt`.
use super::requests::segment;
use crate::api::server::ServerApi;
use anyhow::Result;
use chrono::{DateTime, Datelike, FixedOffset, NaiveDate};
use serde::Deserialize;

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
pub enum PointKind {
    #[default]
    Auto,
    BeforeRollback,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Point {
    pub id: String,
    pub kind: PointKind,
    pub created_at: i64,
    pub chunk_count: i64,
    pub player_count: i64,
    pub changed_chunks: i64,
    pub changed_players: i64,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
pub enum RequestState {
    #[default]
    Requested,
    Applying,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Pending {
    pub point_id: String,
    pub requested_at: i64,
    pub state: RequestState,
    pub abandoned: bool,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
pub enum Outcome {
    #[default]
    Done,
    Failed,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct LastResult {
    pub point_id: Option<String>,
    pub before_point_id: Option<String>,
    pub outcome: Outcome,
    pub message: Option<String>,
    pub finished_at: i64,
}

/// `HostRollbackStatusVo`; points are newest first.
#[derive(Clone, Debug, Default, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct Status {
    pub points: Vec<Point>,
    pub pending: Option<Pending>,
    pub last_result: Option<LastResult>,
    pub used_bytes: i64,
    pub max_bytes: i64,
    pub host_running: bool,
}

#[derive(Clone, Copy, Debug, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Created {
    pub stopped_host: bool,
}

pub async fn status(api: &ServerApi, host: &str) -> Result<Status> {
    api.get(&format!("/host/{}/rollback", segment(host)?)).await
}
pub async fn request(api: &ServerApi, host: &str, point: &str) -> Result<Created> {
    api.post(&format!("/host/{}/rollback", segment(host)?), &serde_json::json!({ "pointId": point })).await
}
pub async fn cancel(api: &ServerApi, host: &str) -> Result<()> {
    let _: serde_json::Value = api.delete(&format!("/host/{}/rollback", segment(host)?), None).await?;
    Ok(())
}

/// The notice after a successful request.
pub fn requested_notice(created: Created) -> &'static str {
    if created.stopped_host { "房间正在关闭，下次启动房间时会回档" } else { "已设置回档，下次启动房间时生效" }
}
pub const CANCELLED_NOTICE: &str = "已取消回档";
/// A waiting rollback on a running room is followed quietly at this interval.
pub const PENDING_REFRESH: std::time::Duration = std::time::Duration::from_secs(10);
pub fn needs_refresh(status: &Status) -> bool {
    status.pending.is_some() && status.host_running
}

/// The one notice above the list (`HostRollbackBanner`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Banner {
    Pending { point: String, can_cancel: bool },
    Unfinished { point: String, can_cancel: bool },
    Abandoned,
    Done { point: Option<String>, undo: Option<String> },
    Failed(String),
}

pub fn banner(status: &Status) -> Option<Banner> {
    if let Some(p) = &status.pending {
        let can_cancel = !status.host_running;
        return Some(match (p.state, p.abandoned) {
            (RequestState::Requested, _) => Banner::Pending { point: p.point_id.clone(), can_cancel },
            (_, true) => Banner::Abandoned,
            _ => Banner::Unfinished { point: p.point_id.clone(), can_cancel },
        });
    }
    let result = status.last_result.as_ref()?;
    Some(match result.outcome {
        Outcome::Done => Banner::Done {
            point: result.point_id.clone(),
            undo: result.before_point_id.clone().filter(|b| status.points.iter().any(|p| &p.id == b)),
        },
        Outcome::Failed => Banner::Failed(result.message.clone().unwrap_or_else(|| "未知错误".into())),
    })
}

/// The banner's two lines, its error colour and its button (`(key, label, glyph, enabled)`).
pub struct BannerView {
    pub title: String,
    pub text: String,
    pub error: bool,
    pub button: Option<(&'static str, &'static str, &'static str, bool)>,
}
pub fn banner_view(status: &Status, banner: &Banner, offset: FixedOffset) -> BannerView {
    let name = |id: Option<&str>| point_name(status, id, offset);
    let later = |can_cancel: bool| if can_cancel { "" } else { "房间关闭后可以取消。" };
    match banner {
        Banner::Pending { point, can_cancel } => BannerView {
            title: format!("已设置回档到{}", name(Some(point))),
            text: format!("下次启动房间时生效。{}", later(*can_cancel)),
            error: false,
            button: Some(("cancel", "取消回档", "\u{f00d}", *can_cancel)),
        },
        Banner::Unfinished { point, can_cancel } => BannerView {
            title: format!("回档到{}还没有完成", name(Some(point))),
            text: format!("启动房间后会继续回档。{}", later(*can_cancel)),
            error: false,
            button: Some(("cancel", "取消回档", "\u{f00d}", *can_cancel)),
        },
        Banner::Abandoned => BannerView {
            title: "已取消回档".into(),
            text: "下次启动房间时，会恢复到回档前的状态。".into(),
            error: false,
            button: None,
        },
        Banner::Done { point, undo } => BannerView {
            title: format!("上次已回档到{}", name(point.as_deref())),
            text: if undo.is_some() { "回档前的状态已保存，可以撤销这次回档。".into() } else { String::new() },
            error: false,
            button: undo.as_ref().map(|_| ("undo", "撤销这次回档", "\u{f0e2}", true)),
        },
        Banner::Failed(message) => BannerView { title: "上次回档失败".into(), text: message.clone(), error: true, button: None },
    }
}

/// What a confirmation is about (`RollbackConfirm`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Confirm {
    Rollback(String),
    Undo(String),
    Cancel,
}
impl Confirm {
    /// The point a confirmed rollback or undo requests.
    pub fn point(&self) -> Option<&str> {
        match self {
            Self::Rollback(p) | Self::Undo(p) => Some(p),
            Self::Cancel => None,
        }
    }
}

/// Title, `· ` lines and the confirm label of `RollbackConfirmDialog`.
pub fn confirm_text(status: &Status, confirm: &Confirm, offset: FixedOffset) -> (String, Vec<String>, &'static str) {
    let mut lines = vec!["房间里的区块和所有玩家的背包都会恢复到这个时间，之后的建造和物品会丢失。".to_owned()];
    if status.host_running {
        lines.push("房间正在运行，会先关闭房间，在线玩家会断开。".into());
    }
    lines.push("回档在下次启动房间时进行。".into());
    lines.push("回档前会自动保存当前状态，之后可以撤销。".into());
    match confirm {
        Confirm::Rollback(id) => {
            let when = status
                .points
                .iter()
                .find(|p| &p.id == id)
                .map(|p| point_date_time(p.created_at, offset))
                .unwrap_or_else(|| "已过期的回档点".into());
            (format!("回档到{when}？"), lines, "回档")
        }
        Confirm::Undo(id) => {
            lines.insert(1, "回档之后在房间里玩的进度也会丢失。".into());
            (format!("撤销回档，恢复到{}？", point_name(status, Some(id), offset)), lines, "回档")
        }
        Confirm::Cancel => {
            let applying = status.pending.as_ref().is_some_and(|p| p.state == RequestState::Applying);
            let line = if applying {
                "回档已经开始，取消后下次启动房间时会恢复到回档前的状态。"
            } else {
                "房间会保持现在的状态，不再回档。"
            };
            ("取消回档？".into(), vec![line.into()], "取消回档")
        }
    }
}

fn local(millis: i64, offset: FixedOffset) -> DateTime<FixedOffset> {
    DateTime::from_timestamp_millis(millis).unwrap_or_default().with_timezone(&offset)
}
/// `HH:mm`.
pub fn point_time(millis: i64, offset: FixedOffset) -> String {
    local(millis, offset).format("%H:%M").to_string()
}
/// `M月d日 HH:mm`.
pub fn point_date_time(millis: i64, offset: FixedOffset) -> String {
    let t = local(millis, offset);
    format!("{}月{}日 {}", t.month(), t.day(), t.format("%H:%M"))
}
/// The point a banner or dialog names, or 已过期的回档点 when it is no longer kept.
pub fn point_name(status: &Status, id: Option<&str>, offset: FixedOffset) -> String {
    status
        .points
        .iter()
        .find(|p| Some(p.id.as_str()) == id)
        .map(|p| point_date_time(p.created_at, offset))
        .unwrap_or_else(|| "已过期的回档点".into())
}
pub fn ago(created_at: i64, now: i64) -> String {
    let minutes = ((now - created_at) / 60_000).max(0);
    match minutes {
        m if m < 1 => "刚刚".into(),
        m if m < 60 => format!("{m}分钟前"),
        m if m < 24 * 60 => format!("{}小时前", m / 60),
        m => format!("{}天前", m / (24 * 60)),
    }
}
pub fn changes(point: &Point) -> String {
    if point.changed_chunks == 0 && point.changed_players == 0 {
        "只有生物或物品变化".into()
    } else {
        format!("区块变化{}个 · 玩家变化{}人", point.changed_chunks, point.changed_players)
    }
}
/// Rounded up, so any used space shows at least 1%.
pub fn usage_percent(used: i64, max: i64) -> i64 {
    if max <= 0 || used <= 0 {
        return 0;
    }
    ((used * 100 + max - 1) / max).clamp(0, 100)
}
pub fn usage_line(status: &Status) -> String {
    format!(
        "回档空间已用{}%，24小时内每5分钟一个，7天内每小时一个",
        usage_percent(status.used_bytes, status.max_bytes)
    )
}

/// Newest-first points grouped by local day: 今天, 昨天, then `M月d日`.
pub fn group_by_day(points: &[Point], now: i64, offset: FixedOffset) -> Vec<(String, Vec<&Point>)> {
    let today = local(now, offset).date_naive();
    let mut groups: Vec<(NaiveDate, Vec<&Point>)> = Vec::new();
    for p in points {
        let day = local(p.created_at, offset).date_naive();
        match groups.iter_mut().find(|(d, _)| *d == day) {
            Some((_, list)) => list.push(p),
            None => groups.push((day, vec![p])),
        }
    }
    groups
        .into_iter()
        .map(|(day, list)| {
            let label = if day == today {
                "今天".into()
            } else if Some(day) == today.pred_opt() {
                "昨天".into()
            } else {
                format!("{}月{}日", day.month(), day.day())
            };
            (label, list)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn point(id: &str, at: i64) -> Point {
        Point { id: id.into(), created_at: at, changed_chunks: 2, changed_players: 1, ..Default::default() }
    }
    fn status(pending: Option<Pending>, result: Option<LastResult>, running: bool) -> Status {
        Status {
            points: vec![point("newer", 2_000_000), point("older", 1_000_000)],
            pending,
            last_result: result,
            used_bytes: 0,
            max_bytes: 1024,
            host_running: running,
        }
    }
    fn result(outcome: Outcome, before: Option<&str>, message: Option<&str>) -> LastResult {
        LastResult {
            point_id: Some("older".into()),
            before_point_id: before.map(Into::into),
            outcome,
            message: message.map(Into::into),
            finished_at: 0,
        }
    }
    fn zone() -> FixedOffset {
        FixedOffset::east_opt(8 * 3600).unwrap()
    }

    #[test]
    fn banner_follows_the_pending_request_then_the_last_result() {
        let pending = Pending { point_id: "older".into(), ..Default::default() };
        let waiting = |can_cancel| Some(Banner::Pending { point: "older".into(), can_cancel });
        assert_eq!(banner(&status(Some(pending.clone()), None, true)), waiting(false));
        assert_eq!(banner(&status(Some(pending.clone()), None, false)), waiting(true));
        let applying = Pending { state: RequestState::Applying, ..pending.clone() };
        assert_eq!(banner(&status(Some(applying.clone()), None, false)), Some(Banner::Unfinished { point: "older".into(), can_cancel: true }));
        assert_eq!(banner(&status(Some(Pending { abandoned: true, ..applying }), None, false)), Some(Banner::Abandoned));
        let failed = result(Outcome::Failed, None, None);
        assert_eq!(banner(&status(Some(pending), Some(failed), false)), waiting(true), "a pending request wins");
        let done = |before| banner(&status(None, Some(result(Outcome::Done, before, None)), false));
        assert_eq!(done(Some("newer")), Some(Banner::Done { point: Some("older".into()), undo: Some("newer".into()) }));
        assert_eq!(done(Some("expired")), Some(Banner::Done { point: Some("older".into()), undo: None }));
        let failed = banner(&status(None, Some(result(Outcome::Failed, None, Some("坏了"))), false));
        assert_eq!(failed, Some(Banner::Failed("坏了".into())));
        assert_eq!(banner(&status(None, None, false)), None);
    }

    #[test]
    fn points_group_by_local_day_and_texts_match_compose() {
        let at = |day: u32, hour: u32| {
            chrono::NaiveDate::from_ymd_opt(2026, 10, day).unwrap().and_hms_opt(hour, 0, 0).unwrap().and_local_timezone(zone()).unwrap().timestamp_millis()
        };
        let points = vec![point("a", at(13, 10)), point("b", at(13, 1)), point("c", at(12, 23)), point("d", at(10, 9))];
        let groups = group_by_day(&points, at(13, 12), zone());
        assert_eq!(groups.iter().map(|g| g.0.as_str()).collect::<Vec<_>>(), ["今天", "昨天", "10月10日"]);
        assert_eq!(groups[0].1.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), ["a", "b"]);
        assert_eq!(point_time(at(13, 9), zone()), "09:00");
        assert_eq!(point_date_time(at(10, 9), zone()), "10月10日 09:00");
        assert_eq!(ago(0, 25 * 60_000), "25分钟前");
        assert_eq!(ago(0, 3 * 3_600_000), "3小时前");
        assert_eq!(ago(0, 30_000), "刚刚");
        assert_eq!(ago(0, 49 * 3_600_000), "2天前");
        assert_eq!(usage_percent(0, 1024), 0);
        assert_eq!(usage_percent(1, 1024), 1, "any used space shows at least 1%");
        assert_eq!(usage_percent(512, 1024), 50);
        assert_eq!(usage_percent(2048, 1024), 100);
        assert_eq!(changes(&Point { changed_chunks: 0, changed_players: 0, ..point("x", 0) }), "只有生物或物品变化");
        assert_eq!(changes(&point("x", 0)), "区块变化2个 · 玩家变化1人");
    }

    #[test]
    fn confirm_and_banner_texts() {
        let s = status(None, None, true);
        let (title, lines, label) = confirm_text(&s, &Confirm::Rollback("older".into()), zone());
        assert_eq!(title, format!("回档到{}？", point_date_time(1_000_000, zone())));
        assert_eq!(lines.len(), 4);
        assert_eq!(lines[1], "房间正在运行，会先关闭房间，在线玩家会断开。");
        assert_eq!(label, "回档");
        let (title, lines, _) = confirm_text(&status(None, None, false), &Confirm::Undo("gone".into()), zone());
        assert_eq!(title, "撤销回档，恢复到已过期的回档点？");
        assert_eq!(lines[1], "回档之后在房间里玩的进度也会丢失。");
        assert_eq!(lines.len(), 4);
        let applying = Pending { state: RequestState::Applying, point_id: "older".into(), ..Default::default() };
        let (_, lines, label) = confirm_text(&status(Some(applying), None, false), &Confirm::Cancel, zone());
        assert_eq!((lines[0].as_str(), label), ("回档已经开始，取消后下次启动房间时会恢复到回档前的状态。", "取消回档"));

        let s = status(Some(Pending { point_id: "older".into(), ..Default::default() }), None, true);
        let view = banner_view(&s, &banner(&s).unwrap(), zone());
        assert!(view.title.starts_with("已设置回档到"));
        assert_eq!(view.text, "下次启动房间时生效。房间关闭后可以取消。");
        assert_eq!(view.button, Some(("cancel", "取消回档", "\u{f00d}", false)));
        assert!(needs_refresh(&s));
        assert!(!needs_refresh(&status(None, None, true)));
        assert_eq!(requested_notice(Created { stopped_host: true }), "房间正在关闭，下次启动房间时会回档");
    }

    #[test]
    fn status_parses_the_server_shape() {
        let s: Status = serde_json::from_value(serde_json::json!({
            "points":[{"id":"p1","kind":"BeforeRollback","createdAt":5,"chunkCount":3,"playerCount":1,"changedChunks":0,"changedPlayers":0}],
            "pending":{"pointId":"p1","requestedAt":1,"state":"Applying","abandoned":false},
            "lastResult":null,"usedBytes":10,"maxBytes":100
        }))
        .unwrap();
        assert_eq!(s.points[0].kind, PointKind::BeforeRollback);
        assert_eq!(s.pending.unwrap().state, RequestState::Applying);
        assert!(!s.host_running, "hostRunning defaults to false");
    }
}
