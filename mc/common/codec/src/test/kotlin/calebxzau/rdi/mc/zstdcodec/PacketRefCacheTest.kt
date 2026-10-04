package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketRefCacheTest {
    @Test
    fun `new packets take the lowest unused slot then evict the least recently used`() {
        val cache = PacketRefCache(3, 64)
        val (a, b, c, d) = listOf(packet(1), packet(2), packet(3), packet(4))

        assertEquals(0, insertedSlot(cache, a))
        assertEquals(1, insertedSlot(cache, b))
        assertEquals(2, insertedSlot(cache, c))
        assertEquals(0, cache.record(Unpooled.wrappedBuffer(a)))
        assertEquals(listOf(1, 2, 0), cache.snapshot().map { it.slot })

        assertEquals(1, insertedSlot(cache, d))
        assertEquals(listOf(2, 0, 1), cache.snapshot().map { it.slot })
        assertContentEquals(d, cache.contentAt(1))
        // b was evicted, so it comes back as a new entry in the then least recently used slot.
        assertEquals(2, insertedSlot(cache, b))
    }

    @Test
    fun `an identical plain packet only moves its entry to newest`() {
        val cache = PacketRefCache(4, 64)
        val a = packet(1)
        insertedSlot(cache, a)
        insertedSlot(cache, packet(2))

        val result = cache.record(Unpooled.wrappedBuffer(a.copyOf()))

        assertTrue(PacketRefCache.isMatch(result))
        assertEquals(0, result)
        assertEquals(listOf(1, 0), cache.snapshot().map { it.slot })
        assertEquals(2, cache.snapshot().size)
    }

    @Test
    fun `touch moves a slot to newest like a received reference`() {
        val cache = PacketRefCache(3, 64)
        insertedSlot(cache, packet(1))
        insertedSlot(cache, packet(2))
        insertedSlot(cache, packet(3))

        cache.touch(0)
        cache.touch(2)

        assertEquals(listOf(1, 0, 2), cache.snapshot().map { it.slot })
    }

    @Test
    fun `entry size bounds are inclusive and others are skipped`() {
        val cache = PacketRefCache(8, 32)

        assertEquals(PacketRefCache.SKIPPED, cache.record(Unpooled.wrappedBuffer(ByteArray(0))))
        assertEquals(PacketRefCache.SKIPPED, cache.record(Unpooled.wrappedBuffer(ByteArray(7))))
        assertTrue(PacketRefCache.isInserted(cache.record(Unpooled.wrappedBuffer(ByteArray(8)))))
        assertTrue(PacketRefCache.isInserted(cache.record(Unpooled.wrappedBuffer(ByteArray(32) { 1 }))))
        assertEquals(PacketRefCache.SKIPPED, cache.record(Unpooled.wrappedBuffer(ByteArray(33))))
        assertEquals(2, cache.snapshot().size)
    }

    @Test
    fun `record reads without consuming the buffer`() {
        val cache = PacketRefCache(2, 64)
        val buffer = Unpooled.wrappedBuffer(packet(5))
        buffer.readByte()

        cache.record(buffer)

        assertEquals(1, buffer.readerIndex())
        assertContentEquals(packet(5).copyOfRange(1, 16), cache.contentAt(0))
    }

    @Test
    fun `colliding hashes never match different bytes and stay deterministic`() {
        val first = PacketRefCache(2, 64) { _, _ -> 42L }
        val second = PacketRefCache(2, 64) { _, _ -> 42L }
        val sequence = listOf(packet(1), packet(2), packet(1), packet(3), packet(2), packet(3))

        val firstResults = sequence.map { first.record(Unpooled.wrappedBuffer(it)) }
        val secondResults = sequence.map { second.record(Unpooled.wrappedBuffer(it)) }

        assertEquals(firstResults, secondResults)
        assertEquals(first.snapshot(), second.snapshot())
        assertTrue(PacketRefCache.isInserted(firstResults[1]))
        assertEquals(0, firstResults[2])
        // packet(3) evicted packet(2); packet(2) must be re-inserted, never matched against packet(3).
        assertTrue(PacketRefCache.isInserted(firstResults[4]))
        assertEquals(PacketRefFormat.check(42L), first.checkAt(0))
    }

    @Test
    fun `evicting one colliding entry keeps the other findable`() {
        val cache = PacketRefCache(2, 64) { _, _ -> 7L }
        insertedSlot(cache, packet(1))
        insertedSlot(cache, packet(2))
        cache.touch(0)

        assertEquals(1, insertedSlot(cache, packet(3)))

        assertEquals(0, cache.record(Unpooled.wrappedBuffer(packet(1))))
        assertEquals(1, cache.record(Unpooled.wrappedBuffer(packet(3))))
    }

    @Test
    fun `content lookup rejects empty and out of range slots`() {
        val cache = PacketRefCache(4, 64)
        insertedSlot(cache, packet(1))

        assertNull(cache.contentAt(-1))
        assertNull(cache.contentAt(1))
        assertNull(cache.contentAt(4))
        assertContentEquals(packet(1), cache.contentAt(0))
        assertEquals(PacketRefFormat.check(PacketRefFormat.hash(packet(1))), cache.checkAt(0))
    }

    @Test
    fun `baselines reset on eviction and on demand`() {
        val cache = PacketRefCache(1, 64)
        insertedSlot(cache, packet(1))
        cache.setBaseline(0, 40)
        assertEquals(40, cache.baselineAt(0))

        insertedSlot(cache, packet(2))
        assertEquals(PacketRefCache.UNKNOWN_BASELINE, cache.baselineAt(0))

        cache.setBaseline(0, 50)
        cache.clearBaselines()
        assertEquals(PacketRefCache.UNKNOWN_BASELINE, cache.baselineAt(0))
    }

    private fun insertedSlot(cache: PacketRefCache, content: ByteArray): Int {
        val result = cache.record(Unpooled.wrappedBuffer(content))
        assertTrue(PacketRefCache.isInserted(result), "expected an insert, got $result")
        return PacketRefCache.insertedSlot(result)
    }

    private fun packet(seed: Int): ByteArray = ByteArray(16) { (it * 13 + seed * 101).toByte() }
}
