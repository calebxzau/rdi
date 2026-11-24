pub mod model;
pub mod server;
pub mod sse;
use anyhow::{Result, ensure};
use serde::de::DeserializeOwned;
use serde_json::Value;
use server::{ServerApi, Session};
// Keep the updater source unchanged; its modulo spelling is outside this port.
#[allow(clippy::manual_is_multiple_of)]
#[path = "../../../updater/src/java_discovery.rs"]
pub(crate) mod java_discovery;

#[derive(Clone)]
pub struct Client {
    pub http: reqwest::Client,
    uid: String,
    pub is_dav: bool,
    server: ServerApi,
    /// J5a local play: the default offline name and whether a Microsoft profile is bound.
    pub account_name: String,
    pub has_msid: bool,
    /// J5c online play: the bound Microsoft profile id, as the server stores it.
    pub msid: Option<String>,
}
impl Client {
    pub async fn connect(server: ServerApi, session: Session) -> Result<Self> {
        let uid = session.uid()?.to_owned();
        let is_dav = session.account["name"].as_str() == Some("davickk");
        let account_name = session.account["name"].as_str().unwrap_or_default().to_owned();
        let msid = session.account["msid"].as_str().map(str::trim).filter(|m| !m.is_empty()).map(str::to_owned);
        Ok(Self { http: server.http.clone(), server, uid, is_dav, account_name, has_msid: msid.is_some(), msid })
    }
    pub fn server(&self) -> &ServerApi {
        &self.server
    }
    pub fn uid(&self) -> Result<String> {
        ensure!(!self.uid.is_empty(), "请先登录");
        Ok(self.uid.clone())
    }
    pub async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.server.get(path).await
    }
    pub async fn get_query<T: DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<T> {
        self.server.get_query(path, query).await
    }
    pub async fn post<T: DeserializeOwned + Default>(&self, path: &str, body: &Value) -> Result<T> {
        self.server.post(path, body).await
    }
}
