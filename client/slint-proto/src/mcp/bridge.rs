//! Requests from MCP tool handlers (tokio threads) to the UI thread, which owns the signed-in
//! account and the deps builders. `App::tick` answers them; it runs every 16ms also while the
//! window is unfocused or minimized, since the event loop's timer keeps firing.
use crate::api::Client;
use anyhow::{Context, Result};
use std::sync::mpsc;
use std::time::Duration;
use tokio::sync::oneshot;

/// The signed-in account.
#[allow(dead_code)] // `api`: M2 tools
pub struct Account {
    pub api: Client,
    /// The name the title bar shows.
    pub name: String,
}

/// What a host start needs from the UI thread.
#[allow(dead_code)] // M2 tools
pub struct RoomKit {
    pub api: Client,
    pub tasks: crate::tasks::Manager,
    pub deps: Box<dyn FnOnce() -> crate::play::room::RoomDeps + Send>,
    /// Physical pixels of the launcher window, as a game started from the UI gets.
    pub window: (u32, u32),
}

/// What a pack install needs from the UI thread.
#[allow(dead_code)] // M2 tools
pub struct InstallKit {
    pub api: Client,
    pub deps: Box<dyn FnOnce() -> crate::pack_install::Deps + Send>,
}

#[allow(dead_code)] // `Room`, `Install`: M2 tools
pub enum Request {
    Account(oneshot::Sender<Option<Account>>),
    Room(oneshot::Sender<Option<RoomKit>>),
    Install(oneshot::Sender<Option<InstallKit>>),
}

/// The sending side, cheap to clone and usable from any thread.
#[derive(Clone)]
pub struct Bridge(mpsc::Sender<Request>);

const ANSWER_TIMEOUT: Duration = Duration::from_secs(10);

impl Bridge {
    pub fn new() -> (Self, mpsc::Receiver<Request>) {
        let (tx, rx) = mpsc::channel();
        (Self(tx), rx)
    }

    async fn ask<T>(&self, make: impl FnOnce(oneshot::Sender<T>) -> Request) -> Result<T> {
        let (tx, rx) = oneshot::channel();
        self.0.send(make(tx)).ok().context("启动器正在关闭")?;
        tokio::time::timeout(ANSWER_TIMEOUT, rx).await.ok().context("启动器没有响应，请稍后再试")?.ok().context("启动器正在关闭")
    }

    pub async fn account(&self) -> Result<Option<Account>> {
        self.ask(Request::Account).await
    }

    #[allow(dead_code)] // M2 tools
    pub async fn room(&self) -> Result<Option<RoomKit>> {
        self.ask(Request::Room).await
    }

    #[allow(dead_code)] // M2 tools
    pub async fn install(&self) -> Result<Option<InstallKit>> {
        self.ask(Request::Install).await
    }
}
