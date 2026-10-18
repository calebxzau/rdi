package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.Modpack
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostContainerServiceTest {
    @Test
    fun `cleanup removes unknown zero byte jars and preserves known files`() = withModsDir { modsDir ->
        val versionMod = mod("version-mod")
        val extraMod = mod("extra-mod")
        val version = version(versionMod)
        val host = host(extraMods = listOf(extraMod))

        listOf(versionMod, extraMod).flatMap { it.fileNames }.forEach { fileName ->
            modsDir.resolve(fileName).writeBytes(byteArrayOf())
        }
        val unknown = modsDir.resolve("unknown.JAR").apply { writeBytes(byteArrayOf()) }
        val nonEmpty = modsDir.resolve("non-empty.jar").apply { writeBytes(byteArrayOf(1)) }
        val nonJar = modsDir.resolve("unknown.txt").apply { writeBytes(byteArrayOf()) }
        val nested = modsDir.resolve("nested").apply { mkdirs() }
            .resolve("nested.jar").apply { writeBytes(byteArrayOf()) }
        val directory = modsDir.resolve("directory.jar").apply { mkdirs() }

        cleanup(host, version, modsDir)

        listOf(versionMod, extraMod).flatMap { it.fileNames }.forEach { fileName ->
            assertTrue(modsDir.resolve(fileName).exists())
        }
        assertFalse(unknown.exists())
        assertTrue(nonEmpty.exists())
        assertTrue(nonJar.exists())
        assertTrue(nested.exists())
        assertTrue(directory.exists())
    }

    @Test
    fun `cleanup removes all packsync jars including non-empty files and symlinks`() = withModsDir { modsDir ->
        val blocked = modsDir.resolve("packsync-1.0.0.jar").apply { writeBytes(byteArrayOf(1)) }
        val blockedUppercase = modsDir.resolve("PackSync-custom.JAR").apply { writeBytes(byteArrayOf(1)) }
        val target = modsDir.resolve("target.jar").apply { writeBytes(byteArrayOf(1)) }
        val blockedLink = modsDir.resolve("packsync-link.jar")
        val linkCreated = runCatching {
            Files.createSymbolicLink(blockedLink.toPath(), target.toPath())
        }.isSuccess

        cleanup(host(), version(), modsDir)

        assertFalse(blocked.exists())
        assertFalse(blockedUppercase.exists())
        if (linkCreated) assertFalse(blockedLink.exists())
        assertTrue(target.exists())
    }

    @Test
    fun `cleanup removes disabled aliases but preserves allowed names containing disabled slug`() = withModsDir { modsDir ->
        val disabledMod = mod("disabled-mod")
        val enabledMod = mod("disabled-mod-addon")
        val extraMod = mod("disabled-mod-extra")
        val version = version(disabledMod, enabledMod)
        val host = host(extraMods = listOf(extraMod), disabledMods = listOf(disabledMod))
        listOf(disabledMod, enabledMod, extraMod).flatMap { it.fileNames }.forEach { fileName ->
            modsDir.resolve(fileName).writeBytes(byteArrayOf())
        }

        cleanup(host, version, modsDir)

        disabledMod.fileNames.forEach { fileName ->
            assertFalse(modsDir.resolve(fileName).exists())
        }
        listOf(enabledMod, extraMod).flatMap { it.fileNames }.forEach { fileName ->
            assertTrue(modsDir.resolve(fileName).exists())
        }
    }

    @Test
    fun `cleanup rejects a symlinked mods directory without touching its target`() {
        val root = Files.createTempDirectory("host-mod-symlink").toFile()
        try {
            val target = root.resolve("target").apply { mkdirs() }
            val unknown = target.resolve("unknown.jar").apply { writeBytes(byteArrayOf()) }
            val link = root.resolve("mods-link").toPath()
            val created = runCatching {
                Files.createSymbolicLink(link, target.toPath())
            }.isSuccess
            if (created) {
                assertFailsWith<RequestError> {
                    cleanup(host(), version(), link.toFile())
                }
                assertTrue(unknown.exists())
                assertTrue(Files.isSymbolicLink(link))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun cleanup(host: Host, version: Modpack.Version, modsDir: File) {
        HostContainerService.run {
            host.cleanupModFilesBeforeContainerCreate(version, modsDir)
        }
    }

    private fun mod(slug: String) = Mod(
        platform = "cf",
        projectId = slug,
        slug = slug,
        fileId = "file-$slug",
        hash = "hash-$slug",
    )

    private fun version(vararg mods: Mod) = Modpack.Version(
        time = 0L,
        modpackId = ObjectId(),
        name = "1.0.0",
        changelog = "",
        status = Modpack.Status.OK,
        mods = mods.toMutableList(),
    )

    private fun host(
        extraMods: List<Mod> = emptyList(),
        disabledMods: List<Mod> = emptyList(),
    ) = Host(
        name = "test-host",
        ownerId = ObjectId(),
        modpackId = ObjectId(),
        port = 25565,
        difficulty = 2,
        gameMode = 0,
        levelType = "default",
        extraMods = extraMods,
        disabledMods = disabledMods,
    )

    private fun withModsDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("host-mod-cleanup").toFile()
        try {
            block(root.resolve("mods").apply { mkdirs() })
        } finally {
            root.deleteRecursively()
        }
    }
}
