//! Resource limits for save import processing. Every decompression in this crate is bounded by one
//! of these values, so a small compressed input can never expand without limit.

/// Decompressed size of one region chunk, inline or external.
pub const CHUNK_DECOMPRESSED_BYTES: usize = 128 * 1024 * 1024;

/// Compressed size of one external chunk file (`c.<x>.<z>.mcc`).
pub const MCC_COMPRESSED_BYTES: u64 = 128 * 1024 * 1024;

/// Decompressed size of `level.dat`, `level.dat_old` and playerdata files.
pub const STRICT_NBT_FILE_BYTES: usize = 32 * 1024 * 1024;

/// Decompressed size of any other gzip or raw NBT file.
pub const OTHER_NBT_FILE_BYTES: usize = 128 * 1024 * 1024;

/// Size of one text file handled by the text patcher.
pub const TEXT_FILE_BYTES: usize = 64 * 1024 * 1024;

/// Maximum NBT nesting depth, the same as Minecraft's `CompoundTag`/`ListTag` readers.
pub const NBT_MAX_DEPTH: usize = 512;

/// Memory that parallel region processing may reserve in total. The Kotlin version used a third of
/// the JVM heap; the launcher has no heap limit, so this is a fixed budget instead.
pub const DEFAULT_MEMORY_BUDGET_BYTES: u64 = 1024 * 1024 * 1024;

/// Number of region files processed in parallel. Each task reserves twice the chunk limit (the
/// decompressed chunk plus its patched copy), so parallelism is bounded by memory as well as CPUs.
pub fn region_parallelism(
    memory_budget_bytes: u64,
    cpu_count: usize,
    chunk_limit_bytes: usize,
) -> usize {
    assert!(chunk_limit_bytes > 0, "chunk_limit_bytes must be positive");
    let per_task = 2 * chunk_limit_bytes as u64;
    let by_memory = (memory_budget_bytes / per_task).min(i32::MAX as u64) as usize;
    by_memory.min(cpu_count).max(1)
}

/// [`region_parallelism`] with the default budget and the machine's CPU count.
pub fn default_region_parallelism() -> usize {
    let cpus = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    region_parallelism(DEFAULT_MEMORY_BUDGET_BYTES, cpus, CHUNK_DECOMPRESSED_BYTES)
}
