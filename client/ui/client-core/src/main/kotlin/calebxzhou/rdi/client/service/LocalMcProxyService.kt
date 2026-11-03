package calebxzhou.rdi.client.service

import calebxzau.rdi.client.proxy.GameProxyBackend
import calebxzau.rdi.client.proxy.GameProxyController
import calebxzau.rdi.client.proxy.LocalProxyEvent
import calebxzau.rdi.client.proxy.NativeLocalProxy
import calebxzau.rdi.client.proxy.NativeProxyBackend
import calebxzau.rdi.client.proxy.ProxyRoute
import calebxzau.rdi.client.proxy.describeLocalProxyEvent
import calebxzau.rdi.client.proxy.loadLocalProxyNativeApi
import calebxzau.rdi.client.proxy.resolveDebugProxyRoute
import calebxzau.rdi.client.proxy.resolveProxyRoute
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.client.net.DEFAULT_GAME_NODE_ADDR
import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.proxy.LocalMcProxy
import calebxzhou.rdi.client.proxy.LocalMcProxyConfig
import calebxzhou.rdi.client.proxy.ProxyEndpoint
import calebxzhou.rdi.common.DEBUG
import java.io.File

private val lgr by Loggers

private const val DEBUG_NODE_PORT = 65230

object LocalMcProxyService {
    @Volatile
    private var logSink: (String) -> Unit = {}

    /** The node games were last seen on, to correct the display when a manual switch fails. */
    @Volatile
    private var lastAttachedNode: String? = null

    private val config = LocalMcProxyConfig(
        metricsDir = ClientDirs.mcDir.parentFile.resolve("net-metrics")
    )

    private val debugDefaultNode get() = "${RServer.DEBUG_HOST ?: "localhost"}:$DEBUG_NODE_PORT"

    private val debugEventLog: File? =
        System.getProperty("rdi.localProxy.eventLog")?.takeIf { DEBUG && it.isNotBlank() }?.let(::File)

    private val kotlinBackend = object : GameProxyBackend {
        private val proxy = LocalMcProxy(
            endpointProvider = {
                if (DEBUG) ProxyEndpoint(RServer.DEBUG_HOST ?: "localhost", DEBUG_NODE_PORT)
                else ProxyEndpoint.parse(RServer.currentGameAddr).getOrThrow()
            },
            config = config,
            onLog = ::log,
        )

        override val seamlessSwitch = false
        override fun start(route: ProxyRoute): Result<String> = proxy.start()

        // The first frame of each connection picks the node, so the next connection follows the route.
        override fun applyRoute(route: ProxyRoute): Result<Unit> = Result.success(Unit)
        override fun stop(): Result<Unit> = proxy.stop()
    }

    private val controller = GameProxyController(
        route = ::currentRoute,
        resumeEnabled = ::resumeEnabled,
        fallback = kotlinBackend,
        loadNative = {
            loadLocalProxyNativeApi().map { api ->
                NativeProxyBackend(
                    proxy = NativeLocalProxy(api, ::onEvent),
                    bindHost = config.localBindHost,
                    preferredPort = config.preferredBindPort,
                )
            }
        },
        onNativeUnavailable = { cause -> lgr.warn(cause) { "不退图换节点不可用，改用普通连接" } },
    )

    fun start(onLog: (String) -> Unit): Result<String> {
        logSink = onLog
        return controller.start()
    }

    fun stop(): Result<Unit> = controller.stop()

    /** Gives a running game the current route; the result says whether it moves without leaving the world. */
    fun applyRoute(): Result<Boolean> = controller.applyRoute()

    private fun resumeEnabled(): Boolean {
        val debugOverride = System.getProperty("rdi.sessionResume")
        return if (DEBUG && debugOverride != null) {
            debugOverride.toBoolean()
        } else {
            RServer.routeState.value.sessionResume
        }
    }

    private fun currentRoute(): ProxyRoute {
        val state = RServer.routeState.value
        if (DEBUG) {
            val debugNodes = System.getProperty("rdi.localProxy.nodes").orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            return resolveDebugProxyRoute(state.gameAddr, debugNodes, debugDefaultNode)
        }
        return resolveProxyRoute(
            selected = RServer.currentGameAddr,
            candidates = state.candidates.map { it.gameAddr },
            fallback = DEFAULT_GAME_NODE_ADDR,
        )
    }

    private fun onEvent(event: LocalProxyEvent) {
        // Local fault tests read this file; addresses stay out of the log players see.
        debugEventLog?.let { file ->
            runCatching { file.appendText("[LocalMcProxy] $event\n") }
                .onFailure { lgr.warn(it) { "写入本地连接事件文件失败" } }
        }
        when (event) {
            is LocalProxyEvent.Attached -> {
                lastAttachedNode = event.node
                RServer.markActiveNode(event.node)
            }

            is LocalProxyEvent.SwitchFailed -> lastAttachedNode?.let(RServer::markActiveNode)
            else -> Unit
        }
        log("[LocalMcProxy] ${describeLocalProxyEvent(event, ::nodeName)}")
    }

    private fun nodeName(addr: String): String =
        RServer.routeState.value.candidates.firstOrNull { it.gameAddr == addr }?.name ?: "默认节点"

    private fun log(line: String) {
        if (DEBUG) println(line)
        runCatching { logSink(line) }.onFailure { it.printStackTrace() }
    }
}
