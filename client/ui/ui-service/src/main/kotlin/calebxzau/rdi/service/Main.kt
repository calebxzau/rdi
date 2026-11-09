package calebxzau.rdi.service

import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.client.service.PlayerService
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.startHostPlay
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.client.ui.screen.McPlayController
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.model.Host
import kotlinx.coroutines.*
import calebxzau.rdi.client.ui.viewmodel.HostListViewModel
import calebxzau.rdi.client.ui.viewmodel.RdiHostListGateway
import calebxzau.rdi.client.ui.viewmodel.HostListUiState
import kotlinx.serialization.json.*
import java.io.File
import java.io.PrintStream

/** Reserve stdout before initializing anything that can log or print. */
fun main() {
    val protocolOutput = System.out
    val log = File(System.getProperty("rdi.service.log", "logs/ui-service.log"))
    log.parentFile?.mkdirs()
    val logStream = PrintStream(log.outputStream().buffered(), true, Charsets.UTF_8)
    System.setOut(logStream)
    System.setErr(logStream)
    System.setProperty("java.awt.headless", "true")
    var exitCode = 0
    try {
        ServiceRuntime.open().getOrThrow().use { runtime ->
            runBlocking { ServiceSession(Protocol(System.`in`, protocolOutput), runtime).run() }
        }
    } catch (error: Exception) {
        exitCode = 1
        error.printStackTrace(logStream)
    } finally {
        McPlayStore.sessions.forEach { it.requestStop(force = true) }
        logStream.flush()
    }
    kotlin.system.exitProcess(exitCode)
}

private class ServiceSession(private val protocol: Protocol, private val runtime: ServiceRuntime) {
    private var handshake = false
    private var loggedIn = false
    private var hostList: HostListViewModel? = null
    private var hostPage = 0
    private val login = LoginService(
        LocalCredentials::read,
        { account, password -> PlayerService.login(account, password).map { Unit } },
        { calebxzhou.rdi.client.net.loggedAccount._id.toHexString() },
        calebxzhou.rdi.client.Const.VERSION_NUMBER,
    )
    private val controller = McPlayController(
        onDuplicate = { event("game.state", buildJsonObject { put("errorMessage", "该整合包已在运行") }) },
        beforeProcessStart = { args -> withContext(Dispatchers.IO) { DevelopmentCoreOverride.install(args).getOrThrow() } },
    )
    private val subscriptions = mutableMapOf<String, Job>()
    private var packs: PackService? = null

    /** Requests run concurrently on this coroutine's thread; see [RequestDispatcher]. */
    suspend fun run(): Unit = coroutineScope {
        val scope = this
        try {
            RequestDispatcher(protocol) { method, params -> handle(scope, method, params) }.run()
        } finally {
            packs?.stopTasks()
            packs?.close()
            // Subscriptions and view-model loads live in this scope; stop them before returning.
            coroutineContext.cancelChildren()
            McPlayStore.sessions.forEach { it.requestStop(force = true) }
        }
    }

    private suspend fun handle(scope: CoroutineScope, method: String, params: JsonObject): JsonElement {
        check(handshake || method == "handshake") { "请先连接启动服务" }
        check(loggedIn || method in setOf("handshake", "login", "login.accounts", "login.saved", "shutdown")) { "请先登录" }
        if (method.substringBefore('.') in setOf("installedPacks", "installer", "packOptions", "packPlay", "tasks", "console")) {
            return (packs ?: PackService(RuntimePackServiceDependencies(runtime), scope, controller::startSession, ::event).also { packs = it }).handle(method, params)
        }
        return when (method) {
            "handshake" -> {
                require(params["protocolVersion"]?.jsonPrimitive?.int == Protocol.VERSION) { "启动服务版本不匹配，请更新启动器" }
                handshake = true
                buildJsonObject { put("protocolVersion", Protocol.VERSION) }
            }
            "login.accounts" -> login.accounts()
            "login", "login.saved" -> {
                val result = login.login(params, saved = method == "login.saved")
                loggedIn = true
                result
            }
            "hostList.subscribe", "hostList.refresh", "hostList.loadMore" -> {
                val model = hostList ?: HostListViewModel(RdiHostListGateway(), useMockData = false, scope = scope).also { hostList = it }
                hostPage = params["page"]?.jsonPrimitive?.int ?: 0
                require(hostPage >= 0) { "房间页码无效" }
                when (method) {
                    "hostList.subscribe" -> {
                        subscriptions.remove("hosts")?.cancel()
                        event("hostList.state", hostState(model.uiState.value))
                        subscriptions["hosts"] = scope.launch {
                            model.uiState.collect {
                                delay(34)
                                event("hostList.state", hostState(model.uiState.value))
                            }
                        }
                    }
                    "hostList.refresh" -> model.refresh()
                    "hostList.loadMore" -> model.loadMore()
                }
                hostState(model.uiState.value)
            }
            "data.get" -> {
                val path = params["path"]?.jsonPrimitive?.content ?: error("请求路径无效")
                require(path in setOf("/host/my", "/host/online-player-ids", "/player/unique-skin-list") ||
                    Regex("/host/list/[0-9]{1,6}").matches(path) || Regex("/player/[a-fA-F0-9]{24}/info").matches(path)) { "不支持的数据请求" }
                if (path == "/host/my" || path.startsWith("/host/list/")) {
                    val response = server.makeRequest<List<Host.BriefVo>>(path.removePrefix("/"))
                    check(response.ok) { response.msg }
                    Json(calebxzhou.rdi.common.serdesJson) { encodeDefaults = true }.encodeToJsonElement(response.data.orEmpty())
                } else {
                    val response = server.makeRequest<JsonElement>(path.removePrefix("/"))
                    check(response.ok) { response.msg }
                    response.data ?: JsonNull
                }
            }
            "host.start" -> {
                // Slint supplies physical pixels; headless AWT cannot resolve monitor DPI.
                val width = params["windowWidth"]?.jsonPrimitive?.int ?: 1280
                val height = params["windowHeight"]?.jsonPrimitive?.int ?: 720
                require(width in 320..16384 && height in 200..16384) { "游戏窗口尺寸无效" }
                calebxzhou.rdi.client.service.launcherWindowLogicalSize =
                    calebxzau.rdi.mclaunch.MinecraftWindowSize(width, height)
                val hostId = params["hostId"]?.jsonPrimitive?.content ?: error("请选择房间")
                when (val result = startHostPlay(hostId).getOrThrow()) {
                    is StartPlayResult.Ready -> {
                        val allowDuplicate = params["allowDuplicateVersion"]?.jsonPrimitive?.boolean ?: false
                        if (!allowDuplicate && McPlayStore.aliveCount(result.args.versionId) > 0) {
                            buildJsonObject {
                                put("duplicateVersion", true)
                                put("hostId", hostId)
                                put("title", result.args.title)
                            }
                        } else {
                            controller.startSession(result.args, allowDuplicate)
                            buildJsonObject { put("accepted", true) }
                        }
                    }
                    is StartPlayResult.NeedInstall -> error("请先在Compose启动器中安装该整合包，再返回这里游玩")
                    is StartPlayResult.NeedMod -> error("缺少游玩所需Mod，请先安装")
                    is StartPlayResult.Installing -> error("整合包正在安装，请稍候")
                }
            }
            "game.restore" -> {
                val snapshots = Json.decodeFromJsonElement<List<GameProcessSnapshot>>(params["sessions"] ?: JsonArray(emptyList()))
                val restored = GameRecovery.restore(snapshots) { session -> controller.markSessionExited(session, "已结束") }.getOrThrow()
                buildJsonObject { put("restored", restored) }
            }
            "game.subscribe" -> {
                subscriptions.remove("game")?.cancel()
                event("game.state", gameState())
                subscriptions["game"] = scope.launch {
                    McPlayStore.revision.collect {
                        delay(34)
                        event("game.state", gameState())
                    }
                }
                buildJsonObject {}
            }
            "game.stopAll" -> {
                McPlayStore.sessions.forEach { controller.stopSession(it, true) }
                gameState()
            }
            "game.stop" -> {
                val session = McPlayStore.sessions.firstOrNull { it.id == params["sessionId"]?.jsonPrimitive?.content }
                    ?: error("该游戏已结束")
                controller.stopSession(session, params["force"]?.jsonPrimitive?.boolean ?: false)
                gameState()
            }
            "shutdown" -> buildJsonObject {}
            else -> error("暂不支持此操作")
        }
    }

    private fun event(name: String, state: JsonObject) {
        protocol.write(buildJsonObject { put("event", name); put("params", state) }).getOrThrow()
    }

    private fun hostState(state: HostListUiState) = buildJsonObject {
        put("initialLoading", state.initialLoading)
        put("loadingMore", state.loadingMore)
        state.errorMessage?.let { put("errorMessage", it) }
        // Bound each visible page; clients request pages, never an unbounded frame.
        put("hosts", buildJsonArray {
            state.hosts.drop(hostPage * 100).take(100).forEach { host -> add(buildJsonObject {
                put("_id", host.target.id); put("name", host.name)
                put("modpackName", host.modpackName); put("playable", host.playable)
                put("status", host.status.name)
                host.iconUrl?.let { put("iconUrl", it) }
            }) }
        })
        put("totalLoaded", state.hosts.size)
        put("page", hostPage)
    }

    private fun gameState() = buildJsonObject {
        put("running", McPlayStore.hasAliveSessions())
        put("sessions", buildJsonArray {
            McPlayStore.sessions.forEach { session -> add(buildJsonObject {
                put("id", session.id); put("title", session.title)
                put("preparing", session.preparing); put("running", session.isAlive())
                put("stopRequested", session.stopRequested)
                GameRecovery.snapshot(session)?.let { put("process", Json.encodeToJsonElement(it)) }
                session.exitMessage?.let { put("exitMessage", it) }
            }) }
        })
    }
}
