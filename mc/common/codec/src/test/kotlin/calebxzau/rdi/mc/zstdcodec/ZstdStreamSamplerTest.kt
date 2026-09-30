package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.Unpooled
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZstdStreamSamplerTest {
    @Test
    fun `capture is non destructive bounded and exports a replayable stream`() {
        var now = 1_000L
        val sampler = ZstdStreamSampler(
            maxDurationMillis = 1000,
            maxCaptureBytes = 4096,
            nanoTime = { now },
        )
        val source = Unpooled.wrappedBuffer(byteArrayOf(9, 1, 2, 8))
        source.readerIndex(1)
        source.writerIndex(3)
        try {
            sampler.record(
                source,
                ZstdBatchPolicy.FourTicks,
                ZstdPacketIdentity("demo:state", "demo", "state"),
                threshold = 64,
            )
            sampler.tick()
            now += TimeUnit.MILLISECONDS.toNanos(2)
            sampler.barrier()
            sampler.finish()

            assertEquals(1, source.readerIndex())
            assertEquals(3, source.writerIndex())
            val capture = sampler.snapshot()
            assertTrue(capture.complete)
            assertEquals(ZstdStreamStopReason.EXPLICIT_END, capture.stopReason)
            val directory = Files.createTempDirectory("rdi-zstd-stream-test")
            try {
                val file = directory.resolve("sample.rdibatch")
                assertTrue(capture.export(file).isSuccess)
                val loaded = ZstdStreamReplay.read(file)
                assertTrue(loaded.complete)
                val record = loaded.events.filterIsInstance<ZstdStreamEvent.Record>().single()
                assertContentEquals(byteArrayOf(1, 2), record.content)
                assertEquals(ZstdBatchPolicy.FourTicks, record.policy)
                assertEquals("demo:state", record.identity?.packetType)

                val replay = ZstdStreamReplay.replay(loaded)
                assertEquals(1, replay.baseline.records)
                assertEquals(1, replay.oneTick.records)
                assertEquals(1, replay.fourTicks.records)
                assertTrue(replay.baseline.decodedRecords.single().contentEquals(byteArrayOf(1, 2)))
            } finally {
                Files.deleteIfExists(directory.resolve("sample.rdibatch"))
                Files.deleteIfExists(directory)
            }
        } finally {
            source.release()
        }
    }

    @Test
    fun `memory saturation is explicit and does not include the rejected record`() {
        var now = 0L
        val sampler = ZstdStreamSampler(
            maxDurationMillis = 1000,
            maxCaptureBytes = 227,
            nanoTime = { now },
        )
        val source = Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3, 4))
        try {
            sampler.record(source, ZstdBatchPolicy.OneTick, threshold = 32)
            val capture = sampler.snapshot()
            assertFalse(capture.complete)
            assertEquals(ZstdStreamStopReason.MEMORY_LIMIT, capture.stopReason)
            assertTrue(capture.events.isEmpty())
            assertEquals(128L, capture.capturedBytes)
        } finally {
            source.release()
        }
    }

    @Test
    fun `duration limit closes a complete window with a replay end marker`() {
        var now = 0L
        val sampler = ZstdStreamSampler(
            maxDurationMillis = 1,
            maxCaptureBytes = 4096,
            nanoTime = { now },
        )
        val source = Unpooled.wrappedBuffer(byteArrayOf(42))
        try {
            sampler.record(source, ZstdBatchPolicy.Immediate, threshold = 1)
            now = TimeUnit.MILLISECONDS.toNanos(1)
            sampler.tick()
            val capture = sampler.snapshot()
            assertTrue(capture.complete)
            assertEquals(ZstdStreamStopReason.DURATION_LIMIT, capture.stopReason)
            assertTrue(capture.events.last() is ZstdStreamEvent.End)
        } finally {
            source.release()
        }
    }

    @Test
    fun `disabling compression ends the capture without storing a negative threshold`() {
        val sampler = ZstdStreamSampler(maxDurationMillis = 1000, maxCaptureBytes = 4096)
        sampler.thresholdChanged(-1)

        val capture = sampler.snapshot()
        assertTrue(capture.complete)
        assertEquals(ZstdStreamStopReason.EXPLICIT_END, capture.stopReason)
        assertTrue(capture.events.single() is ZstdStreamEvent.End)
    }

    @Test
    fun `same stream replay preserves bytes while four tick policy reduces frames`() {
        var now = 0L
        val sampler = ZstdStreamSampler(
            maxDurationMillis = 1000,
            maxCaptureBytes = 16 * 1024,
            nanoTime = { now },
        )
        val record = Unpooled.wrappedBuffer(ByteArray(300) { (it * 13).toByte() })
        try {
            repeat(3) {
                sampler.record(
                    record,
                    ZstdBatchPolicy.FourTicks,
                    ZstdPacketIdentity("test:state", "test", "state"),
                    threshold = 64,
                )
                now += TimeUnit.MILLISECONDS.toNanos(10)
                sampler.tick()
            }
            sampler.finish()

            val result = ZstdStreamReplay.replay(sampler.snapshot())
            assertEquals(3, result.baseline.frameCount)
            assertEquals(3, result.oneTick.frameCount)
            assertEquals(1, result.fourTicks.frameCount)
            assertEquals(3, result.fourTicks.averageRecordsPerFrame.toInt())
        } finally {
            record.release()
        }
    }

    @Test
    fun `offline replay advances silent gaps to each batching deadline`() {
        var now = 0L
        val sampler = ZstdStreamSampler(
            maxDurationMillis = 1000,
            maxCaptureBytes = 4096,
            nanoTime = { now },
        )
        val record = Unpooled.wrappedBuffer(ByteArray(300) { it.toByte() })
        try {
            sampler.record(record, ZstdBatchPolicy.FourTicks, threshold = 64)
            now = TimeUnit.MILLISECONDS.toNanos(250)
            sampler.barrier()
            sampler.finish()

            val result = ZstdStreamReplay.replay(sampler.snapshot())
            assertEquals(TimeUnit.MILLISECONDS.toNanos(50), result.oneTick.maximumBatchWaitNanos)
            assertEquals(TimeUnit.MILLISECONDS.toNanos(200), result.fourTicks.maximumBatchWaitNanos)
        } finally {
            record.release()
        }
    }
}
