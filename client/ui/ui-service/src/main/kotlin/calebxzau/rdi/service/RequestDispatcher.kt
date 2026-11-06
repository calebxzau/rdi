package calebxzau.rdi.service

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Reads requests in arrival order and handles each one in its own coroutine, so a slow
 * request (starting a room) never delays stop or close requests.
 *
 * Handlers inherit the caller's dispatcher. Under `runBlocking` they all resume on one
 * thread, so session state needs no locks as long as handlers only leave that thread
 * through `withContext`. Handshake and shutdown run inline: nothing after a handshake
 * is read before it completes, and shutdown cancels the requests still in flight
 * without answering them (the client sees the stream end instead).
 *
 * A failed write means the protocol stream is gone; it fails [run] instead of being
 * answered, which ends the service.
 */
internal class RequestDispatcher(
    private val protocol: Protocol,
    private val handle: suspend (method: String, params: JsonObject) -> JsonElement,
) {
    suspend fun run(): Unit = coroutineScope {
        try {
            while (true) {
                val request = withContext(Dispatchers.IO) { protocol.read().getOrThrow() } ?: break
                val id = (request["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
                if (id == null) {
                    // There is no id to answer; one bad request must not end every game session.
                    lgr.warn { "忽略缺少有效编号的启动服务请求" }
                    continue
                }
                val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (method == null || method in INLINE_METHODS) {
                    respond(id, method, request)
                    if (method == SHUTDOWN) break
                } else {
                    launch { respond(id, method, request) }
                }
            }
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    private suspend fun respond(id: Long, method: String?, request: JsonObject) {
        val reply = try {
            checkNotNull(method) { "请求缺少方法" }
            val params = when (val value = request["params"]) {
                null -> buildJsonObject {}
                is JsonObject -> value
                else -> throw IllegalArgumentException("请求参数无效")
            }
            val result = handle(method, params)
            buildJsonObject { put("id", id); put("result", result) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            lgr.warn(error) { "启动服务请求失败：${method}" }
            buildJsonObject {
                put("id", id)
                put("error", buildJsonObject {
                    put("code", if (method == HANDSHAKE) "PROTOCOL_VERSION_MISMATCH" else "REQUEST_FAILED")
                    put("message", error.message ?: "操作失败，请重试")
                })
            }
        }
        protocol.write(reply).getOrThrow()
    }

    companion object {
        // Not the shared RDI `lgr`: its file also loads AppConfig, which a plain test cannot.
        private val lgr = KotlinLogging.logger("RDI")
        const val HANDSHAKE = "handshake"
        const val SHUTDOWN = "shutdown"
        private val INLINE_METHODS = setOf(HANDSHAKE, SHUTDOWN)
    }
}
