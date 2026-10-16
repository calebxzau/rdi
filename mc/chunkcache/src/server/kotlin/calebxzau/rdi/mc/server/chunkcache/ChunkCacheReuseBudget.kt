package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits

/**
 * Server-wide time for hashing candidates and encoding reuse metadata in one server tick; once it is spent, later
 * candidates in that tick get the full chunk. Each call passes its limit for the tick, so a warming-up session may
 * use more of the same budget ([ChunkCacheServerSession.reuseBudgetNanos]). Owned by the server thread.
 */
internal class ChunkCacheReuseBudget {
    private var tick = Long.MIN_VALUE
    private var usedNanos = 0L

    /** Moving average of one candidate's hash and metadata time, to plan how many reuses fit a tick's budget. */
    var averageReuseNanos = INITIAL_REUSE_NANOS
        private set

    fun available(tick: Long, limitNanos: Long = ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK): Boolean {
        start(tick)
        return usedNanos < limitNanos
    }

    /** How many more candidates this tick's budget probably covers up to [limitNanos]. */
    fun allowance(tick: Long, limitNanos: Long = ChunkCacheLimits.MAX_SERVER_REUSE_NANOS_PER_TICK): Int {
        start(tick)
        val remaining = limitNanos - usedNanos
        if (remaining <= 0) return 0
        return (remaining / averageReuseNanos).toInt().coerceAtLeast(1)
    }

    /** Charges [nanos] to the tick last passed to [available] or [allowance]. */
    fun spend(nanos: Long) {
        usedNanos += nanos
    }

    fun recordReuse(nanos: Long) {
        averageReuseNanos = (averageReuseNanos * 7 + nanos.coerceAtLeast(MIN_REUSE_NANOS)) / 8
    }

    fun clear() {
        tick = Long.MIN_VALUE
        usedNanos = 0
    }

    private fun start(tick: Long) {
        if (this.tick != tick) {
            this.tick = tick
            usedNanos = 0
        }
    }

    companion object {
        const val INITIAL_REUSE_NANOS = 300_000L
        const val MIN_REUSE_NANOS = 20_000L
    }
}
