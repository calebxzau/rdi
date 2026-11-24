//! A4: the steps of the 导入单人存档 screen, Kotlin `HostSaveImportViewModel` without the I/O. The
//! screen (`save_import_ui.rs`) runs the file, server and game work and hands the results to
//! these methods, so the rules can be tested without Slint, a server or a game.
use super::api::{MemberWarning, MsidMatchVo, PrecheckDto, PrecheckVo};
use super::marking::{MarkingRecord, MarkingSummary};
use super::service::{ImportPlan, Preparation, loader_name, object_id_of};
use crate::play::room::uuid_of;
use anyhow::{Result, anyhow};
use rdi_anvilrw::{DualIdentity, DualIdentityKeep, LevelMetadata, SaveMarkingPlayer, SavePlayer, SavePlayerKind, build_identity_mapping, find_dual_identities};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use uuid::Uuid;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Step {
    #[default]
    PickSave,
    SelectPlayer,
    Marking,
    Summary,
    Mapping,
    Confirm,
    Submitted,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TargetSource {
    MsidMatch,
    Me,
    Qq,
}

/// The RDI account a save player is assigned to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target {
    /// The account's ObjectId.
    pub account_id: String,
    pub name: String,
    pub source: TargetSource,
}

impl Target {
    pub fn uuid(&self) -> Result<Uuid> {
        Ok(Uuid::parse_str(&uuid_of(&self.account_id)?)?)
    }
}

/// One player of the save and the account they become.
#[derive(Clone, Debug, PartialEq)]
pub struct Row {
    pub player: SavePlayer,
    pub display_name: String,
    /// 正版 / 离线 / 离线（已验证）/ 已是RDI玩家 / 未知
    pub badge: &'static str,
    pub target: Option<Target>,
    /// RDI players are kept as they are.
    pub read_only: bool,
    /// An RDI player whose account no longer exists.
    pub account_missing: bool,
}

#[derive(Clone, Debug)]
pub struct Confirmation {
    pub messages: Vec<String>,
    pub plan: ImportPlan,
}

/// What the flow needs to know about the host.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HostInfo {
    pub id: String,
    pub name: String,
    pub owner_id: String,
    pub version: i32,
    /// `forge`, `neoforge` or `fabric`, any case.
    pub modloader: String,
}

impl HostInfo {
    pub fn is_fabric(&self) -> bool {
        self.modloader.eq_ignore_ascii_case("fabric")
    }
}

/// The signed-in player.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Me {
    pub id: String,
    pub name: String,
    pub is_dav: bool,
}

impl Me {
    pub fn target(&self) -> Target {
        Target { account_id: self.id.clone(), name: self.name.clone(), source: TargetSource::Me }
    }
}

/// The server precheck and the plan it is for, built from the mapping step (plan §4 steps 9-10).
#[derive(Clone, Debug)]
pub struct Review {
    pub dto: PrecheckDto,
    pub mapping: HashMap<Uuid, Uuid>,
    pub record: MarkingRecord,
}

/// Only the owner (or dav) of a host made since 2026.9.5 can import.
pub fn check_host(host: &HostInfo, me: &Me) -> Result<()> {
    if host.owner_id != me.id && !me.is_dav {
        return Err(anyhow!("只有房主可以导入存档"));
    }
    if host.version != 2 {
        return Err(anyhow!("仅2026.9.5以后创建的房间支持此功能"));
    }
    Ok(())
}

/// The first precheck, from the picked save's `level.dat`, before anything is copied.
pub fn pick_precheck(level: &LevelMetadata) -> Result<PrecheckDto> {
    Ok(PrecheckDto {
        mc_version_name: level.version_name.clone().unwrap_or_default(),
        loader: loader_name(level.loader_detection())?.into(),
        member_player_ids: vec![],
    })
}

const LOADER_BRANDS: [&str; 5] = ["forge", "neoforge", "fabric", "quilt", "vanilla"];

/// The screen's state for one host.
#[derive(Clone, Debug, Default)]
pub struct Flow {
    pub step: Step,
    pub host: Option<HostInfo>,
    pub source: Option<PathBuf>,
    pub marking_players: Vec<SaveMarkingPlayer>,
    pub selected_player: Option<Uuid>,
    pub record: Option<MarkingRecord>,
    /// A copy from an earlier attempt that can be reused.
    pub reusable: Option<MarkingRecord>,
    pub busy: Option<String>,
    pub error: Option<String>,
    pub summary: Option<MarkingSummary>,
    pub preparation: Option<Preparation>,
    pub rows: Vec<Row>,
    pub duals: Vec<DualIdentity>,
    pub dual_choices: HashMap<Uuid, DualIdentityKeep>,
    pub confirmation: Option<Confirmation>,
    pub run_id: Option<String>,
    /// The game session of the marking launch, watched for its end.
    pub session: Option<String>,
}

impl Flow {
    /// Starts work with a busy message; `false` while other work runs.
    pub fn begin(&mut self, message: &str) -> bool {
        if self.busy.is_some() {
            return false;
        }
        self.busy = Some(message.into());
        self.error = None;
        true
    }

    /// Ends the running work, with a player-facing error when it failed.
    pub fn end(&mut self, error: Option<String>) {
        self.busy = None;
        if error.is_some() {
            self.error = error;
        }
    }

    pub fn host_loaded(&mut self, host: HostInfo) {
        self.host = Some(host);
    }

    /// A save was picked and passed the first precheck: start over with it.
    pub fn picked(&mut self, source: PathBuf, players: Vec<SaveMarkingPlayer>, reusable: Option<MarkingRecord>) {
        let selected = players.iter().find(|p| p.player.is_singleplayer_host).or(players.first()).map(|p| p.player.uuid);
        *self = Flow {
            step: Step::SelectPlayer,
            host: self.host.take(),
            source: Some(source),
            marking_players: players,
            selected_player: selected,
            reusable,
            busy: self.busy.take(),
            ..Flow::default()
        };
    }

    pub fn select_player(&mut self, uuid: Uuid) {
        if self.busy.is_none() && self.marking_players.iter().any(|p| p.player.uuid == uuid) {
            self.selected_player = Some(uuid);
        }
    }

    /// The copy to open: the earlier one when `reuse` is asked and present.
    pub fn reuse_record(&self, reuse: bool) -> Option<MarkingRecord> {
        if reuse { self.reusable.clone() } else { None }
    }

    /// A copy exists now, so a failed launch can still offer to continue with it.
    pub fn copy_ready(&mut self, record: MarkingRecord) {
        self.reusable = Some(record);
    }

    /// The game opened `record` in marking mode as `session`.
    pub fn marking_started(&mut self, record: MarkingRecord, session: String) {
        self.record = Some(record);
        self.reusable = None;
        self.session = Some(session);
        self.step = Step::Marking;
    }

    /// The marking game ended and `summary` is what it left in the copy.
    pub fn exited(&mut self, summary: MarkingSummary) {
        self.session = None;
        self.summary = Some(summary);
        self.step = Step::Summary;
    }

    /// The copy to identify players in, once at least one chunk is marked.
    pub fn mapping_record(&self) -> Result<MarkingRecord> {
        if self.summary.as_ref().is_none_or(|s| s.marked.is_empty()) {
            return Err(anyhow!("请至少标记一个要保留的区块"));
        }
        self.record.clone().ok_or_else(|| anyhow!("请先选择存档"))
    }

    pub fn mapped(&mut self, preparation: Preparation, me: &Me) {
        self.rows = suggest_rows(&preparation, self.record.as_ref(), me);
        self.preparation = Some(preparation);
        self.step = Step::Mapping;
        self.refresh_duals();
    }

    /// Assigns the player `uuid` to `target`, or clears it. Two players cannot share one account.
    pub fn set_target(&mut self, uuid: Uuid, target: Option<Target>) {
        let Some(index) = self.rows.iter().position(|r| r.player.uuid == uuid) else { return };
        if self.rows[index].read_only {
            return;
        }
        if let Some(target) = &target
            && self.rows.iter().any(|r| r.player.uuid != uuid && r.target.as_ref().is_some_and(|t| t.account_id == target.account_id))
        {
            self.error = Some(format!("{}已对应存档中的另一名玩家", target.name));
            return;
        }
        self.rows[index].target = target;
        self.error = None;
        self.refresh_duals();
    }

    pub fn set_dual_keep(&mut self, other: Uuid, keep: DualIdentityKeep) {
        if self.duals.iter().any(|d| d.other == other) {
            self.dual_choices.insert(other, keep);
        }
    }

    fn assignments(&self) -> Result<BTreeMap<Uuid, Uuid>> {
        self.rows.iter().filter_map(|r| r.target.as_ref().map(|t| Ok((r.player.uuid, t.uuid()?)))).collect()
    }

    fn refresh_duals(&mut self) {
        let Some(preparation) = &self.preparation else { return };
        let Ok(assignments) = self.assignments() else { return };
        self.duals = find_dual_identities(&preparation.scan, &assignments);
        let old = std::mem::take(&mut self.dual_choices);
        self.dual_choices = self.duals.iter().map(|d| (d.other, old.get(&d.other).copied().unwrap_or(d.default_keep))).collect();
    }

    /// The mapping and the precheck the confirmation needs.
    pub fn review(&self) -> Result<Review> {
        let preparation = self.preparation.as_ref().ok_or_else(|| anyhow!("请先识别存档中的玩家"))?;
        let record = self.record.clone().ok_or_else(|| anyhow!("请先选择存档"))?;
        let mapping = build_identity_mapping(&preparation.scan, &self.assignments()?, &self.dual_choices).map_err(|error| {
            tracing::warn!(%error, "玩家对应关系无效");
            anyhow!("玩家对应关系无效")
        })?;
        let mut members: Vec<String> = vec![];
        let mut existing: Vec<&Uuid> = preparation.existing_rdi_players.iter().collect();
        existing.sort();
        let ids = self.rows.iter().filter_map(|r| r.target.as_ref().map(|t| t.account_id.clone()));
        for id in ids.chain(existing.into_iter().map(|u| object_id_of(*u)).collect::<Result<Vec<_>>>()?) {
            if !members.contains(&id) {
                members.push(id);
            }
        }
        let scan = &preparation.scan;
        let dto = PrecheckDto {
            mc_version_name: scan.mc_version_name.clone().unwrap_or_default(),
            loader: loader_name(scan.loader_detection)?.into(),
            member_player_ids: members,
        };
        Ok(Review { dto, mapping, record })
    }

    /// The server answered the precheck of `review`.
    pub fn reviewed(&mut self, review: Review, precheck: PrecheckVo) -> Result<()> {
        let host = self.host.clone().ok_or_else(|| anyhow!("房间信息未加载"))?;
        let messages = self.messages(&precheck, &review.dto.loader);
        let plan = ImportPlan {
            host_id: host.id,
            host_name: host.name,
            record: review.record,
            mapping: review.mapping,
            member_player_ids: review.dto.member_player_ids,
            mc_version_name: review.dto.mc_version_name,
            loader: review.dto.loader,
            confirmed_warnings: precheck.member_warnings.clone(),
        };
        self.confirmation = Some(Confirmation { messages, plan });
        self.step = Step::Confirm;
        Ok(())
    }

    pub fn back_to_mapping(&mut self) {
        self.step = Step::Mapping;
        self.confirmation = None;
    }

    pub fn submitted(&mut self, run_id: String) {
        self.run_id = Some(run_id);
        self.step = Step::Submitted;
    }

    /// The copy that 清理导入副本 deletes.
    pub fn cleanup_record(&self) -> Option<MarkingRecord> {
        self.record.clone().or_else(|| self.reusable.clone())
    }

    /// The copy was deleted: back to the start.
    pub fn cleaned(&mut self) {
        *self = Flow { host: self.host.take(), busy: self.busy.take(), ..Flow::default() };
    }

    fn messages(&self, precheck: &PrecheckVo, loader: &str) -> Vec<String> {
        let scan = self.preparation.as_ref().map(|p| &p.scan);
        let mut out = vec!["当前房间的存档将被删除 并替换为导入的存档，此操作无法撤销。".to_owned()];
        if let Some(summary) = &self.summary {
            out.push(format!("只有标记的{}个区块会被完整保留，其余区块会在房间中重新生成(建筑、箱子和生物都会消失)", summary.marked.len()));
        }
        if precheck.game_rule_override_count > 0 {
            out.push(format!("房间已设置的{}条游戏规则将被清除，改用存档中的游戏规则。", precheck.game_rule_override_count));
        }
        let other_loader = scan.is_some_and(|scan| {
            scan.server_brands.iter().any(|brand| {
                brand.split(',').map(|t| t.trim().to_lowercase()).any(|token| token != loader && LOADER_BRANDS.contains(&token.as_str()))
            })
        });
        if other_loader {
            out.push("该存档曾被其他加载器打开过，部分数据可能已丢失。".into());
        }
        if scan.is_some_and(|s| s.is_hardcore) {
            out.push("该存档是极限模式，导入后将关闭极限模式。".into());
        }
        for warning in &precheck.member_warnings {
            out.push(format!("{}无法加入房间：{}", self.warning_name(warning), warning.reason));
        }
        out
    }

    /// The account a warning is about: an assigned account's name, else the name of the save's RDI
    /// player with that account (Kotlin shows the bare id for those), else the id.
    fn warning_name(&self, warning: &MemberWarning) -> String {
        let assigned = self.rows.iter().filter_map(|r| r.target.as_ref()).find(|t| t.account_id == warning.player_id).map(|t| t.name.clone());
        let in_save = || {
            self.rows
                .iter()
                .find(|r| r.read_only && object_id_of(r.player.uuid).is_ok_and(|id| id == warning.player_id))
                .map(|r| r.display_name.clone())
        };
        assigned.or_else(in_save).unwrap_or_else(|| warning.player_id.clone())
    }
}

/// The name shown for a save player: Mojang's, the verified offline name, a hint, or the UUID.
pub fn display_name(player: &SavePlayer, mojang: &HashMap<Uuid, String>) -> String {
    mojang
        .get(&player.uuid)
        .cloned()
        .or_else(|| player.verified_name.clone())
        .or_else(|| player.name_hints.first().map(|h| h.name.clone()))
        .unwrap_or_else(|| player.uuid.to_string()[..8].to_owned())
}

/// The first suggestion: a bound Microsoft profile goes to its account; the selected role (or
/// the save's owner for an old copy) goes to me unless my account is already used.
fn suggest_rows(preparation: &Preparation, record: Option<&MarkingRecord>, me: &Me) -> Vec<Row> {
    let msid: HashMap<Uuid, Target> = preparation.msid_matches.iter().map(|(uuid, m)| (*uuid, msid_target(m))).collect();
    let my_uuid = uuid_of(&me.id).ok().and_then(|u| Uuid::parse_str(&u).ok());
    let me_used = msid.values().any(|t| t.account_id == me.id) || my_uuid.is_some_and(|u| preparation.existing_rdi_players.contains(&u));
    let suggested_me = match record {
        Some(r) if r.identity_version == 1 => r.selected_player_uuid.as_deref().and_then(|u| Uuid::parse_str(u).ok()),
        _ => preparation.scan.players.iter().find(|p| p.is_singleplayer_host).map(|p| p.uuid),
    };
    let mut players: Vec<&SavePlayer> = preparation.scan.players.iter().collect();
    players.sort_by(|a, b| b.is_singleplayer_host.cmp(&a.is_singleplayer_host).then(b.play_time_ticks.unwrap_or(0).cmp(&a.play_time_ticks.unwrap_or(0))));
    players
        .into_iter()
        .map(|player| {
            let display_name = display_name(player, &preparation.mojang_names);
            if player.kind == SavePlayerKind::Rdi {
                return Row {
                    player: player.clone(),
                    display_name,
                    badge: "已是RDI玩家",
                    target: None,
                    read_only: true,
                    account_missing: !preparation.existing_rdi_players.contains(&player.uuid),
                };
            }
            let target = msid.get(&player.uuid).cloned().or_else(|| (Some(player.uuid) == suggested_me && !me_used).then(|| me.target()));
            let badge = match player.kind {
                SavePlayerKind::Online => "正版",
                SavePlayerKind::Offline if player.verified_name.is_some() => "离线（已验证）",
                SavePlayerKind::Offline => "离线",
                _ => "未知",
            };
            Row { player: player.clone(), display_name, badge, target, read_only: false, account_missing: false }
        })
        .collect()
}

fn msid_target(found: &MsidMatchVo) -> Target {
    Target { account_id: found.id.clone(), name: found.name.clone(), source: TargetSource::MsidMatch }
}

/// Every account id the mapping step shows a head for.
pub fn target_ids(flow: &Flow) -> HashSet<String> {
    flow.rows.iter().filter_map(|r| r.target.as_ref().map(|t| t.account_id.clone())).collect()
}

/// Game ticks as hours, e.g. `12.5小时`.
pub fn play_time_text(ticks: Option<i64>) -> Option<String> {
    ticks.map(|t| format!("{:.1}小时", t as f64 / 72_000.0))
}

/// The player-facing name of a dimension.
pub fn dimension_name(id: &str) -> String {
    match id {
        "minecraft:overworld" => "主世界".into(),
        "minecraft:the_nether" => "下界".into(),
        "minecraft:the_end" => "末地".into(),
        other => other.into(),
    }
}

#[cfg(test)]
#[path = "flow_tests.rs"]
mod tests;
