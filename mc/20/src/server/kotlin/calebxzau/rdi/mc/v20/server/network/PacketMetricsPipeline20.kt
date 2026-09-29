package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.local.LocalChannel
import io.netty.channel.local.LocalServerChannel
import io.netty.util.AttributeKey
import net.minecraft.network.protocol.Packet
import org.apache.logging.log4j.LogManager

/**
 * Per-channel, event-loop-confined attribution for Minecraft 1.20.1.
 *
 * 1.20 has no `FlowControlHandler` between the outer splitter and the decoder, so
 * inbound frame sizes are measured between the outer splitter and the decompression
 * boundary. A size stays valid only for the frame that is being decoded right now: the
 * decoder of that same frame consumes it, and the capture handler drops it once the
 * frame has been handled. No packet buffer is copied or retained for metrics.
 */
object PacketMetricsPipeline20 {
    private const val CAPTURE = "rdi_metrics_capture"
    private const val MEASURE = "rdi_metrics_measure"
    private const val OUTBOUND = "rdi_metrics_outbound"
    private const val SPLITTER = "splitter"
    private const val DECODER = "decoder"
    private const val PREPENDER = "prepender"
    private const val ENCODER = "encoder"
    private const val DECOMPRESS = "decompress"
    private const val COMPRESS = "compress"
    private val STATE = AttributeKey.valueOf<State>("rdi.packet-metrics.frames20")
    private val logger = LogManager.getLogger("rdi.packet-metrics")

    internal fun interface Recorder {
        fun record(packet: Packet<*>, direction: PacketDirection, bytes: Int)
    }

    private class State(val recorder: Recorder) {
        var pendingInboundBytes: Int = 0
        var outboundPacket: Packet<*>? = null
        var writing = false

        fun recordSafely(packet: Packet<*>, direction: PacketDirection, bytes: Int) {
            runCatching { recorder.record(packet, direction, bytes) }
                .onFailure { logger.error("Failed to collect a compressed packet metric", it) }
        }
    }

    @JvmStatic
    fun install(pipeline: ChannelPipeline) = install(pipeline, PacketMetrics20::record)

    internal fun install(pipeline: ChannelPipeline, record: Recorder) {
        if (pipeline.get(CAPTURE) != null) return
        val channel = pipeline.channel()
        if (channel is LocalChannel || channel is LocalServerChannel) return
        if (pipeline.get(SPLITTER) == null || pipeline.get(DECODER) == null ||
            pipeline.get(PREPENDER) == null || pipeline.get(ENCODER) == null
        ) {
            logger.warn(
                "RDI packet metrics disabled for a connection with an unexpected pipeline layout: {}",
                pipeline.names(),
            )
            return
        }
        val state = State(record)
        channel.attr(STATE).set(state)
        pipeline.addAfter(SPLITTER, CAPTURE, Capture(state))
        pipeline.addAfter(PREPENDER, MEASURE, Measure(state))
        pipeline.addAfter(ENCODER, OUTBOUND, Outbound(state))
        compressionChanged(pipeline)
    }

    /** setupCompression inserts directly after splitter/prepender, so restore the measuring boundaries. */
    @JvmStatic
    fun compressionChanged(pipeline: ChannelPipeline) {
        if (pipeline.get(CAPTURE) == null) return
        val state = pipeline.channel().attr(STATE).get() ?: return
        if (pipeline.get(DECOMPRESS) != null) {
            pipeline.remove(CAPTURE)
            pipeline.addBefore(DECOMPRESS, CAPTURE, Capture(state))
        }
        if (pipeline.get(COMPRESS) != null) {
            pipeline.remove(MEASURE)
            pipeline.addBefore(COMPRESS, MEASURE, Measure(state))
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
        val bytes = state.pendingInboundBytes
        state.pendingInboundBytes = 0
        if (bytes <= 0) return
        state.recordSafely(packet, PacketDirection.C2S, bytes)
    }

    /**
     * Clears the size of a frame that was handed to the decoder but produced no packet.
     * The per-frame scope in [Capture] already drops that size, so this stays a safety net
     * for the inbound decoder hook.
     */
    @JvmStatic
    fun discarded(context: ChannelHandlerContext) {
        context.channel().attr(STATE).get()?.pendingInboundBytes = 0
    }

    /**
     * Measures the compressed frame content: after decompression's input boundary and before the outer length prepender.
     *
     * The count belongs to the frame that is being decoded; Netty turns a throwable raised by a
     * downstream handler into an `exceptionCaught` event instead of rethrowing it through this
     * call, and a frame that carries no packet never reaches the packet decoder at all. Dropping
     * the count once the frame has been handled keeps either case from adding its size to the next
     * frame's sample. The enclosing value is restored so nested reads stay intact.
     */
    private class Capture(val state: State) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val enclosing = state.pendingInboundBytes
            state.pendingInboundBytes = if (msg is ByteBuf) msg.readableBytes() else 0
            try {
                ctx.fireChannelRead(msg)
            } finally {
                state.pendingInboundBytes = enclosing
            }
        }
    }

    private class Measure(val state: State) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val packet = state.outboundPacket
            state.outboundPacket = null
            if (packet != null && msg is ByteBuf) {
                state.recordSafely(packet, PacketDirection.S2C, msg.readableBytes())
            }
            ctx.write(msg, promise)
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
}
