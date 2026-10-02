package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheWriterLifecycleTest {
    @Test
    fun reconnectOpensOnlyAfterOldActiveWriteAndFileClose(): Unit {
        val events = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val lifecycle = ChunkCacheWriterLifecycle()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstPublished = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        val secondPublished = CountDownLatch(1)
        try {
            lifecycle.open({ false }, {
                queue(object : CoalescingWriteQueue.Sink<ClientChunkRegionSink.Key, ClientChunkSnapshot.Snapshot> {
                    override fun write(key: ClientChunkRegionSink.Key, value: ClientChunkSnapshot.Snapshot) {
                        events.add("old-write")
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                    override fun close() { events.add("old-close") }
                })
            }, { old ->
                old.submit(key(), value())
                firstPublished.countDown()
            }, failures::add)
            assertTrue(firstPublished.await(5, TimeUnit.SECONDS))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // Calling open must return even though the old write is blocked.
            lifecycle.open({ secondRequested.countDown(); false }, {
                events.add("new-open")
                queue(sink(events, "new"))
            }, { secondPublished.countDown() }, failures::add)
            assertFalse(secondPublished.await(30, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(secondPublished.await(5, TimeUnit.SECONDS))
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-write", "old-close", "new-open"), events.toList())
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            assertTrue(lifecycle.shutdown(5_000))
        }
    }

    @Test
    fun cancelledDuringQueueCreationClosesPublishedWriter(): Unit {
        val lifecycle = ChunkCacheWriterLifecycle()
        val cancelled = AtomicBoolean()
        val closed = CountDownLatch(1)
        val published = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        try {
            lifecycle.open(cancelled::get, {
                cancelled.set(true)
                queue(object : CoalescingWriteQueue.Sink<ClientChunkRegionSink.Key, ClientChunkSnapshot.Snapshot> {
                    override fun write(key: ClientChunkRegionSink.Key, value: ClientChunkSnapshot.Snapshot) = Unit
                    override fun close() { closed.countDown() }
                })
            }, { published.countDown() }, failures::add)
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
        } finally { assertTrue(lifecycle.shutdown(5_000)) }
    }

    @Test
    fun shutdownTimeoutLeavesCloseOnBackgroundOwner(): Unit {
        val lifecycle = ChunkCacheWriterLifecycle()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        try {
            lifecycle.open({ false }, {
                queue(object : CoalescingWriteQueue.Sink<ClientChunkRegionSink.Key, ClientChunkSnapshot.Snapshot> {
                    override fun write(key: ClientChunkRegionSink.Key, value: ClientChunkSnapshot.Snapshot) {
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                    override fun close() { closed.countDown() }
                })
            }, { it.submit(key(), value()) }, failures::add)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(lifecycle.shutdown(10))
            assertEquals(1L, closed.count)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
        } finally { release.countDown() }
    }

    private fun sink(events: MutableList<String>, name: String) =
        object : CoalescingWriteQueue.Sink<ClientChunkRegionSink.Key, ClientChunkSnapshot.Snapshot> {
            override fun write(key: ClientChunkRegionSink.Key, value: ClientChunkSnapshot.Snapshot) = Unit
            override fun close() { events.add("${name}-close") }
        }

    private fun queue(sink: CoalescingWriteQueue.Sink<ClientChunkRegionSink.Key, ClientChunkSnapshot.Snapshot>) =
        CoalescingWriteQueue(sink, { throw AssertionError(it) }, 2, 8192, 8192, ClientChunkSnapshot.Snapshot::estimatedBytes)

    private fun key() = ClientChunkRegionSink.Key(ResourceLocation.parse("minecraft:overworld"), 0, 0)
    private fun value() = ClientChunkSnapshot.Snapshot(key().dimension(), 0, 0, -4, 1, 0, 0,
        byteArrayOf(1), CompoundTag(), emptyList())
}
