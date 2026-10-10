package calebxzau.rdi.client.packproc

import calebxzhou.rdi.common.archive.ArchiveReadLimits
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerPackSourceTest {
    @Test
    fun `flat and wrapped ZIP retain relative paths and source archive`() = runBlocking {
        withWorkspace { workspace ->
            for (prefix in listOf("", "服务端/")) {
                val archive = workspace.resolve("server.ZIP")
                writeZip(archive, mapOf("${prefix}mods/example.jar" to "mod", "${prefix}config/test.toml" to "enabled=true"))
                val original = archive.readBytes()
                val root = prepareServerPackDirectory(archive, workspace.resolve("work"), {})
                assertEquals("mod", root.resolve("mods/example.jar").readText())
                assertEquals("enabled=true", root.resolve("config/test.toml").readText())
                assertTrue(original.contentEquals(archive.readBytes()))
            }
        }
    }

    @Test
    fun `reselection keeps previous extracted files available`() = runBlocking {
        withWorkspace { workspace ->
            val archive = workspace.resolve("server.zip")
            writeZip(archive, mapOf("mods/example.jar" to "first"))
            val first = prepareServerPackDirectory(archive, workspace.resolve("work"), {})
            writeZip(archive, mapOf("mods/example.jar" to "second"))
            val second = prepareServerPackDirectory(archive, workspace.resolve("work"), {})
            assertEquals("first", first.resolve("mods/example.jar").readText())
            assertEquals("second", second.resolve("mods/example.jar").readText())
        }
    }

    @Test
    fun `missing deep or ambiguous server roots are rejected`() = runBlocking {
        withWorkspace { workspace ->
            val work = workspace.resolve("work")
            for (entries in listOf(
                mapOf("readme.txt" to "missing mods"),
                mapOf("a/b/mods/example.jar" to "nested too deep"),
                mapOf("a/mods/example.jar" to "a", "b/mods/example.jar" to "b"),
                mapOf("mods/example.jar" to "root", "a/mods/example.jar" to "nested"),
            )) {
                val archive = workspace.resolve("server.zip")
                writeZip(archive, entries)
                assertFailsWith<ModpackError> { prepareServerPackDirectory(archive, work, {}) }
                assertTrue(work.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `corrupt ZIP and non ZIP input are rejected`() = runBlocking {
        withWorkspace { workspace ->
            for (name in listOf("bad.zip", "server.jar", "missing.zip")) {
                val source = workspace.resolve(name)
                if (name != "missing.zip") source.writeText("not a ZIP")
                val result = ModpackProcessor(PackProcessingPaths(workspace.resolve("work")))
                    .loadServerPack(source, emptyList(), emptyMap(), {})
                assertTrue(result.isFailure)
                assertTrue(workspace.resolve("work").listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `unsafe duplicate and conflicting paths are rejected without escaped writes`() = runBlocking {
        withWorkspace { workspace ->
            val work = workspace.resolve("work")
            for (entries in listOf(
                mapOf("../escaped.txt" to "bad"),
                mapOf("/absolute.txt" to "bad"),
                mapOf("C:/absolute.txt" to "bad"),
                mapOf("mods/example.jar" to "a", "mods/./example.jar" to "b"),
                mapOf("mods/example.jar" to "a", "mods/EXAMPLE.jar" to "b"),
                mapOf("mods" to "file", "mods/example.jar" to "child"),
                mapOf("mods/example.jar" to "child", "mods" to "file"),
            )) {
                val archive = workspace.resolve("server.zip")
                writeZip(archive, entries)
                assertFailsWith<IllegalArgumentException> { prepareServerPackDirectory(archive, work, {}) }
                assertTrue(work.listFiles()!!.isEmpty())
                assertFalse(workspace.resolve("escaped.txt").exists())
            }
        }
    }

    @Test
    fun `expanded byte and entry limits reject ZIP and clean partial extraction`() = runBlocking {
        withWorkspace { workspace ->
            val archive = workspace.resolve("server.zip")
            writeZip(archive, mapOf("mods/a.jar" to "1234", "mods/b.jar" to "5678"))
            val work = workspace.resolve("work")
            for (limits in listOf(
                ArchiveReadLimits(maxEntryBytes = 3),
                ArchiveReadLimits(maxTotalBytes = 7),
                ArchiveReadLimits(maxEntries = 1),
            )) {
                assertFailsWith<IllegalArgumentException> { prepareServerPackDirectory(archive, work, {}, limits) }
                assertTrue(work.listFiles()!!.isEmpty())
            }
        }
    }

    @Test
    fun `cancellation cleans partial extraction`() = runBlocking {
        withWorkspace { workspace ->
            val archive = workspace.resolve("server.zip")
            writeZip(archive, mapOf("mods/example.jar" to "mod"))
            val work = workspace.resolve("work")
            assertFailsWith<CancellationException> {
                prepareServerPackDirectory(archive, work, { throw CancellationException("cancelled") })
            }
            assertTrue(work.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun `Bukkit family markers are checked in flat and wrapped ZIP roots`() = runBlocking {
        withWorkspace { workspace ->
            val processor = ModpackProcessor(PackProcessingPaths(workspace.resolve("work")))
            for (prefix in listOf("", "server/")) {
                for (marker in listOf("spigot.yml", "arclight.conf", "bukkit.yml")) {
                    val archive = workspace.resolve("server.zip")
                    writeZip(archive, mapOf("${prefix}mods/example.jar" to "mod", "${prefix}${marker}" to ""))
                    val result = processor.loadServerPack(archive, emptyList(), emptyMap(), {})
                    assertEquals("请选择正确安装了模组载入器的服务端", assertIs<ModpackError>(result.exceptionOrNull()).message)
                }
            }
        }
    }

    @Test
    fun `wrapped ZIP matches client mods and keeps extras readable for upload`() = runBlocking {
        withWorkspace { workspace ->
            val clientJar = workspace.resolve("client.jar")
            writeZip(clientJar, mapOf("mcmod.info" to "[{\"modid\":\"shared\"}]"))
            val archive = workspace.resolve("server.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((name, bytes) in mapOf(
                    "server/mods/shared.jar" to clientJar.readBytes(),
                    "server/config/test.toml" to "enabled=true".toByteArray(),
                    "server/server.properties" to "level-name=world".toByteArray(),
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val clientMod = Mod("mr", "project", "shared", "file", clientJar.sha1)
            val loaded = ModpackProcessor(PackProcessingPaths(workspace.resolve("work")))
                .loadServerPack(archive, listOf(clientMod), mapOf(clientMod to clientJar), {}).getOrThrow()
            assertEquals(1, loaded.mods.size)
            val extras = loaded.serverExtraFiles.associateBy { it.relativePath }
            assertEquals(setOf("config/test.toml", "server.properties"), extras.keys)
            assertEquals("enabled=true", extras.getValue("config/test.toml").sourceFile.readText())
            assertEquals("level-name=world", extras.getValue("server.properties").sourceFile.readText())
        }
    }

    private suspend fun withWorkspace(block: suspend (File) -> Unit) {
        val workspace = Files.createTempDirectory("server-pack-source-test-").toFile()
        try {
            block(workspace)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
