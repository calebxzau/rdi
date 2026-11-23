//! Microsoft device-code sign-in, returning what Kotlin `MsaAccountInfo` holds: the Minecraft
//! profile id and name and a Minecraft access token. Nothing is saved; the server checks the
//! token itself. Owner decision D1 chose azalea-auth, but its azalea-buf needs nightly Rust
//! (simdnbt), so this is the agreed fallback: the same live.com flow azalea-auth uses.
use anyhow::{Result, anyhow, bail};
use serde_json::{Value, json};
use std::time::{Duration, Instant};

/// The Nintendo Switch application, azalea-auth's default, which works for players under 18.
/// Compose's MinecraftAuth uses the Java title id `00000000402b5328` through SISU device
/// signing instead; the server only checks the resulting Minecraft token.
const CLIENT_ID: &str = "00000000441cc96b";
const SCOPE: &str = "service::user.auth.xboxlive.com::MBI_SSL";
/// Kotlin `MICROSOFT_LOGIN_TIMEOUT_MS`: ten minutes.
const TIMEOUT: Duration = Duration::from_secs(600);

#[derive(Clone, Debug, PartialEq)]
pub struct MsaProfile {
    /// Dashed, as Kotlin `UUID.toString`.
    pub uuid: String,
    pub name: String,
    pub token: String,
}
impl MsaProfile {
    /// Kotlin `MsaAccountInfo` JSON.
    pub fn json(&self) -> Value {
        json!({"uuid": self.uuid, "name": self.name, "token": self.token})
    }
}

/// MinecraftAuth `directVerificationUri`: the page with the code already filled in.
pub fn direct_uri(verification_uri: &str, user_code: &str) -> String {
    format!("{verification_uri}?otc={user_code}")
}

/// One answer while waiting for the player.
#[derive(Debug, PartialEq)]
enum Poll {
    Token(String),
    Pending,
    SlowDown,
    Failed(&'static str),
}

/// The live.com token endpoint's reply to a device-code poll.
fn poll_reply(body: &Value) -> Poll {
    if let Some(token) = body["access_token"].as_str().filter(|t| !t.is_empty()) {
        return Poll::Token(token.to_owned());
    }
    match body["error"].as_str().unwrap_or_default() {
        "authorization_pending" => Poll::Pending,
        "slow_down" => Poll::SlowDown,
        "authorization_declined" | "access_denied" => Poll::Failed("已在浏览器中取消登录"),
        "expired_token" => Poll::Failed("登录链接已过期，请重试"),
        _ => Poll::Failed("微软登录失败，请重试"),
    }
}

/// The XSTS `XErr` codes a player can fix, as the Minecraft launcher explains them.
fn xsts_error(code: i64) -> &'static str {
    match code {
        2148916233 => "这个微软账号还没有Xbox档案，请先登录一次minecraft.net或Xbox创建档案",
        2148916235 => "Xbox Live在这个账号所在的国家或地区不可用",
        2148916236 | 2148916237 => "这个微软账号需要先在Xbox网站完成成人验证",
        2148916238 => "这是未成年人账号，需要家长先把它加入微软家庭组",
        _ => "读取Xbox账号失败，请稍后重试",
    }
}

/// Minecraft's undashed profile id as a dashed UUID.
fn dashed(id: &str) -> Result<String> {
    let id = id.replace('-', "").to_ascii_lowercase();
    anyhow::ensure!(id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit()), "MC档案格式不兼容");
    Ok(format!("{}-{}-{}-{}-{}", &id[..8], &id[8..12], &id[12..16], &id[16..20], &id[20..]))
}

fn network(step: &str, error: reqwest::Error) -> anyhow::Error {
    // Diagnostics name the Microsoft host only; device codes and tokens travel in bodies.
    tracing::warn!(step, error = %error.without_url(), "微软登录请求失败");
    anyhow!("无法连接微软登录服务，请检查网络")
}

async fn json_body(step: &str, response: reqwest::Response) -> Result<(reqwest::StatusCode, Value)> {
    let status = response.status();
    let body = response.bytes().await.map_err(|e| network(step, e))?;
    // A non-JSON body (an HTML error page) is reported without its contents.
    let value = serde_json::from_slice(&body).unwrap_or(Value::Null);
    Ok((status, value))
}

/// Asks Microsoft for a device code, hands its link to `on_link`, waits up to ten minutes for
/// the player to sign in, then reads the Minecraft profile.
pub async fn sign_in(http: &reqwest::Client, on_link: impl FnOnce(String)) -> Result<MsaProfile> {
    let response = http
        .post("https://login.live.com/oauth20_connect.srf")
        .form(&[("scope", SCOPE), ("client_id", CLIENT_ID), ("response_type", "device_code")])
        .send()
        .await
        .map_err(|e| network("device code", e))?;
    let (_, code) = json_body("device code", response).await?;
    let (Some(device_code), Some(user_code), Some(uri)) =
        (code["device_code"].as_str(), code["user_code"].as_str(), code["verification_uri"].as_str())
    else {
        bail!("获取微软登录链接失败，请重试");
    };
    on_link(direct_uri(uri, user_code));
    let mut interval = Duration::from_secs(code["interval"].as_u64().unwrap_or(5).clamp(1, 30));
    let expires = Duration::from_secs(code["expires_in"].as_u64().unwrap_or(900)).min(TIMEOUT);
    let deadline = Instant::now() + expires;
    let access_token = loop {
        if Instant::now() >= deadline {
            bail!("登录超时，请在10分钟内完成微软登录");
        }
        tokio::time::sleep(interval).await;
        let response = http
            .post(format!("https://login.live.com/oauth20_token.srf?client_id={CLIENT_ID}"))
            .form(&[
                ("client_id", CLIENT_ID),
                ("device_code", device_code),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ])
            .send()
            .await
            .map_err(|e| network("device token", e))?;
        let (_, body) = json_body("device token", response).await?;
        match poll_reply(&body) {
            Poll::Token(token) => break token,
            Poll::Pending => {}
            Poll::SlowDown => interval += Duration::from_secs(5),
            Poll::Failed(message) => {
                tracing::warn!(error = body["error"].as_str().unwrap_or("none"), "微软登录未完成");
                bail!("{message}");
            }
        }
    };
    let response = http
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .header("Accept", "application/json")
        .header("x-xbl-contract-version", "1")
        .json(&json!({
            "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": access_token},
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        }))
        .send()
        .await
        .map_err(|e| network("xbox live", e))?;
    let (status, xbl) = json_body("xbox live", response).await?;
    let (Some(xbl_token), Some(user_hash)) = (xbl["Token"].as_str(), xbl["DisplayClaims"]["xui"][0]["uhs"].as_str()) else {
        tracing::warn!(status = status.as_u16(), "Xbox Live登录失败");
        bail!("读取Xbox账号失败，请稍后重试");
    };
    let response = http
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .header("Accept", "application/json")
        .json(&json!({
            "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        }))
        .send()
        .await
        .map_err(|e| network("xsts", e))?;
    let (status, xsts) = json_body("xsts", response).await?;
    let Some(xsts_token) = xsts["Token"].as_str() else {
        let code = xsts["XErr"].as_i64().unwrap_or_default();
        tracing::warn!(status = status.as_u16(), code, "XSTS授权失败");
        bail!("{}", xsts_error(code));
    };
    let response = http
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .header("Accept", "application/json")
        .json(&json!({"identityToken": format!("XBL3.0 x={user_hash};{xsts_token}")}))
        .send()
        .await
        .map_err(|e| network("minecraft", e))?;
    let (status, minecraft) = json_body("minecraft", response).await?;
    let Some(token) = minecraft["access_token"].as_str() else {
        tracing::warn!(status = status.as_u16(), "MC登录失败");
        bail!("登录MC服务失败，请稍后重试");
    };
    let response = http
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| network("profile", e))?;
    let (status, profile) = json_body("profile", response).await?;
    let (Some(id), Some(name)) = (profile["id"].as_str(), profile["name"].as_str()) else {
        tracing::warn!(status = status.as_u16(), "读取MC档案失败");
        bail!("这个微软账号没有Minecraft Java版档案，请确认已购买并设置过角色名");
    };
    Ok(MsaProfile { uuid: dashed(id)?, name: name.to_owned(), token: token.to_owned() })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn profile_json_matches_kotlin_msa_account_info() {
        let p = MsaProfile { uuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5".into(), name: "Notch".into(), token: "t".into() };
        assert_eq!(p.json(), json!({"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","token":"t"}));
        assert_eq!(direct_uri("https://www.microsoft.com/link", "AB12CD"), "https://www.microsoft.com/link?otc=AB12CD");
    }
    #[test]
    fn device_code_polls() {
        assert_eq!(poll_reply(&json!({"access_token":"x","expires_in":3600})), Poll::Token("x".into()));
        assert_eq!(poll_reply(&json!({"error":"authorization_pending"})), Poll::Pending);
        assert_eq!(poll_reply(&json!({"error":"slow_down"})), Poll::SlowDown);
        assert_eq!(poll_reply(&json!({"error":"authorization_declined"})), Poll::Failed("已在浏览器中取消登录"));
        assert_eq!(poll_reply(&json!({"error":"expired_token"})), Poll::Failed("登录链接已过期，请重试"));
        assert_eq!(poll_reply(&Value::Null), Poll::Failed("微软登录失败，请重试"));
    }
    #[test]
    fn xsts_errors_and_profile_ids() {
        assert!(xsts_error(2148916238).contains("家庭组"));
        assert!(xsts_error(2148916233).contains("Xbox档案"));
        assert_eq!(xsts_error(1), "读取Xbox账号失败，请稍后重试");
        assert_eq!(dashed("069A79F444E94726A5BEFCA90E38AAF5").unwrap(), "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assert!(dashed("069a").is_err());
    }
}
