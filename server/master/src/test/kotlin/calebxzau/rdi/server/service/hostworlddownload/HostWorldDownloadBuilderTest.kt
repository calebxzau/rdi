package calebxzau.rdi.server.service.hostworlddownload

import calebxzau.rdi.anvilrw.remap.RawRegionFile
import calebxzau.rdi.common.model.HostWorldDownloadStage
import calebxzhou.rdi.common.archive.extractArchiveToDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostWorldDownloadBuilderTest {
    private val temp: Path = Files.createTempDirectory("host-world-download-builder")
    private val world = temp.resolve("world")

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun keepsOnlySyncChunksAndTheIncludedFiles() {
        HostWorldTestWorld.standard(world)
        val target = temp.resolve("save.tar.zst")
        val progress = mutableListOf<Triple<HostWorldDownloadStage, Int, Int>>()

        val result = HostWorldDownloadBuilder.build(world, target, temp.resolve("work")) { stage, done, total ->
            progress += Triple(stage, done, total)
        }.getOrThrow()

        assertEquals(3, result.chunkCount, "terrain of the 3 sync chunks")
        assertEquals(Files.size(target), result.bytes)
        assertEquals(sha1(target), result.sha1)
        assertFalse(Files.exists(temp.resolve("work")), "the work folder is removed")

        val out = temp.resolve("out")
        extractArchiveToDir(target.toFile(), out.toFile())
        assertEquals(
            listOf(
                "DIM-1/region/r.0.0.mca",
                "data/rdi_sync_chunks.dat",
                "data/sophisticatedbackpacks.dat",
                "entities/r.0.0.mca",
                "level.dat",
                "playerdata/00000000-0000-0000-0000-000000000001.dat",
                "region/r.0.0.mca",
                "serverconfig/ftbchunks-world.snbt",
            ),
            files(out),
        )
        assertEquals(listOf(0 to 0, 1 to 0), RawRegionFile.presentChunks(out.resolve("region/r.0.0.mca")).getOrThrow().sortedBy { it.first })
        assertEquals(listOf(0 to 0), RawRegionFile.presentChunks(out.resolve("entities/r.0.0.mca")).getOrThrow())
        assertEquals(listOf(0 to 0), RawRegionFile.presentChunks(out.resolve("DIM-1/region/r.0.0.mca")).getOrThrow())
        assertEquals(setOf(2), RawRegionFile.compressionTypes(out.resolve("region/r.0.0.mca")).getOrThrow().values.toSet(), "chunk compression is kept")

        assertEquals(Triple(HostWorldDownloadStage.SyncChunks, 3, 3), progress.last { it.first == HostWorldDownloadStage.SyncChunks })
        assertEquals(Triple(HostWorldDownloadStage.Compress, 1, 1), progress.last())
    }

    @Test
    fun hostWithoutSyncChunksStillGetsItsOtherData() {
        HostWorldTestWorld.file(world, "level.dat")
        HostWorldTestWorld.file(world, "playerdata/00000000-0000-0000-0000-000000000001.dat")
        HostWorldTestWorld.region(world.resolve("region/r.0.0.mca"), listOf(0 to 0))
        val target = temp.resolve("save.tar.zst")

        val result = HostWorldDownloadBuilder.build(world, target, temp.resolve("work")).getOrThrow()

        assertEquals(0, result.chunkCount)
        val out = temp.resolve("out")
        extractArchiveToDir(target.toFile(), out.toFile())
        assertEquals(listOf("level.dat", "playerdata/00000000-0000-0000-0000-000000000001.dat"), files(out))
    }

    @Test
    fun damagedSyncListFails() {
        HostWorldTestWorld.file(world, "level.dat")
        HostWorldTestWorld.file(world, "data/rdi_sync_chunks.dat", byteArrayOf(1, 2, 3))
        val result = HostWorldDownloadBuilder.build(world, temp.resolve("save.tar.zst"), temp.resolve("work"))
        assertTrue(result.isFailure)
        assertEquals("房间的同步区块列表无法读取", result.exceptionOrNull()?.message)
    }

    private fun files(root: Path): List<String> = Files.walk(root).use { stream ->
        stream.asSequence().filter { Files.isRegularFile(it) }.map { root.relativize(it).invariantSeparatorsPathString }.sorted().toList()
    }

    private fun sha1(file: Path): String =
        MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
