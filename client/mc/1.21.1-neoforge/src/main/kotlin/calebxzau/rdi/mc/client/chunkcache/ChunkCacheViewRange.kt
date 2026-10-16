package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import net.minecraft.server.level.ChunkTrackingView

/**
 * Candidate range for NeoForge 1.21.1.
 *
 * A 1.21 server tracks `clamp(client render distance, 2, server view distance)`, which is the client's effective
 * render distance, and admits offers with `ChunkTrackingView.isWithinDistance(view distance + 2, outer = true)`.
 * Candidates use that same round predicate, so square corners are no longer prepared only to be rejected.
 */
internal object ChunkCacheViewRange {
    /** Matches the server's prefetch margin. */
    const val PREFETCH_MARGIN = 2

    fun viewDistance(effectiveRenderDistance: Int): Int = effectiveRenderDistance.coerceIn(2, 32)

    fun admits(x: Int, z: Int, centerX: Int, centerZ: Int, viewDistance: Int): Boolean =
        ChunkTrackingView.isWithinDistance(centerX, centerZ, viewDistance + PREFETCH_MARGIN, x, z, true)

    /**
     * Square half-width containing every admitted chunk: the predicate reaches one chunk past its distance on the
     * axes. The cap covers the whole prefetch ring up to view distance 32.
     */
    fun scanRadius(viewDistance: Int): Int = (viewDistance + PREFETCH_MARGIN + 1).coerceAtMost(ChunkCacheLimits.MAX_SCAN_RADIUS)
}
