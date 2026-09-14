package calebxzhou.rdi.common.service

import kotlinx.coroutines.runBlocking
import calebxzhou.rdi.common.exception.ModpackError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains
import calebxzhou.rdi.common.model.*

class ModrinthServiceTest {
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

        val result = ModrinthService.resolveManifestEntries(index, mapOf(sha1 to version), mapOf("p1" to project))

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
        )
        assertEquals("resourcepacks/explicit.zip", result.clientExtras.single().path)
        assertEquals(listOf("https://cdn.example/files/1/002/file.zip"), result.clientExtras.single().downloadUrls)

        val mismatch = file.copy(hashes = listOf(CurseForgeFileHash("d".repeat(40), 1)))
        assertFailsWith<ModpackError> {
            ModrinthService.resolveManifestEntries(
                index(entry), emptyMap(), emptyMap(),
                mapOf((entry to "resourcepacks/explicit.zip") to (mismatch to cfProject)),
            )
        }
    }

    @Test
    fun `unresolved supported entry names its file and same destination conflict fails`() {
        val sha1 = "e".repeat(40)
        val unresolved = entry("shaderpacks/Missing.zip", sha1)
        val error = assertFailsWith<ModpackError> {
            ModrinthService.resolveManifestEntries(index(unresolved), emptyMap(), emptyMap())
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
