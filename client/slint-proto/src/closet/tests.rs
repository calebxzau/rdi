use super::*;
use base64::Engine;

fn current() -> Clothes {
    Clothes { skin: "https://a/skin".into(), cape: Some("https://a/cape".into()), slim: false }
}
fn texture(kind: TextureType) -> Texture {
    Texture { id: 7, name: "t".into(), kind, url: "https://littleskin.cn/textures/abc".into() }
}

#[test]
fn a_cape_keeps_the_skin_and_a_skin_keeps_the_cape() {
    let cape = wearing(&current(), &texture(TextureType::Cape));
    assert_eq!(cape, Clothes { cape: Some("https://littleskin.cn/textures/abc".into()), ..current() });
    let alex = wearing(&current(), &texture(TextureType::Alex));
    assert_eq!(alex, Clothes { skin: "https://littleskin.cn/textures/abc".into(), slim: true, cape: current().cape });
    let steve = wearing(&Clothes { slim: true, ..current() }, &texture(TextureType::Steve));
    assert!(!steve.slim);
}

#[test]
fn imports_follow_the_checkboxes() {
    let textures = MojangTextures { skin: Some(("https://m/skin".into(), true)), cape: Some("https://m/cape".into()) };
    let skin_only = imported(&current(), "Notch", &textures, true, false).unwrap();
    assert_eq!(skin_only, Clothes { skin: "https://m/skin".into(), slim: true, cape: current().cape });
    let cape_only = imported(&current(), "Notch", &textures, false, true).unwrap();
    assert_eq!(cape_only, Clothes { cape: Some("https://m/cape".into()), ..current() });
    let both = imported(&current(), "Notch", &textures, true, true).unwrap();
    assert_eq!(both, Clothes { skin: "https://m/skin".into(), slim: true, cape: Some("https://m/cape".into()) });

    let bare = MojangTextures { skin: Some(("https://m/skin".into(), false)), cape: None };
    assert_eq!(imported(&current(), "Notch", &bare, false, true).unwrap_err().to_string(), "玩家Notch没有披风");
    let none = MojangTextures::default();
    assert_eq!(imported(&current(), "Notch", &none, true, false).unwrap_err().to_string(), "玩家Notch没有设置过皮肤");
    // A cape alone needs no Mojang skin while the player has one.
    let cape_no_skin = MojangTextures { skin: None, cape: Some("https://m/cape".into()) };
    assert!(imported(&current(), "Notch", &cape_no_skin, false, true).is_ok());
    // A player without a skin takes the Mojang one, because the server needs a skin.
    let empty = Clothes::default();
    assert_eq!(imported(&empty, "Notch", &textures, false, true).unwrap().skin, "https://m/skin");

    assert_eq!(check_import("  ", true, false).unwrap_err().to_string(), "请输入玩家名");
    assert_eq!(check_import("Notch", false, false).unwrap_err().to_string(), "请选择皮肤或披风");
    assert_eq!(check_import(" Notch ", false, true).unwrap(), "Notch");
}

#[test]
fn profile_textures_decode_skin_model_and_cape() {
    let payload = r#"{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/s","metadata":{"model":"slim"}},"CAPE":{"url":"http://textures.minecraft.net/texture/c"}}}"#;
    let value = base64::engine::general_purpose::STANDARD.encode(payload);
    let profile = format!(r#"{{"id":"x","name":"n","properties":[{{"name":"textures","value":"{value}"}}]}}"#);
    let t = profile_textures(profile.as_bytes()).unwrap();
    assert_eq!(t.skin, Some(("http://textures.minecraft.net/texture/s".into(), true)));
    assert_eq!(t.cape.as_deref(), Some("http://textures.minecraft.net/texture/c"));
    let plain = base64::engine::general_purpose::STANDARD.encode(r#"{"textures":{"SKIN":{"url":"u"}}}"#);
    let t = profile_textures(format!(r#"{{"properties":[{{"name":"textures","value":"{plain}"}}]}}"#).as_bytes()).unwrap();
    assert_eq!((t.skin, t.cape), (Some(("u".into(), false)), None));
    assert_eq!(profile_textures(br#"{"properties":[]}"#).unwrap(), MojangTextures::default());
}

#[test]
fn clothes_round_trip_the_account_cloth() {
    let cloth: crate::api::model::Cloth = serde_json::from_str(r#"{"skin":" s ","cape":"","isSlim":true}"#).unwrap();
    let clothes = Clothes::from_cloth(Some(&cloth));
    assert_eq!(clothes, Clothes { skin: "s".into(), cape: None, slim: true });
    assert_eq!(Clothes::from_cloth(None), Clothes::default());
    let back = clothes.to_cloth();
    assert_eq!((back.skin.as_deref(), back.cape, back.is_slim), (Some("s"), None, Some(true)));
}

/// A one-shot local HTTP server answering every request with `body`.
async fn serve(status: u16, body: &'static str) -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut stream, _)) = listener.accept().await {
            let mut buffer = [0u8; 4096];
            let _ = stream.read(&mut buffer).await;
            let reply = format!("HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
            let _ = stream.write_all(reply.as_bytes()).await;
        }
    });
    format!("http://{address}")
}

#[tokio::test]
async fn resolve_reads_the_texture_and_builds_its_url() {
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    let base = serve(200, r#"{"tid":42,"name":"草地","type":"alex","hash":"abc123","uploader":1,"public":true,"likes":3}"#).await;
    let t = resolve(&http, &base, 42).await.unwrap();
    assert_eq!((t.id, t.name.as_str(), t.kind), (42, "草地", TextureType::Alex));
    assert_eq!(t.url, format!("{base}/textures/abc123"));
    let base = serve(200, r#"{"tid":1,"name":"x","type":"elytra","hash":"h","uploader":1,"public":true,"likes":0}"#).await;
    assert!(resolve(&http, &base, 1).await.unwrap_err().to_string().contains("未知的Blessing Skin材质类型"));
    assert!(resolve(&http, &base, 0).await.is_err());
}

#[tokio::test]
async fn unknown_mojang_players_are_reported_by_name() {
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    let names = serve(404, "").await;
    let error = mojang_textures(&http, Some(&format!("{names}/")), None, "nobody_here").await.unwrap_err();
    assert_eq!(error.to_string(), "玩家nobody_here不存在");
}
