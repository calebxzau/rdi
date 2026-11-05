//! Reports whether the platform has a usable primary-backend adapter before scene compilation.
fn main() -> anyhow::Result<()> {
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends: if cfg!(windows) { wgpu::Backends::DX12 } else { wgpu::Backends::PRIMARY },
        ..wgpu::InstanceDescriptor::new_without_display_handle()
    });
    let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))?;
    println!("{:?}", adapter.get_info());
    Ok(())
}
