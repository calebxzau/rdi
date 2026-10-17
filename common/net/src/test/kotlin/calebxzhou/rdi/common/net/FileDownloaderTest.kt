package calebxzhou.rdi.common.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDownloaderTest {
    @Test
    fun `failure summary names every source`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/first") { exchange -> fixture.respond(exchange, 404, ByteArray(0)) }
        fixture.add("/second") { exchange -> fixture.respond(exchange, 404, ByteArray(0)) }
        try {
            val result = fixture.tempDirectory.resolve("file.bin").downloadFileFrom(
                urls = listOf(fixture.url("/first"), fixture.url("/second")),
                knownSize = 4,
                maxAttempts = 1,
            ) {}

            val summary = result.exceptionOrNull()?.downloadFailureSummary().orEmpty()
            assertTrue(fixture.url("/first") in summary, summary)
            assertTrue(fixture.url("/second") in summary, summary)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `validation failure falls back to the next source`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/bad", "bad")
        fixture.add("/good", "good")
        try {
            val target = fixture.tempDirectory.resolve("file.bin")
            val result = target.downloadFileFrom(
                urls = listOf(fixture.url("/bad"), fixture.url("/good")),
                knownSize = 4,
                maxAttempts = 1,
                validator = { path ->
                    if (path.readText() == "good") Result.success(Unit)
                    else Result.failure(IllegalStateException("bad content"))
                },
                onProgress = {},
            )

            assertTrue(result.isSuccess)
            assertEquals("good", target.readText())
            assertTrue(fixture.requests("/good") > 0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `validation failure leaves existing target and removes temp`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/one", "bad1")
        fixture.add("/two", "bad2")
        try {
            val target = fixture.tempDirectory.resolve("existing.bin")
            target.writeText("original")
            val result = target.downloadFileFrom(
                urls = listOf(fixture.url("/one"), fixture.url("/two")),
                knownSize = 4,
                maxAttempts = 1,
                validator = { Result.failure(IllegalStateException("invalid")) },
                onProgress = {},
            )

            assertTrue(result.isFailure)
            assertEquals("original", target.readText())
            assertFalse(Files.exists(target.resolveSibling("existing.bin.downloading")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancellation removes partial temp file and propagates`() = runBlocking {
        val fixture = LocalDownloadServer()
        val body = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val downloadedBytes = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        fixture.add("/cancel") { exchange ->
            try {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { output ->
                    Thread.sleep(600)
                    output.write(body, 0, 8192)
                    output.flush()
                    releaseResponse.await()
                    output.write(body, 8192, body.size - 8192)
                }
            } catch (_: IOException) {
                // The client is expected to close the response when cancellation propagates.
            }
        }
        try {
            val target = fixture.tempDirectory.resolve("cancel.bin")
            val download = async {
                target.downloadFileFrom(
                    fixture.url("/cancel"),
                    knownSize = body.size.toLong(),
                    maxAttempts = 1,
                    onProgress = { progress ->
                        if (progress.bytesDownloaded > 0L) downloadedBytes.countDown()
                    },
                )
            }

            assertTrue(downloadedBytes.await(5, TimeUnit.SECONDS))
            download.cancel()
            releaseResponse.countDown()

            assertFailsWith<kotlinx.coroutines.CancellationException> {
                download.await()
            }
            assertFalse(Files.exists(target))
            assertFalse(Files.exists(target.resolveSibling("cancel.bin.downloading")))
        } finally {
            releaseResponse.countDown()
            fixture.close()
        }
    }

    @Test
    fun `stale temp is discarded without a resume range`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/file", "fresh")
        try {
            val target = fixture.tempDirectory.resolve("stale.bin")
            target.resolveSibling("stale.bin.downloading").writeText("old")
            val result = target.downloadFileFrom(
                fixture.url("/file"),
                knownSize = 5,
                maxAttempts = 1,
                onProgress = {},
            )

            assertTrue(result.isSuccess)
            assertEquals("fresh", target.readText())
            assertTrue(fixture.rangeHeaders("/file").isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `conflicting full response is rejected`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/wrong", "four")
        try {
            val target = fixture.tempDirectory.resolve("wrong.bin")
            val result = target.downloadFileFrom(
                fixture.url("/wrong"),
                knownSize = 3,
                maxAttempts = 1,
                onProgress = {},
            )

            assertTrue(result.isFailure)
            assertFalse(Files.exists(target))
            assertFalse(Files.exists(target.resolveSibling("wrong.bin.downloading")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `different source sizes remain eligible for sequential fallback`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/short", "bad")
        fixture.add("/long", "right!")
        try {
            val target = fixture.tempDirectory.resolve("sizes.bin")
            val result = target.downloadFileFrom(
                urls = listOf(fixture.url("/short"), fixture.url("/long")),
                maxAttempts = 1,
                validator = { path ->
                    if (path.readText() == "right!") Result.success(Unit)
                    else Result.failure(IllegalStateException("bad content"))
                },
                onProgress = {},
            )

            assertTrue(result.isSuccess)
            assertEquals("right!", target.readText())
            assertTrue(fixture.requests("/long") > 0)
            assertTrue(fixture.rangeHeaders("/short").isEmpty())
            assertTrue(fixture.rangeHeaders("/long").isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `known small file uses one get and unknown small uses head then get`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/known", "known")
        fixture.add("/unknown", "unknown")
        try {
            val knownTarget = fixture.tempDirectory.resolve("known.bin")
            assertTrue(
                knownTarget.downloadFileFrom(
                    fixture.url("/known"),
                    knownSize = 5,
                    maxAttempts = 1,
                    onProgress = {},
                ).isSuccess
            )
            assertEquals(listOf("GET"), fixture.methods("/known"))
            assertTrue(fixture.rangeHeaders("/known").isEmpty())

            val unknownTarget = fixture.tempDirectory.resolve("unknown.bin")
            assertTrue(
                unknownTarget.downloadFileFrom(
                    fixture.url("/unknown"),
                    maxAttempts = 1,
                    onProgress = {},
                ).isSuccess
            )
            assertEquals(listOf("HEAD", "GET"), fixture.methods("/unknown"))
            assertTrue(fixture.rangeHeaders("/unknown").isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `same target downloads serialize and lock entry is evicted`() = runBlocking {
        val fixture = LocalDownloadServer()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        fixture.add("/slow") { exchange ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
            try {
                Thread.sleep(100)
                fixture.respond(exchange, "slow")
            } finally {
                active.decrementAndGet()
            }
        }
        try {
            val target = fixture.tempDirectory.resolve("serialized.bin")
            val results = listOf(1, 2, 3).map {
                async {
                    target.downloadFileFrom(
                        fixture.url("/slow"),
                        knownSize = 4,
                        maxAttempts = 1,
                        onProgress = {},
                    )
                }
            }.awaitAll()

            assertTrue(results.all { it.isSuccess })
            assertEquals(1, maximum.get())
            val field = Class.forName("calebxzhou.rdi.common.net.FileDownloaderKt")
                .getDeclaredField("targetDownloadMutexes")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val locks = field.get(null) as Map<String, *>
            assertTrue(locks.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ranged wildcard total is rejected`() = runBlocking {
        val fixture = LocalDownloadServer()
        val body = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        fixture.add("/wildcard") { exchange ->
            when (exchange.requestMethod) {
                "HEAD" -> fixture.respondNoBody(exchange, 200, mapOf("Content-Length" to body.size.toString()))
                else -> {
                    val range = exchange.requestHeaders["Range"].orEmpty().firstOrNull()
                    if (range == "bytes=0-0") {
                        fixture.respond(exchange, 206, byteArrayOf(body[0]), mapOf("Content-Range" to "bytes 0-0/*"))
                    } else {
                        val bounds = fixture.parseRange(range!!)
                        fixture.respond(
                            exchange,
                            206,
                            body.copyOfRange(bounds.first, bounds.second + 1),
                            mapOf("Content-Range" to "bytes ${bounds.first}-${bounds.second}/*"),
                        )
                    }
                }
            }
        }
        try {
            val target = fixture.tempDirectory.resolve("wildcard.bin")
            target.writeText("original")
            val result = target.downloadFileFrom(
                fixture.url("/wildcard"),
                knownSize = body.size.toLong(),
                maxAttempts = 1,
                onProgress = {},
            )
            assertTrue(result.isFailure)
            assertEquals("original", target.readText())
            assertFalse(Files.exists(target.resolveSibling("wildcard.bin.downloading")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `multi range validation failure downgrades to sequential sources`() = runBlocking {
        val fixture = LocalDownloadServer()
        val invalid = ByteArray(4 * 1024 * 1024) { 1 }
        val valid = ByteArray(invalid.size) { 2 }
        fixture.add("/invalid") { exchange -> fixture.respondRanged(exchange, invalid, invalid) }
        fixture.add("/valid") { exchange -> fixture.respondRanged(exchange, invalid, valid) }
        try {
            val target = fixture.tempDirectory.resolve("downgrade.bin")
            val result = target.downloadFileFrom(
                urls = listOf(fixture.url("/invalid"), fixture.url("/valid")),
                knownSize = valid.size.toLong(),
                maxAttempts = 1,
                validator = { path ->
                    if (Files.readAllBytes(path).firstOrNull() == 2.toByte()) Result.success(Unit)
                    else Result.failure(IllegalStateException("invalid ranged content"))
                },
                onProgress = {},
            )
            assertTrue(result.isSuccess)
            assertEquals(2.toByte(), Files.readAllBytes(target).first())
            assertTrue(fixture.rangeHeaders("/valid").contains("bytes=0-0"))
            assertTrue(fixture.fullGetRequests("/valid") > 0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `heterogeneous large sources are not mixed into ranges`() = runBlocking {
        val fixture = LocalDownloadServer()
        val first = ByteArray(4 * 1024 * 1024) { 3 }
        val second = ByteArray(5 * 1024 * 1024) { 4 }
        fixture.add("/first") { exchange -> fixture.respondRanged(exchange, first, first) }
        fixture.add("/second") { exchange -> fixture.respondRanged(exchange, second, second) }
        try {
            val target = fixture.tempDirectory.resolve("heterogeneous.bin")
            val result = target.downloadFileFrom(
                urls = listOf(fixture.url("/first"), fixture.url("/second")),
                maxAttempts = 1,
                validator = { path ->
                    if (Files.size(path) == second.size.toLong() && Files.readAllBytes(path).first() == 4.toByte()) {
                        Result.success(Unit)
                    } else Result.failure(IllegalStateException("wrong source"))
                },
                onProgress = {},
            )
            assertTrue(result.isSuccess)
            assertEquals(second.size.toLong(), Files.size(target))
            assertTrue(fixture.rangeHeaders("/first").any { it != "bytes=0-0" })
            assertTrue(fixture.rangeHeaders("/second").all { it == "bytes=0-0" })
            assertTrue(fixture.fullGetRequests("/second") > 0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `same source interruption resumes with etag`() = runBlocking {
        val fixture = LocalDownloadServer()
        val body = "abcdef".toByteArray()
        val etag = "\"resume-v1\""
        val calls = AtomicInteger()
        fixture.add("/resume") { exchange ->
            val range = exchange.requestHeaders["Range"].orEmpty().firstOrNull()
            exchange.responseHeaders.add("ETag", etag)
            when {
                exchange.requestMethod == "HEAD" -> fixture.respondNoBody(exchange, 200, mapOf("Content-Length" to body.size.toString()))
                range == "bytes=0-0" -> fixture.respond(exchange, 206, byteArrayOf(body[0]), mapOf("Content-Range" to "bytes 0-0/${body.size}"))
                calls.getAndIncrement() == 0 -> {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body, 0, 3) }
                }
                range == "bytes=3-" -> fixture.respond(exchange, 206, body.copyOfRange(3, body.size), mapOf("Content-Range" to "bytes 3-5/${body.size}"))
                else -> fixture.respond(exchange, 200, body)
            }
        }
        try {
            val target = fixture.tempDirectory.resolve("resume.bin")
            val result = target.downloadFileFrom(
                fixture.url("/resume"),
                knownSize = body.size.toLong(),
                maxAttempts = 2,
                onProgress = {},
            )
            assertTrue(result.isSuccess)
            assertEquals("abcdef", target.readText())
            assertTrue(fixture.rangeHeaders("/resume").contains("bytes=3-"))
            assertEquals(etag, fixture.ifRangeHeaders("/resume").last())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `partial source failure still falls back to another source`() = runBlocking {
        val fixture = LocalDownloadServer()
        fixture.add("/a") { exchange ->
            exchange.responseHeaders.add("ETag", "\"a-v1\"")
            exchange.sendResponseHeaders(200, 6)
            exchange.responseBody.use { it.write("abc".toByteArray()) }
        }
        fixture.add("/b", "abcdef")
        try {
            val target = fixture.tempDirectory.resolve("fallback.bin")
            val result = target.downloadFileFrom(
                urls = listOf(fixture.url("/a"), fixture.url("/b")),
                knownSize = 6,
                maxAttempts = 1,
                onProgress = {},
            )
            assertTrue(result.isSuccess)
            assertEquals("abcdef", target.readText())
            assertTrue(fixture.requests("/b") > 0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `unreachable host is skipped for other files after one failure`() = runBlocking {
        val fixture = LocalDownloadServer()
        // Drop the connection without a response, like a blocked or dead host.
        fixture.add("/dead/one") { exchange -> exchange.close() }
        fixture.add("/dead/two") { exchange -> exchange.close() }
        fixture.add("/good/two", "two!")
        try {
            // "localhost" and 127.0.0.1 reach the same server but count as different hosts.
            val first = fixture.tempDirectory.resolve("one.bin").downloadFileFrom(
                urls = listOf(fixture.url("/dead/one", host = "localhost")),
                knownSize = 4,
                maxAttempts = 1,
                onProgress = {},
            )
            assertTrue(first.isFailure)
            assertEquals(1, fixture.requests("/dead/one"))

            val secondTarget = fixture.tempDirectory.resolve("two.bin")
            val second = secondTarget.downloadFileFrom(
                urls = listOf(fixture.url("/dead/two", host = "localhost"), fixture.url("/good/two")),
                knownSize = 4,
                maxAttempts = 1,
                onProgress = {},
            )
            assertTrue(second.isSuccess)
            assertEquals("two!", secondTarget.readText())
            assertEquals(0, fixture.requests("/dead/two"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `resume mismatch gets a bounded clean retry`() = runBlocking {
        val fixture = LocalDownloadServer()
        val body = "abcdef".toByteArray()
        val oldEtag = "\"old\""
        val newEtag = "\"new\""
        val initialGet = AtomicInteger()
        fixture.add("/mismatch") { exchange ->
            val range = exchange.requestHeaders["Range"].orEmpty().firstOrNull()
            when {
                exchange.requestMethod == "HEAD" -> {
                    exchange.responseHeaders.add("ETag", oldEtag)
                    fixture.respondNoBody(exchange, 200, mapOf("Content-Length" to body.size.toString()))
                }
                range == "bytes=0-0" -> {
                    exchange.responseHeaders.add("ETag", oldEtag)
                    fixture.respond(exchange, 206, byteArrayOf(body[0]), mapOf("Content-Range" to "bytes 0-0/${body.size}"))
                }
                initialGet.getAndIncrement() == 0 -> {
                    exchange.responseHeaders.add("ETag", oldEtag)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body, 0, 3) }
                }
                range == "bytes=3-" -> {
                    exchange.responseHeaders.add("ETag", newEtag)
                    fixture.respond(exchange, 200, body)
                }
                else -> {
                    exchange.responseHeaders.add("ETag", newEtag)
                    fixture.respond(exchange, 200, body)
                }
            }
        }
        try {
            val target = fixture.tempDirectory.resolve("mismatch.bin")
            val result = target.downloadFileFrom(
                fixture.url("/mismatch"),
                knownSize = body.size.toLong(),
                maxAttempts = 2,
                onProgress = {},
            )
            assertTrue(result.isSuccess)
            assertEquals("abcdef", target.readText())
            assertTrue(fixture.rangeHeaders("/mismatch").contains("bytes=3-"))
            assertTrue(fixture.requests("/mismatch") >= 3)
        } finally {
            fixture.close()
        }
    }
}

private class LocalDownloadServer : AutoCloseable {
    val tempDirectory: Path = createTempDirectory("file-downloader-test")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val handlers = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val requestMethods = ConcurrentHashMap<String, MutableList<String>>()
    private val ranges = ConcurrentHashMap<String, MutableList<String>>()
    private val ifRanges = ConcurrentHashMap<String, MutableList<String>>()
    private val fullGets = ConcurrentHashMap<String, AtomicInteger>()

    init {
        server.executor = executor
        server.start()
    }

    fun add(path: String, body: String) = add(path) { exchange -> respond(exchange, body) }

    fun add(path: String, handler: (HttpExchange) -> Unit) {
        handlers[path] = handler
        server.createContext(path) { exchange ->
            requestMethods.computeIfAbsent(path) { CopyOnWriteArrayList() }.add(exchange.requestMethod)
            exchange.requestHeaders["Range"]?.let { values ->
                ranges.computeIfAbsent(path) { CopyOnWriteArrayList() }.addAll(values)
            }
            if (exchange.requestMethod == "GET" && exchange.requestHeaders["Range"].isNullOrEmpty()) {
                fullGets.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            }
            exchange.requestHeaders["If-Range"]?.let { values ->
                ifRanges.computeIfAbsent(path) { CopyOnWriteArrayList() }.addAll(values)
            }
            handlers[path]!!.invoke(exchange)
        }
    }

    fun url(path: String, host: String = "127.0.0.1"): String = "http://${host}:${server.address.port}$path"

    fun requests(path: String): Int = requestMethods[path]?.count { it == "GET" } ?: 0

    fun methods(path: String): List<String> = requestMethods[path]?.toList() ?: emptyList()

    fun rangeHeaders(path: String): List<String> = ranges[path]?.toList() ?: emptyList()

    fun ifRangeHeaders(path: String): List<String> = ifRanges[path]?.toList() ?: emptyList()

    fun fullGetRequests(path: String): Int = fullGets[path]?.get() ?: 0

    fun parseRange(raw: String): Pair<Int, Int> {
        val bounds = raw.removePrefix("bytes=").split('-')
        return bounds[0].toInt() to bounds[1].toInt()
    }

    fun respondRanged(exchange: HttpExchange, probeBody: ByteArray, fullBody: ByteArray) {
        if (exchange.requestMethod == "HEAD") {
            respondNoBody(exchange, 200, mapOf("Content-Length" to fullBody.size.toString()))
            return
        }
        val rawRange = exchange.requestHeaders["Range"].orEmpty().firstOrNull()
        if (rawRange == "bytes=0-0") {
            respond(exchange, 206, byteArrayOf(probeBody[0]), mapOf("Content-Range" to "bytes 0-0/${fullBody.size}"))
            return
        }
        if (rawRange != null) {
            val bounds = parseRange(rawRange)
            respond(
                exchange,
                206,
                probeBody.copyOfRange(bounds.first, bounds.second + 1),
                mapOf("Content-Range" to "bytes ${bounds.first}-${bounds.second}/${fullBody.size}"),
            )
        } else {
            respond(exchange, 200, fullBody)
        }
    }

    fun respond(exchange: HttpExchange, body: String) {
        respond(exchange, 200, body.toByteArray(StandardCharsets.UTF_8))
    }

    fun respond(exchange: HttpExchange, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    fun respondNoBody(exchange: HttpExchange, status: Int, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
