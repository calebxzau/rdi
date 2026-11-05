//! Stationary account avatar. Geometry/pipelines are built once on the loader worker.
use super::{diagnostics, player_geometry as geometry};
use anyhow::{Context, Result};
use image::RgbaImage;
use slint::wgpu_30::wgpu;
pub const SOURCE: &str = concat!(include_str!("common.wgsl"), include_str!("player.wgsl"));
pub const STEVE: &[u8] =
    include_bytes!("../../../ui/assets/src/main/resources/assets/skins/steve.png");
pub struct Skin {
    pub pixels: RgbaImage,
    pub slim: bool,
}
pub struct Appearance {
    pub skin: Skin,
    pub cape: Option<RgbaImage>,
}
pub fn normalize(image: RgbaImage, cape: bool) -> Result<RgbaImage> {
    let (w, h) = image.dimensions();
    anyhow::ensure!(
        (64..=2048).contains(&w) && w.is_multiple_of(64) && (h * 2 == w || (!cape && h == w)),
        "Unsupported player {} dimensions: {w}x{h}",
        if cape { "cape" } else { "skin" }
    );
    let height = if h == w { 64 } else { 32 };
    // Nearest source texels, exactly matching toBackgroundCrowdSkin (not centered filtering).
    Ok(RgbaImage::from_fn(64, height, |x, y| {
        *image.get_pixel(x * w / 64, y * h / height)
    }))
}
pub fn detect_slim(image: &RgbaImage) -> bool {
    if image.width() < 64 || image.height() < 64 {
        return false;
    }
    let transparent = (20..32)
        .map(|y| image.get_pixel(54, y)[3])
        .chain((52..64).map(|y| image.get_pixel(46, y)[3]))
        .filter(|&a| a < 16)
        .count();
    transparent >= 18
}
impl Skin {
    pub fn new(image: RgbaImage, slim: Option<bool>) -> Result<Self> {
        let pixels = normalize(image, false)?;
        let slim = slim.unwrap_or_else(|| detect_slim(&pixels));
        Ok(Self { pixels, slim })
    }
}
impl Appearance {
    pub fn steve() -> Result<Self> {
        Ok(Self {
            skin: Skin::new(
                image::load_from_memory(STEVE)
                    .context("Decode bundled Steve")?
                    .to_rgba8(),
                Some(false),
            )?,
            cape: None,
        })
    }
}
pub struct Player {
    vertices: wgpu::Buffer,
    ranges: [std::ops::Range<u32>; 4],
    uniforms: wgpu::Buffer,
    skin: wgpu::Texture,
    cape: wgpu::Texture,
    group: wgpu::BindGroup,
    main: wgpu::RenderPipeline,
    reflection: wgpu::RenderPipeline,
    slim: bool,
    legacy: bool,
    has_cape: bool,
}
impl Player {
    pub fn new(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        frame: &wgpu::BindGroupLayout,
        samples: u32,
        reflection_first: bool,
    ) -> Result<Self> {
        let mut data = Vec::new();
        let ranges = std::array::from_fn(|i| {
            let start = data.len() as u32;
            data.extend(geometry::mesh(i & 1 != 0, i & 2 != 0, true, true));
            start..data.len() as u32
        });
        let vertices = super::upload::buffer(
            device,
            queue,
            "scene player mesh variants",
            bytemuck::cast_slice(&data),
            wgpu::BufferUsages::VERTEX,
            false,
        )?;
        drop(data);
        let uniform_size = std::mem::size_of::<geometry::Params>() as u64;
        diagnostics::resource("scene player bones", uniform_size);
        let uniforms = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("scene player bones"),
            size: uniform_size,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let tex = |label, size| {
            diagnostics::resource(label, 64 * size as u64 * 4);
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some(label),
                size: wgpu::Extent3d {
                    width: 64,
                    height: size,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8UnormSrgb,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            })
        };
        let skin = tex("scene player skin SRGB", 64);
        let cape = tex("scene player cape SRGB", 32);
        let texture = wgpu::BindingType::Texture {
            sample_type: wgpu::TextureSampleType::Float { filterable: true },
            view_dimension: wgpu::TextureViewDimension::D2,
            multisampled: false,
        };
        let types = [
            wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Uniform,
                has_dynamic_offset: false,
                min_binding_size: std::num::NonZeroU64::new(uniform_size),
            },
            texture,
            texture,
            wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
        ];
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("scene player inputs"),
            entries: &types
                .into_iter()
                .enumerate()
                .map(|(i, ty)| wgpu::BindGroupLayoutEntry {
                    binding: i as u32,
                    visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                    ty,
                    count: None,
                })
                .collect::<Vec<_>>(),
        });
        let skin_view = skin.create_view(&Default::default());
        let cape_view = cape.create_view(&Default::default());
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("scene player reference nearest clamp"),
            ..Default::default()
        });
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("scene player inputs"),
            layout: &layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: uniforms.as_entire_binding(),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&skin_view),
                },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::TextureView(&cape_view),
                },
                wgpu::BindGroupEntry {
                    binding: 3,
                    resource: wgpu::BindingResource::Sampler(&sampler),
                },
            ],
        });
        let make = |name, count| {
            let start = std::time::Instant::now();
            let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some(name),
                source: wgpu::ShaderSource::Wgsl(SOURCE.into()),
            });
            let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some(name),
                bind_group_layouts: &[Some(frame), Some(&layout)],
                immediate_size: 0,
            });
            let attributes = wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x3,3=>Float32,4=>Float32];
            let mut attributes = attributes;
            // The core's face-shade float at byte36 is intentionally unused by the source shader.
            attributes[4].offset = 40;
            let p = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some(name),
                layout: Some(&pipeline_layout),
                vertex: wgpu::VertexState {
                    module: &shader,
                    entry_point: Some("vs"),
                    compilation_options: Default::default(),
                    buffers: &[Some(wgpu::VertexBufferLayout {
                        array_stride: 44,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &attributes,
                    })],
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
            p
        };
        let (main, reflection) = if reflection_first {
            let reflection = make("scene player reflection", 1);
            (make("scene player main", samples), reflection)
        } else {
            let main = make("scene player main", samples);
            (main, make("scene player reflection", 1))
        };
        let mut player = Self {
            vertices,
            ranges,
            uniforms,
            skin,
            cape,
            group,
            main,
            reflection,
            slim: false,
            legacy: false,
            has_cape: false,
        };
        player.appearance(queue, Appearance::steve()?);
        Ok(player)
    }
    /// Shared with the crowd, which draws the same variants without their cape vertices.
    pub fn mesh(&self) -> (wgpu::Buffer, [std::ops::Range<u32>; 4]) {
        (self.vertices.clone(), self.ranges.clone())
    }
    /// Consumes decoded pixels; only GPU textures and scalar model flags survive this call.
    pub fn appearance(&mut self, queue: &wgpu::Queue, appearance: Appearance) {
        let write = |texture: &wgpu::Texture, image: &RgbaImage| {
            queue.write_texture(
                texture.as_image_copy(),
                image.as_raw(),
                wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(image.width() * 4),
                    rows_per_image: Some(image.height()),
                },
                wgpu::Extent3d {
                    width: image.width(),
                    height: image.height(),
                    depth_or_array_layers: 1,
                },
            )
        };
        write(&self.skin, &appearance.skin.pixels);
        self.slim = appearance.skin.slim;
        self.legacy = appearance.skin.pixels.height() == 32;
        self.has_cape = appearance.cape.is_some();
        if let Some(cape) = &appearance.cape {
            write(&self.cape, cape);
        }
        tracing::info!(
            slim = self.slim,
            legacy = self.legacy,
            cape = self.has_cape,
            "Player appearance uploaded; CPU pixels released"
        );
    }
    pub fn update(&self, queue: &wgpu::Queue, seconds: f64) {
        queue.write_buffer(
            &self.uniforms,
            0,
            bytemuck::bytes_of(&geometry::Params::at(seconds, self.slim, self.has_cape)),
        );
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>, frame: &wgpu::BindGroup, reflection: bool) {
        pass.set_pipeline(if reflection {
            &self.reflection
        } else {
            &self.main
        });
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
        pass.set_vertex_buffer(0, self.vertices.slice(..));
        let mut range = self.ranges[self.slim as usize + self.legacy as usize * 2].clone();
        if !self.has_cape {
            range.end -= 36;
        }
        pass.draw(range, 0..1);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn classic_slim_and_legacy_detection() {
        let mut image = RgbaImage::from_pixel(64, 64, image::Rgba([255; 4]));
        assert!(!detect_slim(&image));
        for y in 20..32 {
            image.get_pixel_mut(54, y)[3] = 0;
        }
        for y in 52..64 {
            image.get_pixel_mut(46, y)[3] = 0;
        }
        assert!(detect_slim(&image));
        assert!(!Skin::new(image.clone(), Some(false)).unwrap().slim);
        assert!(Skin::new(image, None).unwrap().slim);
        assert!(!detect_slim(&RgbaImage::new(64, 32)));
        assert!(!Appearance::steve().unwrap().skin.slim);
    }
    #[test]
    fn hd_normalization_matches_source_grid_and_rejects_invalid_input() {
        for height in [128, 64] {
            let image =
                RgbaImage::from_fn(128, height, |x, y| image::Rgba([x as u8, y as u8, 7, 128]));
            let output = normalize(image, false).unwrap();
            assert_eq!(output.dimensions(), (64, height / 2));
            assert_eq!(output.get_pixel(5, 7).0, [10, 14, 7, 128]);
        }
        for (w, h) in [(32, 32), (65, 64), (64, 48), (4096, 4096)] {
            assert!(normalize(RgbaImage::new(w, h), false).is_err());
        }
        assert!(normalize(RgbaImage::new(64, 64), true).is_err());
        assert_eq!(
            normalize(RgbaImage::new(128, 64), true)
                .unwrap()
                .dimensions(),
            (64, 32)
        );
    }
}
