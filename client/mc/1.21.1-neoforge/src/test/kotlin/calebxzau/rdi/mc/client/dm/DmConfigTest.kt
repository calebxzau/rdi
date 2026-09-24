package calebxzau.rdi.mc.client.dm

import java.net.URI
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
    fun directConfigAndAbsentOverrideUseThirtySecondDefault() {
        assertEquals(
            DmWorldSyncTiming.DEFAULT_INTERVAL_NANOS,
            DmConfig(URI("http://example.test/")).worldSyncIntervalNanos,
        )
        assertEquals(
            DmWorldSyncTiming.DEFAULT_INTERVAL_NANOS,
            DmConfig.fromSystemProperty("http://example.test/", null).getOrThrow()!!.worldSyncIntervalNanos,
        )
    }

    @Test
    fun validIntervalOverrideIsStoredAsNanoseconds() {
        assertEquals(
            DmWorldSyncTiming.intervalNanos(7),
            DmConfig.fromSystemProperty("http://example.test/", "7").getOrThrow()!!.worldSyncIntervalNanos,
        )
    }

    @Test
    fun invalidIntervalOverrideIsRejected() {
        listOf("", "abc", "0", "-1", Long.MAX_VALUE.toString()).forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                DmConfig.fromSystemProperty("http://example.test/", value).getOrThrow()
            }
        }
    }

    @Test
    fun invalidQueryAndSchemeAreRejected() {
        assertFailsWith<IllegalArgumentException> { DmConfig.fromSystemProperty("ftp://example.test:8080").getOrThrow() }
        assertFailsWith<IllegalArgumentException> { DmConfig.fromSystemProperty("http://example.test:8080/base?x=1").getOrThrow() }
    }
}
