//! J5b: the jars a room game gets on its class path (J5 decision D4). The Gradle task
//! `:ui-service:gameRuntime` writes them with `manifest.json` into a `game-runtime` folder;
//! Kotlin's `GameRuntimeExportTest` checks the manifest against the service's own resolvers.
//! The FFmpeg natives are unpacked like `MediaProcNativeRuntime.prepare`, into the same cache
//! folder and under the same lock file, so Compose and Rust share one extraction.
use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use std::io::Read;
use std::path::{Path, PathBuf};

pub const SCHEMA: u32 = 1;
const READY_MARKER: &str = ".complete";

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    schema: u32,
    kotlin: Vec<String>,
    zstd: Vec<String>,
    media: Vec<String>,
    media_native: String,
    media_platform: String,
    forgeguard: Option<String>,
}

#[derive(Clone, Debug)]
pub struct GameRuntime {
    pub kotlin: Vec<PathBuf>,
    pub zstd: Vec<PathBuf>,
    pub media: Vec<PathBuf>,
    pub media_native: PathBuf,
    /// The JavaCPP classifier, such as `windows-x86_64-gpl`.
    pub media_platform: String,
    pub forgeguard: Option<PathBuf>,
}

/// `RDI_GAME_RUNTIME_DIR`, else the development build output beside the service.
/// Which runtimes a room game gets: `RdiRuntimeSelection` (GTNH has Kotlin through Forgelin),
/// `needsForgeRuntime` (no Kotlin for Fabric) and `resolveMediaRuntime` (1.20.1 and 1.21.1 only).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Selection {
    pub kotlin: bool,
    pub zstd: bool,
    pub media: bool,
}

impl Selection {
    pub fn for_room(mc_version: &str, loader: &str) -> Self {
        let modern = mc_version == "1.20.1" || mc_version == "1.21.1";
        Self { kotlin: modern && !loader.eq_ignore_ascii_case("fabric"), zstd: modern || mc_version == "1.7.10", media: modern }
    }
}

pub fn default_dir() -> PathBuf {
    std::env::var_os("RDI_GAME_RUNTIME_DIR").map(PathBuf::from).unwrap_or_else(|| {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../ui/ui-service/build/game-runtime")
    })
}

impl GameRuntime {
    pub fn load(dir: &Path) -> Result<Self> {
        let text = match std::fs::read_to_string(dir.join("manifest.json")) {
            Ok(text) => text,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                tracing::error!(dir = %dir.display(), "缺少游戏运行库；开发环境请在client/ui运行 gradlew :ui-service:gameRuntime");
                bail!("缺少游戏运行库，请重新安装启动器");
            }
            Err(error) => return Err(error).context("读取游戏运行库清单失败"),
        };
        let manifest: Manifest = serde_json::from_str(&text).context("游戏运行库清单格式无效")?;
        ensure!(manifest.schema == SCHEMA, "游戏运行库版本不匹配，请重新安装启动器");
        ensure!(manifest.kotlin.len() == 5, "游戏运行库清单无效: Kotlin运行库应有5个");
        ensure!(manifest.media.contains(&manifest.media_native), "游戏运行库清单无效: 缺少FFmpeg原生库");
        let file = |name: &String| -> Result<PathBuf> {
            ensure!(!name.contains(['/', '\\']) && name.ends_with(".jar"), "游戏运行库清单无效: {name}");
            let path = dir.join(name);
            ensure!(path.is_file(), "游戏运行库缺少文件: {name}");
            Ok(path)
        };
        let all = |names: &[String]| names.iter().map(file).collect::<Result<Vec<_>>>();
        Ok(Self {
            kotlin: all(&manifest.kotlin)?,
            zstd: all(&manifest.zstd)?,
            media: all(&manifest.media)?,
            media_native: file(&manifest.media_native)?,
            media_platform: manifest.media_platform,
            forgeguard: manifest.forgeguard.as_ref().map(file).transpose()?,
        })
    }

    /// The selected runtimes in `buildLaunchClasspath` order.
    pub fn classpath_for(&self, selection: Selection) -> Vec<PathBuf> {
        let pick = |on: bool, jars: &[PathBuf]| if on { jars.to_vec() } else { vec![] };
        [pick(selection.kotlin, &self.kotlin), pick(selection.zstd, &self.zstd), pick(selection.media, &self.media)].concat()
    }

    /// `MediaProcNativeRuntime.prepare` into `<tools>/mediaproc-natives/<sha256 of the native jar>`.
    pub fn prepare_media_natives(&self, tools: &Path) -> Result<PathBuf> {
        prepare_natives(&self.media_native, &tools.join("mediaproc-natives"), &self.media_platform)
    }
}

fn avutil_library(platform: &str) -> &'static str {
    if platform.starts_with("windows-") {
        "jniavutil.dll"
    } else if platform.starts_with("macosx-") {
        "libjniavutil.dylib"
    } else {
        "libjniavutil.so"
    }
}

fn sha256_file(path: &Path) -> Result<String> {
    use sha2::Digest as _;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = sha2::Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

pub fn prepare_natives(native_jar: &Path, root: &Path, platform: &str) -> Result<PathBuf> {
    ensure!(native_jar.is_file(), "FFmpeg原生库不存在: {}", native_jar.display());
    std::fs::create_dir_all(root)?;
    let sha = sha256_file(native_jar)?;
    let bundle = root.join(&sha);
    // Same lock file as Kotlin's FileChannel lock, so a Compose launcher never sees half a folder.
    let lock = std::fs::OpenOptions::new().create(true).truncate(false).write(true).open(root.join(format!("{sha}.lock")))?;
    lock.lock().context("锁定FFmpeg原生库目录失败")?;
    let result = (|| -> Result<()> {
        if !bundle.join(READY_MARKER).is_file() {
            extract(native_jar, &bundle, platform)?;
            let name = native_jar.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            std::fs::write(bundle.join(READY_MARKER), name)?;
        }
        Ok(())
    })();
    let _ = lock.unlock();
    result?;
    ensure!(bundle.join(avutil_library(platform)).is_file(), "FFmpeg原生库不完整");
    Ok(bundle)
}

fn extract(native_jar: &Path, bundle: &Path, platform: &str) -> Result<()> {
    let prefix = format!("org/bytedeco/ffmpeg/{platform}/");
    let mut zip = zip::ZipArchive::new(std::fs::File::open(native_jar)?).context("无法打开FFmpeg原生库")?;
    std::fs::create_dir_all(bundle)?;
    for index in 0..zip.len() {
        let mut entry = zip.by_index(index)?;
        if entry.is_dir() {
            continue;
        }
        let Some(relative) = entry.name().strip_prefix(&prefix).map(str::to_owned) else {
            continue;
        };
        let relative = crate::content_store::relative(&relative).with_context(|| format!("FFmpeg原生库条目无效: {}", entry.name()))?;
        let target = bundle.join(&relative);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = std::fs::File::create(&target)?;
        std::io::copy(&mut entry, &mut out)?;
        #[cfg(unix)]
        if !platform.starts_with("windows-") {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&target, std::fs::Permissions::from_mode(0o755))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod selection_tests {
    use super::Selection;

    #[test]
    fn runtimes_follow_kotlin_per_version_and_loader() {
        let all = Selection { kotlin: true, zstd: true, media: true };
        assert_eq!(Selection::for_room("1.20.1", "forge"), all);
        assert_eq!(Selection::for_room("1.21.1", "neoforge"), all);
        assert_eq!(Selection::for_room("1.20.1", "Fabric"), Selection { kotlin: false, ..all });
        assert_eq!(Selection::for_room("1.7.10", "forge"), Selection { kotlin: false, zstd: true, media: false });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(path: &Path, entries: &[(&str, &[u8])]) {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for (name, bytes) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }

    fn folder(entries: &[&str], forgeguard: bool) -> tempfile_dir::Dir {
        let dir = tempfile_dir::Dir::new();
        let names: Vec<String> = entries.iter().map(|s| s.to_string()).collect();
        for name in &names {
            std::fs::write(dir.path().join(name), b"jar").unwrap();
        }
        if forgeguard {
            std::fs::write(dir.path().join("forgeguard.jar"), b"agent").unwrap();
        }
        let manifest = serde_json::json!({
            "schema": 1, "kotlin": &names[0..5], "zstd": &names[5..6], "media": &names[6..8],
            "mediaNative": names[7], "mediaPlatform": "windows-x86_64-gpl",
            "forgeguard": if forgeguard { serde_json::json!("forgeguard.jar") } else { serde_json::Value::Null },
        });
        std::fs::write(dir.path().join("manifest.json"), manifest.to_string()).unwrap();
        dir
    }

    const NAMES: [&str; 8] = ["k1.jar", "k2.jar", "k3.jar", "k4.jar", "k5.jar", "zstd.jar", "media.jar", "ffmpeg-windows-x86_64-gpl.jar"];

    #[test]
    fn manifest_gives_the_class_path_in_order() {
        let dir = folder(&NAMES, true);
        let runtime = GameRuntime::load(dir.path()).unwrap();
        let names: Vec<String> = runtime.classpath_for(Selection { kotlin: true, zstd: true, media: true }).iter().map(|p| p.file_name().unwrap().to_string_lossy().into_owned()).collect();
        assert_eq!(names, NAMES);
        assert_eq!(runtime.forgeguard.unwrap(), dir.path().join("forgeguard.jar"));
    }

    #[test]
    fn missing_folder_file_or_schema_is_refused() {
        let empty = tempfile_dir::Dir::new();
        assert_eq!(GameRuntime::load(empty.path()).unwrap_err().to_string(), "缺少游戏运行库，请重新安装启动器");
        let dir = folder(&NAMES, false);
        std::fs::remove_file(dir.path().join("k3.jar")).unwrap();
        assert_eq!(GameRuntime::load(dir.path()).unwrap_err().to_string(), "游戏运行库缺少文件: k3.jar");
        let dir = folder(&NAMES, false);
        let text = std::fs::read_to_string(dir.path().join("manifest.json")).unwrap().replace("\"schema\":1", "\"schema\":2");
        std::fs::write(dir.path().join("manifest.json"), text).unwrap();
        assert!(GameRuntime::load(dir.path()).unwrap_err().to_string().contains("版本不匹配"));
        assert!(GameRuntime::load(folder(&NAMES, false).path()).unwrap().forgeguard.is_none());
    }

    #[test]
    fn natives_are_unpacked_once_under_the_jar_hash() {
        let dir = tempfile_dir::Dir::new();
        let native = dir.path().join("ffmpeg-8-windows-x86_64-gpl.jar");
        jar(&native, &[
            ("META-INF/MANIFEST.MF", b"m"),
            ("org/bytedeco/ffmpeg/windows-x86_64-gpl/jniavutil.dll", b"dll"),
            ("org/bytedeco/ffmpeg/windows-x86_64-gpl/sub/avcodec-61.dll", b"codec"),
            ("org/bytedeco/ffmpeg/linux-x86_64-gpl/libjniavutil.so", b"other platform"),
        ]);
        let root = dir.path().join("tools/mediaproc-natives");
        let bundle = prepare_natives(&native, &root, "windows-x86_64-gpl").unwrap();
        assert_eq!(bundle, root.join(sha256_file(&native).unwrap()));
        assert_eq!(std::fs::read(bundle.join("sub/avcodec-61.dll")).unwrap(), b"codec");
        assert!(!bundle.join("libjniavutil.so").exists());
        assert_eq!(std::fs::read_to_string(bundle.join(READY_MARKER)).unwrap(), "ffmpeg-8-windows-x86_64-gpl.jar");
        // A finished folder is reused as it is.
        std::fs::write(bundle.join("sub/avcodec-61.dll"), b"kept").unwrap();
        prepare_natives(&native, &root, "windows-x86_64-gpl").unwrap();
        assert_eq!(std::fs::read(bundle.join("sub/avcodec-61.dll")).unwrap(), b"kept");
        // Wrong platform: the marker check fails.
        let other = dir.path().join("other-root");
        assert_eq!(prepare_natives(&native, &other, "macosx-arm64-gpl").unwrap_err().to_string(), "FFmpeg原生库不完整");
    }

    /// A temporary folder removed on drop; the crate has no tempfile dependency.
    mod tempfile_dir {
        pub struct Dir(std::path::PathBuf);
        impl Dir {
            pub fn new() -> Self {
                let path = std::env::temp_dir().join(format!("rdi-runtime-test-{}", uuid::Uuid::now_v7()));
                std::fs::create_dir_all(&path).unwrap();
                Self(path)
            }
            pub fn path(&self) -> &std::path::Path {
                &self.0
            }
        }
        impl Drop for Dir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }
}
