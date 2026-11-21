package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20.Decision
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20.ReferenceSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class RServerPacketRefsTest {
    @Test
    fun `references are enabled by default`() {
        assertEquals(ReferenceSettings(true, 256, 1024), ServerPacketExtensions20.referenceSettings { null })
        assertEquals(false, ServerPacketExtensions20.referenceSettings(mapOf("rdi.pktref.enabled" to "false")::get).enabled)
        assertEquals(true, ServerPacketExtensions20.referenceSettings(mapOf("rdi.pktref.enabled" to "true")::get).enabled)
    }

    @Test
    fun `table settings are clamped to the range every client accepts`() {
        val high = ServerPacketExtensions20.referenceSettings(
            mapOf("rdi.pktref.slots" to "4096", "rdi.pktref.maxEntryBytes" to "8192")::get,
        )
        assertEquals(1024, high.slots)
        assertEquals(2048, high.maxEntryBytes)

        val low = ServerPacketExtensions20.referenceSettings(
            mapOf("rdi.pktref.slots" to "0", "rdi.pktref.maxEntryBytes" to "1")::get,
        )
        assertEquals(1, low.slots)
        assertEquals(8, low.maxEntryBytes)

        val invalid = ServerPacketExtensions20.referenceSettings(
            mapOf("rdi.pktref.slots" to "many", "rdi.pktref.maxEntryBytes" to " 512 ")::get,
        )
        assertEquals(256, invalid.slots)
        assertEquals(512, invalid.maxEntryBytes)
    }

    @Test
    fun `each missing precondition keeps plain packets`() {
        fun decide(
            enabled: Boolean = true,
            memoryConnection: Boolean = false,
            remotePresent: Boolean = true,
            hasEncoder: Boolean = true,
            hasRdiEncoder: Boolean = true,
        ) = ServerPacketExtensions20.decide(enabled, memoryConnection, remotePresent, hasEncoder, hasRdiEncoder)

        assertEquals(Decision.Enable, decide())
        assertEquals(Decision.Disabled, decide(enabled = false))
        assertEquals(Decision.MemoryConnection, decide(memoryConnection = true))
        assertEquals(Decision.RemoteAbsent, decide(remotePresent = false))
        assertEquals(Decision.CompressionOff, decide(hasEncoder = false, hasRdiEncoder = false))
        assertEquals(Decision.ForeignEncoder, decide(hasRdiEncoder = false))
    }
}
