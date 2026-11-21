package calebxzau.rdi.service

import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzau.rdi.client.logic.LocalPackPlay
import calebxzau.rdi.client.logic.rdiPackPlayArgs
import calebxzau.rdi.client.ui.viewmodel.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** One framed-service session; no native UI and no credentials in snapshots. */
internal class PackService(
    private val dependencies: PackServiceDependencies,
    private val scope: CoroutineScope,
    private val startSession: (McPlayArgs, Boolean) -> Unit,
    private val emit: (String, JsonObject) -> Unit,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val subscriptions = mutableMapOf<String, Job>()
    private var playJob: Job? = null
    private var pendingPlay: McPlayArgs? = null
    private var playGeneration = 0L
    private val mutablePlayState = MutableStateFlow(buildJsonObject {})
    private var playState: JsonObject
        get() = mutablePlayState.value
        set(value) { mutablePlayState.value = value }

    private fun replace(topic: String, block: suspend CoroutineScope.() -> Unit) {
        subscriptions.remove(topic)?.cancel()
        subscriptions[topic] = scope.launch(block = block)
    }

    @OptIn(FlowPreview::class)
    private fun <T> snapshots(topic: String, flow: StateFlow<T>, encode: (T) -> JsonObject) {
        emit(topic, encode(flow.value))
        replace(topic) { flow.sample(34).collect { emit(topic, encode(it)) } }
    }

    /** The list lives in Rust since J2, so the pack to play is looked up on demand. */
    private suspend fun selected(params: JsonObject): ModpackLocalDir =
        withContext(Dispatchers.IO) { dependencies.findPack(params.text("versionId")) }.getOrElse { cause ->
            logger.warn(cause) { "查找整合包${params.text("versionId")}失败" }
            throw cause
        }

    fun isIdle(): Boolean = playJob?.isActive != true && pendingPlay == null

    suspend fun handle(method: String, params: JsonObject): JsonObject = when (method) {
        "packPlay.open" -> {
            val pack = selected(params)
            playGeneration++
            playJob?.cancel(); pendingPlay = null
            val online = loggedAccount.msid != null
            playState = buildJsonObject {
                put("versionId", pack.versionId); put("name", pack.name); put("local", pack.isLocal)
                put("onlineAvailable", online)
                put("mode", (pack.local?.lastAccountMode?.takeIf { it == LocalPlayAccountMode.Offline || online } ?: LocalPlayAccountMode.Offline).name)
                put("nickname", pack.local?.lastOfflineName ?: loggedAccount.name); put("busy", false)
            }
            snapshots("packPlay.state", mutablePlayState) { it }
            playState
        }
        "packPlay.cancel" -> {
            playGeneration++
            playJob?.cancel(); playJob = null; pendingPlay = null
            playState = buildJsonObject { put("closed", true) }
            playState
        }
        "packPlay.start" -> {
            require(playJob?.isActive != true) { "游戏正在准备中" }
            val pack = selected(params)
            val width = params["windowWidth"]?.jsonPrimitive?.int ?: 1280
            val height = params["windowHeight"]?.jsonPrimitive?.int ?: 720
            require(width in 320..16384 && height in 200..16384) { "游戏窗口尺寸无效" }
            launcherWindowLogicalSize = calebxzau.rdi.mclaunch.MinecraftWindowSize(width, height)
            val duplicateAllowed = params["allowDuplicateVersion"]?.jsonPrimitive?.boolean ?: false
            val generation = ++playGeneration
            playState = buildJsonObject {
                playState.forEach { (k,v) -> if (k !in setOf("error", "deviceUri", "duplicateVersion", "closed")) put(k,v) }
                put("nickname", params.text("nickname")); put("mode", params.text("mode").ifBlank { "Offline" })
                put("busy", true)
            }
            playJob = scope.launch {
                try {
                    val args = pendingPlay?.takeIf { duplicateAllowed && it.versionId == pack.versionId } ?: if (pack.isLocal) {
                        LocalPackPlay().prepare(pack,
                            LocalPlayAccountMode.valueOf(params.text("mode").ifBlank { "Offline" }), params.text("nickname")) { code ->
                            scope.launch {
                                if (generation != playGeneration) return@launch
                                playState = buildJsonObject { playState.forEach { (k,v) -> put(k,v) }; put("deviceUri", code.directVerificationUri) }
                            }
                        }
                    } else rdiPackPlayArgs(pack)
                    currentCoroutineContext().ensureActive()
                    if (generation != playGeneration) return@launch
                    if (!duplicateAllowed && McPlayStore.aliveCount(args.versionId) > 0) {
                        pendingPlay = args
                        playState = buildJsonObject { put("versionId", pack.versionId); put("duplicateVersion", true); put("busy", false) }
                    } else {
                        pendingPlay = null; startSession(args, duplicateAllowed)
                        playState = buildJsonObject { put("closed", true); put("busy", false) }
                    }
                } catch (cause: CancellationException) { throw cause }
                catch (cause: Exception) {
                    logger.warn(cause) { "启动整合包失败" }
                    playState = buildJsonObject { playState.forEach { (k,v) -> if (k != "deviceUri") put(k,v) }; put("busy", false); put("error", cause.message ?: "启动游戏失败") }
                }
            }
            buildJsonObject { put("accepted", true) }
        }
        "console.subscribe", "console.clear" -> {
            val session = params.text("sessionId").let { id -> McPlayStore.sessions.firstOrNull { it.id == id } ?: McPlayStore.sessions.lastOrNull() }
            if (method == "console.clear") session?.consoleState?.clear()
            fun snapshot() = buildJsonObject {
                put("sessionId", session?.id.orEmpty())
                put("sessions", buildJsonArray { McPlayStore.sessions.forEach { add(buildJsonObject { put("id", it.id); put("title", it.title) }) } })
                put("lines", buildJsonArray { boundedConsoleLines(session?.consoleState?.snapshot().orEmpty()).forEach { add(JsonPrimitive(it)) } })
            }
            subscriptions.remove("console.state")?.cancel()
            if (session != null) snapshots("console.state", session.consoleState.revision) { snapshot() }
            snapshot().also { emit("console.state", it) }
        }
        else -> error("暂不支持此整合包操作")
    }

    override fun close() {
        playJob?.cancel()
        subscriptions.values.forEach(Job::cancel)
        dependencies.close()
    }


}

internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
