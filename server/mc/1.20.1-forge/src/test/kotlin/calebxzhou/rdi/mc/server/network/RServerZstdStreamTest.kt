package calebxzhou.rdi.mc.server.network

import calebxzhou.rdi.mc.server.network.RServerZstdStream.Decision
import calebxzhou.rdi.mc.server.network.RServerZstdStream.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class RServerZstdStreamTest {
    @Test
    fun `the stream is on with a 32 MiB window unless disabled`() {
        assertEquals(Settings(true, 25), RServerZstdStream.readSettings { null })
        assertEquals(false, RServerZstdStream.readSettings(mapOf("rdi.zstream.enabled" to "false")::get).enabled)
        assertEquals(true, RServerZstdStream.readSettings(mapOf("rdi.zstream.enabled" to "true")::get).enabled)
    }

    @Test
    fun `the window is clamped to the range every client accepts`() {
        assertEquals(25, RServerZstdStream.readSettings(mapOf("rdi.zstream.windowLog" to "27")::get).windowLog)
        assertEquals(20, RServerZstdStream.readSettings(mapOf("rdi.zstream.windowLog" to "10")::get).windowLog)
        assertEquals(23, RServerZstdStream.readSettings(mapOf("rdi.zstream.windowLog" to " 23 ")::get).windowLog)
        assertEquals(25, RServerZstdStream.readSettings(mapOf("rdi.zstream.windowLog" to "large")::get).windowLog)
    }

    @Test
    fun `each missing precondition keeps independent frames`() {
        fun decide(
            enabled: Boolean = true,
            memoryConnection: Boolean = false,
            remotePresent: Boolean = true,
            hasEncoder: Boolean = true,
            hasRdiEncoder: Boolean = true,
        ) = RServerZstdStream.decide(enabled, memoryConnection, remotePresent, hasEncoder, hasRdiEncoder)

        assertEquals(Decision.Enable, decide())
        assertEquals(Decision.Disabled, decide(enabled = false))
        assertEquals(Decision.MemoryConnection, decide(memoryConnection = true))
        assertEquals(Decision.RemoteAbsent, decide(remotePresent = false))
        assertEquals(Decision.CompressionOff, decide(hasEncoder = false, hasRdiEncoder = false))
        assertEquals(Decision.ForeignEncoder, decide(hasRdiEncoder = false))
    }
}
