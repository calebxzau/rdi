package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.server.network.MinecraftVarIntCodec211
import calebxzau.rdi.mc.zstdcodec.*
import calebxzau.rdi.mc.zstdcodec.v21.ExtensionDecision21
import calebxzau.rdi.mc.zstdcodec.v21.PacketRefSettings21
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import kotlin.test.*

class RServerPacketRefsTest {
    private val defaults = PacketRefSettings21(true, 256, 1024)

    @Test
    fun `settings are stable per connection and reconnect reads new settings`() {
        val first = channel()
        val second = channel()
        try {
            assertTrue(RServerPacketRefs.settings(first) { null }.enabled)
            assertTrue(RServerPacketRefs.settings(first) { "false" }.enabled)
            assertFalse(RServerPacketRefs.settings(second) { if (it == "rdi.pktref.enabled") "false" else null }.enabled)
        } finally {
            first.finishAndReleaseAll()
            second.finishAndReleaseAll()
        }
    }

    @Test
    fun `disabled absent memory and foreign connections never emit START`() {
        val server = channel()
        try {
            assertEquals(ExtensionDecision21.Disabled, RServerPacketRefs.activate(server, defaults.copy(enabled = false), false, true))
            assertEquals(ExtensionDecision21.MemoryConnection, RServerPacketRefs.activate(server, defaults, true, true))
            assertEquals(ExtensionDecision21.RemoteAbsent, RServerPacketRefs.activate(server, defaults, false, false))
            assertEquals(ExtensionDecision21.CompressionOff, RServerPacketRefs.activate(server, defaults, false, true))
            server.pipeline().addBefore("prepender", "compress", ChannelOutboundHandlerAdapter())
            assertEquals(ExtensionDecision21.ForeignEncoder, RServerPacketRefs.activate(server, defaults, false, true))
            assertNull(server.readOutbound<Any>())
        } finally { server.finishAndReleaseAll() }
    }

    @Test
    fun `START happens once even when a downstream write reenters activation`() {
        val server = channel()
        try {
            setup(server)
            var writes = 0
            server.pipeline().addBefore("compress", "reenter", object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    writes++
                    RServerPacketRefs.activate(server, defaults, false, true)
                    ctx.write(msg, promise)
                }
            })
            assertEquals(ExtensionDecision21.Enable, RServerPacketRefs.activate(server, defaults, false, true))
            RServerPacketRefs.activate(server, defaults, false, true)
            assertEquals(1, writes)
            assertTrue(ZstdCompressionPipeline.isOutboundPacketRefsEnabled(server))
        } finally { server.finishAndReleaseAll() }
    }

    @Test
    fun `references round trip with every batching and streaming combination`() {
        for (batching in listOf(false, true)) for (streaming in listOf(false, true)) {
            val server = channel()
            val client = channel()
            val samples = mutableListOf<ZstdBatchSample>()
            try {
                setup(server)
                setup(client)
                ZstdCompressionPipeline.setBatchObserver(server, object : ZstdBatchObserver {
                    override fun batchFlushed(sample: ZstdBatchSample) { samples += sample }
                })
                assertTrue(ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(client, true))
                if (streaming) {
                    assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(client, true))
                    ZstdCompressionPipeline.setOutboundStream(server, 25)
                }
                if (batching) {
                    ZstdCompressionPipeline.setInboundBatchingIfAvailable(client, true)
                    ZstdCompressionPipeline.setOutboundBatching(server, true, delayUnassociated = false)
                }
                RServerPacketRefs.activate(server, defaults, false, true)
                RServerPacketRefs.activate(server, defaults, false, true)
                val expected = mutableListOf<ByteArray>()
                fun send(bytes: ByteArray, policy: ZstdBatchPolicy) {
                    expected += bytes
                    server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(bytes), policy, null))
                }
                val repeated = ByteArray(32) { (it * 19).toByte() }
                send(repeated, ZstdBatchPolicy.Immediate)
                send(repeated, ZstdBatchPolicy.Immediate)
                send(ByteArray(300) { 7 }, ZstdBatchPolicy.OneTick)
                send(ByteArray(300) { 8 }, ZstdBatchPolicy.OneTick)
                ZstdCompressionPipeline.flushAtTickEnd(server)
                send(repeated, ZstdBatchPolicy.Immediate)
                server.flushOutbound()
                while (true) client.writeInbound(server.readOutbound<ByteBuf>() ?: break)
                val actual = mutableListOf<ByteArray>()
                while (true) {
                    val decoded = client.readInbound<ByteBuf>() ?: break
                    try { actual += ByteArray(decoded.readableBytes()).also(decoded::readBytes) }
                    finally { decoded.release() }
                }
                assertEquals(expected.size, actual.size)
                expected.zip(actual).forEach { (a, b) -> assertContentEquals(a, b) }
                assertEquals(if (streaming) 2 else 1, samples.count { it.frameKind == ZstdBatchFrameKind.Control })
                assertTrue(samples.any { it.frameKind == ZstdBatchFrameKind.Ref })
            } finally {
                server.finishAndReleaseAll()
                client.finishAndReleaseAll()
            }
        }
    }

    private fun channel() = EmbeddedChannel().apply {
        freezeTime()
        pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
    }

    private fun setup(channel: EmbeddedChannel) =
        ZstdCompressionPipeline.setup(channel, 64, true, MinecraftVarIntCodec211.INSTANCE)
}
