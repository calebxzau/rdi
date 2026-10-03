package calebxzau.rdi.mc.chunkcache

/** Shared limits for client offers and server-side pending cache candidates. */
object ChunkCacheLimits {
    const val MAX_OFFERS = 128
    const val MAX_OFFER_BATCH = 64
    const val MAX_PREPARED_BYTES = 64 * 1024 * 1024
    const val MAX_SECTION_BYTES = 2 * 1024 * 1024
    const val MAX_METADATA_BYTES = 900 * 1024
    const val OFFER_TTL_MILLIS = 10_000L
    /** Server-wide hashing/metadata time per tick; later candidates in that tick get the full chunk. */
    const val MAX_SERVER_REUSE_NANOS_PER_TICK = 5_000_000L
}
