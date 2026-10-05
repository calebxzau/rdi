package calebxzau.rdi.mc.syncchunk

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncChunkSaveFormatTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val bob = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun roundTripKeepsDimensionsCoordinatesAndOwners() {
        val state = SyncChunkState.empty()
        state.add(SyncChunkKey("minecraft:overworld", -1, -1), alice)
        state.add(SyncChunkKey("minecraft:the_nether", 30_000, -5), bob)
        state.add(SyncChunkKey("removed_mod:old_dimension", 0, 0), alice)

        val restored = SyncChunkSaveFormat.read(SyncChunkSaveFormat.write(state.snapshot(), CompoundTag()))

        assertEquals(state.snapshot(), restored.snapshot())
        assertTrue(SyncChunkSaveFormat.read(SyncChunkSaveFormat.write(emptyList(), CompoundTag())).snapshot().isEmpty())
    }

    @Test
    fun writesTheSharedFieldNames() {
        val tag = SyncChunkSaveFormat.write(listOf(SyncChunkEntry(SyncChunkKey("minecraft:overworld", 4, -9), alice)), CompoundTag())
        val chunk = (tag.get("chunks") as ListTag).getCompound(0)

        assertEquals("minecraft:overworld", chunk.getString("dimension"))
        assertEquals(4, chunk.getInt("x"))
        assertEquals(-9, chunk.getInt("z"))
        assertEquals(alice.toString(), chunk.getString("owner"))
    }

    @Test
    fun strictReadRejectsTheWholeFileForAnyInvalidEntry() {
        val invalid = listOf(
            CompoundTag(),
            CompoundTag().apply { putString("chunks", "none") },
            root(IntTag.valueOf(1)),
            root(chunk().apply { remove("dimension") }),
            root(chunk().apply { putString("x", "1") }),
            root(chunk().apply { remove("owner") }),
            root(chunk(owner = "not-a-uuid")),
            root(chunk(dimension = "Bad Dimension")),
            root(chunk(), chunk(owner = bob.toString())),
            root(*Array(SyncChunkState.DEFAULT_MAX_TOTAL + 1) { chunk(x = it) }),
        )

        invalid.forEach { tag ->
            assertFailsWith<IllegalArgumentException>(tag.toString()) { SyncChunkSaveFormat.read(tag) }
        }
    }

    private fun chunk(
        dimension: String = "minecraft:overworld",
        x: Int = 0,
        z: Int = 0,
        owner: String = alice.toString(),
    ): CompoundTag = CompoundTag().apply {
        putString("dimension", dimension)
        putInt("x", x)
        putInt("z", z)
        putString("owner", owner)
    }

    private fun root(vararg chunks: Tag): CompoundTag = CompoundTag().apply {
        put("chunks", ListTag().apply { chunks.forEach { add(it) } })
    }
}
