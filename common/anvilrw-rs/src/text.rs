//! UUID replacement in UTF-8 text, renaming of UUID path segments, and detection of mapped UUIDs in
//! bytes that are copied unchanged.

use std::collections::HashMap;
use std::sync::LazyLock;

use regex::bytes::Regex;
use uuid::Uuid;

use crate::error::{RemapError, Result};
use crate::uuid_matcher::UuidMatcher;

/// Output of [`TextUuidPatcher::patch`].
#[derive(Debug)]
pub struct TextPatchResult {
    /// The patched text, or `None` when nothing changed (the input is the result).
    pub patched: Option<Vec<u8>>,
    pub replacements: HashMap<Uuid, usize>,
    /// `XxxMost: …L` / `XxxLeast: …L` pairs equal to a source UUID. They are detected, never replaced.
    pub most_least_hits: usize,
}

impl TextPatchResult {
    pub fn changed(&self) -> bool {
        self.patched.is_some()
    }

    pub fn bytes<'a>(&'a self, original: &'a [u8]) -> &'a [u8] {
        self.patched.as_deref().unwrap_or(original)
    }
}

/// `\s` and `\d` are ASCII-only here, as in Java regular expressions without UNICODE_CHARACTER_CLASS.
static INT_ARRAY: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?-u)\[(I;)?\s*(-?[0-9]+)\s*,\s*(-?[0-9]+)\s*,\s*(-?[0-9]+)\s*,\s*(-?[0-9]+)\s*\]")
        .expect("valid regex")
});

/// SNBT `UUIDMost: 123L`, or JSON `"UUIDMost": 123`.
static MOST_LEAST: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?-u)([A-Za-z0-9_]*)(Most|Least)"?\s*:\s*(-?[0-9]+)[Ll]?"#).expect("valid regex")
});

fn ascii(bytes: &[u8]) -> &str {
    std::str::from_utf8(bytes).expect("regex groups here are ASCII")
}

/// Replaces mapped player UUIDs in strictly valid UTF-8 text (SNBT, JSON, TOML, config files…).
///
/// - Dashed and dashless UUID text: same length, keeping dash style and letter case.
/// - SNBT `[I; a, b, c, d]` and JSON `[a, b, c, d]` int arrays, only when all four ints equal a
///   source UUID. The whitespace and separators are kept; the numbers may change length.
/// - `Most`/`Least` long pairs are only counted in [`TextPatchResult::most_least_hits`].
pub struct TextUuidPatcher {
    matcher: UuidMatcher,
}

impl TextUuidPatcher {
    pub fn new(mapping: &HashMap<Uuid, Uuid>) -> Result<Self> {
        Ok(TextUuidPatcher {
            matcher: UuidMatcher::new(mapping)?,
        })
    }

    pub fn patch(&self, text: &[u8]) -> Result<TextPatchResult> {
        if !is_strict_utf8(text) {
            return Err(RemapError::NotUtf8Text(
                "Text is not strictly valid UTF-8 or contains NUL bytes".into(),
            ));
        }
        let mut counts = vec![0usize; self.matcher.sources.len()];
        let mut out = None;
        self.matcher
            .replace_ascii(text, 0, text.len(), &mut out, |i| counts[i] += 1);

        let mut arrays = Vec::new();
        for captures in INT_ARRAY.captures_iter(text) {
            let ints: Option<Vec<i32>> = (2..=5)
                .map(|group| ascii(&captures[group]).parse::<i32>().ok())
                .collect();
            let Some(ints) = ints else { continue };
            let msb = ((ints[0] as u32 as u64) << 32) | ints[1] as u32 as u64;
            let lsb = ((ints[2] as u32 as u64) << 32) | ints[3] as u32 as u64;
            if let Some(index) = self.matcher.find(msb, lsb) {
                let ranges: Vec<_> = (2..=5)
                    .map(|group| captures.get(group).expect("group matched").range())
                    .collect();
                arrays.push((ranges, index));
            }
        }
        let most_least_hits = self.count_most_least_hits(text);

        let patched = if arrays.is_empty() {
            out
        } else {
            let ascii_patched = out.as_deref().unwrap_or(text);
            let mut builder = Vec::with_capacity(text.len() + 64);
            let mut position = 0;
            for (ranges, index) in &arrays {
                counts[*index] += 1;
                let msb = self.matcher.target_msb(*index);
                let lsb = self.matcher.target_lsb(*index);
                let values = [
                    (msb >> 32) as u32 as i32,
                    msb as u32 as i32,
                    (lsb >> 32) as u32 as i32,
                    lsb as u32 as i32,
                ];
                for (range, value) in ranges.iter().zip(values) {
                    builder.extend_from_slice(&ascii_patched[position..range.start]);
                    builder.extend_from_slice(value.to_string().as_bytes());
                    position = range.end;
                }
            }
            builder.extend_from_slice(&ascii_patched[position..]);
            Some(builder)
        };
        let replacements = counts
            .iter()
            .enumerate()
            .filter(|(_, count)| **count > 0)
            .map(|(index, count)| (self.matcher.sources[index], *count))
            .collect();
        Ok(TextPatchResult {
            patched,
            replacements,
            most_least_hits,
        })
    }

    fn count_most_least_hits(&self, text: &[u8]) -> usize {
        let mut most: HashMap<&[u8], Vec<u64>> = HashMap::new();
        let mut least: HashMap<&[u8], Vec<u64>> = HashMap::new();
        for captures in MOST_LEAST.captures_iter(text) {
            let Ok(value) = ascii(&captures[3]).parse::<i64>() else {
                continue;
            };
            let side = if &captures[2] == b"Most" {
                &mut most
            } else {
                &mut least
            };
            side.entry(captures.get(1).expect("group matched").as_bytes())
                .or_default()
                .push(value as u64);
        }
        let mut hits = 0;
        for (prefix, msbs) in &most {
            let Some(lsbs) = least.get(prefix) else {
                continue;
            };
            for &msb in msbs {
                for &lsb in lsbs {
                    if self.matcher.find(msb, lsb).is_some() {
                        hits += 1;
                    }
                }
            }
        }
        hits
    }
}

/// True if `bytes` are valid UTF-8 (as a reporting Java decoder sees it) and contain no NUL byte.
pub fn is_strict_utf8(bytes: &[u8]) -> bool {
    !bytes.contains(&0) && std::str::from_utf8(bytes).is_ok()
}

static DASHED_STEM: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(
        r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
    )
    .expect("valid regex")
});
static DASHLESS_STEM: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"^[0-9A-Fa-f]{32}$").expect("valid regex"));

/// Renames path segments whose stem (the part before the first `.`) is exactly a mapped player
/// UUID, dashed or dashless and in any letter case. The style, case and extension are kept. A UUID
/// that is only part of a stem is never renamed. Conflicts are checked by the caller.
pub struct SavePathRenamer {
    matcher: UuidMatcher,
}

impl SavePathRenamer {
    pub fn new(mapping: &HashMap<Uuid, Uuid>) -> Result<Self> {
        Ok(SavePathRenamer {
            matcher: UuidMatcher::new(mapping)?,
        })
    }

    /// Renames each segment of a `/`-separated relative path.
    pub fn rename(&self, relative_path: &str) -> String {
        relative_path
            .split('/')
            .map(|segment| self.rename_segment(segment))
            .collect::<Vec<_>>()
            .join("/")
    }

    pub fn rename_segment(&self, segment: &str) -> String {
        let stem_length = segment.find('.').unwrap_or(segment.len());
        let stem = &segment[..stem_length];
        if !DASHED_STEM.is_match(stem) && !DASHLESS_STEM.is_match(stem) {
            return segment.to_string();
        }
        let mut out = None;
        let replaced =
            self.matcher
                .replace_ascii(stem.as_bytes(), 0, stem_length, &mut out, |_| {});
        match out {
            Some(renamed) if replaced == 1 => {
                String::from_utf8(renamed).expect("hex digits are ASCII") + &segment[stem_length..]
            }
            _ => segment.to_string(),
        }
    }
}

/// Best-effort detection of mapped UUIDs in bytes that are copied unchanged: 16 big-endian binary
/// bytes, or dashed/dashless ASCII text. Input is fed in blocks; a 35-byte overlap between blocks
/// finds values that span two blocks. A rare false positive only adds a report line.
pub(crate) struct RawUuidScanner<'a> {
    matcher: &'a UuidMatcher,
    window: Vec<u8>,
    hit: bool,
}

impl<'a> RawUuidScanner<'a> {
    const OVERLAP: usize = 35;
    const BINARY_LENGTH: usize = 16;

    pub fn new(matcher: &'a UuidMatcher) -> Self {
        RawUuidScanner {
            matcher,
            window: Vec::new(),
            hit: false,
        }
    }

    pub fn feed(&mut self, block: &[u8]) {
        if self.hit || self.matcher.is_empty() || block.is_empty() {
            return;
        }
        let carried = self.window.len().min(Self::OVERLAP);
        let mut combined = Vec::with_capacity(carried + block.len());
        combined.extend_from_slice(&self.window[self.window.len() - carried..]);
        combined.extend_from_slice(block);
        self.hit = self.scan_window(&combined);
        self.window = combined;
    }

    pub fn scan(&mut self, bytes: &[u8]) -> bool {
        self.feed(bytes);
        self.hit
    }

    fn scan_window(&self, bytes: &[u8]) -> bool {
        if bytes.len() >= Self::BINARY_LENGTH
            && (0..=bytes.len() - Self::BINARY_LENGTH)
                .any(|offset| self.matcher.find_binary(bytes, offset).is_some())
        {
            return true;
        }
        let mut scratch = None;
        self.matcher
            .replace_ascii(bytes, 0, bytes.len(), &mut scratch, |_| {})
            > 0
    }
}
