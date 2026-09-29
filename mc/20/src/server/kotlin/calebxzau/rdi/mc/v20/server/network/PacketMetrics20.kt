package calebxzau.rdi.mc.v20.server.network

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
    private val resolvedNames = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String = PacketClassNames20.find(type) ?: type.name
    }

    @JvmStatic
    fun start(databasePath: Path) = SharedPacketMetrics.start(databasePath)

    @JvmStatic
    fun stop() = SharedPacketMetrics.stop()

    @JvmStatic
    fun record(packet: Packet<*>, direction: PacketDirection, compressedFrameBytes: Int) {
        if (!SharedPacketMetrics.isActive) return
        SharedPacketMetrics.record(metricKey(packet, direction), compressedFrameBytes)
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
