//! U4e2: wiring of the skin preview and 导入正版皮肤/披风 to the `Closet` global. The rules are
//! in `closet/`. The turning player is drawn by the render loop in `main.rs` through `Avatar`.
use crate::{
    AppWindow, Closet,
    api::{Client, model::PlayerInfo},
    closet::{self, Clothes, Texture},
    images,
};
use anyhow::Result;
use rdi_renderer::scene::player::Appearance;
use slint::ComponentHandle;
use std::{cell::RefCell, rc::Rc, sync::mpsc};
use tokio::runtime::Handle;

/// What the render loop shares with the preview page.
#[derive(Default)]
pub struct AvatarSlot {
    /// The preview is shown and wants frames.
    pub active: bool,
    /// A new player to upload before the next frame.
    pub pending: Option<Appearance>,
    /// Drop the uploaded player (the page was left).
    pub clear: bool,
    /// The renderer failed; the page shows the still picture.
    pub failed: bool,
}
pub type Avatar = Rc<RefCell<AvatarSlot>>;

struct Preview {
    texture: Texture,
    current: Clothes,
    appearance: Appearance,
    still: Option<image::RgbaImage>,
}

enum Reply {
    Preview(u64, Result<Preview>),
    Applied(u64, Result<Clothes>),
    Imported(u64, Result<Clothes>),
}

pub struct ClosetUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    epoch: u64,
    /// The opened card: Blessing Skin texture id and its list name.
    opened: Option<(i32, String)>,
    texture: Option<Texture>,
    current: Option<Clothes>,
    /// The account's clothes after a change, for the title bar and the parade (`App`).
    changed: Option<Clothes>,
    avatar: Avatar,
    /// `false` without the wgpu renderer: the page shows the still picture at once.
    gpu: bool,
    last_screen: i32,
    pending_nav: Vec<&'static str>,
}

fn closet(ui: &AppWindow) -> Closet<'_> {
    ui.global::<Closet>()
}

fn shown(error: &anyhow::Error) -> String {
    let text = format!("{error:#}");
    if text.trim().is_empty() { "未知错误".into() } else { text }
}

/// The account's clothes now (`/player/{uid}/info`), as Compose reads `loggedAccount.cloth`.
async fn current_clothes(api: &Client) -> Result<Clothes> {
    let uid = api.uid()?;
    let info: PlayerInfo = api.get(&format!("/player/{uid}/info")).await?;
    Ok(Clothes::from_cloth(info.cloth.as_ref()))
}

impl ClosetUi {
    pub fn wire(ui: &AppWindow, runtime: Handle, gpu: bool) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            api: None,
            tx,
            rx,
            epoch: 0,
            opened: None,
            texture: None,
            current: None,
            changed: None,
            avatar: Avatar::default(),
            gpu,
            last_screen: 0,
            pending_nav: vec![],
        }));
        let weak = Rc::downgrade(&state);
        closet(ui).on_action(move |name, value| {
            if let Some(state) = weak.upgrade() {
                match state.try_borrow_mut() {
                    Ok(mut state) => state.action(name.as_str(), value.as_str()),
                    Err(_) => tracing::warn!(%name, "衣柜操作重入，已忽略"),
                }
            }
        });
        state
    }

    pub fn avatar(&self) -> Avatar {
        self.avatar.clone()
    }

    pub fn connect(&mut self, api: Option<Client>) {
        self.epoch += 1;
        self.api = api;
        self.opened = None;
        self.texture = None;
        self.current = None;
        self.changed = None;
        while self.rx.try_recv().is_ok() {}
        self.leave_preview();
        if let Some(ui) = self.ui.upgrade() {
            let g = closet(&ui);
            g.set_mojang_open(false);
            g.set_mojang_busy(false);
            g.set_applying(false);
        }
    }

    /// The clothes the account wears after an apply or an import, once.
    pub fn take_changed(&mut self) -> Option<Clothes> {
        self.changed.take()
    }

    fn leave_preview(&mut self) {
        let mut slot = self.avatar.borrow_mut();
        slot.active = false;
        slot.pending = None;
        slot.clear = true;
    }

    fn open_preview(&mut self, ui: &AppWindow, value: &str) {
        let Some((id, name)) = value.split_once('|').and_then(|(id, name)| Some((id.parse::<i32>().ok()?, name.to_owned()))) else { return };
        self.opened = Some((id, name));
        self.pending_nav.push("skin-preview");
        self.load_preview(ui);
    }

    fn load_preview(&mut self, ui: &AppWindow) {
        let (Some(api), Some((id, name))) = (self.api.clone(), self.opened.clone()) else { return };
        self.epoch += 1;
        self.texture = None;
        self.current = None;
        {
            let mut slot = self.avatar.borrow_mut();
            slot.pending = None;
            slot.clear = true;
            slot.active = false;
        }
        let g = closet(ui);
        g.set_title(format!("预览：{}", if name.trim().is_empty() { "皮肤" } else { name.trim() }).into());
        g.set_loading(true);
        g.set_error("".into());
        g.set_can_apply(false);
        g.set_applying(false);
        g.set_avatar_state(0);
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = async {
                let http = api.http.clone();
                let (texture, current) = tokio::try_join!(closet::resolve(&http, closet::LITTLE_SKIN, id), current_clothes(&api))?;
                let wearing = closet::wearing(&current, &texture);
                let still = async {
                    let url = format!("{}/preview/{id}?height=600&png", closet::LITTLE_SKIN);
                    match images::download_image(&http, &url).await {
                        Ok(image) => Some(image.to_rgba8()),
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "皮肤预览图加载失败");
                            None
                        }
                    }
                };
                let (appearance, still) = tokio::join!(images::background_appearance(&http, Some(wearing.to_cloth())), still);
                anyhow::Ok(Preview { texture, current, appearance: appearance?, still })
            }
            .await;
            let _ = tx.send(Reply::Preview(epoch, result));
        });
    }

    fn apply(&mut self, ui: &AppWindow) {
        let (Some(api), Some(texture), Some(current)) = (self.api.clone(), self.texture.clone(), self.current.clone()) else { return };
        let g = closet(ui);
        if g.get_applying() {
            return;
        }
        g.set_applying(true);
        let clothes = closet::wearing(&current, &texture);
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = closet::set_clothes(&api, &clothes).await.map(|()| clothes);
            let _ = tx.send(Reply::Applied(epoch, result));
        });
    }

    fn import(&mut self, ui: &AppWindow) {
        let g = closet(ui);
        let Some(api) = self.api.clone() else { return };
        if g.get_mojang_busy() {
            return;
        }
        let (skin, cape) = (g.get_mojang_skin(), g.get_mojang_cape());
        let name = match closet::check_import(&g.get_mojang_name(), skin, cape) {
            Ok(name) => name,
            Err(error) => {
                g.set_mojang_error(error.to_string().into());
                return;
            }
        };
        g.set_mojang_busy(true);
        g.set_mojang_error("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = async {
                let (current, textures) = tokio::try_join!(current_clothes(&api), closet::mojang_textures(&api.http, None, None, &name))?;
                let clothes = closet::imported(&current, &name, &textures, skin, cape)?;
                closet::set_clothes(&api, &clothes).await?;
                anyhow::Ok(clothes)
            }
            .await;
            let _ = tx.send(Reply::Imported(epoch, result));
        });
    }

    fn action(&mut self, name: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = closet(&ui);
        match name {
            "preview" => self.open_preview(&ui, value),
            "retry" => self.load_preview(&ui),
            "apply" => self.apply(&ui),
            "back-nav" => self.pending_nav.push("back"),
            "mojang-open" => {
                g.set_mojang_name("".into());
                g.set_mojang_skin(true);
                g.set_mojang_cape(false);
                g.set_mojang_error("".into());
                g.set_mojang_busy(false);
                g.set_mojang_open(true);
            }
            "mojang-name" => g.set_mojang_error("".into()),
            "mojang-ok" => self.import(&ui),
            "mojang-cancel" | "dismiss" if !g.get_mojang_busy() => g.set_mojang_open(false),
            _ => tracing::debug!(name, "忽略的衣柜操作"),
        }
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            ui.invoke_navigate(action.into());
        }
        let g = closet(&ui);
        let screen = g.get_screen();
        if screen != self.last_screen {
            if screen == 0 {
                self.epoch += 1;
                self.leave_preview();
                g.set_loading(false);
                g.set_applying(false);
            }
            self.last_screen = screen;
        }
        if screen == 1 && g.get_avatar_state() == 1 || self.avatar.borrow().active && g.get_avatar_state() == 0 {
            // The player turns: draw again every timer tick.
            ui.window().request_redraw();
        }
        if self.avatar.borrow().failed && g.get_avatar_state() != 2 && screen == 1 && !g.get_loading() {
            g.set_avatar_state(2);
        }
        while let Ok(reply) = self.rx.try_recv() {
            match reply {
                Reply::Preview(epoch, result) if epoch == self.epoch => {
                    g.set_loading(false);
                    match result {
                        Ok(preview) => {
                            g.set_title(format!("预览：{}", preview.texture.name).into());
                            if let Some(still) = &preview.still {
                                g.set_fallback(images::to_slint(still));
                            }
                            let mut slot = self.avatar.borrow_mut();
                            if self.gpu && !slot.failed {
                                slot.pending = Some(preview.appearance);
                                slot.clear = false;
                                slot.active = true;
                                g.set_avatar_state(0);
                            } else {
                                g.set_avatar_state(2);
                            }
                            self.texture = Some(preview.texture);
                            self.current = Some(preview.current);
                            g.set_can_apply(true);
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "Blessing Skin预览加载失败");
                            g.set_error(format!("加载预览失败：{}", shown(&error)).into());
                        }
                    }
                }
                Reply::Applied(epoch, result) if epoch == self.epoch => {
                    g.set_applying(false);
                    match result {
                        Ok(clothes) => {
                            self.changed = Some(clothes);
                            self.pending_nav.push("back");
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "Blessing Skin设置失败");
                            ui.invoke_notify(format!("设置失败：{}", shown(&error)).into());
                        }
                    }
                }
                Reply::Imported(epoch, result) if epoch == self.epoch => {
                    g.set_mojang_busy(false);
                    match result {
                        Ok(clothes) => {
                            self.changed = Some(clothes);
                            g.set_mojang_open(false);
                            ui.invoke_notify("导入成功".into());
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "导入正版皮肤失败");
                            g.set_mojang_error(shown(&error).into());
                        }
                    }
                }
                _ => {}
            }
        }
    }
}
