//! Strict, allocation-free structural walking of binary NBT, and Java modified UTF-8.

use crate::error::{RemapError, Result};
use crate::limits::NBT_MAX_DEPTH;

/// NBT tag type ids.
pub mod tag {
    pub const END: u8 = 0;
    pub const BYTE: u8 = 1;
    pub const SHORT: u8 = 2;
    pub const INT: u8 = 3;
    pub const LONG: u8 = 4;
    pub const FLOAT: u8 = 5;
    pub const DOUBLE: u8 = 6;
    pub const BYTE_ARRAY: u8 = 7;
    pub const STRING: u8 = 8;
    pub const LIST: u8 = 9;
    pub const COMPOUND: u8 = 10;
    pub const INT_ARRAY: u8 = 11;
    pub const LONG_ARRAY: u8 = 12;

    /// Payload size of fixed-size tags, or 0 for variable-size tags.
    pub fn fixed_size(kind: u8) -> usize {
        match kind {
            BYTE => 1,
            SHORT => 2,
            INT | FLOAT => 4,
            LONG | DOUBLE => 8,
            _ => 0,
        }
    }
}

/// Big-endian access. Callers have checked the bounds.
pub(crate) fn u2(src: &[u8], offset: usize) -> usize {
    u16::from_be_bytes([src[offset], src[offset + 1]]) as usize
}

pub(crate) fn s4(src: &[u8], offset: usize) -> i32 {
    i32::from_be_bytes([
        src[offset],
        src[offset + 1],
        src[offset + 2],
        src[offset + 3],
    ])
}

pub(crate) fn u8x8(src: &[u8], offset: usize) -> u64 {
    let mut bytes = [0; 8];
    bytes.copy_from_slice(&src[offset..offset + 8]);
    u64::from_be_bytes(bytes)
}

pub(crate) fn put_u8x8(dst: &mut [u8], offset: usize, value: u64) {
    dst[offset..offset + 8].copy_from_slice(&value.to_be_bytes());
}

/// Callbacks for [`NbtWalker`]. Offsets are absolute offsets into the walked bytes.
pub(crate) trait NbtVisitor {
    fn begin_compound(&mut self) -> Result<()> {
        Ok(())
    }

    /// A named entry of the current compound; called before its payload is walked.
    fn compound_entry(
        &mut self,
        _kind: u8,
        _name_offset: usize,
        _name_length: usize,
        _payload_offset: usize,
    ) -> Result<()> {
        Ok(())
    }

    fn end_compound(&mut self) -> Result<()> {
        Ok(())
    }

    /// The bytes of a `TAG_String` value (modified UTF-8, without the length prefix).
    fn string_payload(&mut self, _offset: usize, _length: usize) -> Result<()> {
        Ok(())
    }

    /// The big-endian elements of a `TAG_Int_Array`.
    fn int_array_payload(&mut self, _offset: usize, _count: usize) -> Result<()> {
        Ok(())
    }

    /// The big-endian elements of a `TAG_Long_Array`.
    fn long_array_payload(&mut self, _offset: usize, _count: usize) -> Result<()> {
        Ok(())
    }
}

pub(crate) struct NoVisitor;

impl NbtVisitor for NoVisitor {}

/// A strict, read-only structural walker over binary NBT.
///
/// It never allocates from a declared length: every length is checked against the remaining input
/// before the walker moves past it. It follows Minecraft's reader rules for depth (root compound at
/// depth 0, at most [`NBT_MAX_DEPTH`]) and rejects a `TAG_End` list with a non-zero count. String
/// contents are not decoded.
pub(crate) struct NbtWalker<'a> {
    src: &'a [u8],
    start: usize,
    end: usize,
    max_depth: usize,
}

impl<'a> NbtWalker<'a> {
    pub fn new(src: &'a [u8]) -> Self {
        NbtWalker {
            src,
            start: 0,
            end: src.len(),
            max_depth: NBT_MAX_DEPTH,
        }
    }

    /// Walks a complete document: a named root compound that must end exactly at the end of the
    /// input. Returns the offset of the root compound's payload.
    pub fn walk_document(&self, visitor: &mut dyn NbtVisitor) -> Result<usize> {
        let kind = self.u1(self.start)?;
        if kind != tag::COMPOUND {
            return fail(
                format!("Root tag must be a compound, got type {kind}"),
                self.start,
            );
        }
        let name_length = self.u2(self.start + 1)?;
        let payload = self.advance(self.start + 3, name_length as i64)?;
        let after = self.walk_payload(tag::COMPOUND, payload, 0, visitor)?;
        if after != self.end {
            return fail(
                format!("{} trailing bytes after the root tag", self.end - after),
                after,
            );
        }
        Ok(payload)
    }

    /// Walks one payload of `kind` at `offset` and returns the offset just after it.
    pub fn walk_payload(
        &self,
        kind: u8,
        offset: usize,
        depth: usize,
        visitor: &mut dyn NbtVisitor,
    ) -> Result<usize> {
        match kind {
            tag::BYTE | tag::SHORT | tag::INT | tag::LONG | tag::FLOAT | tag::DOUBLE => {
                self.advance(offset, tag::fixed_size(kind) as i64)
            }
            tag::BYTE_ARRAY => {
                let count = self.array_count(offset)?;
                self.advance(offset + 4, count as i64)
            }
            tag::STRING => {
                let length = self.u2(offset)?;
                let after = self.advance(offset + 2, length as i64)?;
                visitor.string_payload(offset + 2, length)?;
                Ok(after)
            }
            tag::INT_ARRAY => {
                let count = self.array_count(offset)?;
                let after = self.advance(offset + 4, count as i64 * 4)?;
                visitor.int_array_payload(offset + 4, count)?;
                Ok(after)
            }
            tag::LONG_ARRAY => {
                let count = self.array_count(offset)?;
                let after = self.advance(offset + 4, count as i64 * 8)?;
                visitor.long_array_payload(offset + 4, count)?;
                Ok(after)
            }
            tag::LIST => self.walk_list(offset, depth, visitor),
            tag::COMPOUND => self.walk_compound(offset, depth, visitor),
            _ => fail(format!("Unknown tag type {kind}"), offset),
        }
    }

    fn walk_compound(
        &self,
        offset: usize,
        depth: usize,
        visitor: &mut dyn NbtVisitor,
    ) -> Result<usize> {
        if depth > self.max_depth {
            return fail(
                format!("NBT nesting is deeper than {}", self.max_depth),
                offset,
            );
        }
        visitor.begin_compound()?;
        let mut position = offset;
        loop {
            let kind = self.u1(position)?;
            position += 1;
            if kind == tag::END {
                break;
            }
            if kind > tag::LONG_ARRAY {
                return fail(format!("Unknown tag type {kind}"), position - 1);
            }
            let name_length = self.u2(position)?;
            let name_offset = position + 2;
            position = self.advance(name_offset, name_length as i64)?;
            visitor.compound_entry(kind, name_offset, name_length, position)?;
            position = self.walk_payload(kind, position, depth + 1, visitor)?;
        }
        visitor.end_compound()?;
        Ok(position)
    }

    fn walk_list(
        &self,
        offset: usize,
        depth: usize,
        visitor: &mut dyn NbtVisitor,
    ) -> Result<usize> {
        if depth > self.max_depth {
            return fail(
                format!("NBT nesting is deeper than {}", self.max_depth),
                offset,
            );
        }
        let element = self.u1(offset)?;
        let count = self.s4(offset + 1)?;
        if count < 0 {
            return fail(format!("Negative list length {count}"), offset + 1);
        }
        let mut position = offset + 5;
        if element == tag::END {
            if count > 0 {
                return fail(format!("List of TAG_End declares {count} elements"), offset);
            }
            return Ok(position);
        }
        if element > tag::LONG_ARRAY {
            return fail(format!("Unknown list element type {element}"), offset);
        }
        // Every element takes at least one byte.
        let remaining = self.end as i64 - position as i64;
        if count as i64 > remaining {
            return fail(
                format!("List declares {count} elements but only {remaining} bytes remain"),
                offset,
            );
        }
        let fixed = tag::fixed_size(element);
        if fixed > 0 {
            return self.advance(position, count as i64 * fixed as i64);
        }
        for _ in 0..count {
            position = self.walk_payload(element, position, depth + 1, visitor)?;
        }
        Ok(position)
    }

    fn array_count(&self, offset: usize) -> Result<usize> {
        let count = self.s4(offset)?;
        if count < 0 {
            return fail(format!("Negative array length {count}"), offset);
        }
        Ok(count as usize)
    }

    fn need(&self, offset: usize, length: i64) -> Result<()> {
        if length < 0 || offset as i64 + length > self.end as i64 {
            return fail(
                format!(
                    "Truncated NBT: {length} bytes needed but {} remain",
                    self.end as i64 - offset as i64
                ),
                offset,
            );
        }
        Ok(())
    }

    fn advance(&self, offset: usize, length: i64) -> Result<usize> {
        self.need(offset, length)?;
        Ok(offset + length as usize)
    }

    fn u1(&self, offset: usize) -> Result<u8> {
        self.need(offset, 1)?;
        Ok(self.src[offset])
    }

    fn u2(&self, offset: usize) -> Result<usize> {
        self.need(offset, 2)?;
        Ok(u2(self.src, offset))
    }

    fn s4(&self, offset: usize) -> Result<i32> {
        self.need(offset, 4)?;
        Ok(s4(self.src, offset))
    }
}

fn fail<T>(message: String, offset: usize) -> Result<T> {
    Err(RemapError::NbtFormat { message, offset })
}

/// Decodes Java modified UTF-8 exactly as `DataInputStream.readUTF` does. Unpaired surrogates,
/// which Java strings can hold, become U+FFFD.
pub(crate) fn decode_modified_utf8(bytes: &[u8]) -> Result<String> {
    let malformed =
        |message: String| Err(RemapError::invalid_data("modified UTF-8 string", message));
    let mut units = Vec::with_capacity(bytes.len());
    let mut count = 0;
    while count < bytes.len() {
        let c = bytes[count] as u16;
        match c >> 4 {
            0..=7 => {
                count += 1;
                units.push(c);
            }
            12 | 13 => {
                count += 2;
                if count > bytes.len() {
                    return malformed("malformed input: partial character at end".into());
                }
                let c2 = bytes[count - 1] as u16;
                if c2 & 0xC0 != 0x80 {
                    return malformed(format!("malformed input around byte {count}"));
                }
                units.push(((c & 0x1F) << 6) | (c2 & 0x3F));
            }
            14 => {
                count += 3;
                if count > bytes.len() {
                    return malformed("malformed input: partial character at end".into());
                }
                let c2 = bytes[count - 2] as u16;
                let c3 = bytes[count - 1] as u16;
                if c2 & 0xC0 != 0x80 || c3 & 0xC0 != 0x80 {
                    return malformed(format!("malformed input around byte {}", count - 1));
                }
                units.push(((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F));
            }
            _ => return malformed(format!("malformed input around byte {count}")),
        }
    }
    Ok(String::from_utf16_lossy(&units))
}

/// Encodes `value` as Java modified UTF-8, without the length prefix (`DataOutput.writeUTF`).
pub(crate) fn encode_modified_utf8(value: &str) -> Vec<u8> {
    let mut output = Vec::with_capacity(value.len());
    for unit in value.encode_utf16() {
        match unit {
            0x0001..=0x007F => output.push(unit as u8),
            0x0000 | 0x0080..=0x07FF => {
                output.push(0xC0 | ((unit >> 6) & 0x1F) as u8);
                output.push(0x80 | (unit & 0x3F) as u8);
            }
            _ => {
                output.push(0xE0 | ((unit >> 12) & 0x0F) as u8);
                output.push(0x80 | ((unit >> 6) & 0x3F) as u8);
                output.push(0x80 | (unit & 0x3F) as u8);
            }
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modified_utf8_round_trips() {
        for text in [
            "",
            "plain",
            "中文😀\u{0}end",
            "\u{0}",
            "é\u{7ff}\u{800}\u{ffff}",
        ] {
            assert_eq!(
                decode_modified_utf8(&encode_modified_utf8(text)).unwrap(),
                text
            );
        }
        assert_eq!(encode_modified_utf8("\u{0}"), vec![0xC0, 0x80]);
        assert_eq!(encode_modified_utf8("😀").len(), 6);
        assert!(decode_modified_utf8(&[0xC0]).is_err());
        assert!(decode_modified_utf8(&[0x80]).is_err());
        assert!(decode_modified_utf8(&[0xF0, 0x9F, 0x98, 0x80]).is_err());
        assert_eq!(decode_modified_utf8(&[0xC0, 0x80]).unwrap(), "\u{0}");
    }
}
