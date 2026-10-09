package calebxzau.rdi.mc.client.chunkcache

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
    private const val MAX_SCAN_RADIUS = 34

    fun viewDistance(effectiveRenderDistance: Int): Int = effectiveRenderDistance.coerceIn(2, 32)

    fun admits(x: Int, z: Int, centerX: Int, centerZ: Int, viewDistance: Int): Boolean =
        ChunkTrackingView.isWithinDistance(centerX, centerZ, viewDistance + PREFETCH_MARGIN, x, z, true)

    /**
     * Square half-width containing every admitted chunk: the predicate reaches one chunk past its distance on the
     * axes. At view distance 32 the cap drops only the outermost axis tips of the prefetch ring.
     */
    fun scanRadius(viewDistance: Int): Int = (viewDistance + PREFETCH_MARGIN + 1).coerceAtMost(MAX_SCAN_RADIUS)
}
