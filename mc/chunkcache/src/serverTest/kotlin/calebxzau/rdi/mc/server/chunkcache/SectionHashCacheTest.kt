package calebxzau.rdi.mc.server.chunkcache

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SectionHashCacheTest {
    private val states = Any()
    private val biomes = Any()
    private val hash = ByteArray(20) { it.toByte() }

    @Test
    fun returnsHashOnlyForTheExactContainersAndStamps() {
        val cache = SectionHashCache()
        assertNull(cache.get(states, 0, biomes, 0))

        cache.store(states, 4, biomes, 7, hash)

        assertContentEquals(hash, cache.get(states, 4, biomes, 7))
        assertNull(cache.get(states, 5, biomes, 7), "A block write must invalidate the hash")
        assertNull(cache.get(states, 4, biomes, 8), "A biome write must invalidate the hash")
        assertNull(cache.get(Any(), 4, biomes, 7), "A replaced block container must invalidate the hash")
        assertNull(cache.get(states, 4, Any(), 7), "A replaced biome container (e.g. /fillbiome) must invalidate the hash")
    }

    @Test
    fun storingReplacesThePreviousEntry() {
        val cache = SectionHashCache()
        val newer = ByteArray(20) { 9 }
        val newBiomes = Any()
        cache.store(states, 1, biomes, 1, hash)
        cache.store(states, 2, newBiomes, 0, newer)

        assertNull(cache.get(states, 1, biomes, 1))
        assertContentEquals(newer, cache.get(states, 2, newBiomes, 0))
    }
}
