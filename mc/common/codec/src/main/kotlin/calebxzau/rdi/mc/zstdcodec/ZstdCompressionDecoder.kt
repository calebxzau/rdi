package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDecompressCtx
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.DecoderException
import java.nio.ByteBuffer

/**
 * RDI's Zstd replacement for Minecraft's compression decoder.
 *
 * The handler reads the legacy per-packet envelope and, on a connection that negotiated batching,
 * the batch block that carries several records. Every record leaves as its own message, so the
 * vanilla packet decoder receives exactly one packet per message.
 *
 * With the packet reference extension the handler also restores referenced packets. It accepts
 * START only after the client prepared for it, and records every plain packet from START onwards.
 *
 * With the Zstd stream extension it likewise accepts STREAM_START only after the client prepared for
 * it, and from then on decodes every compressed payload as the next segment of one stream.
 *
 * With the persistent packet extension it restores packets the client kept from earlier joins, once a
 * [PersistentPacketClientSession] is attached to the channel.
 *
 * With the zlib tail extension it deflates the inflated field of a packet again, see [ZlibTailFormat].
 */
internal class ZstdCompressionDecoder(
    private var threshold: Int,
    private var validateDecompressed: Boolean,
    private val varIntCodec: MinecraftVarIntCodec,
) : ByteToMessageDecoder() {
    private val decompressionContext = ZstdDecompressCtx()
    private var handlerContext: ChannelHandlerContext? = null
    @Volatile
    private var batchingEnabled = false
    private var packetRefState = ExtensionState.Idle

    /** The client table of the packet reference extension; non-null exactly in [ExtensionState.Active]. */
    private var packetRefs: PacketRefCache? = null

    private var streamState = ExtensionState.Idle

    /** The connection's Zstd stream; non-null exactly in [ExtensionState.Active], see [ZstdStreamFormat]. */
    private var streamContext: ZstdDecompressCtx? = null

    @Volatile
    private var zlibTailReady = false

    private enum class ExtensionState { Idle, Ready, Active }

    override fun handlerAdded(context: ChannelHandlerContext) {
        handlerContext = context
    }

    override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        ZstdTransportGuard.existing(context.channel())?.checkHealthy()
        if (!input.isReadable) {
            return
        }

        val declaredSize = varIntCodec.read(input)
        if (declaredSize == ZstdBatchFormat.MARKER) {
            decodeBatchBlock(context, input, output)
            return
        }
        // Below threshold 2 the marker is also a legal compressed size until START arrives.
        if (declaredSize == PacketRefFormat.REFERENCE_MARKER &&
            (packetRefState == ExtensionState.Active || threshold > PacketRefFormat.REFERENCE_MARKER)
        ) {
            consumeOnFailure(input) { decodeReference(context, input, output) }
            return
        }
        if (declaredSize == PacketRefFormat.CONTROL_MARKER) {
            consumeOnFailure(input) { decodeControl(input) }
            return
        }
        if (declaredSize == PersistentPacketFormat.MARKER) {
            consumeOnFailure(input) { decodePersistent(context, input, output) }
            return
        }
        if (declaredSize == ZlibTailFormat.MARKER) {
            consumeOnFailure(input) { decodeZlibTail(context, input, output) }
            return
        }

        if (declaredSize == 0) {
            val rawSize = input.readableBytes()
            if (rawSize > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw DecoderException(
                    "Uncompressed packet size of $rawSize is larger than protocol maximum of ${ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH}"
                )
            }
            emit(input.readRetainedSlice(input.readableBytes()), output)
            return
        }

        if (declaredSize < 0) {
            throw DecoderException("Negative uncompressed packet size: $declaredSize")
        }
        // A stream compresses payloads below the threshold as well, see ZstdStreamFormat.MINIMUM_SEGMENT_BYTES.
        if (validateDecompressed && streamState != ExtensionState.Active && declaredSize < threshold) {
            throw DecoderException(
                "Badly compressed packet - size of $declaredSize is below server threshold of $threshold"
            )
        }
        if (declaredSize > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
            throw DecoderException(
                "Badly compressed packet - size of $declaredSize is larger than protocol maximum of ${ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH}"
            )
        }

        val compressedSize = input.readableBytes()
        if (compressedSize == 0) throw DecoderException("Compressed packet contains no Zstd frame")
        if (compressedSize > ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH) {
            throw DecoderException(
                "Badly compressed packet - compressed size of $compressedSize is larger than protocol maximum of ${ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH}"
            )
        }

        emit(decompressFrame(context, input, compressedSize, declaredSize), output)
    }

    /**
     * Records one plain packet in the client table, then passes it on; a failed record is released.
     * A packet the server kept out of its own table, such as a restored zlib tail, skips the record.
     */
    private fun emit(record: ByteBuf, output: MutableList<Any>, recordReference: Boolean = true) {
        try {
            if (recordReference) packetRefs?.record(record)
        } catch (error: Throwable) {
            record.release()
            throw error
        }
        try {
            handlerContext?.let { ZstdTransportGuard.existing(it.channel())?.decoded(it, record) }
            output.add(record)
        } catch (error: Throwable) {
            record.release()
            throw error
        }
    }

    /** A rejected extension frame drops its rest; ByteToMessageDecoder would parse it as the next frame. */
    private inline fun <T> consumeOnFailure(input: ByteBuf, decode: () -> T): T {
        try {
            return decode()
        } catch (error: Throwable) {
            input.skipBytes(input.readableBytes())
            throw error
        }
    }

    fun updateThreshold(threshold: Int, validateDecompressed: Boolean) {
        this.threshold = threshold
        this.validateDecompressed = validateDecompressed
    }

    fun isBatchingEnabled(): Boolean = batchingEnabled

    /** Enables or disables acceptance of inbound batch blocks on the connection's event loop. */
    fun requestBatching(enabled: Boolean) {
        val context = handlerContext ?: return
        val task = Runnable {
            if (enabled) ZstdTransportGuard.get(context.channel()).armInbound(context)
            batchingEnabled = enabled
        }
        if (context.executor().inEventLoop()) task.run() else context.executor().execute(task)
    }

    /**
     * Prepares for the server's START, or forgets the extension when [ready] is false.
     *
     * Preparing again is a no-op, so a late fallback never resets a table that START already aligned.
     */
    fun requestPacketRefsReady(ready: Boolean) {
        val context = handlerContext ?: return
        val task = Runnable {
            if (!ready) {
                packetRefState = ExtensionState.Idle
                packetRefs = null
            } else if (packetRefState == ExtensionState.Idle) {
                ZstdTransportGuard.get(context.channel()).armInbound(context)
                packetRefState = ExtensionState.Ready
            }
        }
        if (context.executor().inEventLoop()) task.run() else context.executor().execute(task)
    }

    /** Test view of the client table; call it on the event loop. */
    internal fun packetRefSnapshot(): List<PacketRefCache.Entry>? = packetRefs?.snapshot()

    /**
     * Prepares for the server's STREAM_START, or forgets the stream when [ready] is false.
     *
     * Preparing again is a no-op, so a late fallback never disturbs a stream that already runs.
     */
    fun requestStreamReady(ready: Boolean) {
        val context = handlerContext ?: return
        val task = Runnable {
            if (!ready) {
                streamState = ExtensionState.Idle
                closeStream()
            } else if (streamState == ExtensionState.Idle) {
                ZstdTransportGuard.get(context.channel()).armInbound(context)
                streamState = ExtensionState.Ready
            }
        }
        if (context.executor().inEventLoop()) task.run() else context.executor().execute(task)
    }

    /** Accepts zlib tail frames from now on, or rejects them again when [ready] is false. */
    fun requestZlibTailReady(ready: Boolean) {
        val context = handlerContext ?: return
        val task = Runnable {
            if (ready) ZstdTransportGuard.get(context.channel()).armInbound(context)
            zlibTailReady = ready
        }
        if (context.executor().inEventLoop()) task.run() else context.executor().execute(task)
    }

    /** Test view: whether compressed payloads decode as stream segments; call it on the event loop. */
    internal fun isStreamActive(): Boolean = streamState == ExtensionState.Active

    private fun closeStream() {
        streamContext?.close()
        streamContext = null
    }

    private fun decodeReference(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        val refs = packetRefs ?: throw DecoderException(
            if (packetRefState == ExtensionState.Idle) {
                "Received a packet reference on a connection that did not negotiate packet references"
            } else {
                "Received a packet reference before START"
            },
        )
        val slot = readRefVarInt(input, "slot")
        if (input.readableBytes() < Int.SIZE_BYTES) {
            throw DecoderException("Packet reference ended before its check")
        }
        val check = input.readInt()
        if (input.isReadable) {
            throw DecoderException("Packet reference carries ${input.readableBytes()} trailing bytes")
        }
        val content = refs.contentAt(slot)
            ?: throw DecoderException("Packet reference names empty or unknown slot $slot of ${refs.slots}")
        if (refs.checkAt(slot) != check) {
            throw DecoderException("Packet reference check mismatch for slot $slot; packet tables are out of sync")
        }
        refs.touch(slot)
        val record = context.alloc().buffer(content.size, content.size)
        record.writeBytes(content)
        try {
            handlerContext?.let { ZstdTransportGuard.existing(it.channel())?.decoded(it, record) }
            output.add(record)
        } catch (error: Throwable) {
            record.release()
            throw error
        }
    }

    private fun decodePersistent(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        val session = PersistentPacketSessions.client(context.channel())
            ?: throw DecoderException("Received a persistent packet on a connection that did not negotiate persistent packets")
        if (input.readableBytes() < 2 + PersistentPacketHash.BYTES) {
            throw DecoderException("Persistent packet frame ended after ${input.readableBytes()} bytes")
        }
        val opcode = input.readUnsignedByte().toInt()
        val kind = input.readUnsignedByte().toInt()
        if (!PersistentPacketKind.isValid(kind)) throw DecoderException("Persistent packet frame names invalid kind ${kind}")
        val named = PersistentPacketHash.read(input)
        when (opcode) {
            PersistentPacketFormat.OP_HIT -> {
                if (input.isReadable) throw DecoderException("Persistent packet HIT carries ${input.readableBytes()} trailing bytes")
                val content = session.content(named)
                    ?: throw DecoderException("Persistent packet ${named} of ${PersistentPacketKind.name(kind)} is not held by this client")
                val record = context.alloc().buffer(content.size, content.size)
                record.writeBytes(content)
                emit(record, output)
            }
            PersistentPacketFormat.OP_FULL -> {
                val record = decodeInnerEnvelope(context, input, "Persistent packet FULL", PERSISTENT_EXTENSION)
                val content = try {
                    ByteBufUtil.getBytes(record).also { verifyPersistent(it, named, kind) }
                } catch (error: Throwable) {
                    record.release()
                    throw error
                }
                session.received(kind, named, content)
                emit(record, output)
            }
            PersistentPacketFormat.OP_DELTA -> {
                val hash = PersistentPacketHash.read(input)
                val size = readRefVarInt(input, "size", PERSISTENT_EXTENSION)
                if (size <= 0 || size > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
                    throw DecoderException("Persistent packet DELTA declares ${size} bytes")
                }
                val deltaBytes = input.readableBytes()
                if (deltaBytes == 0 || deltaBytes > ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH) {
                    throw DecoderException("Persistent packet DELTA carries ${deltaBytes} bytes")
                }
                val base = session.content(named)
                    ?: throw DecoderException("Persistent packet base ${named} of ${PersistentPacketKind.name(kind)} is not held by this client")
                val delta = ByteArray(deltaBytes)
                input.readBytes(delta)
                val content = try {
                    PersistentPacketDelta.decode(base, delta, size)
                } catch (error: Exception) {
                    throw DecoderException("Failed to restore persistent packet ${hash} from base ${named}", error)
                }
                verifyPersistent(content, hash, kind)
                session.received(kind, hash, content)
                val record = context.alloc().buffer(content.size, content.size)
                record.writeBytes(content)
                emit(record, output)
            }
            else -> throw DecoderException("Unsupported persistent packet opcode: ${opcode}")
        }
    }

    /** The legacy envelope inside an extension frame, decoded like a top-level one, the Zstd stream included. */
    private fun decodeInnerEnvelope(context: ChannelHandlerContext, input: ByteBuf, frame: String, extension: String): ByteBuf {
        val declaredSize = readRefVarInt(input, "envelope size", extension)
        if (declaredSize == 0) {
            val rawSize = input.readableBytes()
            if (rawSize == 0 || rawSize > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw DecoderException("${frame} carries ${rawSize} raw bytes")
            }
            return input.readRetainedSlice(rawSize)
        }
        if (declaredSize < 0 || declaredSize > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
            throw DecoderException("${frame} declares ${declaredSize} bytes")
        }
        val compressedSize = input.readableBytes()
        if (compressedSize == 0 || compressedSize > ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH) {
            throw DecoderException("${frame} carries ${compressedSize} compressed bytes")
        }
        return decompressFrame(context, input, compressedSize, declaredSize)
    }

    private fun decodeZlibTail(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        if (!zlibTailReady) throw DecoderException("Received a zlib tail frame on a connection that did not negotiate zlib tails")
        if (!input.isReadable) throw DecoderException("Zlib tail frame ended before its version")
        val version = input.readUnsignedByte().toInt()
        if (version != ZlibTailFormat.VERSION) throw DecoderException("Unsupported zlib tail version: ${version}")
        val tailOffset = readRefVarInt(input, "tail offset", ZLIB_TAIL_EXTENSION)
        val envelope = decodeInnerEnvelope(context, input, "Zlib tail frame", ZLIB_TAIL_EXTENSION)
        val unwrapped = try {
            ByteBufUtil.getBytes(envelope)
        } finally {
            envelope.release()
        }
        val packet = ZlibTail.rewrap(unwrapped, tailOffset).getOrElse { error ->
            throw DecoderException("Failed to restore the zlib field of a ${unwrapped.size} byte packet", error)
        }
        val record = context.alloc().buffer(packet.size, packet.size)
        record.writeBytes(packet)
        emit(record, output, recordReference = false)
    }

    private fun verifyPersistent(content: ByteArray, hash: PersistentPacketHash, kind: Int) {
        if (PersistentPacketHash.of(content) != hash) {
            throw DecoderException("Restored persistent packet of ${PersistentPacketKind.name(kind)} does not match its hash ${hash}")
        }
    }

    private fun decodeControl(input: ByteBuf) {
        if (!input.isReadable) throw DecoderException("Control frame ended before its opcode")
        when (val opcode = input.readUnsignedByte().toInt()) {
            PacketRefFormat.OPCODE_START -> decodePacketRefStart(input)
            ZstdStreamFormat.OPCODE_STREAM_START -> decodeStreamStart(input)
            else -> throw DecoderException("Unsupported control frame opcode: $opcode")
        }
    }

    private fun decodeStreamStart(input: ByteBuf) {
        when (streamState) {
            ExtensionState.Idle ->
                throw DecoderException("Received STREAM_START on a connection that did not negotiate the Zstd stream")
            ExtensionState.Active -> throw DecoderException("Received a second STREAM_START")
            ExtensionState.Ready -> Unit
        }
        val version = readRefVarInt(input, "version", STREAM_EXTENSION)
        if (version != ZstdStreamFormat.VERSION) {
            throw DecoderException("Unsupported Zstd stream version: $version")
        }
        val windowLog = readRefVarInt(input, "window log", STREAM_EXTENSION)
        if (!ZstdStreamFormat.isWindowLogAccepted(windowLog)) {
            throw DecoderException("STREAM_START window log of $windowLog is outside the accepted range")
        }
        if (input.isReadable) {
            throw DecoderException("STREAM_START carries ${input.readableBytes()} trailing bytes")
        }
        val stream = ZstdDecompressCtx()
        try {
            stream.setMagicless(true)
        } catch (error: Throwable) {
            stream.close()
            throw error
        }
        streamContext = stream
        streamState = ExtensionState.Active
    }

    private fun decodePacketRefStart(input: ByteBuf) {
        if (packetRefState == ExtensionState.Idle) {
            throw DecoderException("Received START on a connection that did not negotiate packet references")
        }
        val version = readRefVarInt(input, "version")
        if (version != PacketRefFormat.VERSION) {
            throw DecoderException("Unsupported packet reference version: $version")
        }
        val slots = readRefVarInt(input, "slot count")
        if (!PacketRefFormat.isSlotCountAccepted(slots)) {
            throw DecoderException("START slot count of $slots is outside the accepted range")
        }
        val maxEntryBytes = readRefVarInt(input, "entry limit")
        if (!PacketRefFormat.isMaxEntryBytesAccepted(maxEntryBytes)) {
            throw DecoderException("START entry limit of $maxEntryBytes bytes is outside the accepted range")
        }
        if (input.isReadable) {
            throw DecoderException("START carries ${input.readableBytes()} trailing bytes")
        }
        packetRefs = PacketRefCache(slots, maxEntryBytes)
        packetRefState = ExtensionState.Active
    }

    /** Reads one extension VarInt; a truncated or overlong value fails the frame with its name. */
    private fun readRefVarInt(input: ByteBuf, field: String, extension: String = REF_EXTENSION): Int {
        handlerContext?.let { ZstdTransportGuard.existing(it.channel())?.checkHealthy() }
        if (!input.isReadable) {
            throw DecoderException("${extension.replaceFirstChar { it.uppercase() }} frame ended before its $field")
        }
        return try {
            varIntCodec.read(input)
        } catch (error: Exception) {
            throw DecoderException("Malformed $extension $field", error)
        }
    }

    private fun decodeBatchBlock(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        if (!batchingEnabled) {
            throw DecoderException("Received a batch block on a connection that did not negotiate batching")
        }
        val version = varIntCodec.read(input)
        if (version != ZstdBatchFormat.VERSION) {
            throw DecoderException("Unsupported batch block version: $version")
        }
        val flags = input.readUnsignedByte().toInt()
        if (flags and ZstdBatchFormat.FLAG_RAW_PAYLOAD.inv() != 0) {
            throw DecoderException("Unsupported batch block flags: $flags")
        }
        val payloadBytes = varIntCodec.read(input)
        if (payloadBytes <= 0 || payloadBytes > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
            throw DecoderException(
                "Badly batched packet - payload size of $payloadBytes is outside the accepted range"
            )
        }
        val recordCount = varIntCodec.read(input)
        if (recordCount <= 0 || recordCount > ZstdBatchFormat.MAXIMUM_RECORDS) {
            throw DecoderException("Badly batched packet - record count of $recordCount is outside the accepted range")
        }
        val blockBytes = input.readableBytes()
        if (blockBytes == 0) {
            throw DecoderException("Batch block contains no payload")
        }
        if (blockBytes > ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH) {
            throw DecoderException(
                "Badly batched packet - block size of $blockBytes is larger than protocol maximum of ${ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH}"
            )
        }
        val raw = flags and ZstdBatchFormat.FLAG_RAW_PAYLOAD != 0
        if (raw && blockBytes != payloadBytes) {
            throw DecoderException(
                "Badly batched packet - raw block declares $payloadBytes payload bytes but carries $blockBytes bytes"
            )
        }

        val payload = if (raw) {
            input.readRetainedSlice(payloadBytes)
        } else {
            decompressFrame(context, input, blockBytes, payloadBytes)
        }
        try {
            splitRecords(payload, recordCount, output)
        } finally {
            payload.release()
        }
    }

    /**
     * Reads the record area twice: every length is validated before the first record is emitted, so a
     * malformed block never leaves half of its records in the decode output.
     */
    private fun splitRecords(payload: ByteBuf, recordCount: Int, output: MutableList<Any>) {
        val recordStart = payload.readerIndex()
        val recordBytes = IntArray(recordCount)
        for (index in 0 until recordCount) {
            if (!payload.isReadable) {
                throw DecoderException("Batch block ended after $index of $recordCount records")
            }
            val length = varIntCodec.read(payload)
            if (length <= 0) {
                throw DecoderException("Batch block record $index declares $length bytes")
            }
            if (length > payload.readableBytes()) {
                throw DecoderException(
                    "Batch block record $index of $length bytes exceeds the remaining ${payload.readableBytes()} bytes"
                )
            }
            recordBytes[index] = length
            payload.skipBytes(length)
        }
        if (payload.isReadable) {
            throw DecoderException(
                "Batch block carries ${payload.readableBytes()} bytes beyond the declared $recordCount records"
            )
        }
        payload.readerIndex(recordStart)
        for (index in 0 until recordCount) {
            varIntCodec.read(payload)
            emit(payload.readRetainedSlice(recordBytes[index]), output)
        }
    }

    private fun decompressFrame(
        context: ChannelHandlerContext,
        input: ByteBuf,
        compressedSize: Int,
        declaredSize: Int,
    ): ByteBuf {
        val sourceBuffer = if (input.isDirect && input.nioBufferCount() == 1) {
            null
        } else {
            context.alloc().directBuffer(compressedSize, compressedSize)
        }
        try {
            sourceBuffer?.writeBytes(input, input.readerIndex(), compressedSize)
            val source = sourceBuffer?.nioBuffer(sourceBuffer.readerIndex(), compressedSize)
                ?: input.nioBuffer(input.readerIndex(), compressedSize)
            if (streamState == ExtensionState.Active) {
                val decoded = consumeOnFailure(input) { decompressSegment(context, source, compressedSize, declaredSize) }
                input.skipBytes(compressedSize)
                return decoded
            }
            val frameSize = Zstd.findFrameCompressedSize(source)
            if (frameSize != compressedSize.toLong()) {
                throw DecoderException(
                    "Badly compressed packet - expected one Zstd frame of $compressedSize bytes, found $frameSize"
                )
            }

            val decoded = context.alloc().directBuffer(declaredSize, declaredSize)
            try {
                val destination = decoded.nioBuffer(0, declaredSize)
                val decodedSize = decompressionContext.decompressDirectByteBuffer(
                    destination,
                    0,
                    declaredSize,
                    source,
                    source.position(),
                    compressedSize,
                )
                if (decodedSize != declaredSize) {
                    throw DecoderException(
                        "Badly compressed packet - actual length of uncompressed payload $decodedSize does not match declared size $declaredSize"
                    )
                }
                decoded.writerIndex(decodedSize)
                input.skipBytes(compressedSize)
                return decoded
            } catch (exception: Throwable) {
                decoded.release()
                throw exception
            }
        } finally {
            sourceBuffer?.release()
        }
    }

    /**
     * Decodes the next segment of the connection's Zstd stream. The spare destination byte exposes a
     * segment that inflates past its declared size. Any failure leaves the two ends without a shared
     * history, so the stream is dropped and every later compressed payload fails as well.
     */
    private fun decompressSegment(
        context: ChannelHandlerContext,
        source: ByteBuffer,
        compressedSize: Int,
        declaredSize: Int,
    ): ByteBuf {
        val stream = streamContext ?: throw DecoderException("The Zstd stream of this connection already failed")
        val decoded = context.alloc().directBuffer(declaredSize + 1, declaredSize + 1)
        try {
            val destination = decoded.nioBuffer(0, declaredSize + 1)
            val destinationStart = destination.position()
            while (source.hasRemaining() && destination.hasRemaining()) {
                val sourceBefore = source.position()
                val destinationBefore = destination.position()
                stream.decompressDirectByteBufferStream(destination, source)
                if (source.position() == sourceBefore && destination.position() == destinationBefore) break
            }
            // A segment ends on a flushed block, so its last bytes may still wait in the stream's buffer.
            if (!source.hasRemaining() && destination.position() - destinationStart < declaredSize) {
                stream.decompressDirectByteBufferStream(destination, source)
            }
            val decodedSize = destination.position() - destinationStart
            if (source.hasRemaining() || decodedSize != declaredSize) {
                throw DecoderException(
                    "Badly compressed packet - stream segment of $compressedSize bytes left ${source.remaining()} " +
                        "bytes unread and decoded $decodedSize bytes, declared $declaredSize; the Zstd stream is out of sync"
                )
            }
            decoded.writerIndex(decodedSize)
            return decoded
        } catch (exception: Throwable) {
            decoded.release()
            closeStream()
            throw exception
        }
    }

    override fun handlerRemoved0(context: ChannelHandlerContext) {
        ZstdTransportGuard.existing(context.channel())?.removed(context, true)
        decompressionContext.close()
        closeStream()
        streamState = ExtensionState.Idle
        packetRefs = null
        packetRefState = ExtensionState.Idle
        zlibTailReady = false
        handlerContext = null
        super.handlerRemoved0(context)
    }

    private companion object {
        const val REF_EXTENSION = "packet reference"
        const val STREAM_EXTENSION = "Zstd stream"
        const val PERSISTENT_EXTENSION = "persistent packet"
        const val ZLIB_TAIL_EXTENSION = "zlib tail"
    }
}
