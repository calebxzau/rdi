package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.metrics.PacketBatchFrameSample
import calebxzau.rdi.mc.metrics.PacketBatchMetrics
import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.metrics.PacketMetricKey
import calebxzau.rdi.mc.zstdcodec.ZstdBatchFormat
import calebxzau.rdi.mc.zstdcodec.ZstdBatchSample
import calebxzau.rdi.mc.zstdcodec.ZstdPacketIdentity
import io.netty.channel.ChannelPromise
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import java.nio.file.Path
import calebxzau.rdi.mc.metrics.PacketMetrics as SharedPacketMetrics

internal fun packetMetricKey(packet: Packet<*>, direction: PacketDirection): PacketMetricKey {
    val channel = when (packet) {
        is ClientboundCustomPayloadPacket -> packet.payload().type().id()
        is ServerboundCustomPayloadPacket -> packet.payload().type().id()
        else -> null
    }
    return PacketMetricKey(
        packetType = packet.type().id().toString(),
        direction = direction,
        namespace = channel?.namespace,
        path = channel?.path,
    )
}

internal fun packetIdentity(packet: Packet<*>): ZstdPacketIdentity {
    val key = packetMetricKey(packet, PacketDirection.S2C)
    return ZstdPacketIdentity(key.packetType, key.namespace, key.path)
}

/**
 * Minecraft 1.21 adapter for the shared recorders.
 *
 * v4 (`startBatchMetrics`) records S2C logical bytes per packet type plus every emitted frame, so batched blocks
 * and control frames are counted once instead of being split across packets. C2S keeps per-packet frame bytes.
 */
object PacketMetrics {
    @Volatile
    var batchMetricsEnabled: Boolean = false
        private set

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
    fun record(packet: Packet<*>, direction: PacketDirection, compressedFrameBytes: Int) {
        if (batchMetricsEnabled) {
            if (direction == PacketDirection.C2S) {
                PacketBatchMetrics.recordInbound(packetMetricKey(packet, direction), compressedFrameBytes)
            }
            return
        }
        if (!SharedPacketMetrics.isActive) return
        SharedPacketMetrics.record(packetMetricKey(packet, direction), compressedFrameBytes)
    }

    internal fun recordLogical(identity: ZstdPacketIdentity?, bytes: Int) =
        PacketBatchMetrics.recordLogical(
            PacketMetricKey(identity?.packetType ?: ":unknown-encoded", PacketDirection.S2C, identity?.namespace, identity?.path),
            bytes,
        )

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
            replacedFrameBytes = sample.replacedFrameBytes,
        ),
    )

    internal fun recordWriteOutcome(success: Boolean, records: Int) = PacketBatchMetrics.recordWriteOutcome(success, records)

    internal fun recordEncodingFailure(records: Int) = PacketBatchMetrics.recordEncodingFailure(records)

    /** A connection without the RDI encoder sends each packet as is; record it as one uncompressed frame. */
    internal fun recordUncompressed(identity: ZstdPacketIdentity?, bytes: Int, promise: ChannelPromise) {
        recordLogical(identity, bytes)
        PacketBatchMetrics.recordFrame(
            PacketBatchFrameSample("Uncompressed", 1, bytes, bytes, ZstdBatchFormat.varIntSize(bytes), "BARRIER", 0, 0, false),
        )
        promise.addListener { future -> recordWriteOutcome(future.isSuccess, 1) }
    }

    @JvmStatic
    fun stop() {
        SharedPacketMetrics.stop()
        PacketBatchMetrics.stop()
        batchMetricsEnabled = false
    }
}
