package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import it.unimi.dsi.fastutil.longs.LongOpenHashSet

/**
 * Decides which pending chunks a new cache session may send while the client is still preparing candidates.
 * Chunks next to the player always go first; others wait until the client offered them (reuse) or reported that
 * it has no cache (full send), or until the warm-up ends. Pure and owned by the server thread.
 */
class ChunkCacheWarmup(
    val startedAt: Long,
    durationMillis: Long,
    private val immediateRadius: Int = ChunkCacheLimits.WARMUP_IMMEDIATE_RADIUS,
) {
    enum class Release { Held, Immediate, Offered, Absent }

    data class Summary(
        val durationMillis: Long,
        val immediate: Int,
        val offered: Int,
        val absent: Int,
        val deferredForBudget: Int,
        /** Chunks still held when the warm-up ended; they are then sent the ordinary way. */
        val heldAtEnd: Int,
    )

    val endsAt: Long = startedAt + durationMillis
    private val absent = LongOpenHashSet()
    private var immediate = 0
    private var offered = 0
    private var absentSent = 0
    private var deferredForBudget = 0

    fun active(now: Long): Boolean = now < endsAt

    /** Positions the client will not offer; bounded so a misbehaving client cannot grow it without limit. */
    fun markAbsent(positions: Iterable<Long>) {
        for (position in positions) {
            if (absent.size >= MAX_TRACKED_ABSENT) return
            absent.add(position)
        }
    }

    fun classify(position: Long, centerX: Int, centerZ: Int, hasOffer: Boolean): Release {
        val dx = Math.abs(chunkX(position) - centerX)
        val dz = Math.abs(chunkZ(position) - centerZ)
        return when {
            maxOf(dx, dz) <= immediateRadius -> Release.Immediate
            hasOffer -> Release.Offered
            position in absent -> Release.Absent
            else -> Release.Held
        }
    }

    /**
     * Picks at most [quota] of [pending] to send now, nearest first. Offered chunks beyond [offerAllowance] stay
     * pending for a later tick: once the server's reuse budget is spent they would otherwise be sent in full.
     * [ready] filters chunks the server cannot send yet; like vanilla, those stay pending.
     */
    fun select(
        pending: Iterable<Long>,
        quota: Int,
        centerX: Int,
        centerZ: Int,
        offerAllowance: Int,
        hasOffer: (Long) -> Boolean,
        ready: (Long) -> Boolean,
    ): List<Long> {
        if (quota <= 0) return emptyList()
        val releasable = ArrayList<Pair<Long, Release>>()
        for (position in pending) {
            val release = classify(position, centerX, centerZ, hasOffer(position))
            if (release != Release.Held) releasable += position to release
        }
        releasable.sortBy { (position, _) -> distanceSquared(position, centerX, centerZ) }
        val selected = ArrayList<Long>(minOf(quota, releasable.size))
        var offersLeft = offerAllowance
        for ((position, release) in releasable) {
            if (selected.size >= quota) break
            if (release == Release.Offered && offersLeft <= 0) {
                deferredForBudget++
                continue
            }
            if (!ready(position)) continue
            if (release == Release.Offered) offersLeft--
            when (release) {
                Release.Immediate -> immediate++
                Release.Offered -> offered++
                Release.Absent -> absentSent++
                Release.Held -> Unit
            }
            selected += position
        }
        return selected
    }

    fun summary(now: Long, heldAtEnd: Int): Summary =
        Summary(now - startedAt, immediate, offered, absentSent, deferredForBudget, heldAtEnd)

    companion object {
        const val MAX_TRACKED_ABSENT = 16_384

        /** Warm-up length: the `rdi.chunkCache.warmupMillis` system property when set, at most [maxMillis]. */
        fun configuredMillis(maxMillis: Long): Long =
            (System.getProperty("rdi.chunkCache.warmupMillis")?.toLongOrNull() ?: ChunkCacheLimits.WARMUP_MILLIS).coerceIn(0L, maxMillis)

        fun chunkX(position: Long): Int = position.toInt()
        fun chunkZ(position: Long): Int = (position ushr 32).toInt()

        private fun distanceSquared(position: Long, centerX: Int, centerZ: Int): Long {
            val dx = (chunkX(position) - centerX).toLong()
            val dz = (chunkZ(position) - centerZ).toLong()
            return dx * dx + dz * dz
        }
    }
}
