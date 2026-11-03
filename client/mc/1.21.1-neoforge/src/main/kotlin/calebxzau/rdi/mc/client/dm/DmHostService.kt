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
import calebxzau.rdi.mc.client.syncchunk.SyncChunkService

object DmHostService {
    private val logger = LoggerFactory.getLogger(DmHostService::class.java)
    private val generationCounter = AtomicLong()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    @Volatile
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
        var keepNoticeShown = false
        var mods: DmModFingerprint? = null
        var sessionId: UUID? = null
        /** Set when the player saves and quits; the session stays up for the final checkpoint. */
        @Volatile var leaving = false
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
        val association = DmWorldAssociationStore.read(server.getWorldPath(LevelResource.ROOT)).getOrElse { error ->
            logger.error("Failed to read DM world association for {}", hostId, error)
            return Result.failure(IllegalStateException("无法读取DM房间关联", error))
        }
        val mods = runCatching { DmModFingerprints.local(server) }.getOrElse { error ->
            logger.error("Failed to read the mod fingerprint for DM host {}", hostId, error)
            return Result.failure(IllegalStateException("无法读取当前的Mod列表", error))
        }
        val hosting = Hosting(generationCounter.incrementAndGet(), server, hostId, config)
        hosting.mods = mods
        active = hosting
        executor.submit {
            val client = DmHttpClient(config)
            val stale = client.getHost(hostId).map { host -> association?.isStaleAgainst(host.checkpointCycleId) == true }
            if (stale.getOrDefault(false) || stale.isFailure) {
                stale.exceptionOrNull()?.let { error -> logger.error("Failed to check DM world freshness for {}", hostId, error) }
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    active = null
                    DmTakeoverService.onHostingFailed(hostId)
                    sendMessage(minecraft, if (stale.isFailure) {
                        "无法确认DM房间状态：${stale.exceptionOrNull()?.message ?: "网络错误"}"
                    } else {
                        "房间已由其他玩家接管，这个世界已过期。请通过「加入DM房间」进入"
                    })
                }
                return@submit
            }
            val room = client.getHostMods(hostId).getOrElse { error ->
                logger.error("Failed to read the mods of DM host {}", hostId, error)
                failHosting(minecraft, hosting, "无法确认房间的Mod：${error.message ?: "网络错误"}")
                return@submit
            }
            // Rooms from before fingerprints, or created by an older client, record one on the next checkpoint.
            val difference = room?.let { DmModFingerprint.diff(it.fingerprint, mods) }
            if (room != null && difference != null && !difference.isEmpty) {
                val detail = DmModFingerprint.describe(difference, room.fingerprint, mods)
                logger.warn("Mods differ from DM host {}: {}", hostId, detail)
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    // Nobody asked an automatic takeover winner to change the room; let someone else host.
                    if (DmTakeoverService.isAutomatic(hostId)) {
                        failHosting(minecraft, hosting, "你的Mod和房间不一致，已停止托管，房间里的世界没有改动。$detail")
                        return@execute
                    }
                    minecraft.setScreen(DmModUpdateScreen(
                        detail,
                        onConfirm = {
                            logger.info("Updating the mods of DM host {} to this player's", hostId)
                            executor.submit { connect(minecraft, hosting, client) }
                        },
                        onCancel = { failHosting(minecraft, hosting, "已取消托管，房间里的世界没有改动") },
                    ))
                }
                return@submit
            }
            connect(minecraft, hosting, client)
        }
        sendMessage(minecraft, "正在连接DM房间 ${hostId}")
        return Result.success(Unit)
    }

    /** Runs on [executor]: looks up the gateway, then opens the tunnel on the client thread. */
    private fun connect(minecraft: Minecraft, hosting: Hosting, client: DmHttpClient) {
        val config = hosting.config
        client.info().onSuccess { info ->
            val publicHost = info.publicHost ?: config.baseUri.host
            val endpoint = DmEndpoint.parse("${formatHost(publicHost)}:${info.tunnelPort}")
                .getOrElse { error -> throw IllegalArgumentException("DM网关地址无效：${error.message}", error) }
            info.serverTimeSeconds?.let(DmJoinGate::syncClock)
            minecraft.execute {
                if (!isCurrent(hosting)) return@execute
                startTunnel(minecraft, hosting, endpoint)
            }
        }.onFailure { error ->
            logger.error("Failed to query DM gateway information", error)
            failHosting(minecraft, hosting, "无法取得DM网关信息：${error.message ?: "网络错误"}")
        }
    }

    private fun failHosting(minecraft: Minecraft, hosting: Hosting, message: String) {
        minecraft.execute {
            if (!isCurrent(hosting)) return@execute
            active = null
            DmTakeoverService.onHostingFailed(hosting.hostId)
            sendMessage(minecraft, message)
        }
    }

    /** The room [server] is hosting, if any. Called from login threads. */
    fun hostedRoom(server: IntegratedServer): UUID? = active?.takeIf { it.owner === server }?.hostId

    fun autoStartIfAssociated() {
        val minecraft = Minecraft.getInstance()
        val server = minecraft.getSingleplayerServer() ?: return
        val root = server.getWorldPath(LevelResource.ROOT)
        DmWorldAssociationStore.read(root).onSuccess { association ->
            association?.let {
                start(it.hostId).onFailure { error ->
                    logger.warn("DM自动托管失败", error)
                    DmTakeoverService.onHostingFailed(it.hostId)
                }
            }
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
            DmTakeoverService.onHostingFailed(hosting.hostId)
            sendMessage(minecraft, "单人世界端口无效")
            return
        }
        val claimId = DmTakeoverService.claimFor(hosting.hostId)
        val tunnel = DmHostTunnel(endpoint, hosting.hostId, targetPort, claimId = claimId, listener = object : DmHostTunnel.Listener {
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
                    hosting.sessionId = session
                    SyncChunkService.begin(hosting.hostId, session, hosting.owner)
                    DmWorldCheckpointService.begin(hosting.hostId, session, hosting.owner, hosting.config, hosting.mods, DmModFingerprints.playerId())
                    DmTakeoverService.onHostingReady(hosting.hostId)
                    sendMessage(minecraft, clickableAddress(endpoint.gameAddress(gamePort)))
                    if (!hosting.keepNoticeShown) {
                        hosting.keepNoticeShown = true
                        sendMessage(minecraft, "房间只保存同步区块和玩家附近的区域，换房主后其他地方会重新生成")
                    }
                }
            }

            override fun onRetry(attempt: Int, reason: String) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    if (hosting.state != State.Retrying) sendMessage(minecraft, "DM网关连接中断，正在重试")
                    hosting.state = State.Retrying
                    hosting.gamePort = null
                    DmWorldCheckpointService.pause(hosting.owner, "DM网关连接中断，世界同步暂时不可用")
                    SyncChunkService.pause(hosting.owner, "DM网关连接中断，世界同步暂时不可用")
                }
            }

            override fun onTerminal(failure: DmHostTunnel.TerminalFailure) {
                minecraft.execute {
                    if (!isCurrent(hosting)) return@execute
                    active = null
                    hosting.tunnel = null
                    if (failure == DmHostTunnel.TerminalFailure.ClaimRefused) {
                        DmTakeoverService.onClaimRefused(hosting.hostId)
                    } else {
                        DmTakeoverService.onHostingFailed(hosting.hostId)
                    }
                    DmWorldCheckpointService.stop(hosting.owner)
                    SyncChunkService.stop(hosting.owner)
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
            DmTakeoverService.onHostingFailed(hosting.hostId)
            DmWorldCheckpointService.stop(hosting.owner)
            SyncChunkService.stop(hosting.owner)
            logger.error("Failed to start DM tunnel for host {}", hosting.hostId, error)
            sendMessage(minecraft, "无法启动DM网关连接：${error.message ?: "未知错误"}")
        }
    }

    /**
     * Called as the player leaves the hosted world. A connected host keeps its gateway session so the
     * world can finish saving and upload a final checkpoint ([announceLeaving], [finishLeaving]);
     * returns false when there is nothing to keep and the caller should stop as before.
     */
    fun beginLeaving(): Boolean {
        val current = active ?: return false
        if (current.state != State.Connected || current.sessionId == null) return false
        // Minecraft.disconnect clears singleplayerServer before LoggingOut fires; the server thread is still running.
        if (current.owner.isShutdown) return false
        current.leaving = true
        logger.info("DM host {} is leaving; keeping the session for the final checkpoint", current.hostId)
        return true
    }

    fun isLeaving(server: IntegratedServer): Boolean = active?.let { it.leaving && it.owner === server } ?: false

    /** Server thread, before guests are disconnected, so they wait instead of seeing the room online. */
    fun announceLeaving(server: IntegratedServer) {
        val current = active?.takeIf { it.leaving && it.owner === server } ?: return
        val session = current.sessionId ?: return
        DmHttpClient(current.config).announceLeaving(current.hostId, session)
            .onFailure { error -> logger.warn("Failed to tell the DM master that host {} is leaving", current.hostId, error) }
    }

    /** Server thread, after the final checkpoint: closes the session so the room goes offline. */
    fun finishLeaving(server: IntegratedServer) {
        val current = active?.takeIf { it.leaving && it.owner === server } ?: return
        current.tunnel?.close()
        Minecraft.getInstance().execute { if (active === current) stop() }
    }

    fun stop(): Boolean {
        val current = active ?: return false
        active = null
        current.tunnel?.close()
        DmWorldCheckpointService.stop(current.owner)
        SyncChunkService.stop(current.owner)
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
        return "DM房主：${state}，房间：${current.hostId}${join}；${DmWorldCheckpointService.status()}"
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
