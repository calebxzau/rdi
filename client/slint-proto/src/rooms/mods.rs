//! 模组 (U4 batch c2b): Compose `HostModsScreen`, `HostModsPane` and `HostModsViewModel`. Owner
//! decision D2: cards are selected by clicking, then 停用/启用/删除 act on the selection, instead
//! of Compose's drag between areas. No Slint types.
use super::requests::segment;
use crate::{
    api::server::ServerApi,
    pack_install::mods::{Mod, Side, same_mod},
};
use anyhow::{Context, Result, bail};
use serde_json::{Value, json};
use std::io::Read;

/// `Mod.uiModKey`.
pub fn key(m: &Mod) -> String {
    format!("{}:{}:{}:{}", m.platform, m.project_id, m.file_id, m.hash)
}

/// `Mod.Side.text`.
pub fn side_text(side: Side) -> &'static str {
    match side {
        Side::Client => "客户端",
        Side::Server => "服务端",
        Side::Both => "客+服通用",
        Side::Unknown => "未知",
    }
}

/// `sideToFlags`: (client, server).
pub fn side_flags(side: Side) -> (bool, bool) {
    match side {
        Side::Both => (true, true),
        Side::Client => (true, false),
        Side::Server => (false, true),
        Side::Unknown => (false, false),
    }
}
pub fn flags_side(client: bool, server: bool) -> Side {
    match (client, server) {
        (true, true) => Side::Both,
        (true, false) => Side::Client,
        (false, true) => Side::Server,
        (false, false) => Side::Unknown,
    }
}

/// `Mod.displaySlugOrProject`.
pub fn display_slug(m: &Mod) -> String {
    let slug = m.slug.trim();
    if slug.is_empty() { m.project_id.trim().to_owned() } else { slug.to_owned() }
}

/// The room's mods split as the page shows them.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Areas {
    /// The version's mods without the disabled ones (启用Mod).
    pub enabled: Vec<Mod>,
    pub disabled: Vec<Mod>,
    pub extra: Vec<Mod>,
    /// Every mod of the version, for the duplicate check when adding.
    pub version: Vec<Mod>,
}
impl Areas {
    pub fn new(version: Vec<Mod>, disabled: Vec<Mod>, extra: Vec<Mod>) -> Self {
        let enabled = version.iter().filter(|m| !disabled.iter().any(|d| same_mod(d, m))).cloned().collect();
        Self { enabled, disabled, extra, version }
    }
    pub fn with_disabled(&self, disabled: Vec<Mod>) -> Self {
        Self::new(self.version.clone(), disabled, self.extra.clone())
    }
    pub fn with_extra(&self, extra: Vec<Mod>) -> Self {
        Self { extra, ..self.clone() }
    }
}

/// The version's mods from `GET /modpack/{id}/detail` (`HostModsUiState.allBaseVersionMods`).
pub async fn version_mods(api: &ServerApi, pack: &str, version: &str) -> Result<Vec<Mod>> {
    let detail: Value = api.get(&format!("/modpack/{}/detail", segment(pack)?)).await?;
    let Some(entry) = detail["versions"].as_array().and_then(|v| v.iter().find(|v| v["name"].as_str() == Some(version))) else {
        return Ok(Vec::new());
    };
    serde_json::from_value(entry.get("mods").cloned().unwrap_or(json!([]))).map_err(|_| anyhow::anyhow!("服务器数据格式不兼容"))
}

/// `POST` (停用) or `DELETE` (启用) `/host/{id}/mods/disabled`; the room's disabled mods afterwards.
pub async fn change_disabled(api: &ServerApi, host: &str, mods: &[Mod], disabled: bool) -> Result<Vec<Mod>> {
    let path = format!("/host/{}/mods/disabled", segment(host)?);
    let body = serde_json::to_value(mods)?;
    if disabled { api.post(&path, &body).await } else { api.delete(&path, Some(&body)).await }
}
/// `DELETE /host/{id}/mods/extra` with the project ids; the room's extra mods afterwards.
pub async fn remove_extra(api: &ServerApi, host: &str, project_ids: &[String]) -> Result<Vec<Mod>> {
    let body = serde_json::to_value(project_ids)?;
    api.delete(&format!("/host/{}/mods/extra", segment(host)?), Some(&body)).await
}
/// `PUT /host/{id}/mods/extra/side` (`Host.ExtraModSideDto`).
pub async fn extra_side(api: &ServerApi, host: &str, project_id: &str, side: Side) -> Result<Vec<Mod>> {
    let body = json!({ "projectId": project_id, "side": side });
    api.put(&format!("/host/{}/mods/extra/side", segment(host)?), Some(&body)).await
}
/// `POST /host/{id}/mods/extra`; the server adds them in a background task and sends mail.
pub async fn add_extra(api: &ServerApi, host: &str, mods: &[Mod]) -> Result<()> {
    let _: Value = api.post(&format!("/host/{}/mods/extra", segment(host)?), &serde_json::to_value(mods)?).await?;
    Ok(())
}

/// `extraModIdentity`: the slug, or the project id when the slug is blank.
pub fn identity(m: &Mod) -> String {
    let slug = m.slug.trim().to_lowercase();
    if slug.is_empty() { m.project_id.trim().to_lowercase() } else { slug }
}
/// `filterExtraModsForAdding` for one mod.
pub fn check_new_extra(candidate: &Mod, existing: &[Mod]) -> Result<(), String> {
    let id = identity(candidate);
    if !id.is_empty() && existing.iter().any(|m| identity(m) == id) {
        return Err(format!("{}：主机或整合包中已存在同名Mod", display_slug(candidate)));
    }
    Ok(())
}

pub fn removal_message(mods: &[Mod]) -> String {
    let mut ids: Vec<&str> = mods.iter().map(|m| m.project_id.as_str()).collect();
    ids.dedup();
    match mods {
        [single] if ids.len() == 1 => format!("确定删除附加Mod《{}》吗？", display_slug(single)),
        _ => format!("确定删除已选择的{}个附加Mod吗？", ids.len()),
    }
}

/// The mod id a jar declares first (`readModMeta().primaryModId`): neoforge.mods.toml or
/// mods.toml, then fabric.mod.json, then mcmod.info. Lower case.
pub fn jar_mod_id(jar: &std::path::Path) -> Result<String> {
    let name = jar.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar)?).with_context(|| format!("无法打开{name}"))?;
    let mut read = |path: &str| -> Option<String> {
        let mut entry = zip.by_name(path).ok()?;
        let mut text = String::new();
        entry.by_ref().take(1024 * 1024).read_to_string(&mut text).ok()?;
        Some(text)
    };
    for path in ["META-INF/neoforge.mods.toml", "META-INF/mods.toml"] {
        if let Some(text) = read(path)
            && let Ok(value) = toml::from_str::<toml::Value>(&text)
            && let Some(id) = value
                .get("mods")
                .and_then(|m| m.as_array())
                .and_then(|mods| mods.iter().filter_map(|m| m.get("modId")?.as_str()).map(|s| s.trim().to_lowercase()).find(|s| !s.is_empty()))
        {
            return Ok(id);
        }
    }
    if let Some(text) = read("fabric.mod.json")
        && let Ok(value) = serde_json::from_str::<Value>(&text)
        && let Some(id) = value["id"].as_str().map(|s| s.trim().to_lowercase()).filter(|s| !s.is_empty())
    {
        return Ok(id);
    }
    if let Some(text) = read("mcmod.info")
        && let Ok(value) = serde_json::from_str::<Value>(&text)
    {
        let list = value.as_array().cloned().or_else(|| value["modList"].as_array().cloned()).unwrap_or_default();
        if let Some(id) = list.iter().filter_map(|m| m["modid"].as_str()).map(|s| s.trim().to_lowercase()).find(|s| !s.is_empty()) {
            return Ok(id);
        }
    }
    bail!("无法从{name}读取Mod ID")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::server::tests::read_request;
    use tokio::io::AsyncWriteExt;

    fn m(platform: &str, project: &str, slug: &str) -> Mod {
        let hash = format!("{platform}{project}{slug}");
        serde_json::from_value(json!({"platform":platform,"projectId":project,"slug":slug,"fileId":"f","hash":hash})).unwrap()
    }

    #[test]
    fn areas_take_disabled_mods_out_of_the_version() {
        let a = Areas::new(vec![m("mr", "A", "jei"), m("cf", "1", "jade"), m("mr", "C", "x")], vec![m("cf", "1", "")], vec![m("github", "o/r", "y")]);
        assert_eq!(a.enabled.iter().map(|m| m.project_id.as_str()).collect::<Vec<_>>(), ["A", "C"]);
        assert_eq!(a.with_disabled(vec![]).enabled.len(), 3);
        assert_eq!(a.with_extra(vec![]).extra.len(), 0);
        assert_eq!(a.with_extra(vec![]).enabled.len(), 2);
    }

    #[test]
    fn sides_and_texts_match_compose() {
        for side in [Side::Both, Side::Client, Side::Server, Side::Unknown] {
            let (c, s) = side_flags(side);
            assert_eq!(flags_side(c, s), side);
        }
        assert_eq!(side_text(Side::Both), "客+服通用");
        assert_eq!(key(&m("mr", "A", "jei")), "mr:A:f:mrAjei");
        assert_eq!(display_slug(&m("mr", " A ", " ")), "A");
        assert_eq!(removal_message(&[m("mr", "A", "jei")]), "确定删除附加Mod《jei》吗？");
        assert_eq!(removal_message(&[m("mr", "A", "jei"), m("mr", "B", "x")]), "确定删除已选择的2个附加Mod吗？");
    }

    #[test]
    fn adding_rejects_a_mod_the_room_or_pack_already_has() {
        let existing = [m("mr", "A", "JEI"), m("cf", "1", "")];
        assert_eq!(check_new_extra(&m("github", "o/r", " jei "), &existing).unwrap_err(), "jei：主机或整合包中已存在同名Mod");
        assert!(check_new_extra(&m("github", "o/r", "other"), &existing).is_ok());
        assert!(check_new_extra(&m("cf", "1", ""), &existing).is_err(), "falls back to the project id");
    }

    fn jar(files: &[(&str, &str)]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("rdi-mods-{}.jar", uuid::Uuid::now_v7()));
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        for (name, body) in files {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            std::io::Write::write_all(&mut zip, body.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
        path
    }

    #[test]
    fn jar_mod_ids_follow_read_mod_meta() {
        let cases = [
            (vec![("META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n[[mods]]\nmodId=\"MyMod\"\n[[mods]]\nmodId=\"second\"\n")], Ok("mymod")),
            (vec![("META-INF/mods.toml", "[[mods]]\nmodId=\" \"\n[[mods]]\nmodId=\"forgemod\"\n")], Ok("forgemod")),
            (vec![("fabric.mod.json", r#"{"schemaVersion":1,"id":"fab"}"#)], Ok("fab")),
            (vec![("mcmod.info", r#"[{"modid":"old"}]"#)], Ok("old")),
            (vec![("readme.txt", "x")], Err(())),
        ];
        for (files, expected) in cases {
            let path = jar(&files);
            let result = jar_mod_id(&path);
            let _ = std::fs::remove_file(&path);
            match expected {
                Ok(id) => assert_eq!(result.unwrap(), id),
                Err(()) => assert!(result.unwrap_err().to_string().starts_with("无法从rdi-mods-")),
            }
        }
    }

    async fn serve(body: &str) -> (ServerApi, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let response = format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
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
    const H: &str = "66a000000000000000000001";

    #[tokio::test]
    async fn requests_use_the_kotlin_methods_and_bodies() {
        let list = r#"{"code":0,"data":[{"platform":"cf","projectId":"1","slug":"jade","fileId":"f","hash":"h","side":"CLIENT"}]}"#;
        let (api, task) = serve(list).await;
        let out = change_disabled(&api, H, &[m("cf", "1", "jade")], true).await.unwrap();
        assert_eq!(out[0].side, Side::Client);
        let request = task.await.unwrap();
        assert!(request.starts_with(&format!("POST /host/{H}/mods/disabled ")));
        assert_eq!(body(&request)[0]["projectId"], "1");

        let (api, task) = serve(r#"{"code":0,"data":[]}"#).await;
        change_disabled(&api, H, &[m("cf", "1", "jade")], false).await.unwrap();
        assert!(task.await.unwrap().starts_with(&format!("DELETE /host/{H}/mods/disabled ")));

        let (api, task) = serve(r#"{"code":0,"data":[]}"#).await;
        remove_extra(&api, H, &["o/r".into()]).await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with(&format!("DELETE /host/{H}/mods/extra ")));
        assert_eq!(body(&request), json!(["o/r"]));

        let (api, task) = serve(r#"{"code":0,"data":[]}"#).await;
        extra_side(&api, H, "o/r", Side::Server).await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with(&format!("PUT /host/{H}/mods/extra/side ")));
        assert_eq!(body(&request), json!({"projectId":"o/r","side":"SERVER"}));

        let (api, task) = serve(r#"{"code":0}"#).await;
        add_extra(&api, H, &[m("github", "o/r", "y")]).await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with(&format!("POST /host/{H}/mods/extra ")));
        assert_eq!(body(&request)[0]["platform"], "github");

        let (api, task) = serve(r#"{"code":0,"data":{"versions":[{"name":"1.0","mods":[{"platform":"mr","projectId":"A","slug":"jei","fileId":"f","hash":"h"}]}]}}"#).await;
        assert_eq!(version_mods(&api, "66a000000000000000000002", "1.0").await.unwrap().len(), 1);
        assert!(task.await.unwrap().starts_with("GET /modpack/66a000000000000000000002/detail "));
    }
}
