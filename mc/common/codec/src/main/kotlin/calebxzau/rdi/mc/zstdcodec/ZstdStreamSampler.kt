package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Bounded, opt-in capture of one connection's encoded outbound packet stream.
 *
 * Construct this only for a selected connection. [record] copies bytes without changing the
 * supplied buffer's indices. It performs no file I/O; callers must invoke [export] away from the
 * Netty event loop. Reaching a configured memory/event limit marks the capture incomplete and
 * rejects further samples. Reaching the duration limit is a complete end of the requested window.
 */
internal class ZstdStreamSampler @JvmOverloads constructor(
    maxDurationMillis: Long,
    private val maxCaptureBytes: Long,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val maxDurationNanos = TimeUnit.MILLISECONDS.toNanos(maxDurationMillis)
    private val startedNanos = nanoTime()
    private val events = ArrayList<ZstdStreamEvent>()
    private var retainedBytes = HEADER_ESTIMATED_BYTES.toLong()
    private var tickIndex = 0L
    private var state: ZstdStreamCaptureState = ZstdStreamCaptureState.Capturing

    init {
        require(maxDurationMillis > 0) { "maxDurationMillis must be positive" }
        require(maxCaptureBytes >= HEADER_ESTIMATED_BYTES) { "maxCaptureBytes is too small" }
        require(maxEvents > 0) { "maxEvents must be positive" }
    }

    @Synchronized
    fun record(
        bytes: ByteBuf,
        policy: ZstdBatchPolicy,
        identity: ZstdPacketIdentity? = null,
        threshold: Int,
    ) {
        if (threshold < 0) {
            finish()
            return
        }
        if (!advanceWindow()) return
        val readableBytes = bytes.readableBytes()
        val estimatedBytes = RECORD_ESTIMATED_BYTES.toLong() + readableBytes + estimate(identity)
        if (!canAppend(estimatedBytes)) return
        val content = ByteArray(readableBytes)
        bytes.getBytes(bytes.readerIndex(), content)
        val event = ZstdStreamEvent.Record(
            timeNanos = elapsedNanos(),
            tickIndex = tickIndex,
            policy = policy,
            identity = identity,
            threshold = threshold,
            content = content,
        )
        append(event, estimatedBytes)
    }

    /** Records the end of one global server tick and advances the tick index. */
    @Synchronized
    fun tick() {
        if (!advanceWindow()) return
        val nextTick = tickIndex + 1
        append(ZstdStreamEvent.Tick(elapsedNanos(), nextTick), EVENT_ESTIMATED_BYTES)
        if (state is ZstdStreamCaptureState.Capturing) tickIndex = nextTick
    }

    /** Records a compression threshold transition at this point in the stream. */
    @Synchronized
    fun thresholdChanged(threshold: Int) {
        if (threshold < 0) {
            finish()
            return
        }
        if (!advanceWindow()) return
        append(ZstdStreamEvent.Threshold(elapsedNanos(), tickIndex, threshold), EVENT_ESTIMATED_BYTES)
    }

    /** Records an outbound ordering barrier. */
    @Synchronized
    fun barrier() {
        if (!advanceWindow()) return
        append(ZstdStreamEvent.Barrier(elapsedNanos(), tickIndex), EVENT_ESTIMATED_BYTES)
    }

    /** Ends the sample with an explicit final drain marker. Safe to call more than once. */
    @Synchronized
    fun finish() {
        if (state !is ZstdStreamCaptureState.Capturing) return
        if (!advanceWindow()) return
        append(ZstdStreamEvent.End(elapsedNanos(), tickIndex), EVENT_ESTIMATED_BYTES)
        if (state is ZstdStreamCaptureState.Capturing) state = ZstdStreamCaptureState.Complete
    }

    @Synchronized
    fun snapshot(): ZstdStreamCapture = ZstdStreamCapture(
        events = events.toList(),
        complete = state is ZstdStreamCaptureState.Complete || state is ZstdStreamCaptureState.DurationReached,
        stopReason = state.reason,
        capturedDurationNanos = elapsedNanos(),
        capturedBytes = retainedBytes,
    )

    /** Serializes a stable snapshot. Call from a worker thread, never from Netty. */
    fun export(path: Path): Result<Path> = snapshot().export(path)

    @Synchronized
    override fun close() = finish()

    private fun append(event: ZstdStreamEvent, estimatedBytes: Long) {
        if (!canAppend(estimatedBytes)) return
        events.add(event)
        retainedBytes += estimatedBytes
    }

    private fun canAppend(estimatedBytes: Long): Boolean {
        if (state !is ZstdStreamCaptureState.Capturing) return false
        when {
            events.size + 1 > maxEvents -> state = ZstdStreamCaptureState.LimitReached(ZstdStreamStopReason.EVENT_LIMIT)
            retainedBytes + estimatedBytes > maxCaptureBytes ->
                state = ZstdStreamCaptureState.LimitReached(ZstdStreamStopReason.MEMORY_LIMIT)
            else -> return true
        }
        return false
    }

    private fun advanceWindow(): Boolean {
        if (state !is ZstdStreamCaptureState.Capturing) return false
        if (elapsedNanos() >= maxDurationNanos) {
            if (canAppend(EVENT_ESTIMATED_BYTES)) {
                events.add(ZstdStreamEvent.End(elapsedNanos(), tickIndex))
                retainedBytes += EVENT_ESTIMATED_BYTES
                state = ZstdStreamCaptureState.DurationReached
            }
            return false
        }
        return true
    }

    private fun elapsedNanos(): Long = (nanoTime() - startedNanos).coerceAtLeast(0L)

    private fun estimate(identity: ZstdPacketIdentity?): Long = identity?.let {
        (it.packetType.toByteArray(StandardCharsets.UTF_8).size +
            (it.namespace?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) +
            (it.path?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0)).toLong()
    } ?: 0L

    internal companion object {
        const val DEFAULT_MAX_EVENTS = 100_000
        const val HEADER_ESTIMATED_BYTES = 128L
        const val EVENT_ESTIMATED_BYTES = 64L
        const val RECORD_ESTIMATED_BYTES = 96L
        internal const val MAGIC = 0x52445354 // RDST
        internal const val FORMAT_VERSION = 1
    }
}

/** A stable, detached capture suitable for persistence or offline replay. */
internal data class ZstdStreamCapture internal constructor(
    val events: List<ZstdStreamEvent>,
    val complete: Boolean,
    val stopReason: ZstdStreamStopReason,
    val capturedDurationNanos: Long,
    val capturedBytes: Long,
) {
    /** Call away from Netty; this detached snapshot performs only file I/O. */
    fun export(path: Path): Result<Path> = runCatching {
        path.toAbsolutePath().parent?.let { parent -> Files.createDirectories(parent) }
        DataOutputStream(
            BufferedOutputStream(
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            ),
        ).use { output ->
            output.writeInt(ZstdStreamSampler.MAGIC)
            output.writeInt(ZstdStreamSampler.FORMAT_VERSION)
            output.writeBoolean(complete)
            output.writeUTF(stopReason.name)
            output.writeLong(capturedDurationNanos)
            output.writeLong(capturedBytes)
            output.writeInt(events.size)
            events.forEach { event -> event.write(output) }
        }
        path
    }
}

internal enum class ZstdStreamStopReason {
    EXPLICIT_END,
    CAPTURE_ACTIVE,
    DURATION_LIMIT,
    MEMORY_LIMIT,
    EVENT_LIMIT,
}

internal sealed class ZstdStreamEvent protected constructor(
    val timeNanos: Long,
    val tickIndex: Long,
) {
    class Record(
        timeNanos: Long,
        tickIndex: Long,
        val policy: ZstdBatchPolicy,
        val identity: ZstdPacketIdentity?,
        val threshold: Int,
        val content: ByteArray,
    ) : ZstdStreamEvent(timeNanos, tickIndex)

    class Tick(timeNanos: Long, tickIndex: Long) : ZstdStreamEvent(timeNanos, tickIndex)
    class Threshold(timeNanos: Long, tickIndex: Long, val threshold: Int) : ZstdStreamEvent(timeNanos, tickIndex)
    class Barrier(timeNanos: Long, tickIndex: Long) : ZstdStreamEvent(timeNanos, tickIndex)
    class End(timeNanos: Long, tickIndex: Long) : ZstdStreamEvent(timeNanos, tickIndex)

    fun write(output: DataOutputStream) {
        output.writeLong(timeNanos)
        output.writeLong(tickIndex)
        when (this) {
            is Record -> {
                output.writeByte(TYPE_RECORD)
                output.writeByte(policy.ordinal)
                output.writeInt(threshold)
                output.writeBoolean(identity != null)
                identity?.let {
                    output.writeString(it.packetType)
                    output.writeBoolean(it.namespace != null)
                    it.namespace?.let(output::writeString)
                    output.writeBoolean(it.path != null)
                    it.path?.let(output::writeString)
                }
                output.writeInt(content.size)
                output.write(content)
            }
            is Tick -> output.writeByte(TYPE_TICK)
            is Threshold -> {
                output.writeByte(TYPE_THRESHOLD)
                output.writeInt(threshold)
            }
            is Barrier -> output.writeByte(TYPE_BARRIER)
            is End -> output.writeByte(TYPE_END)
        }
    }

    private companion object {
        const val TYPE_RECORD = 1
        const val TYPE_TICK = 2
        const val TYPE_THRESHOLD = 3
        const val TYPE_BARRIER = 4
        const val TYPE_END = 5
    }
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private sealed interface ZstdStreamCaptureState {
    val reason: ZstdStreamStopReason

    data object Capturing : ZstdStreamCaptureState {
        override val reason = ZstdStreamStopReason.CAPTURE_ACTIVE
    }

    data object Complete : ZstdStreamCaptureState {
        override val reason = ZstdStreamStopReason.EXPLICIT_END
    }

    data object DurationReached : ZstdStreamCaptureState {
        override val reason = ZstdStreamStopReason.DURATION_LIMIT
    }

    data class LimitReached(override val reason: ZstdStreamStopReason) : ZstdStreamCaptureState
}
