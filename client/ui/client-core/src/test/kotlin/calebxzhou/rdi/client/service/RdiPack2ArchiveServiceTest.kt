package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.service.content.ClientContentMigrator
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzhou.rdi.client.service.content.ContentDigest
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.ContentSource
import calebxzhou.rdi.common.archive.forEachArchiveEntry
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.service.murmur2
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bson.types.ObjectId

class RdiPack2ArchiveServiceTest {
    @Test
    fun `exclusions retain requested player data`() {
        assertTrue(excludedRdiPack2Path("logs/latest.log"))
        assertTrue(excludedRdiPack2Path("logs", isDirectory = true))
        assertTrue(excludedRdiPack2Path("crash-reports/report.txt"))
        assertTrue(excludedRdiPack2Path("nested/debug.LOG.GZ"))
        assertFalse(excludedRdiPack2Path("options.txt"))
        assertFalse(excludedRdiPack2Path("servers.dat"))
        assertFalse(excludedRdiPack2Path("waypoints/data.json"))
        assertFalse(excludedRdiPack2Path("xaero/world.txt"))
        assertFalse(excludedRdiPack2Path("schematics/build.litematic"))
        assertFalse(excludedRdiPack2Path("resourcepacks/test.zip"))
        assertFalse(excludedRdiPack2Path("logs"))
    }

    @Test
    fun `recognized nested mod path is not flattened`() {
        assertTrue(isRdiPack2DirectCacheCandidate("mods/example_cf_123.jar"))
        assertFalse(isRdiPack2DirectCacheCandidate("mods/subdir/example_cf_123.jar"))
        assertFalse(isRdiPack2DirectCacheCandidate("mods/plain-mod.jar"))
    }

    @Test
    fun `digest plans validate all supported platforms`() {
        val root = createTempDirectory("rdipack2-digest-test").toFile()
        try {
            val source = root.resolve("mod.jar").apply { writeBytes("content".toByteArray()) }.toPath()
            val sha1 = hash(source, "SHA-1")
            val sha256 = hash(source, "SHA-256")
            val murmur = source.murmur2.toULong().toString()
            assertEquals(ContentDigestAlgorithm.MURMUR2, verifiedDigest(source, "CURSEFORGE", murmur).first.single().algorithm)
            assertEquals(ContentDigestAlgorithm.SHA1, verifiedDigest(source, "MODRINTH", sha1).first.single().algorithm)
            assertEquals(ContentDigestAlgorithm.SHA1, verifiedDigest(source, "GITHUB", sha1).first.single().algorithm)
            assertEquals(2, verifiedDigest(source, "GITHUB", sha256).first.size)
            assertEquals(ContentDigestAlgorithm.SHA1, verifiedDigest(source, "RDI_CORE", null).first.single().algorithm)
            assertFailsWith<IllegalArgumentException> { verifiedDigest(source, "MODRINTH", "0".repeat(40)) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `digest mismatches are classified separately from malformed names`() {
        val root = createTempDirectory("rdipack2-digest-mismatch-test").toFile()
        try {
            val source = root.resolve("mod.jar").apply { writeBytes("content".toByteArray()) }.toPath()
            val murmur = source.murmur2.toULong().toString()
            val wrongMurmur = if (murmur == "0") "1" else "0"
            listOf(
                "CURSEFORGE" to wrongMurmur,
                "MODRINTH" to "0".repeat(40),
                "GITHUB" to "0".repeat(40),
                "GITHUB" to "0".repeat(64),
            ).forEach { (platform, embedded) ->
                assertFailsWith<RdiPack2DigestMismatchException> {
                    verifiedDigest(source, platform, embedded)
                }
            }

            val malformedValues = listOf(
                "MODRINTH" to "not-a-sha1",
            )
            malformedValues.forEach { (platform, embedded) ->
                val malformed = assertFailsWith<IllegalArgumentException> {
                    verifiedDigest(source, platform, embedded)
                }
                assertFalse(malformed is RdiPack2DigestMismatchException)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `local materialization uses the digest cache and target link`() = runBlocking {
        val root = createTempDirectory("rdipack2-cache-test").toFile()
        try {
            val source = root.resolve("source.jar").apply { writeBytes("cached".toByteArray()) }
            val cache = root.resolve("dlc")
            val target = root.resolve("mods")
            val sha1 = hash(source.toPath(), "SHA-1")
            val request = ContentRequest(
                id = "test-local-cache",
                relativePath = "example.jar",
                size = source.length(),
                digests = listOf(ContentDigest(ContentDigestAlgorithm.SHA1, sha1)),
                sources = listOf(ContentSource(
                    knownSize = source.length(),
                    localOnly = true,
                    downloader = { destination, _ ->
                        runCatching { Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING) }
                    },
                )),
                allowNetwork = false,
            )
            val materialized = ClientContentStore(cache.toPath())
                .materialize(listOf(request), target.toPath())
                .getOrThrow()
                .single()
            assertTrue(Files.isRegularFile(materialized))
            assertTrue(Files.isSameFile(materialized, cache.resolve("$sha1.sha1").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `root parser accepts exact version and rejects unsafe roots`() {
        val id = ObjectId("0123456789abcdef01234567")
        val parsed = parseRdiPack2Root("${id}_1.0_test/")
        assertEquals(id, parsed.modpackId)
        assertEquals("1.0_test", parsed.versionName)
        assertEquals("${id}_1.0_test", parsed.rootName)
        listOf("bad/", "${id}_/", "${id}_../", "${id}_a/b/", "${id}_a:b/", "${id}_a\u0000b/").forEach { unsafe ->
            assertFailsWith<IllegalArgumentException> { parseRdiPack2Root(unsafe) }
        }
    }

    @Test
    fun `payload paths reject traversal and absolute forms`() {
        assertEquals("config/test.txt", normalizeRdiPack2PayloadPath("config\\test.txt", false))
        listOf("../escape", "/absolute", "C:/drive", "a//b", "a/./b", "a/../b", "bad\u0000name").forEach { unsafe ->
            assertFailsWith<IllegalArgumentException> { normalizeRdiPack2PayloadPath(unsafe, false) }
        }
    }

    @Test
    fun `duplicate ordinary root is ignored without accepting empty payload`() {
        val id = ObjectId("0123456789abcdef01234567")
        val identity = RdiPack2Identity(id, "1.0", "${id}_1.0")
        assertTrue(isRdiPack2DuplicateRoot("${identity.rootName}/", identity, true))
        assertFalse(isRdiPack2DuplicateRoot("${identity.rootName}/", identity, false))
        assertFalse(isRdiPack2DuplicateRoot("${identity.rootName}//", identity, true))
        assertFailsWith<IllegalArgumentException> { normalizeRdiPack2PayloadPath("", true) }
    }

    @Test
    fun `export writes root first and reports concrete progress`() = runBlocking {
        val root = createTempDirectory("rdipack2-export-test").toFile()
        try {
            val id = ObjectId("0123456789abcdef01234567")
            val source = root.resolve("${id}_1.0").apply { mkdirs() }
            source.resolve("config").mkdirs()
            source.resolve("emptyDir").mkdirs()
            source.resolve("config/example.txt").writeText("example")
            source.resolve("logs").mkdirs()
            source.resolve("logs/latest.log").writeText("excluded")
            val output = root.resolve("renamed.rdipack2")
            val progress = mutableListOf<calebxzhou.rdi.common.model.Task2Progress>()
            exportRdiPack2(
                ModpackLocalDir(source, "1.0", Modpack.BriefVo(id = id), 0L),
                output,
                Task2Context { progress += it },
            )
            assertEquals("正在扫描整合包文件…", progress.first().message)
            val generationIndex = progress.indexOfFirst { it.message == "正在生成整合包" }
            assertTrue(generationIndex > 0)
            assertFalse(progress.any { it.message.startsWith("正在准备 ") })
            assertEquals(0f, progress[generationIndex].fraction)
            assertTrue(progress.any { it.message == "正在写入 config/example.txt" })
            assertTrue(progress.zipWithNext().all { (a, b) -> (a.fraction ?: 0f) <= (b.fraction ?: 0f) })
            assertTrue(progress.all { it.fraction?.let { fraction -> fraction in 0f..1f } ?: true })
            assertEquals(1f, progress.last().fraction)
            val entries = mutableListOf<String>()
            forEachArchiveEntry(output) { entries += it.path }
            assertEquals("${id}_1.0/", entries.first())
            assertEquals(1, entries.count { it == "${id}_1.0/" })
            assertTrue(entries.drop(1).all { it.startsWith("${id}_1.0/") })
            assertTrue(entries.contains("${id}_1.0/emptyDir/"))
            assertFalse(entries.any { it.endsWith("latest.log") })
            assertEquals(ObjectId(id.toHexString()), readRdiPack2Identity(output).modpackId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `export rejects symbolic links inside excluded directories`() = runBlocking {
        val root = createTempDirectory("rdipack2-export-symlink-exclusion-test").toFile()
        try {
            val id = ObjectId("0123456789abcdef01234567")
            val source = root.resolve("${id}_1.0").apply { mkdirs() }
            val logs = source.resolve("logs").apply { mkdirs() }
            val target = source.resolve("target.txt").apply { writeText("target") }.toPath()
            val link = logs.resolve("link.txt").toPath()
            if (runCatching { Files.createSymbolicLink(link, target) }.isFailure) return@runBlocking

            assertFailsWith<IllegalArgumentException> {
                exportRdiPack2(
                    ModpackLocalDir(source, "1.0", Modpack.BriefVo(id = id), 0L),
                    root.resolve("output.rdipack2"),
                    Task2Context {},
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `limits and collision keys are bounded safely`() {
        requireRdiPack2PayloadWithinLimits(100_000, 20L * 1024L * 1024L * 1024L)
        assertFailsWith<IllegalArgumentException> { requireRdiPack2PayloadWithinLimits(100_001, 0) }
        assertFailsWith<IllegalArgumentException> { requireRdiPack2PayloadWithinLimits(0, 20L * 1024L * 1024L * 1024L + 1) }
        requireRdiPack2PayloadAdditionWithinLimits(100_000 - 1, 0, 1, 0)
        assertFailsWith<IllegalArgumentException> { requireRdiPack2PayloadAdditionWithinLimits(100_000, 0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { requireRdiPack2PayloadAdditionWithinLimits(0, 0, 1, Long.MAX_VALUE) }
        assertEquals(rdiPack2CollisionKey("Config/Example.txt"), rdiPack2CollisionKey("config/example.txt"))
        assertEquals(3L, addRdiPack2PayloadBytes(1L, 2L))
        assertFailsWith<IllegalArgumentException> { addRdiPack2PayloadBytes(Long.MAX_VALUE, 1L) }
        assertFailsWith<IllegalArgumentException> { addRdiPack2PayloadBytes(20L * 1024L * 1024L * 1024L, 1L) }
    }

    @Test
    fun `collision tracker rejects ancestor case changes but allows same spelling`() {
        val tracker = RdiPack2CollisionTracker()
        tracker.register("Config/a.txt", false)
        tracker.register("Config/b.txt", false)
        assertFailsWith<IllegalArgumentException> { tracker.register("config/c.txt", false) }
    }

    @Test
    fun `export channel refuses symbolic links when supported`() {
        val root = createTempDirectory("rdipack2-symlink-test").toFile()
        try {
            val source = root.resolve("source.txt").apply { writeText("source") }.toPath()
            val link = root.resolve("link.txt").toPath()
            if (runCatching { Files.createSymbolicLink(link, source) }.isFailure) return
            assertFailsWith<Exception> { openRdiPack2ExportChannel(link).use { } }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun hash(path: java.nio.file.Path, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
