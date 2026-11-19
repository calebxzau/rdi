//! Milk-white background with a single, looping queue of Minecraft players.
use crate::scene::{
    self, diagnostics, perf,
    player::{Appearance, Skin},
    player_geometry,
};
use anyhow::{Context, Result};
use std::{
    ops::Range,
    sync::mpsc,
    time::{Duration, Instant},
};
use wgpu::util::DeviceExt;

pub const COLOR: [u8; 4] = [247, 243, 232, 255];
const FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8Unorm;
const SOURCE: &str = include_str!("parade.wgsl");
const SPACING: f64 = 56.;
const SPEED: f64 = 100.;
const MARGIN: f64 = 40.;
const RUN_CYCLE_RADIANS_PER_SECOND: f64 = 11.5;
const RUN_LEG_SWING_RADIANS: f32 = 0.9;
const RUN_BOB_PIXELS: f32 = 4.;

/// Each identity occurs once per circuit. Extra empty slots keep small rosters from repeating
/// simultaneously; long rosters continue offscreen until every member has passed.
fn queue_x(index: usize, count: usize, width: f64, seconds: f64) -> f32 {
    let slots = ((width + 2. * MARGIN) / SPACING).ceil().max(count as f64);
    let circuit = slots * SPACING;
    (width + MARGIN - (index as f64 * SPACING - seconds * SPEED).rem_euclid(circuit)) as f32
}

struct Member {
    id: String,
    group: wgpu::BindGroup,
    variant: usize,
}
struct Targets {
    output: wgpu::Texture,
    view: wgpu::TextureView,
    depth: wgpu::TextureView,
}
impl Targets {
    fn new(device: &wgpu::Device, width: u32, height: u32) -> Self {
        let make = |label, format, usage| {
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d {
                    width,
                    height,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage,
                view_formats: &[],
            })
        };
        let output = make(
            "parade output",
            FORMAT,
            wgpu::TextureUsages::RENDER_ATTACHMENT
                | wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::COPY_SRC,
        );
        let view = output.create_view(&Default::default());
        let depth = make(
            "parade depth",
            wgpu::TextureFormat::Depth32Float,
            wgpu::TextureUsages::RENDER_ATTACHMENT,
        )
        .create_view(&Default::default());
        Self {
            output,
            view,
            depth,
        }
    }
}

pub struct Renderer {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::RenderPipeline,
    uniforms: wgpu::Buffer,
    frame: wgpu::BindGroup,
    skins: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    vertices: wgpu::Buffer,
    meshes: [Range<u32>; 4],
    instances: wgpu::Buffer,
    capacity: usize,
    poses: Vec<[f32; 4]>,
    visible: Vec<usize>,
    members: Vec<Member>,
    fallback: bool,
    targets: Option<Targets>,
    complete_tx: mpsc::Sender<Duration>,
    complete_rx: mpsc::Receiver<Duration>,
    in_flight: bool,
    discard_completion: bool,
    monitor: perf::Monitor,
    options: diagnostics::Options,
    frames: u64,
}
impl Renderer {
    pub fn new(
        device: wgpu::Device,
        queue: wgpu::Queue,
        options: diagnostics::Options,
    ) -> Result<Self> {
        scene::checked(&device.clone(), || Self::create(device, queue, options))
    }
    fn create(
        device: wgpu::Device,
        queue: wgpu::Queue,
        options: diagnostics::Options,
    ) -> Result<Self> {
        anyhow::ensure!(!options.startup_fail, "Intentional parade startup failure");
        let frame_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("parade frame"),
            entries: &[wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::VERTEX,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            }],
        });
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("parade viewport"),
            size: 16,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let frame = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("parade frame"),
            layout: &frame_layout,
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: uniforms.as_entire_binding(),
            }],
        });
        let skins = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("parade skin"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor::default());
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("parade players"),
            source: wgpu::ShaderSource::Wgsl(SOURCE.into()),
        });
        let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("parade players"),
            bind_group_layouts: &[Some(&frame_layout), Some(&skins)],
            immediate_size: 0,
        });
        let mesh = wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x3,3=>Float32];
        let instance = wgpu::vertex_attr_array![5=>Float32x4];
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("parade players"),
            layout: Some(&layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[
                    Some(wgpu::VertexBufferLayout {
                        array_stride: std::mem::size_of::<player_geometry::Vertex>() as u64,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &mesh,
                    }),
                    Some(wgpu::VertexBufferLayout {
                        array_stride: 16,
                        step_mode: wgpu::VertexStepMode::Instance,
                        attributes: &instance,
                    }),
                ],
            },
            primitive: wgpu::PrimitiveState {
                cull_mode: Some(wgpu::Face::Back),
                ..Default::default()
            },
            depth_stencil: Some(wgpu::DepthStencilState {
                format: wgpu::TextureFormat::Depth32Float,
                depth_write_enabled: Some(true),
                depth_compare: Some(wgpu::CompareFunction::LessEqual),
                stencil: Default::default(),
                bias: Default::default(),
            }),
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format: FORMAT,
                    blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });
        let mut data = Vec::new();
        let meshes = std::array::from_fn(|i| {
            let start = data.len() as u32;
            data.extend(player_geometry::mesh(i & 1 != 0, i & 2 != 0, true, false));
            start..data.len() as u32
        });
        let vertices = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("parade shared player meshes"),
            contents: bytemuck::cast_slice(&data),
            usage: wgpu::BufferUsages::VERTEX,
        });
        let instances = Self::instance_buffer(&device, 16);
        let (complete_tx, complete_rx) = mpsc::channel();
        let mut renderer = Self {
            device,
            queue,
            pipeline,
            uniforms,
            frame,
            skins,
            sampler,
            vertices,
            meshes,
            instances,
            capacity: 16,
            poses: Vec::new(),
            visible: Vec::new(),
            members: Vec::new(),
            fallback: true,
            targets: None,
            complete_tx,
            complete_rx,
            in_flight: false,
            discard_completion: false,
            monitor: perf::Monitor::with_threshold(options.low_performance_ms),
            options,
            frames: 0,
        };
        renderer.upload("builtin:steve", Appearance::steve()?.skin)?;
        Ok(renderer)
    }
    fn instance_buffer(device: &wgpu::Device, capacity: usize) -> wgpu::Buffer {
        device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("parade visible poses"),
            size: (capacity * 16) as u64,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        })
    }
    fn upload(&mut self, id: &str, skin: Skin) -> Result<()> {
        let (width, height) = skin.pixels.dimensions();
        anyhow::ensure!(
            width == 64 && (height == 32 || height == 64),
            "Parade skin must be normalized"
        );
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("parade skin"),
            size: wgpu::Extent3d {
                width: 64,
                height: 64,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: FORMAT,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        self.queue.write_texture(
            texture.as_image_copy(),
            skin.pixels.as_raw(),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(256),
                rows_per_image: Some(height),
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("parade skin"),
            layout: &self.skins,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(
                        &texture.create_view(&Default::default()),
                    ),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                },
            ],
        });
        self.members.push(Member {
            id: id.into(),
            group,
            variant: skin.slim as usize + (height == 32) as usize * 2,
        });
        Ok(())
    }
    pub fn set_crowd_skin(&mut self, id: &str, skin: Skin) -> Result<()> {
        if self.options.no_crowd || self.members.iter().any(|m| m.id == id) {
            return Ok(());
        }
        scene::checked(&self.device.clone(), || {
            if self.fallback {
                self.members.clear();
                self.fallback = false;
            }
            self.upload(id, skin)
        })
    }
    pub fn set_player_appearance(&mut self, appearance: Appearance) -> Result<()> {
        if self.fallback {
            scene::checked(&self.device.clone(), || {
                self.members.clear();
                self.upload("builtin:account", appearance.skin)
            })?;
        }
        Ok(())
    }
    pub fn pause(&mut self) {
        self.discard_completion = self.in_flight;
    }
    pub fn output(&self) -> &wgpu::Texture {
        &self
            .targets
            .as_ref()
            .expect("render parade before reading output")
            .output
    }
    /// Returns whether a new frame was submitted, and whether its output texture changed.
    pub fn render(
        &mut self,
        seconds: f64,
        width: u32,
        height: u32,
        scale: f32,
    ) -> Result<(bool, bool)> {
        anyhow::ensure!(
            seconds.is_finite() && scale.is_finite() && scale > 0.,
            "Invalid parade frame"
        );
        self.device
            .poll(wgpu::PollType::Poll)
            .context("Parade device poll failed")?;
        while let Ok(work) = self.complete_rx.try_recv() {
            self.in_flight = false;
            let ignore = std::mem::take(&mut self.discard_completion);
            anyhow::ensure!(
                ignore || !self.monitor.sample(work),
                "Parade exceeded completion budget"
            );
        }
        if self.in_flight || width == 0 || height == 0 || self.options.pipelines_only {
            return Ok((false, false));
        }
        scene::checked(&self.device.clone(), || {
            anyhow::ensure!(
                !self.options.fail || self.frames < 180,
                "Intentional parade frame failure"
            );
            let resized = self
                .targets
                .as_ref()
                .is_none_or(|t| t.output.width() != width || t.output.height() != height);
            if resized {
                self.targets = Some(Targets::new(&self.device, width, height));
            }
            let logical_width = width as f32 / scale;
            let logical_height = height as f32 / scale;
            self.queue.write_buffer(
                &self.uniforms,
                0,
                bytemuck::cast_slice(&[logical_width, logical_height, 0., 0.]),
            );
            self.poses.clear();
            self.visible.clear();
            for (index, member) in self.members.iter().enumerate() {
                let x = queue_x(index, self.members.len(), logical_width as f64, seconds);
                if x < -MARGIN as f32 || x > logical_width + MARGIN as f32 {
                    continue;
                }
                let swing = (seconds * RUN_CYCLE_RADIANS_PER_SECOND + index as f64 * 0.7)
                    .rem_euclid(std::f64::consts::TAU)
                    .sin() as f32;
                let bob = RUN_BOB_PIXELS * (1. - swing * swing);
                self.poses.push([
                    x,
                    logical_height - 8. - bob,
                    swing * RUN_LEG_SWING_RADIANS,
                    if member.variant & 1 != 0 { 5.5 } else { 6. },
                ]);
                self.visible.push(index);
            }
            if self.poses.len() > self.capacity {
                self.capacity = self.poses.len().next_power_of_two();
                self.instances = Self::instance_buffer(&self.device, self.capacity);
            }
            if !self.poses.is_empty() {
                self.queue
                    .write_buffer(&self.instances, 0, bytemuck::cast_slice(&self.poses));
            }
            let start = Instant::now();
            let mut encoder = self
                .device
                .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                    label: Some("parade frame"),
                });
            let targets = self.targets.as_ref().unwrap();
            {
                let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                    label: Some("milk background and players"),
                    color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                        view: &targets.view,
                        depth_slice: None,
                        resolve_target: None,
                        ops: wgpu::Operations {
                            load: wgpu::LoadOp::Clear(wgpu::Color {
                                r: COLOR[0] as f64 / 255.,
                                g: COLOR[1] as f64 / 255.,
                                b: COLOR[2] as f64 / 255.,
                                a: 1.,
                            }),
                            store: wgpu::StoreOp::Store,
                        },
                    })],
                    depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                        view: &targets.depth,
                        depth_ops: Some(wgpu::Operations {
                            load: wgpu::LoadOp::Clear(1.),
                            store: wgpu::StoreOp::Discard,
                        }),
                        stencil_ops: None,
                    }),
                    ..Default::default()
                });
                pass.set_pipeline(&self.pipeline);
                pass.set_bind_group(0, &self.frame, &[]);
                pass.set_vertex_buffer(0, self.vertices.slice(..));
                pass.set_vertex_buffer(1, self.instances.slice(..));
                for (instance, &index) in self.visible.iter().enumerate() {
                    let member = &self.members[index];
                    pass.set_bind_group(1, &member.group, &[]);
                    pass.draw(
                        self.meshes[member.variant].clone(),
                        instance as u32..instance as u32 + 1,
                    );
                }
            }
            self.queue.submit([encoder.finish()]);
            let tx = self.complete_tx.clone();
            self.queue.on_submitted_work_done(move || {
                let _ = tx.send(start.elapsed());
            });
            self.in_flight = true;
            self.frames += 1;
            Ok((true, resized))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn every_member_passes_once_including_rosters_larger_than_fifty() {
        for count in [1, 3, 50, 137] {
            let width = 1280.;
            let circuit = ((width + 2. * MARGIN) / SPACING).ceil().max(count as f64) * SPACING;
            for index in 0..count {
                let time = (index as f64 * SPACING - (width + MARGIN - width / 2.))
                    .rem_euclid(circuit)
                    / SPEED;
                assert!((queue_x(index, count, width, time) - width as f32 / 2.).abs() < 0.01);
                assert!(
                    (queue_x(index, count, width, time + 1.) - width as f32 / 2. - SPEED as f32)
                        .abs()
                        < 0.01
                );
                assert!(
                    (queue_x(index, count, width, time + circuit / SPEED) - width as f32 / 2.)
                        .abs()
                        < 0.01
                );
            }
        }
    }
    #[test]
    fn queue_spacing_stays_clear_at_different_window_widths() {
        for width in [640., 1280., 2560.] {
            for count in [2, 12, 137] {
                let mut xs: Vec<_> = (0..count)
                    .map(|i| queue_x(i, count, width, 1234.))
                    .filter(|&x| x >= 0. && x <= width as f32)
                    .collect();
                xs.sort_by(f32::total_cmp);
                assert!(
                    xs.windows(2)
                        .all(|pair| pair[1] - pair[0] >= SPACING as f32 - 0.01)
                );
            }
        }
    }
    #[test]
    fn shader_is_valid() {
        let module = naga::front::wgsl::parse_str(SOURCE).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::all(),
        )
        .validate(&module)
        .unwrap();
    }
}
