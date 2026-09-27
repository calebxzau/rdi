package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.local.LocalChannel
import io.netty.handler.codec.DecoderException
import java.util.zip.Deflater
import kotlin.experimental.and
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ZstdCompressionPipelineTest {
    @Test
    fun `threshold boundary uses raw below and zstd at threshold`() {
        val raw = encode(ByteArray(127), threshold = 128)
        assertEquals(0, raw[0].toInt())

        val compressed = encode(ByteArray(128) { (it * 17).toByte() }, threshold = 128)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01, 0x28.toByte(), 0xB5.toByte(), 0x2F, 0xFD.toByte()), compressed.copyOfRange(0, 6))
    }

    @Test
    fun `raw and zstd payloads round trip`() {
        val raw = ByteArray(127) { it.toByte() }
        assertContentEquals(raw, decode(encode(raw, threshold = 128), threshold = 128))

        val compressed = ByteArray(4096) { (it * 31).toByte() }
        assertContentEquals(compressed, decode(encode(compressed, threshold = 128), threshold = 128))
    }

    @Test
    fun `decoder handles successive pooled direct outputs`() {
        val encoder = EmbeddedChannel(ZstdCompressionEncoder(128, TEST_VAR_INT))
        val decoder = EmbeddedChannel(ZstdCompressionDecoder(128, true, TEST_VAR_INT))
        try {
            repeat(4) { packetNumber ->
                val source = ByteArray(128 + packetNumber * 7) { (packetNumber + 1).toByte() }
                assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(source)))
                val compressed = assertNotNull(encoder.readOutbound<ByteBuf>())
                assertTrue(decoder.writeInbound(compressed))
                val decoded = assertNotNull(decoder.readInbound<ByteBuf>())
                val actual = ByteArray(decoded.readableBytes())
                decoded.readBytes(actual)
                decoded.release()
                assertContentEquals(source, actual)
            }
        } finally {
            encoder.finishAndReleaseAll()
            decoder.finishAndReleaseAll()
        }
    }

    @Test
    fun `empty packet round trips`() {
        val empty = ByteArray(0)
        assertContentEquals(empty, decode(encode(empty, threshold = 128), threshold = 128))
    }

    @Test
    fun `empty packet at zero threshold keeps the empty raw envelope`() {
        val encoded = encode(ByteArray(0), threshold = 0)
        assertContentEquals(byteArrayOf(0), encoded)
        assertContentEquals(ByteArray(0), decode(encoded, threshold = 0))
    }

    @Test
    fun `direct heap sliced and composite buffers round trip with offsets`() {
        val payload = ByteArray(1024) { (it * 43).toByte() }
        for (variant in BufferVariant.entries) {
            val source = variantBuffer(variant, payload)
            val encoded = encode(source, threshold = 128)

            val encodedBuffer = variantBuffer(variant, encoded)
            val decoder = EmbeddedChannel(ZstdCompressionDecoder(128, true, TEST_VAR_INT))
            try {
                assertTrue(decoder.writeInbound(encodedBuffer))
                val decoded = assertNotNull(decoder.readInbound<ByteBuf>())
                val actual = ByteArray(decoded.readableBytes())
                decoded.readBytes(actual)
                decoded.release()
                assertContentEquals(payload, actual, "buffer variant $variant")
            } finally {
                decoder.finishAndReleaseAll()
            }
        }
    }

    @Test
    fun `maximum uncompressed packet is accepted`() {
        val input = ByteArray(ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH) { (it * 13).toByte() }
        assertContentEquals(input, decode(encode(input, threshold = 128), threshold = 128))
    }

    @Test
    fun `compressed payload limit is enforced before native decode`() {
        val oversized = envelope(128, ByteArray(ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH + 1))
        assertDecodeFails(oversized)
    }

    @Test
    fun `malformed inner varints are rejected`() {
        assertDecodeFails(byteArrayOf(0x80.toByte()))
        assertDecodeFails(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F))
        assertDecodeFails(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01))
    }

    @Test
    fun `non-zstd and malformed frames are rejected`() {
        val source = ByteArray(128) { it.toByte() }
        val valid = encode(source, threshold = 128)
        val frameStart = varIntSize(source.size)
        val frame = valid.copyOfRange(frameStart, valid.size)

        assertDecodeFails(envelope(source.size, deflate(source)))
        assertDecodeFails(valid.copyOf(valid.size - 1))
        assertDecodeFails(valid + byteArrayOf(0))
        assertDecodeFails(valid + frame)
        assertDecodeFails(envelope(source.size + 1, frame))
    }

    @Test
    fun `pipeline updates replaces disables and keeps handler order`() {
        val channel = pipelineChannel()
        try {
            ZstdCompressionPipeline.setup(channel, 128, true, TEST_VAR_INT)
            val decoder = assertNotNull(channel.pipeline().get("decompress"))
            val encoder = assertNotNull(channel.pipeline().get("compress"))
            assertTrue(decoder is ZstdCompressionDecoder)
            assertTrue(encoder is ZstdCompressionEncoder)
            assertTrue(channel.pipeline().names().indexOf("splitter") < channel.pipeline().names().indexOf("decompress"))
            assertTrue(channel.pipeline().names().indexOf("decompress") < channel.pipeline().names().indexOf("decoder"))
            assertTrue(channel.pipeline().names().indexOf("prepender") < channel.pipeline().names().indexOf("compress"))
            assertTrue(channel.pipeline().names().indexOf("compress") < channel.pipeline().names().indexOf("encoder"))

            ZstdCompressionPipeline.setup(channel, 256, false, TEST_VAR_INT)
            assertSame(decoder, channel.pipeline().get("decompress"))
            assertSame(encoder, channel.pipeline().get("compress"))

            ZstdCompressionPipeline.setup(channel, -1, true, TEST_VAR_INT)
            assertNull(channel.pipeline().get("decompress"))
            assertNull(channel.pipeline().get("compress"))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `non-zstd handlers are replaced while disabling preserves them`() {
        val channel = pipelineChannel()
        try {
            channel.pipeline().addAfter("splitter", "decompress", ChannelInboundHandlerAdapter())
            channel.pipeline().addAfter("prepender", "compress", ChannelOutboundHandlerAdapter())
            ZstdCompressionPipeline.setup(channel, 128, true, TEST_VAR_INT)
            assertTrue(channel.pipeline().get("decompress") is ZstdCompressionDecoder)
            assertTrue(channel.pipeline().get("compress") is ZstdCompressionEncoder)

            ZstdCompressionPipeline.setup(channel, -1, true, TEST_VAR_INT)
            assertNull(channel.pipeline().get("decompress"))
            assertNull(channel.pipeline().get("compress"))
        } finally {
            channel.finishAndReleaseAll()
        }

        val disabledChannel = pipelineChannel()
        try {
            disabledChannel.pipeline().addAfter("splitter", "decompress", ChannelInboundHandlerAdapter())
            disabledChannel.pipeline().addAfter("prepender", "compress", ChannelOutboundHandlerAdapter())
            ZstdCompressionPipeline.setup(disabledChannel, -1, true, TEST_VAR_INT)
            assertNotNull(disabledChannel.pipeline().get("decompress"))
            assertNotNull(disabledChannel.pipeline().get("compress"))
        } finally {
            disabledChannel.finishAndReleaseAll()
        }
    }

    @Test
    fun `local channels do not receive zstd handlers`() {
        val channel = LocalChannel()
        channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        ZstdCompressionPipeline.setup(channel, 128, true, TEST_VAR_INT)
        assertNull(channel.pipeline().get("decompress"))
        assertNull(channel.pipeline().get("compress"))
    }

    private fun encode(input: ByteArray, threshold: Int): ByteArray {
        return encode(Unpooled.wrappedBuffer(input), threshold)
    }

    private fun encode(input: ByteBuf, threshold: Int): ByteArray {
        val channel = EmbeddedChannel(ZstdCompressionEncoder(threshold, TEST_VAR_INT))
        try {
            assertTrue(channel.writeOutbound(input))
            val output = assertNotNull(channel.readOutbound<ByteBuf>())
            val bytes = ByteArray(output.readableBytes())
            output.readBytes(bytes)
            output.release()
            return bytes
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun variantBuffer(variant: BufferVariant, bytes: ByteArray): ByteBuf {
        val padded = byteArrayOf(0x55) + bytes + byteArrayOf(0x66)
        return when (variant) {
            BufferVariant.DIRECT -> Unpooled.directBuffer(padded.size)
                .writeBytes(padded)
                .setIndex(1, bytes.size + 1)

            BufferVariant.HEAP -> Unpooled.buffer(padded.size)
                .writeBytes(padded)
                .setIndex(1, bytes.size + 1)

            BufferVariant.SLICED -> {
                val parent = Unpooled.wrappedBuffer(padded)
                try {
                    parent.slice(1, bytes.size).retain()
                } finally {
                    parent.release()
                }
            }

            BufferVariant.COMPOSITE -> {
                val composite: CompositeByteBuf = Unpooled.compositeBuffer(3)
                composite.addComponents(
                    true,
                    Unpooled.wrappedBuffer(byteArrayOf(padded[0])),
                    Unpooled.wrappedBuffer(bytes.copyOfRange(0, bytes.size / 2)),
                    Unpooled.wrappedBuffer(bytes.copyOfRange(bytes.size / 2, bytes.size) + byteArrayOf(padded.last())),
                )
                composite.setIndex(1, bytes.size + 1)
            }
        }
    }

    private enum class BufferVariant {
        DIRECT,
        HEAP,
        SLICED,
        COMPOSITE,
    }

    private fun decode(input: ByteArray, threshold: Int): ByteArray {
        val channel = EmbeddedChannel(ZstdCompressionDecoder(threshold, true, TEST_VAR_INT))
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(input)))
            val output = assertNotNull(channel.readInbound<ByteBuf>())
            val bytes = ByteArray(output.readableBytes())
            output.readBytes(bytes)
            output.release()
            return bytes
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun assertDecodeFails(input: ByteArray) {
        val decoder = ZstdCompressionDecoder(128, true, TEST_VAR_INT)
        val channel = EmbeddedChannel(decoder)
        try {
            assertFailsWith<Throwable> {
                channel.writeInbound(Unpooled.wrappedBuffer(input))
            }
        } finally {
            channel.pipeline().remove(decoder)
            channel.finishAndReleaseAll()
        }
    }

    private fun envelope(declaredSize: Int, payload: ByteArray): ByteArray {
        val buffer = Unpooled.buffer()
        return try {
            TEST_VAR_INT.write(buffer, declaredSize)
            buffer.writeBytes(payload)
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            bytes
        } finally {
            buffer.release()
        }
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArray(1024)
            val result = java.io.ByteArrayOutputStream()
            while (!deflater.finished()) {
                result.write(output, 0, deflater.deflate(output))
            }
            result.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun varIntSize(value: Int): Int = when {
        (value and -0x80) == 0 -> 1
        (value and -0x4000) == 0 -> 2
        (value and -0x200000) == 0 -> 3
        (value and -0x10000000) == 0 -> 4
        else -> 5
    }

    private fun pipelineChannel(): EmbeddedChannel {
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        return channel
    }

    private object TEST_VAR_INT : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                if (!buffer.isReadable) {
                    throw DecoderException("Unterminated VarInt")
                }
                val current = buffer.readUnsignedByte().toInt()
                value = value or ((current and 0x7F) shl shift)
                if ((current and 0x80) == 0) {
                    return value
                }
                shift += 7
            }
            throw DecoderException("VarInt too big")
        }

        override fun write(buffer: ByteBuf, value: Int) {
            var current = value
            while ((current and -0x80) != 0) {
                buffer.writeByte((current and 0x7F) or 0x80)
                current = current ushr 7
            }
            buffer.writeByte(current)
        }
    }
}
