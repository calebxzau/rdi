package calebxzau.rdi.client.modcatalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class J4bCatalogParityTest {
    private val fixtures = Json.parseToJsonElement(File("../../slint-proto/protocol/fixtures/j4b.json").readText()).jsonObject["catalog"]!!.jsonObject

    @Test
    fun `shared CurseForge batch request and response map identically`(): Unit = runBlocking {
        val fixture = fixtures["curseforge"]!!.jsonObject
        val http = HttpClient(MockEngine { request ->
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertEquals(fixture["request"], Json.parseToJsonElement(body))
            respond(fixture["response"].toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val adapter = CurseForgeAdapter(http, CurseForgeConfig(apiKey = "fixture"), CatalogNetworkPolicy(preferMirror = false), Json { ignoreUnknownKeys = true })
        val file = adapter.getFiles(setOf("42")).found.getValue("42")
        assertEquals("fixture.jar", file.fileName)
        assertEquals(3, file.fileSize)
        assertEquals("neoforge", file.loaders.single().name)
        http.close()
    }

    @Test
    fun `shared Modrinth exact hash match uses requested file`(): Unit = runBlocking {
        val fixture = fixtures["modrinth"]!!.jsonObject
        val http = HttpClient(MockEngine { request ->
            assertEquals(fixture["request"], Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text))
            respond(fixture["response"].toString(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val adapter = ModrinthAdapter(http, ModrinthConfig(), CatalogNetworkPolicy(preferMirror = false), Json { ignoreUnknownKeys = true })
        val file = adapter.matchFiles(listOf(CatalogFileHashes("fixture", "a9993e364706816aba3e25717850c26c9cd0d89d", null))).getValue("fixture")
        assertEquals("v1", file.ref.fileId)
        assertEquals("p1", file.project.projectId)
        assertEquals("fixture.jar", file.fileName)
        http.close()
    }
}
