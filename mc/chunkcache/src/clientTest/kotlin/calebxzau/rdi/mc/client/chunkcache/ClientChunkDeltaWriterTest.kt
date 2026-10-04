package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.resources.ResourceLocation
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientChunkDeltaWriterTest {
    @Test
    fun deferredStartBuffersOrderedInputsUntilOwnershipIsGranted(): Unit {
        val writes = Collections.synchronizedList(mutableListOf<Long>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { writes += write.sequence() }
            override fun flush() { }
            override fun close() { }
        }
        val writer = ClientChunkDeltaWriter(sink, failures::add, 2, 20, 20, false)
        assertTrue(writer.submit(write(1)))
        assertTrue(writer.submit(write(2)))
        assertTrue(writes.isEmpty())
        assertEquals(2, writer.stats().pendingEntries)
        writer.start()
        writer.start() // Lifecycle publication must not create a second owner.
        writer.closeAsync()
        assertTrue(writer.awaitClosed(5_000))
        assertEquals(listOf(1L, 2L), writes)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun cancellingBeforeStartNeverCreatesAFileOwner(): Unit {
        var touched = false
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { touched = true }
            override fun flush() { touched = true }
            override fun close() { touched = true }
        }
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 2, 20, 20, false)
        assertTrue(writer.submit(write(1)))
        writer.closeAsync()
        writer.start()
        assertTrue(writer.awaitClosed(5_000))
        assertFalse(touched)
        assertEquals(1L, writer.stats().discarded)
        assertEquals(0, writer.stats().pendingEntries)
    }

    @Test
    fun fullQueueRejectsWithoutEvictingEarlierUpdatesAndCloseDrainsInOrder(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Long>())
        val threads = Collections.synchronizedList(mutableListOf<String>())
        var closed = false
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) {
                threads += Thread.currentThread().name
                if (write.sequence() == 1L) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                writes += write.sequence()
            }
            override fun flush() { threads += Thread.currentThread().name }
            override fun close() { closed = true }
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = ClientChunkDeltaWriter(sink, failures::add, 2, 20, 10_000)
        try {
            assertTrue(writer.submit(write(1)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.submit(write(2)))
            assertTrue(writer.submit(write(3)))
            assertFalse(writer.submit(write(4)))
            assertEquals(2, writer.stats().pendingEntries)
            assertEquals(0L, writer.stats().discarded)
            writer.closeAsync()
        } finally {
            release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(listOf(1L, 2L, 3L), writes)
        assertEquals(1L, writer.stats().rejected)
        assertTrue(threads.all { it == "rdi-chunk-cache-writer" })
        assertTrue(closed)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun idlePendingDeltasFlushWithoutAnotherSubmission(): Unit {
        val flushed = CountDownLatch(1)
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { }
            override fun flush() { flushed.countDown() }
            override fun close() { }
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = ClientChunkDeltaWriter(sink, failures::add, 2, 20, 20)
        try {
            assertTrue(writer.submit(write(1)))
            assertTrue(flushed.await(5, TimeUnit.SECONDS))
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertTrue(writer.stats().flushes >= 1)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun writeFailureStopsProcessingAndClosesOwner(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IOException("delta write failed")
        var closed = false
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                throw failure
            }
            override fun flush() { }
            override fun close() { closed = true }
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = ClientChunkDeltaWriter(sink, failures::add, 2, 20, 20)
        try {
            assertTrue(writer.submit(write(1)))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(writer.submit(write(2)))
        } finally {
            release.countDown()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertTrue(closed)
        assertEquals(listOf(failure), failures)
        assertEquals(0L, writer.stats().processed)
        assertEquals(1L, writer.stats().discarded)
        assertFalse(writer.submit(write(3)))
    }

    private fun write(sequence: Long) = ClientChunkCacheWrite(
        ClientChunkRegionSink.Key(ResourceLocation.parse("minecraft:overworld"), 0, 0),
        sequence, 0, null, listOf(ClientChunkCacheWrite.BlockChange(0, 1, false)), 10,
    )
}
