package calebxzau.rdi.client.proxy

import calebxzau.rdi.common.logging.Loggers
import com.sun.jna.Library
import com.sun.jna.Native
import kotlin.concurrent.thread

private val lgr by Loggers

private const val LIBRARY_NAME = "rdi_local_proxy"
private const val EXPECTED_ABI_VERSION = 1
private const val EVENT_BUFFER_BYTES = 64 * 1024
private const val ERROR_BUFFER_BYTES = 4 * 1024
private const val EVENT_POLL_INTERVAL_MS = 500L
private const val STOP_TIMEOUT_MS = 3_000
private const val POLLER_JOIN_MS = 1_000L

/** Mirrors `client/local-proxy-rs/src/ffi.rs`. Strings are passed as NUL-terminated UTF-8. */
@Suppress("FunctionName")
internal interface LocalProxyNativeApi : Library {
    fun rdi_lp_abi_version(): Int
    fun rdi_lp_start(bindHost: ByteArray, preferredPort: Short, nodes: ByteArray, outPort: ShortArray): Int
    fun rdi_lp_set_nodes(nodes: ByteArray): Int
    fun rdi_lp_switch_node(address: ByteArray): Int
    fun rdi_lp_poll_events(buffer: ByteArray, length: Int): Int
    fun rdi_lp_last_error(buffer: ByteArray, length: Int): Int
    fun rdi_lp_stop(timeoutMs: Int): Int
}

internal enum class NodeAttachReason { Initial, Failover, Manual }

internal sealed interface LocalProxyEvent {
    data class Attached(
        val session: Long,
        val node: String,
        val reason: NodeAttachReason,
        val elapsedMs: Long,
    ) : LocalProxyEvent

    data class PathLost(val session: Long, val reason: String) : LocalProxyEvent
    data class SwitchFailed(val session: Long, val node: String, val error: String) : LocalProxyEvent
    data class Ended(val session: Long, val reason: String, val detail: String) : LocalProxyEvent
    data class Dropped(val count: Long) : LocalProxyEvent
}

/** Returns null for a line this launcher does not understand. */
internal fun parseLocalProxyEvent(line: String): LocalProxyEvent? {
    val fields = line.split('\t')
    return when {
        fields[0] == "dropped" && fields.size == 2 ->
            fields[1].toLongOrNull()?.let(LocalProxyEvent::Dropped)

        else -> {
            val session = fields.getOrNull(1)?.toLongOrNull() ?: return null
            when {
                fields[0] == "attached" && fields.size == 5 -> {
                    val reason = when (fields[3]) {
                        "initial" -> NodeAttachReason.Initial
                        "failover" -> NodeAttachReason.Failover
                        "manual" -> NodeAttachReason.Manual
                        else -> return null
                    }
                    val elapsedMs = fields[4].toLongOrNull() ?: return null
                    LocalProxyEvent.Attached(session, fields[2], reason, elapsedMs)
                }

                fields[0] == "path_lost" && fields.size == 3 ->
                    LocalProxyEvent.PathLost(session, fields[2])

                fields[0] == "switch_failed" && fields.size == 4 ->
                    LocalProxyEvent.SwitchFailed(session, fields[2], fields[3])

                fields[0] == "ended" && fields.size == 4 ->
                    LocalProxyEvent.Ended(session, fields[2], fields[3])

                else -> null
            }
        }
    }
}

internal fun loadLocalProxyNativeApi(): Result<LocalProxyNativeApi> = runCatching {
    val api = Native.load(LIBRARY_NAME, LocalProxyNativeApi::class.java)
    val abi = api.rdi_lp_abi_version()
    check(abi == EXPECTED_ABI_VERSION) { "本地连接组件版本不匹配: $abi" }
    api
}

/** Drives the native library. Its events arrive on a polling thread owned by this class. */
internal class NativeLocalProxy(
    private val api: LocalProxyNativeApi,
    private val onEvent: (LocalProxyEvent) -> Unit,
    private val pollIntervalMs: Long = EVENT_POLL_INTERVAL_MS,
) {
    private val lock = Any()
    private var poller: Thread? = null

    @Volatile
    private var polling = false

    fun start(bindHost: String, preferredPort: Int, nodes: List<String>): Result<Int> = runCatching {
        synchronized(lock) {
            val port = ShortArray(1)
            api.rdi_lp_start(cString(bindHost), preferredPort.toShort(), nodes.toNativeList(), port).orThrow("启动")
            if (poller == null) {
                polling = true
                poller = thread(isDaemon = true, name = "rdi-local-proxy-events") { pollLoop() }
            }
            port[0].toInt() and 0xFFFF
        }
    }

    fun setNodes(nodes: List<String>): Result<Unit> = runCatching {
        api.rdi_lp_set_nodes(nodes.toNativeList()).orThrow("更新节点")
    }

    fun switchNode(address: String): Result<Unit> = runCatching {
        api.rdi_lp_switch_node(cString(address)).orThrow("切换节点")
    }

    fun stop(): Result<Unit> = runCatching {
        synchronized(lock) {
            polling = false
            poller?.let { running ->
                running.interrupt()
                running.join(POLLER_JOIN_MS)
            }
            poller = null
            api.rdi_lp_stop(STOP_TIMEOUT_MS).orThrow("停止")
        }
        drainEvents()
    }

    private fun pollLoop() {
        while (polling) {
            runCatching { drainEvents() }.onFailure { lgr.warn(it) { "读取本地连接事件失败" } }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun drainEvents() {
        val buffer = ByteArray(EVENT_BUFFER_BYTES)
        while (true) {
            val count = api.rdi_lp_poll_events(buffer, buffer.size)
            count.orThrow("读取事件")
            if (count == 0) return
            String(buffer, 0, count, Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).forEach { line ->
                val event = parseLocalProxyEvent(line)
                if (event == null) {
                    lgr.warn { "无法识别的本地连接事件: ${line.substringBefore('\t')}" }
                } else {
                    runCatching { onEvent(event) }.onFailure { lgr.warn(it) { "处理本地连接事件失败" } }
                }
            }
        }
    }

    private fun Int.orThrow(action: String) {
        if (this < 0) throw IllegalStateException("本地连接${action}失败($this): ${lastError()}")
    }

    private fun lastError(): String {
        val buffer = ByteArray(ERROR_BUFFER_BYTES)
        val count = api.rdi_lp_last_error(buffer, buffer.size)
        return if (count > 0) String(buffer, 0, count, Charsets.UTF_8) else "未知错误"
    }

    private fun List<String>.toNativeList() = cString(joinToString("\n"))

    private fun cString(value: String) = (value + '\u0000').toByteArray(Charsets.UTF_8)
}
