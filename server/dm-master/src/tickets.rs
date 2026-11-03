//! Verifies the DM tickets signed by the RDI master. dm-master holds the public keys only, so it
//! can tell who a player is but can never issue a ticket or see the account token.

use base64::{
    Engine,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use ed25519_dalek::{Signature, VerifyingKey};
use serde::Deserialize;
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

pub const AUDIENCE_API: &str = "api";
pub const AUDIENCE_TUNNEL: &str = "tunnel";

const MAX_TICKET_BYTES: usize = 2048;
/// Tolerates clock drift between the RDI master and dm-master.
const CLOCK_LEEWAY_SECONDS: i64 = 30;

/// A player proven by a valid ticket.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TicketPlayer {
    pub id: Uuid,
    pub name: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TicketError {
    Malformed,
    BadSignature,
    WrongAudience,
    WrongRoom,
    Expired,
}

#[derive(Debug, Deserialize)]
struct Payload {
    v: u32,
    aud: String,
    sub: Uuid,
    name: String,
    room: Option<Uuid>,
    exp: i64,
}

#[derive(Debug, Clone, Default)]
pub struct TicketKeys {
    keys: Vec<VerifyingKey>,
}

impl TicketKeys {
    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    /// Adds a raw 32-byte Ed25519 public key in standard base64. Several keys may be active while
    /// the master's signing key is being replaced.
    pub fn add_base64(&mut self, encoded: &str) -> anyhow::Result<()> {
        let bytes: [u8; 32] = STANDARD
            .decode(encoded.trim())
            .map_err(|_| anyhow::anyhow!("ticket public key is not base64"))?
            .try_into()
            .map_err(|_| anyhow::anyhow!("ticket public key must be 32 bytes"))?;
        self.keys.push(
            VerifyingKey::from_bytes(&bytes)
                .map_err(|_| anyhow::anyhow!("ticket public key is not a valid Ed25519 key"))?,
        );
        Ok(())
    }

    #[cfg(test)]
    pub fn add(&mut self, key: VerifyingKey) {
        self.keys.push(key);
    }

    pub fn verify(
        &self,
        ticket: &str,
        audience: &str,
        room: Option<Uuid>,
    ) -> Result<TicketPlayer, TicketError> {
        self.verify_at(ticket, audience, room, unix_seconds())
    }

    fn verify_at(
        &self,
        ticket: &str,
        audience: &str,
        room: Option<Uuid>,
        now: i64,
    ) -> Result<TicketPlayer, TicketError> {
        if ticket.len() > MAX_TICKET_BYTES {
            return Err(TicketError::Malformed);
        }
        let (encoded_payload, encoded_signature) =
            ticket.split_once('.').ok_or(TicketError::Malformed)?;
        let signature: [u8; 64] = URL_SAFE_NO_PAD
            .decode(encoded_signature)
            .map_err(|_| TicketError::Malformed)?
            .try_into()
            .map_err(|_| TicketError::Malformed)?;
        let signature = Signature::from_bytes(&signature);
        // Nothing in the payload is read until a trusted key has signed it.
        if !self.keys.iter().any(|key| {
            key.verify_strict(encoded_payload.as_bytes(), &signature)
                .is_ok()
        }) {
            return Err(TicketError::BadSignature);
        }
        let payload: Payload = URL_SAFE_NO_PAD
            .decode(encoded_payload)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .ok_or(TicketError::Malformed)?;
        if payload.v != 1 {
            return Err(TicketError::Malformed);
        }
        if payload.aud != audience {
            return Err(TicketError::WrongAudience);
        }
        if room.is_some() && payload.room != room {
            return Err(TicketError::WrongRoom);
        }
        if now > payload.exp.saturating_add(CLOCK_LEEWAY_SECONDS) {
            return Err(TicketError::Expired);
        }
        Ok(TicketPlayer {
            id: payload.sub,
            name: payload.name,
        })
    }
}

pub fn unix_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |value| value.as_secs() as i64)
}

#[cfg(test)]
pub mod test_support {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    pub const TEST_PLAYER: Uuid = Uuid::from_u128(0x68b314bb_adaf_52dd_ab96_b5ed00000000);

    pub fn test_key() -> SigningKey {
        SigningKey::from_bytes(&[7; 32])
    }

    pub fn sign(key: &SigningKey, payload: &serde_json::Value) -> String {
        let encoded = URL_SAFE_NO_PAD.encode(payload.to_string());
        let signature = key.sign(encoded.as_bytes());
        format!("{encoded}.{}", URL_SAFE_NO_PAD.encode(signature.to_bytes()))
    }

    pub fn ticket(audience: &str, player: Uuid, room: Option<Uuid>, exp: i64) -> String {
        sign(
            &test_key(),
            &serde_json::json!({
                "v": 1, "kid": "test", "aud": audience, "sub": player, "name": "Steve",
                "room": room, "jti": Uuid::now_v7(), "iat": exp - 60, "exp": exp,
            }),
        )
    }

    pub fn valid_ticket(audience: &str, player: Uuid, room: Option<Uuid>) -> String {
        ticket(audience, player, room, unix_seconds() + 600)
    }
}

#[cfg(test)]
mod tests {
    use super::{test_support::*, *};
    use ed25519_dalek::SigningKey;

    const VECTOR_PUBLIC_KEY: &str = "Q5hKLEAJnWx/a/rYnyXrdlZjA3EzEa9QqI6a1M6Mtf8=";
    const VECTOR_JOIN_TICKET: &str = "eyJ2IjoxLCJraWQiOiJ0ZXN0IiwiYXVkIjoiam9pbiIsInN1YiI6IjY4YjMxNGJiLWFkYWYtNTJkZC1hYjk2LWI1ZWQwMDAwMDAwMCIsIm5hbWUiOiLmtYvor5XnjqnlrrYiLCJyb29tIjoiMDFhMTgwNWUtMzJlYy03MTI5LWFjMDAtMWU3ZGQ0YjRkMTUzIiwianRpIjoiMDFhMTgwNWUtMzJlYy03MTI5LWFjMDAtMDAwMDAwMDAwMDAxIiwiaWF0IjoxODAwMDAwMDAwLCJleHAiOjE4MDAwMDAwNjB9.ViZDtUbdMF4AgGdVbH-SA6PsRfQ5dIZhdPnq8vI5ugQUvGXcUwCRXkpRsozNUmr5DdoelkqFFxAVc3tDPZqsAw";
    const NOW: i64 = 1_800_000_000;

    fn keys() -> TicketKeys {
        let mut keys = TicketKeys::default();
        keys.add(test_key().verifying_key());
        keys
    }

    #[test]
    fn accepts_the_vector_signed_by_the_rdi_master() {
        let mut keys = TicketKeys::default();
        keys.add_base64(VECTOR_PUBLIC_KEY).unwrap();
        let room = Uuid::parse_str("01a1805e-32ec-7129-ac00-1e7dd4b4d153").unwrap();
        assert_eq!(
            keys.verify_at(VECTOR_JOIN_TICKET, "join", Some(room), NOW),
            Ok(TicketPlayer {
                id: TEST_PLAYER,
                name: "测试玩家".into(),
            })
        );
    }

    #[test]
    fn refuses_a_ticket_from_another_key_or_one_that_was_changed() {
        let ticket = ticket(AUDIENCE_API, TEST_PLAYER, None, NOW + 60);
        assert!(keys().verify_at(&ticket, AUDIENCE_API, None, NOW).is_ok());

        let mut other = TicketKeys::default();
        other.add(SigningKey::from_bytes(&[9; 32]).verifying_key());
        assert_eq!(
            other.verify_at(&ticket, AUDIENCE_API, None, NOW),
            Err(TicketError::BadSignature)
        );
        assert_eq!(
            TicketKeys::default().verify_at(&ticket, AUDIENCE_API, None, NOW),
            Err(TicketError::BadSignature)
        );

        let (payload, signature) = ticket.split_once('.').unwrap();
        let forged = URL_SAFE_NO_PAD.encode(
            String::from_utf8(URL_SAFE_NO_PAD.decode(payload).unwrap())
                .unwrap()
                .replace("Steve", "Alex_"),
        );
        assert_eq!(
            keys().verify_at(&format!("{forged}.{signature}"), AUDIENCE_API, None, NOW),
            Err(TicketError::BadSignature)
        );
    }

    #[test]
    fn a_ticket_only_works_for_its_audience_and_room() {
        let room = Uuid::now_v7();
        let tunnel = ticket(AUDIENCE_TUNNEL, TEST_PLAYER, Some(room), NOW + 60);
        assert!(keys().verify_at(&tunnel, AUDIENCE_TUNNEL, Some(room), NOW).is_ok());
        assert_eq!(
            keys().verify_at(&tunnel, AUDIENCE_API, None, NOW),
            Err(TicketError::WrongAudience)
        );
        assert_eq!(
            keys().verify_at(&tunnel, AUDIENCE_TUNNEL, Some(Uuid::now_v7()), NOW),
            Err(TicketError::WrongRoom)
        );
        let join = ticket("join", TEST_PLAYER, Some(room), NOW + 60);
        assert_eq!(
            keys().verify_at(&join, AUDIENCE_TUNNEL, Some(room), NOW),
            Err(TicketError::WrongAudience)
        );
    }

    #[test]
    fn an_expired_ticket_is_refused_after_the_leeway() {
        let expiring = ticket(AUDIENCE_API, TEST_PLAYER, None, NOW);
        assert!(keys()
            .verify_at(&expiring, AUDIENCE_API, None, NOW + CLOCK_LEEWAY_SECONDS)
            .is_ok());
        assert_eq!(
            keys().verify_at(&expiring, AUDIENCE_API, None, NOW + CLOCK_LEEWAY_SECONDS + 1),
            Err(TicketError::Expired)
        );
    }

    #[test]
    fn broken_tickets_and_keys_are_reported() {
        for broken in ["", "no-dot", "a.b", "!!.!!"] {
            assert_eq!(
                keys().verify_at(broken, AUDIENCE_API, None, NOW),
                Err(TicketError::Malformed)
            );
        }
        assert_eq!(
            keys().verify_at(&"a".repeat(MAX_TICKET_BYTES + 1), AUDIENCE_API, None, NOW),
            Err(TicketError::Malformed)
        );
        assert!(TicketKeys::default().add_base64("not base64!").is_err());
        assert!(TicketKeys::default().add_base64("AAAA").is_err());
    }
}
