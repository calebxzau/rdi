//! One bounded upload buffer, reused only after the preceding copy completes (debug experiment).
use super::diagnostics;
use anyhow::Result;
use wgpu::util::DeviceExt;
pub fn buffer(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    label: &str,
    bytes: &[u8],
    usage: wgpu::BufferUsages,
    chunked: bool,
) -> Result<wgpu::Buffer> {
    diagnostics::resource(label, bytes.len() as u64);
    if !chunked {
        return Ok(
            device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some(label),
                contents: bytes,
                usage,
            }),
        );
    }
    let output = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some(label),
        size: bytes.len() as u64,
        usage: usage | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let chunk_size = 1024 * 1024;
    let staging = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("scene reusable 1MiB upload"),
        size: chunk_size as u64,
        usage: wgpu::BufferUsages::MAP_WRITE | wgpu::BufferUsages::COPY_SRC,
        mapped_at_creation: true,
    });
    for (i, chunk) in bytes.chunks(chunk_size).enumerate() {
        if i > 0 {
            let (tx, rx) = std::sync::mpsc::channel();
            staging.slice(..).map_async(wgpu::MapMode::Write, move |r| {
                let _ = tx.send(r);
            });
            device.poll(wgpu::PollType::wait_indefinitely())?;
            rx.recv()??;
        }
        staging
            .get_mapped_range_mut(..)?
            .slice(..chunk.len())
            .copy_from_slice(chunk);
        staging.unmap();
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(
            &staging,
            0,
            &output,
            (i * chunk_size) as u64,
            chunk.len() as u64,
        );
        queue.submit([encoder.finish()]);
        device.poll(wgpu::PollType::wait_indefinitely())?;
    }
    drop(staging);
    Ok(output)
}
