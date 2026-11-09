use std::{env, fs, path::PathBuf};

const DXC_DLL: &str = "third_party/dxc/x64/dxcompiler.dll";

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let versions = "../ui/gradle/libs.versions.toml";
    println!("cargo:rerun-if-changed={versions}");
    let text = fs::read_to_string(versions)?;
    let version = text
        .lines()
        .find_map(|line| {
            let (key, value) = line.split_once('=')?;
            (key.trim() == "app").then(|| value.trim().trim_matches('"').to_owned())
        })
        .filter(|value| !value.is_empty())
        .ok_or("app version missing")?;
    println!("cargo:rustc-env=RDI_APP_VERSION={version}");
    slint_build::compile("ui/app.slint")?;
    println!("cargo:rerun-if-changed={DXC_DLL}");
    if env::var("CARGO_CFG_TARGET_OS")? == "windows"
        && env::var("CARGO_CFG_TARGET_ARCH")? == "x86_64"
    {
        // OUT_DIR is target/<profile>/build/<crate>-<hash>/out, and the app looks next to its own exe.
        let out_dir = PathBuf::from(env::var("OUT_DIR")?);
        let profile_dir = out_dir
            .ancestors()
            .nth(3)
            .ok_or("OUT_DIR has an unexpected layout")?;
        fs::copy(DXC_DLL, profile_dir.join("dxcompiler.dll"))?;
    }
    Ok(())
}
