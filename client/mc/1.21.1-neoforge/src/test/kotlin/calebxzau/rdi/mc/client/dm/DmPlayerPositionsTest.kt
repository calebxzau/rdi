package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DmPlayerPositionsTest {
    private fun player(x: Double, z: Double, dimension: String? = null) = CompoundTag().apply {
        put("Pos", ListTag().apply { add(DoubleTag.valueOf(x)); add(DoubleTag.valueOf(64.0)); add(DoubleTag.valueOf(z)) })
        dimension?.let { putString("Dimension", it) }
    }

    @Test
    fun savedPositionMapsToItsChunk() {
        assertEquals(SyncChunkKey("minecraft:the_nether", -1, 2), DmPlayerPositions.chunkOf(player(-0.5, 47.9, "minecraft:the_nether")))
        assertEquals(SyncChunkKey("minecraft:overworld", 0, -1), DmPlayerPositions.chunkOf(player(15.9, -16.0)))
        assertNull(DmPlayerPositions.chunkOf(CompoundTag()))
    }

    @Test
    fun loadReadsEveryPlayerFile() {
        val root = Files.createTempDirectory("dm-player-positions")
        val directory = Files.createDirectories(root.resolve("playerdata"))
        val id = UUID.randomUUID()
        NbtIo.writeCompressed(player(100.0, 200.0), directory.resolve("$id.dat"))
        Files.writeString(directory.resolve("not-a-uuid.dat"), "ignored")
        val positions = DmPlayerPositions()
        assertEquals(1, positions.load(root).getOrThrow())
        assertEquals(listOf(SyncChunkKey("minecraft:overworld", 6, 12)), positions.all())
    }
}
