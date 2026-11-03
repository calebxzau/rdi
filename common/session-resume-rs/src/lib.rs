//! Resumable byte-stream sessions that survive a change of network path.
//!
//! The layer sits below the Minecraft protocol and carries raw bytes, so game
//! versions, compression, encryption and mods are unaffected. The protocol and
//! its timing budget are described in `docs/session-resume-plan.md`.

pub mod anchor;
pub mod buffer;
pub mod client;
mod engine;
pub mod protocol;

pub use engine::{AsyncStream, BoxStream, EndReason, Liveness, Tuning};
