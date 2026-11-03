package calebxzau.rdi.client.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModpackJvmArgsTest {
    @Test
    fun `params are normalized to one argument per line`() {
        val result = normalizeModpackJvmParams("  -Dexample=true\r\n\n-XX:+UnlockDiagnosticVMOptions  ")

        assertEquals(
            "-Dexample=true\n-XX:+UnlockDiagnosticVMOptions",
            result.getOrThrow(),
        )
    }

    @Test
    fun `launcher owned params are rejected`() {
        val result = parseModpackJvmParams("-Xmx8G")

        assertTrue(result.isFailure)
    }

    @Test
    fun `the DM server cannot be set through custom params`() {
        assertTrue(parseModpackJvmParams("-Drdi.dm.server=http://example.com:15566").isFailure)
    }
}
