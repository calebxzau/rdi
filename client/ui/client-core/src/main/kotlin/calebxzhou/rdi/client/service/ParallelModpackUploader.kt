package calebxzhou.rdi.client.service

/*
 * 客户端暂时使用旧multipartAPI，保留分片上传实现以便后续重新启用。
 *
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.client.net.accountAuthHeader
import calebxzhou.rdi.client.net.rdiResponse
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.net.DynamicProxySelector
import calebxzhou.rdi.common.serdesJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.Protocol
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class ModpackUploadProgress(
    val uploadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Double
)

internal class ParallelModpackUploader(
    private val client: HttpClient = createUploadClient(),
    private val baseUrl: () -> String = { server.hqUrl }
) {
    suspend fun upload(
        file: File,
        onProgress: (ModpackUploadProgress) -> Unit
    ): Result<UUID> = resultOf {
        require(file.isFile && file.length() > 0) { "上传文件不存在或为空" }
        val fileSha1 = withContext(Dispatchers.IO) { file.sha1.lowercase() }
        val session = createSession(file, fileSha1)
        if (!session.ready) {
            uploadMissingParts(file, session, onProgress)
            completeSession(session.id)
        }
        session.id
    }

    private suspend fun createSession(file: File, sha1: String): ModpackUploadSessionVo =
        request(
            path = "modpack/upload-sessions",
            method = HttpMethod.Post,
            body = TextContent(
                serdesJson.encodeToString(ModpackUploadSessionCreateDto(file.name, file.length(), sha1)),
                ContentType.Application.Json
            )
        )

    private suspend fun completeSession(id: UUID): ModpackUploadSessionVo = request(
        path = "modpack/upload-sessions/$id/complete",
        method = HttpMethod.Post
    )

    private suspend fun uploadMissingParts(
        file: File,
        session: ModpackUploadSessionVo,
        onProgress: (ModpackUploadProgress) -> Unit
    ) = coroutineScope {
        val missingParts = (0 until session.partCount).filterNot(session.uploadedParts.toSet()::contains)
        if (missingParts.isEmpty()) return@coroutineScope
        val tracker = UploadProgressTracker(
            totalBytes = session.size,
            confirmedBytes = session.uploadedParts.sumOf { session.partLength(it).toLong() },
            onProgress = onProgress
        )
        val queue = Channel<Int>(Channel.UNLIMITED)
        missingParts.forEach { queue.trySend(it).getOrThrow() }
        queue.close()
        repeat(minOf(CONNECTION_COUNT, missingParts.size)) {
            launch {
                for (index in queue) {
                    val offset = index.toLong() * session.partSize
                    val length = session.partLength(index)
                    val partSha1 = hashRange(file, offset, length)
                    uploadPartWithRetry(file, session.id, index, offset, length, partSha1, tracker)
                }
            }
        }
    }

    private suspend fun uploadPartWithRetry(
        file: File,
        sessionId: UUID,
        index: Int,
        offset: Long,
        length: Int,
        sha1: String,
        tracker: UploadProgressTracker
    ) {
        var lastFailure: Throwable? = null
        repeat(PART_RETRY_COUNT) { attempt ->
            tracker.start(index)
            try {
                val content = FileRangeContent(file, offset, length.toLong()) { uploaded ->
                    tracker.update(index, uploaded)
                }
                request<Unit>(
                    path = "modpack/upload-sessions/$sessionId/parts/$index",
                    method = HttpMethod.Put,
                    body = content,
                    headers = mapOf(PART_SHA1_HEADER to sha1)
                )
                tracker.complete(index, length.toLong())
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                tracker.fail(index)
                lastFailure = error
                if (attempt + 1 < PART_RETRY_COUNT) delay(RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        throw lastFailure ?: RequestError("分片上传失败")
    }

    private suspend fun hashRange(file: File, offset: Long, length: Int): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(BUFFER_SIZE)
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(read > 0) { "读取上传分片失败" }
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        HexFormat.of().formatHex(digest.digest())
    }

    private suspend inline fun <reified T> request(
        path: String,
        method: HttpMethod,
        body: OutgoingContent? = null,
        headers: Map<String, String> = emptyMap()
    ): T {
        val response = client.request {
            url("${baseUrl().trimEnd('/')}/${path.trimStart('/')}")
            this.method = method
            accountAuthHeader()
            headers.forEach { (name, value) -> header(name, value) }
            timeout {
                requestTimeoutMillis = UPLOAD_TIMEOUT_MILLIS
                socketTimeoutMillis = UPLOAD_TIMEOUT_MILLIS
            }
            body?.let { setBody(it) }
        }.rdiResponse<T>()
        if (!response.ok) throw RequestError(response.msg)
        return response.data ?: if (T::class == Unit::class) Unit as T
        else throw RequestError("服务器未返回上传数据")
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private class FileRangeContent(
        private val file: File,
        private val offset: Long,
        override val contentLength: Long,
        private val onProgress: (Long) -> Unit
    ) : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) = withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                var remaining = contentLength
                var uploaded = 0L
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "读取上传分片失败" }
                    channel.writeFully(buffer, 0, read)
                    uploaded += read
                    remaining -= read
                    onProgress(uploaded)
                }
            }
        }
    }

    private class UploadProgressTracker(
        private val totalBytes: Long,
        confirmedBytes: Long,
        private val onProgress: (ModpackUploadProgress) -> Unit
    ) {
        private val startedAt = System.nanoTime()
        private val initialConfirmed = confirmedBytes
        private var confirmed = confirmedBytes
        private val active = mutableMapOf<Int, Long>()
        private var lastEmitAt = 0L

        @Synchronized
        fun start(index: Int) {
            active[index] = 0
            emit(force = false)
        }

        @Synchronized
        fun update(index: Int, uploaded: Long) {
            active[index] = uploaded
            emit(force = false)
        }

        @Synchronized
        fun complete(index: Int, length: Long) {
            active.remove(index)
            confirmed += length
            emit(force = confirmed >= totalBytes)
        }

        @Synchronized
        fun fail(index: Int) {
            active.remove(index)
            emit(force = true)
        }

        private fun emit(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastEmitAt < PROGRESS_INTERVAL_NANOS) return
            lastEmitAt = now
            val uploaded = (confirmed + active.values.sum()).coerceAtMost(totalBytes)
            val seconds = (now - startedAt) / 1_000_000_000.0
            val uploadedThisRun = (uploaded - initialConfirmed).coerceAtLeast(0)
            onProgress(
                ModpackUploadProgress(
                    uploaded,
                    totalBytes,
                    uploadedThisRun / seconds.coerceAtLeast(0.001)
                )
            )
        }
    }

    private fun ModpackUploadSessionVo.partLength(index: Int): Int {
        val offset = index.toLong() * partSize
        return minOf(partSize.toLong(), size - offset).toInt()
    }

    companion object {
        private const val CONNECTION_COUNT = 8
        private const val PART_RETRY_COUNT = 3
        private const val RETRY_DELAY_MILLIS = 500L
        private const val BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 75_000_000L
        private const val UPLOAD_TIMEOUT_MILLIS = 60 * 60 * 1000L
        private const val PART_SHA1_HEADER = "X-Part-SHA1"

        private fun createUploadClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    protocols(listOf(Protocol.HTTP_1_1))
                    dispatcher(Dispatcher().apply {
                        maxRequests = CONNECTION_COUNT
                        maxRequestsPerHost = CONNECTION_COUNT
                    })
                    followRedirects(true)
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.SECONDS)
                    writeTimeout(0, TimeUnit.SECONDS)
                    proxySelector(DynamicProxySelector())
                }
            }
            BrowserUserAgent()
            install(ContentNegotiation) {
                json(serdesJson)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = UPLOAD_TIMEOUT_MILLIS
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = UPLOAD_TIMEOUT_MILLIS
            }
        }
    }
}
*/
