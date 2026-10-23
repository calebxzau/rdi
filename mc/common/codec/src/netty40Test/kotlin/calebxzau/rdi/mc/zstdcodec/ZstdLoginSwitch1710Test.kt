package calebxzau.rdi.mc.zstdcodec

import calebxzau.rdi.mc.zstdcodec.v1710.MinecraftVarIntCodec1710
import calebxzau.rdi.mc.zstdcodec.v1710.Zstd1710
import calebxzau.rdi.mc.zstdcodec.v1710.ZstdLoginSwitch1710
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.local.LocalChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.EncoderException
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.util.ReferenceCountUtil
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mirrors the copying 1.7.10 splitter and priority login-success callback on Netty 4.0.10. */
class ZstdLoginSwitch1710Test {
    @Test
    fun `same read switches after raw login and before FML replies with stream on or off`() {
        for (stream in listOf(true, false)) {
            val reply = Payload(ByteArray(1024) { 7 })
            val pair = Pair(stream, reply)
            try {
                val repeat = Payload(ByteArray(4096).also { Random(7).nextBytes(it) })
                val packets = listOf(Payload(byteArrayOf(1)), Payload(ByteArray(255)), Payload(ByteArray(256)), repeat, repeat)
                pair.server.writeOutbound(LoginSuccess)
                packets.forEach { pair.server.writeOutbound(it) }
                val wire = drainWire(pair.server)
                val peek = wire.duplicate()
                assertEquals(1, MinecraftVarIntCodec1710.read(peek))
                assertEquals(0, peek.readUnsignedByte().toInt()) // S02 was not given a compression envelope.
                if (stream) {
                    MinecraftVarIntCodec1710.read(peek)
                    assertEquals(PacketRefFormat.CONTROL_MARKER, MinecraftVarIntCodec1710.read(peek))
                } else {
                    MinecraftVarIntCodec1710.read(peek)
                    assertEquals(0, MinecraftVarIntCodec1710.read(peek)) // First small payload, no STREAM_START.
                }
                pair.client.writeInbound(wire)
                assertSame(LoginSuccess, pair.client.readInbound())
                assertPackets(packets, readPayloads(pair.client))
                val decoder = pair.client.pipeline().get("decompress") as ZstdCompressionDecoder
                assertEquals(stream, decoder.isStreamActive())
                assertNull(pair.client.pipeline().get(ZstdLoginSwitch1710.NAME))
                assertNull(pair.server.pipeline().get(ZstdLoginSwitch1710.NAME))

                val response = drainWire(pair.client)
                val responsePeek = response.duplicate()
                MinecraftVarIntCodec1710.read(responsePeek)
                assertEquals(reply.bytes.size + 1, MinecraftVarIntCodec1710.read(responsePeek))
                pair.server.writeInbound(response)
                assertPackets(listOf(reply), readPayloads(pair.server))
                assertFalse((pair.server.pipeline().get("decompress") as ZstdCompressionDecoder).isStreamActive())
                assertFalse(ZstdCompressionPipeline.isOutboundBatchingEnabled(pair.server))
                assertFalse(ZstdCompressionPipeline.isOutboundPacketRefsEnabled(pair.server))
                assertFalse(ZstdCompressionPipeline.isOutboundZlibTailEnabled(pair.server))
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `install twice is a no-op and status packets leave compression off`() {
        val pair = Pair(false)
        try {
            val clientSwitch = pair.client.pipeline().get(ZstdLoginSwitch1710.NAME)
            val serverSwitch = pair.server.pipeline().get(ZstdLoginSwitch1710.NAME)
            ZstdLoginSwitch1710.installClient(pair.client, { it === LoginSuccess })
            ZstdLoginSwitch1710.installServer(pair.server, { it === LoginSuccess }, { "duplicate" })
            assertSame(clientSwitch, pair.client.pipeline().get(ZstdLoginSwitch1710.NAME))
            assertSame(serverSwitch, pair.server.pipeline().get(ZstdLoginSwitch1710.NAME))
            val status = Payload(byteArrayOf(5))
            pair.server.writeOutbound(status)
            pair.client.writeInbound(drainWire(pair.server))
            assertPackets(listOf(status), readPayloads(pair.client))
            assertNull(pair.client.pipeline().get("compress"))
            assertNull(pair.server.pipeline().get("compress"))
        } finally {
            pair.close()
        }
        val local = LocalChannel()
        ZstdLoginSwitch1710.installClient(local, { true })
        ZstdLoginSwitch1710.installServer(local, { true }, { "local" })
        assertNull(local.pipeline().get(ZstdLoginSwitch1710.NAME))
    }

    @Test
    fun `large incompressible and repetitive packets round trip with and without stream`() {
        for (stream in listOf(false, true)) {
            val pair = Pair(stream)
            try {
                pair.login()
                for (random in listOf(true, false)) {
                    val packet = Payload(ByteArray(20 * 1024 * 1024).also { if (random) Random(19).nextBytes(it) })
                    pair.server.writeOutbound(packet)
                    pair.client.writeInbound(drainWire(pair.server))
                    assertPackets(listOf(packet), readPayloads(pair.client))
                }
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `exactly 32MiB including packet id round trips as a stream segment`() {
        val pair = Pair(true)
        try {
            pair.login()
            val packet = Payload(ByteArray(Zstd1710.LIMITS.maximumUncompressedBytes - 1).also { Random(23).nextBytes(it) })
            pair.server.writeOutbound(packet)
            pair.client.writeInbound(drainWire(pair.server))
            assertPackets(listOf(packet), readPayloads(pair.client))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `encoder rejects more than 32MiB`() {
        val pair = Pair(false)
        try {
            pair.login()
            assertFailsWith<EncoderException> {
                pair.server.writeOutbound(Payload(ByteArray(Zstd1710.LIMITS.maximumUncompressedBytes)))
            }
        } finally {
            pair.close()
        }
    }

    @Test
    fun `decoder rejects oversized raw declared and compressed packets`() {
        for (kind in 0..2) {
            val decoder = EmbeddedChannel(ZstdCompressionDecoder(256, true, MinecraftVarIntCodec1710, Zstd1710.LIMITS))
            val frame = Unpooled.buffer()
            when (kind) {
                0 -> {
                    MinecraftVarIntCodec1710.write(frame, 0)
                    frame.writeZero(Zstd1710.LIMITS.maximumUncompressedBytes + 1)
                }
                1 -> MinecraftVarIntCodec1710.write(frame, Zstd1710.LIMITS.maximumUncompressedBytes + 1)
                2 -> {
                    MinecraftVarIntCodec1710.write(frame, 256)
                    frame.writeZero(Zstd1710.LIMITS.maximumCompressedBytes + 1)
                }
            }
            try {
                assertFailsWith<DecoderException> { decoder.writeInbound(frame) }
            } finally {
                // Failed legacy decoding may leave unread bytes; consume before finish's final decode.
                if (frame.refCnt() > 0) frame.skipBytes(frame.readableBytes())
                finish(decoder)
            }
        }
    }

    private object LoginSuccess
    private class Payload(val bytes: ByteArray)

    private class Pair(stream: Boolean, reply: Payload? = null) {
        val server = channel()
        val client = channel(reply)

        init {
            val settings = Zstd1710.StreamSettings.read({ if (it.endsWith("enabled")) stream.toString() else "20" }, {})
            ZstdLoginSwitch1710.installServer(server, { it === LoginSuccess }, { "test-player" }, settings)
            ZstdLoginSwitch1710.installClient(client, { it === LoginSuccess })
        }

        fun login() {
            server.writeOutbound(LoginSuccess)
            client.writeInbound(drainWire(server))
            assertSame(LoginSuccess, client.readInbound())
            assertNull(client.readInbound())
        }

        fun close() {
            try { finish(server) } finally { finish(client) }
        }
    }

    /** Copies each complete frame exactly as vanilla MessageDeserializer2 does. */
    private class Splitter : ByteToMessageDecoder() {
        override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
            input.markReaderIndex()
            var size = 0
            repeat(5) { index ->
                if (!input.isReadable) { input.resetReaderIndex(); return }
                val byte = input.readUnsignedByte().toInt()
                size = size or ((byte and 127) shl (index * 7))
                if (byte and 128 == 0) {
                    check(size >= 0)
                    if (input.readableBytes() < size) input.resetReaderIndex() else output.add(input.readBytes(size))
                    return
                }
            }
            throw DecoderException("Frame VarInt too big")
        }
    }

    private class Decoder : MessageToMessageDecoder<ByteBuf>() {
        override fun decode(context: ChannelHandlerContext, msg: ByteBuf, output: MutableList<Any>) {
            if (msg.readByte().toInt() == 0) {
                check(!msg.isReadable)
                output.add(LoginSuccess)
            } else {
                output.add(Payload(ByteArray(msg.readableBytes()).also { msg.readBytes(it) }))
            }
        }
    }

    private class Encoder : MessageToByteEncoder<Any>() {
        override fun encode(context: ChannelHandlerContext, msg: Any, output: ByteBuf) {
            if (msg === LoginSuccess) output.writeByte(0) else {
                output.writeByte(1)
                output.writeBytes((msg as Payload).bytes)
            }
        }
    }

    private class Prepender : MessageToByteEncoder<ByteBuf>() {
        override fun encode(context: ChannelHandlerContext, msg: ByteBuf, output: ByteBuf) {
            MinecraftVarIntCodec1710.write(output, msg.readableBytes())
            output.writeBytes(msg, msg.readerIndex(), msg.readableBytes())
        }
    }

    private companion object {
        fun channel(reply: Payload? = null): EmbeddedChannel = EmbeddedChannel(ChannelInboundHandlerAdapter()).apply {
            pipeline().addFirst("splitter", Splitter())
            pipeline().addAfter("splitter", "decoder", Decoder())
            pipeline().addAfter("decoder", "prepender", Prepender())
            pipeline().addAfter("prepender", "encoder", Encoder())
            pipeline().addAfter("encoder", "packet_handler", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(context: ChannelHandlerContext, msg: Any) {
                    if (msg === LoginSuccess && reply != null) context.writeAndFlush(reply)
                    context.fireChannelRead(msg)
                }
            })
        }

        fun drainWire(channel: EmbeddedChannel): ByteBuf {
            val wire = Unpooled.buffer()
            try {
                while (true) {
                    val frame = channel.readOutbound() as ByteBuf? ?: break
                    try { wire.writeBytes(frame) } finally { frame.release() }
                }
                return wire
            } catch (error: Throwable) {
                wire.release()
                throw error
            }
        }

        fun readPayloads(channel: EmbeddedChannel): List<Payload> = buildList {
            while (true) add(channel.readInbound() as Payload? ?: break)
        }

        fun assertPackets(expected: List<Payload>, actual: List<Payload>) {
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (a, b) -> assertContentEquals(a.bytes, b.bytes) }
        }

        fun finish(channel: EmbeddedChannel) {
            try { channel.finish() } finally {
                while (true) ReferenceCountUtil.release(channel.readInbound() ?: break)
                while (true) ReferenceCountUtil.release(channel.readOutbound() ?: break)
            }
        }
    }
}
