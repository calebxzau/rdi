package calebxzhou.rdi.mc.server.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RServerIdleTest {
    @Test
    fun `idle reports are honoured unless explicitly disabled`() {
        assertTrue(RServerIdle.isEnabled { null })
        assertTrue(RServerIdle.isEnabled { if (it == "rdi.idle.enabled") "true" else null })
        assertFalse(RServerIdle.isEnabled { if (it == "rdi.idle.enabled") " false " else null })
    }
}
