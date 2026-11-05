mod shaders;
pub mod uniforms;
/// Layout metrics in logical pixels, owned by the calling UI.
#[derive(Clone, Copy)]
pub struct GridMetrics {
    pub tile: f32,
    pub pitch: f32,
    pub padding: f32,
    pub rows: usize,
    pub height: f32,
}
use anyhow::{Context, Result, bail};
use std::{
    future::Future,
    num::NonZeroU64,
    task::{Poll, Waker},
    time::Instant,
};
use uniforms::UniformWriter;
#[derive(Clone, Copy)]
pub struct Grid {
    pub y: f32,
    pub x: f32,
    pub scroll: f32,
    pub count: usize,
    pub opacity: f32,
    pub hover: i32,
    pub hover_progress: f32,
    pub pressed: i32,
    pub press_scale: f32,
}
#[derive(Clone, Copy)]
pub struct Pill {
    pub rect: [f32; 4],
    pub selected: bool,
    pub progress: f32,
    pub pivot: [f32; 2],
}
pub struct Frame {
    pub width: i32,
    pub height: i32,
    pub scale: f32,
    pub grids: Vec<Grid>,
    pub pills: Vec<Pill>,
}
/// Every texture is plain 8-bit RGBA, like femtovg's swapchain (no sRGB conversion).
const FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8Unorm;
/// Uniform slots in the shared buffer; a frame needs about a dozen (background, 2 grids, pills × 2).
const SLOTS: u64 = 32;
/// Indices into `Cache::frosts`: one blurred background per frost radius.
const FROST_TILE: usize = 0;
const FROST_HAZE: usize = 1;
const FROST_SELECTED: usize = 2;
struct Pipelines {
    background: wgpu::RenderPipeline,
    blur: wgpu::RenderPipeline,
    grid: wgpu::RenderPipeline,
    pill: wgpu::RenderPipeline,
    haze: wgpu::RenderPipeline,
    rim: wgpu::RenderPipeline,
}
/// A texture with the bind group that samples it.
struct Bound {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
    group: wgpu::BindGroup,
}
/// Size-dependent textures, rebuilt when the window size or scale changes.
struct Cache {
    key: (u32, u32, u32),
    /// What Slint shows behind the UI: background plus glass, redrawn every frame.
    backdrop: wgpu::Texture,
    backdrop_view: wgpu::TextureView,
    frosts: Vec<Bound>,
    live: Option<LiveBlur>,
}
struct LiveBlur {
    half: Bound,
    quarter: Bound,
    horizontals: [Bound; 3],
    sigmas: [f32; 3],
}
struct Draw<'a> {
    pipeline: &'a wgpu::RenderPipeline,
    group: &'a wgpu::BindGroup,
    offset: u32,
    scissor: Option<[u32; 4]>,
}
/// Draws for one submission; each gets its own uniform slot in `bytes`.
struct Batch<'a> {
    slot: u64,
    bytes: Vec<u8>,
    draws: Vec<Draw<'a>>,
}
impl<'a> Batch<'a> {
    fn new(slot: u64) -> Self {
        Self {
            slot,
            bytes: vec![],
            draws: vec![],
        }
    }
    fn add(
        &mut self,
        pipeline: &'a wgpu::RenderPipeline,
        group: &'a wgpu::BindGroup,
        params: &[u8],
        scissor: Option<[u32; 4]>,
    ) -> Result<()> {
        let offset = self.draws.len() as u64 * self.slot;
        anyhow::ensure!(offset < SLOTS * self.slot, "glass draw slots exhausted");
        anyhow::ensure!(
            params.len() as u64 <= self.slot,
            "glass uniforms exceed one slot"
        );
        self.bytes.resize(offset as usize, 0);
        self.bytes.extend_from_slice(params);
        self.draws.push(Draw {
            pipeline,
            group,
            offset: offset as u32,
            scissor,
        });
        Ok(())
    }
}
pub struct Renderer {
    metrics: GridMetrics,
    device: wgpu::Device,
    queue: wgpu::Queue,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    pipelines: Pipelines,
    uniforms: wgpu::Buffer,
    slot: u64,
    background: Bound,
    image_size: [f32; 2],
    live_background: Option<Bound>,
    cache: Option<Cache>,
}
/// Runs `body` inside a validation error scope so wgpu errors surface as `Err` instead of panicking.
fn validated<T>(device: &wgpu::Device, body: impl FnOnce() -> Result<T>) -> Result<T> {
    let scope = device.push_error_scope(wgpu::ErrorFilter::Validation);
    let result = body();
    let mut pop = std::pin::pin!(scope.pop());
    // Native backends resolve error scopes immediately; Pending means "unknown", not "failed".
    if let Poll::Ready(Some(error)) = pop
        .as_mut()
        .poll(&mut std::task::Context::from_waker(Waker::noop()))
    {
        bail!("wgpu: {error}");
    }
    result
}
#[cfg(not(test))]
fn haze_source() -> &'static str {
    shaders::HAZE
}
#[cfg(test)]
fn haze_source() -> &'static str {
    if std::env::var_os("GLASS_CAPTURE_GENERIC_HAZE").is_some() {
        shaders::tests::HAZE_GENERIC
    } else {
        shaders::HAZE
    }
}
impl Renderer {
    pub fn device(&self) -> &wgpu::Device {
        &self.device
    }
    pub fn new(device: wgpu::Device, queue: wgpu::Queue, metrics: GridMetrics) -> Result<Self> {
        validated(&device.clone(), || Self::create(device, queue, metrics))
    }
    fn create(device: wgpu::Device, queue: wgpu::Queue, metrics: GridMetrics) -> Result<Self> {
        let info = device.adapter_info();
        tracing::info!(backend = ?info.backend, adapter = %info.name, driver = %info.driver_info, "wgpu setup");
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("glass"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: true,
                        min_binding_size: None,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("glass"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = |name: &str, source: &str, blend: Option<wgpu::BlendState>| {
            let started = Instant::now();
            let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some(name),
                source: wgpu::ShaderSource::Wgsl(source.into()),
            });
            let result = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(name),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState {
                    module: &module,
                    entry_point: Some("vs"),
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                fragment: Some(wgpu::FragmentState {
                    module: &module,
                    entry_point: Some("fs"),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: FORMAT,
                        blend,
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                multiview_mask: None,
                cache: None,
            });
            tracing::info!(
                pipeline = name,
                glass_compile_ms = started.elapsed().as_secs_f64() * 1000.0,
                "Glass pipeline compiled"
            );
            result
        };
        let over = Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING);
        let pipelines = Pipelines {
            background: pipeline("background", shaders::BACKGROUND, None),
            blur: pipeline("blur", shaders::BLUR, None),
            grid: pipeline("grid", shaders::GRID, over),
            pill: pipeline("pill", shaders::PILL, over),
            haze: pipeline("haze", haze_source(), over),
            rim: pipeline("rim", shaders::RIM, over),
        };
        let slot = u64::from(device.limits().min_uniform_buffer_offset_alignment).max(256);
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass uniforms"),
            size: slot * SLOTS,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("glass"),
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let started = Instant::now();
        let image = image::load_from_memory(include_bytes!("../../../../assets/bg.png"))
            .context("背景图片解码失败")?
            .to_rgba8();
        tracing::info!(
            decode_ms = started.elapsed().as_secs_f64() * 1000.0,
            "Background image decoded"
        );
        let (w, h) = image.dimensions();
        let background = Bound::new(
            &device,
            &layout,
            &sampler,
            &uniforms,
            slot,
            [w, h],
            wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        );
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &background.texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            image.as_raw(),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(4 * w),
                rows_per_image: Some(h),
            },
            extent([w, h]),
        );
        Ok(Self {
            metrics,
            device,
            queue,
            layout,
            sampler,
            pipelines,
            uniforms,
            slot,
            background,
            image_size: [w as f32, h as f32],
            live_background: None,
            cache: None,
        })
    }
    fn target(&self, size: [u32; 2]) -> Bound {
        Bound::new(
            &self.device,
            &self.layout,
            &self.sampler,
            &self.uniforms,
            self.slot,
            size,
            wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::RENDER_ATTACHMENT,
        )
    }
    /// Draws `draws` into one cleared target each (`targets[i]` receives `batch.draws[i]`).
    fn submit_passes(&self, batch: &Batch<'_>, targets: &[&wgpu::TextureView]) {
        self.queue.write_buffer(&self.uniforms, 0, &batch.bytes);
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("glass"),
            });
        for (draw, target) in batch.draws.iter().zip(targets) {
            let mut pass = begin_pass(&mut encoder, target);
            draw.record(&mut pass);
        }
        self.queue.submit([encoder.finish()]);
    }
    fn build_cache(&self, f: &Frame) -> Result<Cache> {
        let size = [f.width as u32, f.height as u32];
        let readback = if cfg!(test) {
            wgpu::TextureUsages::COPY_SRC
        } else {
            wgpu::TextureUsages::empty()
        };
        let backdrop = self.device.create_texture(&texture_descriptor(
            size,
            wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::RENDER_ATTACHMENT
                | readback,
        ));
        if let Some(source) = &self.live_background {
            let half = self.target([
                source.texture.width().div_ceil(2),
                source.texture.height().div_ceil(2),
            ]);
            let quarter = self.target([
                source.texture.width().div_ceil(4),
                source.texture.height().div_ceil(4),
            ]);
            let sizes = [
                [quarter.texture.width(), quarter.texture.height()],
                [quarter.texture.width(), quarter.texture.height()],
                [half.texture.width(), half.texture.height()],
            ];
            let cover = content_cover_scale(f, self.image_size);
            let cache = Cache {
                key: (size[0], size[1], f.scale.to_bits()),
                backdrop_view: backdrop.create_view(&Default::default()),
                backdrop,
                frosts: sizes.map(|s| self.target(s)).into(),
                live: Some(LiveBlur {
                    half,
                    quarter,
                    horizontals: sizes.map(|s| self.target(s)),
                    sigmas: [
                        uniforms::tile(f.scale).frost / cover / 4.0,
                        (20.0 * f.scale * 0.57735 + 0.5) / cover / 4.0,
                        3.0 * f.scale / cover / 2.0,
                    ],
                }),
            };
            let live = cache.live.as_ref().unwrap();
            let bytes = [&live.half, &live.quarter]
                .into_iter()
                .chain(live.horizontals.iter())
                .chain(cache.frosts.iter())
                .map(|b| u64::from(b.texture.width()) * u64::from(b.texture.height()) * 4)
                .sum();
            crate::scene::diagnostics::resource(
                "glass live retained downsample+horizontal+frost",
                bytes,
            );
            self.refresh_live(&cache)?;
            return Ok(cache);
        }
        // Blur in background image space: the cover crop is a uniform scale, so blurring the source image
        // by sigma / cover_scale equals blurring the cropped frame, at a fraction of the texture size.
        let cover_scale = content_cover_scale(f, self.image_size);
        // Tile frost 8dp, haze 20dp (radius to sigma), selected pill 3dp, in FROST_* order.
        let sigmas = [
            uniforms::tile(f.scale).frost,
            20.0 * f.scale * 0.57735 + 0.5,
            3.0 * f.scale,
        ]
        .map(|sigma| sigma / cover_scale);
        // A wide blur loses nothing at half resolution and needs a quarter of the memory.
        let downs = sigmas.map(|sigma| if sigma >= 4.0 { 2 } else { 1 });
        let image = self.image_size.map(|v| v as u32);
        let blur_sizes = downs.map(|d| [image[0].div_ceil(d), image[1].div_ceil(d)]);
        // The horizontal passes are dropped after submit; wgpu keeps them alive until the GPU is done.
        let horizontals = blur_sizes.map(|s| self.target(s));
        let frosts = blur_sizes.map(|s| self.target(s));
        let mut writer = UniformWriter::default();
        let mut batch = Batch::new(self.slot);
        let mut targets = vec![];
        for i in 0..sigmas.len() {
            let blur_size = blur_sizes[i].map(|v| v as f32);
            let sigma = sigmas[i] / downs[i] as f32;
            for (input, output, direction) in [
                (&self.background.group, &horizontals[i].view, [1.0, 0.0]),
                (&horizontals[i].group, &frosts[i].view, [0.0, 1.0]),
            ] {
                blur_params(&mut writer, blur_size, direction, sigma);
                batch.add(&self.pipelines.blur, input, &writer.finish(), None)?;
                targets.push(output);
            }
        }
        self.submit_passes(&batch, &targets);
        let bytes = frosts
            .iter()
            .map(|b| u64::from(b.texture.width()) * u64::from(b.texture.height()) * 4)
            .sum();
        crate::scene::diagnostics::resource("glass static retained frost", bytes);
        tracing::info!(
            width = size[0],
            height = size[1],
            scale = f.scale,
            "Background blur cache rebuilt"
        );
        Ok(Cache {
            key: (size[0], size[1], f.scale.to_bits()),
            backdrop_view: backdrop.create_view(&Default::default()),
            backdrop,
            frosts: frosts.into(),
            live: None,
        })
    }
    /// Renders background and glass into the backdrop texture before Slint draws the UI.
    /// Returns the image to show when the backdrop texture was (re)created.
    /// Switching source also releases its bind group and all cached frost targets.
    pub fn set_scene(&mut self, texture: Option<&wgpu::Texture>) {
        self.live_background = texture.map(|texture| {
            Bound::from_texture(
                &self.device,
                &self.layout,
                &self.sampler,
                &self.uniforms,
                self.slot,
                texture.clone(),
            )
        });
        let source = self.live_background.as_ref().unwrap_or(&self.background);
        self.image_size = [
            source.texture.width() as f32,
            source.texture.height() as f32,
        ];
        self.cache = None;
    }
    fn refresh_live(&self, cache: &Cache) -> Result<()> {
        let (Some(live), Some(source)) = (&cache.live, &self.live_background) else {
            return Ok(());
        };
        let mut writer = UniformWriter::default();
        let mut batch = Batch::new(self.slot);
        let mut targets = vec![];
        for (input, output) in [
            (&source.group, &live.half),
            (&live.half.group, &live.quarter),
        ] {
            background_params(
                &mut writer,
                [
                    1.0 / output.texture.width() as f32,
                    1.0 / output.texture.height() as f32,
                    0.0,
                    0.0,
                ],
            );
            batch.add(&self.pipelines.background, input, &writer.finish(), None)?;
            targets.push(&output.view);
        }
        for i in 0..3 {
            let input = if i == FROST_SELECTED {
                &live.half
            } else {
                &live.quarter
            };
            let horizontal = &live.horizontals[i];
            let frost = &cache.frosts[i];
            let size = [frost.texture.width() as f32, frost.texture.height() as f32];
            for (group, output, direction) in [
                (&input.group, &horizontal.view, [1.0, 0.0]),
                (&horizontal.group, &frost.view, [0.0, 1.0]),
            ] {
                blur_params(&mut writer, size, direction, live.sigmas[i]);
                batch.add(&self.pipelines.blur, group, &writer.finish(), None)?;
                targets.push(output);
            }
        }
        self.submit_passes(&batch, &targets);
        Ok(())
    }
    pub fn render(&mut self, f: &Frame, scene_changed: bool) -> Result<Option<wgpu::Texture>> {
        if f.width <= 0 || f.height <= 0 {
            return Ok(None);
        }
        let device = self.device.clone();
        validated(&device, || self.render_inner(f, scene_changed))
    }
    fn render_inner(&mut self, f: &Frame, scene_changed: bool) -> Result<Option<wgpu::Texture>> {
        let key = (f.width as u32, f.height as u32, f.scale.to_bits());
        let mut created = None;
        if self.cache.as_ref().map(|c| c.key) != Some(key) {
            // Release the old textures before allocating the new ones.
            self.cache = None;
            let cache = self.build_cache(f)?;
            created = Some(cache.backdrop.clone());
            self.cache = Some(cache);
        }
        let Some(cache) = &self.cache else {
            bail!("glass cache missing");
        };
        if scene_changed && created.is_none() {
            self.refresh_live(cache)?;
        }
        let size = [f.width as u32, f.height as u32];
        let transform = content_transform(f, self.image_size);
        let mut writer = UniformWriter::default();
        let mut batch = Batch::new(self.slot);
        background_params(&mut writer, transform);
        batch.add(
            &self.pipelines.background,
            &self
                .live_background
                .as_ref()
                .unwrap_or(&self.background)
                .group,
            &writer.finish(),
            None,
        )?;
        for grid in &f.grids {
            if grid.count == 0 || grid.opacity <= 0.0 {
                continue;
            }
            // The shader repeats tiles by modulo, so it must be clipped to the grid band.
            let top = (grid.y * f.scale).clamp(0.0, size[1] as f32) as u32;
            let bottom =
                ((grid.y + self.metrics.height) * f.scale).clamp(0.0, size[1] as f32) as u32;
            if bottom <= top {
                continue;
            }
            grid_params(&mut writer, f, transform, grid, self.metrics);
            let frost = &cache.frosts[FROST_TILE].group;
            batch.add(
                &self.pipelines.grid,
                frost,
                &writer.finish(),
                Some([0, top, size[0], bottom - top]),
            )?;
        }
        for pill in &f.pills {
            let g = PillGeometry::new(pill, f.scale);
            if pill.selected {
                pill_params(&mut writer, f, transform, &g);
                let frost = &cache.frosts[FROST_SELECTED].group;
                batch.add(&self.pipelines.pill, frost, &writer.finish(), None)?;
            } else {
                let frost = &cache.frosts[FROST_HAZE].group;
                haze_params(&mut writer, f, transform, &g);
                batch.add(&self.pipelines.haze, frost, &writer.finish(), None)?;
                rim_params(&mut writer, f, &g);
                batch.add(&self.pipelines.rim, frost, &writer.finish(), None)?;
            }
        }
        self.queue.write_buffer(&self.uniforms, 0, &batch.bytes);
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("glass"),
            });
        {
            let mut pass = begin_pass(&mut encoder, &cache.backdrop_view);
            for draw in &batch.draws {
                let [x, y, w, h] = draw.scissor.unwrap_or([0, 0, size[0], size[1]]);
                pass.set_scissor_rect(x, y, w, h);
                draw.record(&mut pass);
            }
        }
        self.queue.submit([encoder.finish()]);
        Ok(created)
    }
}
impl Draw<'_> {
    fn record(&self, pass: &mut wgpu::RenderPass<'_>) {
        pass.set_pipeline(self.pipeline);
        pass.set_bind_group(0, self.group, &[self.offset]);
        pass.draw(0..3, 0..1);
    }
}
fn begin_pass<'e>(
    encoder: &'e mut wgpu::CommandEncoder,
    target: &wgpu::TextureView,
) -> wgpu::RenderPass<'e> {
    encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
        label: Some("glass"),
        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
            view: target,
            depth_slice: None,
            resolve_target: None,
            ops: wgpu::Operations {
                load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                store: wgpu::StoreOp::Store,
            },
        })],
        depth_stencil_attachment: None,
        timestamp_writes: None,
        occlusion_query_set: None,
        multiview_mask: None,
    })
}
fn extent([width, height]: [u32; 2]) -> wgpu::Extent3d {
    wgpu::Extent3d {
        width,
        height,
        depth_or_array_layers: 1,
    }
}
fn texture_descriptor(
    size: [u32; 2],
    usage: wgpu::TextureUsages,
) -> wgpu::TextureDescriptor<'static> {
    wgpu::TextureDescriptor {
        label: Some("glass"),
        size: extent(size),
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: FORMAT,
        usage,
        view_formats: &[],
    }
}
impl Bound {
    fn new(
        device: &wgpu::Device,
        layout: &wgpu::BindGroupLayout,
        sampler: &wgpu::Sampler,
        uniforms: &wgpu::Buffer,
        slot: u64,
        size: [u32; 2],
        usage: wgpu::TextureUsages,
    ) -> Self {
        let texture = device.create_texture(&texture_descriptor(size, usage));
        Self::from_texture(device, layout, sampler, uniforms, slot, texture)
    }
    fn from_texture(
        device: &wgpu::Device,
        layout: &wgpu::BindGroupLayout,
        sampler: &wgpu::Sampler,
        uniforms: &wgpu::Buffer,
        slot: u64,
        texture: wgpu::Texture,
    ) -> Self {
        let view = texture.create_view(&Default::default());
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("glass"),
            layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: uniforms,
                        offset: 0,
                        size: NonZeroU64::new(slot),
                    }),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&view),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::Sampler(sampler),
                },
            ],
        });
        Self {
            texture,
            view,
            group,
        }
    }
}
/// A pill's rect in physical pixels, shrunk toward the press point by the press spring.
struct PillGeometry {
    rect: [f32; 4],
    pivot: [f32; 2],
    progress: f32,
    /// Size factor while pressed (0.95 at full press).
    k: f32,
}
impl PillGeometry {
    fn new(pill: &Pill, scale: f32) -> Self {
        let progress = pill.progress.clamp(0.0, 1.0);
        Self {
            rect: pill.rect.map(|v| v * scale),
            pivot: [
                pill.pivot[0].clamp(0.0, pill.rect[2]) * scale,
                pill.pivot[1].clamp(0.0, pill.rect[3]) * scale,
            ],
            progress,
            k: 1.0 - 0.05 * progress,
        }
    }
    fn origin(&self) -> [f32; 2] {
        [
            self.rect[0] + self.pivot[0] * (1.0 - self.k),
            self.rect[1] + self.pivot[1] * (1.0 - self.k),
        ]
    }
    fn size(&self) -> [f32; 2] {
        [self.rect[2] * self.k, self.rect[3] * self.k]
    }
}
// The functions below write the fields of each shader's `Params` struct in declaration order;
// `tests::params_match_shaders` checks them against the WGSL layouts.
/// Frame pixels per background image pixel when the image covers the frame (ContentScale.Crop).
fn content_cover_scale(f: &Frame, image_size: [f32; 2]) -> f32 {
    (f.width as f32 / image_size[0]).max(f.height as f32 / image_size[1])
}
/// Maps frame pixels to background image uv for the cover crop: uv = p * xy + zw.
fn content_transform(f: &Frame, image_size: [f32; 2]) -> [f32; 4] {
    let size = [f.width as f32, f.height as f32];
    let scale = content_cover_scale(f, image_size);
    let crop = [0, 1].map(|i| size[i] / (image_size[i] * scale));
    [
        crop[0] / size[0],
        crop[1] / size[1],
        0.5 - 0.5 * crop[0],
        0.5 - 0.5 * crop[1],
    ]
}
fn background_params(w: &mut UniformWriter, transform: [f32; 4]) {
    w.vec4(transform);
}
fn blur_params(w: &mut UniformWriter, size: [f32; 2], direction: [f32; 2], sigma: f32) {
    w.vec2(size).vec2(direction).f32(sigma);
}
fn grid_params(
    w: &mut UniformWriter,
    f: &Frame,
    transform: [f32; 4],
    grid: &Grid,
    metrics: GridMetrics,
) {
    let s = f.scale;
    let t = uniforms::tile(s);
    w.vec4([1.0, 1.0, 1.0, 0.2]) // tint
        .vec4([1.0, 1.0, 1.0, 0.7]) // hairline
        .vec4(transform)
        .vec2([
            (metrics.padding + grid.scroll + grid.x) * s,
            (grid.y + metrics.padding) * s,
        ]) // origin
        .vec2([metrics.pitch * s; 2])
        .vec2([metrics.tile * s; 2])
        .vec2(t.light)
        .vec2(t.shadow)
        .vec2([3.0 * s, 6.0 * s]) // shadow_offset_y
        .f32(metrics.rows as f32)
        .f32(grid.count as f32)
        .f32(t.radius)
        .f32(t.bevel)
        .f32(t.taper)
        .f32(1.5) // refractive_index
        .f32(1.0) // specular_intensity
        .f32(t.shininess)
        .f32(s) // hairline_width
        .f32(grid.hover as f32)
        .f32(grid.hover_progress.clamp(0.0, 1.0))
        .f32(0.8) // hover_specular_boost
        .f32(grid.pressed as f32)
        .f32(grid.press_scale)
        .f32(0.18) // shadow_alpha
        .f32(t.edge)
        .f32(0.16) // edge_shadow_alpha
        .f32(grid.opacity);
}
fn pill_params(w: &mut UniformWriter, f: &Frame, transform: [f32; 4], g: &PillGeometry) {
    let s = f.scale;
    let t = uniforms::tile(s);
    let [x, y, width, height] = g.rect;
    let [px, py] = g.pivot;
    w.vec4([
        x + px * (1.0 - g.k),
        y + py * (1.0 - g.k),
        x + px + (width - px) * g.k,
        y + py + (height - py) * g.k,
    ]) // shape_rect
    .vec4([1.0, 1.0, 1.0, 0.35]) // tint
    .vec4([1.0, 1.0, 1.0, 0.1]) // hairline
    .vec4(transform)
    .vec2(t.light)
    .vec2([x + px, y + py]) // glow_center
    .f32(height * 0.5 * g.k) // corner_radius
    .f32(13.0 * s * g.k) // bevel
    .f32((8.0 * s).min(13.0 * s * g.k * 0.45)) // taper
    .f32(1.5) // refractive_index
    .f32(1.0) // specular_intensity
    .f32(t.shininess)
    .f32(s) // hairline_width
    .f32(height * 0.8) // glow_radius
    .f32(0.5 * g.progress); // glow_intensity
}
fn haze_params(w: &mut UniformWriter, f: &Frame, transform: [f32; 4], g: &PillGeometry) {
    let s = f.scale;
    let height = g.rect[3];
    w.vec4([height * 0.5 * g.k; 4]) // corner_radii
        .vec4([0.98, 0.97, 1.0, 0.35]) // tint_color
        .vec4(transform)
        .vec2([f.width as f32, f.height as f32]) // sample_size
        .vec2(g.origin())
        .vec2(g.size())
        .f32(2.0) // sample_step
        .f32(0.7) // refraction_strength
        .f32(0.0) // refraction_fold_strength
        .f32(0.15) // ambient_response
        .f32(s) // edge_softness
        .f32(height * 0.15) // refraction_height
        .f32(20.0 * s) // edge_refraction_width
        .f32(0.0) // chromatic_aberration_strength
        .f32(0.0) // surface_profile
        .f32(0.0) // chromatic_aberration_mode
        .f32(0.0) // contrast
        .f32(0.55) // white_point
        .f32(1.5) // chroma_multiplier
        .f32(48.0 * s) // refraction_scale
        .f32(0.15) // content_normal_blend
        .f32(3.0); // fresnel_exponent
}
fn rim_params(w: &mut UniformWriter, f: &Frame, g: &PillGeometry) {
    let [_, _, width, height] = g.rect;
    w.vec4([height * 0.5 * g.k; 4]) // corner_radii
        .vec4([0.0, 0.0, 0.0, 0.14]) // edge_shadow
        .vec2(g.origin())
        .vec2(g.size())
        .vec2([width * 0.5, height * 0.5]) // light_position
        .f32(2.0) // sample_step
        .f32(0.48) // specular_intensity
        .f32(24.0) // specular_exponent
        .f32(f.scale); // edge_softness
}
#[cfg(test)]
mod tests {
    fn test_metrics() -> super::GridMetrics {
        super::GridMetrics {
            tile: 126.,
            pitch: 138.,
            padding: 16.,
            rows: 3,
            height: 434.,
        }
    }
    use super::*;
    fn frame(width: i32, height: i32) -> Frame {
        Frame {
            width,
            height,
            scale: 1.0,
            grids: vec![],
            pills: vec![],
        }
    }
    fn uv(t: [f32; 4], p: [f32; 2]) -> [f32; 2] {
        [p[0] * t[0] + t[2], p[1] * t[1] + t[3]]
    }
    #[test]
    fn content_transform_covers_the_frame() {
        // Same aspect: the image exactly fills the frame.
        let t = content_transform(&frame(200, 100), [400.0, 200.0]);
        assert_eq!(uv(t, [0.0, 0.0]), [0.0, 0.0]);
        assert_eq!(uv(t, [200.0, 100.0]), [1.0, 1.0]);
        // Wider frame: full width, a centred band of the height.
        let t = content_transform(&frame(400, 100), [400.0, 200.0]);
        let [l, top] = uv(t, [0.0, 0.0]);
        let [r, bottom] = uv(t, [400.0, 100.0]);
        assert_eq!([l, r], [0.0, 1.0]);
        assert!((top - 0.25).abs() < 1e-6 && (bottom - 0.75).abs() < 1e-6);
        assert_eq!(content_cover_scale(&frame(400, 100), [400.0, 200.0]), 1.0);
    }
    /// Renders a fixed frame that exercises every glass pipeline and saves it, so that two shader
    /// compilers can be compared. Compare release test builds: debug builds skip DXC optimization.
    #[test]
    #[ignore = "needs a GPU and GLASS_CAPTURE_PATH"]
    fn capture_fixed_frame() {
        let path = std::env::var("GLASS_CAPTURE_PATH").expect("GLASS_CAPTURE_PATH");
        let scale: f32 = std::env::var("GLASS_CAPTURE_SCALE")
            .map_or(2.0, |value| value.parse().expect("GLASS_CAPTURE_SCALE"));
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::DX12,
            flags: wgpu::InstanceFlags::from_build_config().with_env(),
            backend_options: wgpu::BackendOptions::from_env_or_default(),
            memory_budget_thresholds: wgpu::MemoryBudgetThresholds::default(),
            display: None,
        });
        let adapter =
            pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default()))
                .expect("adapter");
        let (device, queue) =
            pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default()))
                .expect("device");
        let mut renderer =
            Renderer::new(device.clone(), queue.clone(), test_metrics()).expect("renderer");
        let grid = |y: f32, hover: i32, pressed: i32| Grid {
            y,
            x: 0.0,
            scroll: 37.0,
            count: 24,
            opacity: 1.0,
            hover,
            hover_progress: 0.6,
            pressed,
            press_scale: 0.97,
        };
        let pill = |x: f32, selected: bool, progress: f32| Pill {
            rect: [x, 10.0, 140.0, 44.0],
            selected,
            progress,
            pivot: [x + 40.0, 30.0],
        };
        let frame = Frame {
            width: (1280.0 * scale) as i32,
            height: (720.0 * scale) as i32,
            scale,
            grids: vec![grid(163.0, 2, -1), grid(420.0, -1, 1)],
            pills: vec![
                pill(10.0, true, 0.5),
                pill(170.0, false, 0.5),
                pill(330.0, false, 1.0),
                // Near square: the refraction field differs from the long buttons.
                Pill {
                    rect: [490.0, 10.0, 60.0, 60.0],
                    selected: false,
                    progress: 0.5,
                    pivot: [520.0, 40.0],
                },
            ],
        };
        renderer.render(&frame, false).expect("render");
        let texture = &renderer.cache.as_ref().expect("cache").backdrop;
        let row = 4 * frame.width as u32;
        let aligned = row.next_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
        let buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("glass capture"),
            size: u64::from(aligned) * frame.height as u64,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            texture.as_image_copy(),
            wgpu::TexelCopyBufferInfo {
                buffer: &buffer,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(aligned),
                    rows_per_image: Some(frame.height as u32),
                },
            },
            texture.size(),
        );
        queue.submit([encoder.finish()]);
        buffer.map_async(wgpu::MapMode::Read, .., |result| {
            result.expect("map capture");
        });
        device
            .poll(wgpu::PollType::wait_indefinitely())
            .expect("poll");
        let mapped = buffer.get_mapped_range(..).expect("mapped capture");
        let mut pixels = Vec::with_capacity((row * frame.height as u32) as usize);
        for line in mapped.chunks_exact(aligned as usize) {
            pixels.extend_from_slice(&line[..row as usize]);
        }
        image::save_buffer(
            &path,
            &pixels,
            frame.width as u32,
            frame.height as u32,
            image::ExtendedColorType::Rgba8,
        )
        .expect("save capture");
    }
    #[test]
    fn params_match_shaders() {
        let frame = Frame {
            width: 2560,
            height: 1440,
            scale: 2.0,
            grids: vec![],
            pills: vec![],
        };
        let grid = Grid {
            y: 163.0,
            x: 0.0,
            scroll: 0.0,
            count: 10,
            opacity: 1.0,
            hover: -1,
            hover_progress: 0.0,
            pressed: -1,
            press_scale: 1.0,
        };
        let pill = PillGeometry::new(
            &Pill {
                rect: [10.0, 10.0, 100.0, 36.0],
                selected: false,
                progress: 0.5,
                pivot: [20.0, 10.0],
            },
            2.0,
        );
        type Write<'a> = Box<dyn Fn(&mut UniformWriter) + 'a>;
        let writers: [(&str, &str, Write); 6] = [
            (
                "background",
                shaders::BACKGROUND,
                Box::new(|w| background_params(w, [1.0; 4])),
            ),
            (
                "blur",
                shaders::BLUR,
                Box::new(|w| blur_params(w, [1.0, 1.0], [1.0, 0.0], 2.0)),
            ),
            (
                "grid",
                shaders::GRID,
                Box::new(|w| grid_params(w, &frame, [1.0; 4], &grid, test_metrics())),
            ),
            (
                "pill",
                shaders::PILL,
                Box::new(|w| pill_params(w, &frame, [1.0; 4], &pill)),
            ),
            (
                "haze",
                shaders::HAZE,
                Box::new(|w| haze_params(w, &frame, [1.0; 4], &pill)),
            ),
            (
                "rim",
                shaders::RIM,
                Box::new(|w| rim_params(w, &frame, &pill)),
            ),
        ];
        for (name, source, write) in writers {
            let (offsets, span) = shaders::tests::params_layout(source);
            let mut w = UniformWriter::default();
            write(&mut w);
            assert_eq!(w.offsets(), offsets.as_slice(), "{name} field offsets");
            assert_eq!(w.finish().len() as u32, span, "{name} size");
            assert!(u64::from(span) <= 256, "{name} exceeds one uniform slot");
        }
    }
}
