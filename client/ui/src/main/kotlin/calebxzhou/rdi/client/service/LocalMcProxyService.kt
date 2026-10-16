package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.proxy.LocalMcProxy
import calebxzhou.rdi.client.proxy.LocalMcProxyConfig
import calebxzhou.rdi.client.proxy.ProxyEndpoint
import calebxzhou.rdi.common.DEBUG

object LocalMcProxyService {
    @Volatile
    private var logSink: (String) -> Unit = {}

    private val proxy = LocalMcProxy(
        endpointProvider = {
            if (DEBUG) ProxyEndpoint(RServer.DEBUG_HOST ?: "localhost", 65230)
            else ProxyEndpoint.parse(RServer.currentGameAddr).getOrThrow()
        },
        config = LocalMcProxyConfig(
            metricsDir = ClientDirs.mcDir.parentFile.resolve("net-metrics")
        ),
        onLog = { line ->
            if (DEBUG) println(line)
            runCatching { logSink(line) }.onFailure { it.printStackTrace() }
        }
    )

    fun start(onLog: (String) -> Unit): Result<String> {
        logSink = onLog
        return proxy.start()
    }

    fun stop(): Result<Unit> = proxy.stop()
}
