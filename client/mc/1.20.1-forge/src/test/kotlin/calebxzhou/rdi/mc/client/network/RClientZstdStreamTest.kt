package calebxzhou.rdi.mc.client.network

import calebxzau.mc.common2021.RdiZstdStreamChannel
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.local.LocalChannel
import io.netty.handler.codec.DecoderException
import io.netty.util.AttributeKey
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.ConnectionData
import net.minecraftforge.network.NetworkEvent
import org.junit.jupiter.api.BeforeAll
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RClientZstdStreamTest {
    @Test
    fun `negotiated client accepts STREAM_START and decodes later segments`() {
        val server = compressionChannel()
        val client = compressionChannel()
        try {
            RClientZstdStream.onLoginPacket(connectionFor(client, remoteStream = true))
            val packet = randomPacket(300)

            ZstdCompressionPipeline.setOutboundStream(server)
            server.writeOutbound(Unpooled.wrappedBuffer(packet))
            server.writeOutbound(Unpooled.wrappedBuffer(packet))

            val frames = drain(server)
            assertEquals(3, frames.size)
            assertTrue(frames[2].readableBytes() < 32, "repeated segment=${frames[2].readableBytes()}")
            frames.forEach { client.writeInbound(it) }
            assertDecodedRecords(client, listOf(packet, packet))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `the later join fallback keeps a running stream`() {
        val server = compressionChannel()
        val client = compressionChannel()
        try {
            val connection = connectionFor(client, remoteStream = true)
            RClientZstdStream.onLoginPacket(connection)
            val packet = randomPacket(300)
            ZstdCompressionPipeline.setOutboundStream(server)
            server.writeOutbound(Unpooled.wrappedBuffer(packet))
            transfer(server, client)
            assertDecodedRecords(client, listOf(packet))

            RClientZstdStream.onJoin(connection)

            server.writeOutbound(Unpooled.wrappedBuffer(packet))
            transfer(server, client)
            assertDecodedRecords(client, listOf(packet))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `leaving rejects segments from the old session`() {
        val server = compressionChannel()
        val client = compressionChannel()
        try {
            val connection = connectionFor(client, remoteStream = true)
            RClientZstdStream.onLoginPacket(connection)
            val packet = randomPacket(300)
            ZstdCompressionPipeline.setOutboundStream(server)
            server.writeOutbound(Unpooled.wrappedBuffer(packet))
            server.writeOutbound(Unpooled.wrappedBuffer(packet))
            val frames = drain(server)
            client.writeInbound(frames[0])
            client.writeInbound(frames[1])
            assertDecodedRecords(client, listOf(packet))

            RClientZstdStream.onLeave(connection)

            assertFailsWith<DecoderException> { client.writeInbound(frames[2]) }
        } finally {
            runCatching { server.finishAndReleaseAll() }
            runCatching { client.finishAndReleaseAll() }
        }
    }

    @Test
    fun `server without the channel cannot start the stream`() {
        val server = compressionChannel()
        val client = compressionChannel()
        try {
            RClientZstdStream.onLoginPacket(connectionFor(client, remoteStream = false))
            ZstdCompressionPipeline.setOutboundStream(server)

            assertFailsWith<DecoderException> { client.writeInbound(drain(server).single()) }
        } finally {
            runCatching { server.finishAndReleaseAll() }
            runCatching { client.finishAndReleaseAll() }
        }
    }

    @Test
    fun `foreign decoder after negotiation reports actual handlers`() {
        val client = emptyPipelineChannel()
        try {
            val foreignDecoder = ChannelInboundHandlerAdapter()
            val foreignEncoder = ChannelOutboundHandlerAdapter()
            client.pipeline().addAfter("splitter", "decompress", foreignDecoder)
            client.pipeline().addAfter("prepender", "compress", foreignEncoder)

            val error = assertFailsWith<IllegalStateException> {
                RClientZstdStream.onLoginPacket(connectionFor(client, remoteStream = true))
            }

            assertTrue(error.message.orEmpty().contains("decompress=${foreignDecoder.javaClass.name}"))
            assertTrue(error.message.orEmpty().contains("compress=${foreignEncoder.javaClass.name}"))
            assertSame(foreignDecoder, client.pipeline().get("decompress"))
        } finally {
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `local channel and missing decoder are left alone`() {
        val localChannel = LocalChannel()
        try {
            val decompressor = ChannelInboundHandlerAdapter()
            localChannel.pipeline().addLast("decompress", decompressor)

            RClientZstdStream.onLoginPacket(connectionFor(localChannel, remoteStream = true))

            assertSame(decompressor, localChannel.pipeline().get("decompress"))
        } finally {
            localChannel.unsafe().closeForcibly()
        }

        val client = emptyPipelineChannel()
        try {
            RClientZstdStream.onLoginPacket(connectionFor(client, remoteStream = true))
            assertNull(client.pipeline().get("decompress"))
        } finally {
            client.finishAndReleaseAll()
        }
    }

    private fun randomPacket(size: Int): ByteArray = ByteArray(size).also(Random(size.toLong())::nextBytes)

    private fun transfer(server: EmbeddedChannel, client: EmbeddedChannel): Int {
        val frames = drain(server)
        frames.forEach { client.writeInbound(it) }
        return frames.size
    }

    private fun drain(server: EmbeddedChannel): List<ByteBuf> {
        val frames = ArrayList<ByteBuf>()
        while (true) frames += server.readOutbound<ByteBuf>() ?: break
        return frames
    }

    private fun compressionChannel(): EmbeddedChannel {
        val channel = emptyPipelineChannel()
        ZstdCompressionPipeline.setup(channel, COMPRESSION_THRESHOLD, false, MinecraftVarIntCodec201.INSTANCE)
        return channel
    }

    private fun emptyPipelineChannel(): EmbeddedChannel = EmbeddedChannel().apply {
        freezeTime()
        pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
    }

    private fun connectionFor(channel: Channel, remoteStream: Boolean): Connection {
        val connection = Connection(PacketFlow.CLIENTBOUND)
        CONNECTION_CHANNEL_FIELD.set(connection, channel)
        val remoteChannels = if (remoteStream) {
            mapOf(ResourceLocation.fromNamespaceAndPath("rdi", "zstream") to RdiZstdStreamChannel.PROTOCOL_VERSION)
        } else {
            emptyMap()
        }
        val connectionData = CONNECTION_DATA_CONSTRUCTOR.newInstance(emptyMap<String, Any>(), remoteChannels)
        channel.attr(CONNECTION_DATA_KEY).set(connectionData)
        return connection
    }

    private fun assertDecodedRecords(channel: EmbeddedChannel, expected: List<ByteArray>) {
        val actual = ArrayList<ByteArray>()
        while (true) {
            val decoded = channel.readInbound<ByteBuf>() ?: break
            try {
                val record = ByteArray(decoded.readableBytes())
                decoded.readBytes(record)
                actual += record
            } finally {
                decoded.release()
            }
        }
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedRecord, actualRecord) ->
            assertContentEquals(expectedRecord, actualRecord)
        }
    }

    companion object {
        private const val COMPRESSION_THRESHOLD = 64

        private val CONNECTION_CHANNEL_FIELD = Connection::class.java.getDeclaredField("channel").apply {
            isAccessible = true
        }
        private val CONNECTION_DATA_CONSTRUCTOR = ConnectionData::class.java
            .getDeclaredConstructor(Map::class.java, Map::class.java)
            .apply { isAccessible = true }
        private val CONNECTION_DATA_KEY: AttributeKey<ConnectionData> = AttributeKey.valueOf("fml:conndata")

        @JvmStatic
        @BeforeAll
        fun registerZstdStreamChannel() {
            // Plain JUnit lacks ModLauncher's injected no-arg event constructor; see RClientBatchingTest.
            NetworkEvent { error("The helper test must not dispatch Forge network events") }.listenerList
            NetworkEvent.GatherLoginPayloadsEvent(arrayListOf(), false).listenerList
            RdiZstdStreamChannel.register()
        }
    }
}
