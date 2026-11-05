//! Persistent P3 fog/composite/mips/bloom resources. No frame-time texture allocation or readback.
use super::{diagnostics::PostStage, fog, state, water};
use std::time::Instant;

pub const FOG: &str = concat!(include_str!("common.wgsl"), include_str!("fog.wgsl"));
pub const COPY: &str = concat!(include_str!("common.wgsl"), include_str!("hdr_copy.wgsl"));
pub const BLOOM: &str = concat!(include_str!("common.wgsl"), include_str!("bloom.wgsl"));
pub const FINAL: &str = concat!(include_str!("common.wgsl"), include_str!("final.wgsl"));
pub const MIP_LEVELS: u32 = 9; // Bloom samples levels 2..8, never the smaller unused tail.
pub struct Post {
    fog_layout: wgpu::BindGroupLayout,
    copy_layout: wgpu::BindGroupLayout,
    bloom_layout: wgpu::BindGroupLayout,
    final_layout: wgpu::BindGroupLayout,
    mip_layout: wgpu::BindGroupLayout,
    fog_pipeline: Option<wgpu::RenderPipeline>,
    copy_pipeline: Option<wgpu::RenderPipeline>,
    bloom_pipeline: Option<wgpu::RenderPipeline>,
    final_pipeline: Option<wgpu::RenderPipeline>,
    mip_pipeline: Option<wgpu::RenderPipeline>,
    noise: wgpu::TextureView,
    repeat: wgpu::Sampler,
    linear: wgpu::Sampler,
    comparison: wgpu::Sampler,
    wind: wgpu::Buffer,
}
pub struct Targets {
    pub fog: wgpu::Texture,
    pub linear: wgpu::Texture,
    pub bloom: wgpu::Texture,
    fog_view: wgpu::TextureView,
    linear_views: Vec<wgpu::TextureView>,
    bloom_view: wgpu::TextureView,
    fog_group: wgpu::BindGroup,
    copy_group: wgpu::BindGroup,
    bloom_group: wgpu::BindGroup,
    final_group: wgpu::BindGroup,
    mip_groups: Vec<wgpu::BindGroup>,
}
fn texture(depth: bool, dimension: wgpu::TextureViewDimension) -> wgpu::BindingType {
    wgpu::BindingType::Texture {
        sample_type: if depth {
            wgpu::TextureSampleType::Depth
        } else {
            wgpu::TextureSampleType::Float { filterable: true }
        },
        view_dimension: dimension,
        multisampled: false,
    }
}
fn layout(
    device: &wgpu::Device,
    label: &str,
    types: &[wgpu::BindingType],
) -> wgpu::BindGroupLayout {
    let entries: Vec<_> = types
        .iter()
        .enumerate()
        .map(|(i, ty)| wgpu::BindGroupLayoutEntry {
            binding: i as u32,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: *ty,
            count: None,
        })
        .collect();
    device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some(label),
        entries: &entries,
    })
}
fn group(
    device: &wgpu::Device,
    label: &str,
    layout: &wgpu::BindGroupLayout,
    resources: &[wgpu::BindingResource<'_>],
) -> wgpu::BindGroup {
    let entries: Vec<_> = resources
        .iter()
        .enumerate()
        .map(|(i, r)| wgpu::BindGroupEntry {
            binding: i as u32,
            resource: r.clone(),
        })
        .collect();
    device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some(label),
        layout,
        entries: &entries,
    })
}
fn pipeline(
    device: &wgpu::Device,
    label: &str,
    source: &str,
    layouts: &[Option<&wgpu::BindGroupLayout>],
    format: wgpu::TextureFormat,
    vertex: &str,
) -> wgpu::RenderPipeline {
    let start = Instant::now();
    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some(label),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some(label),
        bind_group_layouts: layouts,
        immediate_size: 0,
    });
    let result = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some(label),
        layout: Some(&layout),
        vertex: wgpu::VertexState {
            module: &shader,
            entry_point: Some(vertex),
            compilation_options: Default::default(),
            buffers: &[],
        },
        primitive: Default::default(),
        depth_stencil: None,
        multisample: Default::default(),
        fragment: Some(wgpu::FragmentState {
            module: &shader,
            entry_point: Some("fs"),
            compilation_options: Default::default(),
            targets: &[Some(wgpu::ColorTargetState {
                format,
                blend: None,
                write_mask: wgpu::ColorWrites::ALL,
            })],
        }),
        multiview_mask: None,
        cache: None,
    });
    tracing::info!(
        pipeline = label,
        compile_ms = start.elapsed().as_secs_f64() * 1000.,
        "Scene pipeline compiled"
    );
    result
}
impl Post {
    pub fn new(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        frame: &wgpu::BindGroupLayout,
        skip: Option<PostStage>,
    ) -> Self {
        let d2 = wgpu::TextureViewDimension::D2;
        let color = texture(false, d2);
        let depth = texture(true, d2);
        let filtering = wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering);
        let fog_layout = layout(
            device,
            "scene fog inputs",
            &[
                depth,
                texture(false, wgpu::TextureViewDimension::D3),
                filtering,
                depth,
                wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Comparison),
                wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: std::num::NonZeroU64::new(
                        std::mem::size_of::<fog::Wind>() as u64
                    ),
                },
            ],
        );
        let copy_layout = layout(device, "scene fog composite inputs", &[color, color, depth]);
        let bloom_layout = layout(device, "scene bloom inputs", &[color, filtering]);
        let final_layout = layout(device, "scene final inputs", &[color, color, filtering]);
        let mip_layout = layout(device, "scene post mip inputs", &[color, filtering]);
        let fog_pipeline = (skip != Some(PostStage::Fog)).then(|| {
            pipeline(
                device,
                "scene valley fog march",
                FOG,
                &[Some(frame), Some(&fog_layout)],
                super::HDR,
                "full_vs",
            )
        });
        let copy_pipeline = (skip != Some(PostStage::Copy)).then(|| {
            pipeline(
                device,
                "scene bilateral fog HDR copy",
                COPY,
                &[Some(frame), Some(&copy_layout)],
                super::HDR,
                "full_vs",
            )
        });
        let bloom_pipeline = (skip != Some(PostStage::Bloom)).then(|| {
            pipeline(
                device,
                "scene bloom atlas",
                BLOOM,
                &[Some(frame), Some(&bloom_layout)],
                wgpu::TextureFormat::Rgba8Unorm,
                "full_vs",
            )
        });
        let final_pipeline = (skip != Some(PostStage::Final)).then(|| {
            pipeline(
                device,
                "scene bloom tonemap saturation",
                FINAL,
                &[Some(frame), Some(&final_layout)],
                wgpu::TextureFormat::Rgba8Unorm,
                "full_vs",
            )
        });
        let mip_pipeline = (skip != Some(PostStage::Mip)).then(|| {
            pipeline(
                device,
                "scene linear HDR downsample",
                include_str!("mip.wgsl"),
                &[Some(&mip_layout)],
                super::HDR,
                "vs",
            )
        });
        let volume = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("scene valley fog noise R8"),
            size: wgpu::Extent3d {
                width: fog::SIZE,
                height: fog::SIZE,
                depth_or_array_layers: fog::SIZE,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D3,
            format: wgpu::TextureFormat::R8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        super::diagnostics::resource("scene valley fog noise R8", fog::SIZE.pow(3) as u64);
        // Both the FBM scratch array and final bytes die here after write_texture copies them.
        queue.write_texture(
            volume.as_image_copy(),
            &fog::noise(fog::SIZE, 0x0F06),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(fog::SIZE),
                rows_per_image: Some(fog::SIZE),
            },
            volume.size(),
        );
        let noise = volume.create_view(&Default::default());
        let repeat = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene fog repeat trilinear"),
            address_mode_u: wgpu::AddressMode::Repeat,
            address_mode_v: wgpu::AddressMode::Repeat,
            address_mode_w: wgpu::AddressMode::Repeat,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let linear = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene post linear mip clamp"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            ..Default::default()
        });
        let comparison = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene fog shadow comparison"),
            compare: Some(wgpu::CompareFunction::LessEqual),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let wind = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("scene fog wind"),
            size: std::mem::size_of::<fog::Wind>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Self {
            fog_layout,
            copy_layout,
            bloom_layout,
            final_layout,
            mip_layout,
            fog_pipeline,
            copy_pipeline,
            bloom_pipeline,
            final_pipeline,
            mip_pipeline,
            noise,
            repeat,
            linear,
            comparison,
            wind,
        }
    }
    pub fn targets(
        &self,
        device: &wgpu::Device,
        hdr: &wgpu::TextureView,
        depth: &wgpu::TextureView,
        shadow: &wgpu::TextureView,
    ) -> Targets {
        let usage = wgpu::TextureUsages::TEXTURE_BINDING
            | wgpu::TextureUsages::RENDER_ATTACHMENT
            | wgpu::TextureUsages::COPY_SRC;
        let fog = water::target(
            device,
            "scene valley fog RGBA16F",
            state::WIDTH / 2,
            state::HEIGHT / 2,
            super::HDR,
            1,
            usage,
        );
        let linear = water::target(
            device,
            "scene linear HDR mip chain",
            state::WIDTH,
            state::HEIGHT,
            super::HDR,
            MIP_LEVELS,
            usage,
        );
        let bloom = water::target(
            device,
            "scene bloom atlas RGBA8",
            state::WIDTH,
            state::HEIGHT,
            wgpu::TextureFormat::Rgba8Unorm,
            1,
            usage,
        );
        let fog_view = fog.create_view(&Default::default());
        let linear_views: Vec<_> = (0..MIP_LEVELS)
            .map(|l| {
                linear.create_view(&wgpu::TextureViewDescriptor {
                    base_mip_level: l,
                    mip_level_count: Some(1),
                    ..Default::default()
                })
            })
            .collect();
        let linear_full = linear.create_view(&Default::default());
        let bloom_view = bloom.create_view(&Default::default());
        use wgpu::BindingResource::{Sampler as S, TextureView as T};
        let fog_group = group(
            device,
            "scene fog inputs",
            &self.fog_layout,
            &[
                T(depth),
                T(&self.noise),
                S(&self.repeat),
                T(shadow),
                S(&self.comparison),
                self.wind.as_entire_binding(),
            ],
        );
        let copy_group = group(
            device,
            "scene fog composite inputs",
            &self.copy_layout,
            &[T(hdr), T(&fog_view), T(depth)],
        );
        let bloom_group = group(
            device,
            "scene bloom inputs",
            &self.bloom_layout,
            &[T(&linear_full), S(&self.linear)],
        );
        let final_group = group(
            device,
            "scene final inputs",
            &self.final_layout,
            &[T(&linear_views[0]), T(&bloom_view), S(&self.linear)],
        );
        let mip_groups = linear_views[..MIP_LEVELS as usize - 1]
            .iter()
            .map(|v| {
                group(
                    device,
                    "scene post mip inputs",
                    &self.mip_layout,
                    &[T(v), S(&self.linear)],
                )
            })
            .collect();
        Targets {
            fog,
            linear,
            bloom,
            fog_view,
            linear_views,
            bloom_view,
            fog_group,
            copy_group,
            bloom_group,
            final_group,
            mip_groups,
        }
    }
    pub fn update(&self, queue: &wgpu::Queue, seconds: f64) {
        queue.write_buffer(&self.wind, 0, bytemuck::bytes_of(&fog::Wind::at(seconds)));
    }
    pub fn render(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        t: &Targets,
        frame: &wgpu::BindGroup,
        output: &wgpu::TextureView,
    ) {
        let draw = |encoder: &mut wgpu::CommandEncoder,
                    label,
                    pipeline: &Option<wgpu::RenderPipeline>,
                    view,
                    group: &wgpu::BindGroup,
                    mip| {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some(label),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                ..Default::default()
            });
            let Some(pipeline) = pipeline else {
                return;
            };
            pass.set_pipeline(pipeline);
            if mip {
                pass.set_bind_group(0, group, &[]);
            } else {
                pass.set_bind_group(0, frame, &[0]);
                pass.set_bind_group(1, group, &[]);
            }
            pass.draw(0..3, 0..1);
        };
        draw(
            encoder,
            "scene valley fog march",
            &self.fog_pipeline,
            &t.fog_view,
            &t.fog_group,
            false,
        );
        draw(
            encoder,
            "scene bilateral fog HDR copy",
            &self.copy_pipeline,
            &t.linear_views[0],
            &t.copy_group,
            false,
        );
        for i in 0..t.mip_groups.len() {
            draw(
                encoder,
                "scene linear HDR downsample",
                &self.mip_pipeline,
                &t.linear_views[i + 1],
                &t.mip_groups[i],
                true,
            );
        }
        draw(
            encoder,
            "scene bloom atlas",
            &self.bloom_pipeline,
            &t.bloom_view,
            &t.bloom_group,
            false,
        );
        draw(
            encoder,
            "scene bloom tonemap saturation",
            &self.final_pipeline,
            output,
            &t.final_group,
            false,
        );
    }
}
