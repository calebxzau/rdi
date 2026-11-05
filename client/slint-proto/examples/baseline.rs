//! Memory baseline: an empty Slint window on the same backend/renderer as the prototype.
//! `cargo run --release --example baseline`, then read WorkingSet64/PrivateMemorySize64 of `baseline.exe`.
slint::slint! {
    export component Baseline inherits Window {
        preferred-width: 1280px;
        preferred-height: 720px;
        Text { text: "baseline"; }
    }
}

fn main() -> anyhow::Result<()> {
    let renderer = std::env::args().nth(1).unwrap_or_else(|| "femtovg".into());
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(renderer)
        .select()?;
    Baseline::new()?.run()?;
    Ok(())
}
