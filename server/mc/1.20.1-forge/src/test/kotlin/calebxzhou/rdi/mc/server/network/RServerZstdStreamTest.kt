package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20.Decision
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20.StreamSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class RServerZstdStreamTest {
    @Test
    fun `the stream is on with a 32 MiB window unless disabled`() {
        assertEquals(StreamSettings(true, 25), ServerPacketExtensions20.streamSettings { null })
        assertEquals(false, ServerPacketExtensions20.streamSettings(mapOf("rdi.zstream.enabled" to "false")::get).enabled)
        assertEquals(true, ServerPacketExtensions20.streamSettings(mapOf("rdi.zstream.enabled" to "true")::get).enabled)
    }

    @Test
    fun `the window is clamped to the range every client accepts`() {
        assertEquals(25, ServerPacketExtensions20.streamSettings(mapOf("rdi.zstream.windowLog" to "27")::get).windowLog)
        assertEquals(20, ServerPacketExtensions20.streamSettings(mapOf("rdi.zstream.windowLog" to "10")::get).windowLog)
        assertEquals(23, ServerPacketExtensions20.streamSettings(mapOf("rdi.zstream.windowLog" to " 23 ")::get).windowLog)
        assertEquals(25, ServerPacketExtensions20.streamSettings(mapOf("rdi.zstream.windowLog" to "large")::get).windowLog)
    }

    @Test
    fun `each missing precondition keeps independent frames`() {
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
