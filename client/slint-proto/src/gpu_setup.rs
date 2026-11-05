//! Creates the DX12 device before Slint starts, so Slint does not probe it again and the shader
//! compiler can fall back from DXC to FXC on GPUs that DXC cannot serve.
use anyhow::{Result, bail};
use slint::wgpu_30::{WGPUConfiguration, WGPUSettings, wgpu};
use std::path::PathBuf;

const DXC_DLL: &str = "dxcompiler.dll";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Manual,
    /// Let Slint create the device, as a control for startup measurements.
    Automatic,
}

impl Mode {
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "manual" => Ok(Self::Manual),
            "automatic" => Ok(Self::Automatic),
            _ => bail!("--gpu-setup只能是manual或automatic：{value}"),
        }
    }
}

/// DX12 on Windows: the lightest GPU path measured on the AMD iGPU (Vulkan and GL cost more).
/// `WGPU_BACKEND` still overrides it for comparisons, and leaves device creation to Slint.
pub fn configuration(mode: Mode) -> WGPUConfiguration {
    let mut settings = WGPUSettings::default();
    if !cfg!(windows) || std::env::var_os("WGPU_BACKEND").is_some() {
        return WGPUConfiguration::Automatic(settings);
    }
    settings.backends = wgpu::Backends::DX12;
    if mode == Mode::Automatic {
        return WGPUConfiguration::Automatic(settings);
    }
    let input = rdi_renderer::gpu::GpuSettings {
        backends: settings.backends,
        instance_flags: settings.instance_flags,
        backend_options: settings.backend_options.clone(),
        instance_memory_budget_thresholds: settings.instance_memory_budget_thresholds,
        power_preference: settings.power_preference,
        device_label: settings.device_label.as_deref().map(str::to_owned),
        device_required_features: settings.device_required_features,
        device_required_limits: settings.device_required_limits.clone(),
        device_experimental_features: settings.device_experimental_features,
        device_memory_hints: settings.device_memory_hints.clone(),
    };
    match rdi_renderer::gpu::create(&input, bundled_dxc().as_deref()) {
        Ok(context) => {
            // Slint's global platform keeps its copy until thread-local destructors after Windows
            // ends the other threads. Device destruction then hangs 5s and crashes (0xC0000409).
            // Keep one reference alive and let the OS reclaim it at process exit.
            std::mem::forget((
                context.instance.clone(),
                context.adapter.clone(),
                context.device.clone(),
                context.queue.clone(),
            ));
            WGPUConfiguration::Manual {
                instance: context.instance,
                adapter: context.adapter,
                device: context.device,
                queue: context.queue,
            }
        }
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "Manual GPU setup failed, letting Slint try");
            WGPUConfiguration::Automatic(settings)
        }
    }
}

/// Only our own copy is used, so a `dxcompiler.dll` elsewhere on the search path is never picked up.
fn bundled_dxc() -> Option<PathBuf> {
    let path = std::env::current_exe().ok()?.parent()?.join(DXC_DLL);
    path.is_file().then_some(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mode_parsing() {
        assert_eq!(Mode::parse("manual").unwrap(), Mode::Manual);
        assert_eq!(Mode::parse("automatic").unwrap(), Mode::Automatic);
        assert!(Mode::parse("other").is_err());
    }
}
