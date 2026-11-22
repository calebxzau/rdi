use super::manifest::{Host, Manifest, normalize_loader};
use super::*;
use serde_json::Value;

const FIXTURE: &str = include_str!("../../protocol/fixtures/j5a.json");

fn temp() -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rdi-j5a-launch-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn placeholder_jar(file: &Path) {
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(file).unwrap());
    zip.start_file("placeholder.txt", zip::write::SimpleFileOptions::default()).unwrap();
    std::io::Write::write_all(&mut zip, b"rdi").unwrap();
    zip.finish().unwrap();
}

fn strip_checksums(mut manifest: Manifest) -> Manifest {
    for library in &mut manifest.libraries {
        library.sha1 = None;
        library.checksums.clear();
        if let Some(a) = &mut library.downloads.artifact {
            a.sha1.clear();
        }
        if let Some(c) = &mut library.downloads.classifiers {
            c.values_mut().for_each(|a| a.sha1.clear());
        }
    }
    manifest
}

fn vanilla(mc: &str) -> Manifest {
    crate::mc_install::meta::vanilla(mc).unwrap()
}

#[test]
fn launch_commands_match_the_kotlin_fixture() {
    let fixture: Value = serde_json::from_str(FIXTURE).unwrap();
    let h = &fixture["host"];
    let host = Host {
        os_name: h["osName"].as_str().unwrap().into(),
        os_arch: h["osArch"].as_str().unwrap().into(),
        os_version: h["osVersion"].as_str().unwrap().into(),
        path_separator: h["pathSeparator"].as_str().unwrap().into(),
    };
    let cases = fixture["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 2);
    for case in cases {
        let root = temp();
        let root_text = root.to_string_lossy().into_owned();
        let dirs = Directories {
            versions: root.join("versions"),
            libraries: root.join("libraries"),
            assets: root.join("assets"),
        };
        let mc = case["mcVersion"].as_str().unwrap();
        let loader_name = case["loader"].as_str().unwrap();
        let dir_name = crate::mc_install::meta::loader(mc, loader_name).unwrap().dir_name;
        let raw: Manifest = serde_json::from_str(
            &std::fs::read_to_string(
                Path::new(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/j5a").join(case["loaderManifest"].as_str().unwrap()),
            )
            .unwrap(),
        )
        .unwrap();
        let manifest = strip_checksums(vanilla(mc));
        let loader = strip_checksums(normalize_loader(raw, dir_name, mc));
        for library in manifest.libraries.iter().chain(&loader.libraries) {
            if let Some(path) = library.downloads.artifact.as_ref().and_then(|a| a.path.clone()).filter(|p| !p.trim().is_empty()).or_else(|| manifest::descriptor_path(&library.name)) {
                placeholder_jar(&dirs.libraries.join(path));
            }
        }
        placeholder_jar(&dirs.versions.join(mc).join(format!("{mc}.jar")));
        std::fs::create_dir_all(root.join("jdk17")).unwrap();
        std::fs::write(root.join("jdk17/release"), "JAVA_VERSION=\"17.0.2\"\nOS_ARCH=\"amd64\"\n").unwrap();
        let version_id = case["versionId"].as_str().unwrap();
        let version_dir = dirs.versions.join(version_id);
        std::fs::create_dir_all(&version_dir).unwrap();
        let nickname = case["nickname"].as_str().unwrap();
        let account = crate::play::offline_account(nickname).unwrap();
        assert_eq!(account.uuid, case["offlineUuid"].as_str().unwrap(), "{nickname}");
        let java_override = case["javaPath"].as_str().map(|j| j.replace("${ROOT}", &root_text));
        let extra: Vec<String> = case["extraJvmArgs"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().into()).collect();
        let window = case["window"].as_array().unwrap();
        let request = Request {
            mc_version: mc,
            version_id,
            version_dir: &version_dir,
            manifest: &manifest,
            loader: &loader,
            account: &account,
            window: (window[0].as_str().unwrap().parse().unwrap(), window[1].as_str().unwrap().parse().unwrap()),
            java_override: java_override.as_deref(),
            max_memory_override: case["maxMemoryMb"].as_i64().map(|m| m as i32),
            extra_jvm_args: &extra,
        };
        let env = Environment {
            directories: &dirs,
            host: &host,
            java: "JAVA",
            java_major: 25,
            max_memory_mb: case["defaultMaxMemoryMb"].as_i64().unwrap() as i32,
            launcher_version: case["launcherVersion"].as_str().unwrap(),
            free_memory_mb: &|| panic!("a maximum is configured"),
        };
        validate_libraries(&manifest, &loader, &dirs.libraries, &host).unwrap();
        let built: Vec<String> = mask_secrets(&command(&request, &env).unwrap())
            .into_iter()
            .map(|a| a.replace(&root_text, "${ROOT}").replace('\\', "/"))
            .collect();
        let expected: Vec<String> = case["command"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().into()).collect();
        for (i, (b, e)) in built.iter().zip(&expected).enumerate() {
            assert_eq!(b, e, "{} argument {i}", case["name"]);
        }
        assert_eq!(built.len(), expected.len(), "{}", case["name"]);
        std::fs::remove_dir_all(root).unwrap();
    }
}

#[test]
fn masking_hides_only_the_token() {
    let args: Vec<String> = ["java", "--accessToken", "secret", "--username", "a", "--accessToken"].map(String::from).into();
    assert_eq!(mask_secrets(&args), ["java", "--accessToken", "***", "--username", "a", "--accessToken"]);
}

#[test]
fn release_majors_follow_kotlin() {
    let root = temp();
    for (version, major) in [("\"25.0.1\"", Some(25)), ("\"1.8.0_392\"", Some(8)), ("\"17\"", Some(17)), ("x", None)] {
        std::fs::write(root.join("release"), format!("JAVA_VERSION={version}\n")).unwrap();
        assert_eq!(release_major(&root.join("bin").join("java.exe")), major, "{version}");
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn rules_use_java_arch_names_and_features_never_match() {
    let host = Host { os_name: "windows".into(), os_arch: "amd64".into(), os_version: "10.0".into(), path_separator: ";".into() };
    let args: Vec<Value> = serde_json::from_str(
        r#"[{"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"},
            {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
            {"rules":[{"action":"allow","os":{"name":"windows","version":"^10\\."}}],"value":["-Dos.name=Windows 10"]},
            {"rules":[{"action":"allow","os":{"name":"osx"}}],"value":"-XstartOnFirstThread"},
            "plain"]"#,
    )
    .unwrap();
    assert_eq!(manifest::resolve_arguments(&args, &host), ["-Dos.name=Windows 10", "plain"]);
    assert_eq!(Host::current().path_separator, if cfg!(windows) { ";" } else { ":" });
}

#[test]
fn spawned_output_reaches_the_callback() {
    let (tx, rx) = std::sync::mpsc::channel();
    let command: Vec<String> = if cfg!(windows) {
        ["cmd", "/c", "echo one && echo two 1>&2 && exit 3"].map(String::from).into()
    } else {
        ["sh", "-c", "echo one; echo two 1>&2; exit 3"].map(String::from).into()
    };
    let mut started = spawn(&command, &std::env::temp_dir(), move |l| tx.send(l).unwrap()).unwrap();
    let mut lines = vec![];
    while let Ok(line) = rx.recv() {
        if line.is_empty() {
            break;
        }
        lines.push(line.trim().to_owned());
    }
    assert_eq!(lines, ["one", "two"]);
    let status = started.child.wait().unwrap();
    assert_eq!(exit_line(status.code()), "MC已结束，退出代码: 3");
}
