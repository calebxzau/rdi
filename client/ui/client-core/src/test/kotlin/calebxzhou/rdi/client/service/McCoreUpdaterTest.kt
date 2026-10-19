package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzhou.rdi.client.service.content.ContentSource
import calebxzhou.rdi.common.net.DownloadProgress
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McCoreUpdaterTest {
    @Test
    fun `materialize core forwards structured content progress`() = runBlocking {
        val root = Files.createTempDirectory("core-progress").toFile()
        val payload = "core-payload".toByteArray()
        try {
            val sourceFile = root.resolve("source.jar").apply { writeBytes(payload) }
            val request = McCoreUpdater.contentRequest(
                mcVersion = calebxzhou.rdi.common.model.McVersion.V201,
                modLoader = calebxzhou.rdi.common.model.ModLoader.forge,
                expectedSha1 = sourceFile.sha1,
            ).copy(
                allowNetwork = false,
                sources = listOf(
                    ContentSource(
                        localOnly = true,
                        downloader = { target, onProgress ->
                            Files.write(target, payload)
                            onProgress(DownloadProgress(payload.size.toLong(), payload.size.toLong(), 0.0))
                            Result.success(target)
                        },
                    )
                ),
            )
            val progress = mutableListOf<calebxzhou.rdi.common.model.Task2Progress>()
            val result = McCoreUpdater.materializeCore(
                update = McCoreUpdateResult(updated = true, contentRequest = request),
                modsDir = root.resolve("mods"),
                onDetail = {},
                onProgress = progress::add,
                contentStore = ClientContentStore(root.resolve("dlc").toPath()),
            ).getOrThrow()

            assertTrue(result)
            assertTrue(progress.any {
                it.completedItems == 1 && it.totalItems == 1 &&
                    it.completedBytes == payload.size.toLong()
            })
            assertEquals(payload.toList(), root.resolve("mods").resolve(request.relativePath).readBytes().toList())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `core replacement swaps instance file without mutating its old inode`() {
        val root = Files.createTempDirectory("core-atomic-replace").toFile()
        try {
            val target = root.resolve("mods/core.jar").apply {
                parentFile.mkdirs()
                writeText("old")
            }
            val oldLink = root.resolve("old-link.jar")
            Files.createLink(oldLink.toPath(), target.toPath())
            val downloaded = root.resolve("downloaded.jar").apply { writeText("new") }

            McCoreUpdater.replaceInstalledCore(
                source = downloaded.toPath(),
                target = target.toPath(),
                expectedSha1 = downloaded.sha1,
            ).getOrThrow()

            assertEquals("new", target.readText())
            assertEquals("old", oldLink.readText())
            assertEquals("new", downloaded.readText())
            assertFalse(root.listFiles()!!.any { it.name.contains(".install-") })
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `failed core verification leaves every old inode unchanged`() {
        val root = Files.createTempDirectory("core-rollback").toFile()
        try {
            val target = root.resolve("mods/core.jar").apply {
                parentFile.mkdirs()
                writeText("old")
            }
            val linked = root.resolve("linked.jar")
            Files.createLink(linked.toPath(), target.toPath())
            val downloaded = root.resolve("downloaded.jar").apply { writeText("new") }

            assertTrue(
                McCoreUpdater.replaceInstalledCore(
                    source = downloaded.toPath(),
                    target = target.toPath(),
                    expectedSha1 = "0".repeat(40),
                ).isFailure
            )
            assertEquals("old", target.readText())
            assertEquals("old", linked.readText())
            assertEquals("new", downloaded.readText())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }
}
