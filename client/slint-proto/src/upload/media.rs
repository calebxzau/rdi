//! The upload's two encoders, stage A4: Kotlin `processUploadPng` (`FfmpegAvifEncoder`) and
//! `processOggBytes` (`OggTranscoder`), through `rdimedia`, the flat C entry points built into
//! the FFmpeg natives jar (`client/mediaproc/ffmpeg/rdimedia.c`). The libraries are the ones
//! the game uses, so both launchers encode with the same code and settings.
use super::archive::{EMPTY_OGG, Media, PNG_THRESHOLD};
use anyhow::{Context, Result, anyhow, bail, ensure};
use libloading::Library;
use std::ffi::{CStr, CString, c_char, c_int};
use std::path::{Path, PathBuf};

/// `rdi_media_abi` of the shim this code was written for.
const ABI: c_int = 1;
const OK: c_int = 0;
const TOO_LONG: c_int = 1;
const BAD_INPUT: c_int = 2;
/// `MAX_DURATION_MICROS`: longer sounds are music, which the game streams from the pack's own
/// files; they upload as the empty sound.
const MAX_SOUND_MICROS: i64 = 10_000_000;
/// `MAX_HEIGHT` and `MAX_PIXELS` of the Kotlin encoder.
const MAX_HEIGHT: u32 = 720;
const MAX_PIXELS: u64 = 64 * 1024 * 1024;
/// What the Kotlin page says when the encoder cannot be used at all.
pub const BROKEN: &str = "音频处理模块损坏，请更新客户端";
/// Each library needs only the ones before it.
const ORDER: [&str; 6] = ["avutil", "swresample", "swscale", "avcodec", "avformat", "rdimedia"];

type AbiFn = unsafe extern "C" fn() -> c_int;
type FreeFn = unsafe extern "C" fn(*mut u8);
type OggFn = unsafe extern "C" fn(*const c_char, *const c_char, i64, *mut c_char, usize) -> c_int;
type AvifFn = unsafe extern "C" fn(*const u8, c_int, c_int, c_int, c_int, *mut *mut u8, *mut usize, *mut c_char, usize) -> c_int;

pub struct Encoders {
    /// The shim first, so that it is released before the libraries it uses.
    _libraries: Vec<Library>,
    free: FreeFn,
    ogg: OggFn,
    avif: AvifFn,
    /// Sounds pass through files here.
    work_dir: PathBuf,
    threads: c_int,
}

fn load_library(path: &Path) -> Result<Library> {
    // SAFETY: the libraries are FFmpeg and our shim from the natives bundle the launcher
    // extracted itself; loading them runs no code of ours besides their initialisers.
    #[cfg(windows)]
    let library = unsafe { libloading::os::windows::Library::load_with_flags(path, libloading::os::windows::LOAD_WITH_ALTERED_SEARCH_PATH).map(Library::from) };
    #[cfg(not(windows))]
    let library = unsafe { Library::new(path) };
    library.with_context(|| format!("无法加载{}", path.display()))
}

/// The place in `ORDER` of a shared library file of the natives folder: `avutil-60.dll`,
/// `libavutil.so.60`, `libavutil.60.dylib`, `librdimedia.so`. Import libraries (`.lib`) and the
/// JNI libraries of the game (`jniavutil`) are not ours to load.
fn library_index(name: &str) -> Option<usize> {
    let shared = name.ends_with(".dll") || name.ends_with(".dylib") || name.ends_with(".so") || name.contains(".so.");
    if !shared {
        return None;
    }
    let base = name.strip_prefix("lib").unwrap_or(name);
    ORDER.iter().position(|wanted| base.strip_prefix(wanted).is_some_and(|rest| rest.starts_with(['-', '.'])))
}

fn message_text(buffer: &[u8]) -> String {
    CStr::from_bytes_until_nul(buffer).map(|c| c.to_string_lossy().into_owned()).unwrap_or_default()
}

impl Encoders {
    /// Loads the FFmpeg libraries of `natives` (the folder `play::runtime::prepare_natives`
    /// extracted) and the shim next to them. Natives without the shim are too old.
    pub fn load(natives: &Path, work_dir: &Path) -> Result<Self> {
        let mut found: Vec<(usize, PathBuf)> = Vec::new();
        for entry in std::fs::read_dir(natives).with_context(|| format!("无法读取{}", natives.display()))? {
            let path = entry?.path();
            let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            if let Some(index) = library_index(&name) {
                found.push((index, path));
            }
        }
        found.sort();
        ensure!(found.iter().map(|f| f.0).eq(0..ORDER.len()), "FFmpeg原生库不完整或过旧，缺少上传所需的rdimedia");
        let mut libraries: Vec<Library> = found.iter().map(|(_, path)| load_library(path)).collect::<Result<_>>()?;
        let shim = libraries.last().context("rdimedia未加载")?;
        // SAFETY: the signatures are those of rdimedia.c at `ABI`, which is checked first.
        let (free, ogg, avif) = unsafe {
            let abi: AbiFn = *shim.get(b"rdi_media_abi\0").context("rdimedia缺少入口")?;
            ensure!(abi() == ABI, "rdimedia版本不匹配");
            let free: FreeFn = *shim.get(b"rdi_media_free\0")?;
            let ogg: OggFn = *shim.get(b"rdi_ogg_to_opus\0")?;
            let avif: AvifFn = *shim.get(b"rdi_rgba_to_avif\0")?;
            (free, ogg, avif)
        };
        libraries.reverse();
        std::fs::create_dir_all(work_dir)?;
        let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).clamp(1, 4) as c_int;
        Ok(Self { _libraries: libraries, free, ogg, avif, work_dir: work_dir.to_path_buf(), threads })
    }

    /// The pixels as a still AVIF (4:2:0 at the upload's quality), or why not.
    fn avif(&self, rgba: &[u8], width: u32, height: u32) -> Result<Vec<u8>> {
        ensure!(rgba.len() as u64 == u64::from(width) * u64::from(height) * 4, "图片数据大小不符");
        let (mut output, mut size) = (std::ptr::null_mut::<u8>(), 0usize);
        let mut message = [0u8; 512];
        // SAFETY: `rgba` holds width x height x 4 bytes (checked above); the shim writes at most
        // `message.len()` bytes of text and returns a buffer it allocated, released below.
        let result = unsafe { (self.avif)(rgba.as_ptr(), width as c_int, height as c_int, 0, self.threads, &mut output, &mut size, message.as_mut_ptr().cast(), message.len()) };
        if result != OK || output.is_null() {
            bail!("AVIF编码失败: {}", message_text(&message));
        }
        // SAFETY: on OK `output` points to `size` bytes owned by the shim until `free`.
        let bytes = unsafe { std::slice::from_raw_parts(output, size).to_vec() };
        unsafe { (self.free)(output) };
        Ok(bytes)
    }

    /// `FfmpegAvifEncoder.encodePng`: decode, shrink to 720 pixels of height, encode.
    fn png_to_avif(&self, bytes: &[u8]) -> Result<Vec<u8>> {
        let decoded = image::load_from_memory(bytes).context("图片无法解码")?.to_rgba8();
        let resized = if decoded.height() > MAX_HEIGHT {
            let width = ((f64::from(decoded.width()) * f64::from(MAX_HEIGHT) / f64::from(decoded.height())) as u32).max(1);
            image::imageops::resize(&decoded, width, MAX_HEIGHT, image::imageops::FilterType::CatmullRom)
        } else {
            decoded
        };
        ensure!(u64::from(resized.width()) * u64::from(resized.height()) <= MAX_PIXELS, "图片尺寸超过AVIF处理上限");
        self.avif(resized.as_raw(), resized.width(), resized.height())
    }

    /// `OggTranscoder.transcodeOgg`: `Ok(None)` when the sound is too long or cannot be read;
    /// an error when the encoder or the work folder fails.
    fn transcode(&self, bytes: &[u8], name: &str) -> Result<Option<Vec<u8>>> {
        let id = uuid::Uuid::now_v7().simple().to_string();
        let (input, output) = (self.work_dir.join(format!("ogg-{id}.input.ogg")), self.work_dir.join(format!("ogg-{id}.output.ogg")));
        let run = || -> Result<Option<Vec<u8>>> {
            std::fs::write(&input, bytes)?;
            let c_path = |path: &Path| CString::new(path.to_str().context("临时路径不是UTF-8")?).map_err(|e| anyhow!(e));
            let (c_input, c_output) = (c_path(&input)?, c_path(&output)?);
            let mut message = [0u8; 512];
            // SAFETY: both paths are NUL-terminated UTF-8 and the shim writes at most
            // `message.len()` bytes of text.
            let result = unsafe { (self.ogg)(c_input.as_ptr(), c_output.as_ptr(), MAX_SOUND_MICROS, message.as_mut_ptr().cast(), message.len()) };
            match result {
                OK => {
                    let encoded = std::fs::read(&output)?;
                    if encoded.is_empty() {
                        tracing::error!(name, "OGG处理没有输出，已替换为空音频");
                        return Ok(None);
                    }
                    Ok(Some(encoded))
                }
                TOO_LONG => {
                    tracing::warn!(name, "OGG超过10秒，已替换为空音频");
                    Ok(None)
                }
                BAD_INPUT => {
                    tracing::error!(name, reason = %message_text(&message), "OGG处理失败，已替换为空音频");
                    Ok(None)
                }
                _ => bail!("Opus编码失败: {}", message_text(&message)),
            }
        };
        let result = run();
        for file in [&input, &output] {
            let _ = std::fs::remove_file(file);
        }
        result
    }

    /// `ensureAudioReady`: the bundled empty sound must come out as Opus before an upload starts.
    pub fn ready(&self) -> Result<()> {
        let encoded = match self.transcode(EMPTY_OGG, "assets/empty.ogg") {
            Ok(Some(encoded)) => encoded,
            Ok(None) => bail!("音频处理模块初始化失败，请重试"),
            Err(error) => {
                tracing::error!(error = %format!("{error:#}"), "音频处理模块不可用");
                bail!(BROKEN);
            }
        };
        ensure!(is_opus(&encoded), "音频处理模块初始化失败，请重试");
        Ok(())
    }
}

/// `OggCodecDetector.detect`: the first page of an Ogg file starts an Opus stream.
pub fn is_opus(bytes: &[u8]) -> bool {
    if bytes.len() < 27 || &bytes[..4] != b"OggS" {
        return false;
    }
    let segments = usize::from(bytes[26]);
    bytes.get(27 + segments..27 + segments + 8) == Some(b"OpusHead".as_slice())
}

impl Media for Encoders {
    fn png(&self, bytes: Vec<u8>, name: &str) -> Vec<u8> {
        if bytes.len() <= PNG_THRESHOLD {
            return bytes;
        }
        match self.png_to_avif(&bytes) {
            Ok(avif) => avif,
            Err(error) => {
                tracing::error!(name, error = %format!("{error:#}"), "PNG转AVIF失败，保留原PNG");
                bytes
            }
        }
    }

    fn ogg(&self, bytes: Vec<u8>, name: &str) -> Result<Vec<u8>> {
        match self.transcode(&bytes, name) {
            Ok(Some(encoded)) => Ok(encoded),
            Ok(None) => Ok(EMPTY_OGG.to_vec()),
            Err(error) => {
                tracing::error!(name, error = %format!("{error:#}"), "音频处理模块不可用");
                bail!(BROKEN)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_dir::TestDir;

    #[test]
    fn opus_is_recognised_by_its_first_page() {
        let mut page = b"OggS".to_vec();
        page.extend([0u8; 22]);
        page.push(1);
        page.push(19);
        page.extend(b"OpusHead");
        assert!(is_opus(&page));
        assert!(!is_opus(EMPTY_OGG));
        assert!(!is_opus(b"OggS"));
        assert!(!is_opus(b"not a sound at all, but long enough to hold a page header"));
    }

    #[test]
    fn shared_libraries_are_found_by_name_on_each_platform() {
        for (name, index) in [
            ("avutil-60.dll", Some(0)),
            ("rdimedia.dll", Some(5)),
            ("rdimedia.lib", None),
            ("jniavutil.dll", None),
            ("libavcodec.so.62", Some(3)),
            ("librdimedia.so", Some(5)),
            ("libjniavformat.so", None),
            ("libswscale.9.dylib", Some(2)),
            ("libavformat.62.dylib", Some(4)),
            ("libswresample.6.dylib", Some(1)),
            ("avutilx-60.dll", None),
            ("avcodec-62.def", None),
        ] {
            assert_eq!(library_index(name), index, "{name}");
        }
    }

    #[test]
    fn natives_without_the_shim_are_refused() {
        let dir = TestDir::new("rdi-upload-media");
        for name in ["avutil-60.dll", "swresample-6.dll", "swscale-9.dll", "avcodec-62.dll", "avformat-62.dll", "jniavutil.dll"] {
            std::fs::write(dir.join(name), b"not loaded").unwrap();
        }
        let error = Encoders::load(&dir, &dir.join("work")).err().unwrap().to_string();
        assert!(error.contains("缺少上传所需的rdimedia"), "{error}");
    }

    /// Live: the real encoders of an extracted natives folder that holds rdimedia.
    #[test]
    #[ignore = "loads the FFmpeg natives folder named by RDI_MEDIA_NATIVES"]
    fn real_encoders() {
        let natives = PathBuf::from(std::env::var("RDI_MEDIA_NATIVES").expect("RDI_MEDIA_NATIVES"));
        let dir = TestDir::new("rdi-upload-media");
        // A work folder with a name outside ASCII: the shim takes UTF-8 paths.
        let encoders = Encoders::load(&natives, &dir.join("声音 work")).unwrap();
        encoders.ready().unwrap();
        let opus = encoders.ogg(EMPTY_OGG.to_vec(), "empty.ogg").unwrap();
        assert!(is_opus(&opus));
        assert_eq!(encoders.ogg(b"this is not a sound".repeat(64), "garbage.ogg").unwrap(), EMPTY_OGG);
        assert!(std::fs::read_dir(dir.join("声音 work")).unwrap().next().is_none(), "temporary sound files are removed");

        // A noisy picture over the threshold, with some transparency.
        let (width, height) = (400u32, 300u32);
        let mut state = 42u32;
        let mut noise = || {
            state = state.wrapping_mul(1664525).wrapping_add(1013904223);
            (state >> 24) as u8
        };
        let picture = image::RgbaImage::from_fn(width, height, |x, _| image::Rgba([noise(), noise(), noise(), if x < 200 { 255 } else { 128 }]));
        let mut png = Vec::new();
        picture.write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).unwrap();
        assert!(png.len() > PNG_THRESHOLD);
        let avif = encoders.png(png.clone(), "noise.png");
        assert_eq!(&avif[4..8], b"ftyp");
        // The launcher's own decoder reads it back with the same size.
        let decoded = crate::avif::decode(&avif, 8192).unwrap();
        assert_eq!((decoded.width, decoded.height), (width, height));
        println!("PNG {} bytes -> AVIF {} bytes; empty sound {} -> Opus {} bytes", png.len(), avif.len(), EMPTY_OGG.len(), opus.len());

        // Small pictures and files that are no pictures stay as they are.
        assert_eq!(encoders.png(b"png".to_vec(), "small.png"), b"png");
        let junk = vec![7u8; PNG_THRESHOLD + 1];
        assert_eq!(encoders.png(junk.clone(), "junk.png"), junk);
        // Taller than 720 pixels: shrunk to 720, keeping the shape.
        let tall = image::RgbaImage::from_fn(300, 1440, |_, _| image::Rgba([noise(), noise(), noise(), 255]));
        let mut png = Vec::new();
        tall.write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png).unwrap();
        let decoded = crate::avif::decode(&encoders.png(png, "tall.png"), 8192).unwrap();
        assert_eq!((decoded.width, decoded.height), (150, 720));
    }
}
