package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.EncoderException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Raised limits opt in per connection; modern defaults still reject packets above 8MiB. */
class ZstdPacketLimitsTest {
    @Test
    fun `default rejects above 8MiB while raised limits round trip the same packet`() {
        val payload = ByteArray(ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH + 1) { 42 }
        val encoder = EmbeddedChannel(ZstdCompressionEncoder(256, VAR_INT))
        try {
            assertFailsWith<EncoderException> { encoder.writeOutbound(Unpooled.wrappedBuffer(payload)) }
        } finally {
            encoder.finishAndReleaseAll()
        }
        val raised = ZstdPacketLimits(payload.size, ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH)
        val sender = EmbeddedChannel(ZstdCompressionEncoder(256, VAR_INT, limits = raised))
        val receiver = EmbeddedChannel(ZstdCompressionDecoder(256, true, VAR_INT, raised))
        val defaultReceiver = EmbeddedChannel(ZstdCompressionDecoder(256, true, VAR_INT))
        try {
            sender.writeOutbound(Unpooled.wrappedBuffer(payload))
            val frame = sender.readOutbound<ByteBuf>()
            try {
                val rejected = frame.copy()
                try {
                    assertFailsWith<DecoderException> { defaultReceiver.writeInbound(rejected) }
                } finally {
                    if (rejected.refCnt() > 0) rejected.skipBytes(rejected.readableBytes())
                }
                receiver.writeInbound(frame.retain())
                val decoded = receiver.readInbound<ByteBuf>()
                try {
                    assertContentEquals(payload, ByteArray(decoded.readableBytes()).also { decoded.readBytes(it) })
                } finally {
                    decoded.release()
                }
            } finally {
                frame.release()
            }
        } finally {
            try { sender.finishAndReleaseAll() } finally {
                try { receiver.finishAndReleaseAll() } finally { defaultReceiver.finishAndReleaseAll() }
            }
        }
    }

    private companion object {
        val VAR_INT = object : MinecraftVarIntCodec {
            override fun read(buffer: ByteBuf): Int {
                var value = 0
                repeat(5) { index ->
                    val byte = buffer.readUnsignedByte().toInt()
                    value = value or ((byte and 127) shl (index * 7))
                    if (byte and 128 == 0) return value
                }
                error("VarInt too big")
            }

            override fun write(buffer: ByteBuf, value: Int) {
                var current = value
                while (current and -128 != 0) {
                    buffer.writeByte((current and 127) or 128)
                    current = current ushr 7
                }
                buffer.writeByte(current)
            }
        }
    }
}
