package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackUploadPreflightDto
import calebxzhou.rdi.master.service.modpack.ModpackQueryService
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.createVersion
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.createWithVersion
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.preflight
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.canUploadVersion
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.requireCanUploadVersion
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.requireUploadAccount
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mongodb.kotlin.client.coroutine.FindFlow
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModpackServiceUploadAccountTest {
    private var previousDebug = false
    private lateinit var collection: MongoCollection<Modpack>
    private val player = ModpackServiceTestFixtures.account().copy(msid = null)
    private val pack = ModpackServiceTestFixtures.modpack(player._id)
    private val accountError = "请先绑定微软账号，再上传整合包或新版本"

    @BeforeTest
    fun setup() {
        previousDebug = DEBUG
        DEBUG = false
        collection = mockk()
        ModpackService.testDbcl = collection
        mockkObject(PlayerService)
        coEvery { PlayerService.getById(player._id) } returns player
        val flow = mockk<FindFlow<Modpack>>()
        every { collection.find(any<Bson>()) } returns flow
        coEvery { flow.collect(any()) } coAnswers {
            firstArg<FlowCollector<Modpack>>().emit(pack)
        }
    }

    @AfterTest
    fun cleanup() {
        DEBUG = previousDebug
        ModpackService.testDbcl = null
        unmockkObject(PlayerService)
    }

    @Test
    fun `missing msid rejects authors listed uploaders public uploaders and administrators`() {
        val contexts = listOf(
            ModpackContext(player, ModpackServiceTestFixtures.modpack(player._id, allowUploaderIds = emptyList()), null),
            ModpackContext(player, ModpackServiceTestFixtures.modpack(allowUploaderIds = listOf(player._id)), null),
            ModpackContext(player, ModpackServiceTestFixtures.modpack(), null),
            ModpackContext(player.copy(name = "davickk"), ModpackServiceTestFixtures.modpack(allowUploaderIds = emptyList()), null),
        )
        contexts.forEach { context ->
            assertFalse(context.canUploadVersion())
            assertEquals(accountError, assertFailsWith<RequestError> { context.requireCanUploadVersion() }.message)
        }
    }

    @Test
    fun `debug skips msid requirement but preserves uploader permissions`() = runTest {
        DEBUG = true
        requireUploadAccount(player)
        val context = ModpackContext(player, pack, null)
        assertTrue(context.canUploadVersion())
        assertEquals(context, context.requireCanUploadVersion())
        preflightDto(pack._id).preflight(player)
        assertEquals(listOf(pack), ModpackQueryService.listUploadable(player))

        val denied = ModpackContext(player, ModpackServiceTestFixtures.modpack(allowUploaderIds = emptyList()), null)
        assertFalse(denied.canUploadVersion())
        assertEquals("没有上传新版本的权限", assertFailsWith<RequestError> { denied.requireCanUploadVersion() }.message)
    }

    @Test
    fun `bound account passes account gate while uploader permissions still apply`() {
        val bound = ModpackServiceTestFixtures.account()
        requireUploadAccount(bound)
        assertTrue(ModpackContext(bound, pack, null).canUploadVersion())
        assertFalse(ModpackContext(bound, ModpackServiceTestFixtures.modpack(allowUploaderIds = emptyList()), null).canUploadVersion())
    }

    @Test
    fun `create and version preflight reject missing msid before database access`() = runTest {
        listOf(null, pack._id).forEach { id ->
            assertEquals(accountError, assertFailsWith<RequestError> { preflightDto(id).preflight(player) }.message)
        }
        verify { collection wasNot Called }
    }

    @Test
    fun `direct publication rejects missing msid before archive or database access`() = runTest {
        val unreadArchive = File("unused-modpack-upload-account-test.tar.zst")
        val dto = Modpack.CreateWithVersionDto(
            name = pack.name,
            mcVer = pack.mcVer,
            modLoader = pack.modloader,
            verName = "1.0.0",
            mods = mutableListOf(),
        )
        assertEquals(accountError, assertFailsWith<RequestError> { dto.createWithVersion(player, unreadArchive) }.message)
        assertEquals(accountError, assertFailsWith<RequestError> {
            ModpackContext(player, pack, null).createVersion("1.0.0", unreadArchive, mutableListOf())
        }.message)
        verify { collection wasNot Called }
    }

    @Test
    fun `missing msid returns no uploadable packs including for administrator`() = runTest {
        assertTrue(ModpackQueryService.listUploadable(player).isEmpty())
        assertTrue(ModpackQueryService.listUploadable(player.copy(name = "davickk")).isEmpty())
        verify { collection wasNot Called }
    }

    @Test
    fun `upload routes reject missing msid before reading bodies or resolving sessions`() = testApplication {
        val algorithm = Algorithm.HMAC256("modpack-upload-account-test")
        val token = JWT.create().withClaim("uid", player._id.toHexString()).sign(algorithm)
        application {
            install(StatusPages) {
                exception<RequestError> { call, cause ->
                    call.respondText(cause.message.orEmpty(), status = HttpStatusCode.Forbidden)
                }
            }
            install(Authentication) {
                jwt("test") {
                    verifier(JWT.require(algorithm).build())
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing { authenticate("test") { modpackRoutes() } }
        }
        val endpoints = listOf(
            HttpMethod.Post to "/modpack/upload-sessions",
            HttpMethod.Put to "/modpack/upload-sessions/invalid-id/parts/0",
            HttpMethod.Post to "/modpack/upload-sessions/invalid-id/complete",
            HttpMethod.Post to "/modpack/from-upload",
            HttpMethod.Post to "/modpack/${pack._id}/version/1.0.0/from-upload",
        )
        endpoints.forEach { (method, path) ->
            val response = client.request(path) {
                this.method = method
                bearerAuth(token)
            }
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
            assertEquals(accountError, response.bodyAsText(), path)
        }
    }

    private fun preflightDto(modpackId: ObjectId?) = ModpackUploadPreflightDto(
        modpackId = modpackId,
        name = pack.name,
        verName = "1.0.0",
        mcVer = pack.mcVer,
        modLoader = pack.modloader,
    )
}
