package calebxzau.rdi.client.service

import calebxzhou.rdi.common.exception.ChunkedUploadErrorCodes
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Task2CancelledException
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

const val MAX_CHUNKED_UPLOAD_PART_SIZE = 4 * 1024 * 1024
const val DEFAULT_CHUNKED_UPLOAD_PARALLELISM = 8
const val DEFAULT_CHUNKED_UPLOAD_PART_RETRIES = 6
const val DEFAULT_CHUNKED_UPLOAD_RETRY_DELAY_MILLIS = 1_000L
const val DEFAULT_CHUNKED_UPLOAD_MAX_RETRY_DELAY_MILLIS = 30_000L

/** A part request fails once no bytes move for this long, rather than after a fixed total duration. */
const val CHUNKED_UPLOAD_PART_IDLE_TIMEOUT_MILLIS = 60_000L

private const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 200L

/**
 * Sends one part to the server. [onBytesSent] receives the total bytes of this attempt
 * written to the connection so far.
 */
typealias ChunkedUploadPart = suspend (index: Int, bytes: ByteArray, sha1: String, onBytesSent: (Long) -> Unit) -> Unit

/**
 * Retries transport failures and server errors marked [ChunkedUploadErrorCodes.PART_RETRYABLE];
 * other server rejections and cancellation are final.
 */
fun isRetryableChunkedUploadError(cause: Throwable): Boolean = when (cause) {
    is CancellationException, is Task2CancelledException -> false
    is RequestError -> cause.errorCode == ChunkedUploadErrorCodes.PART_RETRYABLE
    else -> true
}

data class ChunkedUploadDescriptor(
    val size: Long,
    val partSize: Int,
    val partCount: Int,
    val uploadedParts: List<Int> = emptyList(),
)

/**
 * Uploads the missing parts of a local file with bounded parallelism.
 *
 * The uploader owns only local file reading, retrying, and progress accounting.
 * The caller supplies the remote part operation and can keep its domain-specific
 * task presentation and cancellation checks outside this reusable component.
 *
 * [onProgress] reports acknowledged bytes plus bytes already sent by in-flight
 * attempts, throttled to [progressIntervalMillis] except when a part completes.
 */
class ChunkedUploader(
    private val file: Path,
    private val descriptor: ChunkedUploadDescriptor,
    private val maxPartRetries: Int = DEFAULT_CHUNKED_UPLOAD_PART_RETRIES,
    private val retryDelayMillis: Long = DEFAULT_CHUNKED_UPLOAD_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = DEFAULT_CHUNKED_UPLOAD_MAX_RETRY_DELAY_MILLIS,
    parallelism: Int = DEFAULT_CHUNKED_UPLOAD_PARALLELISM,
    private val progressIntervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
    private val uploadPart: ChunkedUploadPart,
    private val ensureActive: () -> Unit = {},
    private val onProgress: (uploadedBytes: Long, completedParts: Int) -> Unit = { _, _ -> },
    private val isRetryable: (Throwable) -> Boolean = ::isRetryableChunkedUploadError,
) {
    private val parallelism = parallelism.also {
        require(it in 1..DEFAULT_CHUNKED_UPLOAD_PARALLELISM) { "分片并发数必须在1到8之间" }
    }

    init {
        require(maxPartRetries >= 0) { "分片重试次数不能为负数" }
        require(retryDelayMillis >= 0) { "分片重试间隔不能为负数" }
        require(maxRetryDelayMillis >= 0) { "分片最长重试间隔不能为负数" }
        require(progressIntervalMillis >= 0) { "上传进度间隔不能为负数" }
    }

    suspend fun upload() {
        validateDescriptor()
        val acknowledged = descriptor.uploadedParts.toSet()
        val missing = (0 until descriptor.partCount).filterNot(acknowledged::contains)
        val progress = UploadProgress(acknowledged.sumOf(::partLength), acknowledged.size)

        progress.start()
        if (missing.isEmpty()) return

        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            coroutineScope {
                val nextPart = AtomicInteger(0)
                val workerCount = minOf(parallelism, missing.size)
                val workers = buildList {
                    repeat(workerCount) {
                        add(async {
                            while (true) {
                                ensureCurrentCoroutineActive()
                                val position = nextPart.getAndIncrement()
                                if (position >= missing.size) return@async
                                val index = missing[position]
                                val length = partLength(index)
                                val bytes = readPart(channel, index, length)
                                val partSha1 = sha1(bytes)
                                uploadWithRetry(index, length, bytes, partSha1, progress)
                            }
                        })
                    }
                }
                workers.awaitAll()
            }
        }
    }

    private fun validateDescriptor() {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            "分片上传文件无效"
        }
        val actualSize = Files.size(file)
        require(descriptor.size > 0 && descriptor.size == actualSize) {
            "服务器返回的上传文件大小不匹配"
        }
        require(descriptor.partSize in 1..MAX_CHUNKED_UPLOAD_PART_SIZE && descriptor.partCount > 0) {
            "服务器返回的上传分片信息无效"
        }
        val expectedPartCount = ((descriptor.size - 1) / descriptor.partSize + 1).toInt()
        require(descriptor.partCount == expectedPartCount) { "服务器返回的分片数量不匹配" }
        require(descriptor.uploadedParts.all { it in 0 until descriptor.partCount }) {
            "服务器返回的已上传分片序号无效"
        }
    }

    private fun partLength(index: Int): Long {
        val offset = index.toLong() * descriptor.partSize
        val length = minOf(descriptor.partSize.toLong(), descriptor.size - offset)
        require(length in 1..Int.MAX_VALUE) { "服务器返回的分片范围无效" }
        return length
    }

    private suspend fun readPart(channel: FileChannel, index: Int, length: Long): ByteArray {
        ensureCurrentCoroutineActive()
        val bytes = ByteArray(length.toInt())
        val buffer = ByteBuffer.wrap(bytes)
        val offset = index.toLong() * descriptor.partSize
        while (buffer.hasRemaining()) {
            ensureCurrentCoroutineActive()
            val count = channel.read(buffer, offset + buffer.position())
            require(count > 0) { "读取压缩包分片失败" }
        }
        return bytes
    }

    private suspend fun uploadWithRetry(
        index: Int,
        length: Long,
        bytes: ByteArray,
        partSha1: String,
        progress: UploadProgress,
    ) {
        var retries = 0
        var nextDelay = minOf(retryDelayMillis, maxRetryDelayMillis)
        while (true) {
            ensureCurrentCoroutineActive()
            val attempt = progress.begin(index, length)
            val failure = try {
                uploadPart(index, bytes, partSha1) { sent -> progress.sent(index, attempt, sent) }
                null
            } catch (cause: Throwable) {
                cause
            }
            if (failure == null) {
                progress.complete(index, attempt)
                return
            }
            progress.fail(index, attempt)
            if (!isRetryable(failure) || retries >= maxPartRetries) throw failure
            retries++
            ensureCurrentCoroutineActive()
            delay(nextDelay)
            nextDelay = if (nextDelay > maxRetryDelayMillis / 2) maxRetryDelayMillis else nextDelay * 2
        }
    }

    private suspend fun ensureCurrentCoroutineActive() {
        currentCoroutineContext().ensureActive()
        ensureActive()
    }

    private fun sha1(bytes: ByteArray): String =
        bytes.sha1

    private class PartAttempt(val length: Long) {
        var sentBytes = 0L
    }

    /**
     * Thread-safe byte accounting. Each attempt is tracked by identity so a progress
     * callback that arrives after its attempt finished or failed is ignored.
     */
    private inner class UploadProgress(acknowledgedBytes: Long, acknowledgedParts: Int) {
        private val lock = Any()
        private val intervalNanos = progressIntervalMillis * 1_000_000
        private val attempts = HashMap<Int, PartAttempt>()
        private var completedBytes = acknowledgedBytes
        private var completedParts = acknowledgedParts
        private var inFlightBytes = 0L
        private var lastEmitAt = 0L

        fun start() = synchronized(lock) { emit() }

        fun begin(index: Int, length: Long): PartAttempt = synchronized(lock) {
            discard(index)
            PartAttempt(length).also { attempts[index] = it }
        }

        fun sent(index: Int, attempt: PartAttempt, bytes: Long) {
            synchronized(lock) {
                if (attempts[index] !== attempt) return
                val sentBytes = bytes.coerceIn(0L, attempt.length)
                inFlightBytes += sentBytes - attempt.sentBytes
                attempt.sentBytes = sentBytes
                if (System.nanoTime() - lastEmitAt >= intervalNanos) emit()
            }
        }

        fun fail(index: Int, attempt: PartAttempt) {
            synchronized(lock) {
                if (attempts[index] !== attempt) return
                val hadSentBytes = attempt.sentBytes > 0
                discard(index)
                if (hadSentBytes) emit()
            }
        }

        fun complete(index: Int, attempt: PartAttempt) = synchronized(lock) {
            if (attempts[index] === attempt) discard(index)
            completedBytes += attempt.length
            completedParts++
            emit()
        }

        private fun discard(index: Int) {
            attempts.remove(index)?.let { inFlightBytes -= it.sentBytes }
        }

        private fun emit() {
            lastEmitAt = System.nanoTime()
            onProgress((completedBytes + inFlightBytes).coerceAtMost(descriptor.size), completedParts)
        }
    }
}
