package calebxzau.rdi.client.modcatalog

import calebxzau.rdi.client.modcatalog.CatalogException
import calebxzau.rdi.client.modcatalog.CatalogNetworkPolicy
import calebxzau.rdi.client.modcatalog.CatalogSort
import calebxzau.rdi.client.modcatalog.CatalogTarget
import calebxzau.rdi.client.modcatalog.CurseForgeAdapter
import calebxzau.rdi.client.modcatalog.CurseForgeConfig
import calebxzau.rdi.client.modcatalog.EnvironmentRequirement
import calebxzau.rdi.client.modcatalog.ModrinthAdapter
import calebxzau.rdi.client.modcatalog.ModrinthConfig
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `modrinth returned fabric loader maps to the shared loader`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """[{"id":"version-1","name":"Fabric mod","version_number":"1.0","project_id":"project-1","version_type":"release","game_versions":["1.20.1"],"loaders":["fabric"],"date_published":"2026-01-01T00:00:00Z","files":[{"filename":"mod.jar","url":"https://cdn.modrinth.com/data/mod.jar","primary":true,"hashes":{"sha1":"${"a".repeat(40)}"}}]}]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine), ModrinthConfig(), CatalogNetworkPolicy(preferMirror = false), json
        )

        val file = adapter.getFiles(setOf("version-1")).found.getValue("version-1")

        assertEquals(setOf(ModLoader.Fabric), file.loaders)
    }

    @Test
    fun `curseforge returned fabric label maps to the shared loader`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"data":[{"id":10001,"modId":7,"displayName":"Fabric mod","fileName":"mod.jar","releaseType":1,"hashes":[{"value":"${"b".repeat(40)}","algo":1}],"fileDate":"2026-01-01T00:00:00Z","downloadUrl":"https://edge.forgecdn.net/files/mod.jar","gameVersions":["1.20.1","Fabric"]}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine), CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false), json
        )

        val file = adapter.getFiles(setOf("10001")).found.getValue("10001")

        assertEquals(setOf(ModLoader.Fabric), file.loaders)
        assertEquals(setOf("1.20.1"), file.minecraftVersions)
    }

    @Test
    fun `modrinth search maps raw values`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("1.21.1", request.url.parameters.getAll("facets")?.single()?.substringAfter("versions:")?.substringBefore('"'))
            respond(
                content = """{"hits":[{"slug":"jei","title":"JEI","description":"items","project_type":"mod","downloads":42,"project_id":"abc","date_modified":"2026-01-01T00:00:00Z","client_side":"required","server_side":"unsupported"}],"total_hits":1}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )
        val page = adapter.search(
            "jei",
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            CatalogSort.RELEVANCE,
            0,
            20
        )
        assertEquals(42L, page.items.single().downloadCount)
        assertEquals(EnvironmentRequirement.UNSUPPORTED, page.items.single().environment.server)
    }

    @Test
    fun `modrinth search returns only mods and advances by raw hits`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"hits":[
                    {"slug":"pack","title":"Pack","project_type":"resourcepack","project_id":"pack"},
                    {"slug":"mod","title":"Mod","project_type":"MOD","project_id":"mod"}
                ],"total_hits":3}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val page = adapter.search(
            "content",
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            CatalogSort.RELEVANCE,
            0,
            2
        )

        assertEquals(listOf("mod"), page.items.map { it.ref.projectId })
        assertEquals(2, page.nextOffset)
    }

    @Test
    fun `modrinth batch projects keeps resource and shader content types`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """[
                    {"id":"mod","slug":"mod","title":"Mod","project_type":"mod"},
                    {"id":"resource","slug":"resource","title":"Resource","project_type":"resourcepack"},
                    {"id":"shader","slug":"shader","title":"Shader","project_type":"shader"}
                ]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val projects = adapter.getProjects(setOf("mod", "resource", "shader")).found

        assertEquals(CatalogContentType.MOD, projects.getValue("mod").contentType)
        assertEquals(CatalogContentType.RESOURCE_PACK, projects.getValue("resource").contentType)
        assertEquals(CatalogContentType.SHADER_PACK, projects.getValue("shader").contentType)
    }

    @Test
    fun `curseforge exact slug lookup uses one filtered search request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            assertEquals("jei", request.url.parameters["slug"])
            assertEquals(null, request.url.parameters["searchFilter"])
            assertEquals("1.21.1", request.url.parameters["gameVersion"])
            assertEquals("6", request.url.parameters["modLoaderType"])
            respond(
                content = """{"data":[{"id":238222,"name":"JEI","slug":"jei","classId":6}],"pagination":{"index":0,"resultCount":1,"totalCount":1}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val project = adapter.resolveSlugs(
            listOf("jei"),
            CatalogTarget(McVersion.V211, ModLoader.neoforge)
        ).found["jei"]

        assertEquals("238222", project?.ref?.projectId)
        assertEquals(1, requestCount)
    }

    @Test
    fun `curseforge gets more than fifty projects in one request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            assertEquals("/v1/mods", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"data":[]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        adapter.getProjects((1..51).mapTo(linkedSetOf(), Int::toString))

        assertEquals(1, requestCount)
    }

    @Test
    fun `curseforge batch projects preserves every content type in one request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            assertEquals("/v1/mods", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"data":[
                    {"id":1,"name":"Mod","slug":"mod","classId":6},
                    {"id":2,"name":"Resource","slug":"resource","classId":12},
                    {"id":3,"name":"Shader","slug":"shader","classId":6552},
                    {"id":4,"name":"Unknown","slug":"unknown","classId":999}
                ]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val projects = adapter.getProjects(setOf("1", "2", "3", "4")).found

        assertEquals(CatalogContentType.MOD, projects.getValue("1").contentType)
        assertEquals(CatalogContentType.RESOURCE_PACK, projects.getValue("2").contentType)
        assertEquals(CatalogContentType.SHADER_PACK, projects.getValue("3").contentType)
        assertEquals(CatalogContentType.OTHER, projects.getValue("4").contentType)
        assertEquals(1, requestCount)
    }

    @Test
    fun `curseforge file side tags map to environment requirements`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/v1/mods/files", request.url.encodedPath)
            respond(
                content = """{"data":[
                    {"id":1001,"modId":1,"fileName":"both.jar","releaseType":1,"hashes":[{"value":"1111111111111111111111111111111111111111","algo":1}],"fileDate":"2026-01-01T00:00:00Z","gameVersions":["1.21.1","Client","Server"],"fileFingerprint":1},
                    {"id":1002,"modId":1,"fileName":"client.jar","releaseType":1,"hashes":[{"value":"2222222222222222222222222222222222222222","algo":1}],"fileDate":"2026-01-01T00:00:00Z","gameVersions":["1.21.1","Client"],"fileFingerprint":2},
                    {"id":1003,"modId":1,"fileName":"server.jar","releaseType":1,"hashes":[{"value":"3333333333333333333333333333333333333333","algo":1}],"fileDate":"2026-01-01T00:00:00Z","gameVersions":["1.21.1","Server"],"fileFingerprint":3},
                    {"id":1004,"modId":1,"fileName":"unknown.jar","releaseType":1,"hashes":[{"value":"4444444444444444444444444444444444444444","algo":1}],"fileDate":"2026-01-01T00:00:00Z","gameVersions":["1.21.1","NeoForge"],"fileFingerprint":4}
                ]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val files = adapter.getFiles(setOf("1001", "1002", "1003", "1004")).found

        assertEquals(
            EnvironmentCompatibility(EnvironmentRequirement.REQUIRED, EnvironmentRequirement.REQUIRED),
            files.getValue("1001").environment
        )
        assertEquals(
            EnvironmentCompatibility(EnvironmentRequirement.REQUIRED, EnvironmentRequirement.UNSUPPORTED),
            files.getValue("1002").environment
        )
        assertEquals(
            EnvironmentCompatibility(EnvironmentRequirement.UNSUPPORTED, EnvironmentRequirement.REQUIRED),
            files.getValue("1003").environment
        )
        assertEquals(EnvironmentCompatibility(), files.getValue("1004").environment)
    }

    @Test
    fun `curseforge batch files resolve downloads without per file requests`() = runBlocking {
        var batchRequestCount = 0
        var singleFileRequestCount = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/download-url")) singleFileRequestCount++
            assertEquals("/v1/mods/files", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            batchRequestCount++
            respond(
                content = """{"data":[
                    {"id":1234567,"modId":1,"fileName":"direct.jar","releaseType":1,"hashes":[{"value":"1111111111111111111111111111111111111111","algo":1}],"downloadUrl":"https://mediafilez.forgecdn.net/files/1234/567/direct.jar"},
                    {"id":7654321,"modId":2,"fileName":"fallback file.jar","releaseType":1,"hashes":[{"value":"2222222222222222222222222222222222222222","algo":1}]}
                ]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val files = adapter.getFiles(setOf("1234567", "7654321")).found
        val direct = adapter.resolveDownload(files.getValue("1234567"))
        val fallback = adapter.resolveDownload(files.getValue("7654321"))

        assertEquals("https://mediafilez.forgecdn.net/files/1234/567/direct.jar", direct.url)
        assertEquals("https://mediafilez.forgecdn.net/files/7654/321/fallback%20file.jar", fallback.url)
        assertEquals(1, batchRequestCount)
        assertEquals(0, singleFileRequestCount)
    }

    @Test
    fun `modrinth complete list carries download url without another request`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            assertEquals("/v2/project/project/version", request.url.encodedPath)
            respond(
                content = """[{"id":"version","name":"Version","version_number":"1.0","project_id":"project","version_type":"release","date_published":"2026-01-01T00:00:00Z","game_versions":["1.21.1"],"loaders":["neoforge"],"files":[{"filename":"mod.jar","url":"https://cdn.modrinth.com/mod.jar","primary":true,"size":1,"hashes":{"sha1":"1111111111111111111111111111111111111111"}}]}]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val list = adapter.listFiles(
            CatalogProjectRef(ModPlatform.MODRINTH, "project"),
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            0,
            1
        ) as AdapterFileList.Complete
        val download = adapter.resolveDownload(list.items.single())

        assertEquals("https://cdn.modrinth.com/mod.jar", download.url)
        assertEquals(1, requestCount)
    }

    @Test
    fun `curseforge list remains server paged`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("10", request.url.parameters["index"])
            assertEquals("5", request.url.parameters["pageSize"])
            respond(
                content = """{"data":[],"pagination":{"index":10,"resultCount":5,"totalCount":20}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val page = adapter.listFiles(
            CatalogProjectRef(ModPlatform.CURSEFORGE, "1"),
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            10,
            5
        ) as AdapterFileList.Page

        assertEquals(15, page.nextOffset)
    }

    @Test
    fun `modrinth resolves multiple slugs in one request and filters target`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            assertEquals("/v2/projects", request.url.encodedPath)
            respond(
                content = """[
                    {"id":"2","slug":"second","title":"Second","game_versions":["1.21.1"],"loaders":["neoforge"]},
                    {"id":"1","slug":"first","title":"First","game_versions":["1.21.1"],"loaders":["neoforge"]},
                    {"id":"3","slug":"old","title":"Old","game_versions":["1.20.1"],"loaders":["forge"]}
                ]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )

        val result = adapter.resolveSlugs(
            listOf("first", "second", "old"),
            CatalogTarget(McVersion.V211, ModLoader.neoforge)
        )

        assertEquals(1, requestCount)
        assertEquals(setOf("first", "second"), result.found.keys)
        assertEquals(setOf("old"), result.missing)
    }

    @Test
    fun `curseforge key reaches mirror and invalid mirror falls back to official`() = runBlocking {
        val requests = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            requests += request.url.host to request.headers["x-api-key"]
            if (request.url.host == "mirror.test") {
                respond(
                    "<html>bad mirror</html>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                )
            } else {
                respond(
                    """{"data":[],"pagination":{"index":0,"resultCount":0,"totalCount":0}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = true, mirrorBaseUrl = "https://mirror.test"),
            json
        )
        adapter.search(
            "jei",
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            CatalogSort.RELEVANCE,
            0,
            20
        )
        assertEquals(listOf("mirror.test", "api.curseforge.com"), requests.map { it.first })
        assertTrue(requests.all { it.second == "secret" })
    }

    @Test
    fun `official retry does not retry mirror`() = runBlocking {
        val hosts = mutableListOf<String>()
        var officialRequests = 0
        val engine = MockEngine { request ->
            hosts += request.url.host
            when (request.url.host) {
                "mirror.test" -> respond(
                    """{"error":"unavailable"}""",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )

                else -> {
                    officialRequests++
                    respond(
                        if (officialRequests == 1) """{"error":"unavailable"}"""
                        else """{"data":[],"pagination":{"index":0,"resultCount":0,"totalCount":0}}""",
                        if (officialRequests == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret"),
            CatalogNetworkPolicy(preferMirror = true, mirrorBaseUrl = "https://mirror.test"),
            json
        )

        adapter.search(
            "jei",
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            CatalogSort.RELEVANCE,
            0,
            20
        )

        assertEquals(listOf("mirror.test", "api.curseforge.com", "api.curseforge.com"), hosts)
    }

    @Test
    fun `invalid official response is not retried`() = runBlocking {
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            respond(
                "<html>invalid</html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json
        )
        var failure: Throwable? = null

        try {
            adapter.search(
                "jei",
                CatalogTarget(McVersion.V211, ModLoader.neoforge),
                CatalogSort.RELEVANCE,
                0,
                20
            )
        } catch (cause: Throwable) {
            failure = cause
        }

        assertTrue(failure is CatalogException.InvalidResponse)
        assertEquals(1, requestCount)
    }

    @Test
    fun `invalid official response logs url and full response body without headers`() = runBlocking {
        val responseBody = "<html>invalid official response</html>"
        val warnings = mutableListOf<String>()
        val engine = MockEngine {
            respond(
                responseBody,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret-api-key"),
            CatalogNetworkPolicy(preferMirror = false),
            json,
            onWarning = { warnings += it.stackTraceToString() }
        )

        runCatching {
            adapter.search(
                "jei",
                CatalogTarget(McVersion.V211, ModLoader.neoforge),
                CatalogSort.RELEVANCE,
                0,
                20
            )
        }

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("method=GET"))
        assertTrue(warnings.single().contains("https://api.curseforge.com/v1/mods/search?"))
        assertTrue(warnings.single().contains("searchFilter=jei"))
        assertTrue(warnings.single().contains("responseBody=$responseBody"))
        assertFalse(warnings.single().contains("secret-api-key"))
        assertFalse(warnings.single().contains("x-api-key", ignoreCase = true))
    }

    @Test
    fun `valid json with invalid dto logs response before throwing`() = runBlocking {
        val warnings = mutableListOf<String>()
        val engine = MockEngine {
            respond(
                """{"hits":"not-an-array","total_hits":1}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = false),
            json,
            onWarning = { warnings += it.stackTraceToString() }
        )

        val failure = runCatching {
            adapter.search(
                "jei",
                CatalogTarget(McVersion.V211, ModLoader.neoforge),
                CatalogSort.RELEVANCE,
                0,
                20
            )
        }.exceptionOrNull()

        assertTrue(failure is CatalogException.InvalidResponse)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("responseBody={\"hits\":\"not-an-array\",\"total_hits\":1}"))
    }

    @Test
    fun `invalid post response logs request body`() = runBlocking {
        val warnings = mutableListOf<String>()
        val engine = MockEngine {
            respond(
                """{"data":"not-an-array"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = CurseForgeAdapter(
            HttpClient(engine),
            CurseForgeConfig(apiKey = "secret-api-key"),
            CatalogNetworkPolicy(preferMirror = false),
            json,
            onWarning = { warnings += it.stackTraceToString() }
        )

        runCatching { adapter.getProjects(linkedSetOf("1", "2")) }

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("method=POST"))
        assertTrue(warnings.single().contains("requestBody={\"modIds\":[1,2]}"))
        assertFalse(warnings.single().contains("secret-api-key"))
    }

    @Test
    fun `mirror preference is read for every request`() = runBlocking {
        var preferMirror = false
        val hosts = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            respond(
                """{"hits":[],"total_hits":0}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(
                mirrorBaseUrl = "https://mirror.test",
                preferMirrorProvider = { preferMirror }
            ),
            json
        )
        val target = CatalogTarget(McVersion.V211, ModLoader.neoforge)

        adapter.search("jei", target, CatalogSort.RELEVANCE, 0, 20)
        assertEquals(listOf("api.modrinth.com"), hosts)

        preferMirror = true
        adapter.search("jei", target, CatalogSort.RELEVANCE, 0, 20)
        assertEquals(listOf("api.modrinth.com", "mirror.test"), hosts)
    }

    @Test
    fun `invalid mirror dto logs mirror and continues with official`() = runBlocking {
        val hosts = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            respond(
                if (request.url.host == "mirror.test") {
                    """{"hits":"not-an-array","total_hits":1}"""
                } else {
                    """{"hits":[],"total_hits":0}"""
                },
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val adapter = ModrinthAdapter(
            HttpClient(engine),
            ModrinthConfig(),
            CatalogNetworkPolicy(preferMirror = true, mirrorBaseUrl = "https://mirror.test"),
            json,
            onWarning = { warnings += it.stackTraceToString() }
        )

        val page = adapter.search(
            "jei",
            CatalogTarget(McVersion.V211, ModLoader.neoforge),
            CatalogSort.RELEVANCE,
            0,
            20
        )

        assertTrue(page.items.isEmpty())
        assertEquals(listOf("mirror.test", "api.modrinth.com"), hosts)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("https://mirror.test/"))
        assertTrue(warnings.single().contains("responseBody={\"hits\":\"not-an-array\",\"total_hits\":1}"))
    }
}
