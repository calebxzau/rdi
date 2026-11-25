//! Stage A3: the upload archive built from packs in a temp folder, read back entry by entry.
use super::archive::{self, Input, Media};
use super::reader::{EmbeddedExtra, Progress};
use super::reader_tests::{sha1, with_ctx, zip_bytes};
use super::server_pack::ServerExtra;
use crate::pack_install::extras::{self, Content};
use crate::test_dir::TestDir;
use serde_json::Value;
use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};

/// Marks what it was given, so the tests see which files went through an encoder.
struct Marking;

impl Media for Marking {
    fn png(&self, bytes: Vec<u8>, _: &str) -> Vec<u8> {
        if bytes.len() > archive::PNG_THRESHOLD { format!("AVIF:{}", bytes.len()).into_bytes() } else { bytes }
    }
    fn ogg(&self, bytes: Vec<u8>, name: &str) -> anyhow::Result<Vec<u8>> {
        anyhow::ensure!(!bytes.starts_with(b"BROKEN"), "音频处理模块损坏，请更新客户端");
        Ok(if bytes.starts_with(b"LONG") { archive::EMPTY_OGG.to_vec() } else { format!("OPUS:{name}").into_bytes() })
    }
}

fn write(root: &Path, files: &[(&str, &[u8])]) {
    for (path, bytes) in files {
        let file: PathBuf = root.join(path);
        if path.ends_with('/') {
            std::fs::create_dir_all(&file).unwrap();
        } else {
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, bytes).unwrap();
        }
    }
}

/// The entries of a `.tar.zst`: path to content, folders with a trailing `/`.
fn read_archive(path: &Path) -> Vec<(String, Vec<u8>)> {
    let mut archive = tar::Archive::new(zstd::stream::read::Decoder::new(std::fs::File::open(path).unwrap()).unwrap());
    let mut out = Vec::new();
    for entry in archive.entries().unwrap() {
        let mut entry = entry.unwrap();
        let name = entry.path().unwrap().to_string_lossy().replace('\\', "/");
        let mut bytes = Vec::new();
        entry.read_to_end(&mut bytes).unwrap();
        out.push((name, bytes));
    }
    out
}

fn read_zip(bytes: &[u8]) -> Vec<(String, Vec<u8>)> {
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(bytes.to_vec())).unwrap();
    (0..zip.len())
        .map(|i| {
            let mut entry = zip.by_index(i).unwrap();
            let mut content = Vec::new();
            entry.read_to_end(&mut content).unwrap();
            (entry.name().to_owned(), content)
        })
        .collect()
}

fn names(entries: &[(String, Vec<u8>)]) -> Vec<&str> {
    entries.iter().map(|e| e.0.as_str()).collect()
}

fn build(dir: &TestDir, input: &Input) -> (anyhow::Result<PathBuf>, Vec<Progress>) {
    let none = |path: &str, _: bool, _: &Value| -> Value { panic!("unexpected {path}") };
    let (built, progress, _) = with_ctx(&dir.join("work"), none, |ctx| archive::build(ctx, &Marking, input));
    (built, progress)
}

fn shader(path: &str) -> Content {
    Content {
        platform: extras::Platform::Modrinth,
        kind: extras::Kind::ShaderPack,
        project_id: "p".into(),
        file_id: "f".into(),
        slug: "shader".into(),
        hash: "a".repeat(40),
        path: Some(path.into()),
        side: extras::Side::Client,
        required: true,
        download_urls: vec![],
    }
}

#[test]
fn archive_holds_the_pack_with_the_upload_rules_applied() {
    let dir = TestDir::new("rdi-upload-archive");
    let root = dir.join("pack");
    let big_png = vec![7u8; archive::PNG_THRESHOLD + 1];
    let mod_jar = zip_bytes(&[
        ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0"),
        ("assets/m/textures/big.png", &big_png),
        ("assets/m/textures/small.png", b"png"),
        ("assets/m/sounds/a.ogg", b"vorbis"),
        ("assets/m/cache/kept-in-a-jar.txt", b"jars keep cache folders"),
        ("assets/yes_steve_model/x.json", b"left out"),
    ]);
    let pack_zip = zip_bytes(&[("pack.mcmeta", b"{}"), ("assets/x/sounds/music.mp3", b"mp3 data"), ("assets/x/sounds/a.ogg", b"kept as it is"), ("assets/x/cache/a.txt", b"kept")]);
    let known_zip = zip_bytes(&[("pack.mcmeta", b"known")]);
    let data_zip = zip_bytes(&[("world/level.dat", b"level"), ("world/region/r.0.0.mca", b"region"), ("notes/cache/a.txt", b"left out"), ("notes/b.txt", b"kept")]);
    write(
        &root,
        &[
            ("modrinth.index.json", b"{}"),
            ("overrides/config/a.toml", b"a = 1"),
            ("overrides/config/mycache/b.toml", b"cache"),
            ("overrides/config/ftbquests/quests/lang/zh_cn.snbt", b"zh"),
            ("overrides/config/ftbquests/quests/lang/ja_jp.snbt", b"ja"),
            ("overrides/kubejs/probe/x.d.ts", b"probe"),
            ("overrides/kubejs/assets/big.png", &big_png),
            ("overrides/kubejs/assets/small.png", b"png"),
            ("overrides/kubejs/assets/a.ogg", b"vorbis"),
            ("overrides/kubejs/assets/long.ogg", b"LONG vorbis"),
            ("overrides/kubejs/assets/b.mp3", b"mp3"),
            ("overrides/kubejs/assets/intro.mp4", b"video"),
            ("overrides/mods/unknown.jar", &mod_jar),
            ("overrides/mods/off.jar.disabled", b"off"),
            ("overrides/data.zip", &data_zip),
            ("overrides/resourcepacks/pack.zip", &pack_zip),
            ("overrides/resourcepacks/known.zip", &known_zip),
            ("overrides/resourcepacks/unpacked/pack.mcmeta", b"{}"),
            ("overrides/resourcepacks/unpacked/a.mp3", b"mp3"),
            ("overrides/resourcepacks/unpacked/a.ogg", b"vorbis"),
            ("overrides/shaderpacks/local.zip", b"shader bytes"),
            ("overrides/shaderpacks/local.zip.txt", b"local settings"),
            ("overrides/shaderpacks/remote.zip.txt", b"remote settings"),
            ("overrides/saves/w/level.dat", b"level"),
            ("overrides/saves/w/region/r.0.0.mca", b"region"),
            ("overrides/empty/", b""),
        ],
    );
    let server_dir = dir.join("server");
    let server_zip = zip_bytes(&[("world/level.dat", b"level"), ("world/region/r.0.0.mca", b"region"), ("big.png", &big_png)]);
    write(&server_dir, &[("config/s.toml", b"s = 1"), ("world/level.dat", b"level"), ("world/region/r.0.0.mca", b"region"), ("backup.zip", &server_zip), ("config/x.disabled", b"off"), ("datacache/y.toml", b"cache")]);
    let server: Vec<ServerExtra> = ["config/s.toml", "world/level.dat", "world/region/r.0.0.mca", "backup.zip", "config/x.disabled", "datacache/y.toml"]
        .iter()
        .map(|r| ServerExtra { source: server_dir.join(r), relative: (*r).to_owned() })
        .collect();
    let known = Content { kind: extras::Kind::ResPack, path: Some("resourcepacks/known.zip".into()), ..shader("x") };
    let excluded = vec![EmbeddedExtra { content: known, staged: dir.join("gone.zip"), source_relative: "overrides/resourcepacks/known.zip".into(), sha1: sha1(&known_zip) }];
    let client_extras = vec![shader("shaderpacks/remote.zip")];
    let input = Input { root: &root, base_name: "测试包", server_extras: &server, excluded: &excluded, client_extras: &client_extras };
    let (built, progress) = build(&dir, &input);
    let built = built.unwrap();
    assert!(built.file_name().unwrap().to_string_lossy().starts_with("测试包_") && built.to_string_lossy().ends_with(".tar.zst"));
    let entries = read_archive(&built);
    let map: BTreeMap<&str, &Vec<u8>> = entries.iter().map(|e| (e.0.as_str(), &e.1)).collect();
    assert_eq!(
        names(&entries),
        [
            "modrinth.index.json",
            "overrides/",
            "overrides/config/",
            "overrides/config/a.toml",
            "overrides/config/ftbquests/",
            "overrides/config/ftbquests/quests/",
            "overrides/config/ftbquests/quests/lang/",
            "overrides/config/ftbquests/quests/lang/zh_cn.snbt",
            "overrides/data.zip",
            "overrides/empty/",
            "overrides/kubejs/",
            "overrides/kubejs/assets/",
            "overrides/kubejs/assets/a.ogg",
            "overrides/kubejs/assets/b.mp3",
            "overrides/kubejs/assets/big.png",
            "overrides/kubejs/assets/long.ogg",
            "overrides/kubejs/assets/small.png",
            // The prefix rule leaves out what is under `kubejs/probe/`; the folder itself stays.
            "overrides/kubejs/probe/",
            "overrides/mods/",
            "overrides/mods/unknown.jar",
            "overrides/resourcepacks/",
            "overrides/resourcepacks/pack.zip",
            "overrides/resourcepacks/unpacked/",
            "overrides/resourcepacks/unpacked/a.mp3",
            "overrides/resourcepacks/unpacked/a.ogg",
            "overrides/resourcepacks/unpacked/pack.mcmeta",
            "overrides/saves/",
            "overrides/saves/w/",
            "overrides/saves/w/level.dat",
            "overrides/saves/w/region/",
            "overrides/saves/w/region/r.0.0.mca",
            "overrides/shaderpacks/",
            "overrides/shaderpacks/remote.zip.txt",
            "server/",
            "server/config/",
            "server/config/s.toml",
            "server/world/",
            "server/world/level.dat",
            "server/world/region/",
            "server/world/region/r.0.0.mca",
            "server/backup.zip",
        ]
    );
    // Pictures over 50KB and sounds go through the encoders; a long sound and every MP3 are
    // replaced by the bundled empty files.
    assert_eq!(map["overrides/kubejs/assets/big.png"].as_slice(), format!("AVIF:{}", big_png.len()).as_bytes());
    assert_eq!(map["overrides/kubejs/assets/small.png"].as_slice(), b"png");
    assert_eq!(map["overrides/kubejs/assets/a.ogg"].as_slice(), b"OPUS:overrides/kubejs/assets/a.ogg");
    assert_eq!(map["overrides/kubejs/assets/long.ogg"].as_slice(), archive::EMPTY_OGG);
    assert!(map["overrides/kubejs/assets/b.mp3"].len() == 566 && map["overrides/resourcepacks/unpacked/a.mp3"].len() == 566);
    assert_eq!(map["overrides/resourcepacks/unpacked/a.ogg"].as_slice(), b"OPUS:overrides/resourcepacks/unpacked/a.ogg");
    // Region files of saves are kept as empty files.
    assert!(map["overrides/saves/w/region/r.0.0.mca"].is_empty() && map["server/world/region/r.0.0.mca"].is_empty());
    assert_eq!(map["overrides/saves/w/level.dat"].as_slice(), b"level");
    assert_eq!(map["overrides/shaderpacks/remote.zip.txt"].as_slice(), b"remote settings");

    // A jar is rewritten: assets encoded, the filter applied, its cache folders kept.
    let jar = read_zip(map["overrides/mods/unknown.jar"]);
    assert_eq!(
        names(&jar),
        ["META-INF/", "META-INF/MANIFEST.MF", "assets/", "assets/m/", "assets/m/textures/", "assets/m/textures/big.png", "assets/m/textures/small.png", "assets/m/sounds/", "assets/m/sounds/a.ogg", "assets/m/cache/", "assets/m/cache/kept-in-a-jar.txt"]
    );
    assert_eq!(jar[5].1, format!("AVIF:{}", big_png.len()).as_bytes());
    assert_eq!(jar[8].1, b"OPUS:assets/m/sounds/a.ogg");
    // A plain zip loses its cache folders and its saves' region data.
    let data = read_zip(map["overrides/data.zip"]);
    assert_eq!(names(&data), ["world/", "world/level.dat", "world/region/", "world/region/r.0.0.mca", "notes/", "notes/b.txt"]);
    assert!(data[3].1.is_empty());
    // A resource pack zip keeps its entries; only MP3 files are emptied.
    let pack = read_zip(map["overrides/resourcepacks/pack.zip"]);
    assert_eq!(names(&pack), ["pack.mcmeta", "assets/", "assets/x/", "assets/x/sounds/", "assets/x/sounds/music.mp3", "assets/x/sounds/a.ogg", "assets/x/cache/", "assets/x/cache/a.txt"]);
    assert_eq!((pack[4].1.len(), pack[5].1.as_slice()), (566, b"kept as it is".as_slice()));
    // A server zip only loses region data.
    let backup = read_zip(map["server/backup.zip"]);
    assert_eq!(names(&backup), ["world/", "world/level.dat", "world/region/", "world/region/r.0.0.mca", "big.png"]);
    assert!(backup[3].1.is_empty() && backup[4].1 == big_png);

    assert!(progress.contains(&Progress::Phase("正在预处理整合包资源文件".into())));
    assert_eq!(progress.last(), Some(&Progress::Percent("整合包打包完成".into(), 1.0)));
}

#[test]
fn archive_follows_layers_limits_and_failures() {
    let dir = TestDir::new("rdi-upload-archive");
    let root = dir.join("pack");
    let same = zip_bytes(&[("pack.mcmeta", b"same")]);
    write(
        &root,
        &[
            ("manifest.json", b"{}"),
            // The same pack in two layers: the lower one is shadowed.
            ("resourcepacks/both.zip", &same),
            ("overrides/resourcepacks/both.zip", &same),
            ("shaderpacks/s.zip.txt", b"lower settings"),
            ("overrides/shaderpacks/s.zip.txt", b"upper settings"),
            ("overrides/config/a.toml", b"a"),
        ],
    );
    let extras = vec![shader("shaderpacks/s.zip")];
    let input = Input { root: &root, base_name: " ", server_extras: &[], excluded: &[], client_extras: &extras };
    let (built, _) = build(&dir, &input);
    let built = built.unwrap();
    assert!(built.file_name().unwrap().to_string_lossy().starts_with("modpack_"));
    let entries = read_archive(&built);
    assert_eq!(
        names(&entries),
        ["manifest.json", "overrides/", "overrides/config/", "overrides/config/a.toml", "overrides/resourcepacks/", "overrides/resourcepacks/both.zip", "overrides/shaderpacks/", "overrides/shaderpacks/s.zip.txt", "resourcepacks/", "shaderpacks/"]
    );
    assert_eq!(entries[7].1, b"upper settings");

    // A recognised pack that changed since it was matched stops the build, and no file is left.
    let changed = vec![EmbeddedExtra { content: shader("x"), staged: dir.join("gone.zip"), source_relative: "overrides/resourcepacks/both.zip".into(), sha1: "b".repeat(40) }];
    let (failed, _) = build(&dir, &Input { excluded: &changed, ..Input { root: &root, base_name: "x", server_extras: &[], excluded: &[], client_extras: &[] } });
    assert_eq!(failed.unwrap_err().to_string(), "客户端资源文件在预处理前发生变化: overrides/resourcepacks/both.zip");
    // A broken encoder stops it too.
    write(&root, &[("overrides/sounds/x.ogg", b"BROKEN")]);
    let (failed, _) = build(&dir, &Input { root: &root, base_name: "x", server_extras: &[], excluded: &[], client_extras: &[] });
    assert_eq!(failed.unwrap_err().to_string(), "音频处理模块损坏，请更新客户端");
    let left: Vec<String> = std::fs::read_dir(dir.join("work")).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).filter(|n| n.starts_with("x_")).collect();
    assert!(left.is_empty(), "{left:?}");
    // A server file that vanished is an error.
    std::fs::remove_file(root.join("overrides/sounds/x.ogg")).unwrap();
    let missing = vec![ServerExtra { source: dir.join("nowhere.toml"), relative: "nowhere.toml".into() }];
    let (failed, _) = build(&dir, &Input { root: &root, base_name: "x", server_extras: &missing, excluded: &[], client_extras: &[] });
    assert!(failed.unwrap_err().to_string().starts_with("服务端额外文件不存在"));

    assert_eq!(archive::human_size_for_test(1023), "1023B");
    assert_eq!(archive::human_size_for_test(1536), "1.5KB");
    assert_eq!(archive::human_size_for_test(800 * 1024 * 1024), "800.0MB");
    assert_eq!(archive::human_size_for_test(3 * 1024 * 1024 * 1024), "3.0GB");
}
