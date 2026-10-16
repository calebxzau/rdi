package calebxzhou.rdi.client.net

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.model.Response
import calebxzhou.rdi.common.model.ServerEntry
import calebxzhou.rdi.common.net.*
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.ModService
import io.ktor.client.call.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

private const val DEFAULT_GAME_NODE_ADDR = "rdi.calebxzhou.cn:65230"
private const val DEFAULT_PRIMARY_HOST = "rdi.calebxzhou.cn"

/**
 * Host of a debug master given by the launcher as -Drdi.server.url (rdi.sh passes RDI_DEBUG_URL);
 * null when it is absent or unusable, so debug builds keep talking to this machine.
 */
internal fun parseDebugServerHost(serverUrl: String?): String? {
    if (serverUrl.isNullOrBlank()) return null
    return runCatching { URI(serverUrl.trim()).host }
        .onFailure { lgr.warn(it) { "rdi.server.url无效，将使用本机调试服务器: $serverUrl" } }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

data class ServerRouteState(
    val useBackupNode: Boolean = false,
    val entryHost: String = DEFAULT_PRIMARY_HOST,
    val nodeName: String? = null,
    val gameAddr: String = DEFAULT_GAME_NODE_ADDR,
)

val server
    get() = RServer.now
var loggedAccount: RAccount
    get() = AccountSessionStore.current
    set(value) {
        AccountSessionStore.current = value
    }
val lgr by Loggers

class RServer(
    var ip: String,
    val httpPort: Int,
    val httpsPort: Int
) {
    var noHttps = System.getProperty("rdi.noHttps").toBoolean()

    val hqUrl get() = "${if (noHttps) "http" else "https"}://${ip}:${if (noHttps) httpPort else httpsPort}"

    companion object {
        init {
            ModService.rdiModDownloadUrlProvider = { mod ->
                "${server.hqUrl.trimEnd('/')}/mod/download/${mod.fileName.encodeURLPathPart()}"
            }
            ModService.rdiModDownloadHeadersProvider = { url ->
                val baseUrl = server.hqUrl.trimEnd('/')
                if (!url.startsWith("$baseUrl/")) {
                    emptyMap()
                } else {
                    loggedAccount.jwt?.takeIf(String::isNotBlank)
                        ?.let { mapOf(HttpHeaders.Authorization to "Bearer $it") }
                        ?: emptyMap()
                }
            }
        }

        val routeState = MutableStateFlow(ServerRouteState())

        val DEBUG_HOST: String? = parseDebugServerHost(System.getProperty("rdi.server.url"))

        val DBG = RServer(
            DEBUG_HOST ?: "127.0.0.1", 65231, 65331
        )
        val OFFICIAL_NNG = RServer(
            DEFAULT_PRIMARY_HOST, 65231, 65331
        )
        val OFFICIAL_NNG2 get() = RServer(routeState.value.entryHost, 443, 443)
        val now: RServer
            get() = if (DEBUG) DBG
            else if (routeState.value.useBackupNode) OFFICIAL_NNG2
            else OFFICIAL_NNG

        val currentGameAddr
            get() = routeState.value.gameAddr.ifBlank { DEFAULT_GAME_NODE_ADDR }

        fun updateServerEntry(entry: ServerEntry) {
            lgr.info { "update server entry: $entry" }
            routeState.value = routeState.value.copy(
                useBackupNode = entry.useBackupNode,
                entryHost = entry.api?.trim().orEmpty().ifBlank { DEFAULT_PRIMARY_HOST },
                nodeName = entry.nodeName.ifBlank { null },
                gameAddr = entry.gameAddr.trim().ifBlank { DEFAULT_GAME_NODE_ADDR }
            )
        }
    }

    suspend inline fun createRequest(
        path: String,
        method: HttpMethod,
        params: Map<String, Any> = mapOf(),
        crossinline builder: HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        return try {
            httpRequest {
                url("$hqUrl/${path}")
                this.method = method
                if (method != HttpMethod.Get && params.isNotEmpty()) {
                    json()
                    setBody(
                        serdesJson.encodeToString(
                            MapSerializer(String.serializer(), JsonElement.serializer()),
                            params.mapValues {
                                JsonPrimitive(it.value.toString())
                            }
                        ))
                    compress("deflate")
                } else if (method == HttpMethod.Get && params.isNotEmpty()) {
                    params.forEach {
                        parameter(it.key, it.value)
                    }
                }
                accountAuthHeader()
                builder()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lgr.warn(e) { "request failed: $method $path\n" }
            throw RequestError("无法连接服务器 请检查网络连接")
        }
    }

    suspend inline fun <reified T> makeRequest(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        params: Map<String, Any> = mapOf(),
        crossinline builder: HttpRequestBuilder.() -> Unit = {}
    ): Response<T> {
        return createRequest(path, method, params, builder).rdiResponse()
    }


    suspend fun download(
        path: String,
        saveTo: String,
        validator: suspend (Path) -> Result<Unit> = { Result.success(Unit) },
        onProgress: (DownloadProgress) -> Unit
    ) {
        Paths.get(saveTo).downloadFileFrom(
            "${hqUrl}/${path}",
            headers = mapOf(HttpHeaders.Authorization to "Bearer ${loggedAccount.jwt}"),
            validator = validator,
            onProgress = onProgress,
        ).getOrThrow()
    }
}

suspend inline fun <reified T> HttpResponse.rdiResponse(): Response<T> {
    if (status.value !in 200..299) {
        val responseText = bodyAsText()
        runCatching {
            serdesJson.decodeFromString<Response<JsonElement>>(responseText)
        }.getOrNull()?.msg?.takeIf(String::isNotBlank)?.let {
            throw RequestError(it)
        }
        throw RequestError("服务器请求失败: ${status.value} ${status.description}")
    }
    val responseContentType = contentType()
    if (responseContentType == null || !responseContentType.match(ContentType.Application.Json)) {
        bodyAsText()
        throw RequestError("服务器响应格式错误: ${responseContentType ?: "无Content-Type"}")
    }
    return body()
}

fun HttpRequestBuilder.accountAuthHeader() {
    loggedAccount.jwt?.let { jwt ->
        header(HttpHeaders.Authorization, "Bearer $jwt")
    }
}

fun CoroutineScope.sse(
    path: String,
    params: Map<String, Any?> = emptyMap(),
    bufferPolicy: SSEBufferPolicy? = null,
    configureRequest: HttpRequestBuilder.() -> Unit = {},
    onError: (Throwable) -> Unit = { throwable ->
        lgr.error { throwable }
    },
    onClosed: suspend () -> Unit = {},
    onEvent: suspend (ServerSentEvent) -> Unit,
) = this.launch {
    val urlString = "${server.hqUrl}/${path.trimStart('/')}"

    if (DEBUG) {
        lgr.info { "[SSE] Connecting to: $urlString" }
    }

    try {
        ktorClient.sse(urlString, {
            accountAuthHeader()
            timeout {
                requestTimeoutMillis = 60000
                socketTimeoutMillis = 60000
            }
            params.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Iterable<*> -> value.forEach { element -> element?.let { parameter(key, it) } }
                    is Array<*> -> value.forEach { element -> element?.let { parameter(key, it) } }
                    else -> parameter(key, value)
                }
            }
            bufferPolicy?.let { bufferPolicy(it) }
            configureRequest()
        }) {
            if (DEBUG) {
                lgr.info { "[SSE] Connected successfully" }
            }
            try {
                incoming.collect { event ->
                    when (event.event) {
                        "heartbeat" -> {}
                        "error" -> onError(RequestError(event.data))
                        else -> onEvent(event)
                    }
                }
            } finally {
                if (DEBUG) {
                    lgr.info { "[SSE] Connection closed" }
                }
                onClosed()
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        lgr.error { "[SSE] Connection failed" + "\n" + t }
        onError(t)
        return@launch
    }
}

inline fun CoroutineScope.rdiRequestU(
    path: String,
    method: HttpMethod = HttpMethod.Post,
    params: Map<String, Any> = mapOf(),
    body: String? = null,
    crossinline onDone: () -> Unit = {},
    crossinline onErr: (Throwable) -> Unit,
    crossinline onOk: (Response<Unit>) -> Unit,
) = rdiRequest<Unit>(path, method, params, body, onDone, onErr, onOk)


inline fun <reified T> CoroutineScope.rdiRequest(
    path: String,
    method: HttpMethod = HttpMethod.Get,
    params: Map<String, Any> = mapOf(),
    body: String? = null,
    crossinline onDone: () -> Unit = {},
    crossinline onErr: (Throwable) -> Unit,
    crossinline onOk: (Response<T>) -> Unit,
) = this.launch {
    try {
        val req = withContext(Dispatchers.IO) {
            server.makeRequest<T>(path, method, params) {
                body?.let {
                    json()
                    setBody(it)
                }
            }
        }
        if (req.ok) {
            onOk(req)
        } else {
            throw RequestError(req.msg)
        }
    } catch (cancel: CancellationException) {
        return@launch
    } catch (t: Throwable) {
        onErr(t)
    } finally {
        onDone()
    }
}
