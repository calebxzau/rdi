package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.archive.listArchiveEntries
import calebxzhou.rdi.common.archive.forEachArchiveEntry
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModpackServiceArchiveTest {
    @Test
    fun `client relative path accepts overrides and gtnh only`() {
        assertEqualsPath("config/a", ModpackService.extractClientPackRelativePathForTest("overrides/config/a"))
        assertEqualsPath("gtnh/config/a", ModpackService.extractClientPackRelativePathForTest("gtnh/config/a"))
        assertEqualsPath("resourcepacks/a.zip", ModpackService.extractClientPackRelativePathForTest("resourcepacks/a.zip"))
        assertEqualsPath("shaderpacks/a.zip.txt", ModpackService.extractClientPackRelativePathForTest("shaderpacks/a.zip.txt"))
        assertFalse(ModpackService.extractClientPackRelativePathForTest("server/config/a") != null)
        assertFalse(ModpackService.extractClientPackRelativePathForTest("config/a") != null)
    }

    @Test
    fun `host filters skip client markers rgp assets and world only when requested`() {
        assertTrue(ModpackService.isClientOnlyMarkedModPathForTest("mods/${CLIENT_ONLY_MARK_PREFIX}client.jar"))
        assertFalse(ModpackService.isClientOnlyMarkedModPathForTest("config/${CLIENT_ONLY_MARK_PREFIX}client.jar"))
        assertTrue(ModpackService.shouldSkipHostClientOnlyJarForTest("mods/example-rgp-client.jar"))
        assertFalse(ModpackService.shouldSkipHostClientOnlyJarForTest("mods/example.jar"))
    }

    @Test
    fun `client archive is built as tar zst with filters`() {
        val pack = ModpackServiceTestFixtures.modpack()
        val version = ModpackServiceTestFixtures.version(pack, "client-filter").copy(
            clientExtras = mutableListOf(
                Content(
                    platform = ContentPlatform.Modrinth,
                    type = ContentType.ShaderPack,
                    projectId = "shader-project",
                    fileId = "shader-file",
                    slug = "matched",
                    hash = "0123456789012345678901234567890123456789",
                    path = "shaderpacks/matched.zip",
                    side = ContentSide.Client,
                )
            )
        )
        try {
            version.storageDir.mkdirs()
            TarZstArchiveWriter(version.zstdPack).use { writer ->
                writer.addFile("overrides/config/keep.json", byteArrayOf(1))
                writer.addFile("resourcepacks/direct.zip", byteArrayOf(6))
                writer.addFile("overrides/resourcepacks/direct.zip", byteArrayOf(7))
                writer.addFile("overrides/shaderpacks/ignored.zip", byteArrayOf(2))
                writer.addFile("overrides/shaderpacks/matched.zip.txt", "shader-options".toByteArray())
                writer.addFile("overrides/shaderpacks/unmatched.txt", "drop".toByteArray())
                writer.addFile("shaderpacks/matched.zip.txt", "shader-options-direct".toByteArray())
                writer.addFile("shaderpacks/unmatched.zip.txt", "drop".toByteArray())
                writer.addFile("overrides/world/region/r.0.0.mca", byteArrayOf(3))
                writer.addFile("gtnh/config/keep.cfg", byteArrayOf(4))
                writer.addFile("server/config/not-client.cfg", byteArrayOf(5))
            }
            ModpackService.buildClientPackForTest(version)
            val entries = listArchiveEntries(version.clientZstdPack).map { it.path }.toSet()
            val copiedBytes = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(version.clientZstdPack) { entry ->
                if (!entry.isDirectory) copiedBytes[entry.path] = entry.bytes ?: byteArrayOf()
            }
            assertTrue("config/keep.json" in entries)
            assertTrue("gtnh/config/keep.cfg" in entries)
            assertTrue("resourcepacks/direct.zip" in entries)
            assertContentEquals(byteArrayOf(7), copiedBytes["resourcepacks/direct.zip"])
            assertTrue("shaderpacks/matched.zip.txt" in entries)
            assertFalse("shaderpacks/ignored.zip" in entries)
            assertFalse("shaderpacks/unmatched.txt" in entries)
            assertFalse(copiedBytes.keys.any { it.startsWith("shaderpacks/") && !it.endsWith(".zip.txt") })
            assertFalse(entries.any { it.endsWith(".mca") })
            assertFalse(entries.any { it.startsWith("server/") })
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `empty client source deletes generated output and zip migrates`() {
        val pack = ModpackServiceTestFixtures.modpack()
        val emptyVersion = ModpackServiceTestFixtures.version(pack, "empty-client")
        val migrationVersion = ModpackServiceTestFixtures.version(pack, "zip-migration")
        try {
            emptyVersion.storageDir.mkdirs()
            TarZstArchiveWriter(emptyVersion.zstdPack).use { it.addFile("server/only.txt", byteArrayOf(1)) }
            ModpackService.buildClientPackForTest(emptyVersion)
            assertFalse(emptyVersion.clientZstdPack.exists())

            migrationVersion.storageDir.mkdirs()
            ZipOutputStream(migrationVersion.zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("overrides/config/a.txt"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
            ModpackService.upgradeFullPackArchiveForTest(migrationVersion)
            assertTrue(migrationVersion.zstdPack.exists())
            assertFalse(migrationVersion.zip.exists())
            assertTrue(listArchiveEntries(migrationVersion.zstdPack).any { it.path == "overrides/config/a.txt" })
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `archive extraction rejects traversal`() {
        val root = ModpackServiceTestFixtures.tempRoot()
        val target = root.resolve("target")
        val archive = root.resolve("unsafe.tar.zst")
        try {
            TarZstArchiveWriter(archive).use { it.addFile("overrides/../escape.txt", byteArrayOf(1)) }
            assertFailsWith<Exception> { ModpackService.unzipOverridesForTest(archive, target) }
            assertFalse(root.resolve("escape.txt").exists())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    private fun assertEqualsPath(expected: String, actual: String?) {
        kotlin.test.assertEquals(expected, actual)
    }
}
