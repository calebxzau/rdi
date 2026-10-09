package calebxzau.rdi.mc.client.chunkcache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkCacheOutboxTest {
    @Test
    fun `offers completed during a tick leave together`() {
        val outbox = ChunkCacheOutbox<Long> { it }
        (1L..5L).forEach(outbox::offer)
        val sent = ArrayList<List<Long>>()
        outbox.drain(128, 64, { error("no cancels") }, { sent += it })
        assertEquals(listOf(listOf(1L, 2L, 3L, 4L, 5L)), sent)
        assertEquals(0, outbox.pendingOffers)
    }

    @Test
    fun `cancelling an unsent offer is settled locally`() {
        val outbox = ChunkCacheOutbox<Long> { it }
        outbox.offer(1L)
        outbox.offer(2L)
        assertEquals(listOf(2L), outbox.cancel(listOf(2L, 9L)))
        val cancels = ArrayList<List<Long>>()
        val offers = ArrayList<List<Long>>()
        outbox.drain(128, 64, { cancels += it }, { offers += it })
        assertEquals(listOf(listOf(9L)), cancels)
        assertEquals(listOf(listOf(1L)), offers)
    }

    @Test
    fun `cancels precede offers and both respect their limits`() {
        val outbox = ChunkCacheOutbox<Long> { it }
        (1L..70L).forEach(outbox::offer)
        outbox.cancel((100L..104L).toList())
        val order = ArrayList<String>()
        val cancels = ArrayList<List<Long>>()
        val offers = ArrayList<List<Long>>()
        outbox.drain(2, 64, { order += "cancel"; cancels += it }, { order += "offer"; offers += it })
        assertEquals(listOf("cancel", "cancel", "cancel", "offer"), order)
        assertEquals(listOf(listOf(100L, 101L), listOf(102L, 103L), listOf(104L)), cancels)
        assertEquals((1L..64L).toList(), offers.single())
        assertEquals(6, outbox.pendingOffers)

        offers.clear()
        outbox.drain(2, 64, { error("cancels already sent") }, { offers += it })
        assertEquals((65L..70L).toList(), offers.single())
        assertTrue(outbox.pendingOffers == 0 && outbox.pendingCancels == 0)
    }
}
