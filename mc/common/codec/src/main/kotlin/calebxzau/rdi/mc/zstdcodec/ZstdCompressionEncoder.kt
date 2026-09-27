package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.EncoderException
import io.netty.handler.codec.MessageToByteEncoder

internal class ZstdCompressionEncoder(
    private var threshold: Int,
    private val varIntCodec: MinecraftVarIntCodec,
) : MessageToByteEncoder<ByteBuf>() {
    private val compressionContext = ZstdCompressCtx()
        .setLevel(COMPRESSION_LEVEL)
        .setMagicless(false)
        .setChecksum(false)
        .setDictID(false)
        .setContentSize(false)

    override fun encode(context: ChannelHandlerContext, input: ByteBuf, output: ByteBuf) {
        val size = input.readableBytes()
        if (size > ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) {
            throw EncoderException(
                "Packet too big (is $size, should be less than or equal to ${ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH})"
            )
        }

        if (size == 0 || size < threshold) {
            varIntCodec.write(output, 0)
            output.writeBytes(input)
            return
        }

        val compressedCapacity = Zstd.compressBound(size.toLong()).toInt()
        val sourceBuffer = if (input.isDirect && input.nioBufferCount() == 1) {
            null
        } else {
            context.alloc().directBuffer(size, size)
        }

        try {
            sourceBuffer?.writeBytes(input, input.readerIndex(), size)
            val source = sourceBuffer?.nioBuffer(sourceBuffer.readerIndex(), size)
                ?: input.nioBuffer(input.readerIndex(), size)
            val compressedBuffer = if (output.isDirect && output.nioBufferCount() == 1) {
                null
            } else {
                context.alloc().directBuffer(compressedCapacity, compressedCapacity)
            }

            try {
                varIntCodec.write(output, size)
                val destination = compressedBuffer?.nioBuffer(0, compressedCapacity)
                    ?: run {
                        output.ensureWritable(compressedCapacity)
                        output.nioBuffer(output.writerIndex(), compressedCapacity)
                    }
                val compressedSize = compressionContext.compressDirectByteBuffer(
                    destination,
                    destination.position(),
                    compressedCapacity,
                    source,
                    source.position(),
                    size,
                )

                if (compressedBuffer != null) {
                    compressedBuffer.writerIndex(compressedSize)
                    output.writeBytes(compressedBuffer)
                } else {
                    output.writerIndex(output.writerIndex() + compressedSize)
                }
                input.skipBytes(size)
            } finally {
                compressedBuffer?.release()
            }
        } finally {
            sourceBuffer?.release()
        }
    }

    fun updateThreshold(threshold: Int) {
        this.threshold = threshold
    }

    override fun handlerRemoved(context: ChannelHandlerContext) {
        compressionContext.close()
        super.handlerRemoved(context)
    }

    companion object {
        private const val COMPRESSION_LEVEL = 3
    }
}
