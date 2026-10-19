package calebxzhou.rdi.client.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModpackInvalidLocalCleanupTest {
    @Test
    fun `scan ignores malformed files and nested directories`() = runBlocking {
        withTempRoot { root ->
            val valid = root.resolve("111111111111111111111111_fabric").apply { mkdir() }
            root.resolve("not-a-modpack").mkdir()
            root.resolve("222222222222222222222222_").mkdir()
            root.resolve("outer").apply { mkdir() }
                .resolve("444444444444444444444444_inner").mkdir()
            root.resolve("555555555555555555555555_file").writeText("not a directory")

            val refs = scanLocalPackRefs(root)

            assertEquals(listOf(valid), refs.map { it.dir })
        }
    }

    @Test
    fun `shared missing id maps all local versions and sends it once`() = runBlocking {
        withTempRoot { root ->
            root.resolve("111111111111111111111111_first").mkdir()
            root.resolve("111111111111111111111111_second").mkdir()
            val requests = mutableListOf<List<ObjectId>>()

            val result = findInvalidLocalPackDirs(root) { ids ->
                requests += ids
                ids
            }

            assertEquals(listOf(listOf(ObjectId("111111111111111111111111"))), requests)
            assertEquals(2, result.size)
            assertEquals(
                setOf("111111111111111111111111_first", "111111111111111111111111_second"),
                result.map { it.versionId }.toSet(),
            )
        }
    }

    @Test
    fun `distinct ids are split into batches of at most 512`() = runBlocking {
        withTempRoot { root ->
            val ids = (0 until 513).map { index -> ObjectId(String.format("%024x", index + 1)) }
            ids.forEachIndexed { index, id -> root.resolve("${id}_v$index").mkdir() }
            val requests = mutableListOf<List<ObjectId>>()

            val result = findInvalidLocalPackDirs(root) { chunk ->
                requests += chunk
                chunk
            }

            assertEquals(listOf(512, 1), requests.map(List<ObjectId>::size))
            assertEquals(513, result.size)
            assertFalse(requests.any { it.size > 512 })
        }
    }

    @Test
    fun `empty scan does not call missing id resolver`() = runBlocking {
        withTempRoot { root ->
            var calls = 0

            val result = findInvalidLocalPackDirs(root) {
                calls++
                emptyList()
            }

            assertEquals(emptyList(), result)
            assertEquals(0, calls)
        }
    }

    @Test
    fun `version lifecycle operations are mutually exclusive`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()

        val first = async {
            ModpackLifecycleCoordinator.withVersionLock("same-version") {
                order += 1
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            ModpackLifecycleCoordinator.withVersionLock("same-version") {
                order += 2
            }
        }
        yield()
        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf(1, 2), order)
    }

    private suspend fun withTempRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("rdi-invalid-pack-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
