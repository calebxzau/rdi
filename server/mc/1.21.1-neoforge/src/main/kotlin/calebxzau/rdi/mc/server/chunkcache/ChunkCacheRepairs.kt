package calebxzau.rdi.mc.server.chunkcache

/** An issued repair keeps its result fence pending until full transmission or explicit abandonment. */
internal class ChunkCacheRepairs {
    private val pending = LinkedHashMap<Long, ChunkCacheOfferLedger.Token>()

    fun add(token: ChunkCacheOfferLedger.Token) { pending[token.id] = token }
    fun contains(id: Long): Boolean = id in pending
    fun find(x: Int, z: Int): ChunkCacheOfferLedger.Token? = pending.values.firstOrNull { it.x == x && it.z == z }
    fun remove(id: Long): ChunkCacheOfferLedger.Token? = pending.remove(id)
    fun completeFull(x: Int, z: Int): ChunkCacheOfferLedger.Token? = find(x, z)?.let { pending.remove(it.id) }
    fun outside(allowed: (Int, Int) -> Boolean): List<ChunkCacheOfferLedger.Token> =
        pending.values.filterNot { allowed(it.x, it.z) }
}
