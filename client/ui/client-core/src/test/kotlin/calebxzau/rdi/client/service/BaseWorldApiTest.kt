package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionVo
import calebxzau.rdi.common.model.BaseWorldUploadStatus
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.model.Response
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
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
import io.ktor.utils.io.toByteArray
import java.util.UUID
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId

class BaseWorldApiTest {
    @Test
    fun `management and ready lists send explicit visibility queries`() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val worlds = listOf(
            BaseWorld(UUID.randomUUID(), UUID.randomUUID(), "World", "minecraft:normal", null, 12),
        )
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = serdesJson.encodeToString(Response(code = 0, msg = "", data = worlds)),
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(serdesJson) }
        }
        val previous = loggedAccount
        loggedAccount = RAccount(ObjectId("68b314bbadaf52ddab96b5ed"), "test", "pwd", "qq").also {
            it.jwt = "token"
        }
        try {
            val api = currentBaseWorldApi(client)
            assertEquals(worlds, api.list())
            assertEquals(worlds, api.listReady())
            assertEquals("/baseworld", requests[0].url.encodedPath)
            assertEquals("true", requests[0].url.parameters["myOnly"])
            assertEquals("false", requests[1].url.parameters["myOnly"])
            assertEquals(null, requests[0].url.parameters["readyOnly"])
            assertEquals(null, requests[1].url.parameters["readyOnly"])
        } finally {
            loggedAccount = previous
            client.close()
        }
    }

    @Test
    fun `complete response decodes asynchronous upload status and error`() = runBlocking {
        val uploadId = UUID.randomUUID()
        val worldId = UUID.randomUUID()
        val session = BaseWorldUploadSessionVo(
            id = uploadId,
            size = 42,
            partSize = 4,
            partCount = 11,
            uploadedParts = (0..10).toList(),
            expiresAt = 123,
            status = BaseWorldUploadStatus.Failed,
            errorMessage = "level.dat损坏",
        )
        val engine = MockEngine {
            respond(
                content = serdesJson.encodeToString(Response(code = 0, msg = "", data = session)),
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(serdesJson) }
        }
        val previous = loggedAccount
        loggedAccount = RAccount(ObjectId("68b314bbadaf52ddab96b5ed"), "test", "pwd", "qq").also {
            it.jwt = "token"
        }
        try {
            assertEquals(session, currentBaseWorldApi(client).completeUpload(worldId, uploadId))
        } finally {
            loggedAccount = previous
            client.close()
        }
    }

    @Test
    fun `api captures authorization and sends JSON to the base world route`() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val world = BaseWorld(
            id = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            name = "World",
            levelType = "normal",
            generatorSettings = null,
            size = 12,
        )
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = serdesJson.encodeToString(Response(code = 0, msg = "", data = world)),
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(serdesJson) }
        }
        val previous = loggedAccount
        val account = RAccount(ObjectId("68b314bbadaf52ddab96b5ed"), "test", "pwd", "qq").also {
            it.jwt = "captured-token"
        }
        loggedAccount = account
        try {
            val api = currentBaseWorldApi(client)
            account.jwt = "changed-token"
            val actual = api.create(BaseWorld.CreateDto("World", "normal", null, 12))
            assertEquals(world, actual)
            val request = assertNotNull(requests.singleOrNull())
            assertEquals("Bearer captured-token", request.headers[HttpHeaders.Authorization])
            assertEquals("/baseworld", request.url.encodedPath)
            val body = request.body.asText()
            assertEquals("World", serdesJson.decodeFromString<BaseWorld.CreateDto>(body).name)
        } finally {
            loggedAccount = previous
            client.close()
        }
    }

    @Test
    fun `server error is retryable transport failure`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "temporary",
                status = HttpStatusCode.InternalServerError,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(serdesJson) }
        }
        val previous = loggedAccount
        loggedAccount = RAccount(ObjectId("68b314bbadaf52ddab96b5ed"), "test", "pwd", "qq").also {
            it.jwt = "token"
        }
        try {
            val error = runCatching {
                currentBaseWorldApi(client, client).uploadPart(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    byteArrayOf(1),
                    "a".repeat(40),
                )
            }.exceptionOrNull()
            assertTrue(error is IOException)
        } finally {
            loggedAccount = previous
            client.close()
        }
    }

    @Test
    fun `part request preserves raw bytes and part digest header`() = runBlocking {
        var requestData: io.ktor.client.request.HttpRequestData? = null
        var requestBody: ByteArray? = null
        val engine = MockEngine { request ->
            requestData = request
            requestBody = request.body.readBytes()
            respond(
                content = "{\"code\":0,\"msg\":\"\"}",
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(serdesJson) }
        }
        val mainClient = HttpClient(MockEngine { error("parts must use the upload client") })
        val previous = loggedAccount
        loggedAccount = RAccount(ObjectId("68b314bbadaf52ddab96b5ed"), "test", "pwd", "qq").also {
            it.jwt = "token"
        }
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val worldId = UUID.randomUUID()
            val uploadId = UUID.randomUUID()
            val sent = mutableListOf<Long>()
            currentBaseWorldApi(mainClient, client).uploadPart(
                worldId,
                uploadId,
                7,
                bytes,
                "a".repeat(40),
            ) { sent += it }
            val actual = requireNotNull(requestData)
            assertEquals(HttpMethod.Put, actual.method)
            assertEquals("/baseworld/$worldId/upload/$uploadId/parts/7", actual.url.encodedPath)
            assertEquals("a".repeat(40), actual.headers["X-Part-SHA1"])
            assertEquals(bytes.toList(), requireNotNull(requestBody).toList())
            assertEquals(3L, sent.last())
        } finally {
            loggedAccount = previous
            client.close()
            mainClient.close()
        }
    }

    @Test
    fun `successful HTTP response with failed envelope is not accepted`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "{\"code\":9,\"msg\":\"denied\"}",
                status = HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(serdesJson) }
        }
        val previous = loggedAccount
        loggedAccount = RAccount(ObjectId("68b314bbadaf52ddab96b5ed"), "test", "pwd", "qq").also {
            it.jwt = "token"
        }
        try {
            val error = runCatching { currentBaseWorldApi(client).list() }.exceptionOrNull()
            assertTrue(error is RequestError)
        } finally {
            loggedAccount = previous
            client.close()
        }
    }
}

private fun OutgoingContent.asText(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    else -> error("unexpected request body: ${this::class.simpleName}")
}

/** Reads a request body inside the engine handler, which also drives upload progress listeners. */
private suspend fun OutgoingContent.readBytes(): ByteArray = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes()
    is OutgoingContent.ReadChannelContent -> readFrom().toByteArray()
    else -> error("unexpected request body: ${this::class.simpleName}")
}
