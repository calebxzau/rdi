//! One function per room route (`HostRoute.kt`, `PlayerService.kt`, `BaseWorldRoute.kt`).
//! They take the plain server API, so the launcher MCP can call them without any UI.
use super::model::*;
use crate::api::server::ServerApi;
use anyhow::Result;
use serde_json::Value;

/// Ids go into paths; reject anything that could change the path.
fn segment(value: &str) -> Result<&str> {
    anyhow::ensure!(
        !value.is_empty() && value.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-'),
        "房间数据格式错误"
    );
    Ok(value)
}

pub async fn create(api: &ServerApi, request: &CreateRequest) -> Result<()> {
    api.post("/host/v2", &serde_json::to_value(request)?).await
}
pub async fn detail(api: &ServerApi, host: &str) -> Result<HostDetail> {
    api.get(&format!("/host/{}/detail", segment(host)?)).await
}
pub async fn save_options(api: &ServerApi, host: &str, request: &OptionsRequest) -> Result<()> {
    let body = serde_json::to_value(request)?;
    api.put(&format!("/host/{}/options", segment(host)?), Some(&body)).await
}
pub async fn update_pack(api: &ServerApi, host: &str) -> Result<()> {
    api.post_empty(&format!("/host/{}/update", segment(host)?)).await
}
pub async fn delete(api: &ServerApi, host: &str, delete_world: bool) -> Result<()> {
    let body = serde_json::to_value(DeleteRequest { delete_world })?;
    api.delete(&format!("/host/{}", segment(host)?), Some(&body)).await
}
pub async fn members(api: &ServerApi, host: &str) -> Result<Vec<Member>> {
    api.get(&format!("/host/{}/members", segment(host)?)).await
}
/// Invites by QQ number; the caller has trimmed it and checked it is not blank.
pub async fn invite(api: &ServerApi, host: &str, qq: &str) -> Result<()> {
    let path = format!("/host/{}/member/{}", segment(host)?, encode(qq));
    api.post_empty(&path).await
}
pub async fn kick(api: &ServerApi, host: &str, uid: &str) -> Result<()> {
    let path = format!("/host/{}/member/{}", segment(host)?, segment(uid)?);
    api.delete(&path, None).await
}
pub async fn set_role(api: &ServerApi, host: &str, uid: &str, role: Role) -> Result<()> {
    let path = format!("/host/{}/member/{}/role/{}", segment(host)?, segment(uid)?, role.wire());
    api.put(&path, None).await
}
pub async fn transfer(api: &ServerApi, host: &str, uid: &str) -> Result<()> {
    api.post_empty(&format!("/host/{}/transfer/{}", segment(host)?, segment(uid)?)).await
}
pub async fn quit(api: &ServerApi, host: &str) -> Result<()> {
    api.put(&format!("/host/{}/quit", segment(host)?), None).await
}
pub async fn friend_by_qq(api: &ServerApi, qq: &str) -> Result<Friend> {
    api.get(&format!("/player/by-qq/{}", encode(qq))).await
}
/// The chosen version's template binding from `GET /modpack/{id}/detail`.
pub async fn version_binding(api: &ServerApi, pack: &str, version: &str) -> Result<Option<BaseWorldBinding>> {
    let detail: Value = api.get(&format!("/modpack/{}/detail", segment(pack)?)).await?;
    let versions = detail["versions"].as_array().cloned().unwrap_or_default();
    let Some(entry) = versions.into_iter().find(|v| v["name"].as_str() == Some(version)) else {
        return Ok(None);
    };
    match entry.get("baseWorld").filter(|v| !v.is_null()) {
        Some(binding) => Ok(Some(
            serde_json::from_value(binding.clone()).map_err(|_| anyhow::anyhow!("服务器数据格式不兼容"))?,
        )),
        None => Ok(None),
    }
}
/// Ready map templates (`BaseWorldApi.listReady`).
pub async fn ready_base_worlds(api: &ServerApi) -> Result<Vec<BaseWorld>> {
    api.get_query("/baseworld", &[("myOnly", "false".into())]).await
}

/// Kotlin `encodeURLPathPart` for a QQ value typed by the player.
fn encode(value: &str) -> String {
    value
        .bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::server::tests::read_request;
    use tokio::io::AsyncWriteExt;

    /// Serves one canned envelope and returns the raw request.
    async fn serve(body: &str) -> (ServerApi, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let response = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let task = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let request = read_request(&mut socket).await;
            socket.write_all(response.as_bytes()).await.unwrap();
            request
        });
        (ServerApi::with_token(&base, "fake-token"), task)
    }
    fn body(request: &str) -> Value {
        serde_json::from_str(request.split("\r\n\r\n").nth(1).unwrap()).unwrap()
    }
    const OK: &str = r#"{"code":0}"#;
    const H: &str = "66a000000000000000000001";
    const U: &str = "66a000000000000000000002";

    #[tokio::test]
    async fn every_route_uses_the_kotlin_method_path_and_body() {
        let (api, task) = serve(OK).await;
        delete(&api, H, false).await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with(&format!("DELETE /host/{H} ")));
        assert_eq!(body(&request), serde_json::json!({}));

        let (api, task) = serve(OK).await;
        delete(&api, H, true).await.unwrap();
        assert_eq!(body(&task.await.unwrap()), serde_json::json!({"deleteWorld":true}));

        for (call, expected) in [
            (0, format!("POST /host/{H}/update ")),
            (1, format!("POST /host/{H}/member/12345 ")),
            (2, format!("DELETE /host/{H}/member/{U} ")),
            (3, format!("PUT /host/{H}/member/{U}/role/ADMIN ")),
            (4, format!("PUT /host/{H}/member/{U}/role/MEMBER ")),
            (5, format!("POST /host/{H}/transfer/{U} ")),
            (6, format!("PUT /host/{H}/quit ")),
        ] {
            let (api, task) = serve(OK).await;
            match call {
                0 => update_pack(&api, H).await,
                1 => invite(&api, H, "12345").await,
                2 => kick(&api, H, U).await,
                3 => set_role(&api, H, U, Role::Admin).await,
                4 => set_role(&api, H, U, Role::Member).await,
                5 => transfer(&api, H, U).await,
                _ => quit(&api, H).await,
            }
            .unwrap();
            let request = task.await.unwrap();
            assert!(request.starts_with(&expected), "{request}");
            assert!(request.to_lowercase().contains("authorization: bearer fake-token"));
        }
    }

    #[tokio::test]
    async fn lookups_parse_server_shapes() {
        let (api, task) = serve(r#"{"code":0,"data":[{"id":"66a000000000000000000002","role":"OWNER"},{"id":"66a000000000000000000003","role":"MEMBER"}]}"#).await;
        let list = members(&api, H).await.unwrap();
        assert_eq!(list[0].role, Role::Owner);
        assert!(task.await.unwrap().starts_with(&format!("GET /host/{H}/members ")));

        let (api, task) = serve(r#"{"code":0,"data":{"id":"66a000000000000000000002","name":"好友","cloth":{}}}"#).await;
        assert_eq!(friend_by_qq(&api, "12 3").await.unwrap().name, "好友");
        assert!(task.await.unwrap().starts_with("GET /player/by-qq/12%203 "));

        let (api, task) = serve(r#"{"code":0,"data":{"versions":[{"name":"1.0"},{"name":"2.0","baseWorld":{"id":"0193d3a4-1111-7000-8000-000000000001","required":true}}]}}"#).await;
        let binding = version_binding(&api, "66a000000000000000000003", "2.0").await.unwrap().unwrap();
        assert!(binding.required);
        task.await.unwrap();
        let (api, task) = serve(r#"{"code":0,"data":{"versions":[{"name":"1.0"}]}}"#).await;
        assert!(version_binding(&api, "66a000000000000000000003", "1.0").await.unwrap().is_none());
        task.await.unwrap();

        let (api, task) = serve(r#"{"code":0,"data":[]}"#).await;
        assert!(ready_base_worlds(&api).await.unwrap().is_empty());
        assert!(task.await.unwrap().starts_with("GET /baseworld?myOnly=false "));

        let (api, task) = serve(r#"{"code":-1,"msg":"无此房间"}"#).await;
        assert_eq!(detail(&api, H).await.unwrap_err().to_string(), "无此房间");
        task.await.unwrap();
    }

    #[tokio::test]
    async fn ids_cannot_change_the_path() {
        let api = ServerApi::with_token("http://127.0.0.1:9", "fake-token");
        for bad in ["", "../x", "a/b", "a?b"] {
            assert_eq!(detail(&api, bad).await.unwrap_err().to_string(), "房间数据格式错误");
        }
    }
}
