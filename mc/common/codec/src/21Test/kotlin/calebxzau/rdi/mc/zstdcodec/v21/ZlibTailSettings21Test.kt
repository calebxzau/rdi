package calebxzau.rdi.mc.zstdcodec.v21

import kotlin.test.*

class ZlibTailSettings21Test {
    @Test
    fun `default enables wonder shop only`() {
        val settings = ZlibTailSettings21.read({ null }) { error(it) }
        assertEquals(ZlibTailSettings21(true, setOf("wonder_shop_story_core")), settings)
        assertEquals(ExtensionDecision21.Enable, settings.decide(false, true, true, true))
        assertEquals(ExtensionDecision21.Disabled, settings.copy(enabled = false).decide(false, true, true, true))
        assertEquals(ExtensionDecision21.RemoteAbsent, settings.decide(false, false, true, true))
        assertEquals(ExtensionDecision21.MemoryConnection, settings.decide(true, true, true, true))
    }

    @Test
    fun `namespaces are trimmed and an empty list turns the extension off`() {
        val listed = ZlibTailSettings21.read({ if (it.endsWith("namespaces")) " a, b ,,*" else null }) { error(it) }
        assertEquals(setOf("a", "b", "*"), listed.namespaces)

        val warnings = mutableListOf<String>()
        val empty = ZlibTailSettings21.read({ if (it.endsWith("namespaces")) " , " else null }, warnings::add)
        assertEquals(emptySet(), empty.namespaces)
        assertEquals(1, warnings.size)
        assertEquals(ExtensionDecision21.Disabled, empty.decide(false, true, true, true))

        assertFalse(ZlibTailSettings21.read({ if (it.endsWith("enabled")) "false" else null }) { error(it) }.enabled)
    }
}
