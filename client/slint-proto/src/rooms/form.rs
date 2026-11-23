//! The room form of Compose `HostCreateViewModel` as a plain state struct. No I/O: the caller
//! runs the requests the methods ask for and reports back with the generation it was given.
use super::model::*;
use std::collections::BTreeMap;

pub const MAX_MEMBERS: usize = 20;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WorldSource {
    Generate,
    Template,
}

/// What submitting sends.
#[derive(Clone, Debug, PartialEq)]
pub enum Submission {
    Create(CreateRequest),
    Edit { host: String, options: OptionsRequest },
}

#[derive(Clone, Debug)]
pub struct FormState {
    pub loading: bool,
    pub edit_host: Option<HostDetail>,
    pub edit_host_id: Option<String>,
    pub title: String,
    pub host_name: String,
    pub pack_id: Option<String>,
    pub pack_name: String,
    pub version: String,
    /// The pack's minor Minecraft version, for the skyblock terrain id.
    pub mc_minor: Option<u32>,
    pub difficulty: i32,
    pub game_mode: i32,
    pub level_type: String,
    pub level_choice: i32,
    pub custom_text: String,
    pub custom_error: Option<String>,
    pub level_type_dirty: bool,
    pub world_source: WorldSource,
    pub selected_world: Option<String>,
    pub worlds: Vec<BaseWorld>,
    pub worlds_loading: bool,
    pub worlds_error: Option<String>,
    pub world_dialog: bool,
    pub binding: Option<BaseWorldBinding>,
    pub binding_error: Option<String>,
    pub world_overridden: bool,
    pub whitelist: bool,
    pub allow_cheats: bool,
    pub game_rules: BTreeMap<String, String>,
    /// An error dialog (Compose `errorMessage`).
    pub error: Option<String>,
    /// A refusal or failure dialog (Compose `statusMessage`).
    pub status: Option<String>,
    pub submitting: bool,
    pub updating_pack: bool,
    pub friend_qq: String,
    pub friend_loading: bool,
    pub friend_error: Option<String>,
    pub friend_dialog: bool,
    pub friends: Vec<Friend>,
    pub me: String,
    friend_generation: u64,
    world_generation: u64,
}

/// `skyblockLevelType`: the old id up to Minecraft 1.16.
pub fn skyblock_level_type(minor: Option<u32>) -> &'static str {
    match minor {
        Some(minor) if minor <= 16 => "skyblockbuilder:custom_skyblock",
        _ => "skyblockbuilder:skyblock",
    }
}

/// `levelChoice`: which terrain card an existing room's level type selects.
pub fn level_choice(level_type: &str) -> i32 {
    if level_type.to_lowercase().contains("skyblock") {
        2
    } else if level_type == "minecraft:flat" {
        1
    } else if level_type == "minecraft:normal" || level_type.trim().is_empty() {
        0
    } else {
        3
    }
}

/// `HostNewCreateScreen.defaultHostName`: `<player>的<first 4 characters of the pack>`.
pub fn default_name(player: &str, pack: &str) -> String {
    let prefix: String = pack.trim().chars().take(4).collect();
    if prefix.is_empty() {
        format!("{player}的世界")
    } else {
        format!("{player}的{prefix}")
    }
}

impl FormState {
    fn blank(me: &str, host_name: String) -> Self {
        Self {
            loading: true,
            edit_host: None,
            edit_host_id: None,
            title: "创建新房间".into(),
            host_name,
            pack_id: None,
            pack_name: String::new(),
            version: String::new(),
            mc_minor: None,
            difficulty: 3,
            game_mode: 0,
            level_type: "minecraft:normal".into(),
            level_choice: 0,
            custom_text: String::new(),
            custom_error: None,
            level_type_dirty: false,
            world_source: WorldSource::Generate,
            selected_world: None,
            worlds: Vec::new(),
            worlds_loading: false,
            worlds_error: None,
            world_dialog: false,
            binding: None,
            binding_error: None,
            world_overridden: false,
            whitelist: true,
            allow_cheats: false,
            game_rules: BTreeMap::new(),
            error: None,
            status: None,
            submitting: false,
            updating_pack: false,
            friend_qq: String::new(),
            friend_loading: false,
            friend_error: None,
            friend_dialog: false,
            friends: Vec::new(),
            me: me.into(),
            friend_generation: 0,
            world_generation: 0,
        }
    }

    /// Create mode, entered from a pack version. The caller loads the version's binding next.
    pub fn create(me: &str, player: &str, pack_id: &str, pack_name: &str, version: &str, mc_ver: &str) -> Self {
        let mut state = Self::blank(me, default_name(player, pack_name));
        let pack_id = pack_id.trim();
        state.pack_id = (!pack_id.is_empty()).then(|| pack_id.into());
        state.pack_name = pack_name.trim().into();
        state.version = version.trim().into();
        state.mc_minor = mc_minor(mc_ver);
        state
    }

    /// Edit mode; `loading` stays set until `host_loaded`.
    pub fn edit(me: &str, player: &str, host_id: &str) -> Self {
        let mut state = Self::blank(me, default_name(player, ""));
        state.edit_host_id = Some(host_id.into());
        state
    }

    pub fn is_edit(&self) -> bool {
        self.edit_host_id.is_some()
    }
    pub fn is_create(&self) -> bool {
        !self.is_edit()
    }
    pub fn can_submit(&self) -> bool {
        !self.loading && !self.submitting && !self.friend_loading && (!self.is_create() || !self.friends.is_empty())
    }
    /// `selectedPackTitle`.
    pub fn pack_title(&self) -> &str {
        if self.pack_name.trim().is_empty() { "未选择整合包" } else { &self.pack_name }
    }
    pub fn required(&self) -> bool {
        self.binding.as_ref().is_some_and(|b| b.required)
    }

    /// Create mode: the version binding arrived (`applyInitial` without a host). Returns whether
    /// the template list should load now.
    pub fn binding_loaded(&mut self, result: Result<Option<BaseWorldBinding>, String>) -> bool {
        self.loading = false;
        match result {
            Ok(binding) => {
                if let Some(binding) = &binding {
                    self.selected_world = Some(binding.id.clone());
                    self.world_source = WorldSource::Template;
                }
                let load = binding.is_some();
                self.binding = binding;
                load
            }
            Err(message) => {
                self.error = Some(format!("无法载入房间创建数据: {message}"));
                false
            }
        }
    }

    /// Edit mode: the room arrived (`applyInitial` with a host).
    pub fn host_loaded(&mut self, result: Result<HostDetail, String>) {
        self.loading = false;
        let host = match result {
            Ok(host) => host,
            Err(message) => {
                self.error = Some(format!("无法加载房间信息: {message}"));
                return;
            }
        };
        let minor = mc_minor(&host.modpack.mc_ver);
        let choice = level_choice(&host.level_type);
        self.title = format!("编辑房间 · {}", host.name);
        self.host_name = host.name.clone();
        self.pack_id = Some(host.modpack.id.clone());
        self.pack_name = host.modpack.name.clone();
        self.version = host.pack_ver.clone();
        self.difficulty = host.difficulty;
        self.game_mode = host.game_mode;
        self.mc_minor = minor;
        self.level_type = match choice {
            0 => "minecraft:normal".into(),
            1 => "minecraft:flat".into(),
            2 => skyblock_level_type(minor).into(),
            _ => host.level_type.clone(),
        };
        self.level_choice = choice;
        self.custom_text = if choice == 3 { host.level_type.clone() } else { String::new() };
        self.level_type_dirty = false;
        self.whitelist = host.whitelist;
        self.allow_cheats = host.allow_cheats;
        self.game_rules = host.game_rules.clone();
        self.edit_host = Some(host);
    }

    fn choose_generate(&mut self) {
        self.world_source = WorldSource::Generate;
        self.selected_world = None;
        self.world_overridden = self.binding.is_some();
        self.binding_error = None;
    }

    /// `selectLevelChoice`; ignored when the version requires its template.
    pub fn select_level_choice(&mut self, choice: i32) {
        if self.required() {
            return;
        }
        match choice {
            0 => self.level_type = "minecraft:normal".into(),
            1 => self.level_type = "minecraft:flat".into(),
            2 => self.level_type = skyblock_level_type(self.mc_minor).into(),
            3 => {}
            _ => return,
        }
        if choice != 3 {
            self.level_type_dirty = true;
        }
        self.level_choice = choice;
        self.choose_generate();
    }
    pub fn begin_custom(&mut self) {
        if self.required() {
            return;
        }
        if self.level_choice == 3 {
            self.custom_text = self.level_type.clone();
        }
        self.custom_error = None;
    }
    pub fn update_custom_text(&mut self, text: &str) {
        self.custom_text = text.into();
        self.custom_error = None;
    }
    /// `applyCustomLevelType`; true closes the dialog.
    pub fn apply_custom(&mut self) -> bool {
        if self.required() {
            return false;
        }
        let trimmed = self.custom_text.trim().to_owned();
        if trimmed.is_empty() {
            self.custom_error = Some("请输入地形ID".into());
            return false;
        }
        self.level_choice = 3;
        self.level_type = trimmed.clone();
        self.custom_text = trimmed;
        self.custom_error = None;
        self.level_type_dirty = true;
        self.choose_generate();
        true
    }
    pub fn cancel_custom(&mut self) {
        self.custom_error = None;
    }

    fn template_allowed(&self) -> bool {
        self.is_create() && (self.world_dialog || self.world_source == WorldSource::Template)
    }
    /// `openBaseWorldSelection`; returns the load generation when the list should load.
    pub fn open_world_dialog(&mut self) -> Option<u64> {
        if !self.is_create() || self.required() || self.submitting {
            return None;
        }
        self.world_dialog = true;
        self.worlds_error = None;
        self.load_worlds()
    }
    /// `loadBaseWorldChoices`; returns the generation the reply must carry.
    pub fn load_worlds(&mut self) -> Option<u64> {
        if !self.template_allowed() {
            return None;
        }
        self.world_generation += 1;
        self.worlds_loading = true;
        self.worlds_error = None;
        Some(self.world_generation)
    }
    /// `cancelBaseWorldSelection`.
    pub fn close_world_dialog(&mut self) {
        let cancel = self.world_source == WorldSource::Generate;
        if cancel {
            self.world_generation += 1;
            self.worlds_loading = false;
        }
        self.world_dialog = false;
        self.worlds_error = None;
    }
    /// `commitBaseWorldSelection`.
    pub fn commit_world(&mut self, id: &str) -> bool {
        if !self.template_allowed() || self.submitting || self.required() || !self.worlds.iter().any(|w| w.id == id) {
            return false;
        }
        self.world_source = WorldSource::Template;
        self.selected_world = Some(id.into());
        self.world_dialog = false;
        self.world_overridden = self.binding.is_some();
        self.worlds_error = None;
        true
    }
    /// The template list arrived (`applyBaseWorldChoices` or the failure branch).
    pub fn worlds_loaded(&mut self, generation: u64, result: Result<Vec<BaseWorld>, String>) {
        if generation != self.world_generation || !self.template_allowed() {
            return;
        }
        let worlds = match result {
            Ok(worlds) => worlds,
            Err(message) => {
                self.worlds_loading = false;
                self.worlds_error = Some(if message.is_empty() { "加载地图模板失败".into() } else { message });
                return;
            }
        };
        let has = |id: &Option<String>| id.as_ref().is_some_and(|id| worlds.iter().any(|w| &w.id == id));
        let selected_ok = has(&self.selected_world);
        let configured = self.binding.as_ref().map(|b| b.id.clone());
        let configured_ok = has(&configured);
        let apply_configured = self.binding.is_some() && !self.world_overridden && configured_ok;
        let selected = if selected_ok {
            self.selected_world.clone()
        } else if apply_configured {
            configured
        } else {
            None
        };
        let unresolved = self.binding.is_some() && !self.world_overridden && !configured_ok;
        self.world_source = if selected.is_some() || unresolved {
            WorldSource::Template
        } else if self.world_source == WorldSource::Template {
            WorldSource::Generate
        } else {
            self.world_source
        };
        self.binding_error = unresolved.then(|| {
            if self.required() {
                "此版本指定的地图模板不可用，请联系整合包管理者".into()
            } else {
                "此版本推荐的地图模板不可用，请重新选择地图模板".into()
            }
        });
        self.selected_world = selected;
        self.worlds = worlds;
        self.worlds_loading = false;
        self.worlds_error = None;
    }
    pub fn selected_world(&self) -> Option<&BaseWorld> {
        let id = self.selected_world.as_ref()?;
        self.worlds.iter().find(|w| &w.id == id)
    }
    /// The template card's title: 模板·名称 once a listed template is chosen.
    pub fn template_title(&self) -> String {
        self.selected_world().map_or("模板".into(), |w| format!("模板·{}", w.name))
    }

    pub fn open_friend_dialog(&mut self) {
        if !self.is_create() || self.submitting {
            return;
        }
        self.update_friend_qq("");
        self.friend_dialog = true;
    }
    pub fn dismiss_friend_dialog(&mut self) {
        self.update_friend_qq("");
        self.friend_dialog = false;
    }
    /// Any edit cancels a running lookup.
    pub fn update_friend_qq(&mut self, value: &str) {
        if self.submitting {
            return;
        }
        self.friend_generation += 1;
        self.friend_qq = value.into();
        self.friend_loading = false;
        self.friend_error = None;
    }
    /// `addFriend`; returns `(generation, qq)` for the lookup to run.
    pub fn add_friend(&mut self) -> Option<(u64, String)> {
        if !self.is_create() || !self.friend_dialog || self.submitting || self.friend_loading {
            return None;
        }
        if self.friends.len() >= MAX_MEMBERS - 1 {
            self.friend_error = Some(format!("最多添加{}位好友", MAX_MEMBERS - 1));
            return None;
        }
        let qq = self.friend_qq.trim().to_owned();
        if qq.is_empty() || qq.chars().count() > 20 || !qq.chars().all(|c| c.is_ascii_digit()) {
            self.friend_error = Some("请输入正确的QQ号".into());
            return None;
        }
        self.friend_generation += 1;
        self.friend_loading = true;
        self.friend_error = None;
        Some((self.friend_generation, qq))
    }
    pub fn friend_found(&mut self, generation: u64, result: Result<Friend, String>) {
        if generation != self.friend_generation {
            return;
        }
        self.friend_loading = false;
        match result {
            Ok(friend) if friend.id == self.me => self.friend_error = Some("不能邀请自己，请添加其他好友".into()),
            Ok(friend) if self.friends.iter().any(|f| f.id == friend.id) => {
                self.friend_error = Some("已添加这位好友".into())
            }
            Ok(friend) => {
                self.friends.push(friend);
                self.friend_qq.clear();
                self.friend_dialog = false;
                self.friend_error = None;
            }
            Err(message) => {
                self.friend_error = Some(if message.is_empty() { "添加失败，请重试".into() } else { message })
            }
        }
    }
    pub fn remove_friend(&mut self, id: &str) {
        if !self.submitting {
            self.friends.retain(|f| f.id != id);
        }
    }

    /// `submit`: either the request to send (and `submitting` is set) or a status message.
    pub fn submit(&mut self) -> Option<Submission> {
        if self.loading || self.submitting || self.friend_loading {
            return None;
        }
        let name = self.host_name.trim().to_owned();
        if name.is_empty() {
            self.status = Some("请输入房间名称".into());
            return None;
        }
        let submission = match self.edit_host_id.clone() {
            Some(host) => Submission::Edit {
                host,
                options: OptionsRequest {
                    name,
                    difficulty: self.difficulty,
                    game_mode: self.game_mode,
                    level_type: self.level_type_dirty.then(|| self.level_type.clone()),
                    whitelist: self.whitelist,
                    allow_cheats: self.allow_cheats,
                    game_rules: self.game_rules.clone(),
                },
            },
            None => match self.create_request(name) {
                Ok(request) => Submission::Create(request),
                Err(message) => {
                    self.status = Some(message);
                    return None;
                }
            },
        };
        self.submitting = true;
        self.status = None;
        Some(submission)
    }

    fn create_request(&self, name: String) -> Result<CreateRequest, String> {
        let pack = self.pack_id.as_deref().ok_or("请从“我的整合包”的菜单发起创建多人房间")?;
        let valid_pack = pack.len() == 24 && pack.bytes().all(|b| b.is_ascii_hexdigit());
        if !valid_pack || self.version.trim().is_empty() {
            return Err("整合包来源或版本无效，请从“我的整合包”的菜单发起创建多人房间".into());
        }
        if let Some(message) = &self.binding_error {
            return Err(message.clone());
        }
        if self.world_source == WorldSource::Generate && self.level_choice == 3 && self.level_type.trim().is_empty() {
            return Err("自定义地形不能为空".into());
        }
        let world = if self.world_source == WorldSource::Template {
            if self.worlds_loading {
                return Err("地图模板正在加载，请稍候".into());
            }
            Some(self.selected_world().ok_or("请选择可用的地图模板")?)
        } else {
            if self.required() {
                return Err("此版本必须使用指定的地图模板".into());
            }
            None
        };
        if let Some(binding) = self.binding.as_ref().filter(|b| b.required)
            && world.map(|w| &w.id) != Some(&binding.id)
        {
            return Err("此版本指定的地图模板不可用".into());
        }
        if self.friends.is_empty() {
            return Err("至少邀请1位好友一起玩".into());
        }
        Ok(CreateRequest {
            name,
            modpack_id: pack.into(),
            pack_ver: self.version.trim().into(),
            difficulty: self.difficulty,
            game_mode: self.game_mode,
            level_type: world.map_or(self.level_type.as_str(), |w| &w.level_type).trim().into(),
            allow_cheats: self.allow_cheats,
            whitelist: self.whitelist,
            game_rules: self.game_rules.clone(),
            base_world_id: world.map(|w| w.id.clone()),
            initial_member_ids: self.friends.iter().map(|f| f.id.clone()).collect(),
        })
    }

    /// The reply to `submit`: `Ok(message)` is the success dialog, after which the page closes.
    pub fn submitted(&mut self, result: Result<(), String>) -> Option<String> {
        self.submitting = false;
        let prefix = if self.is_edit() { "保存失败" } else { "创建失败" };
        match result {
            Ok(()) => Some(if self.is_edit() { "设置已保存" } else { "房间创建中，服务器准备完成后会通过邮箱通知你" }.into()),
            Err(message) => {
                let message = if message.is_empty() { "未知错误".into() } else { message };
                self.status = Some(format!("{prefix}: {message}"));
                None
            }
        }
    }

    /// `requestHostPackUpdate`; returns the room to update.
    pub fn start_pack_update(&mut self) -> Option<String> {
        if self.updating_pack {
            return None;
        }
        let host = self.edit_host_id.clone()?;
        self.updating_pack = true;
        self.error = None;
        Some(host)
    }
    pub fn pack_updated(&mut self, result: Result<(), String>) -> Option<String> {
        self.updating_pack = false;
        match result {
            Ok(()) => Some("已提交更新".into()),
            Err(message) => {
                self.error = Some(if message.is_empty() { "更新失败".into() } else { message });
                None
            }
        }
    }
    /// The 更新整合包 confirmation text.
    pub fn pack_update_message(&self) -> String {
        let name = self.edit_host.as_ref().map_or("", |h| h.modpack.name.as_str());
        format!("将更新房间当前的整合包《{name}》到最新版本。所有修改过的配置都会丢失。")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PACK: &str = "66a000000000000000000002";
    const ME: &str = "66a0000000000000000000aa";

    fn world(id: &str, level: &str) -> BaseWorld {
        BaseWorld { id: id.into(), owner_id: String::new(), name: format!("模板{id}"), level_type: level.into(), size: 1 }
    }
    fn friend(id: &str) -> Friend {
        Friend { id: id.into(), name: format!("好友{id}") }
    }
    fn created() -> FormState {
        let mut state = FormState::create(ME, "玩家", PACK, "测试整合包", "2.0.0", "V201");
        assert!(!state.binding_loaded(Ok(None)));
        state
    }
    fn with_friend(mut state: FormState) -> FormState {
        state.open_friend_dialog();
        state.update_friend_qq(" 12345678 ");
        let (generation, qq) = state.add_friend().unwrap();
        assert_eq!(qq, "12345678");
        state.friend_found(generation, Ok(friend("f1")));
        state
    }
    fn host_detail(level: &str) -> HostDetail {
        serde_json::from_value(serde_json::json!({
            "_id":"66a000000000000000000001","name":"编辑中的房间","ownerId":ME,
            "modpack":{"id":PACK,"name":"测试整合包","mcVer":"V201"},"packVer":"latest","version":2,
            "difficulty":1,"gameMode":1,"levelType":level,"whitelist":false,"allowCheats":true,
            "gameRules":{"keepInventory":"true"}
        }))
        .unwrap()
    }

    #[test]
    fn defaults_and_default_names() {
        let state = created();
        assert_eq!(state.host_name, "玩家的测试整合");
        assert_eq!(default_name("玩家", "  "), "玩家的世界");
        assert_eq!(default_name("玩家", "ab"), "玩家的ab");
        assert_eq!((state.difficulty, state.game_mode), (3, 0));
        assert_eq!(state.level_type, "minecraft:normal");
        assert!(state.whitelist && !state.allow_cheats);
        assert_eq!(state.title, "创建新房间");
        assert_eq!(state.pack_title(), "测试整合包");
        assert!(!state.can_submit(), "a create needs one friend");
        assert_eq!(FormState::create(ME, "玩家", "", "", "", "").pack_title(), "未选择整合包");
    }

    #[test]
    fn create_submission_preserves_exact_options() {
        let mut state = with_friend(created());
        state.whitelist = false;
        state.game_rules.insert("keepInventory".into(), "true".into());
        let Some(Submission::Create(request)) = state.submit() else { panic!() };
        assert_eq!(request.modpack_id, PACK);
        assert_eq!(request.pack_ver, "2.0.0");
        assert!(!request.whitelist);
        assert_eq!(request.initial_member_ids, ["f1"]);
        assert!(request.base_world_id.is_none());
        assert!(state.submitting);
        assert!(state.submit().is_none(), "a second submit is ignored while one runs");
        assert_eq!(state.submitted(Ok(())).unwrap(), "房间创建中，服务器准备完成后会通过邮箱通知你");
        assert!(!state.submitting);
    }

    #[test]
    fn terrain_choices_and_skyblock_ids() {
        let mut state = created();
        state.select_level_choice(2);
        assert_eq!(state.level_type, "skyblockbuilder:skyblock");
        let mut old = FormState::create(ME, "玩家", PACK, "包", "1", "V071");
        old.binding_loaded(Ok(None));
        old.select_level_choice(2);
        assert_eq!(old.level_type, "skyblockbuilder:custom_skyblock");
        state.select_level_choice(1);
        assert_eq!(state.level_type, "minecraft:flat");
        state.select_level_choice(3);
        state.begin_custom();
        state.update_custom_text("  ");
        assert!(!state.apply_custom());
        assert_eq!(state.custom_error.as_deref(), Some("请输入地形ID"));
        state.update_custom_text(" mod:terrain ");
        assert!(state.apply_custom());
        assert_eq!((state.level_type.as_str(), state.level_choice), ("mod:terrain", 3));
        state.begin_custom();
        assert_eq!(state.custom_text, "mod:terrain");
        state.select_level_choice(0);
        assert_eq!(state.level_type, "minecraft:normal");
        assert!(state.level_type_dirty);
        assert_eq!(level_choice("Mod:SkyBlock_x"), 2);
        assert_eq!(level_choice(""), 0);
        assert_eq!(level_choice("other:x"), 3);
    }

    #[test]
    fn modern_skyblock_create_body() {
        let mut state = with_friend(created());
        state.select_level_choice(2);
        let Some(Submission::Create(request)) = state.submit() else { panic!() };
        assert_eq!(request.level_type, "skyblockbuilder:skyblock");
    }

    #[test]
    fn required_binding_selects_template_and_locks_terrain() {
        let mut state = FormState::create(ME, "玩家", PACK, "包", "1", "V211");
        let binding = BaseWorldBinding { id: "w1".into(), required: true };
        assert!(state.binding_loaded(Ok(Some(binding))));
        assert_eq!(state.world_source, WorldSource::Template);
        let generation = state.load_worlds().unwrap();
        state.worlds_loaded(generation, Ok(vec![world("w1", "minecraft:flat"), world("w2", "x")]));
        assert_eq!(state.selected_world.as_deref(), Some("w1"));
        state.select_level_choice(0);
        assert_eq!(state.world_source, WorldSource::Template, "terrain cards are locked");
        assert!(state.open_world_dialog().is_none());
        let mut state = with_friend(state);
        let Some(Submission::Create(request)) = state.submit() else { panic!() };
        assert_eq!(request.base_world_id.as_deref(), Some("w1"));
        assert_eq!(request.level_type, "minecraft:flat", "the template's terrain is sent");
    }

    #[test]
    fn unavailable_bound_template_blocks_submit() {
        for (required, message) in [
            (true, "此版本指定的地图模板不可用，请联系整合包管理者"),
            (false, "此版本推荐的地图模板不可用，请重新选择地图模板"),
        ] {
            let mut state = FormState::create(ME, "玩家", PACK, "包", "1", "V211");
            state.binding_loaded(Ok(Some(BaseWorldBinding { id: "gone".into(), required })));
            let generation = state.load_worlds().unwrap();
            state.worlds_loaded(generation, Ok(vec![world("w2", "x")]));
            assert_eq!(state.binding_error.as_deref(), Some(message));
            let mut state = with_friend(state);
            assert!(state.submit().is_none());
            assert_eq!(state.status.as_deref(), Some(message));
        }
    }

    #[test]
    fn optional_binding_keeps_a_manual_choice_after_refresh() {
        let mut state = FormState::create(ME, "玩家", PACK, "包", "1", "V211");
        state.binding_loaded(Ok(Some(BaseWorldBinding { id: "w1".into(), required: false })));
        let generation = state.open_world_dialog().unwrap();
        state.worlds_loaded(generation, Ok(vec![world("w1", "a"), world("w2", "b")]));
        assert!(state.commit_world("w2"));
        assert!(state.world_overridden);
        let generation = state.load_worlds().unwrap();
        state.worlds_loaded(generation, Ok(vec![world("w1", "a"), world("w2", "b")]));
        assert_eq!(state.selected_world.as_deref(), Some("w2"));
        assert_eq!(state.template_title(), "模板·模板w2");
        state.select_level_choice(1);
        assert_eq!(state.world_source, WorldSource::Generate);
        assert!(state.selected_world.is_none() && state.binding_error.is_none());
    }

    #[test]
    fn template_list_errors_and_stale_replies() {
        let mut state = created();
        let first = state.open_world_dialog().unwrap();
        let second = state.load_worlds().unwrap();
        state.worlds_loaded(first, Ok(vec![world("old", "x")]));
        assert!(state.worlds.is_empty() && state.worlds_loading, "a stale reply is dropped");
        state.worlds_loaded(second, Err(String::new()));
        assert_eq!(state.worlds_error.as_deref(), Some("加载地图模板失败"));
        assert!(!state.commit_world("old"));
        state.close_world_dialog();
        assert!(!state.world_dialog && state.worlds_error.is_none());
        let generation = state.load_worlds();
        assert!(generation.is_none(), "closed with generated terrain: nothing to load");
    }

    #[test]
    fn template_choice_falls_back_when_it_disappears() {
        let mut state = with_friend(created());
        let generation = state.open_world_dialog().unwrap();
        state.worlds_loaded(generation, Ok(vec![world("w1", "a")]));
        assert!(state.commit_world("w1"));
        let generation = state.load_worlds().unwrap();
        state.worlds_loaded(generation, Ok(vec![]));
        assert_eq!(state.world_source, WorldSource::Generate);
        let generation = state.open_world_dialog().unwrap();
        state.worlds_loading = true;
        state.world_source = WorldSource::Template;
        let _ = generation;
        assert!(state.submit().is_none());
        assert_eq!(state.status.as_deref(), Some("地图模板正在加载，请稍候"));
    }

    #[test]
    fn friend_rules() {
        let mut state = created();
        state.open_friend_dialog();
        for bad in ["", "12a", "123456789012345678901"] {
            state.update_friend_qq(bad);
            assert!(state.add_friend().is_none());
            assert_eq!(state.friend_error.as_deref(), Some("请输入正确的QQ号"));
        }
        state.update_friend_qq("1");
        let (generation, _) = state.add_friend().unwrap();
        state.friend_found(generation, Ok(friend(ME)));
        assert_eq!(state.friend_error.as_deref(), Some("不能邀请自己，请添加其他好友"));
        assert!(state.friend_dialog && state.friends.is_empty());
        let (generation, _) = state.add_friend().unwrap();
        state.friend_found(generation, Err("无此账号".into()));
        assert_eq!(state.friend_error.as_deref(), Some("无此账号"));
        assert!(!state.can_submit());
        let (generation, _) = state.add_friend().unwrap();
        state.friend_found(generation, Ok(friend("f1")));
        assert!(!state.friend_dialog && state.can_submit());
        state.open_friend_dialog();
        assert_eq!(state.friend_qq, "");
        state.update_friend_qq("2");
        let (generation, _) = state.add_friend().unwrap();
        state.friend_found(generation, Ok(friend("f1")));
        assert_eq!(state.friend_error.as_deref(), Some("已添加这位好友"));
        state.update_friend_qq("3");
        let (generation, _) = state.add_friend().unwrap();
        state.friend_found(generation, Err(String::new()));
        assert_eq!(state.friend_error.as_deref(), Some("添加失败，请重试"));
        state.remove_friend("f1");
        assert!(!state.can_submit());
        assert!(state.submit().is_none());
        assert_eq!(state.status.as_deref(), Some("至少邀请1位好友一起玩"));
    }

    #[test]
    fn newer_lookup_or_dismissal_cancels_an_older_one() {
        let mut state = created();
        state.open_friend_dialog();
        state.update_friend_qq("1");
        let (old, _) = state.add_friend().unwrap();
        assert!(state.add_friend().is_none(), "one lookup at a time");
        state.dismiss_friend_dialog();
        state.friend_found(old, Ok(friend("f1")));
        assert!(state.friends.is_empty() && !state.friend_loading && !state.friend_dialog);
        state.open_friend_dialog();
        state.update_friend_qq("1");
        let (old, _) = state.add_friend().unwrap();
        state.update_friend_qq("2");
        let (new, _) = state.add_friend().unwrap();
        state.friend_found(old, Ok(friend("f1")));
        assert!(state.friends.is_empty() && state.friend_loading);
        state.friend_found(new, Ok(friend("f2")));
        assert_eq!(state.friends, [friend("f2")]);
    }

    #[test]
    fn friend_limit_is_nineteen() {
        let mut state = created();
        state.friends = (0..19).map(|i| friend(&format!("f{i}"))).collect();
        state.open_friend_dialog();
        state.update_friend_qq("1");
        assert!(state.add_friend().is_none());
        assert_eq!(state.friend_error.as_deref(), Some("最多添加19位好友"));
    }

    #[test]
    fn blank_name_and_entry_point_messages() {
        let mut state = with_friend(created());
        state.host_name = "  ".into();
        assert!(state.submit().is_none());
        assert_eq!(state.status.as_deref(), Some("请输入房间名称"));
        let mut state = with_friend(FormState::create(ME, "玩家", "", "包", "1", "V211"));
        state.binding_loaded(Ok(None));
        state.submit();
        assert_eq!(state.status.as_deref(), Some("请从“我的整合包”的菜单发起创建多人房间"));
        let mut state = with_friend(FormState::create(ME, "玩家", "bad", "包", "1", "V211"));
        state.binding_loaded(Ok(None));
        state.submit();
        assert_eq!(state.status.as_deref(), Some("整合包来源或版本无效，请从“我的整合包”的菜单发起创建多人房间"));
        let mut state = with_friend(created());
        state.level_choice = 3;
        state.level_type = " ".into();
        state.submit();
        assert_eq!(state.status.as_deref(), Some("自定义地形不能为空"));
        let mut state = FormState::create(ME, "玩家", PACK, "包", "1", "V211");
        state.binding_loaded(Err("超时".into()));
        assert_eq!(state.error.as_deref(), Some("无法载入房间创建数据: 超时"));
        assert!(!state.loading);
    }

    #[test]
    fn edit_mode_loads_host_and_sends_only_options() {
        let mut state = FormState::edit(ME, "玩家", "66a000000000000000000001");
        assert!(state.loading && !state.can_submit());
        state.host_loaded(Ok(host_detail("skyblockbuilder:custom_skyblock")));
        assert_eq!(state.title, "编辑房间 · 编辑中的房间");
        assert_eq!(state.version, "latest");
        assert_eq!(state.level_type, "skyblockbuilder:skyblock", "mapped for the pack's version");
        assert_eq!(state.level_choice, 2);
        assert!(state.can_submit(), "edits need no friends");
        state.host_name = "修改后的名称".into();
        state.whitelist = true;
        let Some(Submission::Edit { host, options }) = state.submit() else { panic!() };
        assert_eq!(host, "66a000000000000000000001");
        assert_eq!(options.name, "修改后的名称");
        assert!(options.whitelist && options.allow_cheats);
        assert_eq!(options.level_type, None, "unchanged terrain is not sent");
        assert_eq!(options.game_rules["keepInventory"], "true");
        assert_eq!(state.submitted(Ok(())).unwrap(), "设置已保存");
        state.select_level_choice(1);
        let Some(Submission::Edit { options, .. }) = state.submit() else { panic!() };
        assert_eq!(options.level_type.as_deref(), Some("minecraft:flat"));
        assert!(state.submitted(Err("拒绝".into())).is_none());
        assert_eq!(state.status.as_deref(), Some("保存失败: 拒绝"));

        let mut custom = FormState::edit(ME, "玩家", "h");
        custom.host_loaded(Ok(host_detail("mod:terrain")));
        assert_eq!((custom.level_choice, custom.custom_text.as_str()), (3, "mod:terrain"));
        assert!(custom.open_world_dialog().is_none(), "templates are create-only");
        custom.open_friend_dialog();
        assert!(!custom.friend_dialog, "friends are create-only");

        let mut failed = FormState::edit(ME, "玩家", "h");
        failed.host_loaded(Err("无此房间".into()));
        assert_eq!(failed.error.as_deref(), Some("无法加载房间信息: 无此房间"));
    }

    #[test]
    fn pack_update_flow() {
        let mut state = FormState::edit(ME, "玩家", "h");
        state.host_loaded(Ok(host_detail("minecraft:normal")));
        assert_eq!(
            state.pack_update_message(),
            "将更新房间当前的整合包《测试整合包》到最新版本。所有修改过的配置都会丢失。"
        );
        assert_eq!(state.start_pack_update().as_deref(), Some("h"));
        assert!(state.start_pack_update().is_none());
        assert_eq!(state.pack_updated(Ok(())).as_deref(), Some("已提交更新"));
        state.start_pack_update();
        assert!(state.pack_updated(Err(String::new())).is_none());
        assert_eq!(state.error.as_deref(), Some("更新失败"));
        assert!(created().start_pack_update().is_none());
        let mut failed = created();
        failed.submitting = true;
        assert!(failed.submitted(Err(String::new())).is_none());
        assert_eq!(failed.status.as_deref(), Some("创建失败: 未知错误"));
    }
}
