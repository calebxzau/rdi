package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.zstdcodec.ZstdBatchObserver
import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import calebxzau.rdi.mc.zstdcodec.ZstdBatchSample
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import io.netty.buffer.ByteBuf
import io.netty.buffer.DefaultByteBufHolder
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.handler.flow.FlowControlHandler
import io.netty.util.AttributeKey
import net.minecraft.network.protocol.Packet
import org.apache.logging.log4j.LogManager

/** Per-channel, event-loop-confined attribution. No packet buffers are copied or retained for metrics. */
object PacketMetricsPipeline {
    private const val NO_FRAME = -1
    private const val CAPTURE = "rdi_metrics_capture"
    private const val WRAP = "rdi_metrics_wrap"
    private const val UNWRAP = "rdi_metrics_unwrap"
    private const val MEASURE = "rdi_metrics_measure"
    private const val OUTBOUND = "rdi_metrics_outbound"
    private val STATE = AttributeKey.valueOf<State>("rdi.packet-metrics.frames")
    private val logger = LogManager.getLogger("rdi.packet-metrics")

    internal fun interface Recorder {
        fun record(packet: Packet<*>, direction: PacketDirection, bytes: Int)
    }

    private class State(val recorder: Recorder) {
        var capturedBytes: Int = NO_FRAME
        var inboundBytes: Int = NO_FRAME
        var outboundPacket: Packet<*>? = null
        var writing = false

        fun recordSafely(packet: Packet<*>, direction: PacketDirection, bytes: Int) {
            runCatching { recorder.record(packet, direction, bytes) }
                .onFailure { logger.error("Failed to collect a compressed packet metric", it) }
        }
    }

    // FlowControlHandler can buffer across protocol transitions. Carry the size WITH that frame.
    private class Frame(content: ByteBuf, val bytes: Int) : DefaultByteBufHolder(content)

    @JvmStatic
    fun install(pipeline: ChannelPipeline) = install(pipeline, PacketMetrics::record)

    internal fun install(pipeline: ChannelPipeline, record: Recorder) {
        if (pipeline.get(CAPTURE) != null) return
        val flow = requireNotNull(pipeline.context(FlowControlHandler::class.java))
        val state = State(record)
        pipeline.channel().attr(STATE).set(state)
        pipeline.addAfter("splitter", CAPTURE, Capture(state))
        pipeline.addBefore(flow.name(), WRAP, Wrap(state))
        pipeline.addAfter(flow.name(), UNWRAP, Unwrap(state))
        pipeline.addAfter("prepender", MEASURE, Measure(state))
        val encoder = if (pipeline.get("encoder") != null) "encoder" else "outbound_config"
        pipeline.addAfter(encoder, OUTBOUND, Outbound(state))
        compressionChanged(pipeline)
    }

    /** setupCompression inserts directly after splitter/prepender, so restore the measuring boundaries. */
    @JvmStatic
    fun compressionChanged(pipeline: ChannelPipeline) {
        if (pipeline.get(CAPTURE) == null) return
        val state = pipeline.channel().attr(STATE).get() ?: return
        if (PacketMetrics.batchMetricsEnabled) ZstdCompressionPipeline.setBatchObserver(pipeline.channel(), MetricsObserver)
        if (pipeline.get("decompress") != null) {
            pipeline.remove(CAPTURE)
            pipeline.addBefore("decompress", CAPTURE, Capture(state))
        }
        if (pipeline.get("compress") != null) {
            pipeline.remove(MEASURE)
            pipeline.addBefore("compress", MEASURE, Measure(state))
        }
    }

    @JvmStatic
    fun encoded(context: ChannelHandlerContext, packet: Packet<*>) {
        val state = context.channel().attr(STATE).get() ?: return
        if (state.writing) state.outboundPacket = packet
    }

    @JvmStatic
    fun decoded(context: ChannelHandlerContext, packet: Packet<*>) {
        val state = context.channel().attr(STATE).get() ?: return
        val bytes = state.inboundBytes
        if (bytes == NO_FRAME) return
        state.inboundBytes = NO_FRAME
        state.recordSafely(packet, PacketDirection.C2S, bytes)
    }

    private object MetricsObserver : ZstdBatchObserver {
        override fun recordEncoded(identity: ZstdPacketIdentity?, encodedBytes: Int, policy: ZstdBatchPolicy) {
            PacketMetrics.recordLogical(identity, encodedBytes)
        }

        override fun batchFlushed(sample: ZstdBatchSample) = PacketMetrics.recordFrame(sample)

        override fun writeCompleted(sample: ZstdBatchSample, success: Boolean) {
            PacketMetrics.recordWriteOutcome(success, sample.recordCount)
        }

        override fun encodingFailed(recordCount: Int) = PacketMetrics.recordEncodingFailure(recordCount)
    }

    private class Capture(val state: State) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val previous = state.capturedBytes
            state.capturedBytes = if (msg is ByteBuf) msg.readableBytes() else NO_FRAME
            try {
                ctx.fireChannelRead(msg)
            } finally {
                state.capturedBytes = previous
            }
        }
    }

    private class Wrap(val state: State) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val bytes = state.capturedBytes
            ctx.fireChannelRead(if (msg is ByteBuf && bytes != NO_FRAME) Frame(msg, bytes) else msg)
        }
    }

    private class Unwrap(val state: State) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val previous = state.inboundBytes
            state.inboundBytes = if (msg is Frame) msg.bytes else NO_FRAME
            try {
                // Ownership passes to the decoder; do not release the holder separately.
                ctx.fireChannelRead(if (msg is Frame) msg.content() else msg)
            } finally {
                state.inboundBytes = previous
            }
        }
    }

    private class Outbound(val state: State) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val previous = state.outboundPacket
            val wasWriting = state.writing
            state.outboundPacket = null
            state.writing = true
            try {
                ctx.write(msg, promise)
            } finally {
                state.outboundPacket = previous
                state.writing = wasWriting
            }
        }
    }

    private class Measure(val state: State) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val packet = state.outboundPacket
            state.outboundPacket = null
            // v4 counts S2C by frame through the encoder observer. A batching encoder also writes delayed
            // records and whole blocks here, so the frame no longer belongs to the packet being written now.
            if (packet != null && msg is ByteBuf && !PacketMetrics.batchMetricsEnabled &&
                !ZstdCompressionPipeline.isOutboundBatchingEnabled(ctx.channel())
            ) {
                state.recordSafely(packet, PacketDirection.S2C, msg.readableBytes())
            }
            ctx.write(msg, promise)
        }
    }
}
