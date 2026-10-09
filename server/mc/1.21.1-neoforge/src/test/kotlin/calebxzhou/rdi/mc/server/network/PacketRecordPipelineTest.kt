package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.server.network.MinecraftVarIntCodec211
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.codec.MessageToMessageEncoder
import net.minecraft.network.protocol.Packet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketRecordPipelineTest {
    @Test
    fun `attribute updates wait and an immediate packet sends them first in order`() = withServer { server ->
        val first = server.write(attributes(1))
        val second = server.write(attributes(2))
        assertFalse(first.isDone)
        assertFalse(second.isDone)
        assertTrue(server.frames().isEmpty())

        val barrier = server.write(keepAlive(3))
        assertTrue(first.isSuccess && second.isSuccess && barrier.isSuccess)
        assertEquals(listOf(1, 2, 3), server.decodeAll())
    }

    @Test
    fun `tick end flushes buffered attribute updates`() = withServer { server ->
        val pending = server.write(attributes(4))
        assertFalse(pending.isDone)
        ZstdCompressionPipeline.flushAtTickEnd(server.channel)
        server.channel.flushOutbound()
        assertTrue(pending.isSuccess)
        assertEquals(listOf(4), server.decodeAll())
    }

    @Test
    fun `an unassociated buffer is sent immediately`() = withServer { server ->
        server.write(attributes(5))
        val bare = server.channel.writeOneOutbound(Unpooled.buffer().writeInt(6))
        server.channel.flushOutbound()
        assertTrue(bare.isSuccess)
        assertEquals(listOf(5, 6), server.decodeAll())
    }

    @Test
    fun `bundle expansion keeps each child classified and ordered`() = withServer { server ->
        server.channel.pipeline().addAfter("encoder", "unbundler", FakeUnbundler())
        val bundle = server.write(FakeBundle(listOf(keepAlive(10), attributes(11), keepAlive(12))))
        assertTrue(bundle.isSuccess)
        assertEquals(listOf(10, 11, 12), server.decodeAll())
    }

    @Test
    fun `a failed encode cannot tag the next buffer`() = withServer { server ->
        server.encoder.failNext = true
        val failed = server.write(attributes(20))
        assertTrue(failed.isDone)
        assertFalse(failed.isSuccess)
        val next = server.channel.writeOneOutbound(Unpooled.buffer().writeInt(21))
        server.channel.flushOutbound()
        assertTrue(next.isSuccess)
        val later = server.write(attributes(22))
        assertFalse(later.isDone)
        server.write(keepAlive(23))
        assertEquals(listOf(21, 22, 23), server.decodeAll())
    }

    @Test
    fun `without selective batching every packet is sent at once`() = withServer(selective = false) { server ->
        val attribute = server.write(attributes(30))
        assertTrue(attribute.isSuccess)
        assertEquals(listOf(30), server.decodeAll())
    }

    private fun attributes(tag: Int) = TestPacket21.attributes(tag)

    private fun keepAlive(tag: Int) = TestPacket21.other(tag)

    private fun withServer(selective: Boolean = true, test: (Server) -> Unit) {
        val server = Server(selective)
        try {
            test(server)
        } finally {
            server.close()
        }
    }

    private class Server(selective: Boolean) {
        val encoder = FakePacketEncoder()
        val channel = EmbeddedChannel()
        private val client = EmbeddedChannel()
        private val sent = ArrayList<ByteBuf>()

        init {
            channel.freezeTime()
            client.freezeTime()
            for (target in listOf(channel, client)) {
                target.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
                target.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
                target.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
            }
            channel.pipeline().addLast("encoder", encoder)
            client.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
            ZstdCompressionPipeline.setup(channel, 16, true, MinecraftVarIntCodec211.INSTANCE)
            ZstdCompressionPipeline.setup(client, 16, true, MinecraftVarIntCodec211.INSTANCE)
            assertTrue(ZstdCompressionPipeline.setInboundBatchingIfAvailable(client, true))
            PacketRecordPipeline.install(channel.pipeline())
            if (selective) {
                assertTrue(PacketRecordPipeline.enableSelective(channel))
                ZstdCompressionPipeline.setOutboundBatching(channel, true, delayUnassociated = false)
            }
        }

        fun write(message: Any): ChannelFuture {
            val future = channel.writeOneOutbound(message)
            channel.flushOutbound()
            return future
        }

        fun frames(): List<ByteBuf> {
            while (true) sent += channel.readOutbound<ByteBuf>() ?: break
            return sent
        }

        fun decodeAll(): List<Int> {
            val tags = ArrayList<Int>()
            for (frame in frames()) {
                client.writeInbound(frame)
                while (true) {
                    val record = client.readInbound<ByteBuf>() ?: break
                    try {
                        tags += record.readInt()
                    } finally {
                        record.release()
                    }
                }
            }
            sent.clear()
            return tags
        }

        fun close() {
            sent.forEach { it.release() }
            runCatching { channel.finishAndReleaseAll() }
            runCatching { client.finishAndReleaseAll() }
        }
    }

    private class FakeBundle(val packets: List<Packet<*>>)

    /** Stands in for vanilla's bundle unpacker, which sits between the encoder and the outbound scope. */
    private class FakeUnbundler : MessageToMessageEncoder<FakeBundle>() {
        override fun encode(ctx: ChannelHandlerContext, msg: FakeBundle, out: MutableList<Any>) {
            out.addAll(msg.packets)
        }
    }

    /** Mirrors vanilla's encoder and the RETURN hook that reports each successful output buffer. */
    private class FakePacketEncoder : MessageToByteEncoder<Packet<*>>() {
        var failNext = false

        override fun encode(ctx: ChannelHandlerContext, packet: Packet<*>, out: ByteBuf) {
            out.writeInt((packet as TestPacket21).tag)
            out.writeZero(48)
            // Tag first, then fail: the stale entry must die with its write scope.
            PacketRecordPipeline.encoded(ctx, packet, out)
            if (failNext) {
                failNext = false
                throw IllegalStateException("test encoder failure")
            }
        }
    }
}
