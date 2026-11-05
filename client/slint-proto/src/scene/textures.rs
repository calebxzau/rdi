//! Reference PNGs are embedded by path; color mips are averaged in linear light.
use super::{diagnostics, mesh::Material, state::srgb_to_linear};
use anyhow::{Context, Result};
use slint::wgpu_30::wgpu;
macro_rules! block {
    ($n:literal) => {
        include_bytes!(concat!(
            "../../../bg-renderer/src/main/resources/assets/minecraft/textures/block/",
            $n,
            ".png"
        ))
        .as_slice()
    };
}
pub fn bytes(m: Material) -> &'static [u8] {
    match m {
        Material::GrassTop => block!("grass_block_top"),
        Material::GrassSide => block!("grass_block_side"),
        Material::Dirt => block!("dirt"),
        Material::Stone => block!("stone"),
        Material::Snow => block!("snow"),
        Material::Sand => block!("sand"),
        Material::Gravel => block!("gravel"),
        Material::OakPlanks => block!("oak_planks"),
        Material::OakLog => block!("oak_log"),
        Material::OakLogTop => block!("oak_log_top"),
        Material::BirchLog => block!("birch_log"),
        Material::SpruceLog => block!("spruce_log"),
        Material::OakLeaves => block!("oak_leaves"),
        Material::BirchLeaves => block!("birch_leaves"),
        Material::SpruceLeaves => block!("spruce_leaves"),
        Material::ShortGrass => block!("short_grass"),
        Material::SprucePlanks => block!("spruce_planks"),
        Material::Cobblestone => block!("cobblestone"),
        Material::Glowstone => block!("glowstone"),
        Material::Lantern => block!("lantern"),
    }
}
fn srgb(v: f32) -> f32 {
    if v <= 0.0031308 {
        v * 12.92
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    }
}
fn reference_rgba(bytes: &[u8], label: &str) -> Result<image::RgbaImage> {
    let decoded =
        image::load_from_memory(bytes).with_context(|| format!("decode bundled {label}"))?;
    let grayscale = decoded.color() == image::ColorType::L8;
    let mut rgba = decoded.to_rgba8();
    if grayscale {
        // ImageIO's TYPE_BYTE_GRAY -> getRGB converts linear CS_GRAY samples to sRGB.
        // Match those uploaded bytes for grayscale PNGs only; palette/RGB stays unchanged.
        for p in rgba.pixels_mut() {
            let value = (srgb(p[0] as f32 / 255.0) * 255.0).round() as u8;
            p.0[..3].fill(value);
        }
    }
    Ok(rgba)
}
pub fn mip_chain(image: image::RgbaImage) -> Vec<image::RgbaImage> {
    mip_chain_encoding(image, true)
}
fn mip_chain_encoding(mut image: image::RgbaImage, color: bool) -> Vec<image::RgbaImage> {
    let mut out = vec![];
    loop {
        let (w, h) = image.dimensions();
        if w == 1 && h == 1 {
            out.push(image);
            break;
        }
        let (nw, nh) = ((w / 2).max(1), (h / 2).max(1));
        let mut next = image::RgbaImage::new(nw, nh);
        // Area filtering also covers the last row of odd-sized mips (the lantern reaches1x3).
        for y in 0..nh {
            for x in 0..nw {
                let (x0, x1, y0, y1) = (
                    x as f32 * w as f32 / nw as f32,
                    (x + 1) as f32 * w as f32 / nw as f32,
                    y as f32 * h as f32 / nh as f32,
                    (y + 1) as f32 * h as f32 / nh as f32,
                );
                let mut sum = [0.0; 4];
                let area = (x1 - x0) * (y1 - y0);
                for sy in y0.floor() as u32..y1.ceil() as u32 {
                    for sx in x0.floor() as u32..x1.ceil() as u32 {
                        let weight = (x1.min(sx as f32 + 1.0) - x0.max(sx as f32))
                            * (y1.min(sy as f32 + 1.0) - y0.max(sy as f32))
                            / area;
                        let p = image.get_pixel(sx.min(w - 1), sy.min(h - 1));
                        for i in 0..3 {
                            let value = p[i] as f32 / 255.;
                            sum[i] += if color {
                                srgb_to_linear(value) * weight
                            } else {
                                value * weight
                            };
                        }
                        sum[3] += p[3] as f32 * weight;
                    }
                }
                next.put_pixel(
                    x,
                    y,
                    image::Rgba([
                        ((if color { srgb(sum[0]) } else { sum[0] }) * 255.0).round() as u8,
                        ((if color { srgb(sum[1]) } else { sum[1] }) * 255.0).round() as u8,
                        ((if color { srgb(sum[2]) } else { sum[2] }) * 255.0).round() as u8,
                        sum[3].round() as u8,
                    ]),
                );
            }
        }
        out.push(image);
        image = next;
    }
    out
}
pub fn upload(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    bytes: &[u8],
    label: &str,
) -> Result<wgpu::TextureView> {
    let image = reference_rgba(bytes, label)?;
    Ok(upload_image(device, queue, image, label, true, true))
}
pub fn upload_data(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    bytes: &[u8],
    label: &str,
) -> Result<wgpu::TextureView> {
    let image = image::load_from_memory(bytes)
        .with_context(|| format!("decode bundled {label}"))?
        .to_rgba8();
    Ok(upload_image(device, queue, image, label, false, true))
}
pub fn upload_image(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    image: image::RgbaImage,
    label: &str,
    color: bool,
    mipmaps: bool,
) -> wgpu::TextureView {
    let (w, h) = image.dimensions();
    let mips = if mipmaps {
        if color {
            mip_chain(image)
        } else {
            mip_chain_encoding(image, false)
        }
    } else {
        vec![image]
    };
    diagnostics::resource(label, mips.iter().map(|i| i.as_raw().len() as u64).sum());
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some(label),
        size: wgpu::Extent3d {
            width: w,
            height: h,
            depth_or_array_layers: 1,
        },
        mip_level_count: mips.len() as u32,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: if color {
            wgpu::TextureFormat::Rgba8UnormSrgb
        } else {
            wgpu::TextureFormat::Rgba8Unorm
        },
        usage: wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    for (i, image) in mips.iter().enumerate() {
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &texture,
                mip_level: i as u32,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
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
        );
    }
    texture.create_view(&Default::default())
}
pub const SUN: &[u8] = include_bytes!(
    "../../../bg-renderer/src/main/resources/assets/minecraft/textures/environment/sun.png"
);
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn grayscale_matches_imageio_upload_without_changing_palette_rgba() {
        let grass = reference_rgba(bytes(Material::GrassTop), "grass").unwrap();
        assert_eq!(grass.get_pixel(0, 0).0, [200, 200, 200, 255]);
        let palette = bytes(Material::ShortGrass);
        assert_eq!(
            reference_rgba(palette, "tuft").unwrap(),
            image::load_from_memory(palette).unwrap().to_rgba8()
        );
    }
    #[test]
    fn mips_filter_srgb_in_linear_light() {
        let image = image::RgbaImage::from_raw(
            2,
            2,
            vec![
                0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 255, 255, 255, 255, 255,
            ],
        )
        .unwrap();
        let levels = mip_chain(image);
        assert_eq!(levels.len(), 2);
        assert!((levels[1].get_pixel(0, 0)[0] as i32 - 188).abs() <= 1);
    }
    #[test]
    fn water_data_mips_keep_linear_channel_values() {
        let image = image::RgbaImage::from_raw(
            2,
            2,
            vec![
                0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 255, 255, 255, 255, 255,
            ],
        )
        .unwrap();
        let levels = mip_chain_encoding(image, false);
        assert_eq!(levels[1].get_pixel(0, 0).0, [128, 128, 128, 255]);
    }
    #[test]
    fn odd_mip_includes_all_source_rows() {
        let image =
            image::RgbaImage::from_raw(1, 3, vec![0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255, 255])
                .unwrap();
        let levels = mip_chain(image);
        assert!((levels[1].get_pixel(0, 0)[0] as i32 - 156).abs() <= 1);
    }
}
