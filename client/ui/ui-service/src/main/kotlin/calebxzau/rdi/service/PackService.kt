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
import java.nio.file.Path

/** One framed-service session; no native UI and no credentials in snapshots. */
internal class PackService(
    private val dependencies: PackServiceDependencies,
    private val scope: CoroutineScope,
    private val startSession: (McPlayArgs, Boolean) -> Unit,
    private val emit: (String, JsonObject) -> Unit,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private var installerScope: CoroutineScope? = null
    private var installer: LocalModpackInstallViewModel? = null
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

    suspend fun handle(method: String, params: JsonObject): JsonObject = when (method) {
        "installer.open" -> {
            installerScope?.cancel()
            val owned = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            installerScope = owned
            val model = withContext(Dispatchers.IO) { dependencies.installer(owned) }
            installer = model
            snapshots("installer.state", model.state, ::installerSnapshot)
            installerSnapshot(model.state.value)
        }
        "installer.close" -> {
            installerScope?.cancel(); installerScope = null; installer = null
            subscriptions.remove("installer.state")?.cancel(); buildJsonObject {}
        }
        "installer.action", "installer.install" -> {
            val model = checkNotNull(installer) { "请先打开导入页面" }
            val action = if (method == "installer.install") "install" else params.text("action")
            when (action) {
                "scan" -> model.scanCandidates()
                "archive" -> model.openArchive(Path.of(params.text("path")))
                "directory" -> model.openDirectory(Path.of(params.text("path")))
                "candidate", "pickedCandidate" -> {
                    val list = if (action == "candidate") model.state.value.candidates else model.state.value.pickedCandidates
                    model.openInstall(list.getOrNull(params["index"]?.jsonPrimitive?.int ?: -1) ?: error("请选择要导入的版本"))
                }
                "create" -> {
                    val runtime = localModpackRuntimes.getOrNull(params["index"]?.jsonPrimitive?.int ?: -1) ?: error("请选择游戏版本")
                    model.openCreate(runtime.first, runtime.second)
                }
                "name" -> model.updateName(params.text("value"))
                "comment" -> model.updateComment(params.text("value"))
                "icon" -> model.updateIconUrl(params.text("value"))
                "runtime" -> {
                    val runtime = localModpackRuntimes.getOrNull(params["index"]?.jsonPrimitive?.int ?: -1) ?: error("请选择游戏版本")
                    model.updateRuntime(runtime.first, runtime.second)
                }
                "includeSaves" -> model.toggleIncludeSaves()
                "optional" -> model.toggleOptionalFile(params.text("key"))
                "back" -> model.closeEditor()
                "dismissCandidates" -> model.dismissPickedCandidates()
                "dismissMissing" -> model.dismissMissingFiles()
                "dismissError" -> model.dismissError()
                "install" -> {
                    require(model.state.value.editor?.canSubmit == true) { "整合包尚未准备好导入" }
                    model.submitInstall(params["missingFilesConfirmed"]?.jsonPrimitive?.boolean ?: false)
                    val state = model.state.value
                    if (state.editor == null) {
                        val started = withTimeout(5_000) { model.events.first() }
                        return buildJsonObject { put("runId", started.runId) }
                    }
                }
                else -> error("不支持的导入操作")
            }
            installerSnapshot(model.state.value)
        }
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
        "tasks.subscribe" -> {
            snapshots("tasks.state", ClientTaskManager.entries) { entries -> buildJsonObject {
                put("tasks", buildJsonArray { entries.forEach { add(taskSnapshot(it)) } })
            } }
            buildJsonObject {}
        }
        "tasks.watch" -> {
            val runId = params.text("runId")
            val entry = ClientTaskManager.entry(runId) ?: error("找不到这个任务")
            val task = ClientTaskManager.controller(runId) ?: error("找不到这个任务")
            replace("task.state") {
                task.state.collect {
                    delay(34)
                    val latest = task.state.value
                    emit("task.state", taskSnapshot(entry, latest))
                    // Rust refreshes the pack list itself when it sees the terminal snapshot.
                    if (latest.status.isTerminal) cancel()
                }
            }
            taskSnapshot(entry, task.state.value)
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
        installerScope?.cancel(); playJob?.cancel()
        subscriptions.values.forEach(Job::cancel)
        dependencies.close()
    }

    suspend fun stopTasks() {
        withContext(NonCancellable + Dispatchers.IO) {
            ClientTaskManager.entries.value.filterNot { it.status.isTerminal }.forEach { entry ->
                ClientTaskManager.controller(entry.runId)?.terminateAndJoin("启动器已关闭，任务已终止")
            }
        }
    }
}

internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
