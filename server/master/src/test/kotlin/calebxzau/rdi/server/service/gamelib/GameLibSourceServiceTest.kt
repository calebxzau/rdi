package calebxzau.rdi.server.service.gamelib

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameLibSourceServiceTest {
    private val path = "net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip"
    private val root: File = Files.createTempDirectory("game-lib-source-test").toFile()
    private val cacheDir = root.resolve("game-libs/maven-cache")
    private val installedRoot = root.resolve("game-libs")

    private fun bytesSha1(content: String): String =
        root.resolve("sha-probe").apply { writeText(content) }.sha1

    @Test
    fun `fetches upstream once and then serves the cache`() = runBlocking {
        val calls = AtomicInteger()
        val service = service(useMirror = true) { urls, target, _ ->
            calls.incrementAndGet()
            assertEquals("https://bmclapi2.bangbang93.com/maven/$path", urls.first())
            assertEquals("https://maven.neoforged.net/releases/$path", urls[1])
            Result.success(target.also { Files.writeString(it, "neoform") })
        }

        val first = service.resolve(path, bytesSha1("neoform")).getOrThrow()
        val second = service.resolve(path, bytesSha1("neoform")).getOrThrow()

        assertEquals(cacheDir.resolve(path), first)
        assertEquals(first, second)
        assertEquals("neoform", first.readText())
        assertEquals(1, calls.get())
    }

    @Test
    fun `reuses a library already installed for hosts`() = runBlocking {
        installedRoot.resolve("1.21.1-neoforge/libraries").resolve(path).apply {
            parentFile.mkdirs()
            writeText("installed")
        }
        val service = service { _, _, _ -> error("must not fetch") }

        val file = service.resolve(path, bytesSha1("installed")).getOrThrow()

        assertEquals(cacheDir.resolve(path), file)
        assertEquals("installed", file.readText())
    }

    @Test
    fun `installed library with another sha1 is not served`() = runBlocking {
        installedRoot.resolve("1.21.1-neoforge/libraries").resolve(path).apply {
            parentFile.mkdirs()
            writeText("stale")
        }
        val service = service { _, target, _ -> Result.success(target.also { Files.writeString(it, "fresh") }) }

        val file = service.resolve(path, bytesSha1("fresh")).getOrThrow()

        assertEquals("fresh", file.readText())
    }

    @Test
    fun `upstream failure becomes a player-facing error`() = runBlocking {
        val service = service(useMirror = false) { urls, _, _ ->
            assertFalse(urls.any { "bmclapi" in it })
            Result.failure(IOException("connect timeout"))
        }

        val error = service.resolve(path, null).exceptionOrNull()

        assertIs<RequestError>(error)
        assertFalse(cacheDir.resolve(path).exists())
    }

    @Test
    fun `invalid path and sha1 are rejected without fetching`() = runBlocking<Unit> {
        val service = service { _, _, _ -> error("must not fetch") }

        assertIs<RequestError>(service.resolve("net/neoforged/../../secret.jar", null).exceptionOrNull())
        assertIs<RequestError>(service.resolve(path, "xyz").exceptionOrNull())
    }

    @Test
    fun `concurrent requests share one upstream fetch`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<String>()
        val service = service { _, target, _ ->
            calls += target.toString()
            release.await()
            Result.success(target.also { Files.writeString(it, "neoform") })
        }

        val requests = List(4) { async { service.resolve(path, null) } }
        // Hold the first fetch open until every request has joined it.
        while (calls.isEmpty()) delay(10)
        delay(100)
        release.complete(Unit)
        val files = requests.awaitAll().map { it.getOrThrow() }

        assertEquals(1, calls.size)
        assertTrue(files.all { it == cacheDir.resolve(path) })
    }

    private fun service(useMirror: Boolean = true, fetcher: GameLibFetcher) = GameLibSourceService(
        cacheDir = cacheDir,
        installedLibsRoot = installedRoot,
        useMirror = { useMirror },
        fetcher = fetcher,
    )
}
