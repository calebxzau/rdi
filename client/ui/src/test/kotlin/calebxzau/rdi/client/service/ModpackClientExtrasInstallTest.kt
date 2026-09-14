package calebxzau.rdi.client.service

import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzhou.rdi.client.service.content.ContentDigest
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.client.service.ModpackService
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModpackClientExtrasInstallTest {
    @Test
    fun `built install extracts before extras and keeps local override`() = runBlocking {
        val root = Files.createTempDirectory("client-extras-built").toFile()
        try {
            val archive = writeZip(root.resolve("client.zip"), "overrides/resourcepacks/same.zip" to "local".toByteArray())
            val cache = root.resolve("cache").apply { mkdirs() }
            val missing = "missing-resource".toByteArray()
            val shader = "missing-shader".toByteArray()
            Files.write(cache.resolve("${missing.sha1}.sha1").toPath(), missing)
            Files.write(cache.resolve("${shader.sha1}.sha1").toPath(), shader)
            val target = root.resolve("instance")
            val same = extra("resourcepacks/same.zip", "a".repeat(40))
            val resource = extra("resourcepacks/missing.zip", missing.sha1)
            val shaderExtra = extra("shaderpacks/missing.zip", shader.sha1, ContentType.ShaderPack)
            val store = ClientContentStore(cache.toPath())
            val tasks = ModpackService.createInstallClientZipTasks2(
                mcVersion = calebxzhou.rdi.common.model.McVersion.V201,
                modLoader = calebxzhou.rdi.common.model.ModLoader.forge,
                modpackId = org.bson.types.ObjectId(),
                verName = "test",
                mods = emptyList(),
                clientExtras = listOf(same, resource, shaderExtra),
                clientPackProvider = { archive },
                versionDir = target,
                contentStore = store,
            )
            tasks.forEach { task ->
                if (task.title == "补充资源包和光影包") assertFalse(target.resolve("options.txt").exists())
                (task as Task2.Leaf).action(Task2Context(emitProgress = {}))
            }
            assertContentEquals("local".toByteArray(), target.resolve("resourcepacks/same.zip").readBytes())
            assertContentEquals(missing, target.resolve("resourcepacks/missing.zip").readBytes())
            assertContentEquals(shader, target.resolve("shaderpacks/missing.zip").readBytes())
            assertTrue(target.resolve("config/fancymenu/options.txt").isFile)
            assertTrue(target.resolve("options.txt").readText().contains("lang:zh_cn"))
            assertFalse(target.resolve("mods/missing.zip").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `normal install resolves archive from cache and places extras at roots`() = runBlocking {
        val root = Files.createTempDirectory("client-extras-normal").toFile()
        try {
            val archive = writeZip(root.resolve("client.zip"), "overrides/config/test.txt" to byteArrayOf(1))
            val archiveHash = archive.readBytes().sha1
            val cache = root.resolve("cache").apply { mkdirs() }
            Files.copy(archive.toPath(), cache.resolve("$archiveHash.sha1").toPath())
            val bytes = "cached-shader".toByteArray()
            Files.write(cache.resolve("${bytes.sha1}.sha1").toPath(), bytes)
            val target = root.resolve("instance")
            val request = ContentRequest(
                id = "archive",
                relativePath = "client.zip",
                digests = listOf(ContentDigest(ContentDigestAlgorithm.SHA1, archiveHash)),
            )
            val tasks = ModpackService.createInstallClientZipTasks2(
                mcVersion = calebxzhou.rdi.common.model.McVersion.V201,
                modLoader = calebxzhou.rdi.common.model.ModLoader.forge,
                modpackId = org.bson.types.ObjectId(),
                verName = "test",
                mods = emptyList(),
                clientExtras = listOf(extra("shaderpacks/cached.zip", bytes.sha1, ContentType.ShaderPack)),
                clientPackProvider = null,
                clientPackRequestProvider = { request },
                versionDir = target,
                contentStore = ClientContentStore(cache.toPath()),
            )
            tasks.forEach { task -> (task as Task2.Leaf).action(Task2Context(emitProgress = {})) }
            assertContentEquals(bytes, target.resolve("shaderpacks/cached.zip").readBytes())
            assertTrue(target.resolve("config/fancymenu/options.txt").isFile)
            assertTrue(target.resolve("options.txt").readText().contains("lang:zh_cn"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `install with empty client extras keeps base archive without supplemental files`(): Unit = runBlocking {
        val root = Files.createTempDirectory("client-extras-empty").toFile()
        try {
            val archive = writeZip(root.resolve("client.zip"), "overrides/config/test.txt" to byteArrayOf(1))
            val target = root.resolve("instance")
            val tasks = ModpackService.createInstallClientZipTasks2(
                mcVersion = calebxzhou.rdi.common.model.McVersion.V201,
                modLoader = calebxzhou.rdi.common.model.ModLoader.forge,
                modpackId = org.bson.types.ObjectId(),
                verName = "test",
                mods = emptyList(),
                clientExtras = emptyList(),
                clientPackProvider = { archive },
                versionDir = target,
                contentStore = ClientContentStore(root.resolve("cache").apply { mkdirs() }.toPath()),
            )

            tasks.forEach { task -> (task as Task2.Leaf).action(Task2Context(emitProgress = {})) }

            assertContentEquals(byteArrayOf(1), target.resolve("config/test.txt").readBytes())
            assertFalse(target.resolve("resourcepacks").exists())
            assertFalse(target.resolve("shaderpacks").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun extra(path: String, hash: String, type: ContentType = ContentType.ResPack) = Content(
        platform = ContentPlatform.Modrinth,
        type = type,
        projectId = "project",
        fileId = "file",
        slug = "extra",
        hash = hash,
        path = path,
        side = ContentSide.Client,
    )

    private fun writeZip(target: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }
}
