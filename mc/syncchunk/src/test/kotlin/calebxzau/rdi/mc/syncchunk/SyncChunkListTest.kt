package calebxzau.rdi.mc.syncchunk

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncChunkListTest {
    @Test
    fun keepsOrderAndDropsOwners() {
        val nether = SyncChunkKey("minecraft:the_nether", 2, -2)
        val overworld = SyncChunkKey("minecraft:overworld", -7, 3)
        val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")

        assertEquals(listOf(nether, overworld), SyncChunkList.of(listOf(SyncChunkEntry(nether, owner), SyncChunkEntry(overworld, owner))).chunks)
        assertEquals(emptyList(), SyncChunkList(emptyList()).chunks)
    }

    @Test
    fun acceptsUpToTheServerQuota() {
        val full = List(SyncChunkList.MAX_ENTRY_COUNT) { SyncChunkKey("minecraft:overworld", it, -it) }
        assertEquals(SyncChunkState.DEFAULT_MAX_TOTAL, SyncChunkList(full).chunks.size)

        assertFailsWith<IllegalArgumentException> {
            SyncChunkList(full + SyncChunkKey("minecraft:overworld", -1, 1))
        }
    }

    @Test
    fun rejectsDuplicatesAndInvalidDimensions() {
        assertFailsWith<IllegalArgumentException> { SyncChunkList(List(2) { SyncChunkKey("minecraft:overworld", 1, 2) }) }
        assertFailsWith<IllegalArgumentException> { SyncChunkList(listOf(SyncChunkKey("Bad Dimension", 0, 0))) }
    }

    @Test
    fun dimensionIdsFollowResourceLocationRules() {
        listOf(
            "minecraft:overworld",
            "removed_mod:old_dimension",
            "a.b-c_d:path/with.dots-and_under",
            "overworld",
            ":overworld",
            "rdi:" + "a".repeat(SyncChunkList.MAX_DIMENSION_ID_LENGTH - 4),
        ).forEach { assertTrue(SyncChunkList.isValidDimensionId(it), it) }

        listOf(
            "",
            " ",
            "Minecraft:overworld",
            "minecraft:Overworld",
            "mine/craft:overworld",
            "minecraft:over world",
            "minecraft:a:b",
            "rdi:" + "a".repeat(SyncChunkList.MAX_DIMENSION_ID_LENGTH - 3),
        ).forEach { assertFalse(SyncChunkList.isValidDimensionId(it), it) }
    }
}
