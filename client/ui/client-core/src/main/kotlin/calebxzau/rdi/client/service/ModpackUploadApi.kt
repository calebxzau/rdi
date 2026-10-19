package calebxzau.rdi.client.service

import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.rdiResponse
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackCreateFromUploadDto
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.model.ModpackVersionCreateFromUploadDto
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.net.ktorUploadClient
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.util.urlEncoded
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.timeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import org.bson.types.ObjectId
import java.io.IOException
import java.util.UUID

interface ModpackUploadApi {
    suspend fun createSession(request: ModpackUploadSessionCreateDto): ModpackUploadSessionVo

    suspend fun uploadPart(
        uploadId: UUID,
        index: Int,
        bytes: ByteArray,
        sha1: String,
        onBytesSent: (Long) -> Unit = {},
    )

    suspend fun completeSession(uploadId: UUID): ModpackUploadSessionVo

    suspend fun cancelSession(uploadId: UUID)

    suspend fun publishNew(request: ModpackCreateFromUploadDto)

    suspend fun publishVersion(
        modpackId: ObjectId,
        versionName: String,
        request: ModpackVersionCreateFromUploadDto,
    )

    suspend fun listMy(): List<Modpack>
}

/** [partHttpClient] sends part bodies only; see [ktorUploadClient]. */
fun currentModpackUploadApi(
    httpClient: HttpClient = ktorClient,
    partHttpClient: HttpClient = ktorUploadClient,
): ModpackUploadApi {
    val token = loggedAccount.jwt?.trim()?.takeIf(String::isNotEmpty)
        ?: error("必须登录后才能上传整合包")
    return HttpModpackUploadApi(httpClient, partHttpClient, server.hqUrl.trimEnd('/'), token)
}

private class HttpModpackUploadApi(
    private val httpClient: HttpClient,
    private val partHttpClient: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : ModpackUploadApi {
    override suspend fun createSession(dto: ModpackUploadSessionCreateDto): ModpackUploadSessionVo =
        request(HttpMethod.Post, "/modpack/upload-sessions", serdesJson.encodeToString(dto))

    override suspend fun uploadPart(
        uploadId: UUID,
        index: Int,
        bytes: ByteArray,
        sha1: String,
        onBytesSent: (Long) -> Unit,
    ) {
        request<Unit>(HttpMethod.Put, "/modpack/upload-sessions/$uploadId/parts/$index", client = partHttpClient) {
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

    override suspend fun completeSession(uploadId: UUID): ModpackUploadSessionVo =
        request(
            HttpMethod.Post,
            "/modpack/upload-sessions/$uploadId/complete",
            timeoutMillis = PUBLISH_TIMEOUT_MILLIS,
        )

    override suspend fun cancelSession(uploadId: UUID) {
        request<Unit>(
            HttpMethod.Delete,
            "/modpack/upload-sessions/$uploadId",
            timeoutMillis = CANCEL_TIMEOUT_MILLIS,
        )
    }

    override suspend fun publishNew(dto: ModpackCreateFromUploadDto) {
        request<Unit>(
            HttpMethod.Post,
            "/modpack/from-upload",
            serdesJson.encodeToString(dto),
            timeoutMillis = PUBLISH_TIMEOUT_MILLIS,
        )
    }

    override suspend fun publishVersion(
        modpackId: ObjectId,
        versionName: String,
        dto: ModpackVersionCreateFromUploadDto,
    ) {
        request<Unit>(
            HttpMethod.Post,
            "/modpack/${modpackId.toHexString()}/version/${versionName.urlEncoded}/from-upload",
            serdesJson.encodeToString(dto),
            timeoutMillis = PUBLISH_TIMEOUT_MILLIS,
        )
    }

    override suspend fun listMy(): List<Modpack> = request(HttpMethod.Get, "/modpack/my")

    private suspend inline fun <reified T> request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        timeoutMillis: Long? = null,
        client: HttpClient = httpClient,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = client.request("$baseUrl$path") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            timeoutMillis?.let { timeout {
                requestTimeoutMillis = it
                socketTimeoutMillis = it
            } }
            configure()
        }
        if (method == HttpMethod.Put && response.status.value >= 500) {
            val status = response.status.value
            response.bodyAsText()
            throw IOException("服务器暂时无法处理整合包分片上传:HTTP$status")
        }
        val envelope = response.rdiResponse<T>()
        if (!envelope.ok) {
            throw RequestError(envelope.msg, errorCode = envelope.errorCode)
        }
        if (T::class == Unit::class) {
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
        return envelope.data ?: throw RequestError("服务器响应缺少整合包上传数据")
    }

    private companion object {
        const val PUBLISH_TIMEOUT_MILLIS = 60 * 60 * 1000L
        const val CANCEL_TIMEOUT_MILLIS = 10_000L
    }
}
