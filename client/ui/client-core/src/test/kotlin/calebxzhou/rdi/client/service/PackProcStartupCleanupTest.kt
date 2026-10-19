package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackProcStartupCleanupTest {
    @Test
    fun `removes existing contents and recreates root`() {
        val root = Files.createTempDirectory("pack-proc-startup-cleanup")
        try {
            val nested = root.resolve("nested/child/data.txt").apply {
                parent.createDirectories()
                Files.write(this, byteArrayOf(1, 2, 3))
            }
            val regular = Files.write(root.resolve("regular.bin"), byteArrayOf(4, 5, 6))

            PackProcStartupCleanup.clear(root).getOrThrow()

            assertTrue(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(nested, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(regular, LinkOption.NOFOLLOW_LINKS))
        } finally {
            root.toFile().deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `creates missing root`() {
        val parent = Files.createTempDirectory("pack-proc-startup-cleanup-missing")
        val root = parent.resolve("pack-proc")
        try {
            PackProcStartupCleanup.clear(root).getOrThrow()

            assertTrue(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        } finally {
            parent.toFile().deleteRecursivelyNoSymlink()
        }
    }
}
