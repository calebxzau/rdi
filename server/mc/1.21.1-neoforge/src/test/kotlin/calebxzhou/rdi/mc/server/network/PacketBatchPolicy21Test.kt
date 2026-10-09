package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.ZstdBatchPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketBatchPolicy21Test {
    @Test
    fun `only vanilla attribute updates wait for the tick`() {
        assertEquals(ZstdBatchPolicy.OneTick, PacketBatchPolicy21.classify(TestPacket21.attributes(7)))
        assertEquals(ZstdBatchPolicy.Immediate, PacketBatchPolicy21.classify(TestPacket21.other(7)))
    }
}
