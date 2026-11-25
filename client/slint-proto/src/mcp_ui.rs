//! The AI assistant's title bar icon and menu (`LAUNCHER-MCP-PLAN.md`, player experience).
//! Off after every launcher start; a click starts it and the Slint side copies the prompt.
use crate::AppWindow;
use crate::mcp::{self, Controller, State, bridge};
use slint::ComponentHandle;
use std::{cell::RefCell, rc::Rc, sync::mpsc};

pub struct McpUi {
    controller: Controller,
    requests: mpsc::Receiver<bridge::Request>,
    /// The agent the title bar shows now.
    shown: Option<mcp::agent::Agent>,
    icons: std::collections::HashMap<mcp::agent::Brand, slint::Image>,
    /// When the assistant was turned on, and whether an agent has come since.
    started: Option<std::time::Instant>,
    connected_once: bool,
    hint: bool,
}

/// Without an agent this long after turning on, the guide says web chats cannot connect.
const HINT_AFTER: std::time::Duration = std::time::Duration::from_secs(5 * 60);

impl McpUi {
    pub fn wire(ui: &AppWindow, runtime: tokio::runtime::Handle, sessions: crate::play::Sessions, tasks: crate::tasks::Manager) -> Rc<RefCell<Self>> {
        let (bridge, requests) = bridge::Bridge::new();
        let context = mcp::tools::Context {
            sessions,
            tasks,
            bridge,
            launcher_version: env!("RDI_APP_VERSION").into(),
            versions: crate::local_packs::versions_dir(),
            java: crate::java_runtime::state,
            game: mcp::game::Forwarder::new(),
            drafts: crate::upload::drafts::Drafts::launcher(),
            agent: Default::default(),
        };
        let this = Rc::new(RefCell::new(Self { controller: Controller::new(mcp::settings_path(), runtime, context), requests, shown: None, icons: Default::default(), started: None, connected_once: false, hint: false }));
        let weak = Rc::downgrade(&this);
        let ui_weak = ui.as_weak();
        ui.on_mcp_start(move || {
            let (Some(this), Some(ui)) = (weak.upgrade(), ui_weak.upgrade()) else {
                return false;
            };
            this.borrow_mut().start(&ui)
        });
        let weak = Rc::downgrade(&this);
        let ui_weak = ui.as_weak();
        ui.on_mcp_stop(move || {
            if let (Some(this), Some(ui)) = (weak.upgrade(), ui_weak.upgrade()) {
                this.borrow_mut().stop(&ui);
            }
        });
        let ui_weak = ui.as_weak();
        ui.on_mcp_copied(move || {
            if let Some(ui) = ui_weak.upgrade() {
                ui.invoke_notify("已复制，去AI软件的对话框里粘贴并发送".into());
            }
        });
        let row: Vec<slint::Image> = mcp::agent::Brand::ALL.iter().filter_map(|brand| this.borrow_mut().icon(*brand)).collect();
        ui.set_mcp_brand_icons(slint::ModelRc::new(slint::VecModel::from(row)));
        this
    }

    /// Starts the server and fills the prompt; the caller copies it when this returns true.
    fn start(&mut self, ui: &AppWindow) -> bool {
        match self.controller.start() {
            Ok(started) => {
                self.started = Some(std::time::Instant::now());
                self.connected_once = false;
                self.render(ui);
                ui.invoke_notify(
                    if started.port_changed { "AI助手已开启，地址有变化，请把新复制的内容发给AI" } else { "AI助手已开启，已复制给AI的内容" }.into(),
                );
                true
            }
            Err(error) => {
                self.render(ui);
                ui.set_action_error(format!("AI助手开启失败：{error:#}").into());
                false
            }
        }
    }

    fn stop(&mut self, ui: &AppWindow) {
        self.controller.stop();
        self.render(ui);
        ui.invoke_notify("AI助手已关闭".into());
    }

    fn render(&mut self, ui: &AppWindow) {
        let state = match self.controller.state() {
            State::Stopped => 0,
            State::Running { .. } => 1,
            State::Failed(_) => 2,
        };
        ui.set_mcp_state(state);
        ui.set_mcp_prompt(self.controller.prompt().unwrap_or_default().into());
        // The menu stays open when the switch turns the assistant off.
        self.shown = None;
        self.show_agent(ui, self.controller.agent());
    }

    /// Every frame: the capsule follows the agent (connected, another one, idle), and the guide
    /// adds its hint when nothing has connected for a while.
    pub fn tick(&mut self, ui: &AppWindow) {
        let agent = self.controller.agent();
        if agent != self.shown {
            self.show_agent(ui, agent);
        }
        let hint = !self.connected_once && self.shown.is_none() && self.started.is_some_and(|t| t.elapsed() > HINT_AFTER) && matches!(self.controller.state(), State::Running { .. });
        if hint != self.hint {
            self.hint = hint;
            ui.set_mcp_hint(hint);
        }
    }

    fn show_agent(&mut self, ui: &AppWindow, agent: Option<mcp::agent::Agent>) {
        let icon = agent.as_ref().and_then(|a| a.brand).and_then(|brand| self.icon(brand));
        ui.set_mcp_agent(agent.as_ref().map(|a| a.label.clone()).unwrap_or_default().into());
        ui.set_mcp_agent_has_icon(icon.is_some());
        ui.set_mcp_agent_icon(icon.unwrap_or_default());
        ui.set_mcp_status(match (&agent, self.controller.state()) {
            (Some(a), _) => format!("已连接：{}", a.detail),
            (None, State::Running { .. }) => "AI助手已开启".into(),
            (None, State::Failed(_)) => "AI助手开启失败".into(),
            (None, State::Stopped) => "AI助手已关闭".into(),
        }
        .into());
        ui.set_mcp_connected(agent.is_some());
        self.connected_once |= agent.is_some();
        self.shown = agent;
    }

    /// The brand's icon, decoded once.
    fn icon(&mut self, brand: mcp::agent::Brand) -> Option<slint::Image> {
        if let Some(image) = self.icons.get(&brand) {
            return Some(image.clone());
        }
        match crate::avif::decode(brand.icon(), 512) {
            Ok(rgba) => {
                let image = slint::Image::from_rgba8(slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(&rgba.pixels, rgba.width, rgba.height));
                self.icons.insert(brand, image.clone());
                Some(image)
            }
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), brand = brand.label(), "AI品牌图标解码失败");
                None
            }
        }
    }

    /// Requests from tool handlers, answered by `App::tick` on the UI thread.
    pub fn take_requests(&self) -> Vec<bridge::Request> {
        self.requests.try_iter().collect()
    }
}
