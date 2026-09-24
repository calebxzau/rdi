package calebxzau.rdi.mc.client.dm

/**
 * Central scheduling and timeout constants for production world synchronization.
 *
 * The 30-second period is inherited from the previous column-only implementation, not a
 * measured target. Every timeout here only ends the synchronization job; none of them stop
 * Minecraft's or NeoForge's own save threads.
 */
object DmWorldSyncTiming {
    const val DEFAULT_INTERVAL_SECONDS: Long = 30L
    const val NANOS_PER_SECOND: Long = 1_000_000_000L
    const val DEFAULT_INTERVAL_NANOS: Long = DEFAULT_INTERVAL_SECONDS * NANOS_PER_SECOND

    /** How long a cycle may wait across ticks for columns to leave a transition state. */
    const val READINESS_NANOS: Long = 5L * 1_000_000_000L

    /** Bound on waiting for the NeoForge save tasks captured for this cycle. */
    const val SAVE_WAIT_NANOS: Long = 30L * 1_000_000_000L

    /** Bound on the whole background WorldData scan, copy, and digest pass. */
    const val WORLD_CAPTURE_NANOS: Long = 60L * 1_000_000_000L

    /** Bound on staging captured column NBT. */
    const val CHUNK_STAGE_NANOS: Long = 30L * 1_000_000_000L

    const val UPLOAD_TIMEOUT_SECONDS: Long = 120L

    /** Retries of one already built candidate. A retry never rebuilds or renumbers it. */
    const val MAX_UPLOAD_ATTEMPTS: Int = 3

    /** Re-copy attempts for a single file that keeps changing underneath the copy. */
    const val MAX_FILE_COPY_ATTEMPTS: Int = 3

    /** Full WorldData re-scans allowed when the file set changes during a capture. */
    const val MAX_WORLD_CAPTURE_ATTEMPTS: Int = 2

    fun intervalNanos(seconds: Long): Long = Math.multiplyExact(seconds, NANOS_PER_SECOND)

    fun firstDue(
        readyAtNanos: Long,
        intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
    ): Long = Math.addExact(readyAtNanos, intervalNanos)

    fun nextDue(
        previousDueNanos: Long,
        completedAtNanos: Long,
        intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
    ): Long = maxOf(previousDueNanos, Math.addExact(completedAtNanos, intervalNanos))

    fun nextSequence(acknowledged: Long?): Long = when {
        acknowledged == null -> 1L
        acknowledged < 0L -> error("世界同步序号不能为负")
        else -> Math.addExact(acknowledged, 1L)
    }
}
