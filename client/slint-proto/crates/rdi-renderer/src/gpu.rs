//! Explicit device setup without a window or UI framework.
use anyhow::{Context, Result, bail};
use std::{path::Path, time::Instant};
pub struct GpuSettings {
    pub backends: wgpu::Backends,
    pub instance_flags: wgpu::InstanceFlags,
    pub backend_options: wgpu::BackendOptions,
    pub instance_memory_budget_thresholds: wgpu::MemoryBudgetThresholds,
    pub power_preference: wgpu::PowerPreference,
    pub device_label: Option<String>,
    pub device_required_features: wgpu::Features,
    pub device_required_limits: wgpu::Limits,
    pub device_experimental_features: wgpu::ExperimentalFeatures,
    pub device_memory_hints: wgpu::MemoryHints,
}
pub struct GpuContext {
    pub instance: wgpu::Instance,
    pub adapter: wgpu::Adapter,
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
}
pub fn create(settings: &GpuSettings, dxc_path: Option<&Path>) -> Result<GpuContext> {
    let attempts = compiler_attempts(
        settings.backend_options.dx12.shader_compiler.clone(),
        dxc_path,
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
    settings: &GpuSettings,
    compiler: wgpu::Dx12Compiler,
    last_attempt: bool,
) -> Result<GpuContext> {
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
    Ok(GpuContext {
        instance,
        adapter,
        device,
        queue,
    })
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
}
