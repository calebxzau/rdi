//! Shell navigation. U4 destinations deliberately remain stubs until their screens are ported.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Screen {
    #[default]
    Menu,
    Gallery,
    HostList,
    Wardrobe,
    LocalModpackInstall,
    ModpackLocalAdvanceOption,
    ModpackPlaza,
    ModpackDetail,
    ModpackVersionDetail,
    /// U4c1: room details and members.
    RoomDetail,
    /// U4c1: the create or edit form of a room.
    RoomForm,
    /// U4c2a: a room's 后台, 存档 and 回档 pages.
    RoomBackend,
    RoomWorld,
    RoomRollback,
    /// U4c2b: a room's 模组 page.
    RoomMods,
    /// A4: 导入单人存档, opened from the room's 存档 page.
    RoomSaveImport,
    /// U4b1: 个人信息.
    PlayerInfo,
    /// U4b2: 信箱.
    Mail,
    /// U4b2: 设置.
    Settings,
    /// U4e1: 模组 (the mod catalog) and a mod's details.
    ContentMods,
    ContentModDetail,
    /// U4e1: 资源包/光影包 and one pack's details.
    ContentResources,
    ContentResourceDetail,
}
#[derive(Default, Debug)]
pub struct Navigation {
    stack: Vec<Screen>,
}
impl Navigation {
    pub fn current(&self) -> Screen {
        self.stack.last().copied().unwrap_or_default()
    }
    pub fn can_back(&self) -> bool {
        !self.stack.is_empty()
    }
    pub fn push(&mut self, screen: Screen) {
        if screen == Screen::Menu {
            self.home();
        } else if self.current() != screen {
            self.stack.push(screen);
        }
    }
    pub fn back(&mut self) {
        self.stack.pop();
    }
    pub fn home(&mut self) {
        self.stack.clear();
    }
    pub fn scene_frozen(&self, game_running: bool) -> bool {
        self.current() != Screen::Menu || game_running
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn root_back_single_top_and_home() {
        let mut n = Navigation::default();
        n.back();
        assert_eq!(n.current(), Screen::Menu);
        n.push(Screen::Gallery);
        n.push(Screen::Gallery);
        assert!(n.can_back());
        n.back();
        assert_eq!(n.current(), Screen::Menu);
        assert!(!n.can_back());
        n.push(Screen::Gallery);
        n.home();
        assert!(!n.can_back());
    }
    #[test]
    fn menu_and_game_control_scene_hold() {
        let mut n = Navigation::default();
        assert!(!n.scene_frozen(false));
        assert!(n.scene_frozen(true));
        n.push(Screen::Gallery);
        assert!(n.scene_frozen(false));
        n.back();
        assert!(!n.scene_frozen(false));
    }

    #[test]
    fn submenu_screens_hold_scene_and_return_to_menu() {
        for screen in [
            Screen::HostList,
            Screen::LocalModpackInstall,
            Screen::ModpackLocalAdvanceOption,
            Screen::RoomDetail,
            Screen::RoomForm,
            Screen::RoomBackend,
            Screen::RoomWorld,
            Screen::RoomRollback,
            Screen::RoomMods,
            Screen::RoomSaveImport,
            Screen::ContentMods,
            Screen::ContentModDetail,
            Screen::ContentResources,
            Screen::ContentResourceDetail,
        ] {
            let mut navigation = Navigation::default();
            navigation.push(screen);
            assert!(navigation.can_back());
            assert!(navigation.scene_frozen(false));
            navigation.back();
            assert_eq!(navigation.current(), Screen::Menu);
            assert!(!navigation.scene_frozen(false));
        }
    }
}
