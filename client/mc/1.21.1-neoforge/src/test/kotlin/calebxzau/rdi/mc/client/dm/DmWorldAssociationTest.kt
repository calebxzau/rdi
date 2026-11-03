package calebxzau.rdi.mc.client.dm

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmWorldAssociationTest {
    private val hostId = UUID.fromString("0199a000-0000-7000-8000-000000000001")
    private val first = UUID.fromString("0199a000-0000-7000-8000-000000000002")
    private val second = UUID.fromString("0199a000-0000-7000-8000-000000000003")

    @Test
    fun freshWorldIsNeverStaleAgainstARoomWithoutCheckpoints(): Unit {
        assertFalse(DmWorldAssociation(hostId).isStaleAgainst(null))
    }

    @Test
    fun worldIsStaleOnlyWhenTheMasterHoldsAnUnknownCycle(): Unit {
        val confirmed = DmWorldAssociation(hostId, DmCheckpointMark(first))
        assertFalse(confirmed.isStaleAgainst(first))
        assertTrue(confirmed.isStaleAgainst(second))
        assertTrue(DmWorldAssociation(hostId).isStaleAgainst(first))
        val pending = DmWorldAssociation(hostId, DmCheckpointMark(first, second))
        assertFalse(pending.isStaleAgainst(second))
    }

    @Test
    fun legacyAssociationWithoutMarkIsTrusted(): Unit {
        val root = Files.createTempDirectory("dm-association-")
        try {
            Files.createDirectories(root.resolve("rdi"))
            Files.writeString(DmWorldAssociationStore.path(root), """{"hostId":"$hostId"}""")
            val legacy = DmWorldAssociationStore.read(root).getOrThrow()!!
            assertNull(legacy.checkpoint)
            assertFalse(legacy.isStaleAgainst(first))
        } finally {
            DmWorldInitArchive.deleteRecursively(root)
        }
    }

    @Test
    fun pendingKeepsTheConfirmedCycleUntilConfirmed(): Unit {
        val root = Files.createTempDirectory("dm-association-")
        try {
            DmWorldAssociationStore.write(root, hostId).getOrThrow()
            assertEquals(DmCheckpointMark(null), DmWorldAssociationStore.read(root).getOrThrow()!!.checkpoint)
            DmWorldAssociationStore.markPending(root, hostId, first).getOrThrow()
            DmWorldAssociationStore.markConfirmed(root, hostId, first).getOrThrow()
            DmWorldAssociationStore.markPending(root, hostId, second).getOrThrow()
            val association = DmWorldAssociationStore.read(root).getOrThrow()!!
            assertEquals(hostId, association.hostId)
            assertEquals(DmCheckpointMark(first, second), association.checkpoint)
            DmWorldAssociationStore.markConfirmed(root, hostId, second).getOrThrow()
            assertEquals(DmCheckpointMark(second), DmWorldAssociationStore.read(root).getOrThrow()!!.checkpoint)
        } finally {
            DmWorldInitArchive.deleteRecursively(root)
        }
    }
}
