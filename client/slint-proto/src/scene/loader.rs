use super::Renderer;
use slint::wgpu_30::wgpu;
use std::sync::mpsc;

pub fn spawn(
    device: wgpu::Device,
    queue: wgpu::Queue,
    first_frame: mpsc::Receiver<()>,
    options: super::diagnostics::Options,
) -> anyhow::Result<mpsc::Receiver<anyhow::Result<Renderer>>> {
    let (tx, rx) = mpsc::sync_channel(1);
    std::thread::Builder::new()
        .name("valley-loader".into())
        .spawn(move || {
            // Keep mesh construction and uploads out of the first static frame's critical path.
            // Closing the window before that frame cancels initialization without GPU work.
            if first_frame.recv().is_err() {
                return;
            }
            let start = std::time::Instant::now();
            let result = Renderer::with_options(device, queue, options);
            if let Err(ref error) = result {
                tracing::error!(error = %format!("{error:#}"), "Scene loader failed; using static background");
            }
            tracing::info!(elapsed_ms = start.elapsed().as_millis(), "Scene loader finished");
            // An unsent result releases the scene on this worker if the window has gone away.
            let _ = tx.send(result);
        })?;
    Ok(rx)
}
