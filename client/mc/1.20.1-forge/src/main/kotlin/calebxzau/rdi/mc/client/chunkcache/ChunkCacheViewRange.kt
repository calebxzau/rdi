package calebxzau.rdi.mc.client.chunkcache

/**
 * Candidate range for Forge 1.20.1.
 *
 * A 1.20.1 server sends every chunk within its own view distance whatever the client's render distance is, and
 * admits offers with `ChunkMap.isChunkInRange(view distance + 2)`. Candidates therefore follow the server's view
 * distance and the same predicate, so the ring that enters view on the next step is prepared before it is sent.
 */
internal object ChunkCacheViewRange {
    /** Matches the server's `PREFETCH_MARGIN`. */
    const val PREFETCH_MARGIN = 2
    private const val MAX_SCAN_RADIUS = 34

    fun viewDistance(serverChunkRadius: Int): Int = serverChunkRadius.coerceIn(2, 32)

    fun admits(x: Int, z: Int, centerX: Int, centerZ: Int, viewDistance: Int): Boolean =
        isChunkInRange(x, z, centerX, centerZ, viewDistance + PREFETCH_MARGIN)

    /**
     * Copy of 1.20.1 `ChunkMap.isChunkInRange`. Calling it would initialize `ChunkMap`, whose static fields read
     * chunk status registries.
     */
    fun isChunkInRange(x: Int, z: Int, centerX: Int, centerZ: Int, maxDistance: Int): Boolean {
        val dx = maxOf(0, kotlin.math.abs(x - centerX) - 1)
        val dz = maxOf(0, kotlin.math.abs(z - centerZ) - 1)
        val far = maxOf(0, maxOf(dx, dz) - 1).toLong()
        val near = minOf(dx, dz).toLong()
        return near * near + far * far < maxDistance.toLong() * maxDistance
    }

    /**
     * Square half-width containing every admitted chunk: the predicate reaches one chunk past its distance on the
     * axes. At view distance 32 the cap drops only the outermost axis tips of the prefetch ring.
     */
    fun scanRadius(viewDistance: Int): Int = (viewDistance + PREFETCH_MARGIN + 1).coerceAtMost(MAX_SCAN_RADIUS)
}
