//! J5c online play of local packs: the Microsoft profile bound to the RDI account (its msid), as
//! Kotlin `OnlineGameAccountService` resolves it.
//!
//! Decision C2: Rust keeps its sessions in its own file, `msa_game_sessions.json`, because Compose
//! rewrites `msa_sessions.json` from its data class and would drop anything Rust added there.
//! Layout: `accounts.<accountId>.<msid>` = `{refreshToken, mcToken, mcTokenExpiresAt, name}`,
//! plain text like Compose's file. Unknown keys are kept. Decision C3: the Switch title id of
//! `account::msa`; the Minecraft token is refreshed when it has under one hour left.
use crate::account::msa::{self, Endpoints};
use crate::mc_launch::Account;
use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "msa_game_sessions.json";
/// A token with less than this left is refreshed before the game starts.
const REFRESH_MARGIN_MS: i64 = 60 * 60 * 1000;

/// One saved session. `Debug` is not derived: it holds tokens.
#[derive(Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Saved {
    pub refresh_token: String,
    pub mc_token: String,
    /// Milliseconds since the epoch.
    pub mc_token_expires_at: i64,
    pub name: String,
}

#[derive(Serialize, Deserialize, Default)]
struct Sessions {
    #[serde(default)]
    accounts: BTreeMap<String, BTreeMap<String, Saved>>,
    #[serde(flatten)]
    other: Map<String, Value>,
}

/// Read-modify-write of the file is one step at a time within the launcher.
static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

pub fn path() -> PathBuf {
    crate::paths::data_dir().join(FILE_NAME)
}

fn read(path: &Path) -> Result<Sessions> {
    match std::fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("正版会话文件格式无效")),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Sessions::default()),
        Err(error) => Err(error).context("读取正版会话失败"),
    }
}

/// Kotlin `readOrReset`: a damaged file is set aside so a new login can still be saved.
fn read_or_reset(path: &Path, now_ms: i64) -> Result<Sessions> {
    match read(path) {
        Ok(sessions) => Ok(sessions),
        Err(error) if path.is_file() => {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or(FILE_NAME);
            let backup = path.with_file_name(format!("{name}.broken-{now_ms}"));
            tracing::warn!(error = %error, backup = %backup.display(), "正版会话文件损坏，已改名");
            std::fs::rename(path, &backup).context("正版会话文件损坏且无法改名")?;
            Ok(Sessions::default())
        }
        Err(error) => Err(error),
    }
}

fn write(path: &Path, sessions: &Sessions) -> Result<()> {
    crate::credentials::write_atomic(path, &serde_json::to_vec(sessions)?)
}

pub fn find(path: &Path, account_id: &str, msid: &str) -> Result<Option<Saved>> {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    Ok(read(path)?.accounts.get(account_id).and_then(|m| m.get(msid)).cloned())
}

pub fn save(path: &Path, account_id: &str, msid: &str, saved: Saved, now_ms: i64) -> Result<()> {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut sessions = read_or_reset(path, now_ms)?;
    sessions.accounts.entry(account_id.to_owned()).or_default().insert(msid.to_owned(), saved);
    write(path, &sessions)
}

/// Forgets every session of the account, when the player logs out of RDI.
pub fn clear(path: &Path, account_id: &str) -> Result<()> {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut sessions = read(path)?;
    if sessions.accounts.remove(account_id).is_none() {
        return Ok(());
    }
    write(path, &sessions)
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

/// The msid as Kotlin `UUID.toString` writes it, so dashed and undashed forms match.
pub fn normalize_msid(msid: &str) -> Result<String> {
    msa::dashed(msid.trim()).map_err(|_| anyhow::anyhow!("绑定的正版账号格式无效"))
}

/// What a resolve needs; tests replace the endpoints, the file and the clock.
pub struct Resolver {
    pub http: reqwest::Client,
    pub endpoints: Endpoints,
    pub path: PathBuf,
    pub now_ms: fn() -> i64,
}

impl Resolver {
    pub fn new(http: reqwest::Client) -> Self {
        Self { http, endpoints: Endpoints::default(), path: path(), now_ms }
    }

    /// The game identity for `msid`: the saved token while it lasts, else a refresh, else a
    /// device-code login whose link goes to `on_link`. A login as another profile is refused.
    pub async fn resolve(&self, account_id: &str, msid: &str, on_link: impl FnOnce(String)) -> Result<Account> {
        let msid = normalize_msid(msid)?;
        let saved = find(&self.path, account_id, &msid).unwrap_or_else(|error| {
            tracing::warn!(error = %error, "读取正版会话失败，将重新登录");
            None
        });
        let now = (self.now_ms)();
        if let Some(saved) = &saved
            && saved.mc_token_expires_at - now > REFRESH_MARGIN_MS
        {
            return Ok(account(&msid, &saved.name, &saved.mc_token));
        }
        let mut refreshed = None;
        if let Some(saved) = &saved {
            match msa::refresh(&self.http, &self.endpoints, &saved.refresh_token).await {
                Ok(session) if session.profile.uuid == msid => {
                    let refresh = session.refresh_token.clone().unwrap_or_else(|| saved.refresh_token.clone());
                    refreshed = Some((session, refresh));
                }
                Ok(_) => tracing::warn!("保存的正版会话不属于绑定账号，将重新登录"),
                Err(error) => tracing::warn!(error = %error, "正版会话已失效，将重新登录"),
            }
        }
        let (session, refresh) = match refreshed {
            Some(pair) => pair,
            None => {
                let session = msa::sign_in_session(&self.http, &self.endpoints, on_link).await?;
                if session.profile.uuid != msid {
                    bail!("登录的正版账号{}与RDI账号绑定的正版账号不一致", session.profile.name);
                }
                let Some(refresh) = session.refresh_token.clone() else {
                    // Without one the next start needs a new login; play still goes ahead.
                    tracing::warn!("微软登录未返回续期凭据，下次启动需重新登录");
                    return Ok(account(&msid, &session.profile.name, &session.profile.token));
                };
                (session, refresh)
            }
        };
        let now = (self.now_ms)();
        let saved = Saved {
            refresh_token: refresh,
            mc_token: session.profile.token.clone(),
            mc_token_expires_at: now + session.expires_in_secs as i64 * 1000,
            name: session.profile.name.clone(),
        };
        if let Err(error) = save(&self.path, account_id, &msid, saved, now) {
            tracing::warn!(error = %format!("{error:#}"), "保存正版会话失败，下次启动需重新登录");
        }
        Ok(account(&msid, &session.profile.name, &session.profile.token))
    }
}

fn account(msid: &str, name: &str, token: &str) -> Account {
    Account { name: name.to_owned(), uuid: msid.to_owned(), access_token: token.to_owned(), user_type: "msa".into() }
}

#[cfg(test)]
mod tests;
