//! Stage A6a: the test server install, against the Kotlin sources it ports.
use super::loader::InstallProfile;
use super::server::*;
use super::*;
use crate::test_dir::TestDir;

fn deps(root: &Path, os: &str) -> Deps {
    Deps {
        downloader: Downloader { http: reqwest::Client::new() },
        server_base: None,
        auth_headers: std::sync::Arc::new(|_| Default::default()),
        prefer_mirror: false,
        mc_dir: root.join("mc"),
        java: "java".into(),
        host: Host { os_name: os.into(), os_arch: "amd64".into(), os_version: "10.0".into(), path_separator: ";".into() },
    }
}

fn kotlin(relative: &str) -> String {
    std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("../..").join(relative)).unwrap()
}

#[test]
fn runtimes_follow_resolve_server_runtime() {
    let forge = ServerRuntime::resolve("1.20.1", "forge").unwrap();
    assert_eq!(forge.loader_version(), "1.20.1-47.4.29");
    assert_eq!(forge.args_file(false).unwrap(), "net/minecraftforge/forge/1.20.1-47.4.29/win_args.txt");
    let neoforge = ServerRuntime::resolve("1.21.1", "neoforge").unwrap();
    assert_eq!(neoforge.loader_version(), "21.1.265");
    assert_eq!(neoforge.args_file(true).unwrap(), "net/neoforged/neoforge/21.1.265/unix_args.txt");
    assert_eq!(ServerRuntime::resolve("1.20.1", "Fabric"), Some(ServerRuntime::Fabric));
    assert_eq!(ServerRuntime::Fabric.args_file(false), None);
    assert_eq!(ServerRuntime::Fabric.loader_version(), "0.19.5");
    assert_eq!(ServerRuntime::resolve("1.21.1", "Fabric"), None);
    assert_eq!(ServerRuntime::resolve("1.7.10", "forge"), None);

    let install = kotlin("client/mc-install/src/main/kotlin/calebxzau/rdi/mcinstall/McInstall.kt");
    assert!(install.contains(&format!("FABRIC_INSTALLER_VERSION = \"{FABRIC_INSTALLER_VERSION}\"")));
    assert!(install.contains(&format!("FABRIC_INSTALLER_SHA1 = \"{FABRIC_INSTALLER_SHA1}\"")));
    assert!(install.contains("\"https://maven.fabricmc.net/net/fabricmc/fabric-installer/${FABRIC_INSTALLER_VERSION}/fabric-installer-${FABRIC_INSTALLER_VERSION}.jar\""));
    assert_eq!(FABRIC_INSTALLER_URL.replace(FABRIC_INSTALLER_VERSION, "V"), "https://maven.fabricmc.net/net/fabricmc/fabric-installer/V/fabric-installer-V.jar");
    let runtime = kotlin("common/model/src/main/kotlin/calebxzhou/rdi/common/model/ServerLoaderRuntime.kt");
    assert!(runtime.contains(&format!("FABRIC_SERVER_LAUNCHER_JAR = \"{FABRIC_SERVER_LAUNCHER_JAR}\"")));
    assert!(runtime.contains(&format!("FABRIC_SERVER_JAR = \"{FABRIC_SERVER_JAR}\"")));
}

#[test]
fn task_title_and_steps_are_kotlins() {
    let forge = ServerRuntime::resolve("1.20.1", "forge").unwrap();
    assert_eq!(forge.task_title(), "下载测试服务端 1.20.1 forge");
    assert_eq!(forge.steps(), ["下载forge 安装器", "解析安装器", "下载forge服务端", "下载服务端Mojmap", "运行安装器服务端"]);
    assert_eq!(ServerRuntime::Fabric.task_title(), "下载测试服务端 1.20.1 Fabric");
    assert_eq!(ServerRuntime::Fabric.steps(), ["下载Fabric安装器", "安装Fabric测试服务端"]);
}

#[test]
fn bundled_metadata_has_the_server_downloads() {
    for mc in ["1.20.1", "1.21.1"] {
        let downloads = meta::vanilla(mc).unwrap().downloads.unwrap();
        assert!(downloads.server.unwrap().url.ends_with("/server.jar"), "{mc}");
        assert!(downloads.server_mappings.unwrap().url.ends_with("/server.txt"), "{mc}");
    }
}

#[test]
fn the_server_jar_goes_where_the_install_profile_says() {
    let root = TestDir::new("rdi-a6a-jar");
    let d = deps(&root, "windows");
    let profile: InstallProfile =
        serde_json::from_str(r#"{"serverJarPath":"{LIBRARY_DIR}/net/minecraft/server/{MINECRAFT_VERSION}/server-{MINECRAFT_VERSION}.jar"}"#).unwrap();
    let target = server_jar_target(&d, &profile, "1.20.1");
    assert_eq!(target, PathBuf::from(format!("{}/net/minecraft/server/1.20.1/server-1.20.1.jar", d.libraries().to_string_lossy())));
    assert_eq!(server_jar_target(&d, &InstallProfile::default(), "1.21.1"), d.mc_dir.join("minecraft_server.1.21.1.jar"));
}

#[test]
fn a_loader_server_is_installed_once_its_args_file_exists() {
    let root = TestDir::new("rdi-a6a-args");
    let forge = ServerRuntime::resolve("1.20.1", "forge").unwrap();
    for (os, name) in [("windows", "win_args.txt"), ("linux", "unix_args.txt")] {
        let d = deps(&root, os);
        assert!(!installed(&d, &forge), "{os}");
        let file = d.libraries().join("net/minecraftforge/forge/1.20.1-47.4.29").join(name);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, "-Dx").unwrap();
        assert!(installed(&d, &forge), "{os}");
    }
}

#[test]
fn managed_paths_follow_is_managed_fabric_runtime_path() {
    for ok in ["fabric-server-launch.jar", "server.jar", "fabric-server-launcher.properties", "libraries/a/b.jar", "libraries/a/B.JAR"] {
        assert!(managed_path(ok), "{ok}");
    }
    for bad in ["", "eula.txt", "libraries/a/b.txt", "libraries/../x.jar", "libraries//x.jar", "./server.jar", "/server.jar", "libraries\\a.jar", "C:/server.jar", "mods/a.jar"] {
        assert!(!managed_path(bad), "{bad}");
    }
}

/// What the Fabric installer leaves in a staging folder, plus a file outside the list.
fn fake_fabric_install(dir: &Path) {
    let files = [
        ("fabric-server-launch.jar", "launch"),
        ("server.jar", "vanilla"),
        ("fabric-server-launcher.properties", "serverJar=server.jar"),
        ("libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar", "loader"),
        ("libraries/org/ow2/asm/asm/9.9/asm-9.9.jar", "asm"),
        ("eula.txt", "eula=false"),
    ];
    for (relative, text) in files {
        let file = dir.join(relative);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, text).unwrap();
    }
}

fn cache_dir(d: &Deps) -> PathBuf {
    d.mc_dir.join("server-runtimes/1.20.1/fabric-0.19.5")
}

#[test]
fn a_fabric_runtime_counts_only_when_complete() {
    let root = TestDir::new("rdi-a6a-fabric");
    let d = deps(&root, "windows");
    assert!(!installed(&d, &ServerRuntime::Fabric));

    let staging = cache_dir(&d).join("runtime-a");
    fake_fabric_install(&staging);
    std::fs::remove_file(staging.join("libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar")).unwrap();
    assert_eq!(super::server::finish_for_test(&staging).unwrap_err().to_string(), "Fabric Loader运行库缺失");
    assert!(find_fabric_runtime(&d).is_none(), "no ready file");

    fake_fabric_install(&staging);
    super::server::finish_for_test(&staging).unwrap();
    let list = std::fs::read_to_string(staging.join(".rdi-runtime-files")).unwrap();
    let names: Vec<&str> = list.lines().map(|l| l.split('\t').next().unwrap()).collect();
    assert_eq!(
        names,
        [
            "fabric-server-launch.jar",
            "fabric-server-launcher.properties",
            "libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
            "libraries/org/ow2/asm/asm/9.9/asm-9.9.jar",
            "server.jar"
        ]
    );
    assert!(!list.ends_with('\n'));
    let ready = std::fs::read_to_string(staging.join(".rdi-runtime-ready")).unwrap();
    assert!(ready.starts_with("1.20.1\nFabric\n0.19.5\n"));
    assert_eq!(ready.lines().count(), 6);
    assert_eq!(find_fabric_runtime(&d), Some(staging.clone()));
    assert!(installed(&d, &ServerRuntime::Fabric));

    // A changed library breaks the runtime.
    std::fs::write(staging.join("libraries/org/ow2/asm/asm/9.9/asm-9.9.jar"), "changed").unwrap();
    assert!(find_fabric_runtime(&d).is_none());
}

#[test]
fn staging_copies_the_listed_files_and_repairs_changed_ones() {
    let root = TestDir::new("rdi-a6a-stage");
    let d = deps(&root, "windows");
    let work = root.join("work");
    assert_eq!(
        stage_fabric_runtime(&d, &work).unwrap_err().to_string(),
        "Fabric测试服务端未安装或安装不完整，请先下载测试服务端"
    );
    let staging = cache_dir(&d).join("runtime-b");
    fake_fabric_install(&staging);
    super::server::finish_for_test(&staging).unwrap();

    stage_fabric_runtime(&d, &work).unwrap();
    assert_eq!(std::fs::read_to_string(work.join("server.jar")).unwrap(), "vanilla");
    assert_eq!(std::fs::read_to_string(work.join("libraries/org/ow2/asm/asm/9.9/asm-9.9.jar")).unwrap(), "asm");
    assert!(!work.join("eula.txt").exists(), "files outside the list stay out");
    assert!(!work.join(".rdi-runtime-files").exists());

    std::fs::write(work.join("server.jar"), "modded").unwrap();
    std::fs::write(work.join("eula.txt"), "eula=true").unwrap();
    stage_fabric_runtime(&d, &work).unwrap();
    assert_eq!(std::fs::read_to_string(work.join("server.jar")).unwrap(), "vanilla");
    assert_eq!(std::fs::read_to_string(work.join("eula.txt")).unwrap(), "eula=true");
    let leftovers: Vec<_> = std::fs::read_dir(&work).unwrap().filter_map(|e| e.ok()).filter(|e| e.file_name().to_string_lossy().ends_with(".tmp")).collect();
    assert!(leftovers.is_empty());

    std::fs::remove_file(work.join("fabric-server-launch.jar")).unwrap();
    std::fs::create_dir(work.join("fabric-server-launch.jar")).unwrap();
    assert_eq!(stage_fabric_runtime(&d, &work).unwrap_err().to_string(), "Fabric服务端运行文件目标不是普通文件: fabric-server-launch.jar");
}

/// Live: downloads and runs the real installers. `RDI_A6_MC_DIR` is a scratch `mc` folder,
/// `RDI_A6_JAVA` a Java 25, `RDI_A6_SERVERS` a list such as `1.20.1:forge,1.20.1:Fabric`.
#[test]
#[ignore]
fn real_test_server_install() {
    let mut d = deps(Path::new("unused"), "windows");
    d.mc_dir = PathBuf::from(std::env::var("RDI_A6_MC_DIR").unwrap());
    d.java = PathBuf::from(std::env::var("RDI_A6_JAVA").unwrap());
    d.host = Host::current();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    for spec in std::env::var("RDI_A6_SERVERS").unwrap().split(',') {
        let (mc, loader) = spec.split_once(':').unwrap();
        let server = ServerRuntime::resolve(mc, loader).unwrap();
        let steps = server.steps();
        let progress = |line: String| eprintln!("[{spec}] {line}");
        let step = |i: usize| eprintln!("[{spec}] == {}", steps[i]);
        let started = std::time::Instant::now();
        runtime.block_on(install(&d, &server, &Cancellation::default(), &step, &progress)).unwrap();
        eprintln!("[{spec}] installed in {:?}", started.elapsed());
        assert!(installed(&d, &server), "{spec}");
        let started = std::time::Instant::now();
        runtime.block_on(install(&d, &server, &Cancellation::default(), &step, &progress)).unwrap();
        eprintln!("[{spec}] second install in {:?}", started.elapsed());
        if server == ServerRuntime::Fabric {
            let work = d.mc_dir.join("a6a-stage");
            stage_fabric_runtime(&d, &work).unwrap();
            assert!(work.join(FABRIC_SERVER_LAUNCHER_JAR).is_file());
        }
    }
}
