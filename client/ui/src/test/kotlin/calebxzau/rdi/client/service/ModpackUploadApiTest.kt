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
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.toByteArray
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
        val bodies = mutableListOf<ByteArray>()
        val partRequests = mutableListOf<io.ktor.client.request.HttpRequestData>()
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
        val handler: MockRequestHandler = { request ->
            requests += request
            bodies += request.body.readBytes()
            respond(responses.removeFirst(), HttpStatusCode.OK, jsonHeaders())
        }
        val client = client(MockEngine(handler))
        val partClient = client(
            MockEngine { request ->
                partRequests += request
                handler(this, request)
            },
        )
        val previousAccount = loggedAccount
        val selectedServer = server
        val previousIp = selectedServer.ip
        val previousNoHttps = selectedServer.noHttps
        loggedAccount = previousAccount.copy().also { it.jwt = "snapshot-token" }
        selectedServer.ip = "snapshot-host"
        selectedServer.noHttps = true
        try {
            val api = currentModpackUploadApi(client, partClient)
            loggedAccount = previousAccount.copy().also { it.jwt = "changed-token" }
            selectedServer.ip = "changed-host"

            assertEquals(session, api.createSession(ModpackUploadSessionCreateDto("pack.zip", 3, "a".repeat(40))))
            val sent = mutableListOf<Long>()
            api.uploadPart(uploadId, 1, byteArrayOf(1, 2), "b".repeat(40)) { sent += it }
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
                serdesJson.decodeFromString(bodies[0].decodeToString()),
            )
            assertEquals("/modpack/upload-sessions/$uploadId/parts/1", requests[1].url.encodedPath)
            assertEquals("b".repeat(40), requests[1].headers["X-Part-SHA1"])
            assertContentEquals(byteArrayOf(1, 2), bodies[1])
            assertEquals(listOf(requests[1]), partRequests)
            assertEquals(2L, sent.last())
            assertEquals(
                uploadId,
                serdesJson.decodeFromString<ModpackCreateFromUploadDto>(bodies[3].decodeToString()).uploadId,
            )
            assertEquals(
                uploadId,
                serdesJson.decodeFromString<ModpackVersionCreateFromUploadDto>(bodies[4].decodeToString()).uploadId,
            )
            assertEquals(
                create.clientExtras,
                serdesJson.decodeFromString<ModpackCreateFromUploadDto>(bodies[3].decodeToString()).modpack.clientExtras,
            )
            assertEquals(
                version.clientExtras,
                serdesJson.decodeFromString<ModpackVersionCreateFromUploadDto>(bodies[4].decodeToString()).clientExtras,
            )
            assertEquals("/modpack/${modpackId.toHexString()}/version/${"release candidate".urlEncoded}/from-upload", requests[4].url.encodedPath)
        } finally {
            loggedAccount = previousAccount
            selectedServer.ip = previousIp
            selectedServer.noHttps = previousNoHttps
            client.close()
            partClient.close()
        }
    }

    @Test
    fun `part server errors are retryable transport failures and envelope errors remain request errors`() = runBlocking<Unit> {
        val bytes = byteArrayOf(1, 2)
        val serverErrorClient = client(MockEngine { respond("temporary", HttpStatusCode.InternalServerError) })
        val previousAccount = loggedAccount
        loggedAccount = previousAccount.copy().also { it.jwt = "token" }
        try {
            val api = currentModpackUploadApi(serverErrorClient, serverErrorClient)
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

/** Reads a request body inside the engine handler, which also drives upload progress listeners. */
private suspend fun OutgoingContent.readBytes(): ByteArray = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes()
    is OutgoingContent.ReadChannelContent -> readFrom().toByteArray()
    is OutgoingContent.NoContent -> ByteArray(0)
    else -> error("unexpected request body: ${this::class.simpleName}")
}
