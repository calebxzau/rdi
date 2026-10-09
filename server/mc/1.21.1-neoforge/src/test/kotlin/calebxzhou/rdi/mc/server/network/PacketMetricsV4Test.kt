package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.server.network.MinecraftVarIntCodec211
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.flow.FlowControlHandler
import net.minecraft.network.protocol.Packet
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketMetricsV4Test {
    @Test
    fun `v4 records logical bytes per packet and every emitted frame once`() {
        val database = Files.createTempDirectory("rdi-v4").resolve("packet-traffic_v4.db")
        PacketMetrics.startBatchMetrics(database)
        val channel = EmbeddedChannel()
        var written = 0L
        try {
            channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
            channel.pipeline().addLast("flow", FlowControlHandler())
            channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
            channel.pipeline().addLast("encoder", TaggingEncoder())
            ZstdCompressionPipeline.setup(channel, 64, true, MinecraftVarIntCodec211.INSTANCE)
            PacketRecordPipeline.install(channel.pipeline())
            PacketMetricsPipeline.install(channel.pipeline())
            ZstdCompressionPipeline.setOutboundStream(channel, 20)
            repeat(2) { channel.writeOneOutbound(TestPacket21.other(it)) }
            channel.flushOutbound()
            while (true) {
                val frame = channel.readOutbound<ByteBuf>() ?: break
                written += frame.readableBytes()
                frame.release()
            }
        } finally {
            channel.finishAndReleaseAll()
            PacketMetrics.stop()
        }

        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT packet_count, sum_encoded_bytes FROM packet_batch_logical_totals WHERE packet_type = 'minecraft:rdi_test_other'",
                ).use { rows ->
                    rows.next()
                    assertEquals(2, rows.getInt(1))
                    assertEquals(2L * BODY_BYTES, rows.getLong(2))
                }
                val frames = HashMap<String, Pair<Int, Long>>()
                statement.executeQuery(
                    "SELECT frame_kind, SUM(frame_count), SUM(sum_frame_bytes) FROM packet_batch_frame_totals GROUP BY frame_kind",
                ).use { rows ->
                    while (rows.next()) frames[rows.getString(1)] = rows.getInt(2) to rows.getLong(3)
                }
                assertEquals(1, frames["Control"]?.first)
                assertEquals(2, frames["Legacy"]?.first)
                // Every frame that left the encoder is counted exactly once.
                assertEquals(written, frames.values.sumOf { it.second })
            }
        }
    }

    /** Mirrors vanilla's encoder plus the RETURN hook feeding both pipelines. */
    private class TaggingEncoder : MessageToByteEncoder<Packet<*>>() {
        override fun encode(ctx: ChannelHandlerContext, packet: Packet<*>, out: ByteBuf) {
            out.writeInt((packet as TestPacket21).tag)
            out.writeZero(BODY_BYTES - Int.SIZE_BYTES)
            PacketRecordPipeline.encoded(ctx, packet, out)
            PacketMetricsPipeline.encoded(ctx, packet)
        }
    }

    private companion object {
        const val BODY_BYTES = 256
    }
}
