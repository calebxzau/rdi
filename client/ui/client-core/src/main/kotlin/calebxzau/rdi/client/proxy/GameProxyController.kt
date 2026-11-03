package calebxzau.rdi.client.proxy

/** Where game connections should go: [nodes] is tried in order and [selected] is what the player chose. */
internal data class ProxyRoute(val nodes: List<String>, val selected: String)

/** The chosen node first, then the server's candidates, then the built-in node as a last resort. */
internal fun resolveProxyRoute(
    selected: String,
    candidates: List<String>,
    fallback: String,
): ProxyRoute = ProxyRoute((listOf(selected) + candidates + fallback).distinct(), selected)

/** Local testing never falls back to a public node. */
internal fun resolveDebugProxyRoute(
    selected: String,
    debugNodes: List<String>,
    debugDefault: String,
): ProxyRoute {
    val nodes = debugNodes.ifEmpty { listOf(debugDefault) }
    return ProxyRoute(nodes, selected.takeIf { it in nodes } ?: nodes.first())
}

internal interface GameProxyBackend {
    /** True when a running game can change node without leaving the world. */
    val seamlessSwitch: Boolean

    /** Returns the address the game connects to. Starting a running backend keeps its address. */
    fun start(route: ProxyRoute): Result<String>
    fun applyRoute(route: ProxyRoute): Result<Unit>
    fun stop(): Result<Unit>
}

internal class NativeProxyBackend(
    private val proxy: NativeLocalProxy,
    private val bindHost: String,
    private val preferredPort: Int,
) : GameProxyBackend {
    override val seamlessSwitch = true

    override fun start(route: ProxyRoute): Result<String> =
        proxy.start(bindHost, preferredPort, route.nodes).map { port -> "$bindHost:$port" }

    override fun applyRoute(route: ProxyRoute): Result<Unit> =
        proxy.setNodes(route.nodes).mapCatching { proxy.switchNode(route.selected).getOrThrow() }

    override fun stop(): Result<Unit> = proxy.stop()
}

/**
 * Picks the proxy for this launcher run. Failing to use the native one must never keep a player
 * out of their host, so every native failure before the game connects falls back to [fallback].
 */
internal class GameProxyController(
    private val route: () -> ProxyRoute,
    private val resumeEnabled: () -> Boolean,
    private val fallback: GameProxyBackend,
    loadNative: () -> Result<GameProxyBackend>,
    private val onNativeUnavailable: (Throwable) -> Unit,
) {
    private val native by lazy { loadNative().onFailure(onNativeUnavailable) }
    private var active: GameProxyBackend? = null

    @Synchronized
    fun start(): Result<String> {
        active?.let { return it.start(route()) }
        if (resumeEnabled()) {
            native.getOrNull()?.let { backend ->
                backend.start(route())
                    .onSuccess { address ->
                        active = backend
                        return Result.success(address)
                    }
                    .onFailure(onNativeUnavailable)
            }
        }
        return fallback.start(route()).onSuccess { active = fallback }
    }

    /** Returns true when running games move to the new route without leaving the world. */
    @Synchronized
    fun applyRoute(): Result<Boolean> {
        val backend = active?.takeIf { it.seamlessSwitch } ?: return Result.success(false)
        return backend.applyRoute(route()).map { true }
    }

    @Synchronized
    fun stop(): Result<Unit> {
        val backend = active ?: return Result.success(Unit)
        active = null
        return backend.stop()
    }
}

/** Log text for players. Only ever names nodes, never their addresses. */
internal fun describeLocalProxyEvent(event: LocalProxyEvent, nodeName: (String) -> String): String = when (event) {
    is LocalProxyEvent.Attached -> when (event.reason) {
        NodeAttachReason.Initial -> "已连接节点${nodeName(event.node)}"
        NodeAttachReason.Failover -> "已自动切换到节点${nodeName(event.node)}，用时${event.elapsedMs}ms"
        NodeAttachReason.Manual -> "已切换到节点${nodeName(event.node)}，用时${event.elapsedMs}ms"
    }

    is LocalProxyEvent.PathLost -> "节点连接中断(${event.reason})，正在切换"
    is LocalProxyEvent.SwitchFailed ->
        "切换到节点${nodeName(event.node)}失败: ${event.error.replace(event.node, nodeName(event.node))}"

    is LocalProxyEvent.Ended -> when (event.reason) {
        "closed" -> "房间连接已结束"
        "expired" -> "所有节点都无法连接，已断开房间连接"
        "gone" -> "房间连接已失效，请重新进入"
        else -> "房间连接异常，已断开: ${event.detail}"
    }

    is LocalProxyEvent.Dropped -> "有${event.count}条连接记录未能显示"
}
