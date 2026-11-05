//! Minecraft fancy cloud geometry and upload-time whole-cloud thinning, as in the Kotlin reference.
use super::{diagnostics, terrain::hash01, textures};
use anyhow::Result;
use glam::Vec3;

#[derive(Clone, Copy, Debug)]
pub struct Coordinates {
    pub position: [f64; 3],
    pub fraction: [f32; 3],
    pub floor: [i32; 3],
}
pub fn coordinates(time: f64, eye: Vec3) -> Coordinates {
    let x = ((eye.x as f64 + time * 0.6) / 12.).rem_euclid(2048.);
    let y = 220. - eye.y as f64 + 0.33;
    let z = (eye.z as f64 / 12. + 0.33).rem_euclid(2048.);
    let floor = [x.floor() as i32, (y / 4.).floor() as i32, z.floor() as i32];
    Coordinates {
        position: [x, y, z],
        fraction: [
            (x - x.floor()) as f32,
            ((y / 4. - (y / 4.).floor()) * 4.) as f32,
            (z - z.floor()) as f32,
        ],
        floor,
    }
}
pub fn thin_cover(image: &mut image::RgbaImage, keep: f64) {
    let (w, h) = image.dimensions();
    let count = (w * h) as usize;
    let mut visited = vec![false; count];
    let mut queue = Vec::with_capacity(count);
    for start in 0..count {
        if visited[start] || image.as_raw()[start * 4 + 3] < 26 {
            continue;
        }
        queue.clear();
        queue.push(start);
        visited[start] = true;
        let mut head = 0;
        while head < queue.len() {
            let index = queue[head];
            head += 1;
            let x = index % w as usize;
            let y = index / w as usize;
            for next in [
                y * w as usize + (x + 1) % w as usize,
                y * w as usize + (x + w as usize - 1) % w as usize,
                ((y + 1) % h as usize) * w as usize + x,
                ((y + h as usize - 1) % h as usize) * w as usize + x,
            ] {
                if !visited[next] && image.as_raw()[next * 4 + 3] >= 26 {
                    visited[next] = true;
                    queue.push(next);
                }
            }
        }
        if hash01(
            (start % w as usize) as i32,
            (start / w as usize) as i32,
            0xC10D,
        ) >= keep
        {
            for &i in &queue {
                image.as_mut()[i * 4 + 3] = 0;
            }
        }
    }
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Vertex {
    pub position: [f32; 3],
    pub uv: [f32; 2],
    pub color: [f32; 4],
    pub normal: [f32; 3],
}
pub fn mesh(c: Coordinates) -> Vec<Vertex> {
    let mut out = Vec::new();
    let base = (c.position[1] / 4.).floor() as f32 * 4.;
    let texture = [
        c.position[0].floor() as f32 / 256.,
        c.position[2].floor() as f32 / 256.,
    ];
    let mut quad = |points: [[f32; 3]; 4], uv: [f32; 2], normal: [f32; 3]| {
        for i in [0, 1, 2, 0, 2, 3] {
            let p = points[i];
            out.push(Vertex {
                position: p,
                uv: [
                    uv[0] + (p[0] - points[0][0]) / 256.,
                    uv[1] + (p[2] - points[0][2]) / 256.,
                ],
                color: [1., 1., 1., 0.8],
                normal,
            });
        }
    };
    for tx in -6..=6 {
        for tz in -6..=6 {
            let x = tx as f32 * 8.;
            let z = tz as f32 * 8.;
            let uv = [x / 256. + texture[0], (z + 8.) / 256. + texture[1]];
            if base > -5. {
                quad(
                    [
                        [x, base, z + 8.],
                        [x + 8., base, z + 8.],
                        [x + 8., base, z],
                        [x, base, z],
                    ],
                    uv,
                    [0., -1., 0.],
                );
            }
            if base <= 5. {
                let top = base + 4. - 0.0009765625;
                quad(
                    [
                        [x, top, z + 8.],
                        [x + 8., top, z + 8.],
                        [x + 8., top, z],
                        [x, top, z],
                    ],
                    uv,
                    [0., 1., 0.],
                );
            }
            for face in 0..4 {
                for slice in 0..8 {
                    let s = slice as f32;
                    if face == 0 && tx > -1 {
                        let sx = x + s;
                        quad(
                            [
                                [sx, base, z + 8.],
                                [sx, base + 4., z + 8.],
                                [sx, base + 4., z],
                                [sx, base, z],
                            ],
                            [(sx + 0.5) / 256. + texture[0], (z + 8.) / 256. + texture[1]],
                            [-1., 0., 0.],
                        );
                    }
                    if face == 1 && tx <= 1 {
                        let sx = x + s + 1. - 0.0009765625;
                        quad(
                            [
                                [sx, base, z + 8.],
                                [sx, base + 4., z + 8.],
                                [sx, base + 4., z],
                                [sx, base, z],
                            ],
                            [
                                (x + s + 0.5) / 256. + texture[0],
                                (z + 8.) / 256. + texture[1],
                            ],
                            [1., 0., 0.],
                        );
                    }
                    if face == 2 && tz > -1 {
                        let sz = z + s;
                        quad(
                            [
                                [x, base + 4., sz],
                                [x + 8., base + 4., sz],
                                [x + 8., base, sz],
                                [x, base, sz],
                            ],
                            [x / 256. + texture[0], (sz + 0.5) / 256. + texture[1]],
                            [0., 0., -1.],
                        );
                    }
                    if face == 3 && tz <= 1 {
                        let sz = z + s + 1. - 0.0009765625;
                        quad(
                            [
                                [x, base + 4., sz],
                                [x + 8., base + 4., sz],
                                [x + 8., base, sz],
                                [x, base, sz],
                            ],
                            [x / 256. + texture[0], (z + s + 0.5) / 256. + texture[1]],
                            [0., 0., 1.],
                        );
                    }
                }
            }
        }
    }
    out
}
pub struct Clouds {
    buffer: wgpu::Buffer,
    count: u32,
    floor: [i32; 3],
    capacity: u64,
    group: wgpu::BindGroup,
    depth: wgpu::RenderPipeline,
    color: wgpu::RenderPipeline,
    reflection_depth: wgpu::RenderPipeline,
    reflection_color: wgpu::RenderPipeline,
}
impl Clouds {
    pub fn new(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        frame_layout: &wgpu::BindGroupLayout,
        samples: u32,
    ) -> Result<Self> {
        let mut image=image::load_from_memory(include_bytes!("../../../../../bg-renderer/src/main/resources/assets/minecraft/textures/environment/clouds.png"))?.to_rgba8();
        thin_cover(&mut image, 0.45);
        let view = textures::upload_image(device, queue, image, "scene clouds SRGB", true, false);
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene clouds"),
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
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene nearest repeat clouds"),
            address_mode_u: wgpu::AddressMode::Repeat,
            address_mode_v: wgpu::AddressMode::Repeat,
            ..Default::default()
        });
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene clouds"),
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
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("scene clouds"),
            source: wgpu::ShaderSource::Wgsl(
                concat!(include_str!("common.wgsl"), include_str!("clouds.wgsl")).into(),
            ),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("scene clouds"),
            bind_group_layouts: &[Some(frame_layout), Some(&layout)],
            immediate_size: 0,
        });
        let attributes =
            wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x4,3=>Float32x3];
        let make = |count, depth_only| {
            let start = std::time::Instant::now();
            let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("scene cloud depth/color"),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState {
                    module: &shader,
                    entry_point: Some("vs"),
                    compilation_options: Default::default(),
                    buffers: &[Some(wgpu::VertexBufferLayout {
                        array_stride: 48,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &attributes,
                    })],
                },
                primitive: wgpu::PrimitiveState {
                    cull_mode: None,
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
                    count,
                    ..Default::default()
                },
                fragment: Some(wgpu::FragmentState {
                    module: &shader,
                    entry_point: Some("fs"),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: super::HDR,
                        blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                        write_mask: if depth_only {
                            wgpu::ColorWrites::empty()
                        } else {
                            wgpu::ColorWrites::ALL
                        },
                    })],
                }),
                multiview_mask: None,
                cache: None,
            });
            tracing::info!(
                pipeline = "cloud",
                count,
                depth_only,
                compile_ms = start.elapsed().as_secs_f64() * 1000.,
                "Scene pipeline compiled"
            );
            pipeline
        };
        let depth = make(samples, true);
        let color = make(samples, false);
        let reflection_depth = make(1, true);
        let reflection_color = make(1, false);
        let capacity = 6 * (169 * 2 + 3120) * 48;
        diagnostics::resource("scene cloud vertex capacity", capacity);
        let buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("scene cloud vertices"),
            size: capacity,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let mut result = Self {
            buffer,
            count: 0,
            floor: [i32::MIN; 3],
            capacity,
            group,
            depth,
            color,
            reflection_depth,
            reflection_color,
        };
        result.update(queue, coordinates(0., Vec3::new(0.5, 90.5, 14.)))?;
        Ok(result)
    }
    pub fn update(&mut self, queue: &wgpu::Queue, c: Coordinates) -> Result<()> {
        if self.floor == c.floor {
            return Ok(());
        }
        let vertices = mesh(c);
        let bytes = bytemuck::cast_slice(&vertices);
        anyhow::ensure!(
            bytes.len() as u64 <= self.capacity,
            "cloud mesh exceeded fixed capacity"
        );
        queue.write_buffer(&self.buffer, 0, bytes);
        self.count = vertices.len() as u32;
        self.floor = c.floor;
        Ok(())
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>, frame: &wgpu::BindGroup, reflection: bool) {
        pass.set_bind_group(
            0,
            frame,
            &[if reflection {
                super::state::FRAME_STRIDE
            } else {
                0
            }],
        );
        pass.set_bind_group(1, &self.group, &[]);
        pass.set_vertex_buffer(0, self.buffer.slice(..));
        pass.set_pipeline(if reflection {
            &self.reflection_depth
        } else {
            &self.depth
        });
        pass.draw(0..self.count, 0..1);
        pass.set_pipeline(if reflection {
            &self.reflection_color
        } else {
            &self.color
        });
        pass.draw(0..self.count, 0..1);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn coordinates_wrap_and_reflection_height() {
        let eye = Vec3::new(0.5, 90.5, 14.);
        let c = coordinates(0., eye);
        assert_eq!(c.floor, [0, 32, 1]);
        assert!((c.fraction[1] - 1.83).abs() < 1e-5);
        let a = super::super::state::Params::at(0.);
        let b = super::super::state::Params::for_pass(0., true);
        assert!((a.eye[1] + b.eye[1] - 4.7).abs() < 1e-5);
        assert!((b.cloud[2] - a.cloud[2] - a.eye[1] + b.eye[1]).abs() < 1e-5);
        for t in [0., 15., 45., 1e9] {
            let c = coordinates(t, eye);
            assert!((0.0..2048.0).contains(&c.position[0]));
            assert!(c.fraction.into_iter().all(|v| (0.0..4.0).contains(&v)));
        }
    }
    #[test]
    fn thinning_keeps_toroidal_components_together() {
        let mut image = image::RgbaImage::new(4, 3);
        for x in [0, 3] {
            image.put_pixel(x, 0, image::Rgba([255; 4]));
        }
        thin_cover(&mut image, 0.45);
        assert_eq!(image.get_pixel(0, 0)[3], image.get_pixel(3, 0)[3]);
        thin_cover(&mut image, 0.);
        assert!(image.pixels().all(|p| p[3] == 0));
    }
    #[test]
    fn fancy_cloud_mesh_layout_and_count() {
        let vertices = mesh(coordinates(0., Vec3::new(0.5, 90.5, 14.)));
        assert_eq!(std::mem::size_of::<Vertex>(), 48);
        assert_eq!(vertices.len(), 19734);
        assert!(
            vertices
                .iter()
                .all(|v| v.position.into_iter().chain(v.uv).all(f32::is_finite))
        );
    }
}
