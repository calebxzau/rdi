package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOffer

/** Pure bounded offer and reuse-token lifecycle, owned by the server thread. */
internal class ChunkCacheOfferLedger {
    internal data class Token(
        val id: Long,
        val x: Int,
        val z: Int,
        val hash: ByteArray,
        val expiresAt: Long,
    )

    internal data class Admission(val accepted: Boolean, val retireIds: List<Long>)
    internal data class Expired(val offers: List<Token>, val inFlight: List<Token>)
    internal data class Resolution(val token: Token, val success: Boolean)

    private data class Position(val x: Int, val z: Int)

    private val offers = LinkedHashMap<Long, Token>()
    private val inFlight = LinkedHashMap<Long, Token>()
    private var lastOfferedId = 0L

    fun admit(entries: List<ChunkCacheOffer>, expiresAt: Long, allowed: (Int, Int) -> Boolean): Admission {
        val ids = entries.map { it.id }.distinct()
        val maxId = entries.maxOfOrNull { it.id } ?: lastOfferedId
        val fresh = entries.all { it.id > lastOfferedId }
        val positions = HashSet<Position>()
        val coordinatesAvailable = entries.all { entry ->
            positions.add(Position(entry.x, entry.z)) &&
                allowed(entry.x, entry.z) &&
                offers.values.none { it.x == entry.x && it.z == entry.z } &&
                inFlight.values.none { it.x == entry.x && it.z == entry.z }
        }
        val valid = entries.isNotEmpty() && entries.size <= ChunkCacheLimits.MAX_OFFER_BATCH &&
            ids.size == entries.size && offers.size + inFlight.size + entries.size <= ChunkCacheLimits.MAX_OFFERS && fresh && coordinatesAvailable &&
            entries.all { it.hash.size == 20 && it.id > 0 && it.id !in offers && it.id !in inFlight }
        lastOfferedId = maxOf(lastOfferedId, maxId)
        if (!valid) {
            ids.forEach(offers::remove)
            return Admission(false, ids)
        }
        entries.forEach { entry ->
            offers[entry.id] = Token(entry.id, entry.x, entry.z, entry.hash.copyOf(), expiresAt)
        }
        return Admission(true, emptyList())
    }

    fun rejectIds(ids: List<Long>) {
        lastOfferedId = maxOf(lastOfferedId, ids.maxOrNull() ?: lastOfferedId)
        ids.distinct().forEach(offers::remove)
    }

    fun consume(x: Int, z: Int): Token? {
        val entry = offers.values.firstOrNull { it.x == x && it.z == z } ?: return null
        return offers.remove(entry.id)
    }

    fun trackReuse(token: Token, expiresAt: Long): Boolean {
        if (inFlight.size >= ChunkCacheLimits.MAX_OFFERS || token.id in inFlight) return false
        inFlight[token.id] = token.copy(hash = token.hash.copyOf(), expiresAt = expiresAt)
        return true
    }

    fun resolve(id: Long, x: Int, z: Int, hash: ByteArray, success: Boolean): Resolution? {
        val token = inFlight[id] ?: return null
        if (token.x != x || token.z != z || !token.hash.contentEquals(hash)) return null
        inFlight.remove(id)
        return Resolution(token, success)
    }

    fun expire(now: Long): Expired {
        val expiredOffers = offers.values.filter { it.expiresAt <= now }
        val expiredFlights = inFlight.values.filter { it.expiresAt <= now }
        expiredOffers.forEach { offers.remove(it.id) }
        expiredFlights.forEach { inFlight.remove(it.id) }
        return Expired(expiredOffers, expiredFlights)
    }

    fun retireOffersOutside(allowed: (Int, Int) -> Boolean): List<Long> {
        val removed = offers.values.filterNot { allowed(it.x, it.z) }
        removed.forEach { offers.remove(it.id) }
        return removed.map { it.id }
    }

    fun retireInFlightOutside(allowed: (Int, Int) -> Boolean): List<Token> {
        val removed = inFlight.values.filterNot { allowed(it.x, it.z) }
        removed.forEach { inFlight.remove(it.id) }
        return removed
    }

    fun cancelOfferIds(ids: List<Long>): List<Long> {
        ids.distinct().forEach(offers::remove)
        return ids.distinct()
    }

    fun retireOffer(id: Long): Token? = offers.remove(id)
    fun inFlightToken(id: Long): Token? = inFlight[id]
    fun offerCount(): Int = offers.size
    fun inFlightCount(): Int = inFlight.size
}
