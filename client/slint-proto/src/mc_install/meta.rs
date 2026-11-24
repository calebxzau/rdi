//! Bundled Minecraft metadata (the `mcmeta` resources of `client/mclaunch`) and the loader
//! versions of `McVersion` (`common/model/.../McVersion.kt`) that J5a supports.
use crate::mc_launch::manifest::Manifest;
use anyhow::{Context, Result, bail};

/// `ModLoader.Version` for the loaders J5a installs (decision D2).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LoaderVersion {
    pub mc_version: &'static str,
    pub loader: &'static str,
    /// The version directory name, also the loader manifest id.
    pub dir_name: &'static str,
    pub installer_url: &'static str,
    pub installer_sha1: &'static str,
}

pub const LOADERS: [LoaderVersion; 2] = [
    LoaderVersion {
        mc_version: "1.21.1",
        loader: "neoforge",
        dir_name: "neoforge-21.1.265",
        installer_url: "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-installer.jar",
        installer_sha1: "25ce1d35d619ccfd3080076fa5758c18e11a9778",
    },
    LoaderVersion {
        mc_version: "1.20.1",
        loader: "forge",
        dir_name: "1.20.1-forge-47.4.29",
        installer_url: "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.29/forge-1.20.1-47.4.29-installer.jar",
        installer_sha1: "5061de68499474cc8eeeede3d794afb063dd3e6d",
    },
];

/// The loaders installed through their own installer; see [`runtime`] for all supported ones.
pub fn loader(mc_version: &str, loader: &str) -> Result<LoaderVersion> {
    LOADERS
        .iter()
        .find(|l| l.mc_version == mc_version && l.loader.eq_ignore_ascii_case(loader))
        .copied()
        .with_context(|| format!("暂不支持在这里启动{mc_version} {loader}整合包"))
}

/// How a pack's loader gets onto the disk and into the launch (J5c).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Runtime {
    /// Forge 1.20.1 and NeoForge 1.21.1 through their installers.
    Installer(LoaderVersion),
    /// Fabric 1.20.1 through the `meta.fabricmc.net` profile (`mc_install::fabric`).
    Fabric,
    /// GTNH on 1.7.10: the pack's Prism patches replace the Forge install (`mc_launch::gtnh`).
    Gtnh,
}

impl Runtime {
    /// `ModLoader.name` as the room and pack code spell it.
    pub fn loader_name(&self) -> &'static str {
        match self {
            Runtime::Installer(v) => v.loader,
            Runtime::Fabric => "Fabric",
            Runtime::Gtnh => "forge",
        }
    }
}

/// Every Minecraft version and loader the Rust launcher starts.
pub fn runtime(mc_version: &str, loader_name: &str) -> Result<Runtime> {
    match (mc_version, loader_name.to_lowercase().as_str()) {
        ("1.7.10", "forge") => Ok(Runtime::Gtnh),
        ("1.20.1", "fabric") => Ok(Runtime::Fabric),
        _ => loader(mc_version, loader_name).map(Runtime::Installer),
    }
}

fn vanilla_text(mc_version: &str) -> Result<&'static str> {
    Ok(match mc_version {
        "1.20.1" => include_str!("../../../mclaunch/src/main/resources/mcmeta/1.20.1.json"),
        "1.21.1" => include_str!("../../../mclaunch/src/main/resources/mcmeta/1.21.1.json"),
        "1.7.10" => include_str!("../../../mclaunch/src/main/resources/mcmeta/1.7.10.json"),
        other => bail!("缺少Minecraft{other}版本信息"),
    })
}

/// `McVersion.metadata`.
pub fn vanilla(mc_version: &str) -> Result<Manifest> {
    serde_json::from_str(vanilla_text(mc_version)?).with_context(|| format!("Minecraft{mc_version}版本信息无效"))
}

/// `mcmeta/assets-index/<id>.json`, as text so it is written out unchanged.
pub fn asset_index(id: &str) -> Result<&'static str> {
    Ok(match id {
        "5" => include_str!("../../../mclaunch/src/main/resources/mcmeta/assets-index/5.json"),
        "17" => include_str!("../../../mclaunch/src/main/resources/mcmeta/assets-index/17.json"),
        "1.7.10" => include_str!("../../../mclaunch/src/main/resources/mcmeta/assets-index/1.7.10.json"),
        other => bail!("缺少资源索引{other}"),
    })
}

pub const LAUNCHER_PROFILES: &[u8] = include_bytes!("../../../ui/assets/src/main/resources/launcher_profiles.json");
pub const INSTALL_BOOTSTRAPPER: &[u8] = include_bytes!("../../../ui/assets/src/main/resources/forge-install-bootstrapper.jar");
pub const EMPTY_SOUND: &[u8] = include_bytes!("../../../ui/assets/src/main/resources/assets/empty.ogg");

#[cfg(test)]
mod tests {
    use super::*;

    /// A changed loader in Kotlin must change Rust too; the strings are read from the Kotlin source.
    #[test]
    fn loader_versions_match_kotlin_mc_version() {
        let kotlin = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../common/model/src/main/kotlin/calebxzhou/rdi/common/model/McVersion.kt"),
        )
        .unwrap();
        for l in LOADERS {
            for value in [l.dir_name, l.installer_url, l.installer_sha1] {
                assert!(kotlin.contains(&format!("\"{value}\"")), "{value}");
            }
        }
        assert!(loader("1.7.10", "forge").is_err());
        assert!(loader("1.20.1", "Fabric").is_err());
        assert_eq!(runtime("1.7.10", "forge").unwrap(), Runtime::Gtnh);
        assert_eq!(runtime("1.20.1", "Fabric").unwrap(), Runtime::Fabric);
        assert!(runtime("1.21.1", "Fabric").is_err());
        let server = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../common/model/src/main/kotlin/calebxzhou/rdi/common/model/ServerLoaderRuntime.kt"),
        )
        .unwrap();
        assert!(server.contains(&format!("FABRIC_1_20_1_LOADER_VERSION = \"{}\"", super::super::fabric::LOADER_VERSION)));
    }

    #[test]
    fn bundled_metadata_parses() {
        for (mc, index) in [("1.20.1", "5"), ("1.21.1", "17"), ("1.7.10", "1.7.10")] {
            let m = vanilla(mc).unwrap();
            assert_eq!(m.id, mc);
            assert_eq!(m.asset_index.as_ref().unwrap().id, index);
            assert!(m.downloads.as_ref().unwrap().client.is_some());
            assert!(serde_json::from_str::<serde_json::Value>(asset_index(index).unwrap()).unwrap()["objects"].is_object());
        }
    }
}
