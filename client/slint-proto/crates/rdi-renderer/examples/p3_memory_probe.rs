//! Diagnostic allocator attribution, separate from the production executable and its timings.
use anyhow::Result;
use rdi_renderer::scene;
use std::{
    alloc::{GlobalAlloc, Layout, System},
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};
struct Counting;
static LIVE: AtomicU64 = AtomicU64::new(0);
static PEAK: AtomicU64 = AtomicU64::new(0);
#[global_allocator]
static ALLOCATOR: Counting = Counting;
fn add(size: u64) {
    let total = LIVE.fetch_add(size, Ordering::Relaxed) + size;
    PEAK.fetch_max(total, Ordering::Relaxed);
}
// The wrapper preserves System's allocation/alignment contract and only counts successful calls.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(layout) };
        if !p.is_null() {
            add(layout.size() as u64);
        }
        p
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let p = unsafe { System.alloc_zeroed(layout) };
        if !p.is_null() {
            add(layout.size() as u64);
        }
        p
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        unsafe {
            System.dealloc(ptr, layout);
        }
        LIVE.fetch_sub(layout.size() as u64, Ordering::Relaxed);
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        let p = unsafe { System.realloc(ptr, layout, size) };
        if !p.is_null() {
            if size >= layout.size() {
                add((size - layout.size()) as u64);
            } else {
                LIVE.fetch_sub((layout.size() - size) as u64, Ordering::Relaxed);
            }
        }
        p
    }
}
fn mark(device: Option<&wgpu::Device>, phase: &str) {
    let live = LIVE.load(Ordering::Relaxed);
    let peak = PEAK.load(Ordering::Relaxed);
    println!(
        "P3_CPU {}",
        serde_json::json!({"phase":phase,"rust_live_bytes":live,"rust_peak_bytes":peak})
    );
    if let Some(device) = device {
        scene::diagnostics::snapshot(device, phase);
    }
    std::thread::sleep(Duration::from_secs(3));
}
fn main() -> Result<()> {
    anyhow::ensure!(cfg!(windows), "Run this diagnostic on Windows");
    tracing_subscriber::fmt()
        .with_env_filter("info")
        .with_ansi(false)
        .init();
    let mut options = scene::diagnostics::Options::default();
    let mut frames = 600_u32;
    for arg in std::env::args().skip(1) {
        if let Some(value) = arg.strip_prefix("--frames=") {
            frames = value.parse()?;
        } else {
            anyhow::ensure!(options.parse(&arg)?, "unknown diagnostic option");
        }
    }
    scene::diagnostics::enable(true);
    mark(None, "process-entry");
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends: wgpu::Backends::DX12,
        ..wgpu::InstanceDescriptor::new_without_display_handle()
    });
    let adapter = pollster::block_on(instance.request_adapter(&Default::default()))?;
    let (device, queue) = pollster::block_on(adapter.request_device(&Default::default()))?;
    mark(Some(&device), "device-ready");
    let mut renderer = scene::Renderer::with_options(device.clone(), queue.clone(), options)?;
    queue.submit([]);
    device.poll(wgpu::PollType::wait_indefinitely())?;
    mark(Some(&device), "renderer-ready-no-frames");
    let mut deadline = Instant::now();
    for i in 0..frames {
        let now = Instant::now();
        if deadline > now {
            std::thread::sleep(deadline - now);
        }
        anyhow::ensure!(
            renderer.render(scene::FrameInput::at(i as f64 / 60.))?,
            "diagnostic frame skipped"
        );
        device.poll(wgpu::PollType::wait_indefinitely())?;
        if i == 0 {
            mark(Some(&device), "after-first-frame");
        }
        deadline += Duration::from_secs_f64(1. / 60.);
        if deadline < Instant::now() {
            deadline = Instant::now();
        }
    }
    mark(Some(&device), "after-paced-frames");
    drop(renderer);
    queue.submit([]);
    device.poll(wgpu::PollType::wait_indefinitely())?;
    mark(Some(&device), "renderer-dropped");
    Ok(())
}
