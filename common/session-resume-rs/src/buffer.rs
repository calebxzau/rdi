//! Bounded record of the bytes sent but not yet acknowledged.

use crate::protocol::ProtocolError;
use std::collections::VecDeque;

const RETAINED_IDLE_CAPACITY: usize = 64 * 1024;

#[derive(Debug)]
pub struct SendBuffer {
    data: VecDeque<u8>,
    capacity: usize,
    /// Stream offset of `data[0]`; everything before it is acknowledged.
    base: u64,
    /// Next offset to transmit on the current path.
    cursor: u64,
    /// Highest offset ever transmitted, on any path.
    high_water: u64,
}

impl SendBuffer {
    pub fn new(capacity: usize) -> Self {
        Self {
            data: VecDeque::new(),
            capacity,
            base: 0,
            cursor: 0,
            high_water: 0,
        }
    }

    pub fn free(&self) -> usize {
        self.capacity - self.data.len()
    }

    pub fn base(&self) -> u64 {
        self.base
    }

    pub fn cursor(&self) -> u64 {
        self.cursor
    }

    pub fn high_water(&self) -> u64 {
        self.high_water
    }

    pub fn end(&self) -> u64 {
        self.base + self.data.len() as u64
    }

    pub fn fully_sent(&self) -> bool {
        self.cursor == self.end()
    }

    /// Transmitted bytes the peer has not acknowledged yet.
    pub fn in_flight(&self) -> u64 {
        self.high_water - self.base
    }

    pub fn stored_capacity(&self) -> usize {
        self.data.capacity()
    }

    pub fn push(&mut self, bytes: &[u8]) {
        assert!(bytes.len() <= self.free(), "send window exceeded");
        self.data.extend(bytes);
    }

    /// Copies up to `max` untransmitted bytes to `out` and advances the cursor.
    pub fn take_next(&mut self, max: usize, out: &mut Vec<u8>) -> usize {
        let start = (self.cursor - self.base) as usize;
        let count = (self.data.len() - start).min(max);
        out.extend(self.data.range(start..start + count));
        self.cursor += count as u64;
        self.high_water = self.high_water.max(self.cursor);
        count
    }

    /// Applies a cumulative acknowledgement and reports whether it made progress.
    pub fn ack(&mut self, offset: u64) -> Result<bool, ProtocolError> {
        if offset > self.high_water {
            return Err(ProtocolError("acknowledged bytes that were never sent"));
        }
        if offset <= self.base {
            return Ok(false);
        }
        self.data.drain(..(offset - self.base) as usize);
        self.base = offset;
        self.cursor = self.cursor.max(offset);
        if self.data.is_empty() && self.data.capacity() > RETAINED_IDLE_CAPACITY {
            self.data = VecDeque::new();
        }
        Ok(true)
    }

    /// Moves the cursor back so a new path replays from `offset`, clamped up to
    /// the acknowledged position. Returns where the replay actually starts.
    pub fn rewind(&mut self, offset: u64) -> Result<u64, ProtocolError> {
        if offset > self.high_water {
            return Err(ProtocolError("peer claims bytes that were never sent"));
        }
        self.cursor = offset.max(self.base);
        Ok(self.cursor)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn taken(buffer: &mut SendBuffer, max: usize) -> Vec<u8> {
        let mut out = Vec::new();
        buffer.take_next(max, &mut out);
        out
    }

    #[test]
    fn window_limits_what_is_free() {
        let mut buffer = SendBuffer::new(4);
        buffer.push(&[1, 2, 3]);
        assert_eq!(buffer.free(), 1);
        buffer.push(&[4]);
        assert_eq!(buffer.free(), 0);
    }

    #[test]
    #[should_panic(expected = "send window exceeded")]
    fn pushing_past_the_window_is_a_bug() {
        SendBuffer::new(1).push(&[1, 2]);
    }

    #[test]
    fn ack_releases_space_and_keeps_cursor_valid() {
        let mut buffer = SendBuffer::new(8);
        buffer.push(&[1, 2, 3, 4]);
        assert_eq!(taken(&mut buffer, 3), [1, 2, 3]);
        assert!(buffer.ack(2).unwrap());
        assert_eq!((buffer.base(), buffer.free()), (2, 6));
        assert_eq!(taken(&mut buffer, 8), [4]);
        assert!(!buffer.ack(1).unwrap());
    }

    #[test]
    fn ack_past_anything_sent_is_rejected() {
        let mut buffer = SendBuffer::new(8);
        buffer.push(&[1, 2, 3, 4]);
        taken(&mut buffer, 2);
        assert!(buffer.ack(3).is_err());
    }

    #[test]
    fn rewind_replays_unacknowledged_bytes() {
        let mut buffer = SendBuffer::new(8);
        buffer.push(&[1, 2, 3, 4]);
        taken(&mut buffer, 4);
        buffer.ack(1).unwrap();
        assert_eq!(buffer.rewind(2).unwrap(), 2);
        assert_eq!(taken(&mut buffer, 8), [3, 4]);
    }

    #[test]
    fn rewind_clamps_to_acknowledged_and_rejects_unsent() {
        let mut buffer = SendBuffer::new(8);
        buffer.push(&[1, 2, 3, 4]);
        taken(&mut buffer, 3);
        buffer.ack(2).unwrap();
        assert_eq!(buffer.rewind(1).unwrap(), 2);
        assert_eq!(taken(&mut buffer, 1), [3]);
        assert!(buffer.rewind(4).is_err());
    }

    #[test]
    fn high_water_survives_rewind() {
        let mut buffer = SendBuffer::new(8);
        buffer.push(&[1, 2, 3]);
        taken(&mut buffer, 3);
        buffer.rewind(0).unwrap();
        assert_eq!(buffer.high_water(), 3);
        assert!(buffer.ack(3).unwrap());
        assert!(buffer.fully_sent());
    }

    #[test]
    fn idle_buffer_releases_large_allocations() {
        let mut buffer = SendBuffer::new(1024 * 1024);
        buffer.push(&vec![0; 512 * 1024]);
        taken(&mut buffer, 512 * 1024);
        buffer.ack(512 * 1024).unwrap();
        assert!(buffer.stored_capacity() <= RETAINED_IDLE_CAPACITY);
    }
}
