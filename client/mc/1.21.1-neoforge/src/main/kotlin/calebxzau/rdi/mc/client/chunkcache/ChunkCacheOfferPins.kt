package calebxzau.rdi.mc.client.chunkcache

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap

/** Game-thread-owned offers. Cancellation keeps the exact snapshot pinned until the server's fence. */
class ChunkCacheOfferPins<T>(private val maxEntries: Int, private val maxBytes: Long) {
    data class Pin<T>(
        val id: Long,
        val position: Long,
        val hash: ByteArray,
        val value: T,
        val bytes: Long,
        val offeredAt: Long,
        var cancelling: Boolean = false,
    )

    private val pins = Long2ObjectLinkedOpenHashMap<Pin<T>>()
    private val positions = Long2LongOpenHashMap()
    private var nextId = 1L
    var bytes: Long = 0
        private set
    val size: Int get() = pins.size

    init { require(maxEntries > 0 && maxBytes > 0) }

    fun add(position: Long, hash: ByteArray, value: T, bytes: Long, now: Long): Pin<T>? {
        require(hash.size == 20 && bytes > 0)
        if (pins.size >= maxEntries || bytes > maxBytes - this.bytes || positions.containsKey(position)) return null
        check(nextId > 0) { "Chunk offer sequence exhausted" }
        val pin = Pin(nextId++, position, hash.copyOf(), value, bytes, now)
        pins.put(pin.id, pin)
        positions.put(position, pin.id)
        this.bytes += bytes
        return pin
    }

    fun get(id: Long): Pin<T>? = pins.get(id)
    fun contains(position: Long): Boolean = positions.containsKey(position)

    fun retire(id: Long): Pin<T>? {
        val pin = pins.remove(id) ?: return null
        positions.remove(pin.position)
        bytes -= pin.bytes
        return pin
    }

    fun cancelWhere(predicate: (Pin<T>) -> Boolean): List<Long> = pins.values.filter {
        !it.cancelling && predicate(it)
    }.map { it.cancelling = true; it.id }

    fun clear() {
        pins.clear()
        positions.clear()
        bytes = 0
    }
}
