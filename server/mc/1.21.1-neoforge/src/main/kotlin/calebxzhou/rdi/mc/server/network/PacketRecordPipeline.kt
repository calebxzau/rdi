package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdSendingRecord
import calebxzau.rdi.mc.zstdcodec.PacketCaptureConnection
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import calebxzau.rdi.mc.zstdcodec.PacketCapturePhase
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.channel.local.LocalChannel
import io.netty.util.AttributeKey
import net.minecraft.network.protocol.Packet
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.PacketEncoder
import net.minecraft.network.protocol.common.CommonPacketTypes
import net.minecraft.network.protocol.game.GamePacketTypes
import org.apache.logging.log4j.LogManager
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Ties each successfully encoded buffer to its packet so the RDI encoder can batch by packet type.
 *
 * Independent of packet metrics: classification works whether or not metrics are collected. A scope
 * opens for every outbound write before vanilla's bundle unpacker and encoder, the encoder hook records
 * `output buffer -> packet` by identity, and the handler after the encoder takes that entry back. A
 * failed encode never reaches the handler, and its scope is dropped, so it cannot tag a later buffer.
 * All state is confined to the connection's event loop.
 */
object PacketRecordPipeline {
    private const val SCOPE = "rdi_record_scope"
    private const val RECORD = "rdi_encoded_record"
    private val STATE = AttributeKey.valueOf<State>("rdi.packet-record")
    private val logger = LogManager.getLogger("rdi.network-extensions")

    private class State(
        val phase: (ChannelHandlerContext) -> PacketCapturePhase?,
        val identity: (Packet<*>) -> ZstdPacketIdentity,
    ) {
        val scopes = ArrayDeque<IdentityHashMap<ByteBuf, Packet<*>>>()
        var selective = false
        var capture: PacketCaptureConnection? = null
        var captureFailed = false
        var unknownPhaseCount = 0L
    }

    @JvmStatic
    fun install(pipeline: ChannelPipeline) = install(pipeline, ::phaseOf)

    internal fun install(
        pipeline: ChannelPipeline,
        phase: (ChannelHandlerContext) -> PacketCapturePhase?,
        identity: (Packet<*>) -> ZstdPacketIdentity = ::packetIdentity,
    ) {
        if (pipeline.get(SCOPE) != null || pipeline.channel() is LocalChannel) return
        // Protocol changes replace this handler in place, so both neighbours keep their positions.
        val encoder = when {
            pipeline.get("encoder") != null -> "encoder"
            pipeline.get("outbound_config") != null -> "outbound_config"
            else -> {
                logger.warn("Packet batching unavailable for a connection with layout {}", pipeline.names())
                return
            }
        }
        val state = State(phase, identity)
        pipeline.channel().attr(STATE).set(state)
        pipeline.addAfter(encoder, SCOPE, Scope(state))
        pipeline.addBefore(encoder, RECORD, Record(state))
    }

    @JvmStatic
    fun encoded(context: ChannelHandlerContext, packet: Packet<*>, output: ByteBuf) {
        context.channel().attr(STATE).get()?.scopes?.peekLast()?.put(output, packet)
    }

    internal fun attachCapture(channel: Channel, factory: () -> PacketCaptureConnection): Boolean {
        check(channel.eventLoop().inEventLoop())
        val state = channel.attr(STATE).get() ?: return false
        if (!state.captureFailed && state.capture == null) state.capture = factory()
        return true
    }

    internal fun phaseOf(context: ChannelHandlerContext): PacketCapturePhase? =
        when ((context.pipeline().get("encoder") as? PacketEncoder<*>)?.protocolInfo?.id()) {
            ConnectionProtocol.CONFIGURATION -> PacketCapturePhase.Configuration
            ConnectionProtocol.PLAY -> PacketCapturePhase.Play
            else -> null
        }

    /** Starts per-packet classification; call on the event loop before enabling encoder batching. */
    fun enableSelective(channel: Channel): Boolean {
        check(channel.eventLoop().inEventLoop()) { "Batching state belongs to the connection event loop" }
        val state = channel.attr(STATE).get() ?: return false
        state.selective = true
        return true
    }

    private class Scope(val state: State) : ChannelDuplexHandler() {
        override fun channelInactive(ctx: ChannelHandlerContext) {
            state.capture = null
            ctx.fireChannelInactive()
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            state.scopes.addLast(IdentityHashMap())
            try {
                ctx.write(msg, promise)
            } finally {
                state.scopes.removeLast()
            }
        }
    }

    private class Record(val state: State) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg !is ByteBuf) {
                ctx.write(msg, promise)
                return
            }
            // An unassociated buffer stays bare; the 1.21 encoder sends those immediately.
            val packet = state.scopes.peekLast()?.remove(msg)
            if (packet == null) {
                ctx.write(msg, promise)
                return
            }
            val capture = state.capture
            if (capture != null && (packet.type() == CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD ||
                    packet.type() == GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES)) {
                runCatching {
                    val phase = state.phase(ctx)
                    if (phase == null) {
                        state.unknownPhaseCount++
                        if (state.unknownPhaseCount == 1L || state.unknownPhaseCount % 1024L == 0L) {
                            logger.warn("Skipped packet capture with unknown protocol phase; count={}", state.unknownPhaseCount)
                        }
                    } else {
                        capture.record(msg, state.identity(packet), phase)
                    }
                }.onFailure {
                    state.capture = null
                    state.captureFailed = true
                    logger.error("Packet capture detached after a recording failure", it)
                }
            }
            if (!ZstdCompressionPipeline.hasOutboundEncoder(ctx.channel())) {
                if (PacketMetrics.batchMetricsEnabled) {
                    PacketMetrics.recordUncompressed(packetIdentity(packet), msg.readableBytes(), promise)
                }
                ctx.write(msg, promise)
                return
            }
            // The identity also feeds v4 logical bytes, so every associated packet carries it.
            val policy = if (state.selective) PacketBatchPolicy21.classify(packet) else ZstdBatchPolicy.Immediate
            ctx.write(ZstdSendingRecord(msg, policy, packetIdentity(packet)), promise)
        }
    }
}
