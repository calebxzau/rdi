package calebxzau.rdi.mc.client.dm

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.HttpUtil
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object DmHostService {
    private val logger = LoggerFactory.getLogger(DmHostService::class.java)
    private val generationCounter = AtomicLong()

    private var active: Hosting? = null

    enum class State {
        Connecting,
        Retrying,
        Connected,
    }

    private class Hosting(
        val generation: Long,
        val owner: IntegratedServer,
        val endpoint: DmEndpoint,
        val room: String,
        val port: Int,
    ) {
        var state: State = State.Connecting
        var session: UUID? = null
        var joinAddress: String? = null
        lateinit var tunnel: DmHostTunnel
    }

    fun start(gateway: String, room: String): Result<Unit> {
        val minecraft = Minecraft.getInstance()
        val endpoint = DmEndpoint.parse(gateway).getOrElse { return Result.failure(it) }
        val normalizedRoom = room.trim()
        val roomBytes = normalizedRoom.toByteArray(Charsets.UTF_8)
        if (roomBytes.isEmpty() || roomBytes.size > DmProtocol.MAX_ROOM_BYTES) {
            return Result.failure(IllegalArgumentException("房间名长度必须在1到128字节之间"))
        }
        if (active != null) return Result.failure(IllegalStateException("DM房主连接已经存在"))
        if (!minecraft.hasSingleplayerServer() || minecraft.player == null) {
            return Result.failure(IllegalStateException("请先进入单人世界"))
        }
        val server = minecraft.getSingleplayerServer()
            ?: return Result.failure(IllegalStateException("找不到当前单人世界服务器"))
        try {
            if (!server.isPublished && !server.publishServer(null, false, HttpUtil.getAvailablePort())) {
                return Result.failure(IllegalStateException("无法开放单人世界"))
            }
        } catch (error: Throwable) {
            logger.error("Failed to publish integrated server for DM room {}", normalizedRoom, error)
            return Result.failure(IllegalStateException("无法开放单人世界", error))
        }
        val port = server.port
        if (port !in 1..65535) {
            return Result.failure(IllegalStateException("单人世界端口无效"))
        }
        val hosting = Hosting(generationCounter.incrementAndGet(), server, endpoint, normalizedRoom, port)
        hosting.tunnel = DmHostTunnel(endpoint, normalizedRoom, port, object : DmHostTunnel.Listener {
            override fun onReady(session: UUID, gamePort: Int) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    if (gamePort !in 1..65535) {
                        logger.error("DM gateway returned invalid game port {} for room {}", gamePort, normalizedRoom)
                        stop()
                        return@execute
                    }
                    hosting.state = State.Connected
                    hosting.session = session
                    hosting.joinAddress = endpoint.gameAddress(gamePort)
                    sendMessage(minecraft, clickableAddress(hosting.joinAddress!!))
                }
            }

            override fun onRetry(attempt: Int, reason: String) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    if (hosting.state != State.Retrying) {
                        sendMessage(minecraft, "DM网关连接中断，正在重试")
                    }
                    hosting.state = State.Retrying
                    hosting.session = null
                    hosting.joinAddress = null
                }
            }
        })
        active = hosting
        try {
            hosting.tunnel.start()
        } catch (error: Throwable) {
            active = null
            hosting.tunnel.close()
            logger.error("Failed to start DM tunnel for room {}", normalizedRoom, error)
            return Result.failure(IllegalStateException("无法启动DM网关连接", error))
        }
        sendMessage(minecraft, "正在连接DM网关，房间：${normalizedRoom}")
        return Result.success(Unit)
    }

    fun stop(): Boolean {
        val current = active ?: return false
        active = null
        current.tunnel.close()
        return true
    }

    fun onTick() {
        val current = active ?: return
        val minecraft = Minecraft.getInstance()
        if (!minecraft.hasSingleplayerServer() || minecraft.getSingleplayerServer() !== current.owner) {
            stop()
        }
    }

    fun status(): String {
        val current = active ?: return "DM房主：未运行"
        val state = when (current.state) {
            State.Connecting -> "连接中"
            State.Retrying -> "重试中"
            State.Connected -> "已连接"
        }
        val join = current.joinAddress?.let { "，加入地址：${it}" } ?: ""
        return "DM房主：${state}，房间：${current.room}，网关：${current.endpoint.displayHost}:${current.endpoint.port}${join}"
    }

    private fun isCurrent(hosting: Hosting): Boolean {
        val minecraft = Minecraft.getInstance()
        return active === hosting &&
            active?.generation == hosting.generation &&
            minecraft.hasSingleplayerServer() &&
            minecraft.getSingleplayerServer() === hosting.owner
    }

    private fun clickableAddress(address: String): Component =
        Component.literal("DM加入地址：${address}").withStyle(ChatFormatting.UNDERLINE).withStyle { style ->
            style.withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, address))
        }

    private fun sendMessage(minecraft: Minecraft, message: String) {
        sendMessage(minecraft, Component.literal(message))
    }

    private fun sendMessage(minecraft: Minecraft, message: Component) {
        minecraft.player?.displayClientMessage(message, false) ?: minecraft.gui.chat.addMessage(message)
    }
}
