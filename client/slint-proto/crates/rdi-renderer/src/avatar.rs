//! U4e2: the skin preview's player (Compose `PlayerModel` with `autoRotate`, `animateWalk` and
//! the outer layer). Renders one player into an offscreen RGBA texture that Slint shows as an
//! image. Two output textures alternate, so every new frame is a different image to Slint.
use crate::scene::{
    self,
    player::{Appearance, Skin},
    player_geometry as geometry,
};
use anyhow::{Result, ensure};
use glam::{Mat4, Vec3};
use wgpu::util::DeviceExt;

const FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8Unorm;
const SOURCE: &str = include_str!("avatar.wgsl");
/// One turn every eight seconds and a slow walk, close to the Compose preview.
const TURN_RADIANS_PER_SECOND: f32 = std::f32::consts::TAU / 8.;
const WALK_RADIANS_PER_SECOND: f32 = 5.5;
const LEG_SWING: f32 = 0.55;
const ARM_SWING: f32 = 0.45;

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Params {
    vp: [[f32; 4]; 4],
    model: [[f32; 4]; 4],
    bones: [[[f32; 4]; 4]; 7],
    traits: [f32; 4],
}

fn pivot(position: Vec3, pitch: f32) -> Mat4 {
    Mat4::from_translation(position) * Mat4::from_rotation_x(pitch) * Mat4::from_translation(-position)
}

/// The pose at `seconds`: turned by the rotation, limbs swinging opposite each other.
fn params(seconds: f64, slim: bool, cape: bool, aspect: f32) -> Params {
    let t = seconds as f32;
    let swing = (t * WALK_RADIANS_PER_SECOND).sin();
    let arm = if slim { 5.5 } else { 6. };
    let bones = [
        Mat4::IDENTITY,
        Mat4::IDENTITY,
        pivot(Vec3::new(-arm, 22., 0.), swing * ARM_SWING),
        pivot(Vec3::new(arm, 22., 0.), -swing * ARM_SWING),
        pivot(Vec3::new(-1.9, 12., 0.), -swing * LEG_SWING),
        pivot(Vec3::new(1.9, 12., 0.), swing * LEG_SWING),
        pivot(Vec3::new(0., 24., -2.1), 0.12 + swing.abs() * 0.18),
    ];
    let model = Mat4::from_rotation_y(t * TURN_RADIANS_PER_SECOND);
    let view = glam::camera::rh::view::look_at_mat4(Vec3::new(0., 20., 78.), Vec3::new(0., 16., 0.), Vec3::Y);
    let projection = glam::camera::rh::proj::directx::perspective(30f32.to_radians(), aspect, 1., 200.);
    Params {
        vp: (projection * view).to_cols_array_2d(),
        model: model.to_cols_array_2d(),
        bones: bones.map(|m| m.to_cols_array_2d()),
        traits: [cape as u8 as f32, 0., 0., 0.],
    }
}

struct Loaded {
    vertices: wgpu::Buffer,
    count: u32,
    group: wgpu::BindGroup,
    slim: bool,
    cape: bool,
}

struct Target {
    output: wgpu::Texture,
    view: wgpu::TextureView,
}

pub struct Renderer {
    device: wgpu::Device,
    queue: wgpu::Queue,
    pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    uniforms: wgpu::Buffer,
    loaded: Option<Loaded>,
    size: (u32, u32),
    targets: Vec<Target>,
    depth: Option<wgpu::TextureView>,
    next: usize,
}

impl Renderer {
    pub fn new(device: wgpu::Device, queue: wgpu::Queue) -> Result<Self> {
        scene::checked(&device.clone(), || Self::create(device, queue))
    }

    fn create(device: wgpu::Device, queue: wgpu::Queue) -> Result<Self> {
        let texture_entry = |binding| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("avatar"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
                texture_entry(1),
                texture_entry(2),
                wgpu::BindGroupLayoutEntry {
                    binding: 3,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("avatar"),
            source: wgpu::ShaderSource::Wgsl(SOURCE.into()),
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("avatar"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let attributes = wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x3,3=>Float32,4=>Float32,5=>Float32];
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("avatar"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[Some(wgpu::VertexBufferLayout {
                    array_stride: std::mem::size_of::<geometry::Vertex>() as u64,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &attributes,
                })],
            },
            // The cape and the outer layer are seen from both sides.
            primitive: wgpu::PrimitiveState { cull_mode: None, ..Default::default() },
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
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor::default());
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("avatar params"),
            size: std::mem::size_of::<Params>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Ok(Self { device, queue, pipeline, layout, sampler, uniforms, loaded: None, size: (0, 0), targets: vec![], depth: None, next: 0 })
    }

    fn texture(&self, label: &str, image: &image::RgbaImage) -> wgpu::Texture {
        let (width, height) = image.dimensions();
        self.device.create_texture_with_data(
            &self.queue,
            &wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: FORMAT,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            },
            wgpu::util::TextureDataOrder::LayerMajor,
            image.as_raw(),
        )
    }

    /// Replaces the shown skin and cape; both must be normalized (`player::normalize`).
    pub fn set_appearance(&mut self, appearance: Appearance) -> Result<()> {
        let Appearance { skin: Skin { pixels, slim }, cape } = appearance;
        let (width, height) = pixels.dimensions();
        ensure!(width == 64 && (height == 32 || height == 64), "Avatar skin must be normalized");
        let skin = self.texture("avatar skin", &pixels);
        let has_cape = cape.is_some();
        let cape = self.texture("avatar cape", &cape.unwrap_or_else(|| image::RgbaImage::new(64, 32)));
        let mesh = geometry::mesh(slim, height == 32, true, has_cape);
        let vertices = self.device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("avatar mesh"),
            contents: bytemuck::cast_slice(&mesh),
            usage: wgpu::BufferUsages::VERTEX,
        });
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("avatar"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: self.uniforms.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&skin.create_view(&Default::default())) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(&cape.create_view(&Default::default())) },
                wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::Sampler(&self.sampler) },
            ],
        });
        self.loaded = Some(Loaded { vertices, count: mesh.len() as u32, group, slim, cape: has_cape });
        Ok(())
    }

    pub fn has_appearance(&self) -> bool {
        self.loaded.is_some()
    }

    /// Drops the player, so the next preview starts empty.
    pub fn clear(&mut self) {
        self.loaded = None;
    }

    fn resize(&mut self, width: u32, height: u32) {
        if self.size == (width, height) && !self.targets.is_empty() {
            return;
        }
        let make = |label, format, usage| {
            self.device.create_texture(&wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format,
                usage,
                view_formats: &[],
            })
        };
        self.targets = (0..2)
            .map(|_| {
                let output = make("avatar output", FORMAT, wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_SRC);
                let view = output.create_view(&Default::default());
                Target { output, view }
            })
            .collect();
        self.depth = Some(make("avatar depth", wgpu::TextureFormat::Depth32Float, wgpu::TextureUsages::RENDER_ATTACHMENT).create_view(&Default::default()));
        self.size = (width, height);
    }

    /// Draws the player at `seconds` and returns the texture to show; `None` before an
    /// appearance was set.
    pub fn render(&mut self, seconds: f64, width: u32, height: u32) -> Result<Option<wgpu::Texture>> {
        let Some(loaded) = &self.loaded else { return Ok(None) };
        let (slim, cape) = (loaded.slim, loaded.cape);
        let (width, height) = (width.clamp(16, 2048), height.clamp(16, 2048));
        self.resize(width, height);
        let params = params(seconds, slim, cape, width as f32 / height as f32);
        self.queue.write_buffer(&self.uniforms, 0, bytemuck::bytes_of(&params));
        let target = &self.targets[self.next];
        self.next = (self.next + 1) % self.targets.len();
        let Some(loaded) = &self.loaded else { return Ok(None) };
        let mut encoder = self.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("avatar") });
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("avatar"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &target.view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations { load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT), store: wgpu::StoreOp::Store },
                })],
                depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                    view: self.depth.as_ref().expect("depth target"),
                    depth_ops: Some(wgpu::Operations { load: wgpu::LoadOp::Clear(1.), store: wgpu::StoreOp::Discard }),
                    stencil_ops: None,
                }),
                ..Default::default()
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &loaded.group, &[]);
            pass.set_vertex_buffer(0, loaded.vertices.slice(..));
            pass.draw(0..loaded.count, 0..1);
        }
        self.queue.submit([encoder.finish()]);
        Ok(Some(target.output.clone()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shader_validates() {
        let module = naga::front::wgsl::parse_str(SOURCE).unwrap();
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all())
            .validate(&module)
            .unwrap();
    }

    /// Renders Steve with a cape offscreen and reads it back: the middle column holds the player,
    /// the corners stay transparent. Skipped without a GPU adapter; `AVATAR_CAPTURE_PATH` saves
    /// the frame as a PNG.
    #[test]
    fn draws_a_player_on_a_transparent_background() {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: wgpu::Backends::from_env().unwrap_or_default(),
            flags: wgpu::InstanceFlags::from_build_config().with_env(),
            backend_options: wgpu::BackendOptions::from_env_or_default(),
            memory_budget_thresholds: wgpu::MemoryBudgetThresholds::default(),
            display: None,
        });
        let Ok(adapter) = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions::default())) else {
            eprintln!("no GPU adapter; skipped");
            return;
        };
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default())).expect("device");
        let mut renderer = Renderer::new(device.clone(), queue.clone()).expect("renderer");
        assert!(renderer.render(0., 60, 90).unwrap().is_none(), "nothing before an appearance");
        let mut steve = Appearance::steve().unwrap();
        steve.cape = Some(image::RgbaImage::from_pixel(64, 32, image::Rgba([200, 30, 30, 255])));
        renderer.set_appearance(steve).unwrap();
        let (width, height) = (300u32, 450u32);
        let texture = renderer.render(1.3, width, height).unwrap().expect("frame");
        let second = renderer.render(1.4, width, height).unwrap().expect("frame");
        assert_ne!(texture, second, "frames alternate between two textures");
        let row = 4 * width;
        let aligned = row.next_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
        let buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: None,
            size: u64::from(aligned) * u64::from(height),
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            texture.as_image_copy(),
            wgpu::TexelCopyBufferInfo {
                buffer: &buffer,
                layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(aligned), rows_per_image: Some(height) },
            },
            wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
        );
        queue.submit([encoder.finish()]);
        buffer.slice(..).map_async(wgpu::MapMode::Read, |r| r.unwrap());
        device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
        let data = buffer.slice(..).get_mapped_range().expect("mapped").to_vec();
        let pixel = |x: u32, y: u32| {
            let i = (y * aligned + x * 4) as usize;
            [data[i], data[i + 1], data[i + 2], data[i + 3]]
        };
        assert_eq!(pixel(2, 2)[3], 0, "transparent corner");
        assert_eq!(pixel(width - 3, height - 3)[3], 0, "transparent corner");
        let opaque = (0..height).filter(|y| pixel(width / 2, *y)[3] > 200).count();
        assert!(opaque as u32 > height / 2, "the player fills the middle column: {opaque} rows");
        if let Ok(path) = std::env::var("AVATAR_CAPTURE_PATH") {
            let mut image = image::RgbaImage::new(width, height);
            for (x, y, p) in image.enumerate_pixels_mut() {
                *p = image::Rgba(pixel(x, y));
            }
            image.save(path).unwrap();
        }
    }

    #[test]
    fn legacy_skins_hide_an_opaque_hat_layer() {
        use crate::scene::player::notch_transparency;
        let mut filled = image::RgbaImage::from_pixel(64, 32, image::Rgba([0, 0, 0, 255]));
        notch_transparency(&mut filled);
        assert_eq!(filled.get_pixel(40, 8)[3], 0, "an opaque hat is hidden");
        assert_eq!(filled.get_pixel(8, 8)[3], 255, "the face stays");
        let mut drawn = image::RgbaImage::from_pixel(64, 32, image::Rgba([0, 0, 0, 255]));
        drawn.get_pixel_mut(33, 1)[3] = 0;
        notch_transparency(&mut drawn);
        assert_eq!(drawn.get_pixel(40, 8)[3], 255, "a hat with transparency is kept");
        let mut modern = image::RgbaImage::from_pixel(64, 64, image::Rgba([0, 0, 0, 255]));
        notch_transparency(&mut modern);
        assert_eq!(modern.get_pixel(40, 8)[3], 255, "only legacy skins");
    }

    #[test]
    fn params_match_the_shader_layout_and_swing_limbs() {
        assert_eq!(std::mem::size_of::<Params>(), 64 * 9 + 16);
        let a = params(0.3, false, true, 2. / 3.);
        // Arms and legs swing opposite each other; head and body stay still.
        assert_eq!(a.bones[0], Mat4::IDENTITY.to_cols_array_2d());
        assert_ne!(a.bones[2], a.bones[3]);
        assert_ne!(a.bones[4], a.bones[5]);
        assert_eq!(a.traits[0], 1.);
        assert_eq!(params(0.3, false, false, 1.).traits[0], 0.);
        // The model turns over time.
        assert_ne!(params(0., false, false, 1.).model, params(1., false, false, 1.).model);
    }
}
