package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap

/**
 * Pure bounded offer and reuse-token lifecycle, owned by the server thread. [capacity] bounds offers plus in-flight
 * tokens; positions are indexed because [consume] runs for every chunk the server sends.
 */
internal class ChunkCacheOfferLedger(private val capacity: Int = ChunkCacheLimits.LEGACY_MAX_OFFERS) {
    internal data class Token(
        val id: Long,
        val x: Int,
        val z: Int,
        val hash: ByteArray,
        val expiresAt: Long,
    ) {
        val position: Long get() = packedPosition(x, z)
    }

    /** Why an offer was refused; logged per metrics window. */
    internal enum class RejectReason {
        /** Outside the admission range the version passed. */
        Range,
        /** The session already holds [capacity] offers and reuses. */
        Capacity,
        /** The position already has an offer or a reuse in flight, or appears twice in the batch. */
        Duplicate,
        /** Malformed: oversized batch, repeated or stale id, or a bad hash. */
        Invalid,
        /** Over the session's offers per tick. */
        Rate,
    }

    internal data class Rejection(val id: Long, val x: Int, val z: Int, val reason: RejectReason)

    /** Offers are admitted one by one; [rejected] lists the refused ones, which the client must be told to retire. */
    internal data class Admission(val rejected: List<Rejection>) {
        val accepted: Boolean get() = rejected.isEmpty()
        val retireIds: List<Long> get() = rejected.map { it.id }.distinct()
    }
    internal data class Expired(val offers: List<Token>, val inFlight: List<Token>)
    internal data class Resolution(val token: Token, val success: Boolean)

    private val offers = LinkedHashMap<Long, Token>()
    private val inFlight = LinkedHashMap<Long, Token>()
    /** Position to id; a position has at most one offer and one in-flight token, never both. */
    private val offerAt = Long2LongOpenHashMap().apply { defaultReturnValue(NO_ID) }
    private val inFlightAt = Long2LongOpenHashMap().apply { defaultReturnValue(NO_ID) }
    private var lastOfferedId = 0L

    init {
        require(capacity in 1..ChunkCacheLimits.MAX_OFFERS)
    }

    /**
     * Admits each offer on its own, in order; one bad entry no longer costs the whole batch, which during warm-up
     * left its chunks held until the warm-up ended. A refused id that matches an existing offer drops that offer too,
     * since the client is told to retire it.
     */
    fun admit(entries: List<ChunkCacheOffer>, expiresAt: Long, allowed: (Int, Int) -> Boolean): Admission {
        if (entries.isEmpty()) return Admission(emptyList())
        val previousId = lastOfferedId
        lastOfferedId = maxOf(lastOfferedId, entries.maxOf { it.id })
        val rejected = ArrayList<Rejection>()
        fun reject(entry: ChunkCacheOffer, reason: RejectReason) {
            removeOffer(entry.id)
            rejected += Rejection(entry.id, entry.x, entry.z, reason)
        }
        if (entries.size > ChunkCacheLimits.MAX_OFFER_BATCH) {
            entries.forEach { reject(it, RejectReason.Invalid) }
            return Admission(rejected)
        }
        val idCounts = entries.groupingBy { it.id }.eachCount()
        val batchPositions = HashSet<Long>()
        for (entry in entries) {
            val position = packedPosition(entry.x, entry.z)
            val reason = when {
                idCounts.getValue(entry.id) > 1 || entry.id <= previousId || entry.id in offers || entry.id in inFlight ||
                    entry.hash.size != 20 -> RejectReason.Invalid
                !batchPositions.add(position) || offerAt.containsKey(position) || inFlightAt.containsKey(position) ->
                    RejectReason.Duplicate
                !allowed(entry.x, entry.z) -> RejectReason.Range
                offers.size + inFlight.size >= capacity -> RejectReason.Capacity
                else -> null
            }
            if (reason != null) {
                reject(entry, reason)
                continue
            }
            val token = Token(entry.id, entry.x, entry.z, entry.hash.copyOf(), expiresAt)
            offers[entry.id] = token
            offerAt.put(token.position, entry.id)
        }
        return Admission(rejected)
    }

    fun rejectIds(ids: List<Long>) {
        lastOfferedId = maxOf(lastOfferedId, ids.maxOrNull() ?: lastOfferedId)
        ids.distinct().forEach(::removeOffer)
    }

    fun hasOffer(x: Int, z: Int): Boolean = offerAt.containsKey(packedPosition(x, z))

    fun consume(x: Int, z: Int): Token? {
        val id = offerAt.get(packedPosition(x, z))
        return if (id == NO_ID) null else removeOffer(id)
    }

    fun trackReuse(token: Token, expiresAt: Long): Boolean {
        if (inFlight.size >= capacity || token.id in inFlight || inFlightAt.containsKey(token.position)) return false
        inFlight[token.id] = token.copy(hash = token.hash.copyOf(), expiresAt = expiresAt)
        inFlightAt.put(token.position, token.id)
        return true
    }

    fun resolve(id: Long, x: Int, z: Int, hash: ByteArray, success: Boolean): Resolution? {
        val token = inFlight[id] ?: return null
        if (token.x != x || token.z != z || !token.hash.contentEquals(hash)) return null
        removeInFlight(id)
        return Resolution(token, success)
    }

    fun expire(now: Long): Expired {
        val expiredOffers = offers.values.filter { it.expiresAt <= now }
        val expiredFlights = inFlight.values.filter { it.expiresAt <= now }
        expiredOffers.forEach { removeOffer(it.id) }
        expiredFlights.forEach { removeInFlight(it.id) }
        return Expired(expiredOffers, expiredFlights)
    }

    fun retireOffersOutside(allowed: (Int, Int) -> Boolean): List<Long> {
        val removed = offers.values.filterNot { allowed(it.x, it.z) }
        removed.forEach { removeOffer(it.id) }
        return removed.map { it.id }
    }

    fun retireInFlightOutside(allowed: (Int, Int) -> Boolean): List<Token> {
        val removed = inFlight.values.filterNot { allowed(it.x, it.z) }
        removed.forEach { removeInFlight(it.id) }
        return removed
    }

    fun cancelOfferIds(ids: List<Long>): List<Long> {
        ids.distinct().forEach(::removeOffer)
        return ids.distinct()
    }

    fun retireOffer(id: Long): Token? = removeOffer(id)
    fun inFlightToken(id: Long): Token? = inFlight[id]
    fun offerCount(): Int = offers.size
    fun inFlightCount(): Int = inFlight.size

    private fun removeOffer(id: Long): Token? {
        val token = offers.remove(id) ?: return null
        if (offerAt.get(token.position) == id) offerAt.remove(token.position)
        return token
    }

    private fun removeInFlight(id: Long): Token? {
        val token = inFlight.remove(id) ?: return null
        if (inFlightAt.get(token.position) == id) inFlightAt.remove(token.position)
        return token
    }

    private companion object {
        const val NO_ID = 0L

        /** Same packing as `ChunkPos.asLong`, without loading Minecraft classes in tests. */
        fun packedPosition(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)
    }
}
