package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import io.netty.handler.flow.FlowControlHandler
import kotlin.test.*

class ZstdTransportGuardTest {
    @Test
    fun `zero negotiated capabilities leave connection unarmed`() {
        val channel = endpoint()
        try {
            assertFalse(ZstdInboundPreparation.armInbound(channel, 0).join())
            assertFalse(ZstdTransportGuard.get(channel).isArmed)
            channel.pipeline().remove("decompress")
            assertTrue(channel.isActive)
        } finally { channel.finishAndReleaseAll() }
    }

    @Test
    fun `compression off skips but a foreign decoder fails preparation`() {
        val off = endpoint()
        val foreign = endpoint()
        try {
            off.pipeline().remove("decompress")
            assertFalse(ZstdInboundPreparation.armInbound(off, 1).join())
            assertTrue(off.isActive)
            foreign.pipeline().replace("decompress", "decompress", ChannelInboundHandlerAdapter())
            assertFails { ZstdInboundPreparation.armInbound(foreign, 1).join() }
            assertFalse(foreign.isActive)
        } finally { off.finishAndReleaseAll(); foreign.finishAndReleaseAll() }
    }

    @Test
    fun `all feature combinations reconstruct fragmented and concatenated framed records`() {
        for (mask in 0..7) {
            val server = endpoint()
            val client = endpoint()
            try {
                ZstdInboundPreparation.armInbound(client, mask).join()
                if (mask and 1 != 0) ZstdCompressionPipeline.setOutboundStream(server, 20)
                if (mask and 4 != 0) ZstdCompressionPipeline.setOutboundPacketRefs(server)
                if (mask and 2 != 0) ZstdCompressionPipeline.setOutboundBatching(server, true)
                val expected = List(6) { index -> ByteArray(300) { (it % 17 + index % 2).toByte() } }
                for (record in expected) server.writeOneOutbound(Unpooled.wrappedBuffer(record))
                ZstdCompressionPipeline.flushBatched(server)
                server.flushOutbound()
                val wire = Unpooled.buffer()
                while (true) {
                    val frame = server.readOutbound<ByteBuf>() ?: break
                    wire.writeBytes(frame)
                    frame.release()
                }
                // The same test exercises one-byte fragments and a concatenated tail.
                repeat(minOf(12, wire.readableBytes())) { client.writeInbound(wire.readRetainedSlice(1)) }
                client.writeInbound(wire)
                for (record in expected) {
                    val actual = assertNotNull(client.readInbound<ByteBuf>())
                    assertContentEquals(record, ByteArray(actual.readableBytes()).also(actual::readBytes), "mask=$mask")
                    actual.release()
                }
                assertNull(client.readInbound<Any>())
                ZstdInboundPreparation.armInbound(client, mask).join() // Idempotent preparation.
                if (mask and 1 != 0) {
                    ZstdCompressionPipeline.setOutboundStream(server, 20)
                    assertNull(server.readOutbound<Any>())
                }
            } finally { server.finishAndReleaseAll(); client.finishAndReleaseAll() }
        }
    }

    @Test
    fun `replacement handler cannot emit bytes before old removal callback`() {
        val server = endpoint()
        val bytes = Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3))
        try {
            ZstdCompressionPipeline.setOutboundStream(server, 20)
            drain(server)
            server.pipeline().replace("compress", "compress", object : ChannelOutboundHandlerAdapter() {
                override fun handlerAdded(ctx: ChannelHandlerContext) { ctx.writeAndFlush(bytes) }
            })
            assertFalse(server.isActive)
            assertEquals(0, bytes.refCnt())
            assertNull(server.readOutbound<Any>())
        } finally { server.finishAndReleaseAll() }
    }

    @Test
    fun `a record count cannot authorize a foreign buffer`() {
        val client = endpoint()
        val expected = Unpooled.wrappedBuffer(byteArrayOf(1))
        val intruder = Unpooled.wrappedBuffer(byteArrayOf(2))
        try {
            ZstdInboundPreparation.armInbound(client, 1).join()
            val ctx = client.pipeline().context("decompress")
            ZstdTransportGuard.get(client).decoded(ctx, expected)
            ctx.fireChannelRead(intruder)
            assertFalse(client.isActive)
            assertEquals(0, intruder.refCnt())
            assertNull(client.readInbound<Any>())
        } finally { expected.release(); client.finishAndReleaseAll() }
    }

    @Test
    fun `removing armed codec or guard terminates the session`() {
        for (handler in listOf("decompress", "rdi_zstd_inbound_guard")) {
            val client = endpoint()
            try {
                ZstdInboundPreparation.armInbound(client, 1).join()
                client.pipeline().remove(handler)
                assertFalse(client.isActive)
            } finally { client.finishAndReleaseAll() }
        }
    }

    @Test
    fun `terminal failure prevents late preparation`() {
        val client = endpoint()
        try {
            ZstdInboundPreparation.abort(client, IllegalStateException("preparation timed out"))
            assertFails { ZstdInboundPreparation.armInbound(client, 1).join() }
            assertFalse(ZstdTransportGuard.get(client).inboundArmed)
            assertFalse(client.isActive)
        } finally { client.finishAndReleaseAll() }
    }

    @Test
    fun `flow control queues only verified records`() {
        val server = endpoint()
        val client = endpoint()
        try {
            client.config().isAutoRead = false
            ZstdInboundPreparation.armInbound(client, 1).join()
            ZstdCompressionPipeline.setOutboundStream(server, 20)
            val data = ByteArray(300) { 42 }
            server.writeOutbound(Unpooled.wrappedBuffer(data))
            while (true) client.writeInbound(server.readOutbound<ByteBuf>() ?: break)
            assertNull(client.readInbound<Any>())
            assertTrue((client.pipeline().get("decompress") as ZstdCompressionDecoder).isStreamActive())
            client.config().isAutoRead = true
            client.read()
            val actual = assertNotNull(client.readInbound<ByteBuf>())
            assertContentEquals(data, ByteArray(actual.readableBytes()).also(actual::readBytes))
            actual.release()
        } finally { server.finishAndReleaseAll(); client.finishAndReleaseAll() }
    }

    @Test
    fun `decoder removal cannot forward residual undecoded bytes`() {
        val client = endpoint()
        val residual = Unpooled.wrappedBuffer(byteArrayOf(9, 8, 7))
        try {
            ZstdInboundPreparation.armInbound(client, 1).join()
            val decoder = client.pipeline().get("decompress")
            // Exercise ByteToMessageDecoder's actual removal path with unread cumulation.
            io.netty.handler.codec.ByteToMessageDecoder::class.java.getDeclaredField("cumulation")
                .apply { isAccessible = true }.set(decoder, residual)
            client.pipeline().remove(decoder)
            assertFalse(client.isActive)
            assertEquals(0, residual.refCnt())
            assertNull(client.readInbound<Any>())
        } finally { client.finishAndReleaseAll() }
    }

    @Test
    fun `reentrant intruder cannot spend a second decoded record identity`() {
        val client = endpoint()
        val first = Unpooled.wrappedBuffer(byteArrayOf(1))
        val second = Unpooled.wrappedBuffer(byteArrayOf(2))
        val intruder = Unpooled.wrappedBuffer(byteArrayOf(3))
        try {
            ZstdInboundPreparation.armInbound(client, 2).join()
            val codec = client.pipeline().context("decompress")
            val guard = ZstdTransportGuard.get(client)
            guard.decoded(codec, first)
            guard.decoded(codec, second)
            client.pipeline().addAfter("rdi_zstd_inbound_guard", "reenter", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    (msg as ByteBuf).release()
                    codec.fireChannelRead(intruder)
                }
            })
            codec.fireChannelRead(first)
            assertFalse(client.isActive)
            assertEquals(0, first.refCnt())
            assertEquals(0, intruder.refCnt())
            assertNull(client.readInbound<Any>())
        } finally { second.release(); client.finishAndReleaseAll() }
    }

    companion object {
        internal val VAR_INT = object : MinecraftVarIntCodec {
            override fun read(buffer: ByteBuf): Int {
                var value = 0
                for (shift in 0..28 step 7) {
                    val byte = buffer.readUnsignedByte().toInt()
                    value = value or ((byte and 127) shl shift)
                    if (byte and 128 == 0) return value
                }
                error("Invalid VarInt")
            }
            override fun write(buffer: ByteBuf, value: Int) {
                var remaining = value
                while (remaining and -128 != 0) {
                    buffer.writeByte(remaining and 127 or 128)
                    remaining = remaining ushr 7
                }
                buffer.writeByte(remaining)
            }
        }

        internal fun endpoint() = EmbeddedChannel().apply {
            pipeline().addLast("splitter", ProtobufVarint32FrameDecoder())
            pipeline().addLast("flow", FlowControlHandler())
            pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            pipeline().addLast("prepender", ProtobufVarint32LengthFieldPrepender())
            pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
            ZstdCompressionPipeline.setup(this, 128, false, VAR_INT)
        }

        private fun drain(channel: EmbeddedChannel) {
            while (true) (channel.readOutbound<ByteBuf>() ?: break).release()
        }
    }
}
