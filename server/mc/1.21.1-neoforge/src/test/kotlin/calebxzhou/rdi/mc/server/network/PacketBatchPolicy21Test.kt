package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import net.minecraft.network.protocol.game.GamePacketTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketBatchPolicy21Test {
    @Test
    fun `only vanilla attribute updates wait for the tick`() {
        assertEquals(ZstdBatchPolicy.OneTick, PacketBatchPolicy21.classify(TestPacket21.attributes(7)))
        assertEquals(ZstdBatchPolicy.Immediate, PacketBatchPolicy21.classify(TestPacket21.other(7)))
        assertEquals(ZstdBatchPolicy.Immediate, PacketBatchPolicy21.classify(TestPacket21.entityMove(7)))
    }

    @Test
    fun `entity tracking updates wait for the tick only with entity batching`() {
        for (type in listOf(
            GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS,
            GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT,
            GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT,
            GamePacketTypes.CLIENTBOUND_SET_ENTITY_MOTION,
            GamePacketTypes.CLIENTBOUND_ROTATE_HEAD,
            GamePacketTypes.CLIENTBOUND_SET_ENTITY_DATA,
            GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY,
        )) {
            assertEquals(ZstdBatchPolicy.OneTick, PacketBatchPolicy21.classify(TestPacket21(1, type), entityBatching = true))
            assertEquals(ZstdBatchPolicy.Immediate, PacketBatchPolicy21.classify(TestPacket21(1, type), entityBatching = false))
        }
        assertEquals(ZstdBatchPolicy.OneTick, PacketBatchPolicy21.classify(TestPacket21.attributes(7), entityBatching = true))
        assertEquals(ZstdBatchPolicy.Immediate, PacketBatchPolicy21.classify(TestPacket21.other(7), entityBatching = true))
        assertEquals(ZstdBatchPolicy.Immediate,
            PacketBatchPolicy21.classify(TestPacket21(1, GamePacketTypes.CLIENTBOUND_ADD_ENTITY), entityBatching = true))
    }
}
