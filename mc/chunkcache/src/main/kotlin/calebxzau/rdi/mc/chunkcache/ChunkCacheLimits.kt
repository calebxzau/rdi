package calebxzau.rdi.mc.chunkcache

/** Shared limits for client offers and server-side pending cache candidates. */
object ChunkCacheLimits {
    /** Candidates a peer may hold when the other side did not announce warm-up support (protocol before warm-up). */
    const val LEGACY_MAX_OFFERS = 128
    /** Candidates per session once warm-up is negotiated; covers the prefetch ring at view distance 32. */
    const val MAX_OFFERS = 4096
    /** Wire limit of one retire or cancel list; longer lists are split, so older peers still decode them. */
    const val MAX_IDS_PER_MESSAGE = 128
    /** Offers in one payload. */
    const val MAX_OFFER_BATCH = 64
    /** Offers per server tick when warm-up is not negotiated: older servers accept one payload per tick. */
    const val LEGACY_MAX_OFFERS_PER_TICK = MAX_OFFER_BATCH
    const val MAX_OFFERS_PER_TICK = 256
    /**
     * Ticks of unused offer allowance the server keeps. Client and server ticks drift, so two client ticks' offers
     * can land in one server tick; the allowance still averages the per-tick rate.
     */
    const val OFFER_BURST_TICKS = 4
    /** Positions in one "no cached terrain" report. */
    const val MAX_ABSENT_BATCH = 256
    const val MAX_PREPARED_BYTES = 128 * 1024 * 1024
    const val MAX_SECTION_BYTES = 2 * 1024 * 1024
    const val MAX_METADATA_BYTES = 900 * 1024
    const val OFFER_TTL_MILLIS = 60_000L
    /** Server-wide hashing/metadata time per tick; later candidates in that tick get the full chunk. */
    const val MAX_SERVER_REUSE_NANOS_PER_TICK = 10_000_000L
    /**
     * The same budget while the calling session warms up: its held chunks wait for reuses, and a tick's fixed work
     * measured about 22ms on a 1.20.1 server, so 20ms more still fits a 50ms tick.
     */
    const val WARMUP_MAX_SERVER_REUSE_NANOS_PER_TICK = 20_000_000L
    /** How long a new session holds chunks back until the client has offered or declined them. */
    const val WARMUP_MILLIS = 8_000L
    /** Half-width of the client's candidate scan square: view distance 32 plus the prefetch margin 2, plus 1. */
    const val MAX_SCAN_RADIUS = 35
    /** Chunks within this Chebyshev distance of the player are never held, so the player has ground at once. */
    const val WARMUP_IMMEDIATE_RADIUS = 1
}
