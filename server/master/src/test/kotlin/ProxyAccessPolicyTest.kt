import calebxzhou.rdi.master.service.BackendHostPolicy
import calebxzhou.rdi.master.service.ProxyAccessPolicy
import net.peanuuutz.tomlkt.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyAccessPolicyTest {
    private val gameAddresses = listOf(
        "1.2.3.4:65230",
        "5.6.7.8:65230"
    )

    @Test
    fun `loopback can request proxy route`() {
        assertTrue(ProxyAccessPolicy.isAllowed("127.0.0.1", gameAddresses))
    }

    @Test
    fun `configured game node ip can request proxy route`() {
        assertTrue(ProxyAccessPolicy.isAllowed("5.6.7.8", gameAddresses))
    }

    @Test
    fun `unknown ip cannot request proxy route`() {
        assertFalse(ProxyAccessPolicy.isAllowed("9.10.11.12", gameAddresses))
    }

    @Test
    fun `local proxy uses local game host`() {
        assertEquals(
            "127.0.0.1",
            BackendHostPolicy.select("127.0.0.1", "127.0.0.1", "203.0.113.10")
        )
    }

    @Test
    fun `remote proxy uses remote game host`() {
        assertEquals(
            "203.0.113.10",
            BackendHostPolicy.select("5.6.7.8", "127.0.0.1", "203.0.113.10")
        )
    }

    @Test
    fun `missing remote game host falls back to local game host`() {
        assertEquals(
            "127.0.0.1",
            BackendHostPolicy.select("5.6.7.8", "127.0.0.1", null)
        )
    }

    @Test
    fun `blank remote game host falls back to local game host`() {
        assertEquals(
            "127.0.0.1",
            BackendHostPolicy.select("5.6.7.8", "127.0.0.1", "  ")
        )
    }

    @Test
    fun `old server config decodes without remote game host`() {
        val config = Toml.decodeFromString(
            calebxzhou.rdi.master.AppConfig.serializer(),
            """
            [server]
            gameHost = "127.0.0.1"
            """.trimIndent()
        )

        assertEquals("127.0.0.1", config.server.gameHost)
        assertNull(config.server.remoteGameHost)
    }
}
