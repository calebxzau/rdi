package calebxzau.rdi.client.service

import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.model.Task2CancelledException
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.humanSpeed
import calebxzau.rdi.common.logging.Loggers
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

private val lgr by Loggers

data class ModpackChunkedUploadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val completedParts: Int,
    val totalParts: Int,
    val bytesPerSecond: Double,
)

class ModpackChunkedUploader(
    private val api: ModpackUploadApi,
    private val maxPartRetries: Int = DEFAULT_CHUNKED_UPLOAD_PART_RETRIES,
    private val retryDelayMillis: Long = DEFAULT_CHUNKED_UPLOAD_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = DEFAULT_CHUNKED_UPLOAD_MAX_RETRY_DELAY_MILLIS,
    private val parallelism: Int = DEFAULT_CHUNKED_UPLOAD_PARALLELISM,
) {
    init {
        require(maxPartRetries >= 0) { "分片重试次数不能为负数" }
        require(retryDelayMillis >= 0) { "分片重试间隔不能为负数" }
        require(maxRetryDelayMillis >= 0) { "分片最长重试间隔不能为负数" }
        require(parallelism in 1..DEFAULT_CHUNKED_UPLOAD_PARALLELISM) { "分片并发数必须在1到8之间" }
    }

    suspend fun upload(
        file: File,
        publish: suspend (UUID) -> Unit,
        ensureActive: () -> Unit = {},
        onProgress: (ModpackChunkedUploadProgress) -> Unit = {},
        onPublicationUncertain: (String) -> Unit = {},
    ) {
        require(file.isFile && file.length() > 0) { "上传文件不存在或为空" }
        val path = file.toPath()
        val totalBytes = file.length()
        val fileSha1 = sha1(path, ensureActive)
        val session = api.createSession(ModpackUploadSessionCreateDto(file.name, totalBytes, fileSha1))
        var publicationStarted = false
        try {
            ensureCurrentActive(ensureActive)
            val completedSession = if (session.ready) {
                onProgress(ModpackChunkedUploadProgress(totalBytes, totalBytes, session.partCount, session.partCount, 0.0))
                session
            } else {
                require(session.maxParallelParts > 0) { "服务器返回的分片并发数无效" }
                val startedAt = System.nanoTime()
                val acknowledgedBytes = session.uploadedParts.toSet().sumOf { session.partLength(it) }
                ModpackChunkedUploaderProgressEmitter(
                    totalBytes = totalBytes,
                    totalParts = session.partCount,
                    acknowledgedBytes = acknowledgedBytes,
                    startedAt = startedAt,
                    onProgress = onProgress,
                ).let { emitter ->
                    ChunkedUploader(
                        file = path,
                        descriptor = ChunkedUploadDescriptor(
                            size = session.size,
                            partSize = session.partSize,
                            partCount = session.partCount,
                            uploadedParts = session.uploadedParts,
                        ),
                        maxPartRetries = maxPartRetries,
                        retryDelayMillis = retryDelayMillis,
                        maxRetryDelayMillis = maxRetryDelayMillis,
                        parallelism = minOf(parallelism, session.maxParallelParts),
                        uploadPart = { index, bytes, sha1, onBytesSent ->
                            api.uploadPart(session.id, index, bytes, sha1, onBytesSent)
                        },
                        ensureActive = ensureActive,
                        onProgress = emitter::emit,
                    ).upload()
                    ensureCurrentActive(ensureActive)
                    api.completeSession(session.id).also {
                        if (!it.ready) throw ModpackError("服务器未确认整合包上传完成")
                    }
                }
            }
            ensureCurrentActive(ensureActive)
            publicationStarted = true
            try {
                publish(completedSession.id)
            } catch (cause: Throwable) {
                if (cause is CancellationException || cause is Task2CancelledException) {
                    onPublicationUncertain("发布结果尚未确认，请刷新整合包列表查看，勿重复提交")
                    throw cause
                }
                if (cause is IOException || cause is HttpRequestTimeoutException) {
                    throw ModpackError("发布结果尚未确认，请刷新整合包列表查看，勿重复提交", cause)
                }
                throw cause
            }
        } catch (cause: Throwable) {
            if (!publicationStarted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { withTimeout(10_000L) { api.cancelSession(session.id) } }
                        .onFailure { cleanupError -> lgr.error(cleanupError) { "清理整合包上传会话失败" } }
                }
            }
            throw cause
        }
    }

    private suspend fun ensureCurrentActive(ensureActive: () -> Unit) {
        currentCoroutineContext().ensureActive()
        ensureActive()
    }

    private suspend fun sha1(path: java.nio.file.Path, ensureActive: () -> Unit): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            HexFormat.of().formatHex(digest.digest())
        }

    private fun ModpackUploadSessionVo.partLength(index: Int): Long {
        val offset = index.toLong() * partSize
        return minOf(partSize.toLong(), size - offset)
    }

    private class ModpackChunkedUploaderProgressEmitter(
        private val totalBytes: Long,
        private val totalParts: Int,
        private val acknowledgedBytes: Long,
        private val startedAt: Long,
        private val onProgress: (ModpackChunkedUploadProgress) -> Unit,
    ) {
        fun emit(completedBytes: Long, completedParts: Int) {
            val elapsed = ((System.nanoTime() - startedAt) / 1_000_000_000.0).coerceAtLeast(0.001)
            val uploadedThisRun = (completedBytes - acknowledgedBytes).coerceAtLeast(0L)
            onProgress(
                ModpackChunkedUploadProgress(
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                    completedParts = completedParts,
                    totalParts = totalParts,
                    bytesPerSecond = uploadedThisRun / elapsed,
                ),
            )
        }
    }

    companion object {
        fun toTask2Progress(name: String, progress: ModpackChunkedUploadProgress): Task2Progress {
            val percent = if (progress.totalBytes <= 0) 100
            else ((progress.completedBytes * 100) / progress.totalBytes).toInt().coerceIn(0, 100)
            val message = buildString {
                appendLine("正在上传整合包 $name...")
                appendLine(
                    "进度：$percent% (${progress.completedBytes.humanFileSize}/${progress.totalBytes.humanFileSize})",
                )
                appendLine("速度：${progress.bytesPerSecond.humanSpeed}")
            }
            return Task2Progress(
                message = message,
                fraction = if (progress.totalBytes <= 0) 1f
                else progress.completedBytes.toFloat() / progress.totalBytes,
                completedBytes = progress.completedBytes,
                totalBytes = progress.totalBytes,
                bytesPerSecond = progress.bytesPerSecond,
                completedItems = progress.completedParts,
                totalItems = progress.totalParts,
            )
        }
    }
}
