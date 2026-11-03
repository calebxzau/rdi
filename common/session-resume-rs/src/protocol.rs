//! Wire format, version 1. All integers are big-endian.

use std::{error::Error, fmt};

pub const MAGIC: [u8; 4] = *b"RDSR";
pub const VERSION: u8 = 1;
pub const CLIENT_HELLO_LEN: usize = 34;
pub const SERVER_HELLO_LEN: usize = 26;
pub const MAX_DATA: usize = 16 * 1024;
pub const TOKEN_LEN: usize = 16;

const FLAG_RESUME: u8 = 0b01;
const FLAG_PROBE: u8 = 0b10;

const TYPE_DATA: u8 = 0x01;
const TYPE_ACK: u8 = 0x02;
const TYPE_PING: u8 = 0x03;
const TYPE_PONG: u8 = 0x04;
const TYPE_CLOSE: u8 = 0x05;
const TYPE_CLOSE_ACK: u8 = 0x06;

pub type Token = [u8; TOKEN_LEN];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ProtocolError(pub &'static str);

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.0)
    }
}

impl Error for ProtocolError {}

/// What the first bytes of an accepted connection belong to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Prefix {
    Resume,
    Legacy,
    NeedMore,
}

/// A Minecraft handshake can never start with the magic: `R` reads as a frame
/// length below 128, which makes the next byte the packet id, and a handshake's
/// packet id is 0x00 rather than `D`. Legacy list pings start with 0xFE.
pub fn classify_prefix(bytes: &[u8]) -> Prefix {
    let compared = bytes.len().min(MAGIC.len());
    if compared == 0 {
        Prefix::NeedMore
    } else if bytes[..compared] != MAGIC[..compared] {
        Prefix::Legacy
    } else if compared == MAGIC.len() {
        Prefix::Resume
    } else {
        Prefix::NeedMore
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ClientHello {
    pub version: u8,
    pub resume: bool,
    /// A probe only tests the path end to end and never touches the session.
    pub probe: bool,
    pub token: Token,
    pub generation: u32,
    pub recv_offset: u64,
}

impl ClientHello {
    pub fn encode(&self) -> [u8; CLIENT_HELLO_LEN] {
        let mut bytes = [0u8; CLIENT_HELLO_LEN];
        bytes[..4].copy_from_slice(&MAGIC);
        bytes[4] = self.version;
        let resume = if self.resume { FLAG_RESUME } else { 0 };
        let probe = if self.probe { FLAG_PROBE } else { 0 };
        bytes[5] = resume | probe;
        bytes[6..22].copy_from_slice(&self.token);
        bytes[22..26].copy_from_slice(&self.generation.to_be_bytes());
        bytes[26..34].copy_from_slice(&self.recv_offset.to_be_bytes());
        bytes
    }

    pub fn decode(bytes: &[u8; CLIENT_HELLO_LEN]) -> Result<Self, ProtocolError> {
        if bytes[..4] != MAGIC {
            return Err(ProtocolError("bad client hello magic"));
        }
        let flags = bytes[5];
        if flags & !(FLAG_RESUME | FLAG_PROBE) != 0 {
            return Err(ProtocolError("unknown client hello flags"));
        }
        Ok(Self {
            version: bytes[4],
            resume: flags & FLAG_RESUME != 0,
            probe: flags & FLAG_PROBE != 0,
            token: bytes[6..22].try_into().expect("slice length"),
            generation: u32::from_be_bytes(bytes[22..26].try_into().expect("slice length")),
            recv_offset: u64::from_be_bytes(bytes[26..34].try_into().expect("slice length")),
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum HelloStatus {
    Ok = 0,
    UnknownSession = 1,
    StaleGeneration = 2,
    UnsupportedVersion = 3,
    TokenInUse = 4,
    Full = 5,
    InvalidOffset = 6,
}

impl HelloStatus {
    fn from_byte(byte: u8) -> Result<Self, ProtocolError> {
        Ok(match byte {
            0 => Self::Ok,
            1 => Self::UnknownSession,
            2 => Self::StaleGeneration,
            3 => Self::UnsupportedVersion,
            4 => Self::TokenInUse,
            5 => Self::Full,
            6 => Self::InvalidOffset,
            _ => return Err(ProtocolError("unknown hello status")),
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ServerHello {
    pub version: u8,
    pub status: HelloStatus,
    /// Bytes the anchor has accepted; the client replays from here.
    pub recv_offset: u64,
    /// Where the anchor's replay starts. This can be ahead of the offset the
    /// client announced when an acknowledgement overtook the hello.
    pub send_from: u64,
    pub grace_ms: u32,
}

impl ServerHello {
    pub fn rejected(status: HelloStatus) -> Self {
        Self {
            version: VERSION,
            status,
            recv_offset: 0,
            send_from: 0,
            grace_ms: 0,
        }
    }

    pub fn encode(&self) -> [u8; SERVER_HELLO_LEN] {
        let mut bytes = [0u8; SERVER_HELLO_LEN];
        bytes[..4].copy_from_slice(&MAGIC);
        bytes[4] = self.version;
        bytes[5] = self.status as u8;
        bytes[6..14].copy_from_slice(&self.recv_offset.to_be_bytes());
        bytes[14..22].copy_from_slice(&self.send_from.to_be_bytes());
        bytes[22..26].copy_from_slice(&self.grace_ms.to_be_bytes());
        bytes
    }

    pub fn decode(bytes: &[u8; SERVER_HELLO_LEN]) -> Result<Self, ProtocolError> {
        if bytes[..4] != MAGIC {
            return Err(ProtocolError("bad server hello magic"));
        }
        Ok(Self {
            version: bytes[4],
            status: HelloStatus::from_byte(bytes[5])?,
            recv_offset: u64::from_be_bytes(bytes[6..14].try_into().expect("slice length")),
            send_from: u64::from_be_bytes(bytes[14..22].try_into().expect("slice length")),
            grace_ms: u32::from_be_bytes(bytes[22..26].try_into().expect("slice length")),
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Frame<'a> {
    Data(&'a [u8]),
    Ack(u64),
    Ping(u32),
    Pong(u32),
    Close { final_offset: u64, reason: u8 },
    CloseAck,
}

impl Frame<'_> {
    pub fn encode_into(&self, out: &mut Vec<u8>) {
        match self {
            Frame::Data(payload) => {
                debug_assert!(!payload.is_empty() && payload.len() <= MAX_DATA);
                out.push(TYPE_DATA);
                out.extend_from_slice(&(payload.len() as u16).to_be_bytes());
                out.extend_from_slice(payload);
            }
            Frame::Ack(offset) => {
                out.push(TYPE_ACK);
                out.extend_from_slice(&offset.to_be_bytes());
            }
            Frame::Ping(nonce) => {
                out.push(TYPE_PING);
                out.extend_from_slice(&nonce.to_be_bytes());
            }
            Frame::Pong(nonce) => {
                out.push(TYPE_PONG);
                out.extend_from_slice(&nonce.to_be_bytes());
            }
            Frame::Close {
                final_offset,
                reason,
            } => {
                out.push(TYPE_CLOSE);
                out.extend_from_slice(&final_offset.to_be_bytes());
                out.push(*reason);
            }
            Frame::CloseAck => out.push(TYPE_CLOSE_ACK),
        }
    }

    /// Decodes one frame and how many bytes it used, or `None` when incomplete.
    pub fn decode(bytes: &[u8]) -> Result<Option<(Frame<'_>, usize)>, ProtocolError> {
        let Some(&kind) = bytes.first() else {
            return Ok(None);
        };
        let body = &bytes[1..];
        let fixed = |len: usize| body.get(..len);
        Ok(match kind {
            TYPE_DATA => {
                let Some(header) = fixed(2) else {
                    return Ok(None);
                };
                let len = u16::from_be_bytes(header.try_into().expect("slice length")) as usize;
                if len == 0 || len > MAX_DATA {
                    return Err(ProtocolError("invalid data frame length"));
                }
                body.get(2..2 + len)
                    .map(|payload| (Frame::Data(payload), 3 + len))
            }
            TYPE_ACK => fixed(8).map(|value| {
                let offset = u64::from_be_bytes(value.try_into().expect("slice length"));
                (Frame::Ack(offset), 9)
            }),
            TYPE_PING => fixed(4).map(|value| {
                let nonce = u32::from_be_bytes(value.try_into().expect("slice length"));
                (Frame::Ping(nonce), 5)
            }),
            TYPE_PONG => fixed(4).map(|value| {
                let nonce = u32::from_be_bytes(value.try_into().expect("slice length"));
                (Frame::Pong(nonce), 5)
            }),
            TYPE_CLOSE => fixed(9).map(|value| {
                let final_offset = u64::from_be_bytes(value[..8].try_into().expect("slice length"));
                (
                    Frame::Close {
                        final_offset,
                        reason: value[8],
                    },
                    10,
                )
            }),
            TYPE_CLOSE_ACK => Some((Frame::CloseAck, 1)),
            _ => return Err(ProtocolError("unknown frame type")),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_round_trips() {
        let client = ClientHello {
            version: VERSION,
            resume: true,
            probe: true,
            token: [7; TOKEN_LEN],
            generation: 9,
            recv_offset: u64::MAX - 1,
        };
        assert_eq!(ClientHello::decode(&client.encode()).unwrap(), client);

        let server = ServerHello {
            version: VERSION,
            status: HelloStatus::StaleGeneration,
            recv_offset: 5,
            send_from: 6,
            grace_ms: 20_000,
        };
        assert_eq!(ServerHello::decode(&server.encode()).unwrap(), server);
    }

    #[test]
    fn hello_rejects_bad_magic_flags_and_status() {
        let client = ClientHello {
            version: VERSION,
            resume: false,
            probe: false,
            token: [0; TOKEN_LEN],
            generation: 0,
            recv_offset: 0,
        };
        let mut bad_magic = client.encode();
        bad_magic[0] = b'X';
        assert!(ClientHello::decode(&bad_magic).is_err());
        let mut bad_flags = client.encode();
        bad_flags[5] = 0b100;
        assert!(ClientHello::decode(&bad_flags).is_err());

        let mut bad_status = ServerHello::rejected(HelloStatus::Full).encode();
        bad_status[5] = 200;
        assert!(ServerHello::decode(&bad_status).is_err());
    }

    #[test]
    fn frames_round_trip_and_report_consumed_bytes() {
        let payload = [3u8; 100];
        let frames = [
            Frame::Data(&payload),
            Frame::Ack(1 << 40),
            Frame::Ping(1),
            Frame::Pong(2),
            Frame::Close {
                final_offset: 77,
                reason: 4,
            },
            Frame::CloseAck,
        ];
        let mut bytes = Vec::new();
        frames
            .iter()
            .for_each(|frame| frame.encode_into(&mut bytes));
        let mut position = 0;
        for expected in frames {
            let (frame, used) = Frame::decode(&bytes[position..]).unwrap().unwrap();
            assert_eq!(frame, expected);
            position += used;
        }
        assert_eq!(position, bytes.len());
    }

    #[test]
    fn partial_frames_wait_for_more_bytes() {
        let mut bytes = Vec::new();
        Frame::Data(&[1, 2, 3]).encode_into(&mut bytes);
        for end in 0..bytes.len() {
            assert_eq!(Frame::decode(&bytes[..end]).unwrap(), None);
        }
        assert!(Frame::decode(&bytes).unwrap().is_some());
    }

    #[test]
    fn invalid_frames_are_errors() {
        assert!(Frame::decode(&[0xee]).is_err());
        assert!(Frame::decode(&[TYPE_DATA, 0, 0]).is_err());
        let too_long = (MAX_DATA as u16 + 1).to_be_bytes();
        assert!(Frame::decode(&[TYPE_DATA, too_long[0], too_long[1]]).is_err());
    }

    #[test]
    fn prefix_classification_handles_fragmented_input() {
        assert_eq!(classify_prefix(b""), Prefix::NeedMore);
        assert_eq!(classify_prefix(b"R"), Prefix::NeedMore);
        assert_eq!(classify_prefix(b"RDS"), Prefix::NeedMore);
        assert_eq!(classify_prefix(b"RDSR"), Prefix::Resume);
        assert_eq!(classify_prefix(b"RDSR-more"), Prefix::Resume);
    }

    #[test]
    fn minecraft_openers_are_never_mistaken_for_resume() {
        // Handshake frames whose length starts like the magic, and the legacy list ping.
        assert_eq!(classify_prefix(&[0x52, 0x00, 0x80, 0x05]), Prefix::Legacy);
        assert_eq!(classify_prefix(&[0x10, 0x00]), Prefix::Legacy);
        assert_eq!(classify_prefix(&[0xFE, 0x01]), Prefix::Legacy);
    }
}
