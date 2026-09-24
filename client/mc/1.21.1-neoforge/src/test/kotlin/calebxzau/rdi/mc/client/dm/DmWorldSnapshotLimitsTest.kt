package calebxzau.rdi.mc.client.dm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmWorldSnapshotLimitsTest {
    @Test
    fun rejectsNonPositivePublishedLimits(): Unit {
        val valid = DmWorldSnapshotLimits(1, 1, 1, 1, 1, 1, 1, 1)
        validateWorldSnapshotLimits(valid)
        assertFails { validateWorldSnapshotLimits(valid.copy(maxWorldEntries = 0)) }
        assertFails { validateWorldSnapshotLimits(valid.copy(maxPathBytes = 0)) }
        assertFails { validateWorldSnapshotLimits(valid.copy(maxPathDepth = -1)) }
        assertTrue(valid.maxUploadBytes > 0)
    }

    @Test
    fun publishedManifestSessionIsCheckedAgainstLatestNotActiveSession(): Unit {
        val published = UUID.fromString("018f0f4d-4b2e-7abc-8def-0123456789ab")
        val active = UUID.fromString("018f0f4d-4b2e-7abc-8def-0123456789ac")
        val unrelated = UUID.fromString("018f0f4d-4b2e-7abc-8def-0123456789ad")

        // The active session authenticates the request, but the latest record says it was
        // published by `published`; that older session is the expected manifest identity.
        validateWorldSnapshotPublishedSession(published, published)
        assertTrue(active != published)
        val failure = assertFailsWith<IllegalArgumentException> {
            validateWorldSnapshotPublishedSession(unrelated, published)
        }
        assertEquals("世界快照manifest的published session不匹配", failure.message)
    }
}
