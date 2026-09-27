package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDecompressCtx
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.DecoderException

internal class ZstdCompressionDecoder(
    private var threshold: Int,
    private var validateDecompressed: Boolean,
    private val varIntCodec: MinecraftVarIntCodec,
) : ByteToMessageDecoder() {
    private val decompressionContext = ZstdDecompressCtx()

    override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
        if (!input.isReadable) {
            return
        }

        val declaredSize = varIntCodec.read(input)
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
                output.add(decoded)
            } catch (exception: Throwable) {
                decoded.release()
                throw exception
            }
        } finally {
            sourceBuffer?.release()
        }
    }

    fun updateThreshold(threshold: Int, validateDecompressed: Boolean) {
        this.threshold = threshold
        this.validateDecompressed = validateDecompressed
    }

    override fun handlerRemoved0(context: ChannelHandlerContext) {
        decompressionContext.close()
        super.handlerRemoved0(context)
    }
}
