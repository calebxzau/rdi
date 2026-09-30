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

        if (declaredSize == 0) {
            val rawSize = input.readableBytes()
            if (rawSize > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
                throw DecoderException(
                    "Uncompressed packet size of $rawSize is larger than protocol maximum of ${ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH}"
                )
            }
            output.add(input.readRetainedSlice(input.readableBytes()))
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

        output.add(decompressFrame(context, input, compressedSize, declaredSize))
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
            output.add(payload.readRetainedSlice(recordBytes[index]))
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
        handlerContext = null
        super.handlerRemoved0(context)
    }
}
