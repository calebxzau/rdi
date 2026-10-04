package calebxzau.rdi.mc.server.chunkcache

/**
 * One section's terrain hash, valid only for the exact block and biome containers and the mutation
 * stamps it was computed from. Memory-only; it is never written to chunk NBT.
 */
class SectionHashCache {
    private var hash: ByteArray? = null
    private var states: Any? = null
    private var statesStamp = 0
    private var biomes: Any? = null
    private var biomesStamp = 0

    fun get(states: Any, statesStamp: Int, biomes: Any, biomesStamp: Int): ByteArray? {
        val cached = hash ?: return null
        return if (this.states === states && this.statesStamp == statesStamp &&
            this.biomes === biomes && this.biomesStamp == biomesStamp) cached else null
    }

    /** Stamps must be read before hashing, so a concurrent mutation always invalidates the result. */
    fun store(states: Any, statesStamp: Int, biomes: Any, biomesStamp: Int, hash: ByteArray) {
        this.states = states
        this.statesStamp = statesStamp
        this.biomes = biomes
        this.biomesStamp = biomesStamp
        this.hash = hash
    }
}
