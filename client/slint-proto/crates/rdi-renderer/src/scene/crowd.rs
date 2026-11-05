//! Hill crowd. One skin array and one instance buffer keep the 50-skin cost near 1MiB.
use super::{
    crowd_sim::{MAX_PLAYERS, Runner, Simulation},
    diagnostics,
    player::{self, Skin},
};
use anyhow::Result;
use std::ops::Range;

pub const SOURCE: &str = concat!(include_str!("common.wgsl"), include_str!("crowd.wgsl"));
pub const SHADOW_SOURCE: &str = concat!(
    include_str!("common.wgsl"),
    include_str!("crowd_shadow.wgsl")
);
pub const FALLBACK_ID: &str = "builtin:steve";
const CAPE_VERTICES: u32 = 36;
const VARIANTS: usize = 4;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Instance {
    pub origin: [f32; 3],
    pub yaw: f32,
    pub stride: f32,
    pub arm: f32,
    pub layer: u32,
    pub shadow: [f32; 3],
}
#[derive(Clone, Debug, PartialEq)]
struct Member {
    id: String,
    layer: u32,
    variant: usize,
}
/// Skin layers and mesh variants, kept in the same order as the simulation's runners.
#[derive(Debug)]
struct Roster {
    members: Vec<Member>,
    free: Vec<u32>,
}
impl Roster {
    fn new() -> Self {
        Self {
            members: Vec::with_capacity(MAX_PLAYERS),
            free: (0..MAX_PLAYERS as u32).rev().collect(),
        }
    }
    /// Returns the layer to upload to, or `None` for a known identity or a full crowd.
    fn add(&mut self, id: &str, variant: usize) -> Option<u32> {
        if self.members.iter().any(|m| m.id == id) {
            return None;
        }
        // Steve only stands in until the first real skin arrives.
        if id != FALLBACK_ID
            && let Some(index) = self.members.iter().position(|m| m.id == FALLBACK_ID)
        {
            let fallback = self.members.remove(index);
            self.free.push(fallback.layer);
        }
        let layer = self.free.pop()?;
        self.members.push(Member {
            id: id.to_owned(),
            layer,
            variant,
        });
        Some(layer)
    }
    fn ids(&self) -> Vec<&str> {
        self.members.iter().map(|m| m.id.as_str()).collect()
    }
}
fn variant(slim: bool, legacy: bool) -> usize {
    slim as usize + legacy as usize * 2
}
/// Groups visible actors by mesh variant, reusing `out` so a frame allocates nothing.
fn fill_instances(
    runners: &[Runner],
    members: &[Member],
    out: &mut Vec<Instance>,
    ranges: &mut [Range<u32>; VARIANTS],
) {
    out.clear();
    for (wanted, range) in ranges.iter_mut().enumerate() {
        let start = out.len() as u32;
        for (runner, member) in runners.iter().zip(members) {
            if !runner.visible || member.variant != wanted {
                continue;
            }
            out.push(Instance {
                origin: [runner.x, runner.y, runner.z],
                yaw: runner.yaw,
                stride: ((runner.phase as f64).sin() as f32) * 0.8,
                arm: if wanted & 1 != 0 { 5.5 } else { 6. },
                layer: member.layer,
                shadow: [runner.shadow_x, runner.shadow_y, runner.shadow_z],
            });
        }
        *range = start..out.len() as u32;
    }
}
pub struct Crowd {
    simulation: Simulation,
    roster: Roster,
    previous_seconds: Option<f64>,
    vertices: wgpu::Buffer,
    meshes: [Range<u32>; VARIANTS],
    instances: wgpu::Buffer,
    scratch: Vec<Instance>,
    draws: [Range<u32>; VARIANTS],
    skins: wgpu::Texture,
    group: wgpu::BindGroup,
    main: wgpu::RenderPipeline,
    reflection: wgpu::RenderPipeline,
    shadow: wgpu::RenderPipeline,
}
impl Crowd {
    pub fn new(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        frame: &wgpu::BindGroupLayout,
        samples: u32,
        vertices: wgpu::Buffer,
        meshes: [Range<u32>; VARIANTS],
    ) -> Result<Self> {
        let instance_size = (std::mem::size_of::<Instance>() * MAX_PLAYERS) as u64;
        diagnostics::resource("scene crowd instances", instance_size);
        let instances = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("scene crowd instances"),
            size: instance_size,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        diagnostics::resource("scene crowd skins SRGB", 64 * 64 * 4 * MAX_PLAYERS as u64);
        let skins = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("scene crowd skins SRGB"),
            size: wgpu::Extent3d {
                width: 64,
                height: 64,
                depth_or_array_layers: MAX_PLAYERS as u32,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8UnormSrgb,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene crowd skins"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2Array,
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
        let view = skins.create_view(&wgpu::TextureViewDescriptor {
            dimension: Some(wgpu::TextureViewDimension::D2Array),
            ..Default::default()
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene crowd reference nearest clamp"),
            ..Default::default()
        });
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene crowd skins"),
            layout: &layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&view),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&sampler),
                },
            ],
        });
        let main = actor_pipeline(device, frame, &layout, "scene crowd main", samples);
        let reflection = actor_pipeline(device, frame, &layout, "scene crowd reflection", 1);
        let shadow = shadow_pipeline(device, frame, samples);
        let mut crowd = Self {
            simulation: Simulation::new(),
            roster: Roster::new(),
            previous_seconds: None,
            vertices,
            meshes,
            instances,
            scratch: Vec::with_capacity(MAX_PLAYERS),
            draws: std::array::from_fn(|_| 0..0),
            skins,
            group,
            main,
            reflection,
            shadow,
        };
        crowd.add_skin(queue, FALLBACK_ID, player::Appearance::steve()?.skin);
        Ok(crowd)
    }
    /// Uploads one normalized skin and consumes its CPU pixels. Known identities are ignored.
    pub fn add_skin(&mut self, queue: &wgpu::Queue, id: &str, skin: Skin) {
        let (width, height) = skin.pixels.dimensions();
        let legacy = height == 32;
        let Some(layer) = self.roster.add(id, variant(skin.slim, legacy)) else {
            return;
        };
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &self.skins,
                mip_level: 0,
                origin: wgpu::Origin3d {
                    x: 0,
                    y: 0,
                    z: layer,
                },
                aspect: wgpu::TextureAspect::All,
            },
            skin.pixels.as_raw(),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(width * 4),
                rows_per_image: Some(height),
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        self.simulation.set_players(&self.roster.ids());
        debug_assert!(
            self.simulation
                .runners
                .iter()
                .map(|r| r.id.as_str())
                .eq(self.roster.ids())
        );
        tracing::debug!(
            layer,
            slim = skin.slim,
            legacy,
            "Crowd skin uploaded; CPU pixels released"
        );
    }
    /// Only rendered frames move the crowd, so paused time is never replayed.
    pub fn advance_to(&mut self, seconds: f64) {
        if let Some(previous) = self.previous_seconds {
            self.simulation.advance(seconds - previous);
        }
        self.previous_seconds = Some(seconds);
    }
    pub fn update(&mut self, queue: &wgpu::Queue, seconds: f64) {
        self.advance_to(seconds);
        fill_instances(
            &self.simulation.runners,
            &self.roster.members,
            &mut self.scratch,
            &mut self.draws,
        );
        if !self.scratch.is_empty() {
            queue.write_buffer(&self.instances, 0, bytemuck::cast_slice(&self.scratch));
        }
    }
    pub fn runners(&self) -> &[Runner] {
        &self.simulation.runners
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>, frame: &wgpu::BindGroup, reflection: bool) {
        if self.scratch.is_empty() {
            return;
        }
        let offset = if reflection {
            super::state::FRAME_STRIDE
        } else {
            0
        };
        pass.set_bind_group(0, frame, &[offset]);
        if !reflection {
            pass.set_pipeline(&self.shadow);
            pass.set_vertex_buffer(0, self.instances.slice(..));
            pass.draw(0..6, 0..self.scratch.len() as u32);
        }
        pass.set_pipeline(if reflection {
            &self.reflection
        } else {
            &self.main
        });
        pass.set_bind_group(1, &self.group, &[]);
        pass.set_vertex_buffer(0, self.vertices.slice(..));
        pass.set_vertex_buffer(1, self.instances.slice(..));
        for (mesh, draw) in self.meshes.iter().zip(&self.draws) {
            if !draw.is_empty() {
                pass.draw(mesh.start..mesh.end - CAPE_VERTICES, draw.clone());
            }
        }
    }
}
fn instance_attributes() -> [wgpu::VertexAttribute; 5] {
    wgpu::vertex_attr_array![5=>Float32x3,6=>Float32,7=>Float32,8=>Float32,9=>Uint32]
}
fn actor_pipeline(
    device: &wgpu::Device,
    frame: &wgpu::BindGroupLayout,
    skins: &wgpu::BindGroupLayout,
    name: &str,
    samples: u32,
) -> wgpu::RenderPipeline {
    let start = std::time::Instant::now();
    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some(name),
        source: wgpu::ShaderSource::Wgsl(SOURCE.into()),
    });
    let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some(name),
        bind_group_layouts: &[Some(frame), Some(skins)],
        immediate_size: 0,
    });
    let mesh = wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x3,3=>Float32];
    let instance = instance_attributes();
    let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some(name),
        layout: Some(&layout),
        vertex: wgpu::VertexState {
            module: &shader,
            entry_point: Some("vs"),
            compilation_options: Default::default(),
            buffers: &[
                Some(wgpu::VertexBufferLayout {
                    array_stride: 44,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &mesh,
                }),
                Some(wgpu::VertexBufferLayout {
                    array_stride: std::mem::size_of::<Instance>() as u64,
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
                blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                write_mask: wgpu::ColorWrites::ALL,
            })],
        }),
        multiview_mask: None,
        cache: None,
    });
    tracing::info!(
        pipeline = name,
        compile_ms = start.elapsed().as_secs_f64() * 1000.,
        "Scene pipeline compiled"
    );
    pipeline
}
fn shadow_pipeline(
    device: &wgpu::Device,
    frame: &wgpu::BindGroupLayout,
    samples: u32,
) -> wgpu::RenderPipeline {
    let name = "scene crowd contact shadow";
    let start = std::time::Instant::now();
    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some(name),
        source: wgpu::ShaderSource::Wgsl(SHADOW_SOURCE.into()),
    });
    let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some(name),
        bind_group_layouts: &[Some(frame)],
        immediate_size: 0,
    });
    let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some(name),
        layout: Some(&layout),
        vertex: wgpu::VertexState {
            module: &shader,
            entry_point: Some("vs"),
            compilation_options: Default::default(),
            buffers: &[Some(wgpu::VertexBufferLayout {
                array_stride: std::mem::size_of::<Instance>() as u64,
                step_mode: wgpu::VertexStepMode::Instance,
                attributes: &[wgpu::VertexAttribute {
                    format: wgpu::VertexFormat::Float32x3,
                    offset: std::mem::offset_of!(Instance, shadow) as u64,
                    shader_location: 0,
                }],
            })],
        },
        primitive: Default::default(),
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
                blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                write_mask: wgpu::ColorWrites::ALL,
            })],
        }),
        multiview_mask: None,
        cache: None,
    });
    tracing::info!(
        pipeline = name,
        compile_ms = start.elapsed().as_secs_f64() * 1000.,
        "Scene pipeline compiled"
    );
    pipeline
}
#[cfg(test)]
mod tests {
    use super::*;
    fn runner(id: &str, visible: bool) -> Runner {
        let mut simulation = Simulation::new();
        simulation.set_players(&[id]);
        let mut runner = simulation.runners.remove(0);
        runner.visible = visible;
        runner
    }
    #[test]
    fn steve_is_replaced_by_the_first_real_skin_and_its_layer_is_reused() {
        let mut roster = Roster::new();
        assert_eq!(roster.add(FALLBACK_ID, 0), Some(0));
        assert_eq!(roster.add(FALLBACK_ID, 0), None);
        assert_eq!(roster.add("a", 1), Some(0));
        assert_eq!(roster.ids(), ["a"]);
        assert_eq!(roster.add("b", 2), Some(1));
        assert_eq!(roster.add("a", 1), None);
        assert_eq!(roster.ids(), ["a", "b"]);
    }
    #[test]
    fn crowd_stops_accepting_skins_when_every_layer_is_used() {
        let mut roster = Roster::new();
        for i in 0..MAX_PLAYERS {
            assert_eq!(roster.add(&format!("skin-{i}"), 0), Some(i as u32));
        }
        assert_eq!(roster.add("one-too-many", 0), None);
        assert_eq!(roster.members.len(), MAX_PLAYERS);
    }
    #[test]
    fn instances_are_grouped_by_variant_and_skip_hidden_actors() {
        let runners = [
            runner("a", true),
            runner("b", false),
            runner("c", true),
            runner("d", true),
        ];
        let member = |id: &str, layer, variant| Member {
            id: id.into(),
            layer,
            variant,
        };
        let members = [
            member("a", 7, 3),
            member("b", 8, 0),
            member("c", 9, 0),
            member("d", 10, 1),
        ];
        let mut out = Vec::new();
        let mut ranges = std::array::from_fn(|_| 0..0);
        fill_instances(&runners, &members, &mut out, &mut ranges);
        assert_eq!(ranges, [0..1, 1..2, 2..2, 2..3]);
        assert_eq!(out.iter().map(|i| i.layer).collect::<Vec<_>>(), [9, 10, 7]);
        assert_eq!(
            out.iter().map(|i| i.arm).collect::<Vec<_>>(),
            [6., 5.5, 5.5]
        );
        assert_eq!(out[0].origin, [runners[2].x, runners[2].y, runners[2].z]);
        assert_eq!(
            out[0].stride,
            ((runners[2].phase as f64).sin() as f32) * 0.8
        );
    }
    #[test]
    fn instance_layout_matches_vertex_attributes() {
        assert_eq!(std::mem::size_of::<Instance>(), 40);
        let attributes = instance_attributes();
        let offsets = attributes.map(|a| a.offset as usize);
        assert_eq!(
            offsets,
            [
                std::mem::offset_of!(Instance, origin),
                std::mem::offset_of!(Instance, yaw),
                std::mem::offset_of!(Instance, stride),
                std::mem::offset_of!(Instance, arm),
                std::mem::offset_of!(Instance, layer),
            ]
        );
    }
    #[test]
    fn variant_index_matches_player_mesh_order() {
        assert_eq!(variant(false, false), 0);
        assert_eq!(variant(true, false), 1);
        assert_eq!(variant(false, true), 2);
        assert_eq!(variant(true, true), 3);
    }
}
