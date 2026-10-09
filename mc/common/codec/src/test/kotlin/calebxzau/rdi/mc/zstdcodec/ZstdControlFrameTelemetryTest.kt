package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZstdControlFrameTelemetryTest {
    @Test
    fun `stream and reference starts are reported once as record-free control frames`() {
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        try {
            ZstdCompressionPipeline.setup(channel, 64, true, ZstdTransportGuardTest.VAR_INT)
            val samples = ArrayList<ZstdBatchSample>()
            val outcomes = ArrayList<Boolean>()
            ZstdCompressionPipeline.setBatchObserver(channel, object : ZstdBatchObserver {
                override fun batchFlushed(sample: ZstdBatchSample) {
                    samples += sample
                }

                override fun writeCompleted(sample: ZstdBatchSample, success: Boolean) {
                    outcomes += success
                }
            })
            ZstdCompressionPipeline.setOutboundStream(channel, ZstdStreamFormat.MINIMUM_WINDOW_LOG)
            ZstdCompressionPipeline.setOutboundPacketRefs(channel)
            channel.flushOutbound()

            val written = ArrayList<Int>()
            while (true) {
                val frame = channel.readOutbound<ByteBuf>() ?: break
                written += frame.readableBytes()
                frame.release()
            }
            assertEquals(2, written.size)
            assertTrue(samples.all { it.frameKind == ZstdBatchFrameKind.Control && it.recordCount == 0 })
            assertEquals(written, samples.map { it.blockBytes })
            assertEquals(written.map(ZstdBatchFormat::varIntSize), samples.map { it.outerPrefixBytes })
            assertEquals(listOf(true, true), outcomes)
        } finally {
            channel.finishAndReleaseAll()
        }
    }
}
