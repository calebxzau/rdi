package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Offline same-stream replay entry point. Run with the codec test runtime classpath. */
object ZstdStreamReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: ZstdStreamReplay <capture.rdibatch>" }
        val capture = read(Path.of(args[0]))
        check(capture.complete) {
            "Capture is incomplete (${capture.stopReason}); replay would misrepresent a truncated stream"
        }
        println(replay(capture).format())
    }

    internal fun read(path: Path): ZstdStreamCapture = DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
        require(input.readInt() == MAGIC) { "Not an RDI Zstd stream capture" }
        require(input.readInt() == FORMAT_VERSION) { "Unsupported capture format version" }
        val complete = input.readBoolean()
        val reason = ZstdStreamStopReason.valueOf(input.readUTF())
        val durationNanos = input.readLong().also { require(it >= 0) { "Negative capture duration" } }
        val capturedBytes = input.readLong().also { require(it >= 0) { "Negative capture size" } }
        require(capturedBytes <= MAX_IMPORTED_CAPTURE_BYTES) { "Capture exceeds the replay memory limit" }
        val eventCount = input.readInt().also { require(it in 0..MAX_EVENTS) { "Invalid event count: $it" } }
        val events = ArrayList<ZstdStreamEvent>(eventCount)
        val budget = CaptureReadBudget()
        var previousTime = -1L
        var previousTick = 0L
        repeat(eventCount) {
            val time = input.readLong().also {
                require(it >= previousTime && it <= durationNanos) { "Capture events are out of time order" }
                previousTime = it
            }
            val tick = input.readLong().also {
                require(it >= previousTick) { "Capture tick index moved backwards" }
                previousTick = it
            }
            when (input.readUnsignedByte()) {
                TYPE_RECORD -> {
                    budget.reserve(RECORD_ESTIMATED_BYTES)
                    val policyIndex = input.readUnsignedByte()
                    val policy = ZstdBatchPolicy.entries.getOrNull(policyIndex)
                        ?: throw IllegalArgumentException("Invalid packet policy $policyIndex")
                    val threshold = input.readInt().also { require(it >= 0) { "Negative threshold" } }
                    val identity = if (input.readBoolean()) {
                        val packetType = input.readString(budget)
                        val namespace = if (input.readBoolean()) input.readString(budget) else null
                        val pathValue = if (input.readBoolean()) input.readString(budget) else null
                        ZstdPacketIdentity(packetType, namespace, pathValue)
                    } else null
                    val length = input.readInt().also { require(it in 0..MAX_RECORD_BYTES) { "Invalid record length: $it" } }
                    budget.reserve(length.toLong())
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    events += ZstdStreamEvent.Record(time, tick, policy, identity, threshold, bytes)
                }
                TYPE_TICK -> {
                    budget.reserve(EVENT_ESTIMATED_BYTES)
                    events += ZstdStreamEvent.Tick(time, tick)
                }
                TYPE_THRESHOLD -> {
                    budget.reserve(EVENT_ESTIMATED_BYTES)
                    events += ZstdStreamEvent.Threshold(
                        time,
                        tick,
                        input.readInt().also { require(it >= 0) { "Negative threshold" } },
                    )
                }
                TYPE_BARRIER -> {
                    budget.reserve(EVENT_ESTIMATED_BYTES)
                    events += ZstdStreamEvent.Barrier(time, tick)
                }
                TYPE_END -> {
                    budget.reserve(EVENT_ESTIMATED_BYTES)
                    events += ZstdStreamEvent.End(time, tick)
                }
                else -> throw IllegalArgumentException("Unknown capture event type")
            }
        }
        require(budget.bytes <= capturedBytes) { "Capture byte accounting is smaller than its decoded contents" }
        require(input.read() == -1) { "Trailing data after stream capture" }
        ZstdStreamCapture(events, complete, reason, durationNanos, capturedBytes)
    }

    internal fun replay(capture: ZstdStreamCapture): ZstdStreamReplayResult {
        require(capture.complete) { "Cannot compare an incomplete capture" }
        val baseline = run(capture, ZstdStreamReplayMode.BASELINE)
        val oneTick = run(capture, ZstdStreamReplayMode.ONE_TICK)
        val fourTicks = run(capture, ZstdStreamReplayMode.FOUR_TICKS)
        requireSameRecords(baseline.decodedRecords, oneTick.decodedRecords)
        requireSameRecords(baseline.decodedRecords, fourTicks.decodedRecords)
        return ZstdStreamReplayResult(baseline, oneTick, fourTicks)
    }

    private fun run(capture: ZstdStreamCapture, mode: ZstdStreamReplayMode): ZstdStreamReplayModeResult {
        var virtualNanos = 1L
        var replayElapsedNanos = 0L
        val threshold = capture.events.filterIsInstance<ZstdStreamEvent.Record>().firstOrNull()?.threshold ?: 0
        val encoder = ZstdCompressionEncoder(
            threshold = threshold,
            varIntCodec = TEST_VAR_INT,
            nanoTime = { virtualNanos },
        )
        val outbound = EmbeddedChannel()
        outbound.freezeTime()
        outbound.pipeline().addLast("compress", encoder)
        val inbound = EmbeddedChannel()
        inbound.freezeTime()
        inbound.pipeline().addLast("decompress", ZstdCompressionDecoder(threshold, false, TEST_VAR_INT))
        if (mode != ZstdStreamReplayMode.BASELINE) {
            ZstdCompressionPipeline.setOutboundBatching(outbound, true)
            ZstdCompressionPipeline.setInboundBatching(inbound, true)
        }
        val observedBlocks = ArrayList<ZstdBatchSample>()
        ZstdCompressionPipeline.setBatchObserver(outbound, object : ZstdBatchObserver {
            override fun recordBuffered(encodedBytes: Int) = Unit
            override fun batchFlushed(sample: ZstdBatchSample) {
                observedBlocks += sample
            }
        })

        val decoded = ArrayList<ByteArray>()
        var encodedInnerBytes = 0L
        var framedBytes = 0L
        var frameCount = 0
        var encoderElapsedNanos = 0L
        val expected = ArrayList<ByteArray>()
        var currentThreshold = threshold

        fun collectFrames() {
            val totals = drain(outbound, inbound, decoded)
            encodedInnerBytes += totals.innerBytes
            framedBytes += totals.framedBytes
            frameCount += totals.frameCount
        }

        fun advanceTo(targetElapsedNanos: Long) {
            require(targetElapsedNanos >= replayElapsedNanos) { "Replay event time moved backwards" }
            while (true) {
                val remainingNanos = targetElapsedNanos - replayElapsedNanos
                val nextDelayNanos = outbound.runScheduledPendingTasks()
                if (nextDelayNanos < 0L || nextDelayNanos > remainingNanos) {
                    if (remainingNanos > 0L) {
                        outbound.advanceTimeBy(remainingNanos, TimeUnit.NANOSECONDS)
                        replayElapsedNanos = targetElapsedNanos
                    }
                    virtualNanos = 1L + replayElapsedNanos
                    outbound.runScheduledPendingTasks()
                    inbound.runScheduledPendingTasks()
                    collectFrames()
                    return
                }

                outbound.advanceTimeBy(nextDelayNanos, TimeUnit.NANOSECONDS)
                replayElapsedNanos += nextDelayNanos
                virtualNanos = 1L + replayElapsedNanos
                outbound.runScheduledPendingTasks()
                inbound.runScheduledPendingTasks()
                collectFrames()
                if (nextDelayNanos == 0L && replayElapsedNanos == targetElapsedNanos) return
            }
        }

        try {
            capture.events.forEach { event ->
                advanceTo(event.timeNanos)
                when (event) {
                    is ZstdStreamEvent.Record -> {
                        if (currentThreshold != event.threshold) {
                            if (mode != ZstdStreamReplayMode.BASELINE) ZstdCompressionPipeline.flushBatched(outbound)
                            currentThreshold = event.threshold
                            encoder.updateThreshold(currentThreshold)
                            collectFrames()
                        }
                        expected += event.content
                        val policy = when (mode) {
                            ZstdStreamReplayMode.BASELINE -> ZstdBatchPolicy.Immediate
                            ZstdStreamReplayMode.ONE_TICK -> if (event.policy == ZstdBatchPolicy.Immediate) {
                                ZstdBatchPolicy.Immediate
                            } else ZstdBatchPolicy.OneTick
                            ZstdStreamReplayMode.FOUR_TICKS -> event.policy
                        }
                        val encodeStarted = System.nanoTime()
                        outbound.writeOneOutbound(
                            ZstdSendingRecord(Unpooled.wrappedBuffer(event.content.copyOf()), policy, event.identity),
                        )
                        if (mode == ZstdStreamReplayMode.BASELINE) outbound.flushOutbound()
                        encoderElapsedNanos += System.nanoTime() - encodeStarted
                        collectFrames()
                    }
                    is ZstdStreamEvent.Tick -> if (mode != ZstdStreamReplayMode.BASELINE) {
                        val flushStarted = System.nanoTime()
                        ZstdCompressionPipeline.flushAtTickEnd(outbound)
                        encoderElapsedNanos += System.nanoTime() - flushStarted
                        collectFrames()
                    }
                    is ZstdStreamEvent.Threshold -> {
                        if (mode != ZstdStreamReplayMode.BASELINE) {
                            val flushStarted = System.nanoTime()
                            ZstdCompressionPipeline.flushBatched(outbound)
                            encoderElapsedNanos += System.nanoTime() - flushStarted
                        }
                        currentThreshold = event.threshold
                        encoder.updateThreshold(currentThreshold)
                        collectFrames()
                    }
                    is ZstdStreamEvent.Barrier, is ZstdStreamEvent.End -> if (mode != ZstdStreamReplayMode.BASELINE) {
                        val flushStarted = System.nanoTime()
                        ZstdCompressionPipeline.flushBatched(outbound)
                        encoderElapsedNanos += System.nanoTime() - flushStarted
                        collectFrames()
                    }
                }
            }
            if (mode != ZstdStreamReplayMode.BASELINE) {
                val flushStarted = System.nanoTime()
                ZstdCompressionPipeline.flushBatched(outbound)
                encoderElapsedNanos += System.nanoTime() - flushStarted
            }
            collectFrames()
            requireSameRecords(expected, decoded)
            val bufferedFlushes = observedBlocks.filter { it.bufferedFlush && it.firstFrameOfFlush }
            val waits = bufferedFlushes.map { it.waitNanos }
            return ZstdStreamReplayModeResult(
                mode = mode,
                records = decoded.size,
                encodedInnerBytes = encodedInnerBytes,
                outerFramedBytes = framedBytes,
                frameCount = frameCount,
                averageRecordsPerFrame = decoded.size.toDouble() / frameCount.coerceAtLeast(1),
                encoderElapsedNanos = encoderElapsedNanos,
                summedBatchWaitNanos = waits.sum(),
                maximumBatchWaitNanos = waits.maxOrNull() ?: 0L,
                barrierFlushes = bufferedFlushes.count { it.flushReason == ZstdBatchFlushReason.BARRIER },
                decodedRecords = decoded,
            )
        } finally {
            outbound.finishAndReleaseAll()
            inbound.finishAndReleaseAll()
        }
    }

    private fun drain(
        outbound: EmbeddedChannel,
        inbound: EmbeddedChannel,
        decoded: MutableList<ByteArray>,
    ): DrainTotals {
        var inner = 0L
        var framed = 0L
        var frames = 0
        while (true) {
            val message = outbound.readOutbound<ByteBuf>() ?: break
            try {
                val size = message.readableBytes()
                inner += size
                framed += size + ZstdBatchFormat.varIntSize(size)
                frames++
                inbound.writeInbound(message)
                while (true) {
                    val record = inbound.readInbound<ByteBuf>() ?: break
                    try {
                        decoded += ByteArray(record.readableBytes()).also {
                            record.getBytes(record.readerIndex(), it)
                        }
                    } finally {
                        record.release()
                    }
                }
            } finally {
                if (message.refCnt() > 0) message.release()
            }
        }
        outbound.runPendingTasks()
        inbound.runPendingTasks()
        return DrainTotals(inner, framed, frames)
    }

    private fun requireSameRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
        check(expected.size == actual.size) { "Replay changed packet count: ${expected.size} != ${actual.size}" }
        expected.indices.forEach { index ->
            check(expected[index].contentEquals(actual[index])) { "Replay changed packet bytes or order at index $index" }
        }
    }

    private data class DrainTotals(val innerBytes: Long, val framedBytes: Long, val frameCount: Int)

    private object TEST_VAR_INT : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                require(buffer.isReadable) { "Unterminated VarInt" }
                val current = buffer.readUnsignedByte().toInt()
                value = value or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("VarInt is too large")
        }

        override fun write(buffer: ByteBuf, value: Int) {
            var current = value
            while (current and -0x80 != 0) {
                buffer.writeByte((current and 0x7F) or 0x80)
                current = current ushr 7
            }
            buffer.writeByte(current)
        }
    }

    internal const val MAGIC = 0x52445354
    internal const val FORMAT_VERSION = 1
    private const val MAX_EVENTS = 100_000
    private const val MAX_RECORD_BYTES = ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH
    private const val EVENT_ESTIMATED_BYTES = 64L
    private const val RECORD_ESTIMATED_BYTES = 96L
    private const val TYPE_RECORD = 1
    private const val TYPE_TICK = 2
    private const val TYPE_THRESHOLD = 3
    private const val TYPE_BARRIER = 4
    private const val TYPE_END = 5
}

internal data class ZstdStreamReplayResult(
    val baseline: ZstdStreamReplayModeResult,
    val oneTick: ZstdStreamReplayModeResult,
    val fourTicks: ZstdStreamReplayModeResult,
) {
    fun format(): String = listOf(baseline, oneTick, fourTicks).joinToString("\n") { it.format() }
}

internal enum class ZstdStreamReplayMode(val label: String) {
    BASELINE("baseline"),
    ONE_TICK("all-eligible-one-tick"),
    FOUR_TICKS("verified-four-tick"),
}

internal data class ZstdStreamReplayModeResult internal constructor(
    private val mode: ZstdStreamReplayMode,
    val records: Int,
    val encodedInnerBytes: Long,
    val outerFramedBytes: Long,
    val frameCount: Int,
    val averageRecordsPerFrame: Double,
    val encoderElapsedNanos: Long,
    val summedBatchWaitNanos: Long,
    val maximumBatchWaitNanos: Long,
    val barrierFlushes: Int,
    internal val decodedRecords: List<ByteArray>,
) {
    internal fun format(): String = """${mode.label}: records=$records innerBytes=$encodedInnerBytes outerFramedBytes=$outerFramedBytes frames=$frameCount averageRecordsPerFrame=$averageRecordsPerFrame encoderElapsedNanos=$encoderElapsedNanos summedBatchWaitNanos=$summedBatchWaitNanos maxBatchWaitNanos=$maximumBatchWaitNanos barrierFlushes=$barrierFlushes"""
}

private class CaptureReadBudget {
    var bytes = ZstdStreamSampler.HEADER_ESTIMATED_BYTES
        private set

    fun reserve(size: Long) {
        require(size >= 0 && bytes + size <= MAX_IMPORTED_CAPTURE_BYTES) { "Capture exceeds the replay memory limit" }
        bytes += size
    }

    fun readString(input: DataInputStream): String {
        val length = input.readInt().also { require(it in 0..MAX_STRING_BYTES) { "Invalid identity string length: $it" } }
        reserve(length.toLong())
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

private fun DataInputStream.readString(budget: CaptureReadBudget): String = budget.readString(this)

private const val MAX_IMPORTED_CAPTURE_BYTES = 64L * 1024 * 1024
private const val MAX_STRING_BYTES = 1 shl 20
