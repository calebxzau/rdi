package calebxzau.rdi.client.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmMasterAddressTest {
    @Test
    fun `dm-master runs on the game server host`() {
        assertEquals("http://rdi.calebxzhou.cn:15566", dmMasterUrl("rdi.calebxzhou.cn"))
    }

    @Test
    fun `only the test account gets the game's DM entry`() {
        assertEquals(
            listOf("-Drdi.dm.server=http://127.0.0.1:15566"),
            dmGameJvmArgs(isTestAccount = true, serverHost = "127.0.0.1"),
        )
        assertTrue(dmGameJvmArgs(isTestAccount = false, serverHost = "127.0.0.1").isEmpty())
    }
}
