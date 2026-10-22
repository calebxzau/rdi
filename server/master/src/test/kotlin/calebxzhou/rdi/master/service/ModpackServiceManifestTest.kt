package calebxzhou.rdi.master.service

import calebxzau.rdi.common.model.ModpackFileManifest
import calebxzau.rdi.common.model.ModpackFileManifest.Side
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.archive.forEachArchiveEntry
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.master.service.modpack.ModpackManifestService
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModpackServiceManifestTest {
    @Test
    fun `client manifest hashes client pack files and full manifest hashes uploaded files`() {
        val pack = ModpackServiceTestFixtures.modpack()
        val version = ModpackServiceTestFixtures.version(pack, "manifest")
        val withWorld = jar("pack/r.0.0.mca" to byteArrayOf(3), "com/x/A.class" to byteArrayOf(4))
        try {
            version.storageDir.mkdirs()
            TarZstArchiveWriter(version.zstdPack).use { writer ->
                writer.addFile("manifest.json", "{}".toByteArray())
                writer.addFile("overrides/config/common.json", "common".toByteArray())
                writer.addFile("overrides/config/shared.json", "common".toByteArray())
                writer.addFile("client-overrides/config/shared.json", "client".toByteArray())
                writer.addFile("overrides/mods/with-world.jar", withWorld)
                writer.addFile("overrides/mods/${CLIENT_ONLY_MARK_PREFIX}menu.jar", byteArrayOf(5))
                writer.addFile("overrides/textures/a.png", byteArrayOf(6))
                writer.addFile("overrides/world/region/r.0.0.mca", byteArrayOf(7))
            }
            ModpackService.buildClientPackForTest(version)
            ModpackManifestService.generate(version).getOrThrow()

            val clientBytes = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(version.clientZstdPack) { entry ->
                if (!entry.isDirectory) clientBytes[entry.path] = entry.bytes ?: byteArrayOf()
            }
            val client = runBlocking { ModpackManifestService.clientManifest(version) }
            assertEquals(version.name, client.version)
            assertEquals(clientBytes.keys, client.files.map { it.path }.toSet())
            client.files.forEach { file ->
                assertEquals(clientBytes.getValue(file.path).sha1, file.sha1, file.path)
                assertEquals(clientBytes.getValue(file.path).size.toLong(), file.size, file.path)
                assertEquals(null, file.side)
            }
            assertEquals("client".toByteArray().sha1, client.file("config/shared.json").sha1)
            assertNotEquals(withWorld.sha1, client.file("mods/with-world.jar").sha1)

            val full = runBlocking { ModpackManifestService.fullManifest(version) }
            assertEquals(withWorld.sha1, full.file("overrides/mods/with-world.jar").sha1)
            assertEquals(Side.Neither, full.file("manifest.json").side)
            assertEquals(Side.Both, full.file("overrides/config/common.json").side)
            assertEquals(Side.ServerOnly, full.file("overrides/config/shared.json").side)
            assertEquals(Side.ClientOnly, full.file("client-overrides/config/shared.json").side)
            assertEquals(Side.Both, full.file("overrides/mods/with-world.jar").side)
            assertEquals(Side.ClientOnly, full.file("overrides/mods/${CLIENT_ONLY_MARK_PREFIX}menu.jar").side)
            assertEquals(Side.ClientOnly, full.file("overrides/textures/a.png").side)
            assertEquals(Side.ServerOnly, full.file("overrides/world/region/r.0.0.mca").side)
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `server root keeps overrides off the host`() {
        val sides = ModpackManifestService.resolveSides(
            fullPaths = listOf("server/config/a.cfg", "overrides/config/b.cfg", "server/mods/x-rgp-client.jar"),
            clientPaths = listOf("config/b.cfg"),
        )
        assertEquals(listOf(Side.ServerOnly, Side.ClientOnly, Side.Neither), sides)
    }

    @Test
    fun `an empty server directory still picks the server root`() {
        val sides = ModpackManifestService.resolveSides(
            fullPaths = listOf("overrides/config/b.cfg"),
            clientPaths = listOf("config/b.cfg"),
            entryPaths = listOf("server/config", "overrides/config/b.cfg"),
        )
        assertEquals(listOf(Side.ClientOnly), sides)
    }

    @Test
    fun `missing or outdated manifests are regenerated on read`() {
        val pack = ModpackServiceTestFixtures.modpack()
        val version = ModpackServiceTestFixtures.version(pack, "manifest-lazy")
        try {
            version.storageDir.mkdirs()
            TarZstArchiveWriter(version.zstdPack).use { it.addFile("overrides/config/a.json", "1".toByteArray()) }
            ModpackService.buildClientPackForTest(version)
            assertFalse(version.clientManifestFile.exists())

            val first = runBlocking { ModpackManifestService.clientManifest(version) }
            assertEquals("1".toByteArray().sha1, first.file("config/a.json").sha1)
            assertTrue(version.fullManifestFile.exists())

            TarZstArchiveWriter(version.zstdPack).use { it.addFile("overrides/config/a.json", "2".toByteArray()) }
            ModpackService.buildClientPackForTest(version)
            val manifestTime = version.clientPackFile.lastModified() - 10_000
            version.clientManifestFile.setLastModified(manifestTime)
            version.fullManifestFile.setLastModified(manifestTime)

            val second = runBlocking { ModpackManifestService.clientManifest(version) }
            assertEquals("2".toByteArray().sha1, second.file("config/a.json").sha1)
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `manifest of an unfinished version is refused`() {
        val pack = ModpackServiceTestFixtures.modpack()
        val version = ModpackServiceTestFixtures.version(pack, "manifest-building", status = Modpack.Status.BUILDING)
        try {
            assertFailsWith<RequestError> { runBlocking { ModpackManifestService.clientManifest(version) } }
            assertFalse(version.clientManifestFile.exists())
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    private fun ModpackFileManifest.file(path: String) = files.single { it.path == path }

    private fun jar(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}
