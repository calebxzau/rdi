//! Rooms (U4 batch c1): create, details and members. No Slint types here, so the launcher MCP
//! can call this without any UI (`J5-PLAN.md` section 5). The wiring lives in `rooms_ui.rs`.
pub mod form;
pub mod game_rules;
pub mod model;
pub mod permissions;
pub mod requests;

#[cfg(test)]
mod tests {
    use super::{form::*, model::*};
    use serde_json::Value;

    /// `protocol/fixtures/room_requests.json`, written by Kotlin `HostRequestFixtureTest`.
    fn fixture(name: &str) -> Value {
        let all: Value = serde_json::from_str(include_str!("../../protocol/fixtures/room_requests.json")).unwrap();
        all["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["name"] == name)
            .unwrap_or_else(|| panic!("缺少用例{name}"))["json"]
            .clone()
    }
    const ME: &str = "66a0000000000000000000aa";
    const PACK: &str = "66a000000000000000000002";

    fn add_friend(state: &mut FormState, id: &str) {
        state.open_friend_dialog();
        state.update_friend_qq("10001");
        let (generation, _) = state.add_friend().unwrap();
        state.friend_found(generation, Ok(Friend { id: id.into(), name: "好友".into() }));
    }

    #[test]
    fn create_bodies_match_kotlin() {
        let mut state = FormState::create(ME, "玩家", PACK, "测试整合包", "2.0.0", "V201");
        state.binding_loaded(Ok(None));
        let generation = state.open_world_dialog().unwrap();
        state.worlds_loaded(
            generation,
            Ok(vec![BaseWorld {
                id: "0193d3a4-1111-7000-8000-000000000001".into(),
                owner_id: String::new(),
                name: "模板".into(),
                level_type: " minecraft:flat ".into(),
                size: 1,
            }]),
        );
        assert!(state.commit_world("0193d3a4-1111-7000-8000-000000000001"));
        state.game_rules.insert("keepInventory".into(), "true".into());
        add_friend(&mut state, "66a000000000000000000010");
        add_friend(&mut state, "66a000000000000000000011");
        let Some(Submission::Create(request)) = state.submit() else { panic!() };
        assert_eq!(serde_json::to_value(&request).unwrap(), fixture("create-template"));

        let mut state = FormState::create(ME, "玩家", PACK, "包", "1.0", "V211");
        state.binding_loaded(Ok(None));
        state.host_name = " 房间 ".into();
        state.difficulty = 1;
        state.game_mode = 1;
        state.select_level_choice(2);
        state.allow_cheats = true;
        state.whitelist = false;
        add_friend(&mut state, "66a000000000000000000010");
        let Some(Submission::Create(request)) = state.submit() else { panic!() };
        assert_eq!(serde_json::to_value(&request).unwrap(), fixture("create-generate"));
    }

    #[test]
    fn option_and_delete_bodies_match_kotlin() {
        let host: HostDetail = serde_json::from_value(serde_json::json!({
            "_id":"66a000000000000000000001","name":"房间","ownerId":ME,
            "modpack":{"id":PACK,"name":"包","mcVer":"V211"},"packVer":"1.0","version":2,
            "difficulty":3,"gameMode":0,"levelType":"minecraft:normal","whitelist":true
        }))
        .unwrap();
        let mut state = FormState::edit(ME, "玩家", &host.id);
        state.host_loaded(Ok(host.clone()));
        state.host_name = "修改后的名称".into();
        state.difficulty = 2;
        state.game_rules.insert("randomTickSpeed".into(), "10".into());
        let Some(Submission::Edit { options, .. }) = state.submit() else { panic!() };
        assert_eq!(serde_json::to_value(&options).unwrap(), fixture("options-unchanged-terrain"));

        let mut state = FormState::edit(ME, "玩家", &host.id);
        state.host_loaded(Ok(host));
        state.select_level_choice(1);
        let Some(Submission::Edit { options, .. }) = state.submit() else { panic!() };
        assert_eq!(serde_json::to_value(&options).unwrap(), fixture("options-terrain"));

        let keep = serde_json::to_value(DeleteRequest { delete_world: false }).unwrap();
        assert_eq!(keep, fixture("delete-keep-world"));
        let drop = serde_json::to_value(DeleteRequest { delete_world: true }).unwrap();
        assert_eq!(drop, fixture("delete-world"));
    }
}
