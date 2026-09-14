package calebxzau.rdi.client.service

import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackCreateFromUploadDto
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.model.ModpackVersionCreateFromUploadDto
import calebxzhou.rdi.common.model.Response
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType as ClientContentType
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.util.urlEncoded
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModpackUploadApiTest {
    @Test
    fun `api sends all chunked upload and publication routes with snapshots`() = runBlocking {
        val uploadId = UUID.randomUUID()
        val session = ModpackUploadSessionVo(
            id = uploadId,
            fileName = "pack.zip",
            size = 3,
            sha1 = "c".repeat(40),
            partSize = 2,
            partCount = 2,
            uploadedParts = listOf(0),
            ready = false,
            expiresAt = 123,
            maxParallelParts = 8,
        )
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val responses = ArrayDeque<String>(
            listOf(
                jsonResponse(Response(code = 0, msg = "", data = session)),
                jsonResponse(Response<Unit>(code = 0, msg = "")),
                jsonResponse(Response(code = 0, msg = "", data = session.copy(ready = true, uploadedParts = listOf(0, 1)))),
                jsonResponse(Response<Unit>(code = 0, msg = "")),
                jsonResponse(Response<Unit>(code = 0, msg = "")),
                jsonResponse(Response(code = 0, msg = "", data = emptyList<Modpack>())),
                jsonResponse(Response<Unit>(code = 0, msg = "")),
            ),
        )
        val engine = MockEngine { request ->
            requests += request
            respond(responses.removeFirst(), HttpStatusCode.OK, jsonHeaders())
        }
        val client = client(engine)
        val previousAccount = loggedAccount
        val selectedServer = server
        val previousIp = selectedServer.ip
        val previousNoHttps = selectedServer.noHttps
        loggedAccount = previousAccount.copy().also { it.jwt = "snapshot-token" }
        selectedServer.ip = "snapshot-host"
        selectedServer.noHttps = true
        try {
            val api = currentModpackUploadApi(client)
            loggedAccount = previousAccount.copy().also { it.jwt = "changed-token" }
            selectedServer.ip = "changed-host"

            assertEquals(session, api.createSession(ModpackUploadSessionCreateDto("pack.zip", 3, "a".repeat(40))))
            api.uploadPart(uploadId, 1, byteArrayOf(1, 2), "b".repeat(40))
            assertEquals(session.copy(ready = true, uploadedParts = listOf(0, 1)), api.completeSession(uploadId))
            val create = createDto()
            api.publishNew(ModpackCreateFromUploadDto(uploadId, create))
            val modpackId = ObjectId("66a000000000000000000001")
            val version = ModpackVersionCreateFromUploadDto(
                uploadId,
                mutableListOf(),
                mutableListOf(create.clientExtras.single()),
            )
            api.publishVersion(modpackId, "release candidate", version)
            assertEquals(emptyList(), api.listMy())
            api.cancelSession(uploadId)

            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer snapshot-token" })
            assertTrue(requests.all { it.url.host == "snapshot-host" })
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Post, HttpMethod.Post, HttpMethod.Post, HttpMethod.Get, HttpMethod.Delete), requests.map { it.method })
            assertEquals("/modpack/upload-sessions", requests[0].url.encodedPath)
            assertEquals(
                ModpackUploadSessionCreateDto("pack.zip", 3, "a".repeat(40)),
                serdesJson.decodeFromString(requests[0].body.asText()),
            )
            assertEquals("/modpack/upload-sessions/$uploadId/parts/1", requests[1].url.encodedPath)
            assertEquals("b".repeat(40), requests[1].headers["X-Part-SHA1"])
            assertContentEquals(byteArrayOf(1, 2), requests[1].body.asBytes())
            assertEquals(
                uploadId,
                serdesJson.decodeFromString<ModpackCreateFromUploadDto>(requests[3].body.asText()).uploadId,
            )
            assertEquals(
                uploadId,
                serdesJson.decodeFromString<ModpackVersionCreateFromUploadDto>(requests[4].body.asText()).uploadId,
            )
            assertEquals(
                create.clientExtras,
                serdesJson.decodeFromString<ModpackCreateFromUploadDto>(requests[3].body.asText()).modpack.clientExtras,
            )
            assertEquals(
                version.clientExtras,
                serdesJson.decodeFromString<ModpackVersionCreateFromUploadDto>(requests[4].body.asText()).clientExtras,
            )
            assertEquals("/modpack/${modpackId.toHexString()}/version/${"release candidate".urlEncoded}/from-upload", requests[4].url.encodedPath)
        } finally {
            loggedAccount = previousAccount
            selectedServer.ip = previousIp
            selectedServer.noHttps = previousNoHttps
            client.close()
        }
    }

    @Test
    fun `part server errors are retryable transport failures and envelope errors remain request errors`() = runBlocking<Unit> {
        val bytes = byteArrayOf(1, 2)
        val serverErrorClient = client(MockEngine { respond("temporary", HttpStatusCode.InternalServerError) })
        val previousAccount = loggedAccount
        loggedAccount = previousAccount.copy().also { it.jwt = "token" }
        try {
            val api = currentModpackUploadApi(serverErrorClient)
            assertFailsWith<IOException> { api.uploadPart(UUID.randomUUID(), 0, bytes, "a".repeat(40)) }
        } finally {
            serverErrorClient.close()
            loggedAccount = previousAccount
        }

        val requestErrorClient = client(
            MockEngine {
                respond(
                    content = serdesJson.encodeToString(Response<Unit>(code = 9, msg = "denied")),
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
            },
        )
        loggedAccount = previousAccount.copy().also { it.jwt = "token" }
        try {
            assertFailsWith<RequestError> { currentModpackUploadApi(requestErrorClient).listMy() }
        } finally {
            requestErrorClient.close()
            loggedAccount = previousAccount
        }
    }

    private fun createDto() = Modpack.CreateWithVersionDto(
        name = "测试整合包",
        mcVer = McVersion.V211,
        modLoader = McVersion.V211.loaderVersions.keys.first(),
        verName = "1.0",
        info = "测试简介",
        mods = mutableListOf(),
        clientExtras = mutableListOf(
            Content(
                platform = ContentPlatform.Modrinth,
                type = ClientContentType.ResPack,
                projectId = "resource-project",
                fileId = "resource-file",
                slug = "resource-pack",
                hash = "a".repeat(40),
                path = "resourcepacks/resource.zip",
                side = ContentSide.Client,
            )
        ),
    )

    private fun client(engine: MockEngine): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(serdesJson) }
    }

    private fun jsonHeaders() = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}

private inline fun <reified T> jsonResponse(value: Response<T>): String = serdesJson.encodeToString(value)

private fun OutgoingContent.asBytes(): ByteArray = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes()
    is TextContent -> text.toByteArray()
    else -> error("unexpected request body: ${this::class.simpleName}")
}

private fun OutgoingContent.asText(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    else -> error("unexpected request body: ${this::class.simpleName}")
}
