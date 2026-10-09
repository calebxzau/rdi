package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.PacketCaptureRecorder
import calebxzau.rdi.mc.zstdcodec.PacketCapturePhase
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.server.network.MinecraftVarIntCodec211
import com.github.luben.zstd.ZstdInputStream
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.MessageToMessageEncoder
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.MessageToByteEncoder
import net.minecraft.network.protocol.Packet
import net.minecraft.network.PacketEncoder
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.CommonPacketTypes
import net.minecraft.network.protocol.common.ClientCommonPacketListener
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class PacketCapturePipelineTest {
    @Test
    fun `configuration attaches before immediate sends and remains attached through reconfiguration`() {
        Harness().use { h ->
            var factories = 0
            RServerPacketCapture.onEventLoop(h.channel) {
                assertTrue(PacketRecordPipeline.attachCapture(h.channel) { factories++; h.recorder.connection(PLAYER) })
            }
            h.send(custom())
            h.phase = PacketCapturePhase.Play
            h.send(TestPacket21.other(2))
            h.send(TestPacket21.attributes(3))
            h.phase = PacketCapturePhase.Configuration
            assertTrue(PacketRecordPipeline.attachCapture(h.channel) { factories++; h.recorder.connection(PLAYER) })
            h.send(custom())
            val rows = h.read()
            assertEquals(1, factories)
            assertEquals(listOf(1, 0, 1), rows.map { it.phase })
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.sequence })
            assertEquals(listOf(1L, 1L, 1L), rows.map { it.connection })
            assertEquals(listOf("minecraft:brand", "", "minecraft:brand"), rows.map { it.channel })
            assertEquals(listOf("minecraft:custom_payload", "minecraft:update_attributes", "minecraft:custom_payload"), rows.map { it.type })
            assertContentEquals(encoded(custom()), rows.first().bytes)
            assertContentEquals(encoded(TestPacket21.attributes(3)), rows[1].bytes)
        }
    }

    @Test
    fun `unknown phase and failed encoding cannot mislabel the next packet`() {
        Harness().use { h ->
            h.attach()
            h.phase = null
            h.send(custom())
            h.phase = PacketCapturePhase.Play
            h.encoder.fail = true
            assertFalse(h.send(custom()))
            h.encoder.fail = false
            val bare = Unpooled.wrappedBuffer(byteArrayOf(22))
            h.channel.writeOutbound(bare)
            h.drain()
            h.send(custom())
            val rows = h.read()
            assertEquals(1, rows.size)
            assertEquals(1L, rows.single().sequence)
            assertEquals(0, rows.single().phase)
        }
    }

    @Test
    fun `recording leaves outgoing bytes identical with compression off batching or streaming`() {
        for (mode in 0..2) {
            val expected = transmit(mode, capture = false)
            val actual = transmit(mode, capture = true)
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (a, b) -> assertContentEquals(a, b) }
        }
    }

    @Test
    fun `closed recorder and phase provider failure leave packet delivery intact`() {
        Harness().use { h ->
            h.attach()
            h.recorder.close().getOrThrow()
            assertTrue(h.send(custom()))
            assertEquals(1L, h.recorder.droppedSamples)
        }
        Harness().use { h ->
            h.attach()
            h.phaseFailure = true
            assertTrue(h.send(custom()))
            h.phaseFailure = false
            h.send(custom())
            assertTrue(h.read().isEmpty()) // A failure detaches for the lifetime of this connection.
        }
    }

    @Test
    fun `capture settings default on and explicit false disables`() {
        assertTrue(RServerPacketCapture.enabled(null))
        assertTrue(RServerPacketCapture.enabled(" true "))
        assertFalse(RServerPacketCapture.enabled(" FALSE "))
        assertTrue(RServerPacketCapture.enabled("typo"))
    }

    @Test
    fun `bundle children are captured in order and disconnected channels clear capture`() {
        Harness().use { h ->
            h.attach()
            h.channel.pipeline().addAfter("encoder", "unbundler", object : MessageToMessageEncoder<List<*>>() {
                override fun encode(ctx: ChannelHandlerContext, msg: List<*>, out: MutableList<Any>) {
                    msg.forEach { out.add(requireNotNull(it)) }
                }
            })
            h.send(listOf(custom(), TestPacket21.other(2), TestPacket21.attributes(3)))
            val rows = h.read()
            assertEquals(listOf(1L, 2L), rows.map { it.sequence })
            h.channel.close()
            var newAttachment = false
            PacketRecordPipeline.attachCapture(h.channel) { newAttachment = true; h.recorder.connection(PLAYER) }
            assertTrue(newAttachment)
        }
    }

    @Test
    fun `phase comes from the current real encoder and missing encoders stay unknown`() {
        val channel = EmbeddedChannel()
        try {
            channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
            assertNull(PacketRecordPipeline.phaseOf(channel.pipeline().context("encoder")))
            for ((protocol, phase) in listOf(
                ConnectionProtocol.CONFIGURATION to PacketCapturePhase.Configuration,
                ConnectionProtocol.PLAY to PacketCapturePhase.Play,
                ConnectionProtocol.LOGIN to null,
            )) {
                val info = object : ProtocolInfo<ClientCommonPacketListener> {
                    override fun id() = protocol
                    override fun flow() = PacketFlow.CLIENTBOUND
                    override fun bundlerInfo() = null
                    override fun codec(): StreamCodec<ByteBuf, Packet<in ClientCommonPacketListener>> = error("unused codec")
                }
                channel.pipeline().replace("encoder", "encoder", PacketEncoder(info))
                assertEquals(phase, PacketRecordPipeline.phaseOf(channel.pipeline().context("encoder")))
            }
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `disabled adapter creates no files and enabled adapter closes an empty v2 run`() {
        val directory = Files.createTempDirectory("rdi-capture21-start-").resolve("capture")
        RServerPacketCapture.start(directory, "false") { error("disabled capture must not read metadata") }
        assertFalse(Files.exists(directory))
        try {
            RServerPacketCapture.start(directory, null) { mapOf("loader" to "test") }
        } finally {
            RServerPacketCapture.stop()
        }
        val files = Files.list(directory).use { it.toList() }
        assertTrue(files.any { it.toString().endsWith(".json") })
        assertTrue(readPart(files.single { it.toString().endsWith(".rdibatch.zst") }).isEmpty())
        assertTrue(files.none { it.toString().endsWith(".partial") })
    }

    private fun transmit(mode: Int, capture: Boolean): List<ByteArray> = Harness(batching = mode == 1, streaming = mode == 2).use { h ->
        if (capture) h.attach()
        h.phase = PacketCapturePhase.Play
        h.send(TestPacket21.attributes(1))
        h.send(custom())
        if (mode != 0) ZstdCompressionPipeline.flushAtTickEnd(h.channel)
        h.channel.flushOutbound()
        h.drain()
        val rows = h.read()
        assertEquals(if (capture) 2 else 0, rows.size)
        h.sent.toList()
    }

    private class Harness(batching: Boolean = false, streaming: Boolean = false) : AutoCloseable {
        val directory = Files.createTempDirectory("rdi-capture21-")
        val recorder = PacketCaptureRecorder(directory)
        var phase: PacketCapturePhase? = PacketCapturePhase.Configuration
        var phaseFailure = false
        val encoder = Encoder()
        val channel = EmbeddedChannel()
        val sent = ArrayList<ByteArray>()
        init {
            channel.freezeTime()
            channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
            channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
            channel.pipeline().addLast("encoder", encoder)
            PacketRecordPipeline.install(channel.pipeline(), phase = {
                check(!phaseFailure) { "test phase failure" }
                phase
            }, identity = { packet ->
                if (packet.type() == CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD)
                    ZstdPacketIdentity(packet.type().id().toString(), "minecraft", "brand")
                else packetIdentity(packet)
            })
            if (batching || streaming) {
                ZstdCompressionPipeline.setup(channel, 256, true, MinecraftVarIntCodec211.INSTANCE)
                if (batching) {
                    PacketRecordPipeline.enableSelective(channel)
                    ZstdCompressionPipeline.setOutboundBatching(channel, true, delayUnassociated = false)
                }
                if (streaming) ZstdCompressionPipeline.setOutboundStream(channel, 25)
            }
        }
        fun attach() { assertTrue(PacketRecordPipeline.attachCapture(channel) { recorder.connection(PLAYER) }) }
        fun send(packet: Any): Boolean {
            val future = channel.writeOneOutbound(packet)
            channel.flushOutbound()
            drain()
            return !future.isDone || future.isSuccess
        }
        fun drain() {
            while (true) {
                val buffer = channel.readOutbound<ByteBuf>() ?: return
                try { sent += ByteArray(buffer.readableBytes()).also { buffer.getBytes(buffer.readerIndex(), it) } }
                finally { buffer.release() }
            }
        }
        fun read(): List<Row> {
            recorder.close().getOrThrow()
            return Files.list(directory).use { files ->
                files.filter { it.toString().endsWith(".rdibatch.zst") }.sorted().toList().flatMap(::readPart)
            }
        }
        override fun close() {
            recorder.close().getOrThrow()
            channel.finishAndReleaseAll()
        }
    }

    private class Encoder : MessageToByteEncoder<Packet<*>>() {
        var fail = false
        override fun encode(ctx: ChannelHandlerContext, packet: Packet<*>, out: ByteBuf) {
            out.writeBytes(encoded(packet))
            PacketRecordPipeline.encoded(ctx, packet, out)
            check(!fail) { "test encoding failure" }
        }
    }

    private data class Row(val connection: Long, val sequence: Long, val phase: Int, val type: String, val channel: String, val bytes: ByteArray)

    companion object {
        private val PLAYER = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        private fun custom() = object : Packet<ClientCommonPacketListener> {
            override fun type() = CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD
            override fun handle(listener: ClientCommonPacketListener) = Unit
        }
        private fun encoded(packet: Packet<*>): ByteArray = packet.type().id().toString().toByteArray()
        private fun readPart(path: Path): List<Row> = DataInputStream(ZstdInputStream(Files.newInputStream(path))).use { input ->
            assertEquals(1, input.readUnsignedByte())
            assertEquals("RDPC", String(input.readNBytes(4)))
            assertEquals(2, input.readUnsignedShort())
            input.skipNBytes(28)
            val rows = ArrayList<Row>()
            while (true) {
                when (input.readUnsignedByte()) {
                    2 -> repeat(input.readInt()) {
                        input.skipNBytes(24)
                        val connection = input.readLong()
                        val sequence = input.readLong()
                        val phase = input.readUnsignedByte()
                        val type = String(input.readNBytes(input.readUnsignedShort()))
                        val channel = String(input.readNBytes(input.readUnsignedShort()))
                        rows += Row(connection, sequence, phase, type, channel, input.readNBytes(input.readInt()))
                    }
                    3 -> { input.skipNBytes(25); break }
                    else -> error("unexpected frame")
                }
            }
            rows
        }
    }
}
