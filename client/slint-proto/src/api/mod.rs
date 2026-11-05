pub mod model;
use anyhow::{Context, Result, bail};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, de::DeserializeOwned};
use std::time::Duration;
#[derive(Clone)]
pub struct Client {
    pub http: reqwest::Client,
    base: String,
    token: String,
}
#[derive(Deserialize)]
struct Envelope {
    code: i32,
    #[serde(default)]
    msg: String,
    data: Option<serde_json::Value>,
}
pub fn decode_envelope<T: DeserializeOwned>(status: u16, bytes: &[u8]) -> Result<T> {
    let envelope: Envelope = match serde_json::from_slice(bytes) {
        Ok(e) => e,
        Err(_) => bail!("服务器响应无效（HTTP{status}）"),
    };
    if !(200..300).contains(&status) || envelope.code != 0 {
        bail!("HTTP{status} / {}：{}", envelope.code, envelope.msg);
    }
    serde_json::from_value(envelope.data.context("服务器未返回数据")?)
        .context("服务器数据格式不兼容")
}
pub fn jwt_uid(token: &str) -> Result<String> {
    let payload = token.split('.').nth(1).context("登录凭据格式无效")?;
    let bytes = URL_SAFE_NO_PAD
        .decode(payload)
        .context("登录凭据编码无效")?;
    #[derive(Deserialize)]
    struct Claims {
        uid: String,
    }
    let uid = serde_json::from_slice::<Claims>(&bytes)
        .context("登录凭据缺少uid")?
        .uid;
    anyhow::ensure!(
        uid.len() == 24 && uid.bytes().all(|b| b.is_ascii_hexdigit()),
        "登录凭据uid无效"
    );
    Ok(uid)
}
impl Client {
    pub fn new(base: String) -> Result<Self> {
        let url = reqwest::Url::parse(&base).context("服务器地址无效")?;
        anyhow::ensure!(
            url.scheme() == "https"
                || (url.scheme() == "http"
                    && matches!(url.host_str(), Some("127.0.0.1" | "localhost"))),
            "服务器地址必须使用HTTPS"
        );
        Ok(Self {
            http: reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .timeout(Duration::from_secs(30))
                .user_agent("rdi-slint-proto/0.1")
                .build()?,
            base: base.trim_end_matches('/').to_owned(),
            token: String::new(),
        })
    }
    pub async fn login(mut self, usr: &str, pwd: &str) -> Result<Self> {
        self.token = Self::response(
            self.http
                .post(format!("{}/player/jwt", self.base))
                .json(&serde_json::json!({"usr":usr,"pwd":pwd})),
            "/player/jwt",
        )
        .await?;
        Ok(self)
    }
    pub fn uid(&self) -> Result<String> {
        jwt_uid(&self.token)
    }
    pub async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        Self::response(
            self.http
                .get(format!("{}{path}", self.base))
                .bearer_auth(&self.token),
            path,
        )
        .await
    }
    async fn response<T: DeserializeOwned>(
        request: reqwest::RequestBuilder,
        path: &str,
    ) -> Result<T> {
        let res = request
            .send()
            .await
            .map_err(|e| anyhow::anyhow!("无法连接服务器，请检查网络连接：{}", e.without_url()))?;
        let status = res.status().as_u16();
        tracing::info!(path, status, "API response");
        let bytes = res.bytes().await.context("读取服务器响应失败")?;
        decode_envelope(status, &bytes)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn envelopes() {
        assert_eq!(
            decode_envelope::<String>(200, br#"{"code":0,"msg":"","data":"ok"}"#).unwrap(),
            "ok"
        );
        for s in [200, 403] {
            assert!(
                decode_envelope::<String>(s, br#"{"code":3,"msg":"denied"}"#)
                    .unwrap_err()
                    .to_string()
                    .contains("denied")
            );
        }
        assert!(
            decode_envelope::<String>(502, b"bad gateway")
                .unwrap_err()
                .to_string()
                .contains("HTTP502")
        );
        assert!(decode_envelope::<String>(200, br#"{"code":0,"data":null}"#).is_err());
    }
    #[test]
    fn uid_from_unsigned_token() {
        let id = "0123456789abcdef01234567";
        let p = URL_SAFE_NO_PAD.encode(format!(r#"{{"uid":"{id}"}}"#));
        assert_eq!(jwt_uid(&format!("e30.{p}.")).unwrap(), id);
        assert!(jwt_uid("bad").is_err());
    }
}
