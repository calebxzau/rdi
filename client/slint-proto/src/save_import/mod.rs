//! A3: importing a singleplayer save into a host (导入存档), the Rust form of the Kotlin client
//! services `HostSaveImportMarkingService`, `HostSaveImportService` and `HostSaveImportApi`, on
//! top of `rdi-anvilrw`. No Slint types, so the screens (A4) and the planned launcher MCP can
//! both call it.
// The screens arrive in A4; until then nothing outside the tests calls this module.
#![allow(dead_code)]
pub mod api;
pub mod marking;
pub mod service;
pub mod upload;
#[cfg(test)]
mod tests;
