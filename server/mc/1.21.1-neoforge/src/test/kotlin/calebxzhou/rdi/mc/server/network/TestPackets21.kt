package calebxzhou.rdi.mc.server.network

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.PacketType
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.GamePacketTypes
import net.minecraft.resources.ResourceLocation

/**
 * Stand-in packets with a real wire type. Constructing vanilla's attribute packet would initialise
 * the attribute registry, which plain JUnit cannot bootstrap.
 */
internal class TestPacket21(
    val tag: Int,
    private val packetType: PacketType<out Packet<ClientGamePacketListener>>,
) : Packet<ClientGamePacketListener> {
    override fun type(): PacketType<out Packet<ClientGamePacketListener>> = packetType

    override fun handle(listener: ClientGamePacketListener) = Unit

    companion object {
        private val OTHER = PacketType<TestPacket21>(PacketFlow.CLIENTBOUND, ResourceLocation.withDefaultNamespace("rdi_test_other"))

        fun attributes(tag: Int) = TestPacket21(tag, GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES)

        fun other(tag: Int) = TestPacket21(tag, OTHER)
    }
}
