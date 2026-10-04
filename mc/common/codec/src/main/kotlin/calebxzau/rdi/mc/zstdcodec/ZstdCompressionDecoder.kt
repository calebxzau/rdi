package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDecompressCtx
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.DecoderException

/**
 * RDI's Zstd replacement for Minecraft's compression decoder.
 *
 * The handler reads the legacy per-packet envelope and, on a connection that negotiated batching,
 * the batch block that carries several records. Every record leaves as its own message, so the
 * vanilla packet decoder receives exactly one packet per message.
 *
 * With the packet reference extension the handler also restores referenced packets. It accepts
 * START only after the client prepared for it, and records every plain packet from START onwards.
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
    private var packetRefState = PacketRefState.Idle

    /** The client table of the packet reference extension; non-null exactly in [PacketRefState.Active]. */
    private var packetRefs: PacketRefCache? = null

    private enum class PacketRefState { Idle, Ready, Active }

    override fun handlerAdded(context: ChannelHandlerContext) {
        handlerContext = context
    }

    override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
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
            (packetRefState == PacketRefState.Active || threshold > PacketRefFormat.REFERENCE_MARKER)
        ) {
            consumeOnFailure(input) { decodeReference(context, input, output) }
            return
        }
        if (declaredSize == PacketRefFormat.CONTROL_MARKER) {
            consumeOnFailure(input) { decodeControl(input) }
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
        if (validateDecompressed && declaredSize < threshold) {
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

    /** Records one plain packet in the client table, then passes it on; a failed record is released. */
    private fun emit(record: ByteBuf, output: MutableList<Any>) {
        try {
            packetRefs?.record(record)
        } catch (error: Throwable) {
            record.release()
            throw error
        }
        output.add(record)
    }

    /** A rejected extension frame drops its rest; ByteToMessageDecoder would parse it as the next frame. */
    private inline fun consumeOnFailure(input: ByteBuf, decode: () -> Unit) {
        try {
            decode()
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
        val task = Runnable { batchingEnabled = enabled }
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
                packetRefState = PacketRefState.Idle
                packetRefs = null
            } else if (packetRefState == PacketRefState.Idle) {
                packetRefState = PacketRefState.Ready
            }
        }
        if (context.executor().inEventLoop()) task.run() else context.executor().execute(task)
    }

    /** Test view of the client table; call it on the event loop. */
    internal fun packetRefSnapshot(): List<PacketRefCache.Entry>? = packetRefs?.snapshot()

    private fun decodeReference(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        val refs = packetRefs ?: throw DecoderException(
            if (packetRefState == PacketRefState.Idle) {
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
        output.add(record)
    }

    private fun decodeControl(input: ByteBuf) {
        if (!input.isReadable) throw DecoderException("Control frame ended before its opcode")
        val opcode = input.readUnsignedByte().toInt()
        if (opcode != PacketRefFormat.OPCODE_START) {
            throw DecoderException("Unsupported control frame opcode: $opcode")
        }
        if (packetRefState == PacketRefState.Idle) {
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
        packetRefState = PacketRefState.Active
    }

    /** Reads one extension VarInt; a truncated or overlong value fails the frame with its name. */
    private fun readRefVarInt(input: ByteBuf, field: String): Int {
        if (!input.isReadable) throw DecoderException("Packet reference frame ended before its $field")
        return try {
            varIntCodec.read(input)
        } catch (error: Exception) {
            throw DecoderException("Malformed packet reference $field", error)
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

    override fun handlerRemoved0(context: ChannelHandlerContext) {
        decompressionContext.close()
        packetRefs = null
        packetRefState = PacketRefState.Idle
        handlerContext = null
        super.handlerRemoved0(context)
    }
}
