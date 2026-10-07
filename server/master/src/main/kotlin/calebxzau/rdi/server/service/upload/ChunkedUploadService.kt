package calebxzau.rdi.server.service.upload

import calebxzhou.rdi.common.util.digestHex
import calebxzhou.rdi.common.util.sha1dig
import calebxzhou.rdi.common.exception.ChunkedUploadErrorCodes
import calebxzhou.rdi.common.exception.RequestError
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Format-agnostic byte receiving and assembly for disk-backed chunked uploads.
 *
 * [idleTimeoutMillis] bounds how long [receive] waits for the next bytes, so a part
 * whose connection silently died is released instead of being held indefinitely.
 */
class ChunkedUploadService(
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
) {
    init {
        require(idleTimeoutMillis > 0) { "分片接收空闲超时必须大于0" }
    }

    suspend fun receive(
        source: ByteReadChannel,
        destination: File,
        expectedLength: Long,
        expectedSha1: String,
    ) = withContext(Dispatchers.IO) {
        try {
            val digest = sha1dig()
            val buffer = ByteArray(BUFFER_SIZE)
            var received = 0L
            destination.outputStream().use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = withTimeoutOrNull(idleTimeoutMillis) {
                        source.readAvailable(buffer, 0, buffer.size)
                    } ?: throw RequestError("分片数据接收超时", errorCode = ChunkedUploadErrorCodes.PART_RETRYABLE)
                    if (read == -1) break
                    if (read == 0) continue
                    received += read
                    checkUpload(received <= expectedLength, "分片数据过长")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            checkUpload(received == expectedLength, "分片数据不完整", ChunkedUploadErrorCodes.PART_RETRYABLE)
            checkUpload(digest.digestHex() == expectedSha1, "分片SHA-1校验失败", ChunkedUploadErrorCodes.PART_RETRYABLE)
        } catch (error: Throwable) {
            deleteQuietly(destination, error)
            throw error
        }
    }

    suspend fun assemble(
        parts: List<File>,
        expectedSize: Long,
        expectedSha1: String,
        destination: File,
    ) = withContext(Dispatchers.IO) {
        try {
            val digest = sha1dig()
            val buffer = ByteArray(BUFFER_SIZE)
            var copied = 0L
            destination.outputStream().use { output ->
                for (part in parts) {
                    coroutineContext.ensureActive()
                    part.inputStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            copied += read
                            checkUpload(copied <= expectedSize, "上传文件过大")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            checkUpload(copied == expectedSize, "上传文件大小不正确")
            checkUpload(digest.digestHex() == expectedSha1, "完整文件SHA-1校验失败")
        } catch (error: Throwable) {
            deleteQuietly(destination, error)
            throw error
        }
    }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 120_000L
    }
}

private fun checkUpload(condition: Boolean, message: String, errorCode: String? = null) {
    if (!condition) throw RequestError(message, errorCode = errorCode)
}

private fun deleteQuietly(file: File, failure: Throwable) {
    if (file.exists() && !file.delete()) {
        failure.addSuppressed(IllegalStateException("无法删除临时上传文件: ${file.absolutePath}"))
    }
}
