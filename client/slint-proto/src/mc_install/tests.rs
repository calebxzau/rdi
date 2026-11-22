use super::loader::{self, InstallProfile};
use super::*;

fn deps(prefer_mirror: bool, root: &Path) -> Deps {
    Deps {
        downloader: Downloader { http: reqwest::Client::new() },
        server_base: Some("https://rdi.example:65331".into()),
        auth_headers: std::sync::Arc::new(|_| Default::default()),
        prefer_mirror,
        mc_dir: root.to_path_buf(),
        java: "java".into(),
        host: Host { os_name: "windows".into(), os_arch: "amd64".into(), os_version: "10.0".into(), path_separator: ";".into() },
    }
}

fn temp() -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rdi-j5a-install-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn source_plans_follow_build_download_source_plan() {
    let root = temp();
    let url = "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar";
    let sha = "AE58664F88E18A9BB2C77B063833CA7AAEC484CB";
    let rdi = "https://rdi.example:65331/game-lib/maven/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar?sha1=ae58664f88e18a9bb2c77b063833ca7aaec484cb";
    let mirror = "https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar";
    assert_eq!(source_plan(&deps(true, &root), url, None, sha), [mirror, url, rdi]);
    assert_eq!(source_plan(&deps(false, &root), url, None, sha), [url, mirror, rdi]);
    // Unknown hosts have no mirror and no RDI copy.
    let jar = "https://piston-data.mojang.com/v1/objects/abc/client.jar";
    assert_eq!(source_plan(&deps(true, &root), jar, None, sha), [jar]);
    assert_eq!(mirror_url("https://resources.download.minecraft.net/ab/abcd"), "https://bmclapi2.bangbang93.com/assets/ab/abcd");
    assert_eq!(rdi_source_url("https://s", "https://maven.minecraftforge.net/a/b/../c.jar", ""), None);
    assert_eq!(rdi_source_url("https://s", "https://maven.minecraftforge.net/a/b/c/d.txt", ""), None);
    assert_eq!(rdi_source_url("https://s/", "https://maven.minecraftforge.net/a/b/c/d.jar?x", "bad"), Some("https://s/game-lib/maven/a/b/c/d.jar".into()));
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn asset_plan_stubs_music_and_links_numbered_sounds() {
    let index = r#"{"objects":{
        "minecraft/sounds/music/game/calm1.ogg":{"hash":"aa00000000000000000000000000000000000001","size":5},
        "minecraft/sounds/mob/cat/meow1.ogg":{"hash":"bb00000000000000000000000000000000000001","size":5},
        "minecraft/sounds/mob/cat/meow2.ogg":{"hash":"bb00000000000000000000000000000000000002","size":5},
        "minecraft/sounds/mob/cat/meow3.ogg":{"hash":"bb00000000000000000000000000000000000001","size":5},
        "minecraft/lang/de_de.json":{"hash":"cc00000000000000000000000000000000000001","size":5},
        "minecraft/lang/zh_cn.json":{"hash":"cc00000000000000000000000000000000000002","size":5},
        "realms/lang/en_us.json":{"hash":"dd00000000000000000000000000000000000001","size":5},
        "realms/lang/de_de.json":{"hash":"dd00000000000000000000000000000000000002","size":5},
        "icons/icon_16x16.png":{"hash":"ee00000000000000000000000000000000000001","size":5}}}"#;
    let plan = assets::plan(index).unwrap();
    let paths: Vec<&str> = plan.download.iter().map(|(p, _)| p.as_str()).collect();
    assert_eq!(plan.stub.len(), 1);
    assert!(paths.contains(&"minecraft/sounds/mob/cat/meow1.ogg"));
    assert!(!paths.contains(&"minecraft/sounds/mob/cat/meow2.ogg"));
    assert!(paths.contains(&"minecraft/lang/zh_cn.json") && !paths.contains(&"minecraft/lang/de_de.json"));
    assert!(paths.contains(&"realms/lang/en_us.json") && !paths.contains(&"realms/lang/de_de.json"));
    assert!(paths.contains(&"icons/icon_16x16.png"));
    assert_eq!(plan.links, [("bb00000000000000000000000000000000000002".to_owned(), "bb00000000000000000000000000000000000001".to_owned())]);
    // The bundled indexes plan without error.
    for id in ["5", "17"] {
        let plan = assets::plan(meta::asset_index(id).unwrap()).unwrap();
        assert!(plan.download.len() > 100 && !plan.stub.is_empty() && !plan.links.is_empty(), "{id}");
    }
}

fn zip_with(path: &Path, entries: &[(&str, &[u8])]) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, body) in entries {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        std::io::Write::write_all(&mut zip, body).unwrap();
    }
    zip.finish().unwrap();
}

/// A loader installed the way Kotlin leaves it is recognized as ready, and a missing processor
/// output or library makes it not ready, so both launchers agree.
#[test]
fn loader_ready_check_matches_kotlin_layout() {
    let root = temp();
    let d = deps(true, &root);
    let version = meta::loader("1.21.1", "neoforge").unwrap();
    let lib_body = b"library";
    let lib_sha = {
        let p = root.join("probe");
        std::fs::write(&p, lib_body).unwrap();
        sha1_file(&p).unwrap()
    };
    let version_json = format!(
        r#"{{"id":"neoforge-21.1.265","inheritsFrom":"1.21.1","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
        "libraries":[{{"name":"a:b:1","downloads":{{"artifact":{{"path":"a/b/1/b-1.jar","url":"https://maven.neoforged.net/releases/a/b/1/b-1.jar","sha1":"{lib_sha}","size":7}}}}}}]}}"#
    );
    let profile = r#"{"libraries":[{"name":"net.neoforged:neoforge:21.1.265:universal","downloads":{"artifact":{"path":"net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar","url":"","sha1":""}}}],
        "data":{"MC_SRG":{"client":"[net.minecraft:client:1.21.1-20240808.144430:srg]"},"PATCHED":{"client":"[net.neoforged:neoforge:21.1.265:client]"}}}"#;
    zip_with(&loader::installer_path(&d, &version), &[("install_profile.json", profile.as_bytes()), ("version.json", version_json.as_bytes())]);
    let json = d.versions().join(version.dir_name).join(format!("{}.json", version.dir_name));
    std::fs::create_dir_all(json.parent().unwrap()).unwrap();
    std::fs::write(&json, &version_json).unwrap();
    let state = loader::read_launch_state(&d, &version).unwrap().unwrap();
    assert_eq!(state.manifest.jar.as_deref(), Some("1.21.1"));
    assert!(!loader::libraries_ready(&d, &state.manifest.libraries));
    std::fs::create_dir_all(d.libraries().join("a/b/1")).unwrap();
    std::fs::write(d.libraries().join("a/b/1/b-1.jar"), lib_body).unwrap();
    assert!(loader::libraries_ready(&d, &state.manifest.libraries));
    let p: InstallProfile = serde_json::from_str(profile).unwrap();
    assert!(!loader::profile_ready(&d, &p));
    for path in [
        "net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar",
        "net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar",
    ] {
        zip_with(&d.libraries().join(path), &[("x", b"x")]);
    }
    assert!(!loader::profile_ready(&d, &p), "PATCHED is still missing");
    zip_with(&d.libraries().join("net/neoforged/neoforge/21.1.265/neoforge-21.1.265-client.jar"), &[("x", b"x")]);
    assert!(loader::profile_ready(&d, &p));
    let (_, launch) = launch_manifests(&d, "1.21.1", "neoforge").unwrap();
    assert_eq!(launch.id, "neoforge-21.1.265");
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn cancelled_prepare_stops_before_any_work() {
    let root = temp();
    let d = deps(true, &root);
    let cancel = Cancellation::default();
    cancel.cancel();
    let error = prepare_for_launch(&d, "1.20.1", "forge", &cancel, &|_| {}).await.unwrap_err();
    assert!(error.to_string().contains("已取消"));
    assert!(!root.join("launcher_profiles.json").exists());
    assert!(prepare_for_launch(&d, "1.7.10", "forge", &Cancellation::default(), &|_| {}).await.is_err());
    std::fs::remove_dir_all(root).unwrap();
}
