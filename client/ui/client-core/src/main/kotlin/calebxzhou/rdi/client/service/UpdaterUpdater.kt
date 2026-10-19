package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.net.downloadFileFrom
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class UpdaterUpdateResult {
    UP_TO_DATE,
    UPDATED,
    SKIPPED_LOG_MODE
}

object UpdaterUpdater {
    suspend fun update(
        onStatus: (String) -> Unit,
        onDetail: (String) -> Unit
    ): Result<UpdaterUpdateResult> = runCatching {
        if (System.getProperty("rdi.updater.islogmode").toBoolean()) {
            return@runCatching UpdaterUpdateResult.SKIPPED_LOG_MODE
        }

        val expectedSha1 = server.makeRequest<String>("update/updater/hash").data
            ?.trim()
            ?.takeIf { it.matches(Regex("^[0-9a-fA-F]{40}$")) }
            ?: throw RequestError("获取启动程序版本信息失败")
        val targetFile = File("start.exe").absoluteFile
        if (targetFile.exists() && targetFile.sha1.equals(expectedSha1, true)) {
            return@runCatching UpdaterUpdateResult.UP_TO_DATE
        }

        onStatus("准备更新启动程序...")
        val compressedFile = targetFile.parentFile.resolve("start.exe.downloading.zst")
        val pendingFile = targetFile.parentFile.resolve("start.exe.pending")
        try {
            compressedFile.toPath().downloadFileFrom("${server.hqUrl}/update/updater") {
                onDetail(it.detailText("start.exe"))
            }.getOrThrow()

            withContext(Dispatchers.IO) {
                ZstdInputStream(compressedFile.inputStream().buffered()).use { input ->
                    pendingFile.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                validatePendingFile(pendingFile, expectedSha1)
                if (!waitForUpdaterExit()) throw IllegalStateException("等待旧启动程序退出超时")
                replaceUpdaterExecutable(targetFile, pendingFile)
            }
            UpdaterUpdateResult.UPDATED
        } finally {
            compressedFile.delete()
            pendingFile.delete()
        }
    }

    internal fun validatePendingFile(file: File, expectedSha1: String) {
        if (!file.sha1.equals(expectedSha1, true)) {
            throw IllegalStateException("启动程序文件校验失败")
        }
    }

    internal fun replaceUpdaterExecutable(targetFile: File, pendingFile: File) {
        val backupFile = targetFile.parentFile.resolve("start.exe.replacing-backup")
        val hadTarget = targetFile.exists()
        if (hadTarget) {
            Files.move(
                targetFile.toPath(),
                backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }
        try {
            Files.move(
                pendingFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (exception: Exception) {
            if (hadTarget) {
                Files.move(
                    backupFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }
            throw exception
        }
        if (hadTarget) Files.deleteIfExists(backupFile.toPath())
    }

    private fun waitForUpdaterExit(): Boolean {
        val updaterProcess = System.getProperty("rdi.updater.pid")
            ?.toLongOrNull()
            ?.let { ProcessHandle.of(it).orElse(null) }
            ?: return true
        if (!updaterProcess.isAlive) return true
        return try {
            updaterProcess.onExit().get(15, TimeUnit.SECONDS)
            true
        } catch (_: TimeoutException) {
            false
        }
    }
}
