//! Wiring of the room screens (U4 batch c1) to the `Rooms` Slint global. Shaped like `plaza.rs`:
//! one `action(name, value)` callback, epoch counters for stale replies, and a channel drained on
//! the UI thread. The room logic itself is in `rooms/` and has no Slint types.
use crate::{
    AppWindow, RoomFormView, RoomFriend, RoomInfo, RoomMember, RoomMenuEntry, RoomRule, RoomWorld, Rooms,
    api::{Client, model::PlayerInfo},
    app::name_placeholder,
    images,
    rooms::{
        form::{FormState, Submission, WorldSource},
        game_rules::{self, Draft, ValueType},
        model::*,
        permissions::{self, Me, MenuEntry, Pane},
        requests,
    },
};
use anyhow::Result;
use slint::{ComponentHandle, Model, SharedString, VecModel};
use std::{cell::RefCell, collections::HashMap, rc::Rc, sync::mpsc};
use tokio::runtime::Handle;

/// Option icons, decoded once from the Compose launcher's AVIF files.
const ICONS: [&[u8]; 9] = [
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/difficulty_peaceful.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/difficulty_easy.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/difficulty_normal.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/difficulty_hard.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/gamemode_survival.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/gamemode_creative.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/worldtype_normal.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/worldtype_flat.avif"),
    include_bytes!("../../ui/assets/src/main/resources/assets/icons/worldtype_skyblock.avif"),
];

#[derive(Clone, Copy, Debug)]
enum Op {
    Invite,
    Role,
    Transfer,
    Kick,
    Quit,
    Delete,
}
impl Op {
    fn fallback(self) -> &'static str {
        match self {
            Op::Invite => "邀请失败",
            Op::Role => "操作失败",
            Op::Transfer => "转让失败",
            Op::Kick => "踢出失败",
            Op::Quit => "退出失败",
            Op::Delete => "删除房间失败",
        }
    }
}

enum Reply {
    Detail(u64, Result<HostDetail>),
    Members(u64, Result<Vec<Member>>),
    Player(String, Result<(String, image::RgbaImage)>),
    PackIcon(u64, image::RgbaImage),
    Op(u64, Op, Result<()>),
    FormHost(u64, Result<HostDetail>),
    Binding(u64, Result<Option<BaseWorldBinding>>),
    Worlds(u64, u64, Result<Vec<BaseWorld>>),
    Friend(u64, u64, Result<Friend>),
    Submitted(u64, Result<()>),
    PackUpdated(u64, Result<()>),
}

/// What the generic confirmation dialog does when confirmed.
enum Confirm {
    Member(MenuEntry, String),
    PackUpdate,
}

/// What closing the alert does next.
#[derive(Clone, Copy, PartialEq)]
enum AfterAlert {
    Nothing,
    Back,
    ClearFormError,
    ClearFormStatus,
}

pub struct RoomsUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    host: String,
    detail: Option<HostDetail>,
    members: Vec<Member>,
    detail_epoch: u64,
    members_epoch: u64,
    op_epoch: u64,
    form_epoch: u64,
    players: HashMap<String, (String, slint::Image)>,
    requested: std::collections::HashSet<String>,
    member_rows: Rc<VecModel<RoomMember>>,
    friend_rows: Rc<VecModel<RoomFriend>>,
    world_rows: Rc<VecModel<RoomWorld>>,
    form: Option<FormState>,
    draft: Option<Draft>,
    rule_rows: Rc<VecModel<RoomRule>>,
    confirm: Option<Confirm>,
    menu_member: Option<String>,
    after_alert: AfterAlert,
    icons_loaded: bool,
    fallback: slint::Image,
    last_screen: i32,
    just_opened: bool,
    hosts_changed: bool,
    /// Navigation runs from `tick`: the shell may call `dismiss` while it holds its own state.
    pending_nav: Vec<&'static str>,
}

fn rooms(ui: &AppWindow) -> Rooms<'_> {
    ui.global::<Rooms>()
}

fn hex_id(value: &str) -> bool {
    value.len() == 24 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn message(error: &anyhow::Error) -> String {
    error.to_string()
}

/// `/player/{uid}/info` plus the head from the player's skin; Steve without one.
pub(crate) async fn player(api: &Client, uid: &str) -> Result<(String, image::RgbaImage)> {
    let info: PlayerInfo = api.get(&format!("/player/{uid}/info")).await?;
    let head = match info.cloth.and_then(|c| c.skin).filter(|s| !s.trim().is_empty()) {
        Some(url) => images::head(&images::decode(&images::download(&api.http, &url).await?)?.to_rgba8())?,
        None => images::steve()?,
    };
    Ok((info.name, head))
}

/// A player's name and head from the cache: 「…」 while the request runs, 「未知玩家」 for a
/// missing id. A failed request is cached as 「未知玩家」 by `Reply::Player`.
fn cached_player(players: &HashMap<String, (String, slint::Image)>, uid: &str, fallback: &slint::Image) -> (String, slint::Image) {
    if uid.trim().is_empty() {
        return ("未知玩家".into(), fallback.clone());
    }
    players.get(uid).cloned().unwrap_or_else(|| ("…".into(), fallback.clone()))
}

/// Kotlin `UUID.toObjectId`: an ObjectId-derived UUID holds the 12 id bytes and four zero bytes.
/// `None` for any other UUID, where Compose's `objectId` would throw.
fn uuid_object_id(uuid: &str) -> Option<String> {
    let hex: String = uuid.trim().chars().filter(|c| *c != '-').collect::<String>().to_lowercase();
    if hex.len() != 32 || !hex.chars().all(|c| c.is_ascii_hexdigit()) || &hex[24..] != "00000000" {
        return None;
    }
    Some(hex[..24].to_owned())
}

/// `BaseWorldCard`: the template's name and its author (`HeadButton(ownerId.objectId)`).
fn world_row(world: &BaseWorld, players: &HashMap<String, (String, slint::Image)>, fallback: &slint::Image) -> RoomWorld {
    let owner = uuid_object_id(&world.owner_id).unwrap_or_default();
    let (owner_name, owner_head) = cached_player(players, &owner, fallback);
    RoomWorld { id: world.id.as_str().into(), name: world.name.as_str().into(), owner_name: owner_name.into(), owner_head }
}

impl RoomsUi {
    pub fn wire(ui: &AppWindow, runtime: Handle) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let member_rows = Rc::new(VecModel::default());
        let friend_rows = Rc::new(VecModel::default());
        let world_rows = Rc::new(VecModel::default());
        let rule_rows = Rc::new(VecModel::default());
        let global = rooms(ui);
        global.set_members(member_rows.clone().into());
        global.set_friends(friend_rows.clone().into());
        global.set_worlds(world_rows.clone().into());
        global.set_rules(rule_rows.clone().into());
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            api: None,
            tx,
            rx,
            host: String::new(),
            detail: None,
            members: Vec::new(),
            detail_epoch: 0,
            members_epoch: 0,
            op_epoch: 0,
            form_epoch: 0,
            players: HashMap::new(),
            requested: Default::default(),
            member_rows,
            friend_rows,
            world_rows,
            form: None,
            draft: None,
            rule_rows,
            confirm: None,
            menu_member: None,
            after_alert: AfterAlert::Nothing,
            icons_loaded: false,
            fallback: images::steve().map(|s| images::to_slint(&s)).unwrap_or_default(),
            last_screen: 0,
            just_opened: false,
            hosts_changed: false,
            pending_nav: Vec::new(),
        }));
        let weak = Rc::downgrade(&state);
        global.on_action(move |action, value| {
            let Some(state) = weak.upgrade() else { return };
            match state.try_borrow_mut() {
                Ok(mut state) => state.action(action.as_str(), value.as_str()),
                Err(_) => tracing::warn!(%action, "房间操作重入，已忽略"),
            }
        });
        let weak = Rc::downgrade(&state);
        global.on_rule_edited(move |id, text| {
            let Some(state) = weak.upgrade() else { return text };
            let Ok(mut state) = state.try_borrow_mut() else { return text };
            state.rule_edited(id.as_str(), text.as_str()).into()
        });
        state
    }

    /// A new sign-in or a sign-out: every room page and request is dropped.
    pub fn connect(&mut self, api: Option<Client>) {
        self.detail_epoch += 1;
        self.members_epoch += 1;
        self.op_epoch += 1;
        self.form_epoch += 1;
        self.api = api;
        self.host.clear();
        self.detail = None;
        self.members.clear();
        self.players.clear();
        self.requested.clear();
        self.form = None;
        self.draft = None;
        self.confirm = None;
        self.menu_member = None;
        self.after_alert = AfterAlert::Nothing;
        self.pending_nav.clear();
        self.member_rows.clear();
        self.friend_rows.clear();
        self.world_rows.clear();
        self.rule_rows.clear();
        if let Some(ui) = self.ui.upgrade() {
            let g = rooms(&ui);
            g.set_guide(false);
            g.set_menu_open(false);
            g.set_confirm_title("".into());
            g.set_alert_title("".into());
            g.set_delete_open(false);
            g.set_invite_open(false);
            g.set_friend_open(false);
            g.set_custom_open(false);
            g.set_rules_open(false);
            g.set_rules_confirm(false);
            g.set_world_open(false);
            g.set_info(RoomInfo::default());
            g.set_error("".into());
            g.set_loading(false);
        }
    }

    /// True once after a room was created, deleted or left, so the home tiles reload.
    pub fn take_hosts_changed(&mut self) -> bool {
        std::mem::take(&mut self.hosts_changed)
    }

    fn navigate(&mut self, action: &'static str) {
        self.pending_nav.push(action);
    }

    fn me(&self) -> Option<(String, bool)> {
        let api = self.api.as_ref()?;
        Some((api.uid().ok()?, api.is_dav))
    }

    fn alert(&mut self, ui: &AppWindow, title: &str, text: &str, after: AfterAlert) {
        self.after_alert = after;
        let g = rooms(ui);
        g.set_alert_message(text.into());
        g.set_alert_title(title.into());
    }

    fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = rooms(&ui);
        match action {
            "guide" => g.set_guide(true),
            "guide-go" => {
                g.set_guide(false);
                self.navigate("plaza");
            }
            "open" if hex_id(value) && self.api.is_some() => {
                self.host = value.into();
                self.detail = None;
                self.members.clear();
                self.member_rows.clear();
                g.set_info(RoomInfo::default());
                self.just_opened = true;
                self.load_detail(&ui);
                self.navigate("room-detail");
            }
            "retry" => self.load_detail(&ui),
            "pack" => {
                if let Some(detail) = &self.detail {
                    ui.invoke_plaza_action("pack".into(), detail.modpack.id.as_str().into());
                }
            }
            "settings" => self.open_edit(&ui),
            "delete" if self.detail.is_some() => {
                g.set_delete_name("".into());
                g.set_delete_world(false);
                g.set_delete_open(true);
            }
            "delete-cancel" => g.set_delete_open(false),
            "delete-confirm" => self.delete(&ui),
            "menu" => {
                let Some(((me, dav), detail)) = self.me().zip(self.detail.as_ref()) else { return };
                let Some(member) = self.members.iter().find(|m| m.id == value) else { return };
                let entries = Pane::new(detail, &self.members, Me { id: &me, is_dav: dav }).menu(member);
                if entries.is_empty() {
                    return;
                }
                let rows: Vec<RoomMenuEntry> = entries
                    .iter()
                    .map(|e| RoomMenuEntry {
                        key: e.key().into(),
                        label: e.label().into(),
                        glyph: e.glyph().into(),
                        danger: e.danger(),
                    })
                    .collect();
                g.set_menu_entries(VecModel::from_slice(&rows));
                self.menu_member = Some(member.id.clone());
                g.set_menu_open(true);
            }
            "menu-close" => {
                g.set_menu_open(false);
                self.menu_member = None;
            }
            "menu-pick" => {
                g.set_menu_open(false);
                let (Some(entry), Some(uid)) = (MenuEntry::from_key(value), self.menu_member.take()) else { return };
                let (title, text) = entry.confirm();
                self.confirm = Some(Confirm::Member(entry, uid));
                g.set_confirm_message(text.into());
                g.set_confirm_title(title.into());
            }
            "confirm" => {
                g.set_confirm_title("".into());
                match self.confirm.take() {
                    Some(Confirm::Member(entry, uid)) => self.member_op(entry, uid),
                    Some(Confirm::PackUpdate) => self.update_pack(&ui),
                    None => {}
                }
            }
            "confirm-cancel" => {
                g.set_confirm_title("".into());
                self.confirm = None;
            }
            "invite" => {
                g.set_invite_qq("".into());
                g.set_invite_open(true);
            }
            "invite-cancel" => g.set_invite_open(false),
            "invite-ok" => {
                let qq = g.get_invite_qq().trim().to_owned();
                if qq.is_empty() {
                    self.alert(&ui, "错误", "QQ不能为空", AfterAlert::Nothing);
                    return;
                }
                g.set_invite_open(false);
                let host = self.host.clone();
                self.run_op(Op::Invite, move |api| async move { requests::invite(api.server(), &host, &qq).await });
            }
            "alert-ok" => {
                g.set_alert_title("".into());
                match std::mem::replace(&mut self.after_alert, AfterAlert::Nothing) {
                    AfterAlert::Back => self.navigate("back"),
                    AfterAlert::ClearFormError => {
                        if let Some(form) = &mut self.form {
                            form.error = None;
                        }
                        self.render_form(&ui);
                    }
                    AfterAlert::ClearFormStatus => {
                        if let Some(form) = &mut self.form {
                            form.status = None;
                        }
                        self.render_form(&ui);
                    }
                    AfterAlert::Nothing => {}
                }
            }
            "dismiss" => self.dismiss(&ui),
            "back-nav" => self.navigate("back"),
            "create" => self.open_create(&ui, value),
            _ => self.form_action(&ui, action, value),
        }
    }

    /// Escape or Alt+Left while a room dialog is open closes the topmost one.
    fn dismiss(&mut self, ui: &AppWindow) {
        let g = rooms(ui);
        if !g.get_alert_title().is_empty() {
            self.action("alert-ok", "");
        } else if !g.get_confirm_title().is_empty() {
            self.action("confirm-cancel", "");
        } else if g.get_rules_confirm() {
            g.set_rules_confirm(false);
        } else if g.get_menu_open() {
            self.action("menu-close", "");
        } else if g.get_custom_open() {
            self.form_action(ui, "custom-cancel", "");
        } else if g.get_friend_open() {
            self.form_action(ui, "friend-cancel", "");
        } else if g.get_world_open() {
            self.form_action(ui, "world-close", "");
        } else if g.get_rules_open() {
            self.form_action(ui, "rules-close", "");
        } else if g.get_invite_open() {
            g.set_invite_open(false);
        } else if g.get_delete_open() {
            g.set_delete_open(false);
        } else if g.get_guide() {
            g.set_guide(false);
        }
    }

    fn load_detail(&mut self, ui: &AppWindow) {
        self.detail_epoch += 1;
        self.members_epoch += 1;
        let g = rooms(ui);
        let Some(api) = self.api.clone() else {
            g.set_error("请先登录".into());
            return;
        };
        g.set_loading(true);
        g.set_error("".into());
        let (epoch, host, tx) = (self.detail_epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Detail(epoch, requests::detail(api.server(), &host).await));
        });
    }

    fn load_members(&mut self, ui: &AppWindow) {
        self.members_epoch += 1;
        let Some(api) = self.api.clone() else { return };
        rooms(ui).set_members_loading(true);
        let (epoch, host, tx) = (self.members_epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Members(epoch, requests::members(api.server(), &host).await));
        });
    }

    fn request_player(&mut self, uid: &str) {
        if self.players.contains_key(uid) || !self.requested.insert(uid.into()) {
            return;
        }
        let Some(api) = self.api.clone() else { return };
        let (uid, tx) = (uid.to_owned(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = player(&api, &uid).await;
            let _ = tx.send(Reply::Player(uid, result));
        });
    }

    fn show_detail(&mut self, ui: &AppWindow, detail: HostDetail) {
        let Some((me, dav)) = self.me() else { return };
        let me = Me { id: &me, is_dav: dav };
        let pack = &detail.modpack;
        let minecraft = crate::local_packs::mc_version_name(&pack.mc_ver).unwrap_or(&pack.mc_ver);
        let loader = match pack.modloader.as_str() {
            "forge" => "Forge",
            "neoforge" => "NeoForge",
            "fabric" | "Fabric" => "Fabric",
            other => other,
        };
        let previous = rooms(ui).get_info();
        let same_pack = previous.mid == pack.id.as_str() && previous.has_pack_icon;
        rooms(ui).set_info(RoomInfo {
            name: detail.name.as_str().into(),
            pack_name: pack.name.as_str().into(),
            pack_meta: format!("MC{minecraft} · {loader} · {}", detail.pack_ver).into(),
            pack_initial: name_placeholder(&pack.name),
            pack_icon: if same_pack { previous.pack_icon } else { Default::default() },
            has_pack_icon: same_pack,
            hid: detail.id.as_str().into(),
            mid: pack.id.as_str().into(),
            wid: detail.world_id.clone().unwrap_or_default().into(),
            can_manage: permissions::can_manage(&detail, me),
            is_owner: permissions::is_owner(&detail, me),
            can_view_members: permissions::can_view_members(&detail, me),
            needs_name: detail.version >= 2,
        });
        if !same_pack
            && let Some((api, url)) = self.api.clone().zip(pack.icon.clone().filter(|u| !u.trim().is_empty()))
        {
            let (epoch, tx) = (self.detail_epoch, self.tx.clone());
            let size = (36.0 * ui.window().scale_factor()).ceil().max(1.0) as u32;
            self.runtime.spawn(async move {
                match images::icon(&api.http, url.trim(), size).await {
                    Ok(icon) => {
                        let _ = tx.send(Reply::PackIcon(epoch, icon));
                    }
                    Err(error) => tracing::warn!(%error, "房间整合包图标加载失败"),
                }
            });
        }
        let view = permissions::can_view_members(&detail, me);
        self.detail = Some(detail);
        if view {
            self.load_members(ui);
        } else {
            self.members.clear();
            self.render_members(ui);
        }
    }

    fn render_members(&mut self, ui: &AppWindow) {
        let g = rooms(ui);
        let Some(((me, dav), detail)) = self.me().zip(self.detail.as_ref()) else { return };
        let pane = Pane::new(detail, &self.members, Me { id: &me, is_dav: dav });
        g.set_can_invite(pane.can_view && pane.me_admin);
        g.set_members_note(
            if permissions::can_view_members(detail, Me { id: &me, is_dav: dav }) && !pane.can_view {
                "仅房间成员可查看成员名单"
            } else {
                ""
            }
            .into(),
        );
        let fallback = self.fallback.clone();
        let rows: Vec<RoomMember> = if pane.can_view {
            self.members
                .iter()
                .map(|m| {
                    let (name, head) = cached_player(&self.players, &m.id, &fallback);
                    RoomMember {
                        id: m.id.as_str().into(),
                        name: name.into(),
                        head,
                        role: m.role.level(),
                        label: m.role.label().into(),
                        menu: pane.can_open_menu(m),
                    }
                })
                .collect()
        } else {
            Vec::new()
        };
        let ids: Vec<String> = self.members.iter().map(|m| m.id.clone()).collect();
        sync_rows(&self.member_rows, rows);
        for id in ids {
            self.request_player(&id);
        }
    }

    fn member_op(&mut self, entry: MenuEntry, uid: String) {
        let host = self.host.clone();
        match entry {
            MenuEntry::Transfer => self.run_op(Op::Transfer, move |api| async move {
                requests::transfer(api.server(), &host, &uid).await
            }),
            MenuEntry::MakeAdmin | MenuEntry::RemoveAdmin => {
                let role = if entry == MenuEntry::MakeAdmin { Role::Admin } else { Role::Member };
                self.run_op(Op::Role, move |api| async move {
                    requests::set_role(api.server(), &host, &uid, role).await
                })
            }
            MenuEntry::Kick => self.run_op(Op::Kick, move |api| async move {
                requests::kick(api.server(), &host, &uid).await
            }),
            MenuEntry::Quit => self.run_op(Op::Quit, move |api| async move { requests::quit(api.server(), &host).await }),
        }
    }

    fn run_op<F, Fut>(&mut self, op: Op, call: F)
    where
        F: FnOnce(Client) -> Fut + Send + 'static,
        Fut: std::future::Future<Output = Result<()>> + Send,
    {
        let Some(api) = self.api.clone() else { return };
        let (epoch, tx) = (self.op_epoch, self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Op(epoch, op, call(api).await));
        });
    }

    fn delete(&mut self, ui: &AppWindow) {
        let g = rooms(ui);
        let Some(detail) = &self.detail else { return };
        if g.get_deleting() || (detail.version >= 2 && g.get_delete_name() != detail.name.as_str()) {
            return;
        }
        let delete_world = g.get_delete_world();
        g.set_delete_open(false);
        g.set_deleting(true);
        let host = self.host.clone();
        self.run_op(Op::Delete, move |api| async move {
            requests::delete(api.server(), &host, delete_world).await
        });
    }

    fn op_done(&mut self, ui: &AppWindow, op: Op, result: Result<()>) {
        if let Op::Delete = op {
            rooms(ui).set_deleting(false);
        }
        match result {
            Ok(()) => match op {
                Op::Invite | Op::Role | Op::Kick => {
                    ui.invoke_notify(
                        match op {
                            Op::Invite => "已发送邀请",
                            Op::Role => "已更新",
                            _ => "已踢出",
                        }
                        .into(),
                    );
                    self.load_members(ui);
                }
                Op::Transfer => {
                    ui.invoke_notify("已转让".into());
                    self.load_detail(ui);
                }
                Op::Quit | Op::Delete => {
                    self.hosts_changed = true;
                    if rooms(ui).get_screen() == 1 {
                        self.navigate("back");
                    }
                }
            },
            Err(error) => {
                tracing::warn!(?op, error = %format!("{error:#}"), "房间操作失败");
                let text = message(&error);
                let text = if text.is_empty() { op.fallback().into() } else { text };
                if let Op::Delete = op {
                    ui.invoke_notify(text.into());
                } else {
                    self.alert(ui, "错误", &text, AfterAlert::Nothing);
                }
            }
        }
    }

    fn ensure_icons(&mut self, ui: &AppWindow) {
        if self.icons_loaded {
            return;
        }
        self.icons_loaded = true;
        let icons: Vec<slint::Image> = ICONS
            .iter()
            .map(|bytes| {
                crate::avif::decode(bytes, 1024)
                    .and_then(|rgba| {
                        image::RgbaImage::from_raw(rgba.width, rgba.height, rgba.pixels)
                            .ok_or_else(|| anyhow::anyhow!("AVIF像素数量不符"))
                    })
                    .map(|image| images::to_slint(&image))
                    .unwrap_or_else(|error| {
                        tracing::warn!(error = %format!("{error:#}"), "房间选项图标解码失败");
                        slint::Image::default()
                    })
            })
            .collect();
        rooms(ui).set_icons(VecModel::from_slice(&icons));
    }

    fn open_create(&mut self, ui: &AppWindow, value: &str) {
        let Some(api) = self.api.clone() else { return };
        let Ok(me) = api.uid() else { return };
        let Ok(args) = serde_json::from_str::<serde_json::Value>(value) else { return };
        let text = |key: &str| args[key].as_str().unwrap_or_default().to_owned();
        let form = FormState::create(&me, &api.account_name, &text("modpackId"), &text("name"), &text("version"), &text("mcVer"));
        self.start_form(ui, form);
        let (epoch, tx) = (self.form_epoch, self.tx.clone());
        let (pack, version) = (text("modpackId"), text("version"));
        self.runtime.spawn(async move {
            let result = requests::version_binding(api.server(), &pack, &version).await;
            let _ = tx.send(Reply::Binding(epoch, result));
        });
    }

    fn open_edit(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        let Ok(me) = api.uid() else { return };
        if self.detail.is_none() {
            return;
        }
        let form = FormState::edit(&me, &api.account_name, &self.host);
        self.start_form(ui, form);
        let (epoch, tx, host) = (self.form_epoch, self.tx.clone(), self.host.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::FormHost(epoch, requests::detail(api.server(), &host).await));
        });
    }

    fn start_form(&mut self, ui: &AppWindow, form: FormState) {
        self.ensure_icons(ui);
        self.form_epoch += 1;
        rooms(ui).set_form_name(form.host_name.as_str().into());
        self.form = Some(form);
        self.draft = None;
        self.render_form(ui);
        self.navigate("room-form");
    }

    fn render_form(&mut self, ui: &AppWindow) {
        let g = rooms(ui);
        let Some(form) = &self.form else { return };
        g.set_form(RoomFormView {
            title: form.title.as_str().into(),
            pack_line: format!("{} v{}", form.pack_title(), form.version).into(),
            edit: form.edit_host.is_some(),
            create: form.is_create(),
            loading: form.loading,
            submitting: form.submitting,
            updating: form.updating_pack,
            can_submit: form.can_submit(),
            difficulty: form.difficulty,
            mode: form.game_mode,
            level: if form.world_source == WorldSource::Generate { form.level_choice } else { -1 },
            template_title: form.template_title().into(),
            required: form.required(),
            binding_error: form.binding_error.clone().unwrap_or_default().into(),
            whitelist: form.whitelist,
            cheats: form.allow_cheats,
            rule_count: form.game_rules.len() as i32,
        });
        let fallback = self.fallback.clone();
        let friends: Vec<RoomFriend> = form
            .friends
            .iter()
            .map(|f| RoomFriend {
                id: f.id.as_str().into(),
                name: f.name.as_str().into(),
                head: self.players.get(&f.id).map(|p| p.1.clone()).unwrap_or_else(|| fallback.clone()),
            })
            .collect();
        let ids: Vec<String> = form.friends.iter().map(|f| f.id.clone()).collect();
        g.set_friend_open(form.friend_dialog);
        g.set_friend_loading(form.friend_loading);
        g.set_friend_error(form.friend_error.clone().unwrap_or_default().into());
        g.set_friend_can_add(!form.friend_loading && !form.friend_qq.trim().is_empty());
        if g.get_friend_qq() != form.friend_qq.as_str() {
            g.set_friend_qq(form.friend_qq.as_str().into());
        }
        g.set_custom_error(form.custom_error.clone().unwrap_or_default().into());
        g.set_world_open(form.world_dialog);
        g.set_world_loading(form.worlds_loading);
        g.set_world_error(form.worlds_error.clone().unwrap_or_default().into());
        let worlds: Vec<RoomWorld> = form.worlds.iter().map(|w| world_row(w, &self.players, &fallback)).collect();
        let owners: Vec<String> = form
            .worlds
            .iter()
            .filter_map(|w| uuid_object_id(&w.owner_id))
            .collect();
        let draft = g.get_world_draft();
        g.set_world_can_confirm(!form.submitting && form.worlds.iter().any(|w| w.id == draft.as_str()));
        let alert = if g.get_alert_title().is_empty() {
            form.error
                .clone()
                .map(|e| (e, AfterAlert::ClearFormError))
                .or_else(|| form.status.clone().map(|s| (s, AfterAlert::ClearFormStatus)))
        } else {
            None
        };
        sync_rows(&self.friend_rows, friends);
        sync_rows(&self.world_rows, worlds);
        for id in ids.into_iter().chain(owners) {
            self.request_player(&id);
        }
        if let Some((text, after)) = alert {
            self.alert(ui, "错误", &text, after);
        }
    }

    fn form_action(&mut self, ui: &AppWindow, action: &str, value: &str) {
        let g = rooms(ui);
        if action == "rule-toggle" {
            self.rule_toggled(ui, value);
            return;
        }
        let Some(form) = &mut self.form else { return };
        let epoch = self.form_epoch;
        match action {
            "difficulty" => form.difficulty = value.parse().unwrap_or(form.difficulty),
            "mode" => form.game_mode = value.parse().unwrap_or(form.game_mode),
            "whitelist" => form.whitelist = !form.whitelist,
            "cheats" => form.allow_cheats = !form.allow_cheats,
            "level" => {
                let Ok(choice) = value.parse::<i32>() else { return };
                form.select_level_choice(choice);
                if choice == 3 && !form.required() {
                    form.begin_custom();
                    g.set_custom_text(form.custom_text.as_str().into());
                    g.set_custom_open(true);
                }
            }
            "custom-text" => form.update_custom_text(value),
            "custom-ok" => {
                form.update_custom_text(g.get_custom_text().as_str());
                if form.apply_custom() {
                    g.set_custom_open(false);
                }
            }
            "custom-cancel" => {
                form.cancel_custom();
                g.set_custom_open(false);
            }
            "template" => {
                if form.submitting || form.required() {
                    return;
                }
                g.set_world_draft(form.selected_world.clone().unwrap_or_default().into());
                if let Some(generation) = form.open_world_dialog() {
                    self.load_worlds(generation);
                }
            }
            "world-retry" => {
                if let Some(generation) = form.load_worlds() {
                    self.load_worlds(generation);
                }
            }
            "world-pick" => g.set_world_draft(value.into()),
            "world-ok" => {
                let draft = g.get_world_draft().to_string();
                form.commit_world(&draft);
            }
            "world-close" => form.close_world_dialog(),
            "friend" => {
                form.open_friend_dialog();
                g.set_friend_qq("".into());
            }
            "friend-qq" => form.update_friend_qq(value),
            "friend-add" => {
                form.update_friend_qq(g.get_friend_qq().as_str());
                if let Some((generation, qq)) = form.add_friend()
                    && let Some(api) = self.api.clone()
                {
                    let tx = self.tx.clone();
                    self.runtime.spawn(async move {
                        let result = requests::friend_by_qq(api.server(), &qq).await;
                        let _ = tx.send(Reply::Friend(epoch, generation, result));
                    });
                }
            }
            "friend-cancel" => form.dismiss_friend_dialog(),
            "friend-remove" => form.remove_friend(value),
            "rules" if !form.loading => {
                self.draft = Some(Draft::open(&form.game_rules));
                self.build_rules(ui, true);
                g.set_rules_confirm(false);
                g.set_rules_open(true);
                return;
            }
            "rules-reset" => {
                if let Some(draft) = &mut self.draft {
                    draft.reset();
                }
                self.build_rules(ui, true);
                return;
            }
            "rules-save" => {
                if let Some(draft) = self.draft.as_ref().filter(|d| d.can_save()) {
                    let lines: Vec<SharedString> = draft.change_lines().into_iter().map(Into::into).collect();
                    g.set_rules_changes(VecModel::from_slice(&lines));
                    g.set_rules_confirm(true);
                }
                return;
            }
            "rules-confirm" => {
                if let Some(draft) = self.draft.take() {
                    form.game_rules = draft.rules;
                }
                g.set_rules_confirm(false);
                g.set_rules_open(false);
            }
            "rules-close" => {
                self.draft = None;
                g.set_rules_confirm(false);
                g.set_rules_open(false);
                return;
            }
            "submit" => {
                form.host_name = g.get_form_name().to_string();
                if let Some(submission) = form.submit()
                    && let Some(api) = self.api.clone()
                {
                    let tx = self.tx.clone();
                    self.runtime.spawn(async move {
                        let result = match submission {
                            Submission::Create(request) => requests::create(api.server(), &request).await,
                            Submission::Edit { host, options } => {
                                requests::save_options(api.server(), &host, &options).await
                            }
                        };
                        let _ = tx.send(Reply::Submitted(epoch, result));
                    });
                }
            }
            "update-pack" if form.edit_host.is_some() && !form.updating_pack => {
                self.confirm = Some(Confirm::PackUpdate);
                g.set_confirm_message(form.pack_update_message().into());
                g.set_confirm_title("确认更新".into());
                return;
            }
            _ => return,
        }
        self.render_form(ui);
    }

    fn load_worlds(&mut self, generation: u64) {
        let Some(api) = self.api.clone() else { return };
        let (epoch, tx) = (self.form_epoch, self.tx.clone());
        self.runtime.spawn(async move {
            let result = requests::ready_base_worlds(api.server()).await;
            let _ = tx.send(Reply::Worlds(epoch, generation, result));
        });
    }

    fn update_pack(&mut self, ui: &AppWindow) {
        let Some(form) = &mut self.form else { return };
        let Some(host) = form.start_pack_update() else { return };
        let Some(api) = self.api.clone() else { return };
        let (epoch, tx) = (self.form_epoch, self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::PackUpdated(epoch, requests::update_pack(api.server(), &host).await));
        });
        self.render_form(ui);
    }

    /// Rebuilds the rule rows; a new model resets the number fields after 重置.
    fn build_rules(&mut self, ui: &AppWindow, replace: bool) {
        let Some(draft) = &self.draft else { return };
        let mut rows = Vec::new();
        for category in game_rules::categories() {
            rows.push(RoomRule { header: true, name: category.name.as_str().into(), ..Default::default() });
            for rule in &category.rules {
                rows.push(self.rule_row(draft, rule));
            }
        }
        if replace {
            self.rule_rows = Rc::new(VecModel::from(rows));
            rooms(ui).set_rules(self.rule_rows.clone().into());
        } else {
            sync_rows(&self.rule_rows, rows);
        }
        self.render_rule_header(ui);
    }

    fn rule_row(&self, draft: &Draft, rule: &game_rules::Rule) -> RoomRule {
        let value = draft.value(rule);
        RoomRule {
            header: false,
            id: rule.id.as_str().into(),
            name: rule.name.as_str().into(),
            description: rule.description.as_str().into(),
            boolean: rule.value_type == ValueType::Boolean,
            checked: value.eq_ignore_ascii_case("true"),
            text: draft.text(rule).into(),
            invalid: draft.invalid.contains(&rule.id),
            changed: draft.rules.contains_key(&rule.id),
        }
    }

    fn render_rule_header(&self, ui: &AppWindow) {
        let Some(draft) = &self.draft else { return };
        let g = rooms(ui);
        g.set_rules_title(draft.title().into());
        g.set_rules_valid(draft.can_save());
    }

    fn update_rule_row(&self, ui: &AppWindow, id: &str) {
        let (Some(draft), Some(rule)) = (&self.draft, game_rules::rule(id)) else { return };
        if let Some(index) = (0..self.rule_rows.row_count())
            .find(|&i| self.rule_rows.row_data(i).is_some_and(|r| r.id == id))
        {
            let row = self.rule_row(draft, rule);
            if self.rule_rows.row_data(index).as_ref() != Some(&row) {
                self.rule_rows.set_row_data(index, row);
            }
        }
        self.render_rule_header(ui);
    }

    fn rule_toggled(&mut self, ui: &AppWindow, id: &str) {
        let Some(draft) = &mut self.draft else { return };
        let Some(rule) = game_rules::rule(id) else { return };
        let checked = draft.value(rule).eq_ignore_ascii_case("true");
        draft.toggle(id, !checked);
        self.update_rule_row(ui, id);
    }

    fn rule_edited(&mut self, id: &str, text: &str) -> String {
        let Some(draft) = &mut self.draft else { return text.into() };
        let shown = draft.edit_integer(id, text);
        if let Some(ui) = self.ui.upgrade() {
            self.update_rule_row(&ui, id);
        }
        shown
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            // A dialog would make the shell ignore the request, so it waits until they close.
            if rooms(&ui).get_modal() {
                self.pending_nav.push(action);
                continue;
            }
            ui.invoke_navigate(action.into());
        }
        let screen = rooms(&ui).get_screen();
        if screen != self.last_screen {
            // Coming back to the details page may follow a change to the room, so reload it (D7).
            if screen == 1 && !self.just_opened && self.detail.is_some() {
                self.load_detail(&ui);
            }
            if self.last_screen == 2 && screen != 2 {
                self.form_epoch += 1;
                self.form = None;
                self.draft = None;
            }
            self.just_opened = false;
            self.last_screen = screen;
        }
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Detail(epoch, result) if epoch == self.detail_epoch => {
                    let g = rooms(&ui);
                    g.set_loading(false);
                    match result {
                        Ok(detail) => self.show_detail(&ui, detail),
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "房间详情加载失败");
                            let text = message(&error);
                            g.set_error(if text.is_empty() { "无法加载房间信息".into() } else { text.into() });
                        }
                    }
                }
                Reply::Members(epoch, result) if epoch == self.members_epoch => {
                    rooms(&ui).set_members_loading(false);
                    match result {
                        Ok(list) => {
                            self.members = permissions::sorted(list);
                            self.render_members(&ui);
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "房间成员加载失败");
                            let text = message(&error);
                            let text = if text.is_empty() { "无法加载成员名单".into() } else { text };
                            self.alert(&ui, "错误", &text, AfterAlert::Nothing);
                        }
                    }
                }
                Reply::Player(uid, result) => {
                    let entry = match result {
                        Ok((name, head)) => (name, images::to_slint(&head)),
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "玩家信息加载失败");
                            let fallback = self.fallback.clone();
                            ("未知玩家".into(), fallback)
                        }
                    };
                    self.players.insert(uid, entry);
                    self.render_members(&ui);
                    if self.form.is_some() {
                        self.render_form(&ui);
                    }
                }
                Reply::PackIcon(epoch, icon) if epoch == self.detail_epoch => {
                    let g = rooms(&ui);
                    let mut info = g.get_info();
                    info.pack_icon = images::to_slint(&icon);
                    info.has_pack_icon = true;
                    g.set_info(info);
                }
                Reply::Op(epoch, op, result) if epoch == self.op_epoch => self.op_done(&ui, op, result),
                Reply::FormHost(epoch, result) if epoch == self.form_epoch => {
                    if let Some(form) = &mut self.form {
                        form.host_loaded(result.map_err(|e| message(&e)));
                        rooms(&ui).set_form_name(form.host_name.as_str().into());
                    }
                    self.render_form(&ui);
                }
                Reply::Binding(epoch, result) if epoch == self.form_epoch => {
                    if let Some(form) = &mut self.form
                        && form.binding_loaded(result.map_err(|e| message(&e)))
                        && let Some(generation) = form.load_worlds()
                    {
                        self.load_worlds(generation);
                    }
                    self.render_form(&ui);
                }
                Reply::Worlds(epoch, generation, result) if epoch == self.form_epoch => {
                    if let Err(error) = &result {
                        tracing::warn!(error = %format!("{error:#}"), "地图模板加载失败");
                    }
                    if let Some(form) = &mut self.form {
                        form.worlds_loaded(generation, result.map_err(|e| message(&e)));
                    }
                    self.render_form(&ui);
                }
                Reply::Friend(epoch, generation, result) if epoch == self.form_epoch => {
                    if let Err(error) = &result {
                        tracing::warn!(error = %format!("{error:#}"), "添加好友失败");
                    }
                    if let Some(form) = &mut self.form {
                        form.friend_found(generation, result.map_err(|e| message(&e)));
                    }
                    self.render_form(&ui);
                }
                Reply::Submitted(epoch, result) if epoch == self.form_epoch => {
                    if let Err(error) = &result {
                        tracing::warn!(error = %format!("{error:#}"), "房间提交失败");
                    }
                    let Some(form) = &mut self.form else { continue };
                    let created = form.is_create();
                    if let Some(text) = form.submitted(result.map_err(|e| message(&e))) {
                        if created {
                            self.hosts_changed = true;
                        }
                        self.alert(&ui, "成功", &text, AfterAlert::Back);
                    }
                    self.render_form(&ui);
                }
                Reply::PackUpdated(epoch, result) if epoch == self.form_epoch => {
                    if let Err(error) = &result {
                        tracing::warn!(error = %format!("{error:#}"), "更新房间整合包失败");
                    }
                    let Some(form) = &mut self.form else { continue };
                    if let Some(text) = form.pack_updated(result.map_err(|e| message(&e))) {
                        self.alert(&ui, "成功", &text, AfterAlert::Nothing);
                    }
                    self.render_form(&ui);
                }
                _ => {}
            }
        }
    }
}

/// Updates `model` to `rows`, touching only rows that changed.
fn sync_rows<T: Clone + PartialEq + 'static>(model: &VecModel<T>, rows: Vec<T>) {
    let len = rows.len();
    for (i, row) in rows.into_iter().enumerate() {
        if i >= model.row_count() {
            model.push(row);
        } else if model.row_data(i).as_ref() != Some(&row) {
            model.set_row_data(i, row);
        }
    }
    while model.row_count() > len {
        model.remove(model.row_count() - 1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn world(owner: &str) -> BaseWorld {
        BaseWorld { id: "w1".into(), owner_id: owner.into(), name: "空岛模板".into(), level_type: "minecraft:flat".into(), size: 0 }
    }

    #[test]
    fn template_rows_show_the_author() {
        let fallback = slint::Image::default();
        let mut players = HashMap::new();
        players.insert("u1".to_string(), ("作者甲".to_string(), fallback.clone()));
        players.insert("66a000000000000000000001".to_string(), ("作者甲".to_string(), fallback.clone()));
        let row = world_row(&world("66a00000-0000-0000-0000-000100000000"), &players, &fallback);
        assert_eq!((row.id.as_str(), row.name.as_str(), row.owner_name.as_str()), ("w1", "空岛模板", "作者甲"));
        let pending = world("66a00000-0000-0000-0000-000200000000");
        assert_eq!(world_row(&pending, &players, &fallback).owner_name.as_str(), "…", "request still running");
        assert_eq!(world_row(&world(" "), &players, &fallback).owner_name.as_str(), "未知玩家", "no owner id");
        let random = world("0192f3a4-5b6c-7d8e-9f00-112233445566");
        assert_eq!(world_row(&random, &players, &fallback).owner_name.as_str(), "未知玩家", "not an ObjectId UUID");
    }

    #[test]
    fn object_ids_come_from_the_first_twelve_uuid_bytes() {
        assert_eq!(uuid_object_id("66A00000-0000-0000-0000-000100000000").as_deref(), Some("66a000000000000000000001"));
        assert_eq!(uuid_object_id("66a00000-0000-0000-0000-000100000001"), None, "trailing bytes must be zero");
        assert_eq!(uuid_object_id("66a0"), None);
    }
}
