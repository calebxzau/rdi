package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.server.network.MinecraftVarIntCodec211
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.MessageToMessageEncoder
import io.netty.handler.flow.FlowControlHandler
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketMetricsPipelineTest {
    @Test
    fun `stream start is excluded and segments retain packet attribution`() {
        val harness = Harness(threshold = 32)
        try {
            ZstdCompressionPipeline.setOutboundStream(harness.channel, 20)
            assertTrue(harness.samples.isEmpty())
            val start = harness.channel.readOutbound<ByteBuf>()
            assertTrue(start != null)
            start.release()
            harness.encoder.bodySize = 2048
            val frame = harness.writePacket(99)
            assertEquals(99L, packetId(harness.samples.single().packet))
            assertEquals(innerFrameSize(frame), harness.samples.single().bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `records compressed inner frame bytes in both directions`() {
        val harness = Harness(threshold = 32)
        try {
            harness.encoder.bodySize = 2048
            val wireFrame = harness.writePacket(42)
            val sentSample = harness.samples.single()
            assertEquals(42L, packetId(sentSample.packet))
            assertEquals(PacketDirection.S2C, sentSample.direction)
            assertEquals(innerFrameSize(wireFrame), sentSample.bytes)
            assertTrue(sentSample.bytes < 2048)

            harness.samples.clear()
            harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame))
            val received = harness.channel.readInbound<ServerboundKeepAlivePacket>()
            assertEquals(42L, received.id)
            val receivedSample = harness.samples.single()
            assertEquals(42L, packetId(receivedSample.packet))
            assertEquals(PacketDirection.C2S, receivedSample.direction)
            assertEquals(innerFrameSize(wireFrame), receivedSample.bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `records actual envelope bytes below threshold and when disabled`() {
        val harness = Harness(threshold = 32)
        try {
            harness.encoder.bodySize = 31
            val belowThreshold = harness.writePacket(1)
            assertEquals(innerFrameSize(belowThreshold), harness.samples.last().bytes)

            harness.setThreshold(-1)
            harness.encoder.bodySize = 2048
            val disabled = harness.writePacket(2)
            assertEquals(innerFrameSize(disabled), harness.samples.last().bytes)
            assertEquals(2048, harness.samples.last().bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `threshold changes preserve sample association across consecutive frames`() {
        val harness = Harness(threshold = 64)
        try {
            harness.encoder.bodySize = 2048
            val compressed = harness.writePacket(10)
            harness.setThreshold(4096)
            val raw = harness.writePacket(11)
            harness.setThreshold(-1)
            harness.setThreshold(16)
            val compressedAgain = harness.writePacket(12)

            assertEquals(listOf(10L, 11L, 12L), harness.samples.map { packetId(it.packet) })
            assertTrue(harness.samples.all { it.direction == PacketDirection.S2C })
            assertEquals(
                listOf(compressed, raw, compressedAgain).map(::innerFrameSize),
                harness.samples.map { it.bytes },
            )
            assertTrue(harness.samples[0].bytes < harness.samples[1].bytes)
            assertTrue(harness.samples[2].bytes < harness.samples[1].bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `channels keep independent packet attribution`() {
        val first = Harness(threshold = 16)
        val second = Harness(threshold = 16)
        try {
            first.encoder.bodySize = 1024
            second.encoder.bodySize = 2048
            first.writePacket(21)
            second.writePacket(22)
            first.writePacket(23)
            assertEquals(listOf(21L, 23L), first.samples.map { packetId(it.packet) })
            assertEquals(listOf(22L), second.samples.map { packetId(it.packet) })
            assertTrue(first.samples.all { it.direction == PacketDirection.S2C })
            assertTrue(second.samples.all { it.direction == PacketDirection.S2C })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `flow control keeps compressed frame size with a queued frame across decoder replacement`() {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = 1024
            val first = harness.writePacket(24)
            val second = harness.writePacket(25)
            harness.samples.clear()
            harness.channel.config().isAutoRead = false
            harness.channel.writeInbound(Unpooled.wrappedBuffer(first), Unpooled.wrappedBuffer(second))
            assertTrue(harness.samples.isEmpty())

            harness.setThreshold(32)
            val temporary = ChannelInboundHandlerAdapter()
            harness.channel.pipeline().replace("decoder", "decoder", temporary)
            harness.decoder = FakePacketDecoder()
            harness.channel.pipeline().replace("decoder", "decoder", harness.decoder)
            harness.channel.config().isAutoRead = true
            harness.channel.read()

            assertEquals(listOf(24L, 25L), harness.samples.map { packetId(it.packet) })
            assertEquals(listOf(innerFrameSize(first), innerFrameSize(second)), harness.samples.map { it.bytes })
            assertTrue(harness.samples.all { it.direction == PacketDirection.C2S })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `closing with flow controlled frames releases queued buffers`() {
        val harness = Harness(threshold = 16)
        try {
            harness.setThreshold(-1)
            harness.encoder.bodySize = 1024
            val frame = harness.writePacket(26)
            harness.samples.clear()
            val queuedBuffers = mutableListOf<ByteBuf>()
            harness.channel.pipeline().addBefore("flow", "queued-buffer-tap", object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    queuedBuffers += (msg as ByteBufHolder).content()
                    ctx.fireChannelRead(msg)
                }
            })
            harness.channel.config().isAutoRead = false
            harness.channel.writeInbound(Unpooled.wrappedBuffer(frame))
            assertEquals(1, queuedBuffers.size)
            assertTrue(queuedBuffers.single().refCnt() > 0)
            harness.close()
            assertEquals(0, queuedBuffers.single().refCnt())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `codec failures do not attribute stale bytes or packets to the next frame`() {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = 1024
            harness.encoder.failNext = true
            assertFails { harness.writePacket(30) }
            assertTrue(harness.samples.isEmpty())

            harness.writePacket(31)
            assertEquals(listOf(31L), harness.samples.map { packetId(it.packet) })
            assertEquals(PacketDirection.S2C, harness.samples.single().direction)

            val goodWireFrame = harness.writePacket(32)
            harness.samples.clear()
            harness.decoder.failNext = true
            assertFails { harness.channel.writeInbound(Unpooled.wrappedBuffer(goodWireFrame)) }
            assertTrue(harness.samples.isEmpty())
            harness.channel.writeInbound(Unpooled.wrappedBuffer(goodWireFrame))
            assertEquals(listOf(32L), harness.samples.map { packetId(it.packet) })
            assertEquals(PacketDirection.C2S, harness.samples.single().direction)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `batching connections leave outbound frames unattributed`() {
        val harness = Harness(threshold = 16)
        try {
            ZstdCompressionPipeline.setOutboundBatching(harness.channel, true, delayUnassociated = false)
            harness.encoder.bodySize = 1024
            val frame = harness.writePacket(50)
            assertTrue(frame.isNotEmpty())
            assertTrue(harness.samples.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun `an oversized packet closes the connection without recording a sample`() {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH + 1
            assertFails { harness.writePacket(33) }
            assertTrue(harness.samples.isEmpty())
            assertFalse(harness.channel.isActive)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `record callback failure does not interrupt packet forwarding`() {
        val harness = Harness(threshold = 16, recordFailure = true)
        try {
            harness.encoder.bodySize = 1024
            val frame = harness.writePacket(40)
            assertTrue(frame.isNotEmpty())
            harness.channel.writeInbound(Unpooled.wrappedBuffer(frame))
            assertEquals(40L, harness.channel.readInbound<ServerboundKeepAlivePacket>().id)
        } finally {
            harness.close()
        }
    }

    private class Harness(threshold: Int, recordFailure: Boolean = false) {
        val samples = mutableListOf<CapturedSample>()
        val encoder = FakePacketEncoder()
        var decoder = FakePacketDecoder()
        val channel = EmbeddedChannel()

        init {
            channel.pipeline().addLast("splitter", OuterFrameSplitter())
            channel.pipeline().addLast("flow", FlowControlHandler())
            channel.pipeline().addLast("decoder", decoder)
            channel.pipeline().addLast("prepender", OuterFramePrepender())
            channel.pipeline().addLast("encoder", encoder)
            ZstdCompressionPipeline.setup(channel, threshold, true, MinecraftVarIntCodec211.INSTANCE)
            PacketMetricsPipeline.install(channel.pipeline()) { packet, direction, bytes ->
                samples += CapturedSample(packet, direction, bytes)
                if (recordFailure) throw IllegalStateException("test record failure")
            }
        }

        fun setThreshold(threshold: Int) {
            ZstdCompressionPipeline.setup(channel, threshold, true, MinecraftVarIntCodec211.INSTANCE)
            PacketMetricsPipeline.compressionChanged(channel.pipeline())
        }

        fun writePacket(id: Long): ByteArray {
            assertTrue(channel.writeOutbound(ServerboundKeepAlivePacket(id)))
            val frame = channel.readOutbound<ByteBuf>() ?: error("packet encoder produced no frame")
            return try {
                ByteArray(frame.readableBytes()).also(frame::readBytes)
            } finally {
                frame.release()
            }
        }

        fun close() {
            channel.finishAndReleaseAll()
        }
    }

    private data class CapturedSample(
        val packet: Packet<*>,
        val direction: PacketDirection,
        val bytes: Int,
    )

    private class FakePacketEncoder : MessageToMessageEncoder<Packet<*>>() {
        var bodySize = 8
        var failNext = false

        override fun encode(ctx: ChannelHandlerContext, packet: Packet<*>, out: MutableList<Any>) {
            PacketMetricsPipeline.encoded(ctx, packet)
            if (failNext) {
                failNext = false
                throw IllegalStateException("test encoder failure")
            }
            val id = packetId(packet)
            val body = Unpooled.buffer(bodySize).writeLong(id)
            repeat((bodySize - Long.SIZE_BYTES).coerceAtLeast(0)) { index -> body.writeByte((id + index).toInt()) }
            out += body
        }
    }

    private class FakePacketDecoder : MessageToMessageDecoder<ByteBuf>() {
        var failNext = false

        override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("test decoder failure")
            }
            val id = input.readLong()
            val packet = ServerboundKeepAlivePacket(id)
            PacketMetricsPipeline.decoded(ctx, packet)
            out += packet
        }
    }

    private class OuterFrameSplitter : ByteToMessageDecoder() {
        override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
            if (!input.isReadable) return
            input.markReaderIndex()
            val size = readVarInt(input)
            if (input.readableBytes() < size) {
                input.resetReaderIndex()
                return
            }
            out += input.readRetainedSlice(size)
        }
    }

    private class OuterFramePrepender : MessageToMessageEncoder<ByteBuf>() {
        override fun encode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
            val size = input.readableBytes()
            val framed = ctx.alloc().buffer(varIntSize(size) + size)
            writeVarInt(framed, size)
            framed.writeBytes(input, input.readerIndex(), size)
            out += framed
        }
    }

    private companion object {
        fun packetId(packet: Packet<*>): Long = (packet as ServerboundKeepAlivePacket).id

        fun innerFrameSize(outerFrame: ByteArray): Int {
            val prefix = varIntSizeFrom(outerFrame)
            return outerFrame.size - prefix
        }

        fun varIntSizeFrom(bytes: ByteArray): Int {
            var index = 0
            while (index < bytes.size && index < 5) {
                if (bytes[index++].toInt() and 0x80 == 0) return index
            }
            error("invalid outer frame length")
        }

        fun varIntSize(value: Int): Int {
            var remaining = value
            var size = 1
            while (remaining and 0xFFFFFF80.toInt() != 0) {
                size++
                remaining = remaining ushr 7
            }
            return size
        }

        fun readVarInt(buffer: ByteBuf): Int = MinecraftVarIntCodec211.INSTANCE.read(buffer)

        fun writeVarInt(buffer: ByteBuf, value: Int) {
            MinecraftVarIntCodec211.INSTANCE.write(buffer, value)
        }
    }
}
