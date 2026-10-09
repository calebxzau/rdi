package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdBatchObserver
import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import calebxzau.rdi.mc.zstdcodec.ZstdBatchSample
import calebxzau.rdi.mc.zstdcodec.PacketCaptureRecorder
import com.github.luben.zstd.ZstdInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.util.UUID
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToMessageEncoder
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Exercises the 1.20.1 metrics pipeline without a running server. The harness mirrors the
 * vanilla handler names and ordering: splitter -> decompress -> decoder inbound, and
 * encoder -> compress -> prepender outbound, with no FlowControlHandler.
 */
class PacketMetricsPipeline20Test {
    @Test
    fun `capture keeps only custom payloads and attributes across connections and compression changes`(): Unit {
        val directory = Files.createTempDirectory("rdi-capture-pipeline")
        val recorder = PacketCaptureRecorder(directory)
        val player = UUID.fromString("00000000-0000-7000-8000-000000000001")
        val first = Harness(threshold = -1)
        val second = Harness(threshold = 16)
        val customData = FriendlyByteBuf(Unpooled.buffer().writeLong(12))
        val secondCustomData = FriendlyByteBuf(Unpooled.buffer().writeLong(22))
        try {
            assertTrue(PacketMetricsPipeline20.attachCapture(first.channel, recorder.connection(player)))
            assertTrue(PacketMetricsPipeline20.attachCapture(second.channel, recorder.connection(player)))
            first.writePacket(ClientboundUpdateAttributesPacket(11, emptyList()))
            first.writePacket(ClientboundKeepAlivePacket(80))
            first.setThreshold(16)
            first.writePacket(ClientboundCustomPayloadPacket(ResourceLocation("l2tabs", "main"), customData))
            first.setThreshold(-1)
            first.writePacket(ClientboundUpdateAttributesPacket(13, emptyList()))
            second.encoder.nestedPacket = ClientboundUpdateAttributesPacket(21, emptyList())
            second.encoder.failNext = true
            assertFails { second.writePacket(ClientboundUpdateAttributesPacket(99, emptyList())) }
            second.channel.readOutbound<ByteBuf>()?.release()
            second.writePacket(ClientboundKeepAlivePacket(81))
            second.writePacket(ClientboundCustomPayloadPacket(ResourceLocation("infinite_abyss", "infinite_abyss"), secondCustomData))
        } finally {
            customData.release()
            secondCustomData.release()
            first.close()
            second.close()
            recorder.close().getOrThrow()
        }
        val files = Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".rdibatch.zst") }.toList()
        }
        assertEquals(1, files.size)
        val records = mutableListOf<Triple<Long, Long, Long>>()
        val types = mutableListOf<String>()
        val channels = mutableListOf<String>()
        DataInputStream(ZstdInputStream(Files.newInputStream(files.single()))).use { input ->
            assertEquals(1, input.readUnsignedByte())
            assertEquals(0x52445043, input.readInt())
            assertEquals(2, input.readUnsignedShort())
            input.skipNBytes(16 + 8 + 4L)
            while (true) {
                when (val kind = input.readUnsignedByte()) {
                    2 -> repeat(input.readInt()) {
                        input.readLong() // elapsed time
                        assertEquals(player, UUID(input.readLong(), input.readLong()))
                        val connection = input.readLong()
                        val sequence = input.readLong()
                        assertEquals(0, input.readUnsignedByte()) // v2: 1.20 always records Play.
                        val type = ByteArray(input.readUnsignedShort()).also(input::readFully).toString(Charsets.UTF_8)
                        types += type.substringAfterLast('.')
                        channels += ByteArray(input.readUnsignedShort()).also(input::readFully).toString(Charsets.UTF_8)
                        assertEquals(8, input.readInt())
                        records += Triple(connection, sequence, input.readLong())
                    }
                    3 -> {
                        assertEquals(5L, input.readLong())
                        assertEquals(0L, input.readLong())
                        assertEquals(1, input.readUnsignedByte())
                        input.readLong()
                        assertEquals(-1, input.read())
                        break
                    }
                    else -> error("Unexpected capture frame kind $kind")
                }
            }
        }
        assertEquals(listOf(11L, 12L, 13L, 21L, 22L), records.map { it.third })
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L), records.map { it.second })
        assertEquals(2, records.map { it.first }.distinct().size)
        assertEquals(listOf("ClientboundUpdateAttributesPacket", "ClientboundCustomPayloadPacket",
            "ClientboundUpdateAttributesPacket", "ClientboundUpdateAttributesPacket",
            "ClientboundCustomPayloadPacket"), types)
        assertEquals(listOf("", "l2tabs:main", "", "", "infinite_abyss:infinite_abyss"), channels)
    }

    @Test
    fun `records compressed inner frame bytes in both directions`(): Unit {
        val harness = Harness(threshold = 32)
        try {
            harness.encoder.bodySize = 2048
            val wireFrame = harness.writePacket(42)
            val sent = harness.samples.single()
            assertEquals(42L, packetId(sent.packet))
            assertEquals(PacketDirection.S2C, sent.direction)
            assertEquals(innerFrameSize(wireFrame), sent.bytes)
            assertTrue(sent.bytes < 2048)

            harness.samples.clear()
            harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame))
            val received = harness.channel.readInbound<ServerboundKeepAlivePacket>()
            assertEquals(42L, received.id)
            val inbound = harness.samples.single()
            assertEquals(42L, packetId(inbound.packet))
            assertEquals(PacketDirection.C2S, inbound.direction)
            assertEquals(innerFrameSize(wireFrame), inbound.bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `records actual envelope bytes below threshold and when compression is disabled`(): Unit {
        val harness = Harness(threshold = 32)
        try {
            harness.encoder.bodySize = 31
            val belowThreshold = harness.writePacket(1)
            assertEquals(innerFrameSize(belowThreshold), harness.samples.last().bytes)
            assertEquals(PacketDirection.S2C, harness.samples.last().direction)

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
    fun `threshold changes keep sample association across consecutive frames`(): Unit {
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
    fun `metrics installed before compression still measure the compressed envelope`(): Unit {
        val harness = Harness(threshold = -1, installBeforeCompression = true)
        try {
            harness.setThreshold(32)
            harness.encoder.bodySize = 2048
            val frame = harness.writePacket(50)

            val sample = harness.samples.single()
            assertEquals(PacketDirection.S2C, sample.direction)
            assertEquals(innerFrameSize(frame), sample.bytes)
            assertTrue(sample.bytes < 2048)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `two frames in one read keep their own sizes`(): Unit {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = 512
            val first = harness.writePacket(24)
            val second = harness.writePacket(25)
            harness.samples.clear()

            harness.channel.writeInbound(Unpooled.wrappedBuffer(first, second))

            assertEquals(listOf(24L, 25L), harness.samples.map { packetId(it.packet) })
            assertEquals(
                listOf(innerFrameSize(first), innerFrameSize(second)),
                harness.samples.map { it.bytes },
            )
            assertTrue(harness.samples.all { it.direction == PacketDirection.C2S })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `channels keep independent state`(): Unit {
        val first = Harness(threshold = 64)
        val second = Harness(threshold = 64)
        try {
            first.encoder.bodySize = 1024
            second.encoder.bodySize = 1024
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
    fun `failed and empty frames do not attribute stale bytes to the next packet`(): Unit {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = 1024
            harness.encoder.failNext = true
            assertFails { harness.writePacket(30) }
            assertTrue(harness.samples.isEmpty())

            harness.encoder.bodySize = 1024
            val wireFrame = harness.writePacket(31)
            assertEquals(listOf(31L), harness.samples.map { packetId(it.packet) })
            assertEquals(innerFrameSize(wireFrame), harness.samples.single().bytes)

            harness.samples.clear()
            harness.decoder.swallowNext = true
            harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame))
            assertTrue(harness.samples.isEmpty())
            harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame))
            assertEquals(innerFrameSize(wireFrame), harness.samples.single().bytes)

            harness.samples.clear()
            harness.decoder.failNext = true
            assertFails { harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame)) }
            assertTrue(harness.samples.isEmpty())
            harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame))
            assertEquals(PacketDirection.C2S, harness.samples.single().direction)
            assertEquals(innerFrameSize(wireFrame), harness.samples.single().bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `a frame that decodes to no packet leaves no bytes for the next frame`(): Unit {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = 1024
            val wireFrame = harness.writePacket(60)
            harness.samples.clear()

            // Outer frame whose whole content is the compression envelope of an empty payload, so
            // the packet decoder is never called for this frame.
            harness.channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x01, 0x00)))
            assertTrue(harness.samples.isEmpty())

            harness.channel.writeInbound(Unpooled.wrappedBuffer(wireFrame))
            assertEquals(listOf(60L), harness.samples.map { packetId(it.packet) })
            assertEquals(innerFrameSize(wireFrame), harness.samples.single().bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `record callback failure does not interrupt packet forwarding`(): Unit {
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

    @Test
    fun `nested encode and failed outer encode leave next packet attribution intact`(): Unit {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = 1024
            harness.encoder.nestedPacket = ServerboundKeepAlivePacket(90)
            harness.encoder.failNext = true
            assertFails { harness.writePacket(30) }
            assertEquals(listOf(90L), harness.samples.map { packetId(it.packet) })
            harness.channel.readOutbound<ByteBuf>()?.release()
            val nextFrame = harness.writePacket(31)
            assertEquals(listOf(90L, 31L), harness.samples.map { packetId(it.packet) })
            assertEquals(innerFrameSize(nextFrame), harness.samples.last().bytes)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `oversized encoded packet closes connection and contributes no frame metric`(): Unit {
        val harness = Harness(threshold = 16)
        try {
            harness.encoder.bodySize = ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH + 1
            assertFails { harness.writePacket(31) }
            assertTrue(harness.samples.isEmpty())
            assertTrue(!harness.channel.isOpen)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `selective records bind metadata to bytes and ordinary packet drains earlier queue`(): Unit {
        val channel = EmbeddedChannel()
        channel.freezeTime()
        val originals = mutableListOf<ByteArray>()
        val identities = mutableListOf<ZstdPacketIdentity?>()
        val policies = mutableListOf<ZstdBatchPolicy>()
        val frames = mutableListOf<ZstdBatchSample>()
        channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("encoder", object : MessageToMessageEncoder<Packet<*>>() {
            override fun encode(ctx: ChannelHandlerContext, packet: Packet<*>, out: MutableList<Any>) {
                // Vanilla PacketEncoder emits the allocated ByteBuf; FriendlyByteBuf is
                // only its temporary writer facade. Netty touch() may unwrap that facade.
                val bytes = ctx.alloc().buffer()
                val writer = FriendlyByteBuf(bytes)
                writer.writeVarInt(1)
                packet.write(writer)
                originals += ByteArray(bytes.readableBytes()).also { bytes.getBytes(bytes.readerIndex(), it) }
                PacketMetricsPipeline20.encoded(ctx, packet, bytes)
                out += bytes
            }
        })
        ZstdCompressionPipeline.setup(channel, 16, true, MinecraftVarIntCodec20.INSTANCE)
        PacketMetricsPipeline20.install(channel.pipeline()) { _, _, _ -> }
        PacketMetricsPipeline20.configureBatching(channel, false, emptySet())
        ZstdCompressionPipeline.setBatchObserver(channel, object : ZstdBatchObserver {
            override fun recordEncoded(identity: ZstdPacketIdentity?, encodedBytes: Int, policy: ZstdBatchPolicy) {
                identities += identity
                policies += policy
            }
            override fun batchFlushed(sample: ZstdBatchSample) { frames += sample }
        })
        ZstdCompressionPipeline.setInboundBatching(channel, true)
        ZstdCompressionPipeline.setOutboundBatching(channel, true)
        try {
            channel.writeOutbound(ClientboundUpdateAttributesPacket(1, emptyList()))
            channel.writeOutbound(ClientboundUpdateAttributesPacket(2, emptyList()))
            assertEquals(listOf(ZstdBatchPolicy.OneTick, ZstdBatchPolicy.OneTick), policies)
            assertEquals(0, frames.size)
            channel.writeOutbound(ClientboundKeepAlivePacket(33))
            assertEquals(listOf(ZstdBatchPolicy.OneTick, ZstdBatchPolicy.OneTick, ZstdBatchPolicy.Immediate), policies)
            assertEquals(listOf("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket",
                "net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket",
                "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket"), identities.map { it?.packetType })
            assertEquals(3, frames.sumOf { it.recordCount })
            while (true) {
                val frame = channel.readOutbound<ByteBuf>() ?: break
                channel.writeInbound(frame)
            }
            val decoded = mutableListOf<ByteArray>()
            while (true) {
                val packet = channel.readInbound<ByteBuf>() ?: break
                try { decoded += ByteArray(packet.readableBytes()).also(packet::readBytes) }
                finally { packet.release() }
            }
            assertEquals(originals.map { it.toList() }, decoded.map { it.toList() })
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private class Harness(
        threshold: Int,
        recordFailure: Boolean = false,
        installBeforeCompression: Boolean = false,
    ) {
        val samples = mutableListOf<CapturedSample>()
        val encoder = FakePacketEncoder()
        var decoder = FakePacketDecoder()
        val channel = EmbeddedChannel()

        init {
            channel.pipeline().addLast("splitter", OuterFrameSplitter())
            channel.pipeline().addLast("decoder", decoder)
            channel.pipeline().addLast("prepender", OuterFramePrepender())
            channel.pipeline().addLast("encoder", encoder)
            val install = {
                PacketMetricsPipeline20.install(channel.pipeline()) { packet, direction, bytes ->
                    samples += CapturedSample(packet, direction, bytes)
                    if (recordFailure) throw IllegalStateException("test record failure")
                }
            }
            if (installBeforeCompression) {
                install()
                setThreshold(threshold)
            } else {
                setThreshold(threshold)
                install()
            }
        }

        fun setThreshold(threshold: Int) {
            ZstdCompressionPipeline.setup(channel, threshold, true, MinecraftVarIntCodec20.INSTANCE)
            PacketMetricsPipeline20.compressionChanged(channel.pipeline())
        }

        fun writePacket(id: Long): ByteArray = writePacket(ServerboundKeepAlivePacket(id))

        fun writePacket(packet: Packet<*>): ByteArray {
            assertTrue(channel.writeOutbound(packet))
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
        var nestedPacket: Packet<*>? = null

        override fun encode(ctx: ChannelHandlerContext, packet: Packet<*>, out: MutableList<Any>) {
            val shouldFail = failNext
            failNext = false
            val nested = nestedPacket
            nestedPacket = null
            if (nested != null) ctx.channel().writeAndFlush(nested)
            if (shouldFail) throw IllegalStateException("test encoder failure")
            val id = packetId(packet)
            val body = Unpooled.buffer(bodySize).writeLong(id)
            repeat((bodySize - Long.SIZE_BYTES).coerceAtLeast(0)) { index -> body.writeByte((id + index).toInt()) }
            PacketMetricsPipeline20.encoded(ctx, packet, body)
            out += body
        }
    }

    /**
     * Mirrors the vanilla PacketDecoder: a real [ByteToMessageDecoder] that consumes one whole frame
     * per call, so an empty payload never reaches [decode] and a frame that produces no packet leaves
     * no unread bytes behind.
     */
    private class FakePacketDecoder : ByteToMessageDecoder() {
        var failNext = false
        var swallowNext = false

        override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
            if (failNext) {
                failNext = false
                input.skipBytes(input.readableBytes())
                throw IllegalStateException("test decoder failure")
            }
            val id = input.readLong()
            input.skipBytes(input.readableBytes())
            if (swallowNext) {
                // Mirrors PacketDecoder.decode consuming a complete frame that produces no packet.
                swallowNext = false
                PacketMetricsPipeline20.discarded(ctx)
                return
            }
            val packet = ServerboundKeepAlivePacket(id)
            out += packet
            PacketMetricsPipeline20.decoded(ctx, packet)
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
        fun packetId(packet: Packet<*>): Long = when (packet) {
            is ServerboundKeepAlivePacket -> packet.id
            is ClientboundKeepAlivePacket -> packet.id
            is ClientboundUpdateAttributesPacket -> packet.entityId.toLong()
            is ClientboundCustomPayloadPacket -> packet.data.let { data ->
                try { data.getLong(data.readerIndex()) } finally { data.release() }
            }
            else -> error("Unsupported test packet ${packet.javaClass.name}")
        }

        fun innerFrameSize(outerFrame: ByteArray): Int = outerFrame.size - varIntSizeFrom(outerFrame)

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

        fun readVarInt(buffer: ByteBuf): Int = MinecraftVarIntCodec20.INSTANCE.read(buffer)

        fun writeVarInt(buffer: ByteBuf, value: Int) {
            MinecraftVarIntCodec20.INSTANCE.write(buffer, value)
        }
    }
}
