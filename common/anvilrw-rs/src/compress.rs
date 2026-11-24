//! Decompression that never produces more than a caller-given limit, and the matching compression.
//!
//! Inflate runs on `flate2::Decompress` directly: the stream must reach its end marker, so a
//! truncated zlib or gzip stream fails instead of reading as shorter data. Gzip headers, CRC32 and
//! size trailers are checked like `java.util.zip.GZIPInputStream`, including its handling of
//! concatenated members (bytes after a member that do not start a valid header are ignored).

use std::io::{Read, Write};

use flate2::{Compression, Decompress, FlushDecompress, Status};

use crate::error::{RemapError, Result};

/// Region chunk compression types (the low 7 bits of the chunk header byte).
pub mod chunk_compression {
    pub const GZIP: u8 = 1;
    pub const ZLIB: u8 = 2;
    pub const NONE: u8 = 3;
    pub const LZ4: u8 = 4;
    pub const ZSTD: u8 = 8;
    pub const CUSTOM: u8 = 127;
}

use chunk_compression::{GZIP, NONE, ZLIB, ZSTD};

const ZSTD_LEVEL: i32 = 3;
const BLOCK_SIZE: usize = 64 * 1024;

fn limit_exceeded(what: &str, max_bytes: usize) -> RemapError {
    RemapError::LimitExceeded(format!("{what} exceeds {max_bytes} bytes"))
}

/// Reads `input` to its end, failing once more than `max_bytes` would be read.
pub fn read_at_most(input: impl Read, max_bytes: usize, what: &str) -> Result<Vec<u8>> {
    let mut output = Vec::new();
    let read = input
        .take(max_bytes as u64 + 1)
        .read_to_end(&mut output)
        .map_err(|error| RemapError::io(what.to_string(), error))?;
    if read > max_bytes {
        return Err(limit_exceeded(what, max_bytes));
    }
    Ok(output)
}

/// Inflates one deflate stream from `data` into `output`, never letting `output` grow past
/// `max_bytes`. Returns the number of input bytes the stream used.
fn inflate_into(
    data: &[u8],
    zlib_header: bool,
    output: &mut Vec<u8>,
    max_bytes: usize,
    what: &str,
) -> Result<usize> {
    let mut inflater = Decompress::new(zlib_header);
    loop {
        if output.len() == output.capacity() {
            if output.len() > max_bytes {
                return Err(limit_exceeded(what, max_bytes));
            }
            let room = max_bytes.saturating_add(1) - output.len();
            output.reserve_exact(output.capacity().max(BLOCK_SIZE).min(room));
        }
        let consumed = inflater.total_in() as usize;
        let produced = inflater.total_out();
        let status = inflater
            .decompress_vec(&data[consumed..], output, FlushDecompress::None)
            .map_err(|error| {
                RemapError::invalid_data(
                    what.to_string(),
                    format!("corrupt deflate stream: {error}"),
                )
            })?;
        if output.len() > max_bytes {
            return Err(limit_exceeded(what, max_bytes));
        }
        match status {
            Status::StreamEnd => return Ok(inflater.total_in() as usize),
            Status::Ok | Status::BufError => {
                let progressed =
                    inflater.total_in() as usize != consumed || inflater.total_out() != produced;
                if !progressed && output.len() < output.capacity() {
                    return Err(RemapError::io(
                        what.to_string(),
                        std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "Unexpected end of ZLIB input stream",
                        ),
                    ));
                }
            }
        }
    }
}

/// Parses a gzip member header the way `GZIPInputStream.readHeader` does and returns its length.
fn gzip_header_length(data: &[u8]) -> std::result::Result<usize, &'static str> {
    const FHCRC: u8 = 2;
    const FEXTRA: u8 = 4;
    const FNAME: u8 = 8;
    const FCOMMENT: u8 = 16;
    if data.len() < 10 {
        return Err("Unexpected end of gzip header");
    }
    if data[0] != 0x1f || data[1] != 0x8b {
        return Err("Not in GZIP format");
    }
    if data[2] != 8 {
        return Err("Unsupported compression method");
    }
    let flags = data[3];
    let mut position = 10;
    if flags & FEXTRA != 0 {
        if data.len() < position + 2 {
            return Err("Unexpected end of gzip header");
        }
        let length = u16::from_le_bytes([data[position], data[position + 1]]) as usize;
        position += 2 + length;
    }
    for flag in [FNAME, FCOMMENT] {
        if flags & flag != 0 {
            loop {
                let byte = *data.get(position).ok_or("Unexpected end of gzip header")?;
                position += 1;
                if byte == 0 {
                    break;
                }
            }
        }
    }
    if flags & FHCRC != 0 {
        let stored = data
            .get(position..position + 2)
            .ok_or("Unexpected end of gzip header")?;
        let crc = crc32fast::hash(&data[..position]) & 0xffff;
        if u16::from_le_bytes([stored[0], stored[1]]) as u32 != crc {
            return Err("Corrupt GZIP header");
        }
        position += 2;
    }
    if position > data.len() {
        return Err("Unexpected end of gzip header");
    }
    Ok(position)
}

/// Decompresses gzip `data`, at most `max_bytes`, with `GZIPInputStream` semantics.
pub fn gunzip(data: &[u8], max_bytes: usize, what: &str) -> Result<Vec<u8>> {
    let mut output = Vec::with_capacity(
        data.len()
            .saturating_mul(4)
            .min(max_bytes.saturating_add(1))
            .min(16 * 1024 * 1024),
    );
    let mut position = 0;
    loop {
        let header = gzip_header_length(&data[position..])
            .map_err(|message| RemapError::invalid_data(what.to_string(), message))?;
        let member_start = output.len();
        let used = inflate_into(
            &data[position + header..],
            false,
            &mut output,
            max_bytes,
            what,
        )?;
        let trailer = position + header + used;
        let Some(bytes) = data.get(trailer..trailer + 8) else {
            return Err(RemapError::io(
                what.to_string(),
                std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "Unexpected end of ZLIB input stream",
                ),
            ));
        };
        let crc = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        let size = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
        let member = &output[member_start..];
        if crc != crc32fast::hash(member) || size != member.len() as u32 {
            return Err(RemapError::invalid_data(
                what.to_string(),
                "Corrupt GZIP trailer",
            ));
        }
        position = trailer + 8;
        // GZIPInputStream reads another member only if the remaining bytes start with a valid header.
        if position >= data.len() || gzip_header_length(&data[position..]).is_err() {
            return Ok(output);
        }
    }
}

/// Compresses `data` as one gzip member, as `GZIPOutputStream` does (default level).
pub fn gzip(data: &[u8]) -> Vec<u8> {
    let mut encoder = flate2::write::GzEncoder::new(
        Vec::with_capacity(data.len() / 4 + 64),
        Compression::default(),
    );
    encoder
        .write_all(data)
        .expect("writing to memory cannot fail");
    encoder.finish().expect("writing to memory cannot fail")
}

/// Decompresses a region chunk payload of the given compression `kind`.
pub fn decompress_chunk(kind: u8, data: &[u8], max_bytes: usize, what: &str) -> Result<Vec<u8>> {
    match kind {
        GZIP => gunzip(data, max_bytes, what),
        ZLIB => {
            let mut output = Vec::with_capacity(
                data.len()
                    .saturating_mul(4)
                    .min(max_bytes.saturating_add(1))
                    .min(16 * 1024 * 1024),
            );
            inflate_into(data, true, &mut output, max_bytes, what)?;
            Ok(output)
        }
        NONE => {
            if data.len() > max_bytes {
                return Err(limit_exceeded(what, max_bytes));
            }
            Ok(data.to_vec())
        }
        ZSTD => {
            let decoder = zstd::stream::read::Decoder::with_buffer(data)
                .map_err(|error| RemapError::io(what.to_string(), error))?;
            read_at_most(decoder, max_bytes, what)
        }
        _ => Err(RemapError::invalid_data(
            what.to_string(),
            format!("Unsupported chunk compression type {kind}"),
        )),
    }
}

/// Compresses chunk NBT with the given `kind`, so a rewritten chunk keeps its compression.
pub fn compress_chunk(kind: u8, data: &[u8]) -> Result<Vec<u8>> {
    match kind {
        GZIP => Ok(gzip(data)),
        ZLIB => {
            let mut encoder = flate2::write::ZlibEncoder::new(
                Vec::with_capacity(data.len() / 4 + 64),
                Compression::default(),
            );
            encoder
                .write_all(data)
                .expect("writing to memory cannot fail");
            Ok(encoder.finish().expect("writing to memory cannot fail"))
        }
        NONE => Ok(data.to_vec()),
        ZSTD => zstd::bulk::compress(data, ZSTD_LEVEL)
            .map_err(|error| RemapError::io("zstd compression", error)),
        _ => Err(RemapError::invalid_data(
            "chunk compression",
            format!("Unsupported chunk compression type {kind}"),
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pseudo_random(seed: u64, size: usize) -> Vec<u8> {
        let mut state = seed
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (0..size)
            .map(|_| {
                state = state
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                (state >> 33) as u8
            })
            .collect()
    }

    #[test]
    fn bounded_decompression_stops_at_the_limit() {
        let compressed = gzip(&vec![0; 2 * 1024 * 1024]);
        assert!(matches!(
            gunzip(&compressed, 1024 * 1024, "test"),
            Err(RemapError::LimitExceeded(_))
        ));
        assert_eq!(
            gunzip(&compressed, 2 * 1024 * 1024, "test").unwrap().len(),
            2 * 1024 * 1024
        );
        for size in [0, 1, 65535, 65536, 65537, 200_000] {
            let data = pseudo_random(size as u64, size);
            assert_eq!(gunzip(&gzip(&data), size, "test").unwrap(), data);
            for kind in [GZIP, ZLIB, NONE, ZSTD] {
                let packed = compress_chunk(kind, &data).unwrap();
                assert_eq!(
                    decompress_chunk(kind, &packed, size, "test").unwrap(),
                    data,
                    "type {kind} size {size}"
                );
                if size > 0 {
                    assert!(matches!(
                        decompress_chunk(kind, &packed, size - 1, "test"),
                        Err(RemapError::LimitExceeded(_))
                    ));
                }
            }
        }
    }

    #[test]
    fn truncated_streams_fail() {
        let data = pseudo_random(7, 10_000);
        for kind in [GZIP, ZLIB, ZSTD] {
            let packed = compress_chunk(kind, &data).unwrap();
            for cut in [1, 3, 8, packed.len() / 2] {
                let result =
                    decompress_chunk(kind, &packed[..packed.len() - cut], usize::MAX / 2, "test");
                assert!(result.is_err(), "type {kind} cut {cut}");
            }
        }
    }

    #[test]
    fn gzip_members_and_trailing_bytes_follow_java() {
        let a = gzip(b"hello ");
        let b = gzip(b"world");
        let mut both = a.clone();
        both.extend_from_slice(&b);
        assert_eq!(gunzip(&both, 100, "test").unwrap(), b"hello world");
        let mut garbage = a.clone();
        garbage.extend_from_slice(&[1, 2, 3]);
        assert_eq!(gunzip(&garbage, 100, "test").unwrap(), b"hello ");
        let mut bad_crc = a.clone();
        let at = bad_crc.len() - 8;
        bad_crc[at] ^= 1;
        assert!(gunzip(&bad_crc, 100, "test").is_err());
        assert!(gunzip(&[1, 2, 3], 100, "test").is_err());
    }
}
