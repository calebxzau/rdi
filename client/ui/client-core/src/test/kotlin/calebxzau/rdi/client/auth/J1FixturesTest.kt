package calebxzau.rdi.client.auth

import calebxzhou.rdi.client.AppConfig
import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.common.ProxyConfig
import calebxzhou.rdi.common.model.HwSpec
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.serdesToml
import java.io.File
import kotlin.test.*

class J1FixturesTest {
    private fun fixture(name: String) = File("../../slint-proto/protocol/fixtures/$name.json").readText()

    /** Rust parses this fixture, so it must stay exactly what Kotlin writes, bare nulls included. */
    @Test
    fun configFixtureMatchesKotlinEncoder() {
        val written = serdesToml.encodeToString(
            AppConfig.serializer(),
            AppConfig(proxyConfig = ProxyConfig(enabled = true)),
        )
        val expected = File("../../slint-proto/protocol/fixtures/config_kotlin.toml").readText()
        assertEquals(expected.trim(), written.trim())
    }

    @Test
    fun rustCredentialsRemainComposeCompatible() {
        val credentials = LocalCredentials.parse(fixture("local_credentials"))
        assertEquals("10001", credentials.lastLogged?.qq)
        assertEquals("RDI玩家", credentials.loginInfos["older"]?.name)
        assertNull(credentials.autoLoginAccount)
        assertEquals("fake-room", credentials.lastPlayHost?.id)
        assertTrue(LocalCredentials.parse("{}").loginInfos.isEmpty())
    }

    @Test
    fun rustHardwareSpecDecodesAsRequiredModel() {
        val spec = serdesJson.decodeFromString<HwSpec>(fixture("hwspec"))
        assertEquals(3200000000L, spec.cpu.frequency)
        assertEquals(8589934592L, spec.gpus.single().vram)
        assertEquals(53, spec.display.single().width)
    }
}
