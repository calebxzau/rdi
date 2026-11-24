use std::collections::{HashMap, HashSet};

use uuid::Uuid;

use crate::error::{RemapError, Result};
use crate::nbt::{NbtVisitor, NbtWalker, NoVisitor, put_u8x8, tag, u8x8};
use crate::uuid_matcher::UuidMatcher;

/// Output of [`NbtBytePatcher::patch`].
#[derive(Debug)]
pub struct NbtPatchResult {
    /// The patched document, or `None` when nothing changed (the input is the result).
    pub patched: Option<Vec<u8>>,
    pub replacements: HashMap<Uuid, usize>,
}

impl NbtPatchResult {
    pub fn changed(&self) -> bool {
        self.patched.is_some()
    }

    /// The resulting bytes: the patched copy, or `original` when nothing changed.
    pub fn bytes<'a>(&'a self, original: &'a [u8]) -> &'a [u8] {
        self.patched.as_deref().unwrap_or(original)
    }
}

/// Replaces mapped player UUIDs inside binary NBT without decoding or re-encoding anything.
///
/// Every replacement preserves length (16-byte `IntArray[4]`/`LongArray[2]`, `Most`/`Least` long
/// pairs, and 36/32-character ASCII UUIDs in strings and compound keys), so the output is the input
/// with only the matched bytes changed. Strings are matched on their raw modified UTF-8 bytes: bytes
/// below `0x80` never occur inside a multi-byte sequence.
///
/// The whole document is validated structurally whether or not anything matches.
pub struct NbtBytePatcher {
    matcher: UuidMatcher,
}

impl NbtBytePatcher {
    pub fn new(mapping: &HashMap<Uuid, Uuid>) -> Result<Self> {
        Ok(NbtBytePatcher {
            matcher: UuidMatcher::new(mapping)?,
        })
    }

    /// Validates `nbt` as a complete document and replaces every mapped UUID in it.
    pub fn patch(&self, nbt: &[u8]) -> Result<NbtPatchResult> {
        if self.matcher.is_empty() {
            NbtWalker::new(nbt).walk_document(&mut NoVisitor)?;
            return Ok(NbtPatchResult {
                patched: None,
                replacements: HashMap::new(),
            });
        }
        let mut visitor = PatchVisitor {
            src: nbt,
            matcher: &self.matcher,
            out: None,
            counts: vec![0; self.matcher.sources.len()],
            frames: Vec::new(),
            depth: -1,
        };
        NbtWalker::new(nbt).walk_document(&mut visitor)?;
        let replacements = visitor
            .counts
            .iter()
            .enumerate()
            .filter(|(_, count)| **count > 0)
            .map(|(index, count)| (self.matcher.sources[index], *count))
            .collect();
        if let Some(patched) = &visitor.out {
            assert_eq!(patched.len(), nbt.len(), "Patched NBT changed length");
        }
        Ok(NbtPatchResult {
            patched: visitor.out,
            replacements,
        })
    }

    /// Checks that `nbt` is a structurally valid document, without matching anything.
    pub fn validate(nbt: &[u8]) -> Result<()> {
        NbtWalker::new(nbt)
            .walk_document(&mut NoVisitor)
            .map(|_| ())
    }
}

/// Per-compound state, reused across compounds at the same depth.
#[derive(Default)]
struct CompoundFrame {
    renamed: bool,
    /// (offset, length) of every key.
    keys: Vec<(usize, usize)>,
    /// (prefix offset, prefix length, payload offset) of `…Most` longs.
    most: Vec<(usize, usize, usize)>,
    least: Vec<(usize, usize, usize)>,
}

impl CompoundFrame {
    fn reset(&mut self) {
        self.renamed = false;
        self.keys.clear();
        self.most.clear();
        self.least.clear();
    }
}

struct PatchVisitor<'a> {
    src: &'a [u8],
    matcher: &'a UuidMatcher,
    out: Option<Vec<u8>>,
    counts: Vec<usize>,
    frames: Vec<CompoundFrame>,
    depth: isize,
}

impl PatchVisitor<'_> {
    fn replace_binary(&mut self, offset: usize) {
        if let Some(index) = self.matcher.find_binary(self.src, offset) {
            let src = self.src;
            self.matcher
                .write_binary(index, self.out.get_or_insert_with(|| src.to_vec()), offset);
            self.counts[index] += 1;
        }
    }

    fn ends_with(&self, offset: usize, length: usize, suffix: &[u8]) -> bool {
        length >= suffix.len()
            && &self.src[offset + length - suffix.len()..offset + length] == suffix
    }

    fn pair_most_least(&mut self, frame: &CompoundFrame) {
        let src = self.src;
        for &(most_prefix, most_length, most_payload) in &frame.most {
            for &(least_prefix, least_length, least_payload) in &frame.least {
                if most_length != least_length
                    || src[most_prefix..most_prefix + most_length]
                        != src[least_prefix..least_prefix + least_length]
                {
                    continue;
                }
                if let Some(index) = self
                    .matcher
                    .find(u8x8(src, most_payload), u8x8(src, least_payload))
                {
                    let dst = self.out.get_or_insert_with(|| src.to_vec());
                    put_u8x8(dst, most_payload, self.matcher.target_msb(index));
                    put_u8x8(dst, least_payload, self.matcher.target_lsb(index));
                    self.counts[index] += 1;
                }
            }
        }
    }

    /// After a key rename, the compound's keys (as patched) must still be unique.
    fn check_key_conflicts(&self, frame: &CompoundFrame) -> Result<()> {
        let current = self.out.as_deref().unwrap_or(self.src);
        let mut seen = HashSet::with_capacity(frame.keys.len() * 2);
        for &(offset, length) in &frame.keys {
            let key = &current[offset..offset + length];
            if !seen.insert(key) {
                let shown: String = key.iter().map(|&b| b as char).collect();
                return Err(RemapError::NbtKeyConflict(format!(
                    "Renaming a UUID key would duplicate key '{shown}' in the same compound"
                )));
            }
        }
        Ok(())
    }
}

impl NbtVisitor for PatchVisitor<'_> {
    fn begin_compound(&mut self) -> Result<()> {
        self.depth += 1;
        if self.depth as usize == self.frames.len() {
            self.frames.push(CompoundFrame::default());
        }
        self.frames[self.depth as usize].reset();
        Ok(())
    }

    fn compound_entry(
        &mut self,
        kind: u8,
        name_offset: usize,
        name_length: usize,
        payload_offset: usize,
    ) -> Result<()> {
        let depth = self.depth as usize;
        let src = self.src;
        let counts = &mut self.counts;
        let renamed = self.matcher.replace_ascii(
            src,
            name_offset,
            name_offset + name_length,
            &mut self.out,
            |i| counts[i] += 1,
        );
        let most = kind == tag::LONG && self.ends_with(name_offset, name_length, b"Most");
        let least =
            kind == tag::LONG && !most && self.ends_with(name_offset, name_length, b"Least");
        let frame = &mut self.frames[depth];
        frame.keys.push((name_offset, name_length));
        if renamed > 0 {
            frame.renamed = true;
        }
        if most {
            frame
                .most
                .push((name_offset, name_length - 4, payload_offset));
        } else if least {
            frame
                .least
                .push((name_offset, name_length - 5, payload_offset));
        }
        Ok(())
    }

    fn end_compound(&mut self) -> Result<()> {
        let depth = self.depth as usize;
        let frame = std::mem::take(&mut self.frames[depth]);
        let result = if frame.renamed {
            self.check_key_conflicts(&frame)
        } else {
            Ok(())
        };
        if result.is_ok() {
            self.pair_most_least(&frame);
        }
        self.frames[depth] = frame;
        self.depth -= 1;
        result
    }

    fn string_payload(&mut self, offset: usize, length: usize) -> Result<()> {
        let counts = &mut self.counts;
        self.matcher
            .replace_ascii(self.src, offset, offset + length, &mut self.out, |i| {
                counts[i] += 1
            });
        Ok(())
    }

    fn int_array_payload(&mut self, offset: usize, count: usize) -> Result<()> {
        if count == 4 {
            self.replace_binary(offset);
        }
        Ok(())
    }

    fn long_array_payload(&mut self, offset: usize, count: usize) -> Result<()> {
        if count == 2 {
            self.replace_binary(offset);
        }
        Ok(())
    }
}
