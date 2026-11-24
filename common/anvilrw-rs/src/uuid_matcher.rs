use std::collections::HashMap;

use uuid::Uuid;

use crate::error::{RemapError, Result};
use crate::nbt::{put_u8x8, u8x8};

const DASHED_LENGTH: usize = 36;
const DASHLESS_LENGTH: usize = 32;
const DASH: u8 = b'-';
const LOWER_DIGITS: &[u8; 16] = b"0123456789abcdef";
const UPPER_DIGITS: &[u8; 16] = b"0123456789ABCDEF";

pub(crate) fn hex_value(b: u8) -> i32 {
    match b {
        b'0'..=b'9' => (b - b'0') as i32,
        b'a'..=b'f' => (b - b'a') as i32 + 10,
        b'A'..=b'F' => (b - b'A') as i32 + 10,
        _ => -1,
    }
}

fn is_hex_or_dash(b: u8) -> bool {
    b == DASH || hex_value(b) >= 0
}

/// Matches whole source UUIDs and writes their mapped targets.
///
/// Only full 128-bit values are ever matched: 16 binary bytes, or a complete 36-character dashed or
/// 32-character dashless hex string with hex boundaries on both sides. Every replacement has the
/// same length as what it replaces.
///
/// The mapping must be injective. A target may also be a source only as part of a swap (`a → b` and
/// `b → a`); any longer chain is rejected. Matches are always read from the unpatched input, so a
/// swap is applied in one simultaneous step.
pub(crate) struct UuidMatcher {
    pub sources: Vec<Uuid>,
    source_msb: Vec<u64>,
    source_lsb: Vec<u64>,
    target_msb: Vec<u64>,
    target_lsb: Vec<u64>,
}

impl UuidMatcher {
    pub fn new(mapping: &HashMap<Uuid, Uuid>) -> Result<Self> {
        let mut sources: Vec<Uuid> = mapping.keys().copied().collect();
        sources.sort();
        let targets: Vec<Uuid> = sources.iter().map(|source| mapping[source]).collect();
        let mut distinct = targets.clone();
        distinct.sort();
        distinct.dedup();
        if distinct.len() != targets.len() {
            return Err(RemapError::Invalid(
                "Two source UUIDs map to the same target".into(),
            ));
        }
        for (source, target) in mapping {
            if source == target {
                return Err(RemapError::Invalid("A source UUID maps to itself".into()));
            }
            if mapping.get(target).is_some_and(|back| back != source) {
                return Err(RemapError::Invalid(
                    "A target UUID is also a source UUID outside a swap".into(),
                ));
            }
        }
        Ok(UuidMatcher {
            source_msb: sources.iter().map(|uuid| uuid.as_u64_pair().0).collect(),
            source_lsb: sources.iter().map(|uuid| uuid.as_u64_pair().1).collect(),
            target_msb: targets.iter().map(|uuid| uuid.as_u64_pair().0).collect(),
            target_lsb: targets.iter().map(|uuid| uuid.as_u64_pair().1).collect(),
            sources,
        })
    }

    pub fn is_empty(&self) -> bool {
        self.sources.is_empty()
    }

    /// Index of the source equal to (`msb`, `lsb`).
    pub fn find(&self, msb: u64, lsb: u64) -> Option<usize> {
        (0..self.source_msb.len()).find(|&i| self.source_msb[i] == msb && self.source_lsb[i] == lsb)
    }

    pub fn target_msb(&self, index: usize) -> u64 {
        self.target_msb[index]
    }

    pub fn target_lsb(&self, index: usize) -> u64 {
        self.target_lsb[index]
    }

    /// Matches 16 big-endian bytes at `offset` (an `IntArray[4]` or `LongArray[2]` payload).
    pub fn find_binary(&self, src: &[u8], offset: usize) -> Option<usize> {
        self.find(u8x8(src, offset), u8x8(src, offset + 8))
    }

    /// Writes the target of source `index` as 16 big-endian bytes.
    pub fn write_binary(&self, index: usize, dst: &mut [u8], offset: usize) {
        put_u8x8(dst, offset, self.target_msb[index]);
        put_u8x8(dst, offset + 8, self.target_lsb[index]);
    }

    /// Finds every full source UUID written as ASCII text in `src[from..to]` and writes its target
    /// into `dst` (a lazily made copy of all of `src`) at the same position, keeping the dash style
    /// and letter case. Text is read from `src` only. Returns the number of replacements, and calls
    /// `on_replace` with the source index of each.
    ///
    /// The rules match, case-insensitively, dashed `(?<![0-9A-Fa-f-])uuid(?![0-9A-Fa-f-])` and
    /// dashless `(?<![0-9A-Fa-f])hex32(?![0-9A-Fa-f])`. They are only safe on encodings whose
    /// multi-byte sequences never contain ASCII bytes, such as UTF-8 and Java modified UTF-8.
    pub fn replace_ascii(
        &self,
        src: &[u8],
        from: usize,
        to: usize,
        dst: &mut Option<Vec<u8>>,
        mut on_replace: impl FnMut(usize),
    ) -> usize {
        if self.is_empty() || to < from + DASHLESS_LENGTH {
            return 0;
        }
        let mut replaced = 0;
        let mut i = from;
        while i + DASHLESS_LENGTH <= to {
            let b = src[i];
            if hex_value(b) < 0 || (i > from && hex_value(src[i - 1]) >= 0) {
                i += 1;
                continue;
            }
            let previous_is_dash = i > from && src[i - 1] == DASH;
            if !previous_is_dash
                && i + DASHED_LENGTH <= to
                && is_dashed_at(src, i)
                && (i + DASHED_LENGTH == to || !is_hex_or_dash(src[i + DASHED_LENGTH]))
                && let Some(index) =
                    self.find(parse_hex(src, i, true, 0), parse_hex(src, i, true, 1))
            {
                self.write_ascii(index, src, i, true, dst.get_or_insert_with(|| src.to_vec()));
                on_replace(index);
                replaced += 1;
                i += DASHED_LENGTH;
                continue;
            }
            if is_dashless_at(src, i)
                && (i + DASHLESS_LENGTH == to || hex_value(src[i + DASHLESS_LENGTH]) < 0)
                && let Some(index) =
                    self.find(parse_hex(src, i, false, 0), parse_hex(src, i, false, 1))
            {
                self.write_ascii(
                    index,
                    src,
                    i,
                    false,
                    dst.get_or_insert_with(|| src.to_vec()),
                );
                on_replace(index);
                replaced += 1;
                i += DASHLESS_LENGTH;
                continue;
            }
            i += 1;
        }
        replaced
    }

    fn write_ascii(&self, index: usize, src: &[u8], start: usize, dashed: bool, dst: &mut [u8]) {
        let length = if dashed {
            DASHED_LENGTH
        } else {
            DASHLESS_LENGTH
        };
        let text = &src[start..start + length];
        let upper = text.iter().filter(|b| (b'A'..=b'F').contains(b)).count();
        let lower = text.iter().filter(|b| (b'a'..=b'f').contains(b)).count();
        let digits = if upper > 0 && lower == 0 {
            UPPER_DIGITS
        } else {
            LOWER_DIGITS
        };
        let mut digit = 0;
        let mut k = 0;
        while digit < 32 {
            if dashed && matches!(k, 8 | 13 | 18 | 23) {
                k += 1;
                continue;
            }
            let word = if digit < 16 {
                self.target_msb[index]
            } else {
                self.target_lsb[index]
            };
            let nibble = ((word >> (60 - 4 * (digit % 16))) & 0xf) as usize;
            dst[start + k] = digits[nibble];
            digit += 1;
            k += 1;
        }
    }
}

fn is_dashed_at(src: &[u8], start: usize) -> bool {
    (0..DASHED_LENGTH).all(|k| {
        let b = src[start + k];
        if matches!(k, 8 | 13 | 18 | 23) {
            b == DASH
        } else {
            hex_value(b) >= 0
        }
    })
}

fn is_dashless_at(src: &[u8], start: usize) -> bool {
    src[start..start + DASHLESS_LENGTH]
        .iter()
        .all(|&b| hex_value(b) >= 0)
}

/// Parses the most (`half` 0) or least (`half` 1) significant 16 hex digits.
fn parse_hex(src: &[u8], start: usize, dashed: bool, half: usize) -> u64 {
    let mut value = 0u64;
    let mut digit = 0;
    let mut k = 0;
    while digit < 32 {
        let b = src[start + k];
        k += 1;
        if dashed && b == DASH {
            continue;
        }
        if digit / 16 == half {
            value = (value << 4) | hex_value(b) as u64;
        }
        digit += 1;
    }
    value
}
