package calebxzau.rdi.anvilrw.remap

/**
 * Resource limits for save import processing. Every decompression in this package is bounded by one
 * of these values, so a small compressed input can never expand without limit.
 */
object RemapLimits {
    /** Decompressed size of one region chunk, inline or external. */
    const val CHUNK_DECOMPRESSED_BYTES: Int = 128 * 1024 * 1024

    /** Compressed size of one external chunk file (`c.<x>.<z>.mcc`). */
    const val MCC_COMPRESSED_BYTES: Int = 128 * 1024 * 1024

    /** Decompressed size of `level.dat`, `level.dat_old` and playerdata files. */
    const val STRICT_NBT_FILE_BYTES: Int = 32 * 1024 * 1024

    /** Decompressed size of any other gzip or raw NBT file. */
    const val OTHER_NBT_FILE_BYTES: Int = 128 * 1024 * 1024

    /** Size of one text file handled by the text rewriter. */
    const val TEXT_FILE_BYTES: Int = 64 * 1024 * 1024

    /** Maximum NBT nesting depth, the same as Minecraft's `CompoundTag`/`ListTag` readers. */
    const val NBT_MAX_DEPTH: Int = 512

    /** Memory that parallel region processing may reserve in total. */
    fun memoryBudget(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long = maxHeapBytes / 3

    /**
     * Number of region files processed in parallel. Each task reserves twice the chunk limit (the
     * decompressed chunk plus its patched copy), so parallelism is bounded by memory as well as CPUs.
     */
    fun regionParallelism(
        memoryBudgetBytes: Long = memoryBudget(),
        cpuCount: Int = Runtime.getRuntime().availableProcessors(),
        chunkLimitBytes: Int = CHUNK_DECOMPRESSED_BYTES,
    ): Int {
        require(chunkLimitBytes > 0) { "chunkLimitBytes must be positive" }
        val perTask = 2L * chunkLimitBytes
        val byMemory = (memoryBudgetBytes / perTask).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return minOf(byMemory, cpuCount).coerceAtLeast(1)
    }
}
