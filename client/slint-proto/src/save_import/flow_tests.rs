//! The rules of Kotlin `HostSaveImportViewModelTest`, on the I/O-free flow.
use super::*;
use rdi_anvilrw::{SaveLoaderDetection, SaveScanResult, SaveSyncChunk, classify, offline_uuid};

const ME: &str = "6a0000000000000000000001";
const FRIEND: &str = "6a0000000000000000000002";

fn uuid(id: &str) -> Uuid {
    Uuid::parse_str(&uuid_of(id).unwrap()).unwrap()
}
fn owner() -> Uuid {
    offline_uuid("Owner")
}
fn online() -> Uuid {
    Uuid::parse_str("1512ef02-2b79-4494-a4e9-e35cd8e03923").unwrap()
}
fn me() -> Me {
    Me { id: ME.into(), name: "我".into(), is_dav: false }
}
fn host(owner_id: &str) -> HostInfo {
    HostInfo { id: "6a00000000000000000000ff".into(), name: "房间".into(), owner_id: owner_id.into(), version: 2, modloader: "forge".into() }
}
fn player(uuid: Uuid, singleplayer_host: bool, ticks: Option<i64>) -> SavePlayer {
    SavePlayer {
        uuid,
        kind: classify(uuid),
        name_hints: vec![],
        verified_name: None,
        is_singleplayer_host: singleplayer_host,
        last_played: None,
        play_time_ticks: ticks,
    }
}
fn chunk() -> SaveSyncChunk {
    SaveSyncChunk { dimension_id: "minecraft:overworld".into(), chunk_x: 0, chunk_z: 0, owner: owner() }
}
fn summary(marked: Vec<SaveSyncChunk>) -> MarkingSummary {
    let by_dimension = if marked.is_empty() { vec![] } else { vec![("minecraft:overworld".into(), marked.len())] };
    MarkingSummary { present_count: marked.len(), marked, marked_by_dimension: by_dimension, players_outside: vec![] }
}
fn record(selected: Option<Uuid>) -> MarkingRecord {
    MarkingRecord {
        source_path: "/saves/新的世界".into(),
        host_id: host(ME).id,
        copy_path: "/pack/saves/新的世界-RDI导入".into(),
        launch_uuid: selected.unwrap_or(uuid(ME)).to_string(),
        created_at: 0,
        identity_version: 1,
        original_owner_uuid: Some(owner().to_string()),
        selected_player_uuid: selected.map(|u| u.to_string()),
        selected_player_name: None,
        identity_backup_path: None,
    }
}

struct Save {
    players: Vec<SavePlayer>,
    msid: HashMap<Uuid, &'static str>,
    existing_rdi: HashSet<Uuid>,
    hardcore: bool,
    brands: Vec<String>,
}
impl Save {
    fn new(players: Vec<SavePlayer>) -> Self {
        Self { players, msid: HashMap::new(), existing_rdi: HashSet::new(), hardcore: false, brands: vec!["forge".into()] }
    }
    fn preparation(&self) -> Preparation {
        Preparation {
            scan: SaveScanResult {
                mc_version_name: Some("1.20.1".into()),
                data_version: Some(3465),
                server_brands: self.brands.clone(),
                fml_mod_ids: Some(vec!["forge".into()]),
                loader_detection: SaveLoaderDetection::Forge,
                is_hardcore: self.hardcore,
                singleplayer_host_uuid: Some(owner()),
                players: self.players.clone(),
                sync_chunks: Some(vec![chunk()]),
                total_bytes: 0,
            },
            mojang_names: HashMap::new(),
            msid_matches: self
                .msid
                .iter()
                .map(|(u, id)| (*u, MsidMatchVo { msid: u.to_string(), id: (*id).into(), name: "正版玩家".into() }))
                .collect(),
            existing_rdi_players: self.existing_rdi.clone(),
        }
    }
    fn marking_players(&self) -> Vec<SaveMarkingPlayer> {
        self.players.iter().map(|p| SaveMarkingPlayer { player: p.clone(), position: None }).collect()
    }
}

/// The flow after the save was picked, a copy was opened as `selected` and the game exited.
fn marked(save: &Save, selected: Option<Uuid>, marks: Vec<SaveSyncChunk>) -> Flow {
    let mut flow = Flow::default();
    flow.host_loaded(host(ME));
    flow.picked("/saves/新的世界".into(), save.marking_players(), None);
    if let Some(selected) = selected {
        flow.select_player(selected);
    }
    let copy = record(flow.selected_player);
    flow.copy_ready(copy.clone());
    flow.marking_started(copy, "session-1".into());
    flow.exited(summary(marks));
    assert_eq!(flow.step, Step::Summary);
    flow
}

fn mapped(save: &Save) -> Flow {
    let mut flow = marked(save, None, vec![chunk()]);
    flow.mapping_record().unwrap();
    flow.mapped(save.preparation(), &me());
    assert_eq!(flow.step, Step::Mapping);
    flow
}

fn row(flow: &Flow, uuid: Uuid) -> &Row {
    flow.rows.iter().find(|r| r.player.uuid == uuid).unwrap()
}

fn precheck(warnings: Vec<MemberWarning>, rules: i32) -> PrecheckVo {
    PrecheckVo { member_warnings: warnings, game_rule_override_count: rules }
}

#[test]
fn suggestions_prefer_the_msid_match_then_the_owner_as_me() {
    let mut save = Save::new(vec![player(owner(), true, None), player(online(), false, None)]);
    save.msid.insert(online(), ME);
    let flow = mapped(&save);
    let matched = row(&flow, online()).target.clone().unwrap();
    assert_eq!(matched.account_id, ME, "the msid match wins");
    assert_eq!(matched.source, TargetSource::MsidMatch);
    assert!(row(&flow, owner()).target.is_none(), "my account is already used by the msid match");

    let no_match = mapped(&Save::new(vec![player(owner(), true, None), player(online(), false, None)]));
    let mine = row(&no_match, owner()).target.clone().unwrap();
    assert_eq!((mine.account_id.as_str(), mine.source), (ME, TargetSource::Me));
}

#[test]
fn targets_are_unique_and_rdi_players_are_read_only() {
    let rdi = uuid("6a00000000000000000000aa");
    let mut flow = mapped(&Save::new(vec![player(owner(), true, None), player(online(), false, None), player(rdi, false, None)]));

    flow.set_target(online(), Some(Target { account_id: ME.into(), name: "我".into(), source: TargetSource::Qq }));
    assert!(flow.error.is_some(), "two players cannot share one account");
    assert!(row(&flow, online()).target.is_none());

    flow.set_target(rdi, Some(Target { account_id: FRIEND.into(), name: "朋友".into(), source: TargetSource::Qq }));
    let rdi_row = row(&flow, rdi);
    assert!(rdi_row.read_only && rdi_row.account_missing);
    assert!(rdi_row.target.is_none());
}

#[test]
fn a_dual_identity_defaults_to_the_longer_play_time_and_can_be_changed() {
    let mine = uuid(ME);
    let mut save = Save::new(vec![player(owner(), true, Some(900)), player(mine, false, Some(100))]);
    save.existing_rdi.insert(mine);
    let mut flow = mapped(&save);

    flow.set_target(owner(), Some(me().target()));
    assert_eq!(flow.duals.len(), 1);
    assert_eq!(flow.duals[0].other, owner());
    assert_eq!(flow.dual_choices[&owner()], DualIdentityKeep::Other);
    let review = flow.review().unwrap();
    assert_eq!(review.mapping, HashMap::from([(owner(), mine), (mine, owner())]));
    flow.reviewed(review, precheck(vec![], 0)).unwrap();

    flow.back_to_mapping();
    flow.set_dual_keep(owner(), DualIdentityKeep::Rdi);
    assert!(flow.review().unwrap().mapping.is_empty());
}

#[test]
fn the_confirmation_shows_warnings_and_submits() {
    let mut save = Save::new(vec![player(owner(), true, None)]);
    save.hardcore = true;
    save.brands = vec!["forge".into(), "fabric".into()];
    let mut flow = mapped(&save);

    let review = flow.review().unwrap();
    assert_eq!(review.dto.member_player_ids, vec![ME.to_owned()]);
    assert_eq!((review.dto.mc_version_name.as_str(), review.dto.loader.as_str()), ("1.20.1", "forge"));
    let warnings = vec![MemberWarning { player_id: ME.into(), reason: "该玩家加入的房间已达上限".into() }];
    flow.reviewed(review, precheck(warnings.clone(), 3)).unwrap();

    let confirmation = flow.confirmation.as_ref().unwrap();
    let has = |text: &str| confirmation.messages.iter().any(|m| m.contains(text));
    assert!(has("3条游戏规则") && has("其他加载器") && has("极限模式") && has("1个区块"));
    assert!(has("我无法加入房间：该玩家加入的房间已达上限"));
    assert_eq!(confirmation.plan.confirmed_warnings, warnings);
    assert_eq!(confirmation.plan.mapping, HashMap::from([(owner(), uuid(ME))]));
    assert_eq!(flow.step, Step::Confirm);

    flow.submitted("run-1".into());
    assert_eq!((flow.step, flow.run_id.as_deref()), (Step::Submitted, Some("run-1")));
}

#[test]
fn an_import_needs_at_least_one_marked_chunk() {
    let flow = marked(&Save::new(vec![player(owner(), true, None)]), None, vec![]);
    assert_eq!(flow.mapping_record().unwrap_err().to_string(), "请至少标记一个要保留的区块");
    assert_eq!(flow.step, Step::Summary);
}

#[test]
fn only_the_owner_of_a_v2_host_can_import() {
    assert_eq!(check_host(&host(FRIEND), &me()).unwrap_err().to_string(), "只有房主可以导入存档");
    let old = HostInfo { version: 1, ..host(ME) };
    assert_eq!(check_host(&old, &me()).unwrap_err().to_string(), "仅2026.9.5以后创建的房间支持此功能");
    assert!(check_host(&host(ME), &me()).is_ok());
}

#[test]
fn dav_can_import_into_another_players_host() {
    let dav = Me { is_dav: true, ..me() };
    assert!(check_host(&host(FRIEND), &dav).is_ok());
}

#[test]
fn fabric_brand_history_is_passed_to_the_server_precheck() {
    let level = LevelMetadata {
        version_name: Some("1.20.1".into()),
        data_version: None,
        server_brands: vec!["vanilla".into(), "fabric".into()],
        hardcore: false,
        hardcore_byte_offset: None,
        singleplayer_uuid: None,
        fml_mod_ids: None,
    };
    let dto = pick_precheck(&level).unwrap();
    assert_eq!((dto.mc_version_name.as_str(), dto.loader.as_str()), ("1.20.1", "fabric"));
    assert!(dto.member_player_ids.is_empty());
}

#[test]
fn the_selected_role_is_suggested_as_me_instead_of_the_original_owner() {
    let save = Save::new(vec![player(owner(), true, None), player(online(), false, None)]);
    let mut flow = Flow::default();
    flow.picked("/saves/新的世界".into(), save.marking_players(), None);
    assert_eq!(flow.step, Step::SelectPlayer);
    assert_eq!(flow.selected_player, Some(owner()));

    let mut flow = marked(&save, Some(online()), vec![chunk()]);
    assert_eq!(flow.record.as_ref().unwrap().launch_uuid, online().to_string());
    flow.mapped(save.preparation(), &me());
    assert_eq!(row(&flow, online()).target.as_ref().unwrap().account_id, ME);
    assert!(row(&flow, owner()).target.is_none());
}

#[test]
fn an_existing_rdi_role_of_mine_is_not_silently_assigned_another_role() {
    let mine = uuid(ME);
    let mut save = Save::new(vec![player(owner(), true, None), player(mine, false, None)]);
    save.existing_rdi.insert(mine);
    let mut flow = mapped(&save);
    assert!(row(&flow, owner()).target.is_none());
    flow.set_target(owner(), Some(me().target()));
    assert!(!flow.duals.is_empty());
}

#[test]
fn reuse_keeps_its_role_and_a_new_selection_does_not_drop_it() {
    let save = Save::new(vec![player(owner(), true, None), player(online(), false, None)]);
    let earlier = record(Some(owner()));
    let mut flow = Flow::default();
    flow.picked("/saves/新的世界".into(), save.marking_players(), Some(earlier.clone()));
    flow.select_player(online());
    assert_eq!(flow.reuse_record(true), Some(earlier.clone()), "continuing uses the earlier copy and its role");
    assert_eq!(flow.reuse_record(false), None, "a new selection makes a new copy");
    assert_eq!(flow.cleanup_record(), Some(earlier), "清理 offers the earlier copy until another is opened");
}

#[test]
fn a_failed_launch_leaves_the_selection_available() {
    let save = Save::new(vec![player(owner(), true, None)]);
    let mut flow = Flow::default();
    flow.picked("/saves/新的世界".into(), save.marking_players(), None);
    assert!(flow.begin("正在复制存档"));
    assert!(!flow.begin("again"), "one piece of work at a time");
    flow.copy_ready(record(Some(owner())));
    flow.end(Some("无法启动".into()));
    assert_eq!(flow.step, Step::SelectPlayer);
    assert_eq!(flow.error.as_deref(), Some("无法启动"));
    assert!(flow.reusable.is_some());
    assert!(flow.busy.is_none());
}

#[test]
fn cleaning_the_copy_starts_over_but_keeps_the_host() {
    let mut flow = marked(&Save::new(vec![player(owner(), true, None)]), None, vec![chunk()]);
    flow.cleaned();
    assert_eq!(flow.step, Step::PickSave);
    assert!(flow.host.is_some() && flow.record.is_none() && flow.summary.is_none());
}

#[test]
fn labels() {
    assert_eq!(play_time_text(Some(900_000)).as_deref(), Some("12.5小时"));
    assert_eq!(dimension_name("minecraft:the_nether"), "下界");
    assert_eq!(dimension_name("twilightforest:twilight_forest"), "twilightforest:twilight_forest");
}

#[test]
fn a_warning_about_an_rdi_player_of_the_save_uses_their_name() {
    let rdi = uuid("6a00000000000000000000aa");
    let mut rdi_player = player(rdi, false, None);
    rdi_player.name_hints = vec![rdi_anvilrw::NameHint { name: "dev1".into(), source: rdi_anvilrw::NameHintSource::UserCache }];
    let mut save = Save::new(vec![player(owner(), true, None), rdi_player]);
    save.existing_rdi.insert(rdi);
    let mut flow = mapped(&save);
    let review = flow.review().unwrap();
    assert_eq!(review.dto.member_player_ids, vec![ME.to_owned(), "6a00000000000000000000aa".to_owned()]);
    let warning = MemberWarning { player_id: "6a00000000000000000000aa".into(), reason: "该玩家加入的房间已达上限".into() };
    flow.reviewed(review, precheck(vec![warning], 0)).unwrap();
    assert!(flow.confirmation.unwrap().messages.iter().any(|m| m == "dev1无法加入房间：该玩家加入的房间已达上限"));
}
