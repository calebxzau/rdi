//! Who may do what on the room details page (`HostInfoScreen.kt`, `HostMembersScreen.kt`).
use super::model::{HostDetail, Member, Role};

/// The signed-in player as the checks see them.
#[derive(Clone, Copy)]
pub struct Me<'a> {
    pub id: &'a str,
    pub is_dav: bool,
}

/// `HostInfoScreen.isOwner`: the room's owner, or the developer account.
pub fn is_owner(host: &HostDetail, me: Me) -> bool {
    host.owner_id == me.id || me.is_dav
}

/// `Host.DetailVo.isAdmin`.
fn detail_admin(host: &HostDetail, me: Me) -> bool {
    host.owner_id == me.id
        || host
            .members
            .iter()
            .any(|m| m.id == me.id && matches!(m.role, Role::Owner | Role::Admin))
}

/// 设置 is shown to the owner and to admins.
pub fn can_manage(host: &HostDetail, me: Me) -> bool {
    is_owner(host, me) || detail_admin(host, me)
}

/// Only members (and the developer account) see the member section.
pub fn can_view_members(host: &HostDetail, me: Me) -> bool {
    host.owner_id == me.id || host.members.iter().any(|m| m.id == me.id) || me.is_dav
}

/// One entry of a member's menu, in Compose's order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MenuEntry {
    Transfer,
    MakeAdmin,
    RemoveAdmin,
    Kick,
    Quit,
}
impl MenuEntry {
    pub fn label(self) -> &'static str {
        match self {
            MenuEntry::Transfer => "转让房间",
            MenuEntry::MakeAdmin => "设为管理员",
            MenuEntry::RemoveAdmin => "取消管理员",
            MenuEntry::Kick => "踢出成员",
            MenuEntry::Quit => "退出房间",
        }
    }
    pub fn glyph(self) -> &'static str {
        match self {
            MenuEntry::Transfer => "\u{f416}",
            MenuEntry::MakeAdmin | MenuEntry::RemoveAdmin => "\u{efa6}",
            MenuEntry::Kick => "\u{ee8b}",
            MenuEntry::Quit => "\u{ef69}",
        }
    }
    pub fn danger(self) -> bool {
        matches!(self, MenuEntry::Kick | MenuEntry::Quit)
    }
    pub fn key(self) -> &'static str {
        match self {
            MenuEntry::Transfer => "transfer",
            MenuEntry::MakeAdmin => "make-admin",
            MenuEntry::RemoveAdmin => "remove-admin",
            MenuEntry::Kick => "kick",
            MenuEntry::Quit => "quit",
        }
    }
    pub fn from_key(key: &str) -> Option<Self> {
        [Self::Transfer, Self::MakeAdmin, Self::RemoveAdmin, Self::Kick, Self::Quit]
            .into_iter()
            .find(|e| e.key() == key)
    }
    /// The confirmation title and message, worded as Compose.
    pub fn confirm(self) -> (&'static str, &'static str) {
        match self {
            MenuEntry::Transfer => ("确认转让", "确定将房间所有权转让给该成员吗？"),
            MenuEntry::MakeAdmin => ("确认操作", "确定设置该成员为管理员？"),
            MenuEntry::RemoveAdmin => ("确认操作", "确定取消管理员身份？"),
            MenuEntry::Kick => ("确认踢出", "要踢出该成员吗？"),
            MenuEntry::Quit => ("退出房间", "确定退出该房间吗？"),
        }
    }
}

/// The member pane's own view, which reads roles from `GET /host/{id}/members`.
pub struct Pane<'a> {
    pub me: Me<'a>,
    pub me_owner: bool,
    pub me_admin: bool,
    pub can_view: bool,
}
impl<'a> Pane<'a> {
    pub fn new(host: &HostDetail, members: &[Member], me: Me<'a>) -> Self {
        let my_role = members.iter().find(|m| m.id == me.id).map(|m| m.role);
        let me_owner = host.owner_id == me.id || me.is_dav;
        Self {
            me,
            me_owner,
            me_admin: me_owner || matches!(my_role, Some(Role::Owner | Role::Admin)),
            can_view: my_role.is_some() || me_owner,
        }
    }
    /// `canKick`: never the owner; others only below admin unless I am the owner. Owner
    /// decision 2026-11-22: unlike Compose, only the owner and admins see it.
    pub fn can_kick(&self, member: &Member) -> bool {
        self.me_admin
            && member.role != Role::Owner
            && (self.me_owner || member.role.level() > Role::Admin.level())
    }
    pub fn menu(&self, member: &Member) -> Vec<MenuEntry> {
        let mut entries = Vec::new();
        if self.me_owner && member.role != Role::Owner {
            entries.push(MenuEntry::Transfer);
            entries.push(if member.role == Role::Admin { MenuEntry::RemoveAdmin } else { MenuEntry::MakeAdmin });
        }
        if self.can_kick(member) {
            entries.push(MenuEntry::Kick);
        }
        if !self.me_owner && member.id == self.me.id {
            entries.push(MenuEntry::Quit);
        }
        entries
    }
    /// `canOpenMenu` is exactly "the menu has an entry".
    pub fn can_open_menu(&self, member: &Member) -> bool {
        !self.menu(member).is_empty()
    }
}

/// Members sorted by role level, as Compose sorts them.
pub fn sorted(mut members: Vec<Member>) -> Vec<Member> {
    members.sort_by_key(|m| m.role.level());
    members
}

#[cfg(test)]
mod tests {
    use super::*;
    use MenuEntry::*;

    fn host(owner: &str, members: &[(&str, Role)]) -> HostDetail {
        let mut detail: HostDetail = serde_json::from_value(serde_json::json!({
            "_id":"h","name":"房间","ownerId":owner,"packVer":"1","version":2,
            "difficulty":3,"gameMode":0,"levelType":"minecraft:normal"
        }))
        .unwrap();
        detail.members = members.iter().map(|(id, role)| Member { id: id.to_string(), role: *role }).collect();
        detail
    }
    fn me(id: &str) -> Me<'_> {
        Me { id, is_dav: false }
    }
    fn member(id: &str, role: Role) -> Member {
        Member { id: id.into(), role }
    }

    #[test]
    fn details_page_rights() {
        let room = host("o", &[("o", Role::Owner), ("a", Role::Admin), ("m", Role::Member)]);
        assert!(is_owner(&room, me("o")) && can_manage(&room, me("o")));
        assert!(!is_owner(&room, me("a")) && can_manage(&room, me("a")));
        assert!(!can_manage(&room, me("m")) && can_view_members(&room, me("m")));
        assert!(!can_view_members(&room, me("x")) && !can_manage(&room, me("x")));
        let dav = Me { id: "x", is_dav: true };
        assert!(is_owner(&room, dav) && can_manage(&room, dav) && can_view_members(&room, dav));
        let outsider_view = host("o", &[]);
        assert!(can_view_members(&outsider_view, me("o")));
    }

    #[test]
    fn member_menus_follow_compose_except_kick() {
        let list = [member("o", Role::Owner), member("a", Role::Admin), member("m", Role::Member)];
        let room = host("o", &[]);
        let owner = Pane::new(&room, &list, me("o"));
        assert_eq!(owner.menu(&list[0]), []);
        assert_eq!(owner.menu(&list[1]), [Transfer, RemoveAdmin, Kick]);
        assert_eq!(owner.menu(&list[2]), [Transfer, MakeAdmin, Kick]);
        assert!(owner.me_admin && owner.can_view);

        let admin = Pane::new(&room, &list, me("a"));
        assert_eq!(admin.menu(&list[0]), []);
        assert_eq!(admin.menu(&list[1]), [Quit]);
        assert_eq!(admin.menu(&list[2]), [Kick]);
        assert!(admin.me_admin);

        let plain = Pane::new(&room, &list, me("m"));
        assert_eq!(plain.menu(&list[1]), []);
        // Compose offers 踢出成员 to plain members too; the owner removed it (2026-11-22).
        assert_eq!(plain.menu(&list[2]), [Quit]);
        let other = [member("o", Role::Owner), member("m", Role::Member), member("m2", Role::Member)];
        let plain = Pane::new(&room, &other, me("m"));
        assert_eq!(plain.menu(&other[2]), []);
        assert!(!plain.can_open_menu(&other[2]));
        assert!(!plain.me_admin && plain.can_view);

        let outsider = Pane::new(&room, &list, me("x"));
        assert!(!outsider.can_view && !outsider.can_open_menu(&list[0]));
        let dav = Pane::new(&room, &list, Me { id: "x", is_dav: true });
        assert!(dav.can_view && dav.me_admin);
        assert_eq!(dav.menu(&list[1]), [Transfer, RemoveAdmin, Kick]);
    }

    #[test]
    fn members_sort_by_role_level_keeping_order() {
        let list = sorted(vec![
            member("m1", Role::Member),
            member("g", Role::Guest),
            member("a", Role::Admin),
            member("m2", Role::Member),
            member("o", Role::Owner),
        ]);
        let ids: Vec<_> = list.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["o", "a", "m1", "m2", "g"]);
        assert_eq!(MenuEntry::from_key("make-admin"), Some(MakeAdmin));
        assert_eq!(Role::Admin.label(), "管理员");
    }
}
