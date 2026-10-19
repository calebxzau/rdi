package calebxzhou.rdi.client.service

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

class RemovedModCleanupServiceTest {
    @Test
    fun `removes exact generated and conventional filenames case insensitively`() = runBlocking {
        val root = Files.createTempDirectory("removed-mod-cleanup")
        try {
            val mods = Files.createDirectories(root.resolve("mods"))
            val generated = Files.write(mods.resolve("powerful-dummy_neoforge_123.JAR"), byteArrayOf(1))
            val conventional = Files.write(mods.resolve("SPARK-1.10.jar"), byteArrayOf(2))
            val exact = Files.write(mods.resolve("spark.jar"), byteArrayOf(3))
            Files.write(mods.resolve("default-server-properties-1.0.JaR"), byteArrayOf(4))

            val removed = RemovedModCleanupService.cleanup(root).getOrThrow()

            assertEquals(
                listOf(
                    generated.fileName.toString(),
                    conventional.fileName.toString(),
                    exact.fileName.toString(),
                    "default-server-properties-1.0.JaR",
                ).sorted(),
                removed,
            )
            assertTrue(removed.all { Files.notExists(mods.resolve(it), LinkOption.NOFOLLOW_LINKS) })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preserves unrelated jars and non jars`() = runBlocking {
        val root = Files.createTempDirectory("removed-mod-cleanup-unrelated")
        try {
            val mods = Files.createDirectories(root.resolve("mods"))
            val preserved = listOf(
                "sparkling.jar",
                "my-spark.jar",
                "spark.txt",
                "ordinary.jar",
            )
            preserved.forEach { Files.write(mods.resolve(it), byteArrayOf(1)) }

            assertEquals(emptyList(), RemovedModCleanupService.cleanup(root).getOrThrow())
            preserved.forEach { assertTrue(Files.exists(mods.resolve(it), LinkOption.NOFOLLOW_LINKS), it) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing mods directory succeeds`() = runBlocking {
        val root = Files.createTempDirectory("removed-mod-cleanup-missing")
        try {
            assertEquals(emptyList(), RemovedModCleanupService.cleanup(root).getOrThrow())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `present regular mods path fails`() = runBlocking {
        val root = Files.createTempDirectory("removed-mod-cleanup-invalid")
        try {
            Files.write(root.resolve("mods"), byteArrayOf(1))

            val result = RemovedModCleanupService.cleanup(root)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `backup filename without an explicit removed slug remains untouched`() = runBlocking {
        val root = Files.createTempDirectory("removed-mod-cleanup-backup")
        try {
            val mods = Files.createDirectories(root.resolve("mods"))
            val backupMod = mods.resolve("backup-only.jar")
            Files.write(backupMod, byteArrayOf(1))

            assertEquals(emptyList(), RemovedModCleanupService.cleanup(root).getOrThrow())
            assertTrue(Files.exists(backupMod, LinkOption.NOFOLLOW_LINKS))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preserves matching directory and removes matching symbolic link without touching target`() = runBlocking {
        val root = Files.createTempDirectory("removed-mod-cleanup-links")
        try {
            val mods = Files.createDirectories(root.resolve("mods"))
            val directory = Files.createDirectory(mods.resolve("spark-1.0.jar"))
            val target = Files.write(root.resolve("spark-target.jar"), byteArrayOf(7, 8, 9))
            val link = mods.resolve("spark-link.jar")
            try {
                Files.createSymbolicLink(link, target)
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "symbolic links are not supported")
            } catch (_: SecurityException) {
                assumeTrue(false, "symbolic links are not available")
            } catch (_: IOException) {
                assumeTrue(false, "symbolic links are not available")
            }

            val removed = RemovedModCleanupService.cleanup(root).getOrThrow()

            assertEquals(listOf(link.fileName.toString()), removed)
            assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.notExists(link, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            assertEquals(listOf<Byte>(7, 8, 9), Files.readAllBytes(target).toList())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `filename predicate requires an allowed slug boundary`() {
        assertTrue(RemovedModCleanupService.matchesRemovedSlug("SPARK.jar"))
        assertTrue(RemovedModCleanupService.matchesRemovedSlug("spark-neoforge-1.0.JAR"))
        assertTrue(RemovedModCleanupService.matchesRemovedSlug("spark_neoforge_abc.jar"))
        assertFalse(RemovedModCleanupService.matchesRemovedSlug("sparkling.jar"))
        assertFalse(RemovedModCleanupService.matchesRemovedSlug("my-spark.jar"))
        assertFalse(RemovedModCleanupService.matchesRemovedSlug("spark.txt"))
    }
}
