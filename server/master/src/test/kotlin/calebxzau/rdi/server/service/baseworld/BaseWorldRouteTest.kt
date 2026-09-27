package calebxzau.rdi.server.service.baseworld

import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.common.model.BaseWorld
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.util.toUUID
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.service.JwtService
import calebxzhou.rdi.master.service.PlayerService
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.common.exception.RequestError
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bson.types.ObjectId
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BaseWorldRouteTest {
    private lateinit var service: BaseWorldService
    @BeforeTest
    fun setUp() {
        service = mockk()
    }

    @kotlin.test.AfterTest
    fun tearDown() {
        unmockkObject(PlayerService)
    }

    @Test
    fun `create and list derive owner from authenticated jwt`() = testApplication {
        val ownerId = ObjectId("00112233445566778899aabb")
        val ownerUuid = ownerId.toUUID()
        val account = RAccount(ownerId, "tester", "123456", "12345")
        mockkObject(PlayerService)
        coEvery { PlayerService.getById(ownerId) } returns account
        val world = BaseWorld(
            id = UUID.fromString("018f0c44-2d1f-7abc-8def-1234567890ab"),
            ownerId = ownerUuid,
            name = "Skyblock",
            levelType = "custom",
            generatorSettings = "{}",
            size = 7,
        )
        coEvery {
            service.create(ownerUuid, "Skyblock", "custom", "{}", 7,)
        } returns Result.success(world)
        coEvery {
            service.create(ownerUuid, "Normal", "normal", null, 0,)
        } returns Result.success(world.copy(name = "Normal", generatorSettings = null, size = 0))
        coEvery { service.listByOwner(ownerUuid) } returns Result.success(listOf(world))
        application { installBaseWorldTestApp(service) }

        val token = JwtService.generateToken(ownerId)
        val createResponse = client.post("/baseworld") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Skyblock","levelType":"custom","generatorSettings":"{}","size":7}""")
        }
        assertEquals(HttpStatusCode.OK, createResponse.status)
        assertTrue(createResponse.bodyAsText().contains(world.id.toString()))

        val omittedGeneratorSettingsResponse = client.post("/baseworld") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Normal","levelType":"normal","size":0}""")
        }
        assertEquals(HttpStatusCode.OK, omittedGeneratorSettingsResponse.status)

        val listResponse = client.get("/baseworld?myOnly=true") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertTrue(listResponse.bodyAsText().contains(world.id.toString()))
        coVerify(exactly = 1) { service.create(ownerUuid, "Skyblock", "custom", "{}", 7,) }
        coVerify(exactly = 1) { service.create(ownerUuid, "Normal", "normal", null, 0,) }
        coVerify(exactly = 1) { service.listByOwner(ownerUuid) }
    }

    @Test
    fun `get and delete are owner scoped and malformed id is a parameter error`() = testApplication {
        val ownerId = ObjectId("00112233445566778899aabb")
        val ownerUuid = ownerId.toUUID()
        val worldId = UUID.fromString("018f0c44-2d1f-7abc-8def-1234567890ab")
        coEvery { service.findById(ownerUuid, worldId) } returns Result.success(null)
        coEvery { service.delete(ownerUuid, worldId) } returns Result.success(false)
        application { installBaseWorldTestApp(service) }
        val token = JwtService.generateToken(ownerId)

        val missing = client.get("/baseworld/$worldId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, missing.status)
        assertTrue(missing.bodyAsText().contains("\"code\":-1"))
        assertTrue(missing.bodyAsText().contains("地图模板不存在"))

        val malformed = client.get("/baseworld/not-a-uuid") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, malformed.status)
        assertTrue(malformed.bodyAsText().contains("\"code\":-1"))
        assertTrue(malformed.bodyAsText().contains("地图模板ID不正确"))

        val deleted = client.delete("/baseworld/$worldId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(deleted.bodyAsText().contains("\"code\":-1"))
        assertTrue(deleted.bodyAsText().contains("地图模板不存在"))
    }

    @Test
    fun `service failures are not converted into successful responses`() = testApplication {
        val ownerId = ObjectId("00112233445566778899aabb")
        coEvery { service.listReleased() } returns Result.failure(IllegalStateException("database unavailable"))
        application { installBaseWorldTestApp(service) }

        val response = client.get("/baseworld") {
            bearerAuth(JwtService.generateToken(ownerId))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":-500"))
    }

    @Test
    fun `upload routes pass authenticated owner, session id, part headers and body`() = testApplication {
        val ownerId = ObjectId("00112233445566778899aabb").toUUID()
        val worldId = UUID.fromString("018f0c44-2d1f-7abc-8def-1234567890ab")
        val uploadId = UUID.fromString("018f0c44-2d1f-7abc-8def-1234567890ac")
        val sha1 = "0123456789012345678901234567890123456789"
        val vo = calebxzau.rdi.common.model.BaseWorldUploadSessionVo(uploadId, 3, 4, 1, listOf(), 123)
        val world = BaseWorld(worldId, ownerId, "World", "normal", null, 3)
        coEvery { service.createUpload(ownerId, worldId, any()) } returns Result.success(vo)
        coEvery { service.uploadStatus(ownerId, worldId, uploadId) } returns Result.success(vo)
        coEvery { service.uploadPart(ownerId, worldId, uploadId, 0, 3, sha1, any()) } returns Result.success(Unit)
        coEvery { service.completeUpload(ownerId, worldId, uploadId) } returns Result.success(vo)
        application { installBaseWorldTestApp(service) }
        val token = JwtService.generateToken(ObjectId("00112233445566778899aabb"))

        assertEquals(HttpStatusCode.OK, client.post("/baseworld/$worldId/upload") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"size":3,"sha1":"$sha1"}""")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/baseworld/$worldId/upload/$uploadId") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.put("/baseworld/$worldId/upload/$uploadId/parts/0") {
            bearerAuth(token)
            header("X-Part-SHA1", sha1)
            setBody(byteArrayOf(1, 2, 3))
        }.status)
        assertEquals(HttpStatusCode.OK, client.post("/baseworld/$worldId/upload/$uploadId/complete") { bearerAuth(token) }.status)
        coVerify(exactly = 1) { service.uploadPart(ownerId, worldId, uploadId, 0, 3, sha1, any()) }
    }

    private fun io.ktor.server.application.Application.installBaseWorldTestApp(
        service: BaseWorldService,
    ) {
        install(ContentNegotiation) { json(serdesJson) }
        install(StatusPages) {
            exception<ParamError> { call, cause ->
                call.response<Unit>(code = -1, msg = cause.message ?: "参数错误", data = null)
            }
            exception<RequestError> { call, cause ->
                call.response<Unit>(code = -1, msg = cause.message ?: "逻辑错误", data = null)
            }
            exception<Throwable> { call, _ ->
                call.response<Unit>(code = -500, msg = "服务器内部错误", data = null)
            }
        }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(JwtService.verifier)
                validate { JWTPrincipal(it.payload) }
            }
        }
        install(Koin) {
            modules(module {
                single { service }
            })
        }
        routing {
            authenticate("auth-jwt") { baseWorldRoutes() }
        }
    }
}
