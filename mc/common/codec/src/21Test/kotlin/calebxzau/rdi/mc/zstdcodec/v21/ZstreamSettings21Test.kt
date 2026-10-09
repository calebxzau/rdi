package calebxzau.rdi.mc.zstdcodec.v21

import kotlin.test.*

class ZstreamSettings21Test {
    @Test
    fun `default enabled with 32 MiB and all eligibility branches`() {
        val settings = ZstreamSettings21.read({ null }) { error(it) }
        assertEquals(ZstreamSettings21(true, 25), settings)
        assertEquals(ExtensionDecision21.Enable, settings.decide(false, true, true, true))
        assertEquals(ExtensionDecision21.Disabled, settings.copy(enabled = false).decide(false, true, true, true))
        assertEquals(ExtensionDecision21.MemoryConnection, settings.decide(true, true, true, true))
        assertEquals(ExtensionDecision21.RemoteAbsent, settings.decide(false, false, true, true))
        assertEquals(ExtensionDecision21.CompressionOff, settings.decide(false, true, false, false))
        assertEquals(ExtensionDecision21.ForeignEncoder, settings.decide(false, true, true, false))
    }

    @Test
    fun `invalid windows warn and explicit disable is honored`() {
        for ((input, expected) in listOf("invalid" to 25, "26" to 25, "19" to 20)) {
            val warnings = mutableListOf<String>()
            val settings = ZstreamSettings21.read({ if (it.endsWith("enabled")) "false" else input }, warnings::add)
            assertFalse(settings.enabled)
            assertEquals(expected, settings.windowLog)
            assertEquals(1, warnings.size)
        }
        assertEquals(23, ZstreamSettings21.read({ if (it.endsWith("windowLog")) " 23 " else null }) { error(it) }.windowLog)
    }
}
