package calebxzau.rdi.mc.syncchunk

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncChunkStorageTest {
    @Test
    fun loadedDataWinsAndMissingFileCreatesNewData() {
        val directory = Files.createTempDirectory("syncchunk-storage")
        try {
            val dataFile = directory.resolve("${SyncChunkStorage.FILE_ID}.dat")
            assertEquals("loaded", SyncChunkStorage.loadOrCreate(dataFile, { "loaded" }, { error("must not create") }))
            assertEquals("created", SyncChunkStorage.loadOrCreate(dataFile, { null }, { "created" }))
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun unreadableExistingFileIsNeverReplaced() {
        val directory = Files.createTempDirectory("syncchunk-storage")
        val dataFile = directory.resolve("${SyncChunkStorage.FILE_ID}.dat")
        try {
            Files.write(dataFile, byteArrayOf(0x13, 0x37))
            var created = false
            assertFailsWith<IllegalStateException> {
                SyncChunkStorage.loadOrCreate(dataFile, { null }, { created = true; "created" })
            }
            assertEquals(false, created)
            assertTrue(Files.exists(dataFile))
        } finally {
            Files.deleteIfExists(dataFile)
            Files.deleteIfExists(directory)
        }
    }
}
