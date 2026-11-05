//! Creates the DX12 device before Slint starts, so Slint does not probe it again and the shader
//! compiler can fall back from DXC to FXC on GPUs that DXC cannot serve.
use anyhow::{Context, Result, bail};
use slint::wgpu_30::{WGPUConfiguration, WGPUSettings, wgpu};
use std::{
    path::{Path, PathBuf},
    time::Instant,
};

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
    match create_manual(&settings) {
        Ok(configuration) => configuration,
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "Manual GPU setup failed, letting Slint try");
            WGPUConfiguration::Automatic(settings)
        }
    }
}

fn create_manual(settings: &WGPUSettings) -> Result<WGPUConfiguration> {
    let attempts = compiler_attempts(
        settings.backend_options.dx12.shader_compiler.clone(),
        bundled_dxc().as_deref(),
    );
    let last = attempts.len().saturating_sub(1);
    let mut last_error = None;
    for (index, compiler) in attempts.into_iter().enumerate() {
        let name = compiler_name(&compiler);
        let started = Instant::now();
        match create_with_compiler(settings, compiler, index == last) {
            Ok(configuration) => {
                tracing::info!(
                    compiler = name,
                    elapsed_ms = started.elapsed().as_millis(),
                    "GPU device created"
                );
                return Ok(configuration);
            }
            Err(error) => {
                tracing::warn!(compiler = name, error = %format!("{error:#}"), "GPU device attempt failed");
                last_error = Some(error);
            }
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("no shader compiler to try")))
}

fn create_with_compiler(
    settings: &WGPUSettings,
    compiler: wgpu::Dx12Compiler,
    last_attempt: bool,
) -> Result<WGPUConfiguration> {
    let mut backend_options = settings.backend_options.clone();
    backend_options.dx12.shader_compiler = compiler;
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends: settings.backends,
        flags: settings.instance_flags,
        backend_options,
        memory_budget_thresholds: settings.instance_memory_budget_thresholds,
        display: None,
    });
    // Same adapter rules as Slint's automatic path. The window does not exist yet, so there is no
    // surface to check; every DX12 adapter can present to a Windows window.
    let adapter = pollster::block_on(async {
        match wgpu::util::initialize_adapter_from_env(&instance, None).await {
            Ok(adapter) => Ok(adapter),
            Err(_) => {
                instance
                    .request_adapter(&wgpu::RequestAdapterOptions {
                        power_preference: settings.power_preference,
                        force_fallback_adapter: false,
                        compatible_surface: None,
                        apply_limit_buckets: false,
                    })
                    .await
            }
        }
    })
    .context("no adapter")?;
    let device_type = adapter.get_info().device_type;
    if !adapter_acceptable(device_type, last_attempt) {
        bail!("only a software adapter is available");
    }
    let (device, queue) = pollster::block_on(
        adapter.request_device(&wgpu::DeviceDescriptor {
            label: settings.device_label.as_deref(),
            required_features: settings.device_required_features,
            required_limits: settings
                .device_required_limits
                .clone()
                .using_resolution(adapter.limits()),
            experimental_features: settings.device_experimental_features,
            memory_hints: settings.device_memory_hints.clone(),
            trace: wgpu::Trace::default(),
        }),
    )
    .context("no device")?;
    // Slint's global platform keeps its copy of these until the main thread's thread-local
    // destructors, when Windows has already ended the other threads. Destroying the device there
    // hangs for about 5s and then crashes the process, so keep it alive and let the OS reclaim it.
    std::mem::forget((
        instance.clone(),
        adapter.clone(),
        device.clone(),
        queue.clone(),
    ));
    Ok(WGPUConfiguration::Manual {
        instance,
        adapter,
        device,
        queue,
    })
}

/// Only our own copy is used, so a `dxcompiler.dll` elsewhere on the search path is never picked up.
fn bundled_dxc() -> Option<PathBuf> {
    let path = std::env::current_exe().ok()?.parent()?.join(DXC_DLL);
    path.is_file().then_some(path)
}

/// `WGPU_DX12_COMPILER` keeps working for comparisons. FXC is always the last resort: it needs no
/// DLL of ours and also serves GPUs without Shader Model 6.
fn compiler_attempts(
    requested: wgpu::Dx12Compiler,
    bundled: Option<&Path>,
) -> Vec<wgpu::Dx12Compiler> {
    match requested {
        wgpu::Dx12Compiler::Fxc => vec![wgpu::Dx12Compiler::Fxc],
        wgpu::Dx12Compiler::Auto => match bundled {
            Some(path) => vec![
                wgpu::Dx12Compiler::DynamicDxc {
                    dxc_path: path.to_string_lossy().into_owned(),
                },
                wgpu::Dx12Compiler::Fxc,
            ],
            None => vec![wgpu::Dx12Compiler::Fxc],
        },
        explicit => vec![explicit, wgpu::Dx12Compiler::Fxc],
    }
}

/// With DXC, wgpu hides GPUs without Shader Model 6, which can leave only the software adapter.
/// Retry with the next compiler then; the last attempt takes what it gets.
fn adapter_acceptable(device_type: wgpu::DeviceType, last_attempt: bool) -> bool {
    last_attempt || device_type != wgpu::DeviceType::Cpu
}

fn compiler_name(compiler: &wgpu::Dx12Compiler) -> &'static str {
    match compiler {
        wgpu::Dx12Compiler::Fxc => "fxc",
        wgpu::Dx12Compiler::DynamicDxc { .. } => "dxc",
        wgpu::Dx12Compiler::StaticDxc => "static-dxc",
        wgpu::Dx12Compiler::Auto => "auto",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(attempts: &[wgpu::Dx12Compiler]) -> Vec<&'static str> {
        attempts.iter().map(compiler_name).collect()
    }

    #[test]
    fn bundled_dxc_is_tried_first_and_falls_back_to_fxc() {
        let attempts = compiler_attempts(
            wgpu::Dx12Compiler::Auto,
            Some(Path::new(r"C:\game\dxcompiler.dll")),
        );
        assert_eq!(names(&attempts), ["dxc", "fxc"]);
        let wgpu::Dx12Compiler::DynamicDxc { dxc_path } = &attempts[0] else {
            panic!("expected dynamic DXC");
        };
        assert_eq!(dxc_path, r"C:\game\dxcompiler.dll");
    }

    #[test]
    fn missing_dll_uses_fxc_only() {
        let attempts = compiler_attempts(wgpu::Dx12Compiler::Auto, None);
        assert_eq!(names(&attempts), ["fxc"]);
    }

    #[test]
    fn environment_override_is_kept() {
        let bundled = Some(Path::new(r"C:\game\dxcompiler.dll"));
        assert_eq!(
            names(&compiler_attempts(wgpu::Dx12Compiler::Fxc, bundled)),
            ["fxc"]
        );
        let attempts = compiler_attempts(wgpu::Dx12Compiler::default_dynamic_dxc(), None);
        assert_eq!(names(&attempts), ["dxc", "fxc"]);
    }

    #[test]
    fn software_adapter_is_only_accepted_on_the_last_attempt() {
        assert!(!adapter_acceptable(wgpu::DeviceType::Cpu, false));
        assert!(adapter_acceptable(wgpu::DeviceType::Cpu, true));
        assert!(adapter_acceptable(wgpu::DeviceType::IntegratedGpu, false));
    }

    #[test]
    fn mode_parsing() {
        assert_eq!(Mode::parse("manual").unwrap(), Mode::Manual);
        assert_eq!(Mode::parse("automatic").unwrap(), Mode::Automatic);
        assert!(Mode::parse("other").is_err());
    }
}
