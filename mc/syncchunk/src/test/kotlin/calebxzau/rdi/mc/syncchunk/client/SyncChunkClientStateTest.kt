package calebxzau.rdi.mc.syncchunk.client

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncChunkClientStateTest {
    private val overworld = "minecraft:overworld"
    private val nether = "minecraft:the_nether"
    private val roomA = Any()
    private val roomB = Any()

    private fun list(vararg chunks: SyncChunkKey) = SyncChunkList(chunks.toList())

    @BeforeTest
    @AfterTest
    fun reset() = SyncChunkClientState.clear(null)

    @Test
    fun hasNoListUntilOneArrives() {
        assertNull(SyncChunkClientState.chunksIn(roomA, overworld))
    }

    @Test
    fun groupsChunksByDimensionForTheReceivingConnectionOnly() {
        val home = SyncChunkKey(overworld, -1, 2)
        val farm = SyncChunkKey(nether, 3, -4)
        SyncChunkClientState.replace(roomA, list(home, farm))

        assertEquals(listOf(home), SyncChunkClientState.chunksIn(roomA, overworld))
        assertEquals(listOf(farm), SyncChunkClientState.chunksIn(roomA, nether))
        assertEquals(emptyList(), SyncChunkClientState.chunksIn(roomA, "minecraft:the_end"))
        assertNull(SyncChunkClientState.chunksIn(roomB, overworld))
    }

    @Test
    fun eachListReplacesThePreviousOne() {
        val removed = SyncChunkKey(overworld, 0, 0)
        val kept = SyncChunkKey(overworld, 1, 1)
        SyncChunkClientState.replace(roomA, list(removed, kept))
        SyncChunkClientState.replace(roomA, list(kept))
        assertEquals(listOf(kept), SyncChunkClientState.chunksIn(roomA, overworld))

        SyncChunkClientState.replace(roomA, list())
        assertEquals(emptyList(), SyncChunkClientState.chunksIn(roomA, overworld))
    }

    @Test
    fun leavingAnotherRoomKeepsTheCurrentList() {
        val chunk = SyncChunkKey(overworld, 5, 6)
        SyncChunkClientState.replace(roomB, list(chunk))

        SyncChunkClientState.clear(roomA)
        assertEquals(listOf(chunk), SyncChunkClientState.chunksIn(roomB, overworld))

        SyncChunkClientState.clear(roomB)
        assertNull(SyncChunkClientState.chunksIn(roomB, overworld))
    }
}
