package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.HostWorldDownloadProgressVo
import calebxzau.rdi.common.model.HostWorldDownloadReadyVo
import calebxzau.rdi.common.model.HostWorldDownloadStartVo
import calebxzau.rdi.common.model.HostWorldDownloadStatusVo
import calebxzhou.rdi.client.net.accountAuthHeader
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.net.DownloadProgress
import calebxzhou.rdi.common.net.downloadFileFrom
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.serdesJson
import io.ktor.client.plugins.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Server calls of the host save download (`/host/{hostId}/world-download`); members only. */
interface HostWorldDownloadApi {
    suspend fun status(hostId: String): HostWorldDownloadStatusVo

    suspend fun start(hostId: String): HostWorldDownloadStartVo

    /** Follows the build until its archive is ready; throws the server's reason when it fails. */
    suspend fun awaitReady(hostId: String, buildId: String, onProgress: (HostWorldDownloadProgressVo) -> Unit): HostWorldDownloadReadyVo

    /** Downloads the archive to [target] and checks its SHA-1. */
    suspend fun download(hostId: String, ready: HostWorldDownloadReadyVo, target: Path, onProgress: (DownloadProgress) -> Unit)
}

object RdiHostWorldDownloadApi : HostWorldDownloadApi {
    override suspend fun status(hostId: String): HostWorldDownloadStatusVo {
        val response = server.makeRequest<HostWorldDownloadStatusVo>("host/${hostId}/world-download")
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
        return response.data ?: throw RequestError("服务器响应缺少存档下载信息")
    }

    override suspend fun start(hostId: String): HostWorldDownloadStartVo {
        val response = server.makeRequest<HostWorldDownloadStartVo>("host/${hostId}/world-download", HttpMethod.Post)
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
        return response.data ?: throw RequestError("服务器响应缺少打包信息")
    }

    override suspend fun awaitReady(
        hostId: String,
        buildId: String,
        onProgress: (HostWorldDownloadProgressVo) -> Unit,
    ): HostWorldDownloadReadyVo {
        var ready: HostWorldDownloadReadyVo? = null
        ktorClient.sse("${server.hqUrl}/host/${hostId}/world-download/${buildId}/events", {
            accountAuthHeader()
            timeout {
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 120_000
            }
        }) {
            ready = incoming.awaitReadyEvent(onProgress)
        }
        return ready ?: throw IOException("打包进度连接中断，请重试")
    }

    override suspend fun download(
        hostId: String,
        ready: HostWorldDownloadReadyVo,
        target: Path,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val token = loggedAccount.jwt ?: throw RequestError("请先登录")
        target.downloadFileFrom(
            url = "${server.hqUrl}/host/${hostId}/world-download/${ready.buildId}/file",
            headers = mapOf(HttpHeaders.Authorization to "Bearer ${token}"),
            knownSize = ready.bytes,
            validator = { file -> checkSha1(file, ready.sha1) },
            onProgress = onProgress,
        ).getOrThrow()
    }

    private suspend fun checkSha1(file: Path, expected: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw IOException("存档包校验失败，请重新下载")
        }
    }
}

/**
 * Reads build events until `ready` or `error`. The shared client's SSE plugin reconnects whenever the
 * server closes the stream, so the flow never ends by itself after the last event; stopping here is
 * what lets the download continue.
 */
internal suspend fun Flow<ServerSentEvent>.awaitReadyEvent(onProgress: (HostWorldDownloadProgressVo) -> Unit): HostWorldDownloadReadyVo {
    val last = transformWhile { event ->
        emit(event)
        event.event != "ready" && event.event != "error"
    }.onEach { event ->
        if (event.event == "progress") event.data?.let { onProgress(serdesJson.decodeFromString(it)) }
    }.lastOrNull() ?: throw IOException("打包进度连接中断，请重试")
    return when (last.event) {
        "ready" -> serdesJson.decodeFromString<HostWorldDownloadReadyVo>(last.data ?: throw IOException("打包结果为空"))
        "error" -> throw RequestError(last.data ?: "打包存档失败")
        else -> throw IOException("打包进度连接中断，请重试")
    }
}
