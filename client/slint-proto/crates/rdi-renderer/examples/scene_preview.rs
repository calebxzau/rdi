//! Portable headless preview: scene_preview [output-directory] [elapsed seconds...].
use anyhow::{Context, Result};
use rdi_renderer::scene;
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};
fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(std::env::var("RUST_LOG").unwrap_or_else(|_| "info".into()))
        .init();
    let mut args = std::env::args().skip(1);
    let directory =
        std::path::PathBuf::from(args.next().unwrap_or_else(|| "evidence/bg-port".into()));
    let mut options = scene::diagnostics::Options::default();
    let mut capture_hdr = false;
    let mut capture_only = false;
    let mut material_probe = 0;
    let mut skin_file = None;
    let mut cape_file = None;
    let mut slim = None;
    let mut dump_player = false;
    let mut crowd_count = None;
    let mut crowd_skins = None;
    let mut dump_crowd = false;
    let mut time_args = vec![];
    for arg in args {
        if let Some(count) = arg.strip_prefix("--crowd=") {
            crowd_count = Some(count.parse::<usize>()?);
        } else if let Some(path) = arg.strip_prefix("--crowd-skins=") {
            crowd_skins = Some(std::path::PathBuf::from(path));
        } else if arg == "--dump-crowd" {
            dump_crowd = true;
        } else if let Some(path) = arg.strip_prefix("--player-skin=") {
            skin_file = Some(std::path::PathBuf::from(path));
        } else if let Some(path) = arg.strip_prefix("--player-cape=") {
            cape_file = Some(std::path::PathBuf::from(path));
        } else if arg == "--player-slim" {
            slim = Some(true);
        } else if arg == "--player-classic" {
            slim = Some(false);
        } else if arg == "--dump-player" {
            dump_player = true;
        } else if arg == "--capture-hdr" {
            capture_hdr = true;
        } else if arg == "--capture-only" {
            capture_only = true;
        } else if let Some(value) = arg.strip_prefix("--material-probe=") {
            material_probe = value.parse::<u32>()?;
            anyhow::ensure!(material_probe <= 5, "material probe must be0..5");
        } else if !options.parse(&arg)? {
            time_args.push(arg);
        }
    }
    scene::diagnostics::enable(options.probe);
    let times = time_args
        .into_iter()
        .map(|v| v.parse::<f64>().context("invalid elapsed seconds"))
        .collect::<Result<Vec<_>>>()?;
    let times = if times.is_empty() {
        vec![0., 15., 45.]
    } else {
        times
    };
    std::fs::create_dir_all(&directory)?;
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends: if cfg!(windows) { wgpu::Backends::DX12 } else { wgpu::Backends::PRIMARY },
        ..wgpu::InstanceDescriptor::new_without_display_handle()
    });
    let adapter =
        pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))?;
    println!("Adapter: {:?}", adapter.get_info());
    let (device, queue) =
        pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default()))?;
    let features = adapter.get_texture_format_features(wgpu::TextureFormat::Rgba16Float);
    tracing::info!(?features, "HDR format support");
    let mut renderer = scene::Renderer::with_options(device.clone(), queue.clone(), options)?;
    renderer.set_visual_probe_for_preview(material_probe);
    let mut appearance = scene::player::Appearance::steve()?;
    if let Some(path) = skin_file {
        appearance.skin = scene::player::Skin::new(image::open(path)?.to_rgba8(), slim)?;
    } else if let Some(slim) = slim {
        appearance.skin.slim = slim;
    }
    if let Some(path) = cape_file {
        appearance.cape = Some(scene::player::normalize(
            image::open(path)?.to_rgba8(),
            true,
        )?);
    }
    if dump_player {
        std::fs::write(
            directory.join("player-mesh.bin"),
            bytemuck::cast_slice(&scene::player_geometry::mesh(
                appearance.skin.slim,
                appearance.skin.pixels.height() == 32,
                true,
                appearance.cape.is_some(),
            )),
        )?;
    }
    let player_slim = appearance.skin.slim;
    let has_cape = appearance.cape.is_some();
    renderer.set_player_appearance(appearance)?;
    if let Some(count) = crowd_count {
        add_preview_crowd(&mut renderer, count, crowd_skins.as_deref())?;
    }
    renderer.advance_crowd_for_preview(0.);
    if options.pipelines_only {
        queue.submit([]);
        device.poll(wgpu::PollType::wait_indefinitely())?;
        scene::diagnostics::snapshot(&device, "preview-pipelines-only");
        tracing::info!("Pipeline-only experiment: no scene targets or PNG captures");
        return Ok(());
    }
    let width = scene::state::WIDTH;
    let height = scene::state::HEIGHT;
    let row = (width * 4).next_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("preview readback"),
        size: (row * height) as u64,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut simulated = 0f64;
    for t in times {
        anyhow::ensure!(t.is_finite() && t >= 0., "invalid elapsed seconds");
        while simulated < t {
            simulated = t.min(simulated + 1. / 60.);
            renderer.advance_crowd_for_preview(simulated);
        }
        if dump_crowd && let Some(runners) = renderer.crowd_runners_for_preview() {
            let runners: Vec<_> = runners
                .iter()
                .map(|r| serde_json::json!({"id":r.id,"slot":r.slot,"visible":r.visible,"x":r.x,"y":r.y,"z":r.z,"yaw":r.yaw,"phase":r.phase,"shadowX":r.shadow_x,"shadowY":r.shadow_y,"shadowZ":r.shadow_z}))
                .collect();
            std::fs::write(
                directory.join(format!("crowd-poses-{t:.1}.json")),
                serde_json::to_vec(&serde_json::json!({"time":t,"runners":runners}))?,
            )?;
        }
        if dump_player {
            std::fs::write(
                directory.join(format!("player-params-{t:.1}.bin")),
                bytemuck::bytes_of(&scene::player_geometry::Params::at(
                    t,
                    player_slim,
                    has_cape,
                )),
            )?;
        }
        renderer.render(scene::FrameInput::at(t))?;
        device.poll(wgpu::PollType::wait_indefinitely())?;
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            wgpu::TexelCopyTextureInfo {
                texture: renderer.output(),
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            wgpu::TexelCopyBufferInfo {
                buffer: &buffer,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(row),
                    rows_per_image: Some(height),
                },
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        queue.submit([encoder.finish()]);
        let (tx, rx) = mpsc::channel();
        buffer.slice(..).map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        device.poll(wgpu::PollType::wait_indefinitely())?;
        rx.recv()??;
        let bytes = buffer.slice(..).get_mapped_range()?;
        let mut image = image::RgbaImage::new(width, height);
        for (dest, src) in image
            .as_mut()
            .chunks_exact_mut(width as usize * 4)
            .zip(bytes.chunks_exact(row as usize))
        {
            dest.copy_from_slice(&src[..width as usize * 4]);
        }
        image.save(directory.join(format!("p0-rust-{t:.1}.png")))?;
        drop(bytes);
        buffer.unmap();
        if capture_hdr {
            capture_linear_hdr(
                &device,
                &queue,
                renderer.hdr_texture_for_preview(),
                &directory,
                t,
            )?;
            if let Some(reflection) = renderer.reflection_texture_for_preview() {
                capture_linear_hdr(&device, &queue, reflection, &directory, t)?;
            }
            if let Some(depth) = renderer.scene_depth_for_preview() {
                capture_linear_hdr(&device, &queue, depth, &directory, t)?;
            }
            if let Some(textures) = renderer.post_textures_for_preview() {
                for (label, texture) in ["fog", "linear", "bloom"].into_iter().zip(textures) {
                    capture_texture(&device, &queue, texture, &directory, t, label)?;
                }
                std::fs::write(
                    directory.join("fog-noise.r8"),
                    scene::fog::noise(scene::fog::SIZE, 0x0F06),
                )?;
            }
            let coordinates = scene::clouds::coordinates(t, scene::state::camera(t).0);
            std::fs::write(
                directory.join(format!("clouds-{t:.1}.bin")),
                bytemuck::cast_slice(&scene::clouds::mesh(coordinates)),
            )?;
            let mut clouds=image::load_from_memory(include_bytes!("../../../../bg-renderer/src/main/resources/assets/minecraft/textures/environment/clouds.png"))?.to_rgba8();
            scene::clouds::thin_cover(&mut clouds, 0.45);
            std::fs::write(directory.join("cloud-cover.rgba"), clouds.as_raw())?;
        }
    }
    capture_shadow(&device, &queue, renderer.shadow_texture(), &directory)?;
    if capture_only {
        return Ok(());
    }
    // Warm up, then pace continuous 60Hz deadlines. Includes CPU encode + GPU completion, no readback.
    for i in 0..30 {
        renderer.render(scene::FrameInput::at(i as f64 / 60.))?;
        device.poll(wgpu::PollType::wait_indefinitely())?;
    }
    let mut samples = vec![];
    let begin = Instant::now();
    let mut deadline = begin;
    for i in 0..180 {
        let now = Instant::now();
        if deadline > now {
            std::thread::sleep(deadline - now);
        }
        let start = Instant::now();
        renderer.render(scene::FrameInput::at(i as f64 / 60.))?;
        device.poll(wgpu::PollType::wait_indefinitely())?;
        samples.push(start.elapsed().as_secs_f64() * 1000.);
        deadline += Duration::from_secs_f64(1. / 60.);
        if deadline < Instant::now() {
            deadline = Instant::now();
        }
    }
    let mut ordered = samples.clone();
    ordered.sort_by(f64::total_cmp);
    std::fs::write(
        directory.join("timing.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            "samples_ms":samples,"mean_ms":samples.iter().sum::<f64>()/samples.len() as f64,
            "median_ms":ordered[ordered.len()/2],"p95_ms":ordered[ordered.len()*95/100],
            "max_ms":ordered[ordered.len()-1],"cadence_fps":180./begin.elapsed().as_secs_f64(),
            "p1_only":options.p1_only,"p2_only":options.p2_only,"legacy_sampler":options.legacy_sampler,"msaa":options.samples,"packed":options.packed,
            "metric":"CPU encode plus submit-to-completion; excludes capture/glass/presentation"
        }))?,
    )?;
    println!(
        "P0 paced: mean={:.3}ms max={:.3}ms cadence={:.2}FPS (CPU encode + completion; no readback/presentation)",
        samples.iter().sum::<f64>() / samples.len() as f64,
        samples.iter().copied().fold(0., f64::max),
        180. / begin.elapsed().as_secs_f64()
    );
    Ok(())
}

/// Mirrors Kotlin's preview: `preview-N` identities cycling through the sorted PNGs, or Steve.
fn add_preview_crowd(
    renderer: &mut scene::Renderer,
    count: usize,
    directory: Option<&std::path::Path>,
) -> Result<()> {
    anyhow::ensure!(
        (1..=scene::crowd_sim::MAX_PLAYERS).contains(&count),
        "crowd count must be 1..=50"
    );
    let mut files = vec![];
    if let Some(directory) = directory {
        for entry in std::fs::read_dir(directory)? {
            let path = entry?.path();
            if path.extension().is_some_and(|e| e == "png") {
                files.push(path);
            }
        }
        files.sort();
        anyhow::ensure!(
            !files.is_empty(),
            "No crowd skins in {}",
            directory.display()
        );
    }
    for index in 0..count {
        let skin = if files.is_empty() {
            scene::player::Appearance::steve()?.skin
        } else {
            let path = &files[index % files.len()];
            scene::player::Skin::new(image::open(path)?.to_rgba8(), None)?
        };
        renderer.set_crowd_skin(&format!("preview-{index}"), skin)?;
    }
    Ok(())
}
fn capture_linear_hdr(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    texture: &wgpu::Texture,
    directory: &std::path::Path,
    time: f64,
) -> Result<()> {
    let prefix = if texture.format() == wgpu::TextureFormat::Depth32Float {
        "depth"
    } else if texture.width() == scene::state::WIDTH {
        "hdr"
    } else {
        "reflection"
    };
    capture_texture(device, queue, texture, directory, time, prefix)
}
fn capture_texture(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    texture: &wgpu::Texture,
    directory: &std::path::Path,
    time: f64,
    prefix: &str,
) -> Result<()> {
    let depth = texture.format() == wgpu::TextureFormat::Depth32Float;
    let rgba8 = texture.format() == wgpu::TextureFormat::Rgba8Unorm;
    let row = texture.width() * if depth || rgba8 { 4 } else { 8 };
    anyhow::ensure!(row.is_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT));
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("preview HDR readback"),
        size: row as u64 * texture.height() as u64,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut encoder = device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(
        wgpu::TexelCopyTextureInfo {
            texture,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: if depth {
                wgpu::TextureAspect::DepthOnly
            } else {
                wgpu::TextureAspect::All
            },
        },
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(row),
                rows_per_image: Some(texture.height()),
            },
        },
        texture.size(),
    );
    queue.submit([encoder.finish()]);
    let (tx, rx) = mpsc::channel();
    buffer.slice(..).map_async(wgpu::MapMode::Read, move |r| {
        let _ = tx.send(r);
    });
    device.poll(wgpu::PollType::wait_indefinitely())?;
    rx.recv()??;
    let bytes = buffer.slice(..).get_mapped_range()?;
    let extension = if depth {
        "depth32f"
    } else if rgba8 {
        "rgba8"
    } else {
        "rgba16f"
    };
    std::fs::write(
        directory.join(format!("{prefix}-{time:.1}.{extension}")),
        &*bytes,
    )?;
    drop(bytes);
    buffer.unmap();
    Ok(())
}

fn capture_shadow(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    texture: &wgpu::Texture,
    directory: &std::path::Path,
) -> Result<()> {
    let width = texture.width();
    let height = texture.height();
    let row = (width * 4).next_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("preview shadow readback"),
        size: row as u64 * height as u64,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut encoder = device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(
        wgpu::TexelCopyTextureInfo {
            texture,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::DepthOnly,
        },
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(row),
                rows_per_image: Some(height),
            },
        },
        wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        },
    );
    queue.submit([encoder.finish()]);
    let (tx, rx) = mpsc::channel();
    buffer.slice(..).map_async(wgpu::MapMode::Read, move |r| {
        let _ = tx.send(r);
    });
    device.poll(wgpu::PollType::wait_indefinitely())?;
    rx.recv()??;
    let bytes = buffer.slice(..).get_mapped_range()?;
    let mut image = image::GrayImage::new(width, height);
    let mut low = 1.0_f32;
    let mut high = 0.0_f32;
    for (y, line) in bytes.chunks_exact(row as usize).enumerate() {
        for (x, p) in line[..width as usize * 4].chunks_exact(4).enumerate() {
            let depth = f32::from_le_bytes(p.try_into()?);
            if depth < 1.0 {
                low = low.min(depth);
                high = high.max(depth);
            }
            image.put_pixel(
                x as u32,
                y as u32,
                image::Luma([((1.0 - depth) * 255.0).round() as u8]),
            );
        }
    }
    image.save(directory.join("shadow-depth.png"))?;
    tracing::info!(low, high, "Preview shadow covered depth range");
    drop(bytes);
    buffer.unmap();
    Ok(())
}
