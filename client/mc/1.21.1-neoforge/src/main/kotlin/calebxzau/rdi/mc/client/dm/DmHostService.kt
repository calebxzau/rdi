package calebxzau.rdi.mc.client.dm

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.HttpUtil
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object DmHostService {
    private val logger = LoggerFactory.getLogger(DmHostService::class.java)
    private val generationCounter = AtomicLong()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private var active: Hosting? = null

    enum class State { Connecting, Retrying, Connected }

    private class Hosting(
        val generation: Long,
        val owner: IntegratedServer,
        val hostId: UUID,
        val config: DmConfig,
    ) {
        var state: State = State.Connecting
        var endpoint: DmEndpoint? = null
        var gamePort: Int? = null
        var tunnel: DmHostTunnel? = null
    }

    fun start(hostId: UUID): Result<Unit> {
        val minecraft = Minecraft.getInstance()
        if (active != null) return Result.failure(IllegalStateException("DM房主连接已经存在"))
        if (!minecraft.hasSingleplayerServer() || minecraft.player == null) {
            return Result.failure(IllegalStateException("请先进入单人世界"))
        }
        val config = DmConfig.fromSystemProperty().getOrElse { return Result.failure(it) } ?:
            return Result.failure(IllegalStateException("DM服务器未配置"))
        val server = minecraft.getSingleplayerServer()
            ?: return Result.failure(IllegalStateException("找不到当前单人世界服务器"))
        try {
            if (!server.isPublished && !server.publishServer(null, false, HttpUtil.getAvailablePort())) {
                return Result.failure(IllegalStateException("无法开放单人世界"))
            }
        } catch (error: Throwable) {
            logger.error("Failed to publish integrated server for DM host {}", hostId, error)
            return Result.failure(IllegalStateException("无法开放单人世界", error))
        }
        val hosting = Hosting(generationCounter.incrementAndGet(), server, hostId, config)
        active = hosting
        executor.submit {
            DmHttpClient(config).info().onSuccess { info ->
                val publicHost = info.publicHost ?: config.baseUri.host
                val endpoint = DmEndpoint.parse("${formatHost(publicHost)}:${info.tunnelPort}")
                    .getOrElse { error -> throw IllegalArgumentException("DM网关地址无效：${error.message}", error) }
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    startTunnel(minecraft, hosting, endpoint)
                }
            }.onFailure { error ->
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    active = null
                    logger.error("Failed to query DM gateway information", error)
                    sendMessage(minecraft, "无法取得DM网关信息：${error.message ?: "网络错误"}")
                }
            }
        }
        sendMessage(minecraft, "正在连接DM房间 ${hostId}")
        return Result.success(Unit)
    }

    fun autoStartIfAssociated() {
        val minecraft = Minecraft.getInstance()
        val server = minecraft.getSingleplayerServer() ?: return
        val root = server.getWorldPath(LevelResource.ROOT)
        DmWorldAssociationStore.read(root).onSuccess { association ->
            association?.let { start(it.hostId).onFailure { error -> logger.warn("DM自动托管失败", error) } }
        }.onFailure { error ->
            logger.error("Failed to read DM world association", error)
            sendMessage(minecraft, "无法读取DM房间关联：${error.message ?: "文件错误"}")
        }
    }

    private fun startTunnel(minecraft: Minecraft, hosting: Hosting, endpoint: DmEndpoint) {
        hosting.endpoint = endpoint
        val targetPort = hosting.owner.port
        if (targetPort !in 1..65535) {
            active = null
            sendMessage(minecraft, "单人世界端口无效")
            return
        }
        val tunnel = DmHostTunnel(endpoint, hosting.hostId, targetPort, object : DmHostTunnel.Listener {
            override fun onReady(session: UUID, gamePort: Int) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    if (gamePort !in 1..65535) {
                        logger.error("DM gateway returned invalid game port {} for host {}", gamePort, hosting.hostId)
                        stop()
                        return@execute
                    }
                    hosting.state = State.Connected
                    hosting.gamePort = gamePort
                    sendMessage(minecraft, clickableAddress(endpoint.gameAddress(gamePort)))
                }
            }

            override fun onRetry(attempt: Int, reason: String) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    if (hosting.state != State.Retrying) sendMessage(minecraft, "DM网关连接中断，正在重试")
                    hosting.state = State.Retrying
                    hosting.gamePort = null
                }
            }

            override fun onTerminal(failure: DmHostTunnel.TerminalFailure) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    active = null
                    hosting.tunnel = null
                    sendMessage(minecraft, failure.message)
                }
            }
        })
        hosting.tunnel = tunnel
        try {
            tunnel.start()
        } catch (error: Throwable) {
            active = null
            tunnel.close()
            logger.error("Failed to start DM tunnel for host {}", hosting.hostId, error)
            sendMessage(minecraft, "无法启动DM网关连接：${error.message ?: "未知错误"}")
        }
    }

    fun stop(): Boolean {
        val current = active ?: return false
        active = null
        current.tunnel?.close()
        return true
    }

    fun onTick() {
        val current = active ?: return
        val minecraft = Minecraft.getInstance()
        if (!minecraft.hasSingleplayerServer() || minecraft.getSingleplayerServer() !== current.owner) stop()
    }

    fun status(): String {
        val current = active ?: return "DM房主：未运行"
        val state = when (current.state) {
            State.Connecting -> "连接中"
            State.Retrying -> "重试中"
            State.Connected -> "已连接"
        }
        val join = current.endpoint?.let { endpoint -> current.gamePort?.let(endpoint::gameAddress) }?.let { "，加入地址：$it" } ?: ""
        return "DM房主：${state}，房间：${current.hostId}${join}"
    }

    private fun isCurrent(hosting: Hosting): Boolean = active === hosting &&
        hosting.generation == active?.generation &&
        Minecraft.getInstance().hasSingleplayerServer() &&
        Minecraft.getInstance().getSingleplayerServer() === hosting.owner

    private fun clickableAddress(address: String): Component =
        Component.literal("DM加入地址：${address}").withStyle(ChatFormatting.UNDERLINE).withStyle { style ->
            style.withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, address))
        }

    private fun formatHost(value: String): String = when {
        value.startsWith("[") && value.endsWith("]") -> value
        value.contains(':') -> "[$value]"
        else -> value
    }

    private fun sendMessage(minecraft: Minecraft, message: String) = sendMessage(minecraft, Component.literal(message))

    private fun sendMessage(minecraft: Minecraft, message: Component) {
        minecraft.player?.displayClientMessage(message, false) ?: minecraft.gui.chat.addMessage(message)
    }
}
