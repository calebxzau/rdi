//! Loads the launcher parade after the first UI frame.
pub use crate::parade::Renderer;
use crate::scene::diagnostics::Options;
use anyhow::Result;
use std::sync::mpsc;

pub fn spawn(
    device: wgpu::Device,
    queue: wgpu::Queue,
    first_frame: mpsc::Receiver<()>,
    options: Options,
) -> Result<mpsc::Receiver<Result<Renderer>>> {
    let (tx, rx) = mpsc::sync_channel(1);
    std::thread::Builder::new()
        .name("background-loader".into())
        .spawn(move || {
            if first_frame.recv().is_err() {
                return;
            }
            let start = std::time::Instant::now();
            let result = Renderer::new(device, queue, options);
            if let Err(error) = &result {
                tracing::error!(error = %format!("{error:#}"), "Background loader failed");
            }
            tracing::info!(
                elapsed_ms = start.elapsed().as_millis(),
                "Background loader finished"
            );
            let _ = tx.send(result);
        })?;
    Ok(rx)
}
