package calebxzau.rdi.mc.zstdcodec.v1710

import calebxzau.rdi.mc.zstdcodec.ZstdBatchFormat
import calebxzau.rdi.mc.zstdcodec.ZstdBatchObserver
import calebxzau.rdi.mc.zstdcodec.ZstdBatchSample
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.apache.logging.log4j.LogManager

/** Optional encoded-size probe; the 1.7.10 Play protocol assigns S21/S26 IDs 0x21/0x26. */
internal object ZstdChunkMetrics1710 {
    fun install(channel: Channel) {
        if (!java.lang.Boolean.getBoolean("rdi.zstd1710.chunkMetrics")) return
        val probe = Probe()
        channel.pipeline().addAfter("compress", "rdi_zstd_chunk_metrics", probe)
        ZstdCompressionPipeline.setBatchObserver(channel, probe)
    }

    /** Batching is disabled; each synchronous write produces its own codec sample. */
    private class Probe : ChannelOutboundHandlerAdapter(), ZstdBatchObserver {
        private var packetId = -1
        private val logger = LogManager.getLogger("rdi")

        override fun write(context: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val previous = packetId
            packetId = if (msg is ByteBuf && msg.isReadable) msg.getUnsignedByte(msg.readerIndex()).toInt() else -1
            try {
                context.write(msg, promise)
            } finally {
                packetId = previous
            }
        }

        override fun batchFlushed(sample: ZstdBatchSample) {
            if (packetId != 0x21 && packetId != 0x26) return
            val payload = sample.blockBytes - if (sample.raw) 1 else ZstdBatchFormat.varIntSize(sample.payloadBytes)
            logger.info(
                "Zstd chunk {} rawBytes={} payloadBytes={} outerFrameBytes={} raw={}",
                if (packetId == 0x21) "S21" else "S26", sample.payloadBytes, payload,
                sample.blockBytes + sample.outerPrefixBytes, sample.raw,
            )
        }
    }
}
