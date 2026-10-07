package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaveSnapshotTest {
    private fun sampleSave(): Path {
        val save = createTempDirectory("snapshot-save")
        Files.write(save.resolve("level.dat"), byteArrayOf(1, 2, 3))
        Files.createDirectories(save.resolve("region"))
        Files.write(save.resolve("region/r.0.0.mca"), ByteArray(10_000) { it.toByte() })
        Files.createDirectories(save.resolve("data/empty"))
        Files.write(save.resolve("中文.txt"), "玩家".toByteArray())
        Files.write(save.resolve("session.lock"), byteArrayOf())
        return save
    }

    @Test
    fun snapshotListsFilesAndDirectoriesButNotTheLock() {
        val snapshot = SaveSnapshot.take(sampleSave()).getOrThrow()

        assertEquals(setOf("level.dat", "region/r.0.0.mca", "中文.txt"), snapshot.files.keys)
        assertEquals(setOf("region", "data", "data/empty"), snapshot.directories)
        assertEquals(10_009L, snapshot.totalBytes)
        snapshot.verify().getOrThrow()
    }

    @Test
    fun copyReproducesTheSave() {
        val save = sampleSave()
        val target = createTempDirectory("snapshot-copy").resolve("copy")
        val snapshot = SaveSnapshot.take(save).getOrThrow()
        var lastProgress = 0L

        snapshot.copyTo(target, progress = { lastProgress = it }).getOrThrow()

        assertEquals(10_009L, lastProgress)
        assertTrue(Files.isDirectory(target.resolve("data/empty")))
        assertTrue(Files.notExists(target.resolve("session.lock")))
        for ((path, state) in snapshot.files) {
            assertContentEquals(Files.readAllBytes(save.resolve(path)), Files.readAllBytes(target.resolve(path)), path)
            assertEquals(state.lastModified, Files.getLastModifiedTime(target.resolve(path)), path)
        }
        val copied = SaveSnapshot.take(target).getOrThrow()
        assertEquals(snapshot.files, copied.files)
        assertEquals(snapshot.directories, copied.directories)
    }

    @Test
    fun changesAfterTheSnapshotAreDetected() {
        val changes = mapOf<String, (Path) -> Unit>(
            "modified" to { Files.write(it.resolve("level.dat"), byteArrayOf(9, 9, 9, 9)) },
            "same size, new mtime" to { Files.setLastModifiedTime(it.resolve("level.dat"), FileTime.fromMillis(1_000)) },
            "added" to { Files.write(it.resolve("new.dat"), byteArrayOf(1)) },
            "removed" to { Files.delete(it.resolve("中文.txt")) },
            "directory added" to { Files.createDirectories(it.resolve("data/other")) },
        )
        changes.forEach { (name, change) ->
            val save = sampleSave()
            val snapshot = SaveSnapshot.take(save).getOrThrow()
            change(save)
            assertIs<SaveChangedException>(snapshot.verify().exceptionOrNull(), name)
            val target = createTempDirectory("snapshot-copy-bad").resolve("copy")
            assertIs<SaveChangedException>(snapshot.copyTo(target).exceptionOrNull(), name)
        }
    }

    @Test
    fun readFileChecksTheSnapshot() {
        val save = sampleSave()
        val snapshot = SaveSnapshot.take(save).getOrThrow()

        assertContentEquals(byteArrayOf(1, 2, 3), snapshot.readFile("level.dat").getOrThrow())
        Files.write(save.resolve("level.dat"), byteArrayOf(4, 5, 6, 7))
        assertIs<SaveChangedException>(snapshot.readFile("level.dat").exceptionOrNull())
        assertIs<SaveChangedException>(snapshot.readFile("missing.dat").exceptionOrNull())
    }

    @Test
    fun linksAndJunctionsAreRejected() {
        val save = sampleSave()
        val outside = createTempDirectory("snapshot-outside")
        var checked = 0
        if (runCatching { Files.createSymbolicLink(save.resolve("link.dat"), outside.resolve("x")) }.isSuccess) {
            assertIs<SaveLinkException>(SaveSnapshot.take(save).exceptionOrNull(), "symbolic link")
            Files.delete(save.resolve("link.dat"))
            checked++
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            val junction = save.resolve("data/junction")
            val exit = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
            assertEquals(0, exit, "mklink /J")
            assertIs<SaveLinkException>(SaveSnapshot.take(save).exceptionOrNull(), "junction")
            checked++
        }
        println("link checks run: ${checked}")
    }

    @Test
    fun lockIsExclusiveAndReleased() {
        val save = createTempDirectory("lock-save")

        val lock = SaveSourceLock.acquire(save).getOrThrow()
        assertTrue(Files.isRegularFile(save.resolve("session.lock")))
        assertIs<SaveInUseException>(SaveSourceLock.acquire(save).exceptionOrNull())
        lock.close()
        SaveSourceLock.acquire(save).getOrThrow().close()
        assertEquals(0L, Files.size(save.resolve("session.lock")))
    }
}
