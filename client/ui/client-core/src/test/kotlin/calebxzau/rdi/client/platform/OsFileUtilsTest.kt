package calebxzau.rdi.client.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OsFileUtilsTest {
    @Test
    fun `missing path is already moved`() {
        val path = Files.createTempDirectory("os-trash-missing").resolve("missing")
        val desktop = RecordingDesktop()

        moveToOsTrash(path, desktop).getOrThrow()

        assertEquals(emptyList(), desktop.moved)
    }

    @Test
    fun `filesystem root is rejected`() {
        val root = Path.of("").toAbsolutePath().root

        assertTrue(moveToOsTrash(requireNotNull(root), RecordingDesktop()).isFailure)
    }

    @Test
    fun `file is handed to desktop trash`() {
        val file = Files.createTempFile("os-trash-file", ".tmp")
        val desktop = RecordingDesktop(delete = true)

        moveToOsTrash(file, desktop).getOrThrow()

        assertEquals(listOf(file.toAbsolutePath().normalize().toFile()), desktop.moved)
        assertFalse(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `directory is handed to desktop trash as one path`() {
        val directory = Files.createTempDirectory("os-trash-directory")
        Files.writeString(directory.resolve("child.txt"), "child")
        val desktop = RecordingDesktop(delete = true)

        moveToOsTrash(directory, desktop).getOrThrow()

        assertEquals(listOf(directory.toAbsolutePath().normalize().toFile()), desktop.moved)
        assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `unsupported desktop is reported`() {
        val file = Files.createTempFile("os-trash-unsupported", ".tmp")
        val desktop = RecordingDesktop(supported = false)

        assertTrue(moveToOsTrash(file, desktop).isFailure)
        assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `unsupported move to trash action is reported`() {
        val file = Files.createTempFile("os-trash-action-unsupported", ".tmp")
        val desktop = RecordingDesktop(actionSupported = false)

        assertTrue(moveToOsTrash(file, desktop).isFailure)
        assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `false desktop result is reported`() {
        val file = Files.createTempFile("os-trash-false", ".tmp")
        val desktop = RecordingDesktop(delete = false)

        assertTrue(moveToOsTrash(file, desktop).isFailure)
        assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `desktop success is rejected when source remains`() {
        val file = Files.createTempFile("os-trash-source-remains", ".tmp")
        val desktop = RecordingDesktop(moveResult = true)

        assertTrue(moveToOsTrash(file, desktop).isFailure)
        assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `desktop exception is reported`() {
        val file = Files.createTempFile("os-trash-exception", ".tmp")
        val desktop = RecordingDesktop(error = IllegalStateException("desktop failure"))

        assertTrue(moveToOsTrash(file, desktop).isFailure)
        assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `symlink is handed to desktop without following target`() {
        val target = Files.createTempFile("os-trash-target", ".tmp")
        val link = target.resolveSibling("${target.fileName}.link")
        try {
            Files.createSymbolicLink(link, target.fileName)
        } catch (_: Exception) {
            return
        }
        val desktop = RecordingDesktop(delete = true)

        moveToOsTrash(link, desktop).getOrThrow()

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
    }

    private class RecordingDesktop(
        private val supported: Boolean = true,
        private val actionSupported: Boolean = true,
        private val delete: Boolean = false,
        private val moveResult: Boolean = delete,
        private val error: Throwable? = null,
    ) : OsTrashDesktop {
        val moved = mutableListOf<File>()

        override fun isDesktopSupported(): Boolean = supported

        override fun isMoveToTrashSupported(): Boolean = actionSupported

        override fun moveToTrash(file: File): Boolean {
            moved += file
            error?.let { throw it }
            if (delete) file.deleteRecursively()
            return moveResult
        }
    }
}
