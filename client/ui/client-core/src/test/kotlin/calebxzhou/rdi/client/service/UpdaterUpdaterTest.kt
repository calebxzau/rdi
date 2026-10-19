package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterUpdaterTest {
    @Test
    fun `log mode skips updater replacement without network access`() = runBlocking {
        val original = System.getProperty("rdi.updater.islogmode")
        try {
            System.setProperty("rdi.updater.islogmode", "true")

            val result = UpdaterUpdater.update(onStatus = {}, onDetail = {}).getOrThrow()

            assertEquals(UpdaterUpdateResult.SKIPPED_LOG_MODE, result)
        } finally {
            if (original == null) System.clearProperty("rdi.updater.islogmode")
            else System.setProperty("rdi.updater.islogmode", original)
        }
    }

    @Test
    fun `valid pending updater atomically replaces current updater`() {
        val root = Files.createTempDirectory("updater-replace").toFile()
        try {
            val target = root.resolve("start.exe").apply { writeText("old") }
            val pending = root.resolve("start.exe.pending").apply { writeText("new") }

            UpdaterUpdater.replaceUpdaterExecutable(target, pending)

            assertEquals("new", target.readText())
            assertFalse(pending.exists())
            assertFalse(root.resolve("start.exe.replacing-backup").exists())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `failed replacement restores current updater`() {
        val root = Files.createTempDirectory("updater-restore").toFile()
        try {
            val target = root.resolve("start.exe").apply { writeText("old") }
            val missingPending = root.resolve("start.exe.pending")

            assertFails { UpdaterUpdater.replaceUpdaterExecutable(target, missingPending) }

            assertTrue(target.exists())
            assertEquals("old", target.readText())
            assertFalse(root.resolve("start.exe.replacing-backup").exists())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `hash mismatch rejects pending updater`() {
        val root = Files.createTempDirectory("updater-hash").toFile()
        try {
            val pending = root.resolve("start.exe.pending").apply { writeText("invalid") }

            assertFailsWith<IllegalStateException> {
                UpdaterUpdater.validatePendingFile(pending, "0000000000000000000000000000000000000000")
            }
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }
}
