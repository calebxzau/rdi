//! Stage A6b: the test folders and line rules, after `ModpackTestSession.kt`.
use super::testrun::*;
use crate::pack_install::mods::{Mod, Side};
use crate::test_dir::TestDir;
use std::path::Path;

fn sha1_of(text: &str) -> String {
    use sha1::Digest as _;
    sha1::Sha1::digest(text.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

/// A Modrinth mod whose jar holds `slug` as text.
fn mr(slug: &str, side: Side) -> Mod {
    Mod {
        platform: "mr".into(),
        project_id: format!("p-{slug}"),
        slug: slug.into(),
        file_id: format!("f-{slug}"),
        hash: sha1_of(slug),
        side,
        download_urls: vec![],
        client_only_override: false,
        client_override_replaced: false,
    }
}

fn write(path: &Path, text: &str) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, text).unwrap();
}

fn read(path: &Path) -> String {
    std::fs::read_to_string(path).unwrap()
}

fn names(dir: &Path) -> Vec<String> {
    let mut out: Vec<String> = std::fs::read_dir(dir).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
    out.sort();
    out
}

#[test]
fn lines_pass_or_fail_as_kotlin() {
    assert_eq!(client_line("[Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [CHAT] <Steve> 开始运行客户端测试"), Line::Passed(None));
    assert_eq!(client_line("org.spongepowered.asm.mixin.transformer.throwables.mixintransformererror: x"), Line::Crashed);
    assert_eq!(client_line("Mod Loading has failed"), Line::Crashed);
    assert_eq!(client_line("Done (3.2s)! For help"), Line::Other);
    assert_eq!(server_line("[Server thread/INFO] [minecraft/DedicatedServer]: Done (12.345s)! For help, type \"help\""), Line::Passed(Some("12.345".into())));
    assert_eq!(server_line("Done (7s)! For help"), Line::Passed(Some("7".into())));
    assert_eq!(server_line("Error: could not open `libraries/net/minecraftforge/forge/1.20.1-47.4.29/win_args.txt'"), Line::MissingServerFiles);
    assert_eq!(server_line("---- Minecraft Crash Report ----"), Line::Crashed);
    assert_eq!(server_line("missing or unsupported MANDATORY dependencies:"), Line::Crashed);
    assert_eq!(server_line("开始运行客户端测试"), Line::Other);
    assert_eq!(Target::parse(" Server ").unwrap(), Target::Server);
    assert!(Target::parse("both").is_err());
}

#[test]
fn state_keeps_forty_lines_and_a_pass_expires_with_the_mods() {
    let mut state = State::default();
    for n in 0..45 {
        state.push_line(&format!("line {n}"));
    }
    let lines = state.lines();
    assert_eq!(lines.len(), KEPT_LINES);
    assert_eq!(lines[0], "line 5");
    state.push_line(&"x".repeat(3000));
    assert_eq!(state.lines().last().unwrap().chars().count(), 2001);

    let mut mods = vec![mr("a", Side::Both), mr("b", Side::Client)];
    state.status = Status::Passed;
    state.pass_seconds = Some("3.0".into());
    state.signature = Some(signature(&run_mods(&mods, false)));
    assert!(state.passed_for(&mods, false));
    mods.reverse();
    assert!(state.passed_for(&mods, false), "order does not matter");
    mods[0].side = Side::Server;
    assert!(!state.passed_for(&mods, false), "a side change does");
    state.mods_changed();
    assert_eq!((state.status, state.pass_seconds.clone(), state.signature.clone()), (Status::NotRun, None, None));

    state.status = Status::Failed;
    state.mods_changed();
    assert_eq!(state.status, Status::Failed, "only a pass is reset");
}

#[test]
fn hard_links_are_for_media_and_packs_only() {
    for yes in ["sounds/a.OGG", "resourcepacks/x.zip", "resourcepacks/x/pack.mcmeta", "shaderpacks/s.zip", "/textures/a.png", "a\\b.png"] {
        assert!(should_hard_link(yes), "{yes}");
    }
    for no in ["shaderpacks/s.zip.txt", "config/a.toml", "options.txt", "kubejs/a.js"] {
        assert!(!should_hard_link(no), "{no}");
    }
}

/// A pack as the reader leaves it: overrides, client overrides, a jar it did not recognise, a
/// client-only marked jar, and the manifest.
fn pack(root: &Path) -> std::path::PathBuf {
    let source = root.join("pack");
    write(&source.join("manifest.json"), "{}");
    write(&source.join("README.txt"), "readme");
    write(&source.join("server/start.sh"), "java");
    write(&source.join("overrides/config/a.toml"), "a=1");
    write(&source.join("overrides/config/mods/inner.toml"), "skipped with mods folders");
    write(&source.join("overrides/mods/unknown.jar"), "unknown");
    write(&source.join("overrides/mods/C$$_marked.jar"), "marked");
    write(&source.join("overrides/mods/folder/data.txt"), "mod folder");
    write(&source.join("overrides/resourcepacks/rp.zip"), "rp");
    write(&source.join("client-overrides/config/client.toml"), "c=1");
    write(&source.join("client-overrides/mods/client-only.jar"), "override");
    write(&source.join("C$$_root.jar"), "root marked");
    source
}

/// The run's mods linked into `MOD_SOURCES`, as the content store does.
fn sources(dir: &Path, mods: &[Mod]) {
    for m in mods {
        write(&dir.join(MOD_SOURCES).join(m.file_name()), &m.slug);
    }
}

#[test]
fn a_server_folder_has_server_mods_and_the_shared_libraries() {
    let root = TestDir::new("rdi-a6b-server");
    let source = pack(&root);
    let libraries = root.join("mc/libraries");
    write(&libraries.join("net/x/x.jar"), "lib");
    let mods = vec![mr("both", Side::Both), mr("server", Side::Server), mr("client", Side::Client), mr("unknown-side", Side::Unknown)];

    let dir = create_server_dir(&source, &root.join("work"), &libraries).unwrap();
    assert!(dir.file_name().unwrap().to_string_lossy().starts_with("servertest-"));
    assert_eq!(read(&dir.join("libraries/net/x/x.jar")), "lib");
    assert_eq!(read(&dir.join("config/a.toml")), "a=1");
    assert_eq!(read(&dir.join("README.txt")), "readme");
    for gone in ["manifest.json", "server", "overrides", "client-overrides", "config/client.toml", "config/mods", "C$$_root.jar", "mods"] {
        assert!(!dir.join(gone).exists(), "{gone}");
    }

    sources(&dir, &mods);
    write(&dir.join("logs/latest.log"), "old");
    write(&dir.join("world/level.dat"), "old");
    write(&dir.join("mods/stale.jar"), "old");
    prepare_server_content(&dir, &source, &mods).unwrap();
    assert_eq!(names(&dir.join("mods")), [mods[0].file_name(), mods[1].file_name(), "unknown.jar".into()]);
    assert!(!dir.join("logs").exists() && !dir.join("world").exists());

    // Configs are copies: the game rewriting them leaves the pack alone.
    std::fs::write(dir.join("config/a.toml"), "a=2").unwrap();
    assert_eq!(read(&source.join("overrides/config/a.toml")), "a=1");
}

#[test]
fn a_client_folder_has_client_mods_with_the_overrides_winning() {
    let root = TestDir::new("rdi-a6b-client");
    let source = pack(&root);
    write(&source.join("shaderpacks/s.zip"), "shader");
    let mut override_mod = mr("client-only", Side::Client);
    override_mod.hash = sha1_of("override");
    let mods = vec![mr("both", Side::Both), mr("server", Side::Server), mr("client", Side::Client), override_mod.clone()];

    let dir = create_client_dir(&source, &root.join("work"), "V201").unwrap();
    let name = dir.file_name().unwrap().to_string_lossy().into_owned();
    assert!(name.starts_with("_rdi_client_test_"), "{name}");
    assert_eq!(read(&dir.join("config/client.toml")), "c=1");
    assert_eq!(read(&dir.join("shaderpacks/s.zip")), "shader");
    assert_eq!(read(&dir.join("resourcepacks/rp.zip")), "rp");
    assert!(read(&dir.join("options.txt")).contains("lang:zh_cn"));
    assert!(!dir.join("manifest.json").exists() && !dir.join("server").exists());

    sources(&dir, &mods);
    // The downloaded copy of the override mod differs; the pack's own jar must win.
    write(&dir.join(MOD_SOURCES).join(override_mod.file_name()), "downloaded");
    write(&dir.join("saves/w/level.dat"), "old");
    prepare_client_content(&dir, &source, &mods, "V201").unwrap();
    let mut expected = vec![mods[0].file_name(), mods[2].file_name(), override_mod.file_name(), "folder".into(), "unknown.jar".into()];
    expected.sort();
    assert_eq!(names(&dir.join("mods")), expected);
    assert_eq!(read(&dir.join("mods").join(override_mod.file_name())), "override");
    assert_eq!(read(&dir.join("mods/folder/data.txt")), "mod folder");
    assert!(!dir.join("saves").exists());

    // A second run starts from a fresh mods folder.
    let fewer = vec![mods[0].clone()];
    prepare_client_content(&dir, &source, &fewer, "V201").unwrap();
    assert!(!dir.join("mods").join(mods[2].file_name()).exists());
}

#[test]
fn two_different_jars_for_one_name_are_refused() {
    let root = TestDir::new("rdi-a6b-conflict");
    let source = root.join("pack");
    let m = mr("dup", Side::Both);
    write(&source.join("mods").join(m.file_name()), "pack copy");
    let dir = create_server_dir(&source, &root.join("work"), &root.join("no-libraries")).unwrap();
    sources(&dir, std::slice::from_ref(&m));
    let error = prepare_server_content(&dir, &source, std::slice::from_ref(&m)).unwrap_err().to_string();
    assert!(error.starts_with(&format!("Mod文件目标冲突: 目标文件{}已存在", m.file_name())), "{error}");
}
