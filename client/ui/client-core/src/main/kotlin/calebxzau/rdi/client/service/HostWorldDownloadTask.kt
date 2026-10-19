package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.HostWorldDownloadProgressVo
import calebxzau.rdi.common.model.HostWorldDownloadStage
import calebxzhou.rdi.client.net.lgr
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * The background task of a host save download (下载房间存档): the server packs the save, the archive is
 * downloaded into the modpack's `saves` folder and extracted there as a new singleplayer save.
 * Progress: packing 0–40%, downloading 40–90%, extracting 90–100%.
 */
class HostWorldDownloadTask(
    private val api: HostWorldDownloadApi = RdiHostWorldDownloadApi,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Shanghai")) },
) {
    fun task(hostId: String, hostName: String, savesDir: Path): Task2 =
        Task2.Leaf("下载房间存档${hostName}") { context -> run(hostId, hostName, savesDir, context) }

    /** Returns the new save folder. */
    suspend fun run(hostId: String, hostName: String, savesDir: Path, context: Task2Context): Path = withContext(Dispatchers.IO) {
        context.emit(Task2Progress("正在请求打包存档", 0f))
        val started = api.start(hostId)
        context.ensureActive()
        val ready = api.awaitReady(hostId, started.buildId) { progress ->
            context.ensureActive()
            context.emit(
                Task2Progress(progressText(progress), PACK_SHARE * packFraction(progress), completedItems = progress.done, totalItems = progress.total)
            )
        }
        // A modpack that was never played in singleplayer has no saves folder yet.
        Files.createDirectories(savesDir)
        val archive = savesDir.resolve(".rdi-download-${ready.buildId}.tar.zst")
        try {
            api.download(hostId, ready, archive) { progress ->
                context.ensureActive()
                context.emit(
                    Task2Progress(
                        "正在下载存档",
                        PACK_SHARE + DOWNLOAD_SHARE * progress.fraction.coerceAtLeast(0f),
                        completedBytes = progress.bytesDownloaded,
                        totalBytes = progress.totalBytes,
                        bytesPerSecond = progress.speedBytesPerSecond,
                    )
                )
            }
            context.ensureActive()
            val extractStart = PACK_SHARE + DOWNLOAD_SHARE
            context.emit(Task2Progress("正在解压存档", extractStart))
            val saved = HostWorldSaveInstaller.install(archive, savesDir, hostName, today()) { done, total ->
                context.ensureActive()
                context.emit(Task2Progress("正在解压存档", extractStart + (1f - extractStart) * done / total.coerceAtLeast(1), completedItems = done, totalItems = total))
            }.getOrThrow()
            context.emit(Task2Progress("已保存为单人存档「${saved.fileName}」", 1f))
            saved
        } finally {
            // Also runs when the task is cancelled, so no archive is left in saves.
            withContext(NonCancellable) {
                runCatching { Files.deleteIfExists(archive) }.onFailure { exception ->
                    lgr.warn(exception) { "删除下载的存档包失败: ${archive}" }
                }
            }
        }
    }

    private companion object {
        const val PACK_SHARE = 0.4f
        const val DOWNLOAD_SHARE = 0.5f
    }
}

internal fun progressText(progress: HostWorldDownloadProgressVo): String = when (progress.stage) {
    HostWorldDownloadStage.SyncChunks -> "正在读取同步区块"
    HostWorldDownloadStage.Files -> "正在收集玩家和其他数据"
    HostWorldDownloadStage.Compress -> "正在压缩存档"
}

/** Share of the server's packing done: sync chunks first half, other files up to 90%, then compression. */
private fun packFraction(progress: HostWorldDownloadProgressVo): Float {
    val step = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
    return when (progress.stage) {
        HostWorldDownloadStage.SyncChunks -> 0.5f * step
        HostWorldDownloadStage.Files -> 0.5f + 0.4f * step
        HostWorldDownloadStage.Compress -> 0.9f + 0.1f * step
    }
}
