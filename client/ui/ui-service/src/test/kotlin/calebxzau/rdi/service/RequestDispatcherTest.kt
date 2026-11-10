package calebxzau.rdi.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestDispatcherTest {
    /** Two OS pipes, so blocking reads behave like the real stdio streams. */
    private class Harness(handle: suspend (String, JsonObject) -> JsonElement) {
        private val toService = Pipe.open()
        private val fromService = Pipe.open()
        private val client = Protocol(
            Channels.newInputStream(fromService.source()),
            Channels.newOutputStream(toService.sink()),
        )
        val dispatcher = RequestDispatcher(
            Protocol(Channels.newInputStream(toService.source()), Channels.newOutputStream(fromService.sink())),
            handle,
        )

        fun send(message: JsonObject) = client.write(message).getOrThrow()

        fun request(id: Long, method: String) = send(buildJsonObject { put("id", id); put("method", method) })

        /**
         * Fails instead of hanging when a request is stuck behind another one. Waits off the
         * test thread, because the dispatcher's handlers run on that thread.
         */
        suspend fun reply(): JsonObject? = withContext(Dispatchers.IO) {
            CompletableFuture.supplyAsync { client.read().getOrThrow() }.get(5, TimeUnit.SECONDS)
        }

        fun closeInput() = toService.sink().close()

        fun closeOutput() = fromService.sink().close()
    }

    private fun named(name: String): JsonElement = buildJsonObject { put("name", name) }

    private fun JsonObject.id() = getValue("id").jsonPrimitive.long

    @Test
    fun slowRequestDoesNotDelayLaterRequests(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val harness = Harness { method, _ ->
            if (method == "slow") release.await()
            named(method)
        }
        val service = launch { harness.dispatcher.run() }
        harness.request(1, "slow")
        harness.request(2, "fast")

        val first = checkNotNull(harness.reply())
        assertEquals(2, first.id())
        assertEquals("fast", first.getValue("result").jsonObject.getValue("name").jsonPrimitive.content)
        release.complete(Unit)
        assertEquals(1, checkNotNull(harness.reply()).id())

        harness.closeInput()
        service.join()
    }

    @Test
    fun handshakeCompletesBeforeTheNextRequestIsHandled(): Unit = runBlocking {
        var ready = false
        val harness = Harness { method, _ ->
            when (method) {
                "handshake" -> { delay(50); ready = true }
                else -> check(ready) { "握手尚未完成" }
            }
            named(method)
        }
        val service = launch { harness.dispatcher.run() }
        harness.request(1, "handshake")
        harness.request(2, "other")

        assertEquals(1, checkNotNull(harness.reply()).id())
        val other = checkNotNull(harness.reply())
        assertEquals(2, other.id())
        assertTrue("result" in other, other.toString())

        harness.closeInput()
        service.join()
    }

    @Test
    fun shutdownCancelsInFlightRequestsWithoutAnsweringThem(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val harness = Harness { method, _ ->
            if (method == "slow") {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
            buildJsonObject { put("stopped", true) }
        }
        val service = launch { harness.dispatcher.run() }
        harness.request(1, "slow")
        started.await()
        harness.request(2, "shutdown")

        assertEquals(2, checkNotNull(harness.reply()).id())
        service.join()
        assertTrue(cancelled)
        harness.closeOutput()
        assertNull(harness.reply(), "a cancelled request must not be answered")
    }

    @Test
    fun refusedIdleShutdownKeepsReading(): Unit = runBlocking {
        val harness = Harness { _, _ -> buildJsonObject { put("stopped", false) } }
        val service = launch { harness.dispatcher.run() }
        harness.request(1, "shutdown")
        assertEquals(1, checkNotNull(harness.reply()).id())
        harness.request(2, "other")
        assertEquals(2, checkNotNull(harness.reply()).id())
        harness.closeInput()
        service.join()
    }

    @Test
    fun requestsWithoutValidIdAreIgnored(): Unit = runBlocking {
        val harness = Harness { method, _ -> named(method) }
        val service = launch { harness.dispatcher.run() }
        harness.send(buildJsonObject { put("method", "missing") })
        harness.send(buildJsonObject { put("id", "7"); put("method", "string") })
        harness.send(buildJsonObject { put("id", 0); put("method", "zero") })
        harness.send(buildJsonObject { put("id", JsonPrimitive(1.5)); put("method", "fraction") })
        harness.request(3, "valid")

        assertEquals(3, checkNotNull(harness.reply()).id())

        harness.closeInput()
        service.join()
    }

    @Test
    fun failuresAreAnsweredWithPlayerReadableErrors(): Unit = runBlocking {
        val harness = Harness { method, _ -> error("${method}失败") }
        val service = launch { harness.dispatcher.run() }
        harness.request(1, "handshake")
        harness.request(2, "host.start")
        harness.send(buildJsonObject { put("id", 3) })
        harness.send(buildJsonObject { put("id", 4); put("method", "host.start"); put("params", 1) })

        val errors = List(4) { checkNotNull(harness.reply()) }.associate { reply ->
            reply.id() to reply.getValue("error").jsonObject
        }
        assertEquals("PROTOCOL_VERSION_MISMATCH", errors.getValue(1).getValue("code").jsonPrimitive.content)
        assertEquals("REQUEST_FAILED", errors.getValue(2).getValue("code").jsonPrimitive.content)
        assertEquals("host.start失败", errors.getValue(2).getValue("message").jsonPrimitive.content)
        assertEquals("请求缺少方法", errors.getValue(3).getValue("message").jsonPrimitive.content)
        assertEquals("请求参数无效", errors.getValue(4).getValue("message").jsonPrimitive.content)

        harness.closeInput()
        service.join()
    }
}
