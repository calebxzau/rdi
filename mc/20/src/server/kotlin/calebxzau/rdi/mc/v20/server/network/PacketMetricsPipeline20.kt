package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdBatchObserver
import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import calebxzau.rdi.mc.zstdcodec.ZstdBatchSample
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import calebxzau.rdi.mc.zstdcodec.ZstdSendingRecord
import calebxzau.rdi.mc.zstdcodec.PacketCaptureConnection
import io.netty.channel.Channel
import java.util.ArrayDeque
import java.util.IdentityHashMap
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
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
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
    private const val RECORD = "rdi_encoded_record"
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
        val encodingScopes = ArrayDeque<IdentityHashMap<ByteBuf, Packet<*>>>()
        var selective = false
        var longWindow = false
        var verifiedChannels: Set<String> = emptySet()
        var capture: PacketCaptureConnection? = null

        fun isBatchingPaused(context: ChannelHandlerContext): Boolean =
            ZstdCompressionPipeline.isOutboundBatchingEnabled(context.channel())

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
        pipeline.addBefore(ENCODER, RECORD, EncodedRecord(state))
        pipeline.addAfter(ENCODER, OUTBOUND, Outbound(state))
        compressionChanged(pipeline)
    }

    /** setupCompression inserts directly after splitter/prepender, so restore the measuring boundaries. */
    @JvmStatic
    fun compressionChanged(pipeline: ChannelPipeline) {
        if (pipeline.get(CAPTURE) == null) return
        val state = pipeline.channel().attr(STATE).get() ?: return
        if (PacketMetrics20.batchMetricsEnabled) {
            ZstdCompressionPipeline.setBatchObserver(pipeline.channel(), MetricsObserver)
        }
        if (pipeline.get(DECOMPRESS) != null) {
            pipeline.remove(CAPTURE)
            pipeline.addBefore(DECOMPRESS, CAPTURE, Capture(state))
        }
        if (pipeline.get(COMPRESS) != null) {
            pipeline.remove(MEASURE)
            pipeline.addBefore(COMPRESS, MEASURE, Measure(state))
        }
    }

    /** Metadata is keyed by the exact successfully encoded buffer, then transferred with its bytes. */
    @JvmStatic
    fun encoded(context: ChannelHandlerContext, packet: Packet<*>, output: ByteBuf) {
        val state = context.channel().attr(STATE).get() ?: return
        state.encodingScopes.peekLast()?.put(output, packet)
    }

    fun configureBatching(
        channel: Channel,
        longWindow: Boolean,
        verifiedChannels: Set<String>,
    ) {
        val task = Runnable {
            val state = channel.attr(STATE).get() ?: return@Runnable
            state.selective = true
            state.longWindow = longWindow
            if (state.verifiedChannels != verifiedChannels) state.verifiedChannels = verifiedChannels.toSet()
        }
        if (channel.eventLoop().inEventLoop()) task.run() else channel.eventLoop().execute(task)
    }

    /** Capture does not depend on negotiated batching or a compression handler. */
    internal fun attachCapture(channel: Channel, capture: PacketCaptureConnection): Boolean {
        check(channel.eventLoop().inEventLoop()) { "Capture ownership belongs to the connection event loop" }
        val state = channel.attr(STATE).get() ?: return false
        state.capture = capture
        return true
    }

    private object MetricsObserver : ZstdBatchObserver {
        override fun recordEncoded(identity: ZstdPacketIdentity?, encodedBytes: Int, policy: ZstdBatchPolicy) {
            PacketMetrics20.recordLogical(identity, encodedBytes)
        }

        override fun batchFlushed(sample: ZstdBatchSample) = PacketMetrics20.recordFrame(sample)

        override fun writeCompleted(sample: ZstdBatchSample, success: Boolean) {
            PacketMetrics20.recordWriteOutcome(success, sample.recordCount)
        }

        override fun encodingFailed(recordCount: Int) = PacketMetrics20.recordEncodingFailure(recordCount)
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
        override fun channelInactive(ctx: ChannelHandlerContext) {
            state.capture = null
            ctx.fireChannelInactive()
        }

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
            if (packet != null && msg is ByteBuf && !PacketMetrics20.batchMetricsEnabled && !state.isBatchingPaused(ctx)) {
                state.recordSafely(packet, PacketDirection.S2C, msg.readableBytes())
            }
            ctx.write(msg, promise)
        }
    }

    private class EncodedRecord(val state: State) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg !is ByteBuf) {
                ctx.write(msg, promise)
                return
            }
            val packet = state.encodingScopes.peekLast()?.remove(msg)
            val key = packet?.let { PacketMetrics20.metricKey(it, PacketDirection.S2C) }
            val identity = key?.let { ZstdPacketIdentity(it.packetType, it.namespace, it.path) }
            val policy = if (state.selective && packet != null) {
                PacketBatchPolicy20.classify(packet, msg, state.verifiedChannels, state.longWindow)
            } else {
                ZstdBatchPolicy.Immediate
            }
            if (packet is ClientboundCustomPayloadPacket || packet is ClientboundUpdateAttributesPacket) {
                state.capture?.let { capture ->
                    runCatching { capture.record(msg, identity) }.onFailure {
                        state.capture = null
                        logger.error("Failed to capture an encoded packet; capture detached from this connection", it)
                    }
                }
            }
            val previous = state.outboundPacket
            state.outboundPacket = packet
            try {
                if (ZstdCompressionPipeline.hasOutboundEncoder(ctx.channel())) {
                    ctx.write(ZstdSendingRecord(msg, policy, identity), promise)
                } else {
                    if (PacketMetrics20.batchMetricsEnabled) {
                        PacketMetrics20.recordUncompressed(identity, msg.readableBytes(), promise)
                    }
                    ctx.write(msg, promise)
                }
            } finally {
                state.outboundPacket = previous
            }
        }
    }

    private class Outbound(val state: State) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val scope = IdentityHashMap<ByteBuf, Packet<*>>()
            state.encodingScopes.addLast(scope)
            try {
                ctx.write(msg, promise)
            } finally {
                state.encodingScopes.removeLast()
            }
        }
    }
}
