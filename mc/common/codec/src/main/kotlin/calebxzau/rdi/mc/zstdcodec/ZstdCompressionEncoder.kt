package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.EncoderException
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.ScheduledFuture
import java.nio.channels.ClosedChannelException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Encodes legacy packets or batches selectively on one connection's event loop. */
internal class ZstdCompressionEncoder(
    private var threshold: Int,
    private val varIntCodec: MinecraftVarIntCodec,
    private val flushTimeoutMillis: Long? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : ChannelDuplexHandler() {
    private val compressionContext = ZstdCompressCtx()
        .setLevel(COMPRESSION_LEVEL)
        .setMagicless(false)
        .setChecksum(false)
        .setDictID(false)
        .setContentSize(false)

    private val buffered = ArrayDeque<BufferedRecord>()
    private var bufferedPayloadBytes = 0
    private var tickCount = 0L
    private var flushTimeout: ScheduledFuture<*>? = null
    private var handlerContext: ChannelHandlerContext? = null
    @Volatile
    private var batchingEnabled = false
    private var batchTargetBytes = ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES
    private var observer: ZstdBatchObserver? = null
    private var failed: Throwable? = null

    private data class BufferedRecord(
        val sending: ZstdSendingRecord,
        val promise: ChannelPromise,
        val enqueuedNanos: Long,
        val enqueuedTick: Long,
    ) {
        val content: ByteBuf get() = sending.content
        val identity: ZstdPacketIdentity? get() = sending.identity
        val policy: ZstdBatchPolicy get() = sending.policy
        val recordBytes: Int = sending.content.readableBytes()
        val payloadBytes: Int get() = ZstdBatchFormat.varIntSize(recordBytes) + recordBytes
        val tickDeadline: Long get() = enqueuedTick + policy.tickBudget
        val timeDeadlineNanos: Long get() = enqueuedNanos + TimeUnit.MILLISECONDS.toNanos(policy.timeBudgetMillis)
    }

    private data class EncodedFrame(
        val buffer: ByteBuf,
        val frameKind: ZstdBatchFrameKind,
        val raw: Boolean,
        val compressionNanos: Long = 0L,
    )

    override fun handlerAdded(context: ChannelHandlerContext) {
        handlerContext = context
    }

    override fun write(context: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        if (failed != null) {
            releaseMessage(msg)
            promise.tryFailure(failed!!)
            return
        }

        if (msg !is ByteBuf && msg !is ZstdSendingRecord) {
            flushBuffered(context, ZstdBatchFlushReason.BARRIER)
            if (failed != null) {
                ReferenceCountUtil.release(msg)
                promise.tryFailure(failed!!)
                return
            }
            context.write(msg, promise)
            context.flush()
            return
        }

        val sending = when (msg) {
            is ZstdSendingRecord -> msg
            is ByteBuf -> ZstdSendingRecord(msg, ZstdBatchPolicy.OneTick, null)
            else -> error("unreachable")
        }
        flushExpiredByClock(context)
        if (failed != null) {
            sending.release()
            promise.tryFailure(failed!!)
            return
        }
        val encodedBytes = sending.content.readableBytes()
        observe { it.recordEncoded(sending.identity, encodedBytes, sending.policy) }

        if (!batchingEnabled || sending.policy == ZstdBatchPolicy.Immediate ||
            encodedBytes == 0 || encodedBytes > MAXIMUM_BATCHED_RECORD_BYTES
        ) {
            if (batchingEnabled) flushBuffered(context, ZstdBatchFlushReason.BARRIER)
            if (failed != null) {
                sending.release()
                promise.tryFailure(failed!!)
                return
            }
            writeLegacy(context, sending, promise)
            return
        }

        val record = BufferedRecord(sending, promise, nanoTime(), tickCount)
        val recordBytes = record.payloadBytes
        if (buffered.isNotEmpty() && (
                bufferedPayloadBytes + recordBytes > batchTargetBytes ||
                    buffered.size >= ZstdBatchFormat.MAXIMUM_RECORDS
                )
        ) {
            flushBuffered(context, ZstdBatchFlushReason.SIZE)
            if (failed != null) {
                failRecord(record, failed!!)
                return
            }
        }

        buffered.addLast(record)
        bufferedPayloadBytes += recordBytes
        observe { it.recordBuffered(encodedBytes) }
        if (bufferedPayloadBytes >= batchTargetBytes || buffered.size >= ZstdBatchFormat.MAXIMUM_RECORDS) {
            flushBuffered(context, ZstdBatchFlushReason.SIZE)
        } else {
            scheduleDeadline(context)
        }
    }

    override fun flush(context: ChannelHandlerContext) {
        if (!batchingEnabled || buffered.isEmpty()) context.flush()
    }

    override fun close(context: ChannelHandlerContext, promise: ChannelPromise) {
        flushBuffered(context, ZstdBatchFlushReason.SHUTDOWN)
        context.close(promise)
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        discardBuffered(ClosedChannelException())
        context.fireChannelInactive()
    }

    override fun handlerRemoved(context: ChannelHandlerContext) {
        flushBuffered(context, ZstdBatchFlushReason.SHUTDOWN)
        if (buffered.isNotEmpty()) discardBuffered(failed ?: ClosedChannelException())
        compressionContext.close()
        handlerContext = null
        super.handlerRemoved(context)
    }

    fun updateThreshold(threshold: Int) = onEventLoop { context ->
        flushBuffered(context, ZstdBatchFlushReason.BARRIER)
        this.threshold = threshold
    }

    fun isBatchingEnabled(): Boolean = batchingEnabled

    fun requestBatching(enabled: Boolean, targetBytes: Int) = onEventLoop { context ->
        if (!enabled || targetBytes != batchTargetBytes) flushBuffered(context, ZstdBatchFlushReason.BARRIER)
        batchTargetBytes = targetBytes.coerceIn(
            ZstdCompressionPipeline.MINIMUM_BATCH_TARGET_BYTES,
            ZstdCompressionPipeline.MAXIMUM_BATCH_TARGET_BYTES,
        )
        batchingEnabled = enabled
    }

    fun requestObserver(observer: ZstdBatchObserver?) = onEventLoop { this.observer = observer }

    fun requestTickEnd() = onEventLoop { context ->
        tickCount++
        if (isTimeDeadlineDue()) {
            flushBuffered(context, ZstdBatchFlushReason.TIMEOUT)
            return@onEventLoop
        }
        val due = buffered.any { it.tickDeadline <= tickCount }
        if (due) flushBuffered(context, ZstdBatchFlushReason.TICK) else scheduleDeadline(context)
    }

    fun requestFlush(reason: ZstdBatchFlushReason) = onEventLoop { context ->
        flushBuffered(context, reason)
    }

    fun releaseBuffered() = onEventLoop { context ->
        flushBuffered(context, ZstdBatchFlushReason.BARRIER)
    }

    private fun onEventLoop(action: (ChannelHandlerContext) -> Unit) {
        val context = handlerContext ?: return
        val task = Runnable {
            if (handlerContext !== context) return@Runnable
            if (context.channel().isActive || context.channel().isOpen) action(context)
            else discardBuffered(ClosedChannelException())
        }
        if (context.executor().inEventLoop()) task.run() else context.executor().execute(task)
    }

    private fun scheduleDeadline(context: ChannelHandlerContext) {
        cancelFlushTimeout()
        val first = buffered.minOfOrNull { it.timeDeadlineNanos } ?: return
        val remaining = (first - nanoTime()).coerceAtLeast(0L)
        val delay = flushTimeoutMillis?.let { TimeUnit.MILLISECONDS.toNanos(it).coerceAtLeast(0L) } ?: remaining
        flushTimeout = context.executor().schedule({
            if (buffered.isNotEmpty()) flushBuffered(context, ZstdBatchFlushReason.TIMEOUT)
        }, delay, TimeUnit.NANOSECONDS)
    }

    private fun isTimeDeadlineDue(): Boolean {
        val deadline = buffered.minOfOrNull { it.timeDeadlineNanos } ?: return false
        return deadline <= nanoTime()
    }

    private fun flushExpiredByClock(context: ChannelHandlerContext) {
        if (isTimeDeadlineDue()) flushBuffered(context, ZstdBatchFlushReason.TIMEOUT)
    }

    private fun cancelFlushTimeout() {
        flushTimeout?.cancel(false)
        flushTimeout = null
    }

    private fun flushBuffered(context: ChannelHandlerContext, reason: ZstdBatchFlushReason) {
        if (buffered.isEmpty()) {
            cancelFlushTimeout()
            return
        }
        cancelFlushTimeout()
        val records = ArrayList<BufferedRecord>(buffered)
        val payloadBytes = bufferedPayloadBytes
        val now = nanoTime()
        buffered.clear()
        bufferedPayloadBytes = 0

        try {
            val startedNanos = nanoTime()
            val frames = buildFrames(context, records, payloadBytes)
            records.forEach { it.sending.release() }
            val compressionNanos = (nanoTime() - startedNanos).coerceAtLeast(0L)
            val waitNanos = records.maxOfOrNull { (now - it.enqueuedNanos).coerceAtLeast(0L) } ?: 0L
            writeFrames(context, records, frames, reason, waitNanos, compressionNanos)
        } catch (error: Throwable) {
            records.forEach { record ->
                if (record.sending.refCnt() > 0) failRecord(record, error)
                else record.promise.tryFailure(error)
            }
            notifyEncodingFailed(records.size)
            failConnection(context, error)
        }
    }

    private fun buildFrames(
        context: ChannelHandlerContext,
        records: List<BufferedRecord>,
        payloadBytes: Int,
    ): List<EncodedFrame> {
        if (records.size == 1) {
            return listOf(encodeLegacyFrame(context, records.single().content))
        }

        if (payloadBytes < threshold && rawBatchEncodedCost(records, payloadBytes) >= legacyRawEncodedCost(records)) {
            val frames = ArrayList<EncodedFrame>(records.size)
            try {
                records.forEach { record ->
                    val started = nanoTime()
                    val frame = encodeLegacyFrame(context, record.content)
                    frames.add(frame.copy(compressionNanos = (nanoTime() - started).coerceAtLeast(0L)))
                }
                return frames
            } catch (error: Throwable) {
                frames.forEach { it.buffer.release() }
                throw error
            }
        }

        return listOf(buildBatchFrame(context, records, payloadBytes, raw = payloadBytes < threshold))
    }

    private fun rawBatchEncodedCost(records: List<BufferedRecord>, payloadBytes: Int): Int {
        val headerBytes = ZstdBatchFormat.varIntSize(ZstdBatchFormat.MARKER) +
            ZstdBatchFormat.varIntSize(ZstdBatchFormat.VERSION) + 1 +
            ZstdBatchFormat.varIntSize(payloadBytes) + ZstdBatchFormat.varIntSize(records.size)
        val blockBytes = headerBytes + payloadBytes
        return ZstdBatchFormat.varIntSize(blockBytes) + blockBytes
    }

    private fun legacyRawEncodedCost(records: List<BufferedRecord>): Int = records.sumOf { record ->
        val innerBytes = 1 + record.recordBytes
        ZstdBatchFormat.varIntSize(innerBytes) + innerBytes
    }

    private fun buildBatchFrame(
        context: ChannelHandlerContext,
        records: List<BufferedRecord>,
        payloadBytes: Int,
        raw: Boolean,
    ): EncodedFrame {
        val payload = context.alloc().directBuffer(payloadBytes, payloadBytes)
        try {
            records.forEach { record ->
                varIntCodec.write(payload, record.recordBytes)
                payload.writeBytes(record.content, record.content.readerIndex(), record.recordBytes)
            }
            val header = context.alloc().buffer(ZstdBatchFormat.MAXIMUM_HEADER_BYTES, ZstdBatchFormat.MAXIMUM_HEADER_BYTES)
            try {
                varIntCodec.write(header, ZstdBatchFormat.MARKER)
                varIntCodec.write(header, ZstdBatchFormat.VERSION)
                header.writeByte(if (raw) ZstdBatchFormat.FLAG_RAW_PAYLOAD else 0)
                varIntCodec.write(header, payloadBytes)
                varIntCodec.write(header, records.size)
                val headerBytes = header.readableBytes()
                val capacity = headerBytes + if (raw) payloadBytes else Zstd.compressBound(payloadBytes.toLong()).toInt()
                val block = context.alloc().directBuffer(capacity, capacity)
                var written = false
                try {
                    block.writeBytes(header, header.readerIndex(), headerBytes)
                    if (raw) {
                        block.writeBytes(payload, payload.readerIndex(), payloadBytes)
                    } else {
                        val destination = block.nioBuffer(block.writerIndex(), capacity - block.writerIndex())
                        val source = payload.nioBuffer(payload.readerIndex(), payloadBytes)
                        val compressedSize = compressionContext.compressDirectByteBuffer(
                            destination, destination.position(), destination.remaining(), source, source.position(), payloadBytes,
                        )
                        block.writerIndex(block.writerIndex() + compressedSize)
                    }
                    written = true
                    return EncodedFrame(block, if (raw) ZstdBatchFrameKind.RawBatch else ZstdBatchFrameKind.ZstdBatch, raw)
                } finally {
                    if (!written) block.release()
                }
            } finally {
                header.release()
            }
        } finally {
            payload.release()
        }
    }

    private fun writeFrames(
        context: ChannelHandlerContext,
        records: List<BufferedRecord>,
        frames: List<EncodedFrame>,
        reason: ZstdBatchFlushReason,
        waitNanos: Long,
        compressionNanos: Long,
    ) {
        if (frames.size == 1) {
            val frame = frames.single()
            val samplePayloadBytes = if (frame.frameKind == ZstdBatchFrameKind.Legacy) {
                records.sumOf { it.recordBytes }
            } else {
                bufferedPayloadSize(records)
            }
            val sample = sample(records.size, samplePayloadBytes, frame, reason, waitNanos, compressionNanos,
                singleRecordFallback = records.size == 1,
                bufferedFlush = true,
                firstFrameOfFlush = true)
            val downstream = context.newPromise()
            downstream.addListener { future ->
                completeRecords(records, future.isSuccess, future.cause())
                observe { it.writeCompleted(sample, future.isSuccess) }
                if (!future.isSuccess) failConnection(context, future.cause() ?: ClosedChannelException())
            }
            observe { it.batchFlushed(sample) }
            context.write(frame.buffer, downstream)
            context.flush()
            return
        }

        val samples = frames.mapIndexed { index, frame ->
            val record = records[index]
            sample(
                1,
                record.recordBytes,
                frame,
                reason,
                waitNanos,
                frame.compressionNanos,
                singleRecordFallback = false,
                bufferedFlush = true,
                firstFrameOfFlush = index == 0,
            )
        }
        var handoffComplete = false
        val pendingCompletions = ArrayList<Pair<Int, io.netty.util.concurrent.Future<*>>>()
        fun complete(index: Int, future: io.netty.util.concurrent.Future<*>) {
            val record = records[index]
            val sample = samples[index]
            if (future.isSuccess) record.promise.trySuccess()
            else record.promise.tryFailure(future.cause() ?: ClosedChannelException())
            observe { it.writeCompleted(sample, future.isSuccess) }
            if (!future.isSuccess) failConnection(context, future.cause() ?: ClosedChannelException())
        }

        var nextFrame = 0
        try {
            frames.forEachIndexed { index, frame ->
                nextFrame = index
                val sample = samples[index]
                val downstream = context.newPromise()
                downstream.addListener { future ->
                    if (handoffComplete) complete(index, future)
                    else pendingCompletions.add(index to future)
                }
                observe { it.batchFlushed(sample) }
                context.write(frame.buffer, downstream)
                nextFrame = index + 1
            }
            context.flush()
        } catch (error: Throwable) {
            for (index in nextFrame until frames.size) frames[index].buffer.release()
            records.forEach { it.promise.tryFailure(error) }
            failConnection(context, error)
        } finally {
            handoffComplete = true
            pendingCompletions.forEach { (index, future) -> complete(index, future) }
            pendingCompletions.clear()
        }
    }

    private fun sample(
        recordCount: Int,
        payloadBytes: Int,
        frame: EncodedFrame,
        reason: ZstdBatchFlushReason,
        waitNanos: Long,
        compressionNanos: Long,
        singleRecordFallback: Boolean,
        bufferedFlush: Boolean = false,
        firstFrameOfFlush: Boolean = false,
    ): ZstdBatchSample {
        val bytes = frame.buffer.readableBytes()
        val outerPrefix = ZstdBatchFormat.varIntSize(bytes)
        val uncompressedInnerBytes = when (frame.frameKind) {
            ZstdBatchFrameKind.Legacy -> {
                val legacyHeader = if (frame.raw) 1 else ZstdBatchFormat.varIntSize(payloadBytes)
                legacyHeader + payloadBytes
            }
            ZstdBatchFrameKind.RawBatch, ZstdBatchFrameKind.ZstdBatch -> {
                val headerBytes = ZstdBatchFormat.varIntSize(ZstdBatchFormat.MARKER) +
                    ZstdBatchFormat.varIntSize(ZstdBatchFormat.VERSION) + 1 +
                    ZstdBatchFormat.varIntSize(payloadBytes) + ZstdBatchFormat.varIntSize(recordCount)
                headerBytes + payloadBytes
            }
        }
        val uncompressedBytes = uncompressedInnerBytes + outerPrefix
        return ZstdBatchSample(
            recordCount = recordCount,
            payloadBytes = payloadBytes,
            blockBytes = bytes,
            raw = frame.raw,
            flushReason = reason,
            waitNanos = waitNanos,
            compressionNanos = compressionNanos,
            frameKind = frame.frameKind,
            outerPrefixBytes = outerPrefix,
            singleRecordFallback = singleRecordFallback,
            noCompressionBytes = uncompressedBytes,
            compressedBytes = if (frame.raw) 0 else bytes + outerPrefix,
            fallbackBytes = if (singleRecordFallback) bytes + outerPrefix else 0,
            bufferedFlush = bufferedFlush,
            firstFrameOfFlush = firstFrameOfFlush,
        )
    }

    private fun bufferedPayloadSize(records: List<BufferedRecord>): Int = records.sumOf { it.payloadBytes }

    private fun writeLegacy(context: ChannelHandlerContext, sending: ZstdSendingRecord, promise: ChannelPromise) {
        val recordBytes = sending.content.readableBytes()
        val started = nanoTime()
        val frame: EncodedFrame
        try {
            frame = encodeLegacyFrame(context, sending.content)
        } catch (error: Throwable) {
            sending.release()
            promise.tryFailure(error)
            notifyEncodingFailed(1)
            failConnection(context, error)
            return
        }
        sending.release()
        val sample = sample(
            recordCount = 1,
            payloadBytes = recordBytes,
            frame = frame,
            reason = ZstdBatchFlushReason.BARRIER,
            waitNanos = 0,
            compressionNanos = (nanoTime() - started).coerceAtLeast(0L),
            singleRecordFallback = false,
        )
        val downstream = context.newPromise()
        downstream.addListener { future ->
            if (future.isSuccess) promise.trySuccess() else promise.tryFailure(future.cause() ?: ClosedChannelException())
            observe { it.writeCompleted(sample, future.isSuccess) }
            if (!future.isSuccess) failConnection(context, future.cause() ?: ClosedChannelException())
        }
        observe { it.batchFlushed(sample) }
        context.write(frame.buffer, downstream)
        if (batchingEnabled) context.flush()
    }

    private fun encodeLegacyFrame(context: ChannelHandlerContext, input: ByteBuf): EncodedFrame {
        val size = input.readableBytes()
        if (size > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
            throw EncoderException("Packet too big (is $size, should be less than or equal to ${ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH})")
        }
        if (size == 0 || size < threshold) {
            val raw = context.alloc().directBuffer(size + 1, size + 1)
            var written = false
            try {
                varIntCodec.write(raw, 0)
                raw.writeBytes(input, input.readerIndex(), size)
                written = true
                return EncodedFrame(raw, ZstdBatchFrameKind.Legacy, raw = true)
            } finally {
                if (!written) raw.release()
            }
        }
        return EncodedFrame(compressLegacy(context, input, size), ZstdBatchFrameKind.Legacy, raw = false)
    }

    private fun compressLegacy(context: ChannelHandlerContext, input: ByteBuf, size: Int): ByteBuf {
        val compressedCapacity = Zstd.compressBound(size.toLong()).toInt()
        val capacity = ZstdBatchFormat.varIntSize(size) + compressedCapacity
        val output = context.alloc().directBuffer(capacity, capacity)
        var written = false
        var scratch: ByteBuf? = null
        try {
            if (!input.isDirect || input.nioBufferCount() != 1) scratch = context.alloc().directBuffer(size, size)
            varIntCodec.write(output, size)
            scratch?.writeBytes(input, input.readerIndex(), size)
            val source = scratch?.nioBuffer(scratch.readerIndex(), size) ?: input.nioBuffer(input.readerIndex(), size)
            val destination = output.nioBuffer(output.writerIndex(), compressedCapacity)
            val compressedSize = compressionContext.compressDirectByteBuffer(
                destination, destination.position(), destination.remaining(), source, source.position(), size,
            )
            output.writerIndex(output.writerIndex() + compressedSize)
            written = true
            return output
        } finally {
            scratch?.release()
            if (!written) output.release()
        }
    }

    private fun completeRecords(records: List<BufferedRecord>, success: Boolean, cause: Throwable?) {
        records.forEach { record ->
            if (success) record.promise.trySuccess()
            else record.promise.tryFailure(cause ?: ClosedChannelException())
        }
    }

    private fun failRecord(record: BufferedRecord, error: Throwable) {
        record.sending.release()
        record.promise.tryFailure(error)
    }

    private fun discardBuffered(error: Throwable) {
        cancelFlushTimeout()
        while (buffered.isNotEmpty()) failRecord(buffered.removeFirst(), error)
        bufferedPayloadBytes = 0
    }

    private fun failConnection(context: ChannelHandlerContext, error: Throwable) {
        failed = error
        discardBuffered(error)
        runCatching { context.fireExceptionCaught(error) }
        context.close()
    }

    private inline fun observe(callback: (ZstdBatchObserver) -> Unit) {
        val current = observer ?: return
        try {
            callback(current)
        } catch (error: Throwable) {
            LOGGER.log(System.Logger.Level.ERROR, "Zstd batch telemetry failed", error)
        }
    }

    private fun notifyEncodingFailed(recordCount: Int) = observe { it.encodingFailed(recordCount) }

    private fun releaseMessage(msg: Any) = ReferenceCountUtil.release(msg)

    companion object {
        private val LOGGER: System.Logger = System.getLogger(ZstdCompressionEncoder::class.java.name)
        private const val COMPRESSION_LEVEL = 3
        private const val MAXIMUM_BATCHED_RECORD_BYTES = ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH - 5
    }
}
