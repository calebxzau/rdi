package calebxzau.rdi.mc.v20.server.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkEntry
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import calebxzau.rdi.mc.syncchunk.SyncChunkStorage
import com.mojang.datafixers.DSL
import com.mojang.datafixers.DataFixer
import com.mojang.datafixers.schemas.Schema
import com.mojang.serialization.Dynamic
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import net.minecraft.world.level.storage.DimensionDataStorage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncChunkSavedData20Test {
    private val alice = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val bob = UUID.fromString("00000000-0000-0000-0000-000000000002")

    init {
        SharedConstants.tryDetectVersion()
    }

    @Test
    fun onlyActualChangesMarkDirty() {
        val data = SyncChunkSavedData20.empty()
        val key = SyncChunkKey("removed_mod:old_dimension", -3, 8)

        assertEquals(SyncChunkAddResult.Added, data.add(key, alice))
        assertTrue(data.isDirty())
        data.setDirty(false)

        assertEquals(SyncChunkAddResult.AlreadyPresent, data.add(key, alice))
        assertEquals(SyncChunkAddResult.OccupiedByOther, data.add(key, bob))
        assertEquals(SyncChunkRemoveResult.OwnedByOther, data.remove(key, bob))
        assertFalse(data.isDirty())
        assertEquals(listOf(SyncChunkEntry(key, alice)), data.snapshot())

        assertEquals(SyncChunkRemoveResult.Removed, data.remove(key, alice))
        assertTrue(data.isDirty())
        data.setDirty(false)
        assertEquals(SyncChunkRemoveResult.AlreadyAbsent, data.remove(key, alice))
        assertFalse(data.isDirty())
    }

    @Test
    fun nbtRoundTripKeepsDimensionsCoordinatesAndOwners() {
        val data = SyncChunkSavedData20.empty()
        data.add(SyncChunkKey("minecraft:overworld", -1, -1), alice)
        data.add(SyncChunkKey("minecraft:the_nether", 30_000, -5), bob)
        data.add(SyncChunkKey("removed_mod:old_dimension", 0, 0), alice)

        val restored = SyncChunkSavedData20.load(data.save(CompoundTag()))

        assertEquals(data.snapshot(), restored.snapshot())
        assertTrue(SyncChunkSavedData20.load(SyncChunkSavedData20.empty().save(CompoundTag())).snapshot().isEmpty())
    }

    @Test
    fun unreadableExistingFileIsNeverReplaced() {
        val corruptBytes = byteArrayOf(0x13, 0x37, 0x01, 0x02)
        val invalidEntryBytes = ByteArrayOutputStream().use { output ->
            NbtIo.writeCompressed(CompoundTag().apply { put("data", root(chunk(owner = "not-a-uuid"))) }, output)
            output.toByteArray()
        }

        listOf(corruptBytes, invalidEntryBytes).forEach { originalBytes ->
            withDataDirectory { directory ->
                val dataFile = dataFile(directory)
                Files.write(dataFile, originalBytes)
                val storage = DimensionDataStorage(directory.toFile(), IdentityDataFixer)

                repeat(2) {
                    assertFailsWith<IllegalStateException> { SyncChunkSavedData20.getOrCreate(storage, dataFile) }
                }
                storage.save()

                assertContentEquals(originalBytes, Files.readAllBytes(dataFile))
            }
        }
    }

    @Test
    fun missingFileCreatesEmptyDataThatIsWrittenOnlyAfterAChange() {
        withDataDirectory { directory ->
            val dataFile = dataFile(directory)
            val storage = DimensionDataStorage(directory.toFile(), IdentityDataFixer)

            val created = SyncChunkSavedData20.getOrCreate(storage, dataFile)
            assertTrue(created.snapshot().isEmpty())
            assertSame(created, SyncChunkSavedData20.getOrCreate(storage, dataFile))
            storage.save()
            assertFalse(Files.exists(dataFile))

            created.add(SyncChunkKey("minecraft:overworld", -7, 2), alice)
            storage.save()
            assertTrue(Files.exists(dataFile))

            val reloaded = SyncChunkSavedData20.getOrCreate(DimensionDataStorage(directory.toFile(), IdentityDataFixer), dataFile)
            assertEquals(created.snapshot(), reloaded.snapshot())
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

    private fun dataFile(directory: Path): Path = directory.resolve("${SyncChunkStorage.FILE_ID}.dat")

    private fun withDataDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("syncchunk20-storage-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** Test files are written at the current data version, so no fixes apply. */
    private object IdentityDataFixer : DataFixer {
        override fun <T> update(type: DSL.TypeReference, input: Dynamic<T>, version: Int, newVersion: Int): Dynamic<T> = input

        override fun getSchema(key: Int): Schema = throw UnsupportedOperationException()
    }
}
