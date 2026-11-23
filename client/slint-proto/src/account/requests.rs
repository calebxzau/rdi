//! Server requests of 注册, 忘记密码 and 个人信息, with Kotlin's paths and bodies.
use super::msa::MsaProfile;
use crate::{
    api::{Client, server::ServerApi},
    credentials::Credentials,
};
use anyhow::Result;
use serde_json::{Map, Value, json};

/// RegisterScreen (Microsoft): `player/register` with `RegisterDto`.
pub async fn register(server: &ServerApi, name: &str, qq: &str, pwd: &str, msa: &MsaProfile) -> Result<()> {
    let body = json!({"name": name, "qq": qq, "pwd": pwd, "msa": msa.json()});
    server.post_public::<Value>("/player/register", &body).await?;
    Ok(())
}

/// `PlayerService.resetPasswordByMsa`.
pub async fn reset_by_msa(server: &ServerApi, msa: &MsaProfile, new_pwd: &str) -> Result<()> {
    let body = json!({"msa": msa.json(), "newPwd": new_pwd});
    server.post_public::<Value>("/player/reset-pwd/msa", &body).await?;
    Ok(())
}

/// ReceiptQueryDialogs: the receipt message, empty when the server sends none.
pub async fn receipt(server: &ServerApi, id: &str) -> Result<String> {
    server.get_public::<String>(&format!("/receipt/{id}")).await
}

/// ChangeProfileDialog: only the changed fields are sent.
pub async fn change_profile(api: &Client, name: Option<&str>, pwd: Option<&str>) -> Result<()> {
    let mut body = Map::new();
    if let Some(name) = name {
        body.insert("name".into(), json!(name));
    }
    if let Some(pwd) = pwd {
        body.insert("pwd".into(), json!(pwd));
    }
    api.server().put::<Value>("/player/profile", Some(&Value::Object(body))).await?;
    Ok(())
}

/// PlayerInfoScreen 「确认绑定」.
pub async fn bind(api: &Client, msa: &MsaProfile) -> Result<()> {
    api.post::<Value>("/player/bind-ms", &msa.json()).await?;
    Ok(())
}

/// The saved password of the signed-in account (Kotlin keeps it in `loggedAccount.pwd`).
pub fn saved_password(uid: &str) -> Result<String> {
    Credentials::saved(&crate::credentials::file(), uid).map(|(_, pwd)| pwd)
}

/// Kotlin signs in again after a change, so the saved account holds the new name, password and
/// Microsoft binding. Returns the account the server sent.
pub async fn refresh_saved(base: &str, uid: &str, pwd: &str) -> Result<Value> {
    let mut server = ServerApi::new(base.to_owned())?;
    let session = server.login(uid, pwd, None).await?;
    let account = session.account.clone();
    let saved = account.clone();
    tokio::task::spawn_blocking(move || Credentials::save_account(&crate::credentials::file(), &saved)).await??;
    Ok(account)
}
