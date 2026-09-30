package calebxzhou.rdi.mc.client.network

import calebxzau.mc.common2021.RdiBatchChannel
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.local.LocalChannel
import io.netty.util.AttributeKey
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.ConnectionData
import net.minecraftforge.network.NetworkEvent
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class RClientBatchingTest {
    @Test
    fun `enables batch decoding on Netty before queued login work and decodes first block`() {
        val server = compressionChannel()
        val client = compressionChannel()
        try {
            val clientConnection = connectionFor(client, remoteBatchChannel = true)
            setOutboundBatching(server, true)

            var loginHandled = false
            val pendingMainThreadLogin = ArrayDeque<() -> Unit>()
            pendingMainThreadLogin.addLast { loginHandled = true }

            client.eventLoop().execute {
                RClientBatching.onLoginPacket(clientConnection)
            }
            client.runPendingTasks()

            assertEquals(1, pendingMainThreadLogin.size)
            assertFalse(loginHandled)

            val records = listOf(
                ByteArray(300) { (it * 17).toByte() },
                ByteArray(400) { (it * 29 + 3).toByte() },
            )
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            ZstdCompressionPipeline.flushBatched(server)
            server.runPendingTasks()
            val batchBlock = assertNotNull(server.readOutbound<ByteBuf>())

            client.writeInbound(batchBlock)
            assertDecodedRecords(client, records)
            assertFalse(loginHandled)
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `old peer without the batch channel continues decoding legacy frames`() {
        val server = compressionChannel()
        val client = compressionChannel()
        try {
            val clientConnection = connectionFor(client, remoteBatchChannel = false)
            client.eventLoop().execute {
                RClientBatching.onLoginPacket(clientConnection)
            }
            client.runPendingTasks()

            val record = ByteArray(256) { (it * 11).toByte() }
            server.writeOutbound(Unpooled.wrappedBuffer(record))
            val legacyFrame = assertNotNull(server.readOutbound<ByteBuf>())

            client.writeInbound(legacyFrame)
            assertDecodedRecords(client, listOf(record))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `negotiated local channel does not enable its decompressor`() {
        val localChannel = LocalChannel()
        try {
            val decompressor = ChannelInboundHandlerAdapter()
            localChannel.pipeline().addLast("decompress", decompressor)
            val connection = connectionFor(localChannel, remoteBatchChannel = true)

            RClientBatching.onLoginPacket(connection)

            assertSame(decompressor, localChannel.pipeline().get("decompress"))
        } finally {
            localChannel.unsafe().closeForcibly()
        }
    }

    @Test
    fun `negotiated remote channel without compression decoder is accepted`() {
        val client = emptyPipelineChannel()
        try {
            val connection = connectionFor(client, remoteBatchChannel = true)

            RClientBatching.onLoginPacket(connection)

            assertNull(client.pipeline().get("decompress"))
        } finally {
            client.finishAndReleaseAll()
        }
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

    private fun setOutboundBatching(channel: Channel, enabled: Boolean) {
        channel.eventLoop().execute {
            ZstdCompressionPipeline.setOutboundBatching(channel, enabled)
        }
        if (channel is EmbeddedChannel) {
            channel.runPendingTasks()
        }
    }

    private fun connectionFor(channel: Channel, remoteBatchChannel: Boolean): Connection {
        val connection = Connection(PacketFlow.CLIENTBOUND)
        CONNECTION_CHANNEL_FIELD.set(connection, channel)
        val remoteChannels = if (remoteBatchChannel) {
            mapOf(ResourceLocation.fromNamespaceAndPath("rdi", "batch") to RdiBatchChannel.PROTOCOL_VERSION)
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
        fun registerBatchChannel() {
            // Plain JUnit lacks ModLauncher's injected no-arg event constructor. Populate
            // this event's listener list through its public instance API before registration.
            NetworkEvent { error("The helper test must not dispatch Forge network events") }.listenerList
            NetworkEvent.GatherLoginPayloadsEvent(arrayListOf(), false).listenerList
            RdiBatchChannel.register()
        }
    }
}
