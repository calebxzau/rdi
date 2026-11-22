pub mod model;
pub mod server;
use anyhow::{Context, Result, ensure};
use rdi_slint_proto::{lazy_service::LazyService, service::Service};
use serde::de::DeserializeOwned;
use serde_json::Value;
use server::{ServerApi, Session};
use std::{process::Command, sync::Arc};
// Keep the updater source unchanged; its modulo spelling is outside this port.
#[allow(clippy::manual_is_multiple_of)]
#[path = "../../../updater/src/java_discovery.rs"]
mod java_discovery;

#[derive(Clone)]
pub struct Client {
    pub http: reqwest::Client,
    service: Arc<LazyService>,
    uid: String,
    pub is_dav: bool,
    server: ServerApi,
    /// The Java 25 the service runs on; Kotlin offered the same path as `globalJavaPath`.
    pub java_path: String,
}
impl Client {
    pub async fn connect(server: ServerApi, session: Session) -> Result<Self> {
        tokio::task::spawn_blocking(move || Self::connect_blocking(server, session)).await?
    }
    fn connect_blocking(server: ServerApi, session: Session) -> Result<Self> {
        let uid = session.uid()?.to_owned();
        let is_dav = session.account["name"].as_str() == Some("davickk");
        // Discovery is useful for options, but an unavailable runtime cannot block sign-in.
        let command = Self::command();
        let java_path = command
            .as_ref()
            .map(|args| args[0].clone())
            .unwrap_or_default();
        let command = command.map_err(|error| format!("{error:#}"));
        let service = LazyService::new(
            move || {
                let args = command
                    .as_ref()
                    .map_err(|error| anyhow::anyhow!(error.clone()))?;
                let mut command = Command::new(&args[0]);
                command
                    .args(&args[1..])
                    .env_remove("RDI_PROTO_USR")
                    .env_remove("RDI_PROTO_PWD");
                let directory = crate::paths::data_dir();
                std::fs::create_dir_all(&directory).context("创建启动器数据目录失败")?;
                command.current_dir(&directory);
                let log = std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(directory.join("ui-service-stderr.log"))?;
                Service::spawn(&mut command, log).map_err(|error| {
                    tracing::error!(error=%format!("{error:#}"), "启动准备失败");
                    anyhow::anyhow!("无法准备游玩，请检查启动器安装")
                })
            },
            session.params(),
            uid.clone(),
        );
        Ok(Self {
            http: server.http.clone(),
            server,
            service: Arc::new(service),
            uid,
            is_dav,
            java_path,
        })
    }
    fn command() -> Result<Vec<String>> {
        if let Ok(value) = std::env::var("RDI_SERVICE_COMMAND") {
            let arguments: Vec<String> = serde_json::from_str(&value)
                .map_err(|_| anyhow::anyhow!("启动配置无效，请检查启动器安装"))?;
            ensure!(!arguments.is_empty(), "启动配置为空，请检查启动器安装");
            return Ok(arguments);
        }
        let java = java_discovery::candidate_paths()
            .into_iter()
            .find(|path| {
                Command::new(path)
                    .arg("-version")
                    .output()
                    .is_ok_and(|output| {
                        output.status.success()
                            && String::from_utf8_lossy(&output.stderr).contains("version \"25")
                    })
            })
            .context("无法准备游玩，请检查启动器安装")?;
        let lib = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../ui/ui-service/build/install/ui-service/lib/*");
        Ok(vec![
            java.to_string_lossy().into_owned(),
            "-Djava.awt.headless=true".into(),
            "-cp".into(),
            lib.to_string_lossy().into_owned(),
            "calebxzau.rdi.service.MainKt".into(),
        ])
    }
    pub async fn call(&self, method: &str, params: Value) -> Result<Value> {
        let service = self.service.clone();
        let method = method.to_owned();
        tokio::task::spawn_blocking(move || service.request(&method, params)).await?
    }
    pub fn take_events(&self) -> Vec<Value> {
        self.service.take_events().unwrap_or_else(|error| {
            tracing::warn!(%error, "读取游戏状态失败");
            vec![]
        })
    }
    pub fn take_unexpected_exit(&self) -> bool {
        self.service.take_unexpected_exit().unwrap_or_else(|error| {
            tracing::warn!(%error, "检查启动准备失败");
            false
        })
    }
    pub fn update_processes(&self, processes: Vec<Value>) {
        self.service.update_processes(processes);
    }
    pub fn stop_immediately(&self) {
        self.service.stop_immediately();
    }
    pub fn idle_due(&self, eligible: bool) -> bool {
        self.service.idle_due(eligible)
    }
    pub async fn stop_if_idle(&self) -> Result<bool> {
        let service = self.service.clone();
        tokio::task::spawn_blocking(move || service.stop_if_idle()).await?
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
