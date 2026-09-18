package calebxzau.rdi.mc.client.dm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DmConfigTest {
    @Test
    fun absentPropertyDisablesDm() {
        assertNull(DmConfig.fromSystemProperty("").getOrThrow())
    }

    @Test
    fun pathBaseAlwaysEndsWithSlashForRelativeRoutes() {
        assertEquals("http://example.test:8080/base/", DmConfig.fromSystemProperty("http://example.test:8080/base").getOrThrow()!!.baseUri.toString())
        assertEquals("http://example.test:8080/base/", DmConfig.fromSystemProperty("http://example.test:8080/base/").getOrThrow()!!.baseUri.toString())
    }

    @Test
    fun invalidQueryAndSchemeAreRejected() {
        assertFailsWith<IllegalArgumentException> { DmConfig.fromSystemProperty("ftp://example.test:8080").getOrThrow() }
        assertFailsWith<IllegalArgumentException> { DmConfig.fromSystemProperty("http://example.test:8080/base?x=1").getOrThrow() }
    }
}
