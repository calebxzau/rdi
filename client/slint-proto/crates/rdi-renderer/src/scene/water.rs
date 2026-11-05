//! Persistent P2 captures/reflection/mips and procedural six-vertex water, no app readback.
use super::{
    diagnostics,
    state::{HEIGHT, WIDTH},
    textures,
};
use anyhow::Result;
pub const SOURCE: &str = concat!(include_str!("common.wgsl"), include_str!("water.wgsl"));
pub struct Water {
    layout: wgpu::BindGroupLayout,
    pipeline: wgpu::RenderPipeline,
    normal: wgpu::TextureView,
    noise: wgpu::TextureView,
    repeat: wgpu::Sampler,
    clamp: wgpu::Sampler,
    mip_layout: wgpu::BindGroupLayout,
    mip_pipeline: wgpu::RenderPipeline,
    depth_layout: Option<wgpu::BindGroupLayout>,
    depth_pipeline: Option<wgpu::RenderPipeline>,
}
pub struct Targets {
    pub reflection: wgpu::Texture,
    pub reflection_view: wgpu::TextureView,
    pub reflection_depth: wgpu::TextureView,
    pub scene_color: wgpu::Texture,
    pub scene_depth: wgpu::Texture,
    pub(super) scene_depth_view: wgpu::TextureView,
    group: wgpu::BindGroup,
    mip_views: Vec<wgpu::TextureView>,
    mip_groups: Vec<wgpu::BindGroup>,
    depth_group: Option<wgpu::BindGroup>,
}
fn entry(binding: u32, ty: wgpu::BindingType) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::FRAGMENT,
        ty,
        count: None,
    }
}
fn texture_type(depth: bool, multisampled: bool) -> wgpu::BindingType {
    wgpu::BindingType::Texture {
        sample_type: if depth {
            wgpu::TextureSampleType::Depth
        } else {
            wgpu::TextureSampleType::Float { filterable: true }
        },
        view_dimension: wgpu::TextureViewDimension::D2,
        multisampled,
    }
}
pub fn target(
    device: &wgpu::Device,
    label: &str,
    width: u32,
    height: u32,
    format: wgpu::TextureFormat,
    mips: u32,
    usage: wgpu::TextureUsages,
) -> wgpu::Texture {
    let bpp = if format == super::HDR { 8 } else { 4 };
    let bytes = (0..mips)
        .map(|l| (width >> l).max(1) as u64 * (height >> l).max(1) as u64 * bpp)
        .sum();
    diagnostics::resource(label, bytes);
    device.create_texture(&wgpu::TextureDescriptor {
        label: Some(label),
        size: wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        },
        mip_level_count: mips,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format,
        usage,
        view_formats: &[],
    })
}
impl Water {
    pub fn new(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        frame_layout: &wgpu::BindGroupLayout,
        samples: u32,
    ) -> Result<Self> {
        let mut entries: Vec<_> = (0..5)
            .map(|i| entry(i, texture_type(i == 3, false)))
            .collect();
        for i in 5..7 {
            entries.push(entry(
                i,
                wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
            ));
        }
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene water inputs"),
            entries: &entries,
        });
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("scene water"),
            source: wgpu::ShaderSource::Wgsl(SOURCE.into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("scene water"),
            bind_group_layouts: &[Some(frame_layout), Some(&layout)],
            immediate_size: 0,
        });
        let start = std::time::Instant::now();
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("scene water"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            primitive: wgpu::PrimitiveState {
                cull_mode: Some(wgpu::Face::Back),
                ..Default::default()
            },
            depth_stencil: Some(wgpu::DepthStencilState {
                format: wgpu::TextureFormat::Depth32Float,
                depth_write_enabled: Some(false),
                depth_compare: Some(wgpu::CompareFunction::LessEqual),
                stencil: Default::default(),
                bias: Default::default(),
            }),
            multisample: wgpu::MultisampleState {
                count: samples,
                ..Default::default()
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format: super::HDR,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });
        tracing::info!(
            pipeline = "water",
            compile_ms = start.elapsed().as_secs_f64() * 1000.,
            "Scene pipeline compiled"
        );
        let normal = textures::upload_data(
            device,
            queue,
            include_bytes!(
                "../../../../../bg-renderer/src/main/resources/assets/complementary/textures/cloud-water.png"
            ),
            "scene water normal linear",
        )?;
        let noise = textures::upload_data(
            device,
            queue,
            include_bytes!(
                "../../../../../bg-renderer/src/main/resources/assets/complementary/textures/noise.png"
            ),
            "scene water noise linear",
        )?;
        let repeat = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene water repeat anisotropic8"),
            address_mode_u: wgpu::AddressMode::Repeat,
            address_mode_v: wgpu::AddressMode::Repeat,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            anisotropy_clamp: 8,
            ..Default::default()
        });
        let clamp = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene water/reflection linear clamp"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            ..Default::default()
        });
        let mip_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene reflection downsample"),
            entries: &[
                entry(0, texture_type(false, false)),
                entry(
                    1,
                    wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                ),
            ],
        });
        let mip_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("scene reflection downsample"),
            source: wgpu::ShaderSource::Wgsl(include_str!("mip.wgsl").into()),
        });
        let ml = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("scene reflection downsample"),
            bind_group_layouts: &[Some(&mip_layout)],
            immediate_size: 0,
        });
        let mip_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("scene reflection downsample"),
            layout: Some(&ml),
            vertex: wgpu::VertexState {
                module: &mip_shader,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &mip_shader,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format: super::HDR,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });
        let (depth_layout, depth_pipeline) = if samples > 1 {
            let dl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("scene MSAA depth capture"),
                entries: &[entry(0, texture_type(true, true))],
            });
            let ds=device.create_shader_module(wgpu::ShaderModuleDescriptor{label:Some("scene MSAA depth capture"),source:wgpu::ShaderSource::Wgsl("@group(0) @binding(0) var depth:texture_depth_multisampled_2d; @vertex fn vs(@builtin(vertex_index) i:u32)->@builtin(position) vec4f {let p=array<vec2f,3>(vec2f(-1.,-1.),vec2f(3.,-1.),vec2f(-1.,3.));return vec4f(p[i],0.,1.);} @fragment fn fs(@builtin(position) p:vec4f)->@builtin(frag_depth) f32 {return textureLoad(depth,vec2i(p.xy),0);}".into())});
            let pl = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("scene MSAA depth capture"),
                bind_group_layouts: &[Some(&dl)],
                immediate_size: 0,
            });
            let dp = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("scene MSAA depth capture"),
                layout: Some(&pl),
                vertex: wgpu::VertexState {
                    module: &ds,
                    entry_point: Some("vs"),
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                primitive: Default::default(),
                depth_stencil: Some(wgpu::DepthStencilState {
                    format: wgpu::TextureFormat::Depth32Float,
                    depth_write_enabled: Some(true),
                    depth_compare: Some(wgpu::CompareFunction::Always),
                    stencil: Default::default(),
                    bias: Default::default(),
                }),
                multisample: Default::default(),
                fragment: Some(wgpu::FragmentState {
                    module: &ds,
                    entry_point: Some("fs"),
                    compilation_options: Default::default(),
                    targets: &[],
                }),
                multiview_mask: None,
                cache: None,
            });
            (Some(dl), Some(dp))
        } else {
            (None, None)
        };
        Ok(Self {
            layout,
            pipeline,
            normal,
            noise,
            repeat,
            clamp,
            mip_layout,
            mip_pipeline,
            depth_layout,
            depth_pipeline,
        })
    }
    pub fn targets(&self, device: &wgpu::Device, depth: &wgpu::TextureView) -> Targets {
        let usage = wgpu::TextureUsages::RENDER_ATTACHMENT
            | wgpu::TextureUsages::TEXTURE_BINDING
            | wgpu::TextureUsages::COPY_SRC;
        let reflection = target(
            device,
            "scene reflection HDR mips0..4",
            640,
            360,
            super::HDR,
            5,
            usage,
        );
        let reflection_depth = target(
            device,
            "scene reflection D32",
            640,
            360,
            wgpu::TextureFormat::Depth32Float,
            1,
            wgpu::TextureUsages::RENDER_ATTACHMENT,
        )
        .create_view(&Default::default());
        let mip_views: Vec<_> = (0..5)
            .map(|l| {
                reflection.create_view(&wgpu::TextureViewDescriptor {
                    base_mip_level: l,
                    mip_level_count: Some(1),
                    ..Default::default()
                })
            })
            .collect();
        let mip_groups = mip_views[..4]
            .iter()
            .map(|view| {
                device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("scene reflection mip"),
                    layout: &self.mip_layout,
                    entries: &[
                        wgpu::BindGroupEntry {
                            binding: 0,
                            resource: wgpu::BindingResource::TextureView(view),
                        },
                        wgpu::BindGroupEntry {
                            binding: 1,
                            resource: wgpu::BindingResource::Sampler(&self.clamp),
                        },
                    ],
                })
            })
            .collect();
        let scene_color = target(
            device,
            "scene pre-water HDR",
            WIDTH,
            HEIGHT,
            super::HDR,
            1,
            wgpu::TextureUsages::COPY_DST
                | wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::COPY_SRC,
        );
        let scene_depth = target(
            device,
            "scene pre-water D32",
            WIDTH,
            HEIGHT,
            wgpu::TextureFormat::Depth32Float,
            1,
            usage | wgpu::TextureUsages::COPY_DST,
        );
        let reflection_full = reflection.create_view(&Default::default());
        let color_view = scene_color.create_view(&Default::default());
        let depth_view = scene_depth.create_view(&Default::default());
        let views = [
            &self.normal,
            &self.noise,
            &color_view,
            &depth_view,
            &reflection_full,
        ];
        let mut entries: Vec<_> = views
            .into_iter()
            .enumerate()
            .map(|(i, v)| wgpu::BindGroupEntry {
                binding: i as u32,
                resource: wgpu::BindingResource::TextureView(v),
            })
            .collect();
        entries.push(wgpu::BindGroupEntry {
            binding: 5,
            resource: wgpu::BindingResource::Sampler(&self.repeat),
        });
        entries.push(wgpu::BindGroupEntry {
            binding: 6,
            resource: wgpu::BindingResource::Sampler(&self.clamp),
        });
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene water inputs"),
            layout: &self.layout,
            entries: &entries,
        });
        let depth_group = self.depth_layout.as_ref().map(|layout| {
            device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("scene MSAA depth capture"),
                layout,
                entries: &[wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(depth),
                }],
            })
        });
        let reflection_view = reflection.create_view(&wgpu::TextureViewDescriptor {
            mip_level_count: Some(1),
            ..Default::default()
        });
        Targets {
            reflection,
            reflection_view,
            reflection_depth,
            scene_color,
            scene_depth,
            scene_depth_view: depth_view,
            group,
            mip_views,
            mip_groups,
            depth_group,
        }
    }
    pub fn capture(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        t: &Targets,
        hdr: &wgpu::TextureView,
        depth: &wgpu::TextureView,
    ) {
        encoder.copy_texture_to_texture(
            hdr.texture().as_image_copy(),
            t.scene_color.as_image_copy(),
            t.scene_color.size(),
        );
        if let Some(pipeline) = &self.depth_pipeline {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("scene sample0 depth capture"),
                color_attachments: &[],
                depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                    view: &t.scene_depth_view,
                    depth_ops: Some(wgpu::Operations {
                        load: wgpu::LoadOp::Clear(1.),
                        store: wgpu::StoreOp::Store,
                    }),
                    stencil_ops: None,
                }),
                ..Default::default()
            });
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, t.depth_group.as_ref().unwrap(), &[]);
            pass.draw(0..3, 0..1);
        } else {
            let mut source = depth.texture().as_image_copy();
            source.aspect = wgpu::TextureAspect::DepthOnly;
            let mut destination = t.scene_depth.as_image_copy();
            destination.aspect = wgpu::TextureAspect::DepthOnly;
            encoder.copy_texture_to_texture(source, destination, t.scene_depth.size());
        }
    }
    pub fn mips(&self, encoder: &mut wgpu::CommandEncoder, t: &Targets) {
        for i in 0..4 {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("scene reflection downsample"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &t.mip_views[i + 1],
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                ..Default::default()
            });
            pass.set_pipeline(&self.mip_pipeline);
            pass.set_bind_group(0, &t.mip_groups[i], &[]);
            pass.draw(0..3, 0..1);
        }
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>, t: &Targets, frame: &wgpu::BindGroup) {
        pass.set_pipeline(&self.pipeline);
        pass.set_bind_group(0, frame, &[0]);
        pass.set_bind_group(1, &t.group, &[]);
        pass.draw(0..6, 0..1);
    }
}
