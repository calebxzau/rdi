package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.resources.ResourceLocation
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientChunkReadCommandTest {
    private val key = ClientChunkRegionSink.Key(ResourceLocation.parse("minecraft:overworld"), 0, 0)

    @Test
    fun readRunsOnWriterAfterEarlierWritesAndBeforeLaterWrites(): Unit {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val readWorker = CompletableFuture<String>()
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { events += "write${write.sequence()}" }
            override fun read(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Combined? {
                events += "read"
                readWorker.complete(Thread.currentThread().name)
                return null
            }
            override fun flush() { }
            override fun close() { }
        }
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 4, 100, 10_000)
        try {
            assertTrue(writer.submit(write(1)))
            val read = writer.read(key)
            assertTrue(writer.submit(write(2)))
            assertNull(read.get(5, TimeUnit.SECONDS))
            assertEquals("rdi-chunk-cache-writer", readWorker.get(5, TimeUnit.SECONDS))
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(listOf("write1", "read", "write2"), events)
    }

    @Test
    fun readFailureCompletesOnlyItsFutureAndWorkerContinues(): Unit {
        var calls = 0
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { }
            override fun read(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Combined? {
                calls++
                if (calls == 1) error("corrupt cached region")
                return null
            }
            override fun flush() { }
            override fun close() { }
        }
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 2, 100, 10_000)
        try {
            val failed = writer.read(key)
            assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
            assertNull(writer.read(key).get(5, TimeUnit.SECONDS))
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(2, calls)
    }

    @Test
    fun fullAndTerrainReadsShareFifoCapacityAndRecordStoreMetrics(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var fullReadCalls = 0
        var terrainReadCalls = 0
        val base = ClientChunkSnapshot.Snapshot(key.dimension(), key.x(), key.z(), 0, 0, 0, 1,
            byteArrayOf(), net.minecraft.nbt.CompoundTag(), emptyList())
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { events += "write${write.sequence()}" }
            override fun read(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Combined? {
                events += "full"
                return when (fullReadCalls++) {
                    0 -> ClientChunkDeltaStore.Combined(base, emptyMap(), emptyList(), null, false)
                    2 -> error("read failure")
                    else -> null
                }
            }
            override fun readTerrain(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Terrain? {
                events += "terrain"
                if (terrainReadCalls++ == 0) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                return null
            }
            override fun flush() { }
            override fun close() { }
        }
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 2, 100, 10_000)
        try {
            assertTrue(writer.submit(write(1)))
            val terrain = writer.readTerrain(key)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val fullReads = List(31) { writer.read(key) }
            assertFailsWith<ExecutionException> { writer.readTerrain(key).get(5, TimeUnit.SECONDS) }
            assertTrue(writer.submit(write(2)))
            release.countDown()
            assertNull(terrain.get(5, TimeUnit.SECONDS))
            assertNotNull(fullReads.first().get(5, TimeUnit.SECONDS))
            assertNull(fullReads[1].get(5, TimeUnit.SECONDS))
            assertFailsWith<ExecutionException> { fullReads[2].get(5, TimeUnit.SECONDS) }
            fullReads.drop(3).forEach { assertNull(it.get(5, TimeUnit.SECONDS)) }
            assertTrue(writer.submit(write(3)))
            val finalTerrain = writer.readTerrain(key)
            assertNull(finalTerrain.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertEquals(listOf("write1", "terrain") + List(31) { "full" } + listOf("write2", "write3", "terrain"), events)
        val stats = writer.stats()
        assertEquals(33L, stats.reads)
        assertEquals(31L, stats.missingReads)
        assertEquals(1L, stats.failedReads)
        assertTrue(stats.readQueueNanos >= 0)
        assertTrue(stats.maxReadQueueNanos >= 0)
        assertTrue(stats.storeReadNanos >= 0)
        assertTrue(stats.maxStoreReadNanos >= 0)
    }

    @Test
    fun readCapacityIncludesActiveCommandAndDiscardCompletesQueuedReads(): Unit {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { }
            override fun read(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Combined? {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return null
            }
            override fun readTerrain(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Terrain? {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return null
            }
            override fun flush() { }
            override fun close() { }
        }
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 2, 100, 10_000)
        val reads = mutableListOf<CompletableFuture<*>>()
        try {
            reads += writer.readTerrain(key)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(31) { index -> reads += if (index % 2 == 0) writer.read(key) else writer.readTerrain(key) }
            assertEquals(32, reads.size)
            assertFailsWith<ExecutionException> { writer.read(key).get(5, TimeUnit.SECONDS) }
            writer.discardPending()
            reads.drop(1).forEach { future ->
                assertTrue(future.isCompletedExceptionally)
            }
        } finally {
            release.countDown()
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertNull(reads.first().get(5, TimeUnit.SECONDS))
    }

    @Test
    fun readBeforeStartIsCompletedExceptionallyWhenClosed(): Unit {
        val sink = object : ClientChunkDeltaWriter.Sink {
            override fun accept(write: ClientChunkCacheWrite) { }
            override fun flush() { }
            override fun close() { }
        }
        val writer = ClientChunkDeltaWriter(sink, { throw AssertionError(it) }, 2, 100, 10_000, false)
        val read = writer.read(key)
        val terrainRead = writer.readTerrain(key)
        writer.closeAsync()
        writer.start()
        assertTrue(writer.awaitClosed(5_000))
        assertFalse(read.isCancelled)
        assertFailsWith<ExecutionException> { read.get(5, TimeUnit.SECONDS) }
        assertFalse(terrainRead.isCancelled)
        assertFailsWith<ExecutionException> { terrainRead.get(5, TimeUnit.SECONDS) }
    }

    private fun write(sequence: Long) = ClientChunkCacheWrite(
        key, sequence, 0, null, listOf(ClientChunkCacheWrite.BlockChange(0, 1, false)), 10,
    )
}
