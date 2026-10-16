package calebxzhou.rdi.client.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebugServerHostTest {
    @Test
    fun `host comes from the launcher server url`() {
        assertEquals("192.168.1.20", parseDebugServerHost("http://192.168.1.20:65231"))
        assertEquals("127.0.0.1", parseDebugServerHost(" http://127.0.0.1:65231 "))
        assertEquals("dev.example.com", parseDebugServerHost("https://dev.example.com"))
    }

    @Test
    fun `missing or unusable server url keeps the local debug master`() {
        assertNull(parseDebugServerHost(null))
        assertNull(parseDebugServerHost(""))
        assertNull(parseDebugServerHost("not a url"))
        assertNull(parseDebugServerHost("192.168.1.20:65231"))
    }
}
