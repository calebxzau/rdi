package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.metrics.PacketDirection
import calebxzau.rdi.mc.metrics.PacketMetricKey
import calebxzau.rdi.mc.metrics.PacketMetrics as SharedPacketMetrics
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import java.nio.file.Path

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

/** Minecraft 1.21 packet classification adapter for the shared metrics recorder. */
object PacketMetrics {
    @JvmStatic
    fun start(databasePath: Path) = SharedPacketMetrics.start(databasePath)

    @JvmStatic
    fun record(packet: Packet<*>, direction: PacketDirection, compressedFrameBytes: Int) {
        if (!SharedPacketMetrics.isActive) return
        SharedPacketMetrics.record(packetMetricKey(packet, direction), compressedFrameBytes)
    }

    @JvmStatic
    fun stop() = SharedPacketMetrics.stop()
}
