package calebxzau.rdi.service

import calebxzau.rdi.client.account.MsaSessionStore
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.startHostPlay
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.client.ui.screen.McPlayController
import kotlinx.coroutines.*
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
    private val session = SessionService({ MsaSessionStore.shared.clear(it) }) { calebxzhou.rdi.client.net.loggedAccount = it }
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
        check(loggedIn || method in setOf("handshake", "session.set", "shutdown")) { "请先登录" }
        if (method.substringBefore('.') in setOf("installedPacks", "installer", "packOptions", "packPlay", "tasks", "console")) {
            return (packs ?: PackService(RuntimePackServiceDependencies(runtime), scope, controller::startSession, ::event).also { packs = it }).handle(method, params)
        }
        return when (method) {
            "handshake" -> {
                require(params["protocolVersion"]?.jsonPrimitive?.int == Protocol.VERSION) { "启动服务版本不匹配，请更新启动器" }
                handshake = true
                buildJsonObject { put("protocolVersion", Protocol.VERSION) }
            }
            "session.set" -> {
                val result = session.set(params).getOrThrow()
                loggedIn = true
                result
            }
            "session.clear" -> {
                val result = session.clear(calebxzhou.rdi.client.net.loggedAccount._id.toHexString())
                AccountSessionStore.logout()
                loggedIn = false
                result.getOrThrow()
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
