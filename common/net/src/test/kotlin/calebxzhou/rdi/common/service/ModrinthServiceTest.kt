package calebxzhou.rdi.common.service

import kotlinx.coroutines.runBlocking
import calebxzhou.rdi.common.exception.ModpackError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.encodeToString
import java.nio.file.Files

class ModrinthServiceTest {
    @Test
    fun `fabric manifest is recognized and rejected before resolving catalog content`() = runBlocking {
        val directory = Files.createTempDirectory("fabric-modrinth-manifest").toFile()
        try {
            directory.resolve("overrides").mkdirs()
            val index = ModrinthModpackIndex(
                formatVersion = 1,
                game = "minecraft",
                versionId = "fixture",
                name = "Fabric fixture",
                dependencies = mapOf("minecraft" to "1.20.1", "fabric-loader" to "0.16.10"),
            )
            directory.resolve("modrinth.index.json").writeText(serdesJson.encodeToString(index))
            var catalogResolutions = 0

            val result = ModrinthService.loadModpack(directory) {
                catalogResolutions += 1
                emptyMap()
            }

            assertTrue(result.isFailure)
            assertContains(result.exceptionOrNull()?.message.orEmpty(), "已识别为Minecraft1.20.1／Fabric")
            assertEquals(0, catalogResolutions)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty project request does not invoke fetcher`() = runBlocking {
        var calls = 0

        val result = ModrinthService.fetchMultipleProjects(emptyList()) {
            calls += 1
            emptyList()
        }

        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `large project request invokes fetcher once with distinct ordered ids`() = runBlocking {
        val expected = (0 until 101).map { "project-$it" }
        val calls = mutableListOf<List<String>>()

        val result = ModrinthService.fetchMultipleProjects(expected + expected.first()) { ids ->
            calls += ids
            emptyList()
        }

        assertTrue(result.isEmpty())
        assertEquals(listOf(expected), calls)
    }

    @Test
    fun `manifest resolver keeps placements and excludes unsupported client entries`() {
        val sha1 = "a".repeat(40)
        val version = version("v1", "p1", sha1)
        val project = project("p1", "pack-project")
        val index = index(
            entry("mods/a.jar", sha1),
            entry("mods/server-only.jar", sha1, client = ModrinthModpackIndex.EnvSide.unsupported),
            entry("resourcepacks/a.zip", sha1, client = ModrinthModpackIndex.EnvSide.optional),
            entry("resourcepacks/copy/a.zip", sha1),
            entry("shaderpacks/a.zip", sha1, client = ModrinthModpackIndex.EnvSide.unsupported),
            entry("global_packs/data.zip", sha1),
        )

        val result = ModrinthService.resolveManifestEntries(
            index,
            mapOf(sha1 to version),
            mapOf("p1" to project),
            loader = ModLoader.neoforge,
        )

        assertEquals(2, result.mods.size)
        assertEquals(listOf("resourcepacks/a.zip", "resourcepacks/copy/a.zip"), result.clientExtras.map { it.path })
        assertEquals(false, result.clientExtras.first().required)
    }

    @Test
    fun `manifest resolver rejects absolute and traversal paths`() {
        assertFailsWith<ModpackError> {
            ModrinthService.normalizeManifestPath("C:/mods/a.jar")
        }
        assertFailsWith<ModpackError> {
            ModrinthService.normalizeManifestPath("mods/../a.jar")
        }
        assertEquals("resourcepacks/a.zip", ModrinthService.normalizeManifestPath("resourcepacks\\a.zip"))
    }

    @Test
    fun `manifest resolver encodes path spaces for mods and client extras`() {
        val rawUrl = "https://mediafilez.forgecdn.net/files/3593/685/Chocapic13 V6 Medium.zip"
        val expectedUrl = "https://mediafilez.forgecdn.net/files/3593/685/Chocapic13%20V6%20Medium.zip"
        val sha1 = "a".repeat(40)
        val result = ModrinthService.resolveManifestEntries(
            index(
                entry("mods/a.jar", sha1, download = rawUrl),
                entry("shaderpacks/Chocapic13 V6 Medium.zip", sha1, download = rawUrl),
                entry("resourcepacks/a.zip", sha1, download = rawUrl),
            ),
            mapOf(sha1 to version("v1", "p1", sha1)),
            mapOf("p1" to project("p1", "pack-project")),
            loader = ModLoader.neoforge,
        )

        assertEquals(listOf(expectedUrl), result.mods.single().downloadUrls)
        assertEquals(2, result.clientExtras.size)
        result.clientExtras.forEach { assertEquals(listOf(expectedUrl), it.downloadUrls) }
        assertEquals("shaderpacks/Chocapic13 V6 Medium.zip", result.clientExtras.first().path)
    }

    @Test
    fun `manifest resolver preserves existing escapes plus query and fragment`() {
        val urls = listOf(
            "https://cdn.example/pack%20name%2b%2B%25.zip?token=a%20b+c#part%20one",
            "https://cdn.example/pack%20name extra.zip?token=a%20b+c#part%20one",
        )
        urls.forEach { url ->
            val result = resolveShaderDownload(url)
            assertEquals(listOf(url.replace(" extra", "%20extra")), result.downloadUrls)
            // Importing an already normalized URL must not double-encode it.
            assertEquals(result.downloadUrls, resolveShaderDownload(result.downloadUrls.single()).downloadUrls)
        }
    }

    @Test
    fun `manifest resolver still rejects invalid client extra urls`() {
        listOf(
            "file:///tmp/pack name.zip",
            "ftp://cdn.example/pack name.zip",
            "https:///pack name.zip",
            "https://bad host/pack name.zip",
            "https://cdn.example/pack%ZZ name.zip",
            "https://cdn.example/pack name.zip?token=bad value",
            "https://cdn.example/pack name.zip#bad fragment",
            "https://cdn.example/pack\tname.zip",
        ).forEach { url ->
            val error = assertFailsWith<IllegalArgumentException>(url) { resolveShaderDownload(url) }
            assertContains(error.message.orEmpty(), "客户端额外内容下载地址无效")
        }
    }

    private fun resolveShaderDownload(url: String): calebxzau.rdi.common.model.Content {
        val sha1 = "a".repeat(40)
        return ModrinthService.resolveManifestEntries(
            index(entry("shaderpacks/a.zip", sha1, download = url)),
            mapOf(sha1 to version("v1", "p1", sha1)),
            mapOf("p1" to project("p1", "pack-project")),
            loader = ModLoader.neoforge,
        ).clientExtras.single()
    }

    @Test
    fun `cf fallback keeps exact path and rejects sha1 mismatch`() {
        val sha1 = "c".repeat(40)
        val entry = entry("resourcepacks/explicit.zip", sha1, download = "https://cdn.example/files/1/002/file.zip")
        val file = CurseForgeFile(
            id = 1002,
            modId = 7,
            fileName = "file.zip",
            downloadUrl = "https://cdn.example/file.zip",
            fileFingerprint = 123L,
            hashes = listOf(CurseForgeFileHash(sha1, 1)),
        )
        // The explicit MR manifest path remains authoritative for fallback entries.
        val cfProject = CurseForgeModInfo(id = 7, name = "CF Pack", slug = "cf-pack", classId = 6)
        val result = ModrinthService.resolveManifestEntries(
            index( entry ),
            emptyMap(),
            emptyMap(),
            mapOf((entry to "resourcepacks/explicit.zip") to (file to cfProject)),
            loader = ModLoader.neoforge,
        )
        assertEquals("resourcepacks/explicit.zip", result.clientExtras.single().path)
        assertEquals(listOf("https://cdn.example/files/1/002/file.zip"), result.clientExtras.single().downloadUrls)

        val mismatch = file.copy(hashes = listOf(CurseForgeFileHash("d".repeat(40), 1)))
        assertFailsWith<ModpackError> {
            ModrinthService.resolveManifestEntries(
                index(entry), emptyMap(), emptyMap(),
                mapOf((entry to "resourcepacks/explicit.zip") to (mismatch to cfProject)),
                loader = ModLoader.neoforge,
            )
        }
    }

    @Test
    fun `cf fallback encodes path spaces without double encoding`() {
        val sha1 = "c".repeat(40)
        val entry = entry("shaderpacks/Chocapic13 V6 Medium.zip", sha1,
            download = "https://mediafilez.forgecdn.net/files/3593/685/Chocapic13%20V6 Medium.zip")
        val file = CurseForgeFile(
            id = 3593685,
            modId = 7,
            fileFingerprint = 123L,
            hashes = listOf(CurseForgeFileHash(sha1, 1)),
        )
        val cfProject = CurseForgeModInfo(id = 7, name = "CF Pack", slug = "cf-pack", classId = 6552)
        val result = ModrinthService.resolveManifestEntries(
            index(entry), emptyMap(), emptyMap(),
            mapOf((entry to entry.path) to (file to cfProject)),
            loader = ModLoader.neoforge,
        )

        assertEquals(listOf("https://mediafilez.forgecdn.net/files/3593/685/Chocapic13%20V6%20Medium.zip"),
            result.clientExtras.single().downloadUrls)
    }

    @Test
    fun `unresolved supported entry names its file and same destination conflict fails`() {
        val sha1 = "e".repeat(40)
        val unresolved = entry("shaderpacks/Missing.zip", sha1)
        val error = assertFailsWith<ModpackError> {
            ModrinthService.resolveManifestEntries(
                index(unresolved), emptyMap(), emptyMap(), loader = ModLoader.neoforge,
            )
        }
        assertContains(error.message.orEmpty(), unresolved.path)

        val otherSha1 = "f".repeat(40)
        val a = entry("resourcepacks/shared.zip", sha1)
        val b = entry("resourcepacks/shared.zip", otherSha1)
        assertFailsWith<ModpackError> {
            ModrinthService.resolveManifestEntries(
                index(a, b),
                mapOf(sha1 to version("v1", "p1", sha1), otherSha1 to version("v2", "p2", otherSha1)),
                mapOf("p1" to project("p1", "first"), "p2" to project("p2", "second")),
                loader = ModLoader.neoforge,
            )
        }
    }

    private fun index(vararg entries: ModrinthModpackIndex.FileEntry) = ModrinthModpackIndex(
        formatVersion = 1,
        game = "minecraft",
        versionId = "v",
        name = "fixture",
        files = entries.toList(),
        dependencies = mapOf("minecraft" to "1.21.1", "neoforge" to "1"),
    )

    private fun entry(path: String, sha1: String, client: ModrinthModpackIndex.EnvSide = ModrinthModpackIndex.EnvSide.required, download: String = "https://example.test/file") =
        ModrinthModpackIndex.FileEntry(path, ModrinthModpackIndex.Hashes(sha1, ""), ModrinthModpackIndex.Env(client), listOf(download), 1L)

    private fun version(id: String, projectId: String, sha1: String) = ModrinthVersionInfo(
        id = id, name = "fixture", versionNumber = "1", projectId = projectId,
        files = listOf(ModrinthFileInfo("fixture.zip", "https://example.test/file", hashes = mapOf("sha1" to sha1))),
    )

    private fun project(id: String, slug: String) = ModrinthProject(
        id = id, slug = slug, title = slug, projectType = "mod", team = "team",
        published = "2026-01-01", updated = "2026-01-01",
    )
}
