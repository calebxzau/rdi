package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.resources.ResourceLocation
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientChunkWriterPriorityTest {
    private val dimension = ResourceLocation.parse("minecraft:overworld")
    private val chunkA = ClientChunkRegionSink.Key(dimension, 0, 0)
    private val chunkB = ClientChunkRegionSink.Key(dimension, 5, -3)

    /** Records commands; the first write blocks until released so later commands queue up behind it. */
    private inner class GatedSink : ClientChunkDeltaWriter.Sink {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val firstWriteEntered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun accept(write: ClientChunkCacheWrite) {
            if (firstWriteEntered.count > 0) {
                firstWriteEntered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            events += "write${write.sequence()}@${name(write.key())}"
        }

        override fun read(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Combined? {
            events += "read@${name(key)}"
            return null
        }

        override fun flush() { }
        override fun close() { }
    }

    @Test
    fun `a read passes queued writes of other chunks but waits for earlier writes of its own`(): Unit {
        val sink = GatedSink()
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 16, 1_000, 10_000)
        try {
            assertTrue(writer.submit(write(chunkA, 1)))
            assertTrue(sink.firstWriteEntered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.submit(write(chunkA, 2)))
            assertTrue(writer.submit(write(chunkA, 3)))
            val readB = writer.read(chunkB)
            val readA = writer.read(chunkA)
            sink.release.countDown()
            CompletableFuture.allOf(readA, readB).get(5, TimeUnit.SECONDS)
        } finally {
            sink.release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(listOf("write1@A", "read@B", "write2@A", "write3@A", "read@A"), sink.events)
    }

    @Test
    fun `a waiting write gets its turn after a few reads`(): Unit {
        val sink = GatedSink()
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 16, 1_000, 10_000)
        val reads = ArrayList<CompletableFuture<*>>()
        try {
            assertTrue(writer.submit(write(chunkA, 1)))
            assertTrue(sink.firstWriteEntered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.submit(write(chunkA, 2)))
            repeat(20) { reads += writer.read(chunkB) }
            sink.release.countDown()
            CompletableFuture.allOf(*reads.toTypedArray()).get(5, TimeUnit.SECONDS)
        } finally {
            sink.release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        val reads8 = List(ClientChunkDeltaWriter.MAX_CONSECUTIVE_READS) { "read@B" }
        assertEquals(listOf("write1@A") + reads8 + "write2@A" + List(20 - reads8.size) { "read@B" }, sink.events)
    }

    @Test
    fun `writes go first once their queue is half full`(): Unit {
        val sink = GatedSink()
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 4, 1_000, 10_000)
        try {
            assertTrue(writer.submit(write(chunkA, 1)))
            assertTrue(sink.firstWriteEntered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.submit(write(chunkA, 2)))
            assertTrue(writer.submit(write(chunkA, 3)))
            val readB = writer.read(chunkB)
            sink.release.countDown()
            readB.get(5, TimeUnit.SECONDS)
        } finally {
            sink.release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(listOf("write1@A", "write2@A", "read@B", "write3@A"), sink.events)
    }

    @Test
    fun `a write due first still waits for earlier reads of its chunk`(): Unit {
        val sink = GatedSink()
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 4, 1_000, 10_000)
        try {
            assertTrue(writer.submit(write(chunkA, 1)))
            assertTrue(sink.firstWriteEntered.await(5, TimeUnit.SECONDS))
            val readA = writer.read(chunkA)
            assertTrue(writer.submit(write(chunkA, 2)))
            assertTrue(writer.submit(write(chunkA, 3)))
            sink.release.countDown()
            readA.get(5, TimeUnit.SECONDS)
        } finally {
            sink.release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(listOf("write1@A", "read@A", "write2@A", "write3@A"), sink.events)
    }

    private fun name(key: ClientChunkRegionSink.Key): String = if (key == chunkA) "A" else "B"

    private fun write(key: ClientChunkRegionSink.Key, sequence: Long) = ClientChunkCacheWrite(
        key, sequence, 0, null, listOf(ClientChunkCacheWrite.BlockChange(0, 1, false)), 10,
    )
}
