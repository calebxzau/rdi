package calebxzau.rdi.mc.zstdcodec.v21

import kotlin.test.*

class PacketRefSettings21Test {
    @Test
    fun `references default on and explicit false disables`() {
        assertEquals(PacketRefSettings21(true, 256, 1024), PacketRefSettings21.read({ null }) { error(it) })
        assertFalse(PacketRefSettings21.read({ if (it == "rdi.pktref.enabled") " false " else null }) { error(it) }.enabled)
        assertTrue(PacketRefSettings21.read({ if (it == "rdi.pktref.enabled") "true" else null }) { error(it) }.enabled)
    }

    @Test
    fun `invalid and out of range table sizes are reported and bounded`() {
        val warnings = mutableListOf<String>()
        val high = PacketRefSettings21.read(mapOf("rdi.pktref.slots" to "9999", "rdi.pktref.maxEntryBytes" to "9999")::get, warnings::add)
        assertEquals(PacketRefSettings21(true, 1024, 2048), high)
        val low = PacketRefSettings21.read(mapOf("rdi.pktref.slots" to "0", "rdi.pktref.maxEntryBytes" to "0")::get, warnings::add)
        assertEquals(PacketRefSettings21(true, 1, 8), low)
        val invalid = PacketRefSettings21.read(mapOf("rdi.pktref.slots" to "many", "rdi.pktref.maxEntryBytes" to " 512 ")::get, warnings::add)
        assertEquals(PacketRefSettings21(true, 256, 512), invalid)
        assertEquals(5, warnings.size)
    }

    @Test
    fun `every unmet prerequisite keeps references off`() {
        val settings = PacketRefSettings21(true, 256, 1024)
        assertEquals(ExtensionDecision21.Enable, settings.decide(false, true, true, true))
        assertEquals(ExtensionDecision21.Disabled, settings.copy(enabled = false).decide(false, true, true, true))
        assertEquals(ExtensionDecision21.MemoryConnection, settings.decide(true, true, true, true))
        assertEquals(ExtensionDecision21.RemoteAbsent, settings.decide(false, false, true, true))
        assertEquals(ExtensionDecision21.CompressionOff, settings.decide(false, true, false, false))
        assertEquals(ExtensionDecision21.ForeignEncoder, settings.decide(false, true, true, false))
    }
}
