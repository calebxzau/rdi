package calebxzau.rdi.mc.zstdcodec.v21

import kotlin.test.Test
import kotlin.test.assertEquals

class BatchSettings21Test {
    @Test
    fun `batching is off by default and only an explicit true enables it`() {
        assertEquals(BatchSettings21(true), BatchSettings21.read { null })
        assertEquals(BatchSettings21(true), BatchSettings21.read { if (it == "rdi.batch.enabled") " true " else null })
        assertEquals(BatchSettings21(false), BatchSettings21.read { if (it == "rdi.batch.enabled") "yes" else null })
    }

    @Test
    fun `entity batching is on unless explicitly disabled`() {
        assertEquals(true, BatchSettings21.read { null }.entityBatching)
        assertEquals(false, BatchSettings21.read { if (it == "rdi.batch.entity") " false " else null }.entityBatching)
        assertEquals(BatchSettings21(true, false), BatchSettings21.read { if (it == "rdi.batch.entity") "false" else null })
    }

    @Test
    fun `batching uses the shared eligibility order`() {
        val settings = BatchSettings21(true)
        assertEquals(ExtensionDecision21.Enable, settings.decide(false, true, true, true))
        assertEquals(ExtensionDecision21.Disabled, BatchSettings21(false).decide(false, true, true, true))
        assertEquals(ExtensionDecision21.MemoryConnection, settings.decide(true, true, true, true))
        assertEquals(ExtensionDecision21.RemoteAbsent, settings.decide(false, false, true, true))
        assertEquals(ExtensionDecision21.CompressionOff, settings.decide(false, true, false, false))
        assertEquals(ExtensionDecision21.ForeignEncoder, settings.decide(false, true, true, false))
    }
}
