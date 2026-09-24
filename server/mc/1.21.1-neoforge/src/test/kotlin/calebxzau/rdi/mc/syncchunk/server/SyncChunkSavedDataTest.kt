package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkEntry
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.RegistryAccess
import net.minecraft.world.level.storage.DimensionDataStorage
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncChunkSavedDataTest {
    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherOwner = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun ownerCanRemoveSelectionInDimensionThatIsNotLoadedAndDataMarksDirty(): Unit {
        val data = emptyData()
        val key = SyncChunkKey("removed_mod:old_dimension", -3, 8)
        assertEquals(SyncChunkAddResult.Added, data.add(key, owner))
        data.setDirty(false)

        assertEquals(SyncChunkRemoveResult.OwnedByOther, data.remove(key, otherOwner))
        assertFalse(data.isDirty())
        assertEquals(listOf(SyncChunkEntry(key, owner)), data.snapshot())

        assertEquals(SyncChunkRemoveResult.Removed, data.remove(key, owner))
        assertTrue(data.isDirty())
        assertTrue(data.snapshot().isEmpty())
        data.setDirty(false)
        assertEquals(SyncChunkRemoveResult.AlreadyAbsent, data.remove(key, owner))
        assertFalse(data.isDirty())
    }

    @Test
    fun savedDataRoundTripsEntriesThroughNbt(): Unit {
        val key = SyncChunkKey("removed_mod:old_dimension", -3, 8)
        val data = emptyData()
        data.add(key, owner)
        val serialized = data.save(CompoundTag(), RegistryAccess.EMPTY)

        val restored = SyncChunkSavedData.factory().deserializer().apply(serialized, RegistryAccess.EMPTY)

        assertEquals(listOf(SyncChunkEntry(key, owner)), restored.snapshot())
    }

    @Test
    fun corruptExistingStorageIsNotReplacedOrCachedAsEmptyAndMissingStorageCreatesNormally(): Unit {
        SharedConstants.tryDetectVersion()
        val directory = Files.createTempDirectory("syncchunk-storage-test")
        try {
            val dataDirectory = Files.createDirectory(directory.resolve("data"))
            val dataFile = dataDirectory.resolve("${SyncChunkSavedData.FILE_ID}.dat")
            val originalBytes = byteArrayOf(0x13, 0x37, 0x01, 0x02)
            Files.write(dataFile, originalBytes)
            val corruptStorage = DimensionDataStorage(dataDirectory.toFile(), null, null)

            assertEquals(null, corruptStorage.get(SyncChunkSavedData.factory(), SyncChunkSavedData.FILE_ID))
            assertFailsWith<IllegalStateException> {
                SyncChunkService.getOrCreate(corruptStorage, dataFile)
            }
            assertEquals(null, corruptStorage.get(SyncChunkSavedData.factory(), SyncChunkSavedData.FILE_ID))
            corruptStorage.save()
            assertContentEquals(originalBytes, Files.readAllBytes(dataFile))

            val missingDirectory = Files.createDirectory(directory.resolve("missing-data"))
            val missingFile = missingDirectory.resolve("${SyncChunkSavedData.FILE_ID}.dat")
            val missingStorage = DimensionDataStorage(missingDirectory.toFile(), null, null)
            val created = SyncChunkService.getOrCreate(missingStorage, missingFile)
            assertTrue(created.snapshot().isEmpty())
            assertFalse(Files.exists(missingFile))
            missingStorage.save()
            assertFalse(Files.exists(missingFile))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }

    private fun emptyData(): SyncChunkSavedData = SyncChunkSavedData.factory().constructor().get()
}
