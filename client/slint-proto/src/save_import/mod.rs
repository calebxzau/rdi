//! A3: importing a singleplayer save into a host (导入存档), the Rust form of the Kotlin client
//! services `HostSaveImportMarkingService`, `HostSaveImportService` and `HostSaveImportApi`, on
//! top of `rdi-anvilrw`. No Slint types, so the screens (A4, `save_import_ui.rs`, with the steps in
//! `flow`) and the planned launcher MCP can both call it.
pub mod api;
pub mod flow;
pub mod marking;
pub mod service;
pub mod upload;
#[cfg(test)]
mod tests;
