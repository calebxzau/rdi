package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.HostWorldDownloadProgressVo
import calebxzau.rdi.common.model.HostWorldDownloadReadyVo
import calebxzau.rdi.common.model.HostWorldDownloadStage
import calebxzau.rdi.common.model.HostWorldDownloadStartVo
import calebxzau.rdi.common.model.HostWorldDownloadStatusVo
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.model.Task2CancelledException
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.net.DownloadProgress
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostWorldDownloadTaskTest {
    private val temp: Path = Files.createTempDirectory("host-world-download-task")
    private val saves = temp.resolve("saves")
    private val task = HostWorldDownloadTask(FakeApi(), today = { LocalDate.of(2026, 10, 13) })

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun packsDownloadsAndExtractsIntoSaves(): Unit = runBlocking {
        val progress = mutableListOf<Task2Progress>()

        val saved = task.run("host", "房间", saves, Task2Context { progress += it })

        assertEquals(saves.resolve("房间-2026-10-13"), saved)
        assertEquals("level", Files.readString(saved.resolve("level.dat")))
        assertEquals(listOf("房间-2026-10-13"), names(saves), "the archive is removed")
        val messages = progress.map { it.message }.distinct()
        assertEquals(listOf("正在请求打包存档", "正在读取同步区块", "正在压缩存档", "正在下载存档", "正在解压存档", "已保存为单人存档「房间-2026-10-13」"), messages)
        val fractions = progress.mapNotNull { it.fraction }
        assertEquals(fractions.sorted(), fractions, "progress never goes back")
        assertEquals(1f, fractions.last())
        assertEquals(1 to 2, progress.first { it.message == "正在读取同步区块" }.let { it.completedItems to it.totalItems })
    }

    @Test
    fun cancellingDuringTheDownloadLeavesNothingInSaves(): Unit = runBlocking {
        var cancelled = false
        val context = Task2Context(isCancelled = { cancelled }) { progress ->
            if (progress.message == "正在下载存档") cancelled = true
        }

        assertFailsWith<Task2CancelledException> { task.run("host", "房间", saves, context) }

        assertEquals(emptyList(), names(saves))
    }

    @Test
    fun cancellingDuringExtractionRemovesTheHalfExtractedSave(): Unit = runBlocking {
        var cancelled = false
        val context = Task2Context(isCancelled = { cancelled }) { progress ->
            if (progress.message == "正在解压存档" && progress.completedItems == 1) cancelled = true
        }

        assertFailsWith<Task2CancelledException> { task.run("host", "房间", saves, context) }

        assertEquals(emptyList(), names(saves))
    }

    private fun names(dir: Path): List<String> = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

    private class FakeApi : HostWorldDownloadApi {
        override suspend fun status(hostId: String) = HostWorldDownloadStatusVo(3, 3, emptyList(), reusable = false, blockedReason = null)

        override suspend fun start(hostId: String) = HostWorldDownloadStartVo("build", reused = false)

        override suspend fun awaitReady(hostId: String, buildId: String, onProgress: (HostWorldDownloadProgressVo) -> Unit): HostWorldDownloadReadyVo {
            onProgress(HostWorldDownloadProgressVo(HostWorldDownloadStage.SyncChunks, 1, 2))
            onProgress(HostWorldDownloadProgressVo(HostWorldDownloadStage.SyncChunks, 2, 2))
            onProgress(HostWorldDownloadProgressVo(HostWorldDownloadStage.Compress, 1, 1))
            return HostWorldDownloadReadyVo(buildId, bytes = 1, sha1 = "x", chunkCount = 2)
        }

        override suspend fun download(hostId: String, ready: HostWorldDownloadReadyVo, target: Path, onProgress: (DownloadProgress) -> Unit) {
            TarZstArchiveWriter(target.toFile()).use { writer ->
                writer.addFile("level.dat", "level".toByteArray())
                writer.addFile("region/r.0.0.mca", "chunks".toByteArray())
            }
            onProgress(DownloadProgress(Files.size(target), Files.size(target), 0.0))
        }
    }
}
