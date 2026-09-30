package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.metrics.PacketBatchMetrics
import calebxzau.rdi.mc.metrics.PacketBatchFrameSample
import calebxzau.rdi.mc.zstdcodec.ZstdBatchSample
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import io.netty.channel.ChannelPromise
import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.metrics.PacketMetricKey
import calebxzau.rdi.mc.metrics.PacketMetrics as SharedPacketMetrics
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.resources.ResourceLocation
import java.nio.file.Path

/**
 * Minecraft 1.20.1 classification adapter for the shared packet metrics recorder.
 *
 * Vanilla class names come from the build-time `PacketClassNames20` table so the
 * database keeps readable names even when the runtime namespace is remapped. Any
 * other packet (mod payloads, unknown classes) keeps its runtime class name.
 */
object PacketMetrics20 {
    @Volatile
    var batchMetricsEnabled: Boolean = false
        private set
    private val resolvedNames = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String = PacketClassNames20.find(type) ?: type.name
    }

    @JvmStatic
    fun start(databasePath: Path) {
        batchMetricsEnabled = false
        SharedPacketMetrics.start(databasePath)
    }

    @JvmStatic
    fun startBatchMetrics(databasePath: Path) {
        SharedPacketMetrics.stop()
        batchMetricsEnabled = true
        PacketBatchMetrics.start(databasePath)
    }

    @JvmStatic
    fun stop() {
        SharedPacketMetrics.stop()
        PacketBatchMetrics.stop()
        batchMetricsEnabled = false
    }

    @JvmStatic
    fun record(packet: Packet<*>, direction: PacketDirection, compressedFrameBytes: Int) {
        if (batchMetricsEnabled) {
            if (direction == PacketDirection.C2S) {
                PacketBatchMetrics.recordInbound(metricKey(packet, direction), compressedFrameBytes)
            }
            return
        }
        if (!SharedPacketMetrics.isActive) return
        SharedPacketMetrics.record(metricKey(packet, direction), compressedFrameBytes)
    }

    private fun identityKey(identity: ZstdPacketIdentity?) = PacketMetricKey(
        identity?.packetType ?: ":unknown-encoded",
        PacketDirection.S2C,
        identity?.namespace,
        identity?.path,
    )

    internal fun recordLogical(identity: ZstdPacketIdentity?, bytes: Int) =
        PacketBatchMetrics.recordLogical(identityKey(identity), bytes)

    internal fun recordFrame(sample: ZstdBatchSample) = PacketBatchMetrics.recordFrame(
        PacketBatchFrameSample(
            frameKind = sample.frameKind.name,
            recordCount = sample.recordCount,
            payloadBytes = sample.payloadBytes,
            frameBytes = sample.blockBytes,
            outerPrefixBytes = sample.outerPrefixBytes,
            flushReason = sample.flushReason.name,
            waitNanos = sample.waitNanos,
            compressionNanos = sample.compressionNanos,
            singleRecordFallback = sample.singleRecordFallback,
            bufferedFlush = sample.bufferedFlush,
            firstFrameOfFlush = sample.firstFrameOfFlush,
        ),
    )

    internal fun recordWriteOutcome(success: Boolean, records: Int) =
        PacketBatchMetrics.recordWriteOutcome(success, records)

    internal fun recordEncodingFailure(records: Int) = PacketBatchMetrics.recordEncodingFailure(records)

    internal fun recordUncompressed(identity: ZstdPacketIdentity?, bytes: Int, promise: ChannelPromise) {
        recordLogical(identity, bytes)
        PacketBatchMetrics.recordFrame(
            PacketBatchFrameSample("Uncompressed", 1, bytes, bytes, varIntSize(bytes), "BARRIER", 0, 0, false),
        )
        promise.addListener { future -> recordWriteOutcome(future.isSuccess, 1) }
    }

    private fun varIntSize(value: Int): Int {
        var remaining = value
        var size = 1
        while (remaining and -128 != 0) {
            size++
            remaining = remaining ushr 7
        }
        return size
    }

    fun metricKey(packet: Packet<*>, direction: PacketDirection): PacketMetricKey {
        val channel = channelOf(packet)
        return PacketMetricKey(
            packetType = resolveName(packet.javaClass),
            direction = direction,
            namespace = channel?.namespace,
            path = channel?.path,
        )
    }

    fun resolveName(type: Class<*>): String = resolvedNames.get(type)

    /**
     * Only packets that carry a channel define a namespace/path. Responses without a
     * channel stay NULL instead of being attributed to an inferred channel.
     */
    private fun channelOf(packet: Packet<*>): ResourceLocation? = when (packet) {
        is ClientboundCustomPayloadPacket -> packet.identifier
        is ServerboundCustomPayloadPacket -> packet.identifier
        is ClientboundCustomQueryPacket -> packet.identifier
        else -> null
    }
}
