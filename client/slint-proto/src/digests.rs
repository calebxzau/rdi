//! Content hashes shared by catalog matching and Compose-compatible dlc entries.
use crate::tasks::Cancellation;
use anyhow::{Result, ensure};
use sha1::Digest as _;
use std::{io::Read, path::Path};
#[derive(
    Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, serde::Serialize, serde::Deserialize,
)]
pub enum Algorithm {
    Sha1,
    Sha256,
    Sha512,
    Murmur2,
}
impl Algorithm {
    pub fn suffix(self) -> &'static str {
        match self {
            Self::Sha1 => "sha1",
            Self::Sha256 => "sha256",
            Self::Sha512 => "sha512",
            Self::Murmur2 => "murmur2",
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Digest {
    pub algorithm: Algorithm,
    pub value: String,
}
impl Digest {
    pub fn new(algorithm: Algorithm, value: &str) -> Result<Self> {
        let value = value.trim().to_lowercase();
        let valid = match algorithm {
            Algorithm::Murmur2 => value.parse::<u32>().is_ok(),
            _ => {
                value.len()
                    == match algorithm {
                        Algorithm::Sha1 => 40,
                        Algorithm::Sha256 => 64,
                        _ => 128,
                    }
                    && value.bytes().all(|b| b.is_ascii_hexdigit())
            }
        };
        ensure!(valid, "整合包文件校验信息无效");
        Ok(Self { algorithm, value })
    }
}
pub fn murmur2(bytes: &[u8]) -> u32 {
    let data: Vec<_> = bytes
        .iter()
        .copied()
        .filter(|b| !matches!(b, 9 | 10 | 13 | 32))
        .collect();
    if data.is_empty() {
        return 0;
    }
    let m = 0x5bd1e995_u32;
    let mut h = 1 ^ data.len() as u32;
    let mut chunks = data.chunks_exact(4);
    for c in &mut chunks {
        let mut k = u32::from_le_bytes(c.try_into().unwrap()).wrapping_mul(m);
        k ^= k >> 24;
        k = k.wrapping_mul(m);
        h = h.wrapping_mul(m) ^ k;
    }
    let tail = chunks.remainder();
    let mut k = 0;
    for (i, b) in tail.iter().enumerate() {
        k |= u32::from(*b) << (i * 8);
    }
    if !tail.is_empty() {
        h = (h ^ k).wrapping_mul(m);
    }
    h ^= h >> 13;
    h = h.wrapping_mul(m);
    h ^ (h >> 15)
}
pub fn hash(path: &Path, algorithm: Algorithm, cancel: &Cancellation) -> Result<String> {
    let mut f = std::fs::File::open(path)?;
    let mut b = [0u8; 65536];
    let mut sha1 = sha1::Sha1::new();
    let mut sha256 = sha2::Sha256::new();
    let mut sha512 = sha2::Sha512::new();
    // Murmur's seed needs the normalized length. Count first; keep memory bounded for large jars.
    if algorithm == Algorithm::Murmur2 {
        let mut n = 0u32;
        loop {
            cancel.check()?;
            let count = f.read(&mut b)?;
            if count == 0 {
                break;
            }
            n = n.wrapping_add(
                b[..count]
                    .iter()
                    .filter(|v| !matches!(v, 9 | 10 | 13 | 32))
                    .count() as u32,
            );
        }
        if n == 0 {
            return Ok("0".into());
        }
        use std::io::{Seek, SeekFrom};
        f.seek(SeekFrom::Start(0))?;
        let m = 0x5bd1e995_u32;
        let mut h = 1 ^ n;
        let (mut k, mut shift) = (0u32, 0);
        loop {
            cancel.check()?;
            let count = f.read(&mut b)?;
            if count == 0 {
                break;
            }
            for &v in &b[..count] {
                if matches!(v, 9 | 10 | 13 | 32) {
                    continue;
                }
                k |= u32::from(v) << shift;
                shift += 8;
                if shift == 32 {
                    k = k.wrapping_mul(m);
                    k ^= k >> 24;
                    k = k.wrapping_mul(m);
                    h = h.wrapping_mul(m) ^ k;
                    k = 0;
                    shift = 0;
                }
            }
        }
        if shift > 0 {
            h = (h ^ k).wrapping_mul(m);
        }
        h ^= h >> 13;
        h = h.wrapping_mul(m);
        return Ok((h ^ (h >> 15)).to_string());
    }
    loop {
        cancel.check()?;
        let n = f.read(&mut b)?;
        if n == 0 {
            break;
        }
        match algorithm {
            Algorithm::Sha1 => sha1.update(&b[..n]),
            Algorithm::Sha256 => sha256.update(&b[..n]),
            Algorithm::Sha512 => sha512.update(&b[..n]),
            _ => unreachable!(),
        }
    }
    Ok(match algorithm {
        Algorithm::Sha1 => format!("{:x}", sha1.finalize()),
        Algorithm::Sha256 => format!("{:x}", sha256.finalize()),
        Algorithm::Sha512 => format!("{:x}", sha512.finalize()),
        _ => unreachable!(),
    })
}
