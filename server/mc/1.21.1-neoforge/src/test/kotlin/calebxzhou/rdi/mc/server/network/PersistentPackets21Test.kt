package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketType
import net.minecraft.network.protocol.common.CommonPacketTypes
import net.minecraft.network.protocol.game.GamePacketTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentPackets21Test {
    /** Stand-in with a real wire type; building the real tables would need bootstrapped registries. */
    private class TypedPacket(private val packetType: PacketType<*>) : Packet<PacketListener> {
        @Suppress("UNCHECKED_CAST")
        override fun type(): PacketType<out Packet<PacketListener>> = packetType as PacketType<out Packet<PacketListener>>

        override fun handle(listener: PacketListener) = Unit
    }

    @Test
    fun `only the recipe and tag tables are kept across joins`() {
        assertEquals(PersistentPacketKind.RECIPES, PersistentPacketKinds21.kindOf(TypedPacket(GamePacketTypes.CLIENTBOUND_UPDATE_RECIPES)))
        assertEquals(PersistentPacketKind.TAGS, PersistentPacketKinds21.kindOf(TypedPacket(CommonPacketTypes.CLIENTBOUND_UPDATE_TAGS)))
        assertEquals(PersistentPacketKind.NONE, PersistentPacketKinds21.kindOf(TypedPacket(GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES)))
        assertEquals(PersistentPacketKind.NONE, PersistentPacketKinds21.kindOf(TestPacket21.other(1)))
    }

    @Test
    fun `persistent packets are on unless explicitly disabled`() {
        assertTrue(RServerPersistentPackets.isEnabled { null })
        assertFalse(RServerPersistentPackets.isEnabled { if (it == "rdi.pcache.enabled") " false " else null })
        assertTrue(RServerPersistentPackets.isEnabled { if (it == "rdi.pcache.enabled") "true" else null })
    }

    @Test
    fun `only remote clients with the query channel are asked`() {
        assertTrue(RServerPersistentPackets.shouldAsk(enabled = true, memoryConnection = false, clientHasChannel = true))
        assertFalse(RServerPersistentPackets.shouldAsk(enabled = false, memoryConnection = false, clientHasChannel = true))
        assertFalse(RServerPersistentPackets.shouldAsk(enabled = true, memoryConnection = true, clientHasChannel = true))
        assertFalse(RServerPersistentPackets.shouldAsk(enabled = true, memoryConnection = false, clientHasChannel = false))
    }
}
