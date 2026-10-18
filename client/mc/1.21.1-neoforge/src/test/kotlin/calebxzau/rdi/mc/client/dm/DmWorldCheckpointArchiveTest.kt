@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmWorldCheckpointArchiveTest {
    @Test
    fun metadataAndRegionMembersUseCheckpointLayout() {
        val root = Files.createTempDirectory("dm-checkpoint-archive")
        val world = root.resolve("world").also { Files.createDirectories(it) }
        Files.write(world.resolve("level.dat"), byteArrayOf(1, 2, 3))
        val output = root.resolve("checkpoint.zip")
        val change = DmRegionChange(DmRegionChunkKey("region/r.0.0.mca", 4), 12, byteArrayOf(0, 0, 0, 2, 3, 4), 1)
        DmWorldCheckpointArchive.write(
            DmWorldCheckpointArchive.Request(v7(), v7(), v7(), 1, true, world, listOf("level.dat"), emptyList(), emptyList(), listOf(change)),
            output,
        ).getOrThrow()
        ZipFile(output.toFile()).use { zip ->
            assertTrue(zip.getEntry("metadata.json") != null)
            val region = zip.getEntry("regions/region/r.0.0.mca/4")
            assertTrue(region != null)
            assertEquals(java.util.zip.ZipEntry.STORED, region.method)
        }
    }

    private fun v7(): UUID = UUID.fromString(kotlin.uuid.Uuid.generateV7().toString())
}
