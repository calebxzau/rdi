package calebxzhou.rdi.client.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModVersionTextTest {
    @Test
    fun `strips minecraft version and loader names`() {
        assertEquals("0.5.1", simplifyModVersion("mc1.20.1-0.5.1"))
        assertEquals("15.2.0.27", simplifyModVersion("1.20.1-forge-15.2.0.27"))
        assertEquals("0.5.1", simplifyModVersion("0.5.1+1.20.1"))
        assertEquals("2.1.0", simplifyModVersion("2.1.0-NeoForge-1.21.1"))
        assertEquals("3.0.4", simplifyModVersion("fabric-3.0.4+mc1.20.1"))
    }

    @Test
    fun `keeps build metadata that is not a minecraft version`() {
        assertEquals("0.5.1+build.12", simplifyModVersion("0.5.1+build.12"))
        assertEquals("1.0.0-beta.3", simplifyModVersion("1.0.0-beta.3"))
    }

    @Test
    fun `removes leading v`() {
        assertEquals("2.3.0", simplifyModVersion("v2.3.0"))
        assertEquals("2.3.0", simplifyModVersion("V2.3.0-1.20.1"))
    }

    @Test
    fun `keeps ambiguous values as declared`() {
        assertEquals("1.20.1", simplifyModVersion("1.20.1"))
        assertEquals("1.10-1.20.1", simplifyModVersion("1.10-1.20.1"))
        assertEquals("1.12.0-beta.3", simplifyModVersion("1.12.0-beta.3"))
        assertEquals("release", simplifyModVersion(" release "))
    }

    @Test
    fun `blank and placeholder versions are unknown`() {
        assertNull(simplifyModVersion(null))
        assertNull(simplifyModVersion("  "))
        assertNull(simplifyModVersion("\${file.jarVersion}"))
    }
}
