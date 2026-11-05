//! Golden-valley P3: accepted geometry/water/clouds with valley fog and complete post.
pub mod clouds;
pub mod crowd;
pub mod crowd_sim;
pub mod diagnostics;
pub mod fog;
pub mod geometry;
pub mod loader;
pub mod mesh;
pub mod perf;
pub mod player;
pub mod player_geometry;
mod post;
pub mod quantize;
pub mod shadow;
#[path = "scene.rs"]
pub mod state;
pub mod surface;
pub mod terrain;
mod textures;
mod upload;
pub mod vegetation;
pub mod village;
mod water;
use anyhow::{Context, Result};
use mesh::{DrawRange, Material};
use state::{HEIGHT, Params, WIDTH};
#[derive(Clone, Copy, Debug)]
pub struct CameraPose {
    pub eye: glam::Vec3,
    pub target: glam::Vec3,
}
#[derive(Clone, Copy, Debug)]
pub struct FrameInput {
    pub seconds: f64,
    pub camera: CameraPose,
}
impl FrameInput {
    pub fn at(seconds: f64) -> Self {
        let (eye, target) = state::camera_pose(seconds);
        Self {
            seconds,
            camera: CameraPose { eye, target },
        }
    }
}
use std::{sync::mpsc, time::Instant};
const PACKED: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("geometry_shared.wgsl"),
    include_str!("vertex_packed.wgsl"),
    include_str!("material.wgsl")
);
const FULL: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("geometry_shared.wgsl"),
    include_str!("vertex_full.wgsl"),
    include_str!("material.wgsl")
);
const SKY: &str = concat!(include_str!("common.wgsl"), include_str!("sky.wgsl"));
const SUN: &str = concat!(include_str!("common.wgsl"), include_str!("sun.wgsl"));
const POST: &str = concat!(include_str!("common.wgsl"), include_str!("post.wgsl"));
const HDR: wgpu::TextureFormat = wgpu::TextureFormat::Rgba16Float;
/// Scopes belong to this thread in wgpu30; scene errors must not escape into Slint's handler.
pub fn checked<T>(device: &wgpu::Device, body: impl FnOnce() -> Result<T>) -> Result<T> {
    let internal = device.push_error_scope(wgpu::ErrorFilter::Internal);
    let memory = device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
    let validation = device.push_error_scope(wgpu::ErrorFilter::Validation);
    let result = body();
    let errors = [
        pollster::block_on(validation.pop()),
        pollster::block_on(memory.pop()),
        pollster::block_on(internal.pop()),
    ];
    if let Some(error) = errors.into_iter().flatten().next() {
        return Err(anyhow::anyhow!("Scene wgpu: {error}"));
    }
    result
}
struct Targets {
    msaa: Option<wgpu::TextureView>,
    depth: wgpu::TextureView,
    hdr: wgpu::TextureView,
    output: wgpu::Texture,
    output_view: wgpu::TextureView,
    post_group: wgpu::BindGroup,
    water: Option<water::Targets>,
    post: Option<post::Targets>,
}
impl Targets {
    fn new(
        device: &wgpu::Device,
        layout: &wgpu::BindGroupLayout,
        uniforms: &wgpu::Buffer,
        samples: u32,
        water: Option<&water::Water>,
        post: Option<&post::Post>,
        shadow: &wgpu::TextureView,
    ) -> Self {
        let target = |format: wgpu::TextureFormat, count: u32, label: &str, bpp: u64| {
            diagnostics::resource(label, WIDTH as u64 * HEIGHT as u64 * count as u64 * bpp);
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d {
                    width: WIDTH,
                    height: HEIGHT,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: count,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT
                    | wgpu::TextureUsages::TEXTURE_BINDING
                    | if count == 1 {
                        wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_SRC
                    } else {
                        wgpu::TextureUsages::empty()
                    },
                view_formats: &[],
            })
        };
        let msaa = if samples > 1 {
            Some(target(HDR, samples, "scene MSAA", 8).create_view(&Default::default()))
        } else {
            None
        };
        let depth = target(wgpu::TextureFormat::Depth32Float, samples, "scene depth", 4)
            .create_view(&Default::default());
        let hdr = target(HDR, 1, "scene HDR", 8).create_view(&Default::default());
        let output = target(wgpu::TextureFormat::Rgba8Unorm, 1, "scene output", 4);
        let output_view = output.create_view(&Default::default());
        let post_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene post"),
            layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: uniforms,
                        offset: 0,
                        size: std::num::NonZeroU64::new(std::mem::size_of::<Params>() as u64),
                    }),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&hdr),
                },
            ],
        });
        let water = water.map(|w| w.targets(device, &depth));
        let post = post.map(|p| {
            p.targets(
                device,
                &hdr,
                &water
                    .as_ref()
                    .expect("P3 needs pre-water depth")
                    .scene_depth_view,
                shadow,
            )
        });
        Self {
            msaa,
            depth,
            hdr,
            output,
            output_view,
            post_group,
            water,
            post,
        }
    }
}
struct GpuMesh {
    vertices: wgpu::Buffer,
    indices: wgpu::Buffer,
    ranges: Vec<DrawRange>,
    format: wgpu::IndexFormat,
    batch_group: wgpu::BindGroup,
    batch_slot: u32,
}
impl GpuMesh {
    fn upload(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        mesh: mesh::Mesh,
        layout: &wgpu::BindGroupLayout,
        options: diagnostics::Options,
    ) -> Result<Self> {
        let (vertices, indices, ranges, format) = if options.packed {
            let (packed, indices, ranges) = mesh::pack_mesh(&mesh)?;
            (
                upload::buffer(
                    device,
                    queue,
                    "scene vertices",
                    bytemuck::cast_slice(&packed),
                    wgpu::BufferUsages::VERTEX,
                    options.chunk_upload,
                )?,
                upload::buffer(
                    device,
                    queue,
                    "scene indices",
                    bytemuck::cast_slice(&indices),
                    wgpu::BufferUsages::INDEX,
                    options.chunk_upload,
                )?,
                ranges,
                wgpu::IndexFormat::Uint16,
            )
        } else {
            let ranges = mesh
                .batches
                .iter()
                .filter(|b| b.count > 0)
                .map(|b| DrawRange {
                    first: b.first,
                    count: b.count,
                    base: 0,
                    origin: [0.0; 3],
                    material: b.material,
                })
                .collect();
            (
                upload::buffer(
                    device,
                    queue,
                    "scene vertices",
                    bytemuck::cast_slice(&mesh.vertices),
                    wgpu::BufferUsages::VERTEX,
                    options.chunk_upload,
                )?,
                upload::buffer(
                    device,
                    queue,
                    "scene indices",
                    bytemuck::cast_slice(&mesh.indices),
                    wgpu::BufferUsages::INDEX,
                    options.chunk_upload,
                )?,
                ranges,
                wgpu::IndexFormat::Uint32,
            )
        };
        let slot = device.limits().min_uniform_buffer_offset_alignment.max(32);
        let mut bytes = vec![0; ranges.len() * slot as usize];
        for (i, r) in ranges.iter().enumerate() {
            let p = mesh::BatchParams {
                origin: [r.origin[0], r.origin[1], r.origin[2], 0.0],
                traits: [
                    r.material.cutout() as u8 as f32,
                    r.material.double_sided() as u8 as f32,
                    r.material.emissive() as u8 as f32,
                    r.material.v_scale(),
                ],
            };
            bytes[i * slot as usize..i * slot as usize + 32]
                .copy_from_slice(bytemuck::bytes_of(&p));
        }
        let origins = upload::buffer(
            device,
            queue,
            "scene local batch origins and traits",
            &bytes,
            wgpu::BufferUsages::UNIFORM,
            options.chunk_upload,
        )?;
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene batches"),
            layout,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                    buffer: &origins,
                    offset: 0,
                    size: std::num::NonZeroU64::new(32),
                }),
            }],
        });
        Ok(Self {
            vertices,
            indices,
            ranges,
            format,
            batch_group: group,
            batch_slot: slot,
        })
    }
    fn order(&self) -> Vec<usize> {
        let mut order: Vec<_> = (0..self.ranges.len()).collect();
        order.sort_by_key(|&i| !self.ranges[i].material.vegetation());
        order
    }
}
struct ReflectionPipelines {
    solid: wgpu::RenderPipeline,
    plants: wgpu::RenderPipeline,
    sky: wgpu::RenderPipeline,
    sun: wgpu::RenderPipeline,
}
pub struct Renderer {
    device: wgpu::Device,
    queue: wgpu::Queue,
    options: diagnostics::Options,
    mesh: Option<GpuMesh>,
    draw_order: Vec<usize>,
    uniforms: wgpu::Buffer,
    frame_group: wgpu::BindGroup,
    solid: wgpu::RenderPipeline,
    plants: wgpu::RenderPipeline,
    sky: wgpu::RenderPipeline,
    sun: wgpu::RenderPipeline,
    post: wgpu::RenderPipeline,
    shadow_pipeline: wgpu::RenderPipeline,
    materials: Vec<wgpu::BindGroup>,
    plain_materials: Vec<wgpu::BindGroup>,
    shadow_texture: wgpu::Texture,
    shadow_view: wgpu::TextureView,
    shadow_frame: shadow::Frame,
    post_layout: wgpu::BindGroupLayout,
    targets: Option<Targets>,
    complete_tx: mpsc::Sender<std::time::Duration>,
    complete_rx: mpsc::Receiver<std::time::Duration>,
    in_flight: bool,
    discard_completion: bool,
    monitor: perf::Monitor,
    visual_probe: u32,
    clouds: Option<clouds::Clouds>,
    water: Option<water::Water>,
    reflection: Option<ReflectionPipelines>,
    complete_post: Option<post::Post>,
    player: Option<player::Player>,
    crowd: Option<crowd::Crowd>,
    rendered_frames: u64,
}
impl Renderer {
    pub fn new(device: wgpu::Device, queue: wgpu::Queue) -> Result<Self> {
        Self::with_options(device, queue, Default::default())
    }
    pub fn with_options(
        device: wgpu::Device,
        queue: wgpu::Queue,
        options: diagnostics::Options,
    ) -> Result<Self> {
        checked(&device.clone(), || Self::create(device, queue, options))
    }
    fn create(
        device: wgpu::Device,
        queue: wgpu::Queue,
        options: diagnostics::Options,
    ) -> Result<Self> {
        if options.delay_ms > 0 {
            std::thread::sleep(std::time::Duration::from_millis(options.delay_ms));
        }
        options.checkpoint(&device, "scene-entry");
        let grid = terrain::Grid::new();
        let shadow_frame = shadow::frame(&grid);
        drop(grid);
        let cpu = if options.pipelines_only {
            None
        } else {
            let start = Instant::now();
            let (mesh, vegetation, terrain) = geometry::build(true)?;
            tracing::info!(?vegetation,?terrain,vertices=mesh.vertices.len(),indices=mesh.indices.len(),build_ms=start.elapsed().as_millis(),displacement=%mesh.displacement.report(),"P1 quantized geometry built");
            Some(mesh)
        };
        options.checkpoint(&device, "mesh-cpu");
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("scene uniforms"),
            size: (state::FRAME_STRIDE * 2) as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let uniform_entry = |size| wgpu::BindGroupLayoutEntry {
            binding: 0,
            visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
            ty: wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Uniform,
                has_dynamic_offset: true,
                min_binding_size: std::num::NonZeroU64::new(size),
            },
            count: None,
        };
        let frame_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene frame"),
            entries: &[uniform_entry(std::mem::size_of::<Params>() as u64)],
        });
        let batch_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene batches"),
            entries: &[uniform_entry(32)],
        });
        let frame_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene frame"),
            layout: &frame_layout,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                    buffer: &uniforms,
                    offset: 0,
                    size: std::num::NonZeroU64::new(std::mem::size_of::<Params>() as u64),
                }),
            }],
        });
        let mesh = cpu
            .map(|m| GpuMesh::upload(&device, &queue, m, &batch_layout, options))
            .transpose()?;
        let draw_order = mesh.as_ref().map_or_else(Vec::new, GpuMesh::order);
        options.checkpoint(&device, "upload-before-wait");
        if options.probe {
            queue.submit([]);
            device.poll(wgpu::PollType::wait_indefinitely())?;
        }
        options.checkpoint(&device, "upload-after-wait");
        let shadow_size = if options.pipelines_only { 1 } else { 2048 };
        diagnostics::resource(
            "scene static shadow D32",
            shadow_size as u64 * shadow_size as u64 * 4,
        );
        let shadow_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("scene static shadow D32"),
            size: wgpu::Extent3d {
                width: shadow_size,
                height: shadow_size,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Depth32Float,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT
                | wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let shadow_view = shadow_texture.create_view(&Default::default());
        let texture_entry = |binding, sample_type| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Texture {
                sample_type,
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };
        let sampler_entry = |binding, ty| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Sampler(ty),
            count: None,
        };
        let plain_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene texture"),
            entries: &[
                texture_entry(0, wgpu::TextureSampleType::Float { filterable: true }),
                sampler_entry(1, wgpu::SamplerBindingType::Filtering),
            ],
        });
        let material_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene texture and shadow"),
            entries: &[
                texture_entry(0, wgpu::TextureSampleType::Float { filterable: true }),
                sampler_entry(1, wgpu::SamplerBindingType::Filtering),
                texture_entry(2, wgpu::TextureSampleType::Depth),
                sampler_entry(3, wgpu::SamplerBindingType::Comparison),
                sampler_entry(4, wgpu::SamplerBindingType::Filtering),
            ],
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene anisotropic8"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            anisotropy_clamp: 8,
            address_mode_u: wgpu::AddressMode::Repeat,
            address_mode_v: wgpu::AddressMode::Repeat,
            ..Default::default()
        });
        // WebGPU requires linear magnification with anisotropy. Use a second sampler
        // for magnified footprints to retain the reference GL_NEAREST cutout edges.
        let magnification = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene reference nearest magnification"),
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            address_mode_u: wgpu::AddressMode::Repeat,
            address_mode_v: wgpu::AddressMode::Repeat,
            ..Default::default()
        });
        let sun_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene reference nearest sun"),
            ..Default::default()
        });
        let comparison = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene shadow comparison"),
            compare: Some(wgpu::CompareFunction::LessEqual),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let mut views = Vec::new();
        for m in Material::ALL {
            views.push(textures::upload(
                &device,
                &queue,
                textures::bytes(m),
                &format!("scene {m:?} SRGB"),
            )?);
        }
        views.push(textures::upload(
            &device,
            &queue,
            textures::SUN,
            "scene sun SRGB",
        )?);
        let mut materials = vec![];
        let mut plain_materials = vec![];
        for (i, view) in views.iter().enumerate() {
            let plain_entries = [
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(view),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(if i == 20 {
                        &sun_sampler
                    } else {
                        &magnification
                    }),
                },
            ];
            plain_materials.push(device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("scene plain material"),
                layout: &plain_layout,
                entries: &plain_entries,
            }));
            let entries = [
                plain_entries[0].clone(),
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&sampler),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::TextureView(&shadow_view),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: wgpu::BindingResource::Sampler(&comparison),
                },
                wgpu::BindGroupEntry {
                    binding: 4,
                    resource: wgpu::BindingResource::Sampler(&magnification),
                },
            ];
            materials.push(device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("scene shadowed material"),
                layout: &material_layout,
                entries: &entries,
            }));
        }
        let post_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene post"),
            entries: &[
                uniform_entry(std::mem::size_of::<Params>() as u64),
                texture_entry(1, wgpu::TextureSampleType::Float { filterable: true }),
            ],
        });
        let water = if options.p1_only || options.pipelines_only {
            None
        } else {
            Some(water::Water::new(
                &device,
                &queue,
                &frame_layout,
                options.samples,
            )?)
        };
        let clouds = if options.p1_only || options.pipelines_only {
            None
        } else {
            Some(clouds::Clouds::new(
                &device,
                &queue,
                &frame_layout,
                options.samples,
            )?)
        };
        let complete_post = if options.p1_only || options.p2_only || options.pipelines_only {
            None
        } else {
            Some(post::Post::new(
                &device,
                &queue,
                &frame_layout,
                options.skip_post,
            ))
        };
        let player =
            if options.p1_only || options.p2_only || options.p3_only || options.pipelines_only {
                None
            } else {
                Some(player::Player::new(
                    &device,
                    &queue,
                    &frame_layout,
                    options.samples,
                    options.reflection_first,
                )?)
            };
        let crowd = match &player {
            Some(player) if !options.no_crowd => {
                let (vertices, meshes) = player.mesh();
                Some(crowd::Crowd::new(
                    &device,
                    &queue,
                    &frame_layout,
                    options.samples,
                    vertices,
                    meshes,
                )?)
            }
            _ => None,
        };
        let targets = if options.lazy_targets || options.pipelines_only {
            None
        } else {
            Some(Targets::new(
                &device,
                &post_layout,
                &uniforms,
                options.samples,
                water.as_ref(),
                complete_post.as_ref(),
                &shadow_view,
            ))
        };
        options.checkpoint(&device, "targets-before-pipelines");
        // Each pipeline is compiled on this loader thread and timed independently.
        let make = |name: &str,
                    source: &str,
                    vertex: &str,
                    fragment: &str,
                    kind: u8,
                    cull: Option<wgpu::Face>,
                    samples: u32| {
            let start = Instant::now();
            let source = if options.legacy_sampler {
                source.replace(
                    "const LEGACY_SAMPLER:bool=false;",
                    "const LEGACY_SAMPLER:bool=true;",
                )
            } else {
                source.to_owned()
            };
            let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some(name),
                source: wgpu::ShaderSource::Wgsl(source.into()),
            });
            let layouts: Vec<_> = match kind {
                0 => vec![
                    Some(&frame_layout),
                    Some(&batch_layout),
                    Some(&material_layout),
                ],
                1 | 3 => vec![
                    Some(&frame_layout),
                    Some(&batch_layout),
                    Some(&plain_layout),
                ],
                2 => vec![Some(&post_layout)],
                _ => vec![Some(&frame_layout)],
            };
            let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some(name),
                bind_group_layouts: &layouts,
                immediate_size: 0,
            });
            let full = wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x3,2=>Float32x3,3=>Float32,4=>Float32x2,5=>Float32];
            let packed =
                wgpu::vertex_attr_array![0=>Sint16x4,1=>Snorm8x4,2=>Float16x4,3=>Float16x2];
            let buffers = [Some(wgpu::VertexBufferLayout {
                array_stride: if options.packed { 24 } else { 52 },
                step_mode: wgpu::VertexStepMode::Vertex,
                attributes: if options.packed { &packed } else { &full },
            })];
            let targets = [Some(wgpu::ColorTargetState {
                format: if kind == 2 {
                    wgpu::TextureFormat::Rgba8Unorm
                } else {
                    HDR
                },
                blend: if kind == 3 {
                    Some(wgpu::BlendState::ALPHA_BLENDING)
                } else {
                    None
                },
                write_mask: wgpu::ColorWrites::ALL,
            })];
            let p = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(name),
                layout: Some(&layout),
                vertex: wgpu::VertexState {
                    module: &module,
                    entry_point: Some(vertex),
                    compilation_options: Default::default(),
                    buffers: if kind <= 1 { &buffers } else { &[] },
                },
                primitive: wgpu::PrimitiveState {
                    cull_mode: cull,
                    ..Default::default()
                },
                depth_stencil: if kind == 2 {
                    None
                } else {
                    Some(wgpu::DepthStencilState {
                        format: wgpu::TextureFormat::Depth32Float,
                        depth_write_enabled: Some(kind <= 1),
                        depth_compare: Some(wgpu::CompareFunction::LessEqual),
                        stencil: Default::default(),
                        bias: if kind == 1 {
                            wgpu::DepthBiasState {
                                constant: 4,
                                slope_scale: 2.0,
                                clamp: 0.0,
                            }
                        } else {
                            Default::default()
                        },
                    })
                },
                multisample: wgpu::MultisampleState {
                    count: if kind == 1 || kind == 2 { 1 } else { samples },
                    ..Default::default()
                },
                fragment: Some(wgpu::FragmentState {
                    module: &module,
                    entry_point: Some(fragment),
                    compilation_options: Default::default(),
                    targets: if kind == 1 { &[] } else { &targets },
                }),
                multiview_mask: None,
                cache: None,
            });
            tracing::info!(
                pipeline = name,
                compile_ms = start.elapsed().as_secs_f64() * 1000.0,
                "Scene pipeline compiled"
            );
            p
        };
        let shader = if options.packed { PACKED } else { FULL };
        let solid = make(
            "scene solid",
            shader,
            "vs",
            "fs",
            0,
            Some(wgpu::Face::Back),
            options.samples,
        );
        let plants = make(
            "scene double sided cutout",
            shader,
            "vs",
            "fs",
            0,
            None,
            options.samples,
        );
        let shadow_pipeline = make(
            "scene static shadow",
            shader,
            "shadow_vs",
            "shadow_fs",
            1,
            Some(wgpu::Face::Back),
            1,
        );
        let sky = make("scene sky", SKY, "full_vs", "fs", 4, None, options.samples);
        let sun = make("scene sun", SUN, "vs", "fs", 3, None, options.samples);
        let post = make("scene post", POST, "full_vs", "fs", 2, None, 1);
        let reflection = if water.is_some() {
            Some(ReflectionPipelines {
                solid: make(
                    "scene reflection solid",
                    shader,
                    "vs",
                    "fs",
                    0,
                    Some(wgpu::Face::Back),
                    1,
                ),
                plants: make("scene reflection cutout", shader, "vs", "fs", 0, None, 1),
                sky: make("scene reflection sky", SKY, "full_vs", "fs", 4, None, 1),
                sun: make("scene reflection sun", SUN, "vs", "fs", 3, None, 1),
            })
        } else {
            None
        };
        let (complete_tx, complete_rx) = mpsc::channel();
        let mut renderer = Self {
            device,
            queue,
            options,
            mesh,
            draw_order,
            uniforms,
            frame_group,
            solid,
            plants,
            sky,
            sun,
            post,
            shadow_pipeline,
            materials,
            plain_materials,
            shadow_texture,
            shadow_view,
            shadow_frame,
            post_layout,
            targets,
            complete_tx,
            complete_rx,
            in_flight: false,
            discard_completion: false,
            monitor: perf::Monitor::with_threshold(options.low_performance_ms),
            visual_probe: 0,
            water,
            clouds,
            reflection,
            complete_post,
            player,
            crowd,
            rendered_frames: 0,
        };
        anyhow::ensure!(!options.startup_fail, "Diagnostic scene loader failure");
        renderer.bake_shadow()?;
        options.checkpoint(&renderer.device, "pipelines-shadow-ready");
        Ok(renderer)
    }
    fn params(&self, input: FrameInput) -> Params {
        self.pass_params(input, false)
    }
    fn pass_params(&self, input: FrameInput, reflection: bool) -> Params {
        let mut p = Params::for_input(input, reflection);
        p.shadow_vp = self.shadow_frame.vp.to_cols_array_2d();
        p.lighting[1] = self.shadow_frame.depth_range;
        p.lighting[2] = self.visual_probe as f32;
        p
    }
    fn bake_shadow(&mut self) -> Result<()> {
        self.queue.write_buffer(
            &self.uniforms,
            0,
            bytemuck::bytes_of(&self.params(FrameInput::at(0.0))),
        );
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("scene static shadow bake"),
            });
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("scene static shadow bake"),
                color_attachments: &[],
                depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                    view: &self.shadow_view,
                    depth_ops: Some(wgpu::Operations {
                        load: wgpu::LoadOp::Clear(1.0),
                        store: wgpu::StoreOp::Store,
                    }),
                    stencil_ops: None,
                }),
                ..Default::default()
            });
            pass.set_pipeline(&self.shadow_pipeline);
            pass.set_bind_group(0, &self.frame_group, &[0]);
            if let Some(mesh) = &self.mesh {
                pass.set_vertex_buffer(0, mesh.vertices.slice(..));
                pass.set_index_buffer(mesh.indices.slice(..), mesh.format);
                for &i in &self.draw_order {
                    let r = &mesh.ranges[i];
                    if r.material == Material::ShortGrass {
                        continue;
                    }
                    pass.set_bind_group(1, &mesh.batch_group, &[i as u32 * mesh.batch_slot]);
                    pass.set_bind_group(2, &self.plain_materials[r.material as usize], &[]);
                    pass.draw_indexed(r.first..r.first + r.count, r.base, 0..1);
                }
            }
        }
        self.queue.submit([encoder.finish()]);
        self.device
            .poll(wgpu::PollType::wait_indefinitely())
            .context("shadow bake completion")?;
        Ok(())
    }
    pub fn set_player_appearance(&mut self, appearance: player::Appearance) -> Result<()> {
        anyhow::ensure!(
            appearance.skin.pixels.width() == 64
                && [32, 64].contains(&appearance.skin.pixels.height()),
            "Player skin must be normalized before upload"
        );
        anyhow::ensure!(
            appearance
                .cape
                .as_ref()
                .is_none_or(|c| c.dimensions() == (64, 32)),
            "Player cape must be normalized before upload"
        );
        checked(&self.device.clone(), || {
            if let Some(player) = &mut self.player {
                player.appearance(&self.queue, appearance);
            }
            Ok(())
        })
    }
    pub fn set_crowd_skin(&mut self, id: &str, skin: player::Skin) -> Result<()> {
        anyhow::ensure!(
            skin.pixels.width() == 64 && [32, 64].contains(&skin.pixels.height()),
            "Crowd skin must be normalized before upload"
        );
        checked(&self.device.clone(), || {
            if let Some(crowd) = &mut self.crowd {
                crowd.add_skin(&self.queue, id, skin);
            }
            Ok(())
        })
    }
    /// The preview steps the crowd like Kotlin's 60FPS loop before rendering a distant time.
    pub fn advance_crowd_for_preview(&mut self, seconds: f64) {
        if let Some(crowd) = &mut self.crowd {
            crowd.advance_to(seconds);
        }
    }
    pub fn crowd_runners_for_preview(&self) -> Option<&[crowd_sim::Runner]> {
        self.crowd.as_ref().map(crowd::Crowd::runners)
    }
    pub fn pause(&mut self) {
        self.discard_completion = self.in_flight;
    }
    pub fn output(&self) -> &wgpu::Texture {
        &self
            .targets
            .as_ref()
            .expect("output requested before a scene frame")
            .output
    }
    pub fn shadow_texture(&self) -> &wgpu::Texture {
        &self.shadow_texture
    }
    pub fn reflection_texture_for_preview(&self) -> Option<&wgpu::Texture> {
        self.targets.as_ref()?.water.as_ref().map(|t| &t.reflection)
    }
    pub fn scene_depth_for_preview(&self) -> Option<&wgpu::Texture> {
        self.targets
            .as_ref()?
            .water
            .as_ref()
            .map(|t| &t.scene_depth)
    }
    /// Diagnostic readback is performed only by the standalone preview, never by the app.
    pub fn hdr_texture_for_preview(&self) -> &wgpu::Texture {
        self.targets
            .as_ref()
            .expect("render before HDR capture")
            .hdr
            .texture()
    }
    pub fn post_textures_for_preview(&self) -> Option<[&wgpu::Texture; 3]> {
        let p = self.targets.as_ref()?.post.as_ref()?;
        Some([&p.fog, &p.linear, &p.bloom])
    }
    pub fn set_visual_probe_for_preview(&mut self, probe: u32) {
        self.visual_probe = probe;
    }
    pub fn render(&mut self, input: FrameInput) -> Result<bool> {
        let seconds = input.seconds;
        self.device
            .poll(wgpu::PollType::Poll)
            .context("Scene device poll failed")?;
        while let Ok(work) = self.complete_rx.try_recv() {
            self.in_flight = false;
            let ignore = std::mem::take(&mut self.discard_completion);
            anyhow::ensure!(
                ignore || !self.monitor.sample(work),
                "Scene exceeded{}ms average; terminal performance fallback",
                self.options.low_performance_ms
            );
        }
        if self.in_flight || self.options.pipelines_only {
            return Ok(false);
        }
        checked(&self.device.clone(), || {
            if self.options.fail && self.rendered_frames >= 180 {
                // Deliberately exercise this thread's validation scope, not a device-wide loss.
                let _invalid = self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("intentional scene-local validation failure"),
                    size: 16,
                    usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::VERTEX,
                    mapped_at_creation: false,
                });
                return Ok(false);
            }
            if self.targets.is_none() {
                self.targets = Some(Targets::new(
                    &self.device,
                    &self.post_layout,
                    &self.uniforms,
                    self.options.samples,
                    self.water.as_ref(),
                    self.complete_post.as_ref(),
                    &self.shadow_view,
                ));
                diagnostics::snapshot(&self.device, "lazy-targets-first-frame");
            }
            if let Some(clouds) = &mut self.clouds {
                clouds.update(&self.queue, clouds::coordinates(seconds, input.camera.eye))?;
            }
            if let Some(post) = &self.complete_post {
                post.update(&self.queue, seconds);
            }
            if let Some(player) = &self.player {
                player.update(&self.queue, seconds);
            }
            if let Some(crowd) = &mut self.crowd {
                crowd.update(&self.queue, seconds);
            }
            let target = self.targets.as_ref().unwrap();
            let mesh = self.mesh.as_ref().unwrap();
            let start = Instant::now();
            self.queue
                .write_buffer(&self.uniforms, 0, bytemuck::bytes_of(&self.params(input)));
            self.queue.write_buffer(
                &self.uniforms,
                state::FRAME_STRIDE as u64,
                bytemuck::bytes_of(&self.pass_params(input, true)),
            );
            let mut encoder = self
                .device
                .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                    label: Some("scene P3 frame"),
                });
            {
                let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                    label: Some("scene vegetation terrain sky sun"),
                    color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                        view: target.msaa.as_ref().unwrap_or(&target.hdr),
                        depth_slice: None,
                        resolve_target: if self.options.samples > 1 {
                            Some(&target.hdr)
                        } else {
                            None
                        },
                        ops: wgpu::Operations {
                            load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                            store: wgpu::StoreOp::Store,
                        },
                    })],
                    depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                        view: &target.depth,
                        depth_ops: Some(wgpu::Operations {
                            load: wgpu::LoadOp::Clear(1.0),
                            store: wgpu::StoreOp::Store,
                        }),
                        stencil_ops: None,
                    }),
                    ..Default::default()
                });
                pass.set_bind_group(0, &self.frame_group, &[0]);
                pass.set_vertex_buffer(0, mesh.vertices.slice(..));
                pass.set_index_buffer(mesh.indices.slice(..), mesh.format);
                for &i in &self.draw_order {
                    let r = &mesh.ranges[i];
                    pass.set_pipeline(if r.material.double_sided() {
                        &self.plants
                    } else {
                        &self.solid
                    });
                    pass.set_bind_group(1, &mesh.batch_group, &[i as u32 * mesh.batch_slot]);
                    pass.set_bind_group(2, &self.materials[r.material as usize], &[]);
                    pass.draw_indexed(r.first..r.first + r.count, r.base, 0..1);
                }
                pass.set_pipeline(&self.sky);
                pass.set_bind_group(0, &self.frame_group, &[0]);
                pass.draw(0..3, 0..1);
                pass.set_pipeline(&self.sun);
                pass.set_bind_group(0, &self.frame_group, &[0]);
                pass.set_bind_group(2, &self.plain_materials[20], &[]);
                pass.draw(0..6, 0..1);
                if let Some(player) = &self.player {
                    player.draw(&mut pass, &self.frame_group, false);
                }
                if let Some(crowd) = &self.crowd {
                    crowd.draw(&mut pass, &self.frame_group, false);
                }
                if let Some(clouds) = &self.clouds {
                    clouds.draw(&mut pass, &self.frame_group, false);
                }
            }
            if let (Some(water), Some(wt), Some(reflection)) =
                (&self.water, &target.water, &self.reflection)
            {
                water.capture(&mut encoder, wt, &target.hdr, &target.depth);
                {
                    let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                        label: Some("scene planar reflection"),
                        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                            view: &wt.reflection_view,
                            depth_slice: None,
                            resolve_target: None,
                            ops: wgpu::Operations {
                                load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                                store: wgpu::StoreOp::Store,
                            },
                        })],
                        depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                            view: &wt.reflection_depth,
                            depth_ops: Some(wgpu::Operations {
                                load: wgpu::LoadOp::Clear(1.),
                                store: wgpu::StoreOp::Discard,
                            }),
                            stencil_ops: None,
                        }),
                        ..Default::default()
                    });
                    pass.set_bind_group(0, &self.frame_group, &[state::FRAME_STRIDE]);
                    pass.set_vertex_buffer(0, mesh.vertices.slice(..));
                    pass.set_index_buffer(mesh.indices.slice(..), mesh.format);
                    for &i in &self.draw_order {
                        let r = &mesh.ranges[i];
                        pass.set_pipeline(if r.material.double_sided() {
                            &reflection.plants
                        } else {
                            &reflection.solid
                        });
                        pass.set_bind_group(1, &mesh.batch_group, &[i as u32 * mesh.batch_slot]);
                        pass.set_bind_group(2, &self.materials[r.material as usize], &[]);
                        pass.draw_indexed(r.first..r.first + r.count, r.base, 0..1);
                    }
                    pass.set_pipeline(&reflection.sky);
                    pass.set_bind_group(0, &self.frame_group, &[state::FRAME_STRIDE]);
                    pass.draw(0..3, 0..1);
                    pass.set_pipeline(&reflection.sun);
                    pass.set_bind_group(2, &self.plain_materials[20], &[]);
                    pass.draw(0..6, 0..1);
                    if let Some(player) = &self.player {
                        player.draw(&mut pass, &self.frame_group, true);
                    }
                    if let Some(crowd) = &self.crowd {
                        crowd.draw(&mut pass, &self.frame_group, true);
                    }
                    if let Some(clouds) = &self.clouds {
                        clouds.draw(&mut pass, &self.frame_group, true);
                    }
                }
                water.mips(&mut encoder, wt);
                let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                    label: Some("scene water"),
                    color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                        view: target.msaa.as_ref().unwrap_or(&target.hdr),
                        depth_slice: None,
                        resolve_target: if self.options.samples > 1 {
                            Some(&target.hdr)
                        } else {
                            None
                        },
                        ops: wgpu::Operations {
                            load: wgpu::LoadOp::Load,
                            store: wgpu::StoreOp::Store,
                        },
                    })],
                    depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                        view: &target.depth,
                        depth_ops: None,
                        stencil_ops: None,
                    }),
                    ..Default::default()
                });
                water.draw(&mut pass, wt, &self.frame_group);
            }
            if let (Some(post), Some(pt)) = (&self.complete_post, &target.post)
                && !self.options.post_create_only
            {
                post.render(&mut encoder, pt, &self.frame_group, &target.output_view);
            } else {
                let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                    label: Some("scene tonemap"),
                    color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                        view: &target.output_view,
                        depth_slice: None,
                        resolve_target: None,
                        ops: wgpu::Operations {
                            load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                            store: wgpu::StoreOp::Store,
                        },
                    })],
                    ..Default::default()
                });
                pass.set_pipeline(&self.post);
                pass.set_bind_group(0, &target.post_group, &[0]);
                pass.draw(0..3, 0..1);
            }
            let encode = start.elapsed();
            self.queue.submit([encoder.finish()]);
            let submitted = Instant::now();
            let tx = self.complete_tx.clone();
            self.queue.on_submitted_work_done(move || {
                let _ = tx.send(encode + submitted.elapsed());
            });
            self.in_flight = true;
            self.rendered_frames += 1;
            Ok(true)
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shaders_and_uniform_layout() {
        for source in [
            PACKED,
            FULL,
            SKY,
            SUN,
            POST,
            post::FOG,
            post::COPY,
            post::BLOOM,
            post::FINAL,
            player::SOURCE,
            crowd::SOURCE,
            crowd::SHADOW_SOURCE,
            water::SOURCE,
            concat!(include_str!("common.wgsl"), include_str!("clouds.wgsl")),
        ] {
            let module = naga::front::wgsl::parse_str(source)
                .unwrap_or_else(|e| panic!("{}", e.emit_to_string(source)));
            naga::valid::Validator::new(
                naga::valid::ValidationFlags::all(),
                naga::valid::Capabilities::empty(),
            )
            .validate(&module)
            .unwrap_or_else(|e| panic!("{}", e.emit_to_string(source)));
            let (_, ty) = module
                .types
                .iter()
                .find(|(_, t)| t.name.as_deref() == Some("Params"))
                .unwrap();
            if let naga::TypeInner::Struct { members, span } = &ty.inner {
                assert_eq!(*span as usize, std::mem::size_of::<Params>());
                assert_eq!(
                    members
                        .iter()
                        .map(|m| m.offset as usize)
                        .collect::<Vec<_>>(),
                    [
                        std::mem::offset_of!(Params, vp),
                        std::mem::offset_of!(Params, inverse_vp),
                        std::mem::offset_of!(Params, eye),
                        std::mem::offset_of!(Params, sun),
                        std::mem::offset_of!(Params, shadow_vp),
                        std::mem::offset_of!(Params, lighting),
                        std::mem::offset_of!(Params, viewport),
                        std::mem::offset_of!(Params, reflection_vp),
                        std::mem::offset_of!(Params, cloud_vp),
                        std::mem::offset_of!(Params, cloud),
                        std::mem::offset_of!(Params, animation)
                    ]
                );
            }
            if let Some((_, ty)) = module
                .types
                .iter()
                .find(|(_, t)| t.name.as_deref() == Some("PlayerParams"))
                && let naga::TypeInner::Struct { members, span } = &ty.inner
            {
                assert_eq!(
                    *span as usize,
                    std::mem::size_of::<player_geometry::Params>()
                );
                assert_eq!(
                    members
                        .iter()
                        .map(|m| m.offset as usize)
                        .collect::<Vec<_>>(),
                    [
                        std::mem::offset_of!(player_geometry::Params, model),
                        std::mem::offset_of!(player_geometry::Params, bones),
                        std::mem::offset_of!(player_geometry::Params, traits)
                    ]
                );
            }
            if let Some((_, ty)) = module
                .types
                .iter()
                .find(|(_, t)| t.name.as_deref() == Some("Wind"))
                && let naga::TypeInner::Struct { members, span } = &ty.inner
            {
                assert_eq!(*span as usize, std::mem::size_of::<fog::Wind>());
                assert_eq!(
                    members
                        .iter()
                        .map(|m| m.offset as usize)
                        .collect::<Vec<_>>(),
                    [
                        std::mem::offset_of!(fog::Wind, coarse),
                        std::mem::offset_of!(fog::Wind, detail)
                    ]
                );
            }
            if let Some((_, ty)) = module
                .types
                .iter()
                .find(|(_, t)| t.name.as_deref() == Some("BatchParams"))
                && let naga::TypeInner::Struct { members, span } = &ty.inner
            {
                assert_eq!(*span as usize, std::mem::size_of::<mesh::BatchParams>());
                assert_eq!(
                    members
                        .iter()
                        .map(|m| m.offset as usize)
                        .collect::<Vec<_>>(),
                    [
                        std::mem::offset_of!(mesh::BatchParams, origin),
                        std::mem::offset_of!(mesh::BatchParams, traits)
                    ]
                );
            }
        }
    }
}
