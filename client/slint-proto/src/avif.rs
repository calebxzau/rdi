//! AVIF still images in pure Rust (J5 spike): `avif-parse` reads the container and
//! `rav1d-safe` (no assembly features) decodes AV1, so no C compiler, NASM or CMake is needed.
//! Plain `rav1d` 1.1.0 was rejected: corrupt AV1 data makes it panic inside an `extern "C"`
//! function, which aborts the launcher. Colors follow the image's own matrix and range;
//! Kotlin's swscale path treats every 8-bit image as limited range.
use anyhow::{Context, Result, ensure};
use rav1d_safe::{ColorRange, Decoder, Frame, MatrixCoefficients, PixelLayout, Planes, Settings};

const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
const MAX_DIMENSION: u32 = 16_384;
const MAX_PIXELS: u64 = 64 * 1024 * 1024;

/// The `ftyp` box names an AVIF brand, as Kotlin's `AvifCodec.isAvif` checks.
pub fn is_avif(bytes: &[u8]) -> bool {
    bytes.len() >= 12 && &bytes[4..8] == b"ftyp" && matches!(&bytes[8..12], b"avif" | b"avis")
}

pub struct Rgba {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

/// Decodes the primary image; `max_dimension` is checked from the sequence header first.
pub fn decode(bytes: &[u8], max_dimension: u32) -> Result<Rgba> {
    ensure!(bytes.len() <= MAX_INPUT_BYTES, "AVIF图片超过64MiB");
    let data = avif_parse::read_avif(&mut &bytes[..]).context("AVIF容器解析失败")?;
    let meta = data.primary_item_metadata().context("AV1序列头解析失败")?;
    let (w, h) = (meta.max_frame_width.get(), meta.max_frame_height.get());
    let limit = max_dimension.min(MAX_DIMENSION);
    ensure!(w <= limit && h <= limit, "AVIF尺寸过大：{w}x{h}");
    let color = Plane::decode(&data.primary_item).context("AVIF颜色解码失败")?;
    let alpha = match &data.alpha_item {
        Some(item) => Some(Plane::decode(item).context("AVIF透明通道解码失败")?),
        None => None,
    };
    if let Some(a) = &alpha {
        ensure!(a.width == color.width && a.height == color.height, "AVIF颜色和透明通道尺寸不同");
    }
    let mut pixels = color.to_rgba();
    if let Some(a) = alpha {
        for (i, px) in pixels.chunks_exact_mut(4).enumerate() {
            px[3] = a.alpha(i);
        }
        if data.premultiplied_alpha {
            for px in pixels.chunks_exact_mut(4) {
                let a = px[3] as u32;
                for c in &mut px[..3] {
                    *c = (*c as u32 * 255 + a / 2).checked_div(a).map_or(0, |v| v.min(255) as u8);
                }
            }
        }
    }
    Ok(Rgba { width: color.width, height: color.height, pixels })
}

/// One decoded AV1 frame, copied out so no decoder object outlives `decode`.
struct Plane {
    width: u32,
    height: u32,
    layout: PixelLayout,
    bpc: u32,
    full_range: bool,
    matrix: MatrixCoefficients,
    y: Vec<u16>,
    u: Vec<u16>,
    v: Vec<u16>,
    chroma_width: u32,
}

impl Plane {
    fn decode(obu: &[u8]) -> Result<Self> {
        let mut settings = Settings::default();
        settings.threads = 1;
        settings.frame_size_limit = MAX_PIXELS as u32;
        let mut decoder = Decoder::with_settings(settings).context("AV1解码器启动失败")?;
        let frame = match decoder.decode(obu).context("AV1数据无效")? {
            Some(frame) => frame,
            None => decoder
                .flush()
                .context("AV1解码失败")?
                .into_iter()
                .next()
                .context("AV1没有输出图像")?,
        };
        Self::copy(&frame)
    }

    fn copy(frame: &Frame) -> Result<Self> {
        let (width, height) = (frame.width(), frame.height());
        ensure!(
            (1..=MAX_DIMENSION).contains(&width)
                && (1..=MAX_DIMENSION).contains(&height)
                && width as u64 * height as u64 <= MAX_PIXELS,
            "AVIF尺寸不合理：{width}x{height}"
        );
        let bpc = frame.bit_depth() as u32;
        ensure!(matches!(bpc, 8 | 10 | 12), "不支持的AV1位深{bpc}");
        let info = frame.color_info();
        let layout = frame.pixel_layout();
        let chroma_width = match layout {
            PixelLayout::I400 => 0,
            PixelLayout::I420 | PixelLayout::I422 => width.div_ceil(2),
            _ => width,
        };
        macro_rules! planes {
            ($p:expr) => {{
                let collect = |rows: Vec<Vec<u16>>| rows.into_iter().flatten().collect::<Vec<u16>>();
                let y = collect($p.y().rows().map(|r| r.iter().map(|&v| v as u16).collect()).collect());
                let u = $p.u().map(|u| collect(u.rows().map(|r| r.iter().map(|&v| v as u16).collect()).collect()));
                let v = $p.v().map(|v| collect(v.rows().map(|r| r.iter().map(|&v| v as u16).collect()).collect()));
                (y, u.unwrap_or_default(), v.unwrap_or_default())
            }};
        }
        let (y, u, v) = match frame.planes() {
            Planes::Depth8(p) => planes!(p),
            Planes::Depth16(p) => planes!(p),
        };
        ensure!(y.len() == (width * height) as usize, "AV1亮度平面尺寸不符");
        if layout != PixelLayout::I400 {
            let rows = if layout == PixelLayout::I420 { height.div_ceil(2) } else { height };
            let expected = (chroma_width * rows) as usize;
            ensure!(u.len() == expected && v.len() == expected, "AV1色度平面尺寸不符");
        }
        Ok(Self {
            width,
            height,
            layout,
            bpc,
            full_range: info.color_range == ColorRange::Full,
            matrix: info.matrix_coefficients,
            y,
            u,
            v,
            chroma_width,
        })
    }

    fn max(&self) -> f32 {
        ((1u32 << self.bpc) - 1) as f32
    }

    /// Alpha planes are usually full range; a limited one is expanded.
    fn alpha(&self, index: usize) -> u8 {
        let raw = self.y[index] as f32;
        let scale = (1u32 << (self.bpc - 8)) as f32;
        let value = if self.full_range { raw / self.max() } else { (raw - 16.0 * scale) / (219.0 * scale) };
        (value * 255.0).round().clamp(0.0, 255.0) as u8
    }

    fn to_rgba(&self) -> Vec<u8> {
        // Kr/Kb per matrix; unspecified and others fall back to BT.601 as libavif does.
        let (kr, kb) = match self.matrix {
            MatrixCoefficients::BT709 => (0.2126, 0.0722),
            MatrixCoefficients::BT2020NCL => (0.2627, 0.0593),
            _ => (0.299, 0.114),
        };
        let kg = 1.0 - kr - kb;
        let max = self.max();
        let scale = (1u32 << (self.bpc - 8)) as f32;
        let mut out = Vec::with_capacity((self.width * self.height * 4) as usize);
        for py in 0..self.height {
            for px in 0..self.width {
                let yv = self.y[(py * self.width + px) as usize] as f32;
                let (mut u, mut v) = (128.0 * scale, 128.0 * scale);
                if self.layout != PixelLayout::I400 {
                    let cx = if self.chroma_width < self.width { px / 2 } else { px };
                    let cy = if self.layout == PixelLayout::I420 { py / 2 } else { py };
                    let i = (cy * self.chroma_width + cx) as usize;
                    u = self.u[i] as f32;
                    v = self.v[i] as f32;
                }
                let (yn, un, vn) = if self.full_range {
                    (yv / max, (u - 128.0 * scale) / max, (v - 128.0 * scale) / max)
                } else {
                    (
                        (yv - 16.0 * scale) / (219.0 * scale),
                        (u - 128.0 * scale) / (224.0 * scale),
                        (v - 128.0 * scale) / (224.0 * scale),
                    )
                };
                let (r, g, b) = if self.layout == PixelLayout::I400 {
                    (yn, yn, yn)
                } else if self.matrix == MatrixCoefficients::Identity {
                    (vn + 0.5, yn, un + 0.5)
                } else {
                    let r = yn + 2.0 * (1.0 - kr) * vn;
                    let b = yn + 2.0 * (1.0 - kb) * un;
                    (r, (yn - kr * r - kb * b) / kg, b)
                };
                for c in [r, g, b] {
                    out.push((c * 255.0).round().clamp(0.0, 255.0) as u8);
                }
                out.push(255);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pixel(image: &Rgba, x: u32, y: u32) -> [u8; 4] {
        let i = ((y * image.width + x) * 4) as usize;
        image.pixels[i..i + 4].try_into().unwrap()
    }

    fn near(actual: [u8; 4], expected: [u8; 4], tolerance: u8) {
        for (a, e) in actual.iter().zip(expected) {
            assert!(a.abs_diff(e) <= tolerance, "{actual:?} vs {expected:?}");
        }
    }

    /// 10-bit, limited range, unspecified matrix; expected values from FFmpeg's 8-bit
    /// BT.601 conversion of the same frame (its direct 10-bit swscale path differs by up to 48).
    #[test]
    fn decodes_ten_bit_limited_range() {
        let bytes = include_bytes!("../assets/sponsor.avif");
        assert!(is_avif(bytes));
        let image = decode(bytes, 8192).unwrap();
        assert_eq!((image.width, image.height, image.pixels.len()), (256, 256, 256 * 256 * 4));
        near(pixel(&image, 128, 128), [122, 248, 158, 255], 4);
    }

    /// 8-bit, full range, BT.709, encoded by FFmpeg/libaom from a known gradient.
    #[test]
    fn decodes_eight_bit_full_range_bt709() {
        let image = decode(include_bytes!("../protocol/fixtures/avif/synthetic-709-full.avif"), 8192).unwrap();
        assert_eq!((image.width, image.height), (64, 48));
        near(pixel(&image, 63, 47), [255, 252, 0, 255], 4);
        near(pixel(&image, 40, 30), [237, 160, 18, 255], 10);
        near(pixel(&image, 20, 10), [59, 80, 196, 255], 10);
    }

    #[test]
    fn rejects_oversized_and_broken_input() {
        let bytes = include_bytes!("../assets/sponsor.avif");
        assert!(decode(bytes, 255).err().unwrap().to_string().contains("尺寸过大"));
        // Truncation is not tested here: avif-parse debug-asserts on it (release returns Err).
        let mut flipped = bytes.to_vec();
        let mid = flipped.len() / 2;
        flipped[mid] ^= 0x55;
        let _ = decode(&flipped, 8192);
        assert!(!is_avif(b"\x89PNG\r\n\x1a\n0000"));
        assert!(decode(b"0000ftypavif", 8192).is_err());
    }
}
