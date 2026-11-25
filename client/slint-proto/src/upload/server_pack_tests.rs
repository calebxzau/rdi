//! Stage A2 part 2c: the server pack reader.
use super::reader::Progress;
use super::reader_tests::{jar, modrinth, sha1, with_ctx, zip_bytes};
use super::server_pack::{self, LoadedServer};
use crate::pack_install::mods::{Mod, Side};
use crate::test_dir::TestDir;
use serde_json::Value;
use std::path::{Path, PathBuf};

fn load(work: &Path, file: &Path, client: &[(Mod, PathBuf)], answer: impl Fn(&str, bool, &Value) -> Value + Send + Sync + 'static) -> (anyhow::Result<LoadedServer>, Vec<Progress>, Vec<String>) {
    let mods: Vec<Mod> = client.iter().map(|(m, _)| m.clone()).collect();
    with_ctx(work, answer, |ctx| server_pack::load(ctx, file, &mods, client, false))
}

fn forge_jar(mod_id: &str, text: &str) -> Vec<u8> {
    let toml = format!("modLoader=\"javafml\"\n[[mods]]\nmodId=\"{mod_id}\"\nversion=\"1.0\"\n");
    zip_bytes(&[("META-INF/mods.toml", toml.as_bytes()), ("marker.txt", text.as_bytes())])
}

#[test]
fn server_zip_gives_its_mods_and_the_files_worth_keeping() {
    let dir = TestDir::new("rdi-upload-server");
    let (known, unknown, shared) = (jar("known"), jar("unknown"), forge_jar("shared", "same bytes"));
    let pack = dir.join("server.zip");
    std::fs::write(
        &pack,
        zip_bytes(&[
            ("server/mods/known.jar", &known),
            ("server/mods/unknown.jar", &unknown),
            ("server/mods/shared-1.0.jar", &shared),
            ("server/mods/off.jar.disabled", b"off"),
            ("server/config/x.toml", b"x = 1"),
            ("server/config/datacache/y.toml", b"cache"),
            ("server/libraries/net/lib.jar", b"lib"),
            ("server/logs/latest.log", b"log"),
            ("server/world/level.dat", b"level"),
            ("server/world/region/r.0.0.mca", b"region"),
            ("server/server.jar", b"launcher"),
            ("server/run.sh", b"java"),
            ("server/eula", b"no extension"),
            ("server/icon.png", b"png"),
            ("server/data.db", b"db"),
            ("server/jdk/bin/java", b"java"),
            ("server/jdk/lib/modules", b"modules"),
            ("server/jdk/jmods/java.base.jmod", b"jmod"),
            ("readme.txt", b"outside the server folder"),
        ]),
    )
    .unwrap();
    // The client already has `shared` from CurseForge: same mod id, same bytes.
    let client_jar = dir.join("shared-client.jar");
    std::fs::write(&client_jar, &shared).unwrap();
    let client = Mod {
        platform: "cf".into(),
        project_id: "9".into(),
        slug: "shared-mod".into(),
        file_id: "900".into(),
        hash: crate::digests::murmur2(&shared).to_string(),
        side: Side::Client,
        download_urls: vec![],
        client_only_override: false,
        client_override_replaced: false,
    };
    let work = dir.join("work");
    let answer = modrinth(vec![(sha1(&known), ["vk", "pk", "known-mod", "unsupported", "required"])]);
    let (loaded, progress, paths) = load(&work, &pack, &[(client, client_jar)], answer);
    let loaded = loaded.unwrap();
    // Platform matches first, then the mod id matches, which count for both sides on Forge.
    assert_eq!(loaded.mods.iter().map(|m| (m.slug.as_str(), m.platform.as_str(), m.side)).collect::<Vec<_>>(), [("known-mod", "mr", Side::Server), ("shared-mod", "cf", Side::Both)]);
    assert_eq!(loaded.embedded_mods.iter().map(|e| e.original_name.as_str()).collect::<Vec<_>>(), ["known.jar", "shared-1.0.jar"]);
    assert_eq!(
        loaded.extras.iter().map(|e| e.relative.as_str()).collect::<Vec<_>>(),
        ["config/x.toml", "mods/unknown.jar", "run.sh", "world/level.dat", "world/region/r.0.0.mca"]
    );
    assert!(loaded.extras.iter().all(|e| e.source.is_file()));
    assert!(loaded.contains_mca);
    // Only the jar the mod id comparison left over is asked from the platforms.
    assert_eq!(paths, ["version_files", "projects", "fingerprints/432"]);
    assert!(progress.contains(&Progress::Warn("服务端目录中有1个mod未识别，将作为额外服务端文件带上：unknown".into())), "{progress:?}");
    assert!(progress.contains(&Progress::Phase("平台未识别的服务端mod，按modId与客户端已下载mod对比".into())));
}

#[test]
fn server_packs_that_cannot_be_used_are_refused() {
    let dir = TestDir::new("rdi-upload-server");
    let none = |path: &str, _: bool, _: &Value| -> Value { panic!("unexpected {path}") };
    let error_in = |work: &str, file: &Path| load(&dir.join(work), file, &[], none).0.unwrap_err().to_string();
    let error = |file: &Path| error_in("work", file);
    let write = |name: &str, files: &[(&str, &[u8])]| {
        let path = dir.join(name);
        std::fs::write(&path, zip_bytes(files)).unwrap();
        path
    };
    std::fs::write(dir.join("server.rar"), b"rar").unwrap();
    assert_eq!(error(&dir.join("server.rar")), "请选择服务端ZIP文件");
    assert_eq!(error(&write("nomods.zip", &[("config/a.toml", b"a")])), "服务端ZIP根目录或其下一层文件夹中应有mods文件夹");
    assert_eq!(error(&write("two.zip", &[("a/mods/x.jar", b"x"), ("b/mods/y.jar", b"y")])), "服务端ZIP中找到多个服务端目录，请只保留一个");
    assert_eq!(error(&write("bukkit.zip", &[("mods/x.jar", b"x"), ("bukkit.yml", b"")])), "请选择正确安装了模组载入器的服务端");
    assert_eq!(error(&write("empty.zip", &[("mods/readme.txt", b"x")])), "服务端包的mods文件夹中没有可用的Mod文件");
    // A zip that cannot be extracted leaves nothing in the work folder. (One refused after the
    // extraction stays until the upload's cleanup, as in Kotlin.)
    assert_eq!(error_in("clean", &write("nomods2.zip", &[("config/a.toml", b"a")])), "服务端ZIP根目录或其下一层文件夹中应有mods文件夹");
    assert_eq!(error_in("clean", &write("dup.zip", &[("mods/x.jar", b"x"), ("Mods/X.jar", b"y")])), "服务端ZIP包含重复文件路径: Mods/X.jar");
    assert!(error_in("clean", &write("escape.zip", &[("mods/x.jar", b"x"), ("../out.txt", b"y")])).starts_with("非法整合包文件路径"));
    assert!(std::fs::read_dir(dir.join("clean")).unwrap().next().is_none());

    // A folder is read in place.
    let folder = dir.join("folder");
    std::fs::create_dir_all(folder.join("mods")).unwrap();
    std::fs::create_dir_all(folder.join("libraries")).unwrap();
    assert_eq!(error(&folder), "服务端包的mods文件夹中没有可用的Mod文件");
    assert!(server_pack::rules_for_test(&folder, &folder.join("mycache"), true));
    assert!(!server_pack::rules_for_test(&folder, &folder.join("libraries"), true));
    assert!(server_pack::rules_for_test(&folder, &folder.join("libraries/net"), true));
    assert!(server_pack::rules_for_test(&folder, &folder.join("xaero/minimap"), true));
    assert!(!server_pack::rules_for_test(&folder, &folder, true));
    assert!(!server_pack::rules_for_test(&folder, &folder.join("mods/a.jar"), false));
    assert!(server_pack::rules_for_test(&folder, &folder.join("forge.jar"), false));
    assert!(server_pack::rules_for_test(&folder, &folder.join("start.EXE"), false));
    assert!(!server_pack::rules_for_test(&folder, &folder.join("server.properties"), false));
    assert!(!server_pack::rules_for_test(&folder, &folder.join("mods/noext"), false));
    assert!(server_pack::rules_for_test(&folder, &folder.join("config/Desktop.ini"), false));
}
