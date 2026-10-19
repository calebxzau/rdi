package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.HostWorldImportCreateDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckVo
import calebxzau.rdi.common.model.HostWorldImportSessionVo
import calebxzau.rdi.common.model.SavePlayerMsidMatchVo
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.rdiResponse
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.net.ktorUploadClient
import calebxzhou.rdi.common.serdesJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import org.bson.types.ObjectId
import java.io.IOException
import java.util.UUID

/** Server calls of the singleplayer save import (`/host/{hostId}/world-import`, player lookups). */
interface HostSaveImportApi {
    suspend fun precheck(hostId: ObjectId, dto: HostWorldImportPrecheckDto): HostWorldImportPrecheckVo
    suspend fun create(hostId: ObjectId, dto: HostWorldImportCreateDto): HostWorldImportSessionVo
    suspend fun status(hostId: ObjectId, importId: UUID): HostWorldImportSessionVo
    suspend fun uploadPart(
        hostId: ObjectId,
        importId: UUID,
        index: Int,
        bytes: ByteArray,
        sha1: String,
        onBytesSent: (Long) -> Unit = {},
    )
    suspend fun complete(hostId: ObjectId, importId: UUID): HostWorldImportSessionVo
    suspend fun cancel(hostId: ObjectId, importId: UUID)

    /** The account bound to [qq], or a `RequestError("无此账号")`. */
    suspend fun findByQq(qq: String): RAccount.Dto

    /** Accounts whose bound Microsoft profile is one of [uuids]. */
    suspend fun msidMatch(uuids: List<UUID>): List<SavePlayerMsidMatchVo>

    /** Which of [ids] are existing accounts. */
    suspend fun existingAccounts(ids: List<ObjectId>): Set<ObjectId>
}

/** [partHttpClient] sends part bodies only; see [ktorUploadClient]. */
fun currentHostSaveImportApi(
    httpClient: HttpClient = ktorClient,
    partHttpClient: HttpClient = ktorUploadClient,
): HostSaveImportApi {
    val token = loggedAccount.jwt?.trim()?.takeIf(String::isNotEmpty) ?: error("必须登录后才能导入存档")
    return HttpHostSaveImportApi(httpClient, partHttpClient, server.hqUrl.trimEnd('/'), token)
}

private class HttpHostSaveImportApi(
    private val httpClient: HttpClient,
    private val partHttpClient: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : HostSaveImportApi {
    override suspend fun precheck(hostId: ObjectId, dto: HostWorldImportPrecheckDto): HostWorldImportPrecheckVo =
        request(HttpMethod.Post, "/host/${hostId}/world-import/precheck", serdesJson.encodeToString(dto))

    override suspend fun create(hostId: ObjectId, dto: HostWorldImportCreateDto): HostWorldImportSessionVo =
        request(HttpMethod.Post, "/host/${hostId}/world-import", serdesJson.encodeToString(dto))

    override suspend fun status(hostId: ObjectId, importId: UUID): HostWorldImportSessionVo =
        request(HttpMethod.Get, "/host/${hostId}/world-import/${importId}")

    override suspend fun uploadPart(
        hostId: ObjectId,
        importId: UUID,
        index: Int,
        bytes: ByteArray,
        sha1: String,
        onBytesSent: (Long) -> Unit,
    ) {
        request<Unit>(HttpMethod.Put, "/host/${hostId}/world-import/${importId}/parts/${index}", client = partHttpClient) {
            header("X-Part-SHA1", sha1)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
            onUpload { sent, _ -> onBytesSent(sent) }
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = CHUNKED_UPLOAD_PART_IDLE_TIMEOUT_MILLIS
            }
        }
    }

    override suspend fun complete(hostId: ObjectId, importId: UUID): HostWorldImportSessionVo =
        request(HttpMethod.Post, "/host/${hostId}/world-import/${importId}/complete")

    override suspend fun cancel(hostId: ObjectId, importId: UUID) {
        request<Unit>(HttpMethod.Delete, "/host/${hostId}/world-import/${importId}")
    }

    override suspend fun findByQq(qq: String): RAccount.Dto =
        request(HttpMethod.Get, "/player/by-qq/${qq.encodeURLParameter()}")

    override suspend fun msidMatch(uuids: List<UUID>): List<SavePlayerMsidMatchVo> =
        if (uuids.isEmpty()) emptyList() else request(HttpMethod.Post, "/player/msid-match", serdesJson.encodeToString(uuids.map { it.toString() }))

    override suspend fun existingAccounts(ids: List<ObjectId>): Set<ObjectId> {
        if (ids.isEmpty()) return emptySet()
        val infos = request<List<RAccount.Dto>>(HttpMethod.Get, "/player/infos?ids=${ids.joinToString("\n") { it.toHexString() }.encodeURLParameter()}")
        return infos.map { it.id }.toSet().intersect(ids.toSet())
    }

    private suspend inline fun <reified T> request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        client: HttpClient = httpClient,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = client.request("${baseUrl}${path}") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${token}")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            configure()
        }
        if (response.status.value >= 500) {
            val status = response.status.value
            response.bodyAsText()
            throw IOException("服务器暂时无法处理存档导入:HTTP${status}")
        }
        if (T::class == Unit::class) {
            val envelope = response.rdiResponse<JsonElement>()
            if (!envelope.ok) throw RequestError(envelope.msg, errorCode = envelope.errorCode)
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
        val envelope = response.rdiResponse<T>()
        if (!envelope.ok) throw RequestError(envelope.msg, errorCode = envelope.errorCode)
        return envelope.data ?: error("服务器响应缺少数据")
    }
}
