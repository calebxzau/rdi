package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import java.util.Arrays

/**
 * One end's table of recent server-to-client packets for the reference extension.
 *
 * The server encoder and the client decoder each own an instance and apply the identical sequence of
 * plain packets and references to it, so both tables hold the same bytes in the same slots. Every rule
 * here belongs to protocol version 1: changing one desynchronizes peers that do not share the change.
 *
 * Not thread-safe; an instance lives on its connection's event loop.
 */
internal class PacketRefCache(
    val slots: Int,
    val maxEntryBytes: Int,
    private val hashFunction: (ByteArray, Int) -> Long = PacketRefFormat::hash,
) {
    /** One occupied slot, in least-recently-used order, for comparing two tables in tests. */
    data class Entry(val slot: Int, val content: List<Byte>)

    private val contents = arrayOfNulls<ByteArray>(slots)
    private val hashes = LongArray(slots)
    private val older = IntArray(slots) { NONE }
    private val newer = IntArray(slots) { NONE }
    private val bucketNext = IntArray(slots) { NONE }
    private val buckets = IntArray(bucketCount(slots)) { NONE }

    /** Server-only annotation: the legacy frame size an entry would cost; not part of the shared state. */
    private val baselines = IntArray(slots) { UNKNOWN_BASELINE }
    private val staging = ByteArray(maxEntryBytes)
    private var oldest = NONE
    private var newest = NONE
    private var used = 0

    init {
        require(PacketRefFormat.isSlotCountAccepted(slots)) { "Unsupported slot count $slots" }
        require(PacketRefFormat.isMaxEntryBytesAccepted(maxEntryBytes)) { "Unsupported entry limit $maxEntryBytes" }
    }

    fun accepts(length: Int): Boolean = length in PacketRefFormat.MINIMUM_ENTRY_BYTES..maxEntryBytes

    /**
     * Applies one plain packet. An exact match moves to newest; any other eligible packet takes the
     * lowest never-used slot, or else the least recently used slot.
     *
     * Returns the matched slot, an [isInserted] result carrying the new slot, or [SKIPPED].
     */
    fun record(content: ByteBuf): Int {
        val length = content.readableBytes()
        if (!accepts(length)) return SKIPPED
        content.getBytes(content.readerIndex(), staging, 0, length)
        val hash = hashFunction(staging, length)
        val match = find(hash, length)
        if (match != NONE) {
            touch(match)
            return match
        }
        return -1 - insert(hash, length)
    }

    /** Moves an occupied slot to newest, as a received reference does. */
    fun touch(slot: Int) {
        if (slot == newest) return
        unlink(slot)
        linkNewest(slot)
    }

    /** The bytes held in [slot], or null when the slot is out of range or still empty. */
    fun contentAt(slot: Int): ByteArray? = if (slot in 0 until slots) contents[slot] else null

    fun checkAt(slot: Int): Int = PacketRefFormat.check(hashes[slot])

    fun baselineAt(slot: Int): Int = baselines[slot]

    fun setBaseline(slot: Int, frameBytes: Int) {
        baselines[slot] = frameBytes
    }

    /** Forgets every cached legacy frame size, for example after the compression threshold changed. */
    fun clearBaselines() = baselines.fill(UNKNOWN_BASELINE)

    fun snapshot(): List<Entry> {
        val entries = ArrayList<Entry>(used)
        var slot = oldest
        while (slot != NONE) {
            entries += Entry(slot, contents[slot]!!.toList())
            slot = newer[slot]
        }
        return entries
    }

    private fun find(hash: Long, length: Int): Int {
        var slot = buckets[bucketOf(hash)]
        while (slot != NONE) {
            val content = contents[slot]!!
            if (hashes[slot] == hash && content.size == length &&
                Arrays.equals(content, 0, length, staging, 0, length)
            ) {
                return slot
            }
            slot = bucketNext[slot]
        }
        return NONE
    }

    private fun insert(hash: Long, length: Int): Int {
        val slot = if (used < slots) {
            used++
        } else {
            oldest.also { evict(it) }
        }
        contents[slot] = staging.copyOf(length)
        hashes[slot] = hash
        baselines[slot] = UNKNOWN_BASELINE
        linkNewest(slot)
        val bucket = bucketOf(hash)
        bucketNext[slot] = buckets[bucket]
        buckets[bucket] = slot
        return slot
    }

    private fun evict(slot: Int) {
        unlink(slot)
        val bucket = bucketOf(hashes[slot])
        if (buckets[bucket] == slot) {
            buckets[bucket] = bucketNext[slot]
        } else {
            var previous = buckets[bucket]
            while (bucketNext[previous] != slot) previous = bucketNext[previous]
            bucketNext[previous] = bucketNext[slot]
        }
        bucketNext[slot] = NONE
        contents[slot] = null
    }

    private fun unlink(slot: Int) {
        val before = older[slot]
        val after = newer[slot]
        if (before == NONE) oldest = after else newer[before] = after
        if (after == NONE) newest = before else older[after] = before
        older[slot] = NONE
        newer[slot] = NONE
    }

    private fun linkNewest(slot: Int) {
        older[slot] = newest
        newer[slot] = NONE
        if (newest == NONE) oldest = slot else newer[newest] = slot
        newest = slot
    }

    private fun bucketOf(hash: Long): Int = hash.toInt() and (buckets.size - 1)

    companion object {
        const val SKIPPED: Int = Int.MIN_VALUE
        const val UNKNOWN_BASELINE: Int = -1
        private const val NONE = -1

        fun isMatch(result: Int): Boolean = result >= 0

        fun isInserted(result: Int): Boolean = result < 0 && result != SKIPPED

        fun insertedSlot(result: Int): Int = -1 - result

        private fun bucketCount(slots: Int): Int {
            var count = 2
            while (count < slots * 2) count = count shl 1
            return count
        }
    }
}
