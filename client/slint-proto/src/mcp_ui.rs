//! The AI assistant's title bar icon and menu (`LAUNCHER-MCP-PLAN.md`, player experience).
//! Off after every launcher start; a click starts it and the Slint side copies the prompt.
use crate::AppWindow;
use crate::mcp::{self, Controller, State, bridge};
use slint::ComponentHandle;
use std::{cell::RefCell, rc::Rc, sync::mpsc};

pub struct McpUi {
    controller: Controller,
    requests: mpsc::Receiver<bridge::Request>,
}

impl McpUi {
    pub fn wire(ui: &AppWindow, runtime: tokio::runtime::Handle, sessions: crate::play::Sessions, tasks: crate::tasks::Manager) -> Rc<RefCell<Self>> {
        let (bridge, requests) = bridge::Bridge::new();
        let context = mcp::tools::Context { sessions, tasks, bridge, launcher_version: env!("RDI_APP_VERSION").into() };
        let this = Rc::new(RefCell::new(Self { controller: Controller::new(mcp::settings_path(), runtime, context), requests }));
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
                ui.invoke_notify("提示词已复制".into());
            }
        });
        this
    }

    /// Starts the server and fills the prompt; the caller copies it when this returns true.
    fn start(&mut self, ui: &AppWindow) -> bool {
        match self.controller.start() {
            Ok(started) => {
                self.render(ui);
                ui.invoke_notify(
                    if started.port_changed { "AI助手已开启，地址有变化，请把新复制的提示词发给AI" } else { "AI助手已开启，提示词已复制" }.into(),
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

    fn render(&self, ui: &AppWindow) {
        let state = match self.controller.state() {
            State::Stopped => 0,
            State::Running { .. } => 1,
            State::Failed(_) => 2,
        };
        ui.set_mcp_state(state);
        ui.set_mcp_prompt(self.controller.prompt().unwrap_or_default().into());
        ui.set_mcp_status("AI助手已开启".into());
        if state != 1 {
            ui.set_mcp_menu(false);
        }
    }

    /// Requests from tool handlers, answered by `App::tick` on the UI thread.
    pub fn take_requests(&self) -> Vec<bridge::Request> {
        self.requests.try_iter().collect()
    }
}
