package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import io.netty.buffer.ByteBuf
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Asynchronously persists encoded outbound packet copies in independently decompressible Zstd frames.
 * Recording is best-effort and never performs compression or disk I/O on a caller's event loop.
 */
internal class PacketCaptureRecorder @JvmOverloads constructor(
    private val directory: Path,
    private val limits: PacketCaptureLimits = PacketCaptureLimits.DEFAULT,
    private val nanoTime: () -> Long = System::nanoTime,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val afterReservation: () -> Unit = {},
    private val afterAdmissionClosed: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Object()
    private val startedNanos = nanoTime()
    val startedEpochMillis: Long = epochMillis()
    val runId: UUID = createVersion7(startedEpochMillis)
    private val nextConnectionId = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val queue = ArrayDeque<PacketCaptureRecord>()
    private var pendingBytes = 0L
    private var outstandingEvents = 0
    private var activeProducers = 0
    private var accepting = true
    private var failure: Throwable? = null
    private var callbackInvoked = false
    private var callbackPending: Throwable? = null
    private val writer = Thread(::writeLoop, "rdi-packet-capture-$runId").apply {
        isDaemon = true
        start()
    }

    val droppedSamples: Long
        get() = dropped.get()

    fun connection(playerId: UUID): PacketCaptureConnection =
        PacketCaptureConnection(this, playerId, nextPositiveId(nextConnectionId))

    internal fun accept(playerId: UUID, connectionId: Long, sequence: Long, bytes: ByteBuf, identity: ZstdPacketIdentity?, phase: PacketCapturePhase) {
        val readableBytes = try {
            bytes.readableBytes()
        } catch (error: Throwable) {
            incrementDropped()
            fail(error)
            return
        }
        val packetType = identity?.packetType ?: UNKNOWN_PACKET_TYPE
        val channel = identity?.let { value ->
            if (!value.namespace.isNullOrEmpty() && !value.path.isNullOrEmpty()) "${value.namespace}:${value.path}" else ""
        }.orEmpty()
        val packetTypeBytes = utf8Length(packetType)
        val channelBytes = utf8Length(channel)
        if (readableBytes < 0 || readableBytes > limits.maxPayloadBytes ||
            packetTypeBytes > limits.maxStringBytes || channelBytes > limits.maxStringBytes
        ) {
            incrementDropped()
            return
        }

        val encodedBytes = RECORD_FIXED_BYTES + packetTypeBytes + channelBytes + readableBytes
        synchronized(lock) {
            if (!accepting || failure != null || outstandingEvents >= limits.maxEvents ||
                pendingBytes + encodedBytes > limits.maxPendingBytes
            ) {
                incrementDropped()
                return
            }
            pendingBytes += encodedBytes
            outstandingEvents++
            activeProducers++
        }

        val payload = try {
            afterReservation()
            ByteArray(readableBytes).also { bytes.getBytes(bytes.readerIndex(), it) }
        } catch (error: Throwable) {
            synchronized(lock) {
                pendingBytes -= encodedBytes
                outstandingEvents--
                activeProducers--
                incrementDropped()
                failLocked(error)
                lock.notifyAll()
            }
            return
        }

        synchronized(lock) {
            activeProducers--
            if (failure == null) {
                queue.addLast(
                    PacketCaptureRecord(
                        elapsedNanos(), playerId, connectionId, sequence, phase,
                        packetType, channel, payload, encodedBytes,
                    ),
                )
            } else {
                pendingBytes -= encodedBytes
                outstandingEvents--
                incrementDropped()
            }
            lock.notifyAll()
        }
    }

    fun close(): Result<Unit> = runCatching {
        synchronized(lock) {
            accepting = false
            lock.notifyAll()
        }
        afterAdmissionClosed()
        try {
            writer.join(limits.closeTimeoutMillis)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
        if (writer.isAlive) {
            writer.interrupt()
            val timeoutFailure = IllegalStateException("Timed out while closing packet capture writer")
            fail(timeoutFailure)
            try {
                writer.join(min(limits.closeTimeoutMillis, 1000L))
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            throw timeoutFailure
        }
        failure?.let { throw it }
    }

    private fun writeLoop() {
        var fileWriter: PacketCaptureFileWriter? = null
        try {
            val activeWriter = PacketCaptureFileWriter(
                directory, runId, startedEpochMillis, limits, ::elapsedNanos, { droppedSamples },
                { count -> repeat(count) { incrementDropped() } },
                ::releaseWritten,
            )
            fileWriter = activeWriter
            while (true) {
                var notifyError: Throwable? = null
                val next = synchronized(lock) {
                    val deadline = System.nanoTime() + limits.idleFlushMillis * NANOS_PER_MILLI
                    while (queue.isEmpty() && (accepting || activeProducers > 0) && failure == null) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) break
                        lock.wait((remaining / NANOS_PER_MILLI).coerceAtLeast(1))
                    }
                    when {
                        failure != null -> {
                            notifyError = callbackPending
                            callbackPending = null
                            PacketCaptureFailed
                        }
                        queue.isNotEmpty() -> queue.removeFirst()
                        !accepting && activeProducers == 0 -> null
                        else -> PacketCaptureIdle
                    }
                }
                when (next) {
                    PacketCaptureFailed -> {
                        notifyFailure(notifyError)
                        break
                    }
                    null -> break
                    PacketCaptureIdle -> activeWriter.flushIfIdle()
                    else -> activeWriter.add(next as PacketCaptureRecord)
                }
                activeWriter.flushIfIdle()
            }
            if (failure == null) activeWriter.finish()
        } catch (error: Throwable) {
            fail(error)
            notifyPendingFailure()
        } finally {
            runCatching { fileWriter?.discardPending() }
            runCatching { fileWriter?.closePartial() }
            runCatching { fileWriter?.closeContext() }
        }
    }

    private fun fail(error: Throwable) {
        synchronized(lock) {
            failLocked(error)
            lock.notifyAll()
        }
    }

    private fun failLocked(error: Throwable) {
        if (failure != null) return
        failure = error
        accepting = false
        while (queue.isNotEmpty()) {
            val record = queue.removeFirst()
            pendingBytes -= record.reservedBytes
            outstandingEvents--
            incrementDropped()
        }
        if (!callbackInvoked) {
            callbackInvoked = true
            callbackPending = error
        }
    }

    private fun notifyPendingFailure() {
        val error = synchronized(lock) {
            callbackPending.also { callbackPending = null }
        }
        notifyFailure(error)
    }

    private fun notifyFailure(error: Throwable?) {
        if (error != null) runCatching { onFailure(error) }
    }

    private fun incrementDropped() {
        dropped.incrementAndGet()
    }

    private fun releaseWritten(bytes: Long, records: Int) {
        synchronized(lock) {
            pendingBytes -= bytes
            outstandingEvents -= records
            lock.notifyAll()
        }
    }

    private fun elapsedNanos(): Long = (nanoTime() - startedNanos).coerceAtLeast(0L)

    private data object PacketCaptureIdle
    private data object PacketCaptureFailed

    companion object {
        const val MAX_FILE_BYTES: Long = 128_000_000L
        const val MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024
        const val MAX_STRING_BYTES: Int = 4096
        const val DEFAULT_MAX_PENDING_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_MAX_EVENTS: Int = 32768
        const val TARGET_FRAME_BYTES: Int = 1024 * 1024
        const val FORMAT_VERSION = 2
        internal const val RECORD_FIXED_BYTES = 49L
        private const val UNKNOWN_PACKET_TYPE = ":unknown-encoded"
        private const val NANOS_PER_MILLI = 1_000_000L

        private fun nextPositiveId(counter: AtomicLong): Long {
            val result = counter.incrementAndGet()
            check(result > 0) { "Packet capture identifier exhausted" }
            return result
        }

        private fun utf8Length(value: String): Int {
            var length = 0L
            var index = 0
            while (index < value.length) {
                val char = value[index]
                length += when {
                    char.code <= 0x7f -> 1
                    char.code <= 0x7ff -> 2
                    Character.isHighSurrogate(char) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) -> {
                        index++
                        4
                    }
                    else -> 3
                }
                if (length > Int.MAX_VALUE) return Int.MAX_VALUE
                index++
            }
            return length.toInt()
        }

        private fun createVersion7(epochMillis: Long): UUID {
            val random = SecureRandom().nextLong()
            val timestamp = epochMillis and 0x0000ffffffffffffL
            val most = (timestamp shl 16) or 0x7000L or ((random ushr 52) and 0x0fffL)
            val least = (random and 0x3fffffffffffffffL) or Long.MIN_VALUE
            return UUID(most, least)
        }
    }
}

private data class PacketCaptureRecord(
    val elapsedNanos: Long,
    val playerId: UUID,
    val connectionId: Long,
    val sequence: Long,
    val phase: PacketCapturePhase,
    val packetType: String,
    val channel: String,
    val payload: ByteArray,
    val reservedBytes: Long,
)

/** Test-adjustable resource limits. Production defaults match docs/packbatch-format.md. */
internal data class PacketCaptureLimits(
    val maxFileBytes: Long = PacketCaptureRecorder.MAX_FILE_BYTES,
    val maxPendingBytes: Long = PacketCaptureRecorder.DEFAULT_MAX_PENDING_BYTES,
    val maxEvents: Int = PacketCaptureRecorder.DEFAULT_MAX_EVENTS,
    val targetFrameBytes: Int = PacketCaptureRecorder.TARGET_FRAME_BYTES,
    val maxPayloadBytes: Int = PacketCaptureRecorder.MAX_PAYLOAD_BYTES,
    val maxStringBytes: Int = PacketCaptureRecorder.MAX_STRING_BYTES,
    val idleFlushMillis: Long = 1000,
    val closeTimeoutMillis: Long = 10_000,
) {
    init {
        require(maxFileBytes in MIN_TEST_FILE_BYTES..PacketCaptureRecorder.MAX_FILE_BYTES)
        require(maxPendingBytes in 1..PacketCaptureRecorder.DEFAULT_MAX_PENDING_BYTES)
        require(maxEvents in 1..PacketCaptureRecorder.DEFAULT_MAX_EVENTS)
        require(targetFrameBytes in 1..PacketCaptureRecorder.TARGET_FRAME_BYTES)
        require(maxPayloadBytes in 0..PacketCaptureRecorder.MAX_PAYLOAD_BYTES)
        require(maxStringBytes in 1..PacketCaptureRecorder.MAX_STRING_BYTES)
        require(idleFlushMillis > 0)
        require(closeTimeoutMillis > 0)
    }

    companion object {
        private const val MIN_TEST_FILE_BYTES = 128L
        val DEFAULT = PacketCaptureLimits()
    }
}

internal enum class PacketCapturePhase(val wire: Int) { Play(0), Configuration(1) }

internal class PacketCaptureConnection internal constructor(
    private val recorder: PacketCaptureRecorder,
    private val playerId: UUID,
    val connectionId: Long,
) {
    private val sequence = AtomicLong(0)

    fun record(bytes: ByteBuf, identity: ZstdPacketIdentity?, phase: PacketCapturePhase = PacketCapturePhase.Play) {
        val currentSequence = sequence.incrementAndGet()
        if (currentSequence <= 0) return
        recorder.accept(playerId, connectionId, currentSequence, bytes, identity, phase)
    }
}

private class PacketCaptureFileWriter(
    private val directory: Path,
    private val runId: UUID,
    private val startedEpochMillis: Long,
    private val limits: PacketCaptureLimits,
    private val elapsedNanos: () -> Long,
    private val droppedSamples: () -> Long,
    private val addDropped: (Int) -> Unit,
    private val releaseWritten: (Long, Int) -> Unit,
) {
    private val compression = ZstdCompressCtx()
        .setLevel(3)
        .setMagicless(false)
        .setChecksum(true)
        .setDictID(false)
        .setContentSize(true)
    private val pendingRecords = ArrayList<PacketCaptureRecord>()
    private var pendingBodyBytes = DATA_PREFIX_BYTES
    private var partNumber = 0
    private var partRecords = 0L
    private var fileBytes = 0L
    private var partialPath: Path? = null
    private var finalPath: Path? = null
    private var output: DataOutputStream? = null
    private var lastFlushNanos = System.nanoTime()

    fun add(record: PacketCaptureRecord) {
        try {
            val recordSize = record.serializedSize()
            if (pendingRecords.isNotEmpty() && pendingBodyBytes + recordSize > limits.targetFrameBytes) flushPending()
            pendingRecords.add(record)
            pendingBodyBytes += recordSize
            if (pendingBodyBytes >= limits.targetFrameBytes || record.payload.size > limits.targetFrameBytes) flushPending()
        } catch (error: Throwable) {
            if (pendingRecords.none { it === record }) {
                releaseWritten(record.reservedBytes, 1)
                addDropped(1)
            }
            throw error
        }
    }

    fun flushIfIdle() {
        if (pendingRecords.isNotEmpty() && System.nanoTime() - lastFlushNanos >= limits.idleFlushMillis * 1_000_000L) {
            flushPending()
        }
    }

    fun flushPending() {
        if (pendingRecords.isEmpty()) return
        val body = encodeData(pendingRecords)
        val compressed = compress(body)
        ensurePart()
        if (!fits(compressed.size) && partRecords > 0) {
            finishPart(final = false)
            ensurePart()
        }
        if (!fits(compressed.size)) {
            writeIndividually()
            return
        }
        writeFrame(compressed, pendingRecords.size)
        releaseRecords(pendingRecords.size)
        pendingRecords.clear()
        pendingBodyBytes = DATA_PREFIX_BYTES
        lastFlushNanos = System.nanoTime()
    }

    fun discardPending() {
        if (pendingRecords.isEmpty()) return
        val droppedCount = pendingRecords.size
        releaseRecords(droppedCount)
        pendingRecords.clear()
        pendingBodyBytes = DATA_PREFIX_BYTES
        addDropped(droppedCount)
    }

    private fun writeIndividually() {
        while (pendingRecords.isNotEmpty()) {
            val record = pendingRecords.first()
            val compressed = compress(encodeData(listOf(record)))
            if (!fits(compressed.size) && partRecords > 0) {
                finishPart(final = false)
                ensurePart()
            }
            if (!fits(compressed.size)) {
                releaseWritten(record.reservedBytes, 1)
                addDropped(1)
            } else {
                writeFrame(compressed, 1)
                releaseWritten(record.reservedBytes, 1)
            }
            pendingRecords.removeAt(0)
            pendingBodyBytes -= record.serializedSize()
        }
        pendingBodyBytes = DATA_PREFIX_BYTES
        lastFlushNanos = System.nanoTime()
    }

    private fun writeFrame(compressed: ByteArray, recordCount: Int) {
        output!!.write(compressed)
        output!!.flush()
        fileBytes += compressed.size
        partRecords += recordCount
    }

    fun finish() {
        flushPending()
        if (output == null) ensurePart()
        finishPart(final = true)
    }

    fun closePartial() {
        runCatching { output?.flush() }
        runCatching { output?.close() }
        output = null
    }

    fun closeContext() = compression.close()

    private fun ensurePart() {
        if (output != null) return
        Files.createDirectories(directory)
        partNumber++
        val basename = "${runId}-${partNumber.toString().padStart(6, '0')}.rdibatch.zst"
        partialPath = directory.resolve("$basename.partial")
        finalPath = directory.resolve(basename)
        output = DataOutputStream(
            BufferedOutputStream(
                Files.newOutputStream(partialPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            ),
        )
        partRecords = 0
        val header = encodeHeader(partNumber)
        val compressedHeader = compress(header)
        if (compressedHeader.size + footerBound() > limits.maxFileBytes) {
            closePartial()
            throw IllegalStateException("Packet capture header and footer exceed the configured file limit")
        }
        output!!.write(compressedHeader)
        fileBytes = compressedHeader.size.toLong()
        if (!fits(0)) throw IllegalStateException("Packet capture header leaves no room for a footer")
    }

    private fun finishPart(final: Boolean) {
        val stream = output ?: return
        val footer = encodeFooter(partRecords, droppedSamples(), final, elapsedNanos())
        val compressedFooter = compress(footer)
        if (fileBytes + compressedFooter.size > limits.maxFileBytes) {
            throw IllegalStateException("Packet capture footer would exceed the configured file limit")
        }
        stream.write(compressedFooter)
        stream.flush()
        stream.close()
        output = null
        val from = checkNotNull(partialPath)
        val to = checkNotNull(finalPath)
        Files.move(from, to)
        fileBytes = 0
    }

    private fun fits(compressedDataBytes: Int): Boolean =
        fileBytes + compressedDataBytes + footerBound() <= limits.maxFileBytes

    private fun footerBound(): Long = Zstd.compressBound(FOOTER_BODY_BYTES.toLong())

    private fun compress(body: ByteArray): ByteArray = compression.compress(body)

    private fun releaseRecords(count: Int) {
        var bytes = 0L
        repeat(count) { index ->
            // The sum is computed before records are cleared; use the removed prefix for both actions.
            bytes += pendingRecords[index].reservedBytes
        }
        releaseWritten(bytes, count)
    }

    private fun encodeHeader(part: Int): ByteArray = frameBody { output ->
        output.writeByte(HEADER_KIND)
        output.write(byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte()))
        output.writeShort(PacketCaptureRecorder.FORMAT_VERSION)
        output.writeUuid(runId)
        output.writeLong(startedEpochMillis)
        output.writeInt(part)
    }

    private fun encodeFooter(records: Long, drops: Long, final: Boolean, elapsed: Long): ByteArray = frameBody { output ->
        output.writeByte(FOOTER_KIND)
        output.writeLong(records)
        output.writeLong(drops)
        output.writeByte(if (final) 1 else 0)
        output.writeLong(elapsed)
    }

    private fun encodeData(records: List<PacketCaptureRecord>): ByteArray = frameBody { output ->
        output.writeByte(DATA_KIND)
        output.writeInt(records.size)
        records.forEach { record ->
            output.writeLong(record.elapsedNanos)
            output.writeUuid(record.playerId)
            output.writeLong(record.connectionId)
            output.writeLong(record.sequence)
            output.writeByte(record.phase.wire)
            output.writeString(record.packetType)
            output.writeString(record.channel)
            output.writeInt(record.payload.size)
            output.write(record.payload)
        }
    }

    private fun frameBody(write: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(write)
        return buffer.toByteArray()
    }

    private fun DataOutputStream.writeUuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun PacketCaptureRecord.serializedSize(): Int =
        (PacketCaptureRecorder.RECORD_FIXED_BYTES +
            packetType.toByteArray(StandardCharsets.UTF_8).size +
            channel.toByteArray(StandardCharsets.UTF_8).size + payload.size).toInt()

    companion object {
        private const val HEADER_KIND = 1
        private const val DATA_KIND = 2
        private const val FOOTER_KIND = 3
        private const val DATA_PREFIX_BYTES = 5
        private const val FOOTER_BODY_BYTES = 26
    }
}
