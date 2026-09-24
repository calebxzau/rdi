package calebxzau.rdi.mc.syncchunk

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncChunkStateTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val bob = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun addIsIdempotentAndOnlyOwnerCanRemove(): Unit {
        val state = SyncChunkState.empty()
        val key = SyncChunkKey("minecraft:overworld", -2, 4)

        assertEquals(SyncChunkAddResult.Added, state.add(key, alice))
        assertEquals(SyncChunkAddResult.AlreadyPresent, state.add(key, alice))
        assertEquals(SyncChunkAddResult.OccupiedByOther, state.add(key, bob))
        assertEquals(SyncChunkRemoveResult.OwnedByOther, state.remove(key, bob))
        assertEquals(SyncChunkRemoveResult.Removed, state.remove(key, alice))
        assertEquals(SyncChunkRemoveResult.AlreadyAbsent, state.remove(key, alice))
    }

    @Test
    fun quotaIsGlobalAcrossDimensions(): Unit {
        val state = SyncChunkState.empty(maxTotal = 2)
        assertEquals(SyncChunkAddResult.Added, state.add(SyncChunkKey("minecraft:overworld", 0, 0), alice))
        assertEquals(SyncChunkAddResult.Added, state.add(SyncChunkKey("minecraft:the_nether", 0, 0), bob))
        assertEquals(SyncChunkAddResult.QuotaReached, state.add(SyncChunkKey("minecraft:overworld", 1, 0), alice))
        assertEquals(2, state.size)
    }

    @Test
    fun restoreRejectsDuplicatesAndOverQuotaAndSnapshotIsSorted(): Unit {
        val first = SyncChunkEntry(SyncChunkKey("minecraft:overworld", 2, 0), alice)
        val second = SyncChunkEntry(SyncChunkKey("minecraft:nether", -1, 0), bob)

        assertFailsWith<IllegalArgumentException> { SyncChunkState.restore(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { SyncChunkState.restore(listOf(first, second), maxTotal = 1) }
        assertEquals(listOf(second, first), SyncChunkState.restore(listOf(first, second)).snapshot())
    }
}
