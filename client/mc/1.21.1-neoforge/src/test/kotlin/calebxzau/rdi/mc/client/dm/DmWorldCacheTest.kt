package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.syncchunk.SyncChunkRequestError
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmWorldCacheTest {
    private fun writeLevel(file: Path, withPlayer: Boolean) {
        val data = CompoundTag().apply {
            putString("LevelName", "room")
            if (withPlayer) put("Player", CompoundTag().apply { putFloat("Health", 7f) })
        }
        NbtIo.writeCompressed(CompoundTag().apply { put("Data", data) }, file)
    }

    private fun readData(file: Path): CompoundTag =
        NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("Data")

    @Test
    fun onlyTheMastersNoCheckpointAnswerAllowsFallingBackToTheInitialWorld(): Unit {
        assertTrue(DmWorldCache.isNoCheckpoint(SyncChunkRequestError("NoCheckpoint", "world checkpoint is not ready")))
        assertFalse(DmWorldCache.isNoCheckpoint(SyncChunkRequestError("StorageError", "checkpoint is not applied")))
        assertFalse(DmWorldCache.isNoCheckpoint(SyncChunkRequestError("Http502", "bad gateway")))
        assertFalse(DmWorldCache.isNoCheckpoint(IOException("connection reset")))
    }

    @Test
    fun removesPreviousHostPlayerFromBothLevelFiles(): Unit {
        val world = Files.createTempDirectory("dm-world-cache-")
        try {
            writeLevel(world.resolve("level.dat"), withPlayer = true)
            writeLevel(world.resolve("level.dat_old"), withPlayer = true)
            DmWorldCache.removeLevelPlayer(world).getOrThrow()
            for (name in listOf("level.dat", "level.dat_old")) {
                val data = readData(world.resolve(name))
                assertFalse(data.contains("Player"))
                assertEquals("room", data.getString("LevelName"))
            }
        } finally {
            DmWorldInitArchive.deleteRecursively(world)
        }
    }

    @Test
    fun leavesWorldsWithoutPlayerOrBackupAlone(): Unit {
        val world = Files.createTempDirectory("dm-world-cache-")
        try {
            val level = world.resolve("level.dat")
            writeLevel(level, withPlayer = false)
            val before = Files.readAllBytes(level)
            DmWorldCache.removeLevelPlayer(world).getOrThrow()
            assertEquals(before.toList(), Files.readAllBytes(level).toList())
            assertFalse(Files.exists(world.resolve("level.dat_old")))
        } finally {
            DmWorldInitArchive.deleteRecursively(world)
        }
    }
}
