package calebxzau.rdi.mc.client.dm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineLabel
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class DmJoinScreen(
    private val parent: Screen,
    private val initialHostId: UUID? = null,
    private var autoJoin: Boolean = false,
) : Screen(Component.literal("加入DM房间")) {
    private val generation = AtomicLong()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private lateinit var hostIdBox: EditBox
    private var message = Component.empty()
    private var joinButton: Button? = null
    private var redownloadButton: Button? = null
    private var takeoverButton: Button? = null
    /** The room last found without a host; the takeover button acts on it until the ID or the query changes. */
    private var offlineRoom: OfflineRoom? = null
    /** The room fingerprint whose version differences the player chose to join anyway. */
    @Volatile private var acceptedMods: String? = null

    override fun init() {
        hostIdBox = EditBox(font, width / 2 - 120, height / 2 - 30, 240, 20, Component.literal("房间ID"))
        hostIdBox.setMaxLength(36)
        initialHostId?.let { hostIdBox.value = it.toString() }
        hostIdBox.setResponder { forgetOfflineRoom() }
        addRenderableWidget(hostIdBox)
        joinButton = addRenderableWidget(Button.builder(Component.literal("加入/重连")) { join(false) }
            .bounds(width / 2 - 120, height / 2 + 5, 115, 20).build())
        redownloadButton = addRenderableWidget(Button.builder(Component.literal("重新下载资料")) { join(true) }
            .bounds(width / 2 + 5, height / 2 + 5, 115, 20).build())
        takeoverButton = addRenderableWidget(Button.builder(Component.literal("接管房间")) { takeOver() }
            .bounds(width / 2 - 120, height / 2 + 32, 240, 20).build())
            .also { it.visible = offlineRoom != null }
        addRenderableWidget(Button.builder(Component.literal("返回")) { onClose() }
            .bounds(width / 2 - 120, height / 2 + 59, 240, 20).build())
        if (autoJoin) {
            autoJoin = false
            join(false)
        }
    }

    private fun join(forceDownload: Boolean) {
        val id = runCatching { UUID.fromString(hostIdBox.value.trim()) }.getOrElse {
            message = Component.literal("房间ID格式无效")
            return
        }
        val config = DmConfig.fromSystemProperty().getOrElse {
            message = Component.literal("DM服务器配置无效")
            return
        } ?: run {
            message = Component.literal("DM服务器未配置")
            return
        }
        val token = generation.incrementAndGet()
        forgetOfflineRoom()
        joinButton?.active = false
        redownloadButton?.active = false
        message = Component.literal("正在查询房间…")
        executor.submit {
            runCatching {
                val client = DmHttpClient(config)
                val room = client.getHost(id).getOrThrow()
                val mods = checkMods(client, id, room)
                // An offline room cannot be joined anyway, and its takeover may update the room's mods.
                if (room.gamePort != null) {
                    joinBlocker(mods, room)?.let { text ->
                        update(token, text)
                        return@runCatching null
                    }
                }
                val cache = DmWorldCache(Minecraft.getInstance().gameDirectory.toPath(), id)
                if (forceDownload || !cache.isPrepared) {
                    update(token, "正在下载世界资料…")
                    cache.downloadAndReplace(client) { generation.get() != token }.getOrThrow()
                }
                val host = client.getHost(id).getOrThrow()
                if (host.gamePort == null || host.gamePort !in 1..65535) {
                    if (DmTakeoverService.canStartManually(host)) {
                        update(token, if (mods.detail == null) {
                            "房主当前离线，可以接管房间，由你来当房主"
                        } else {
                            "房主当前离线，可以接管房间。你的Mod和房间不一致，接管时要确认按你的Mod更新房间。${mods.detail}"
                        })
                        offerTakeover(token, OfflineRoom(id, config))
                    } else {
                        update(token, "世界资料已准备，房主当前离线")
                    }
                    return@runCatching null
                }
                update(token, "正在连接房间…")
                val joinTicket = DmTickets.joinTicket(id).getOrThrow()
                val latestInfo = client.info().getOrThrow()
                val publicHost = latestInfo.publicHost ?: config.baseUri.host
                val endpoint = DmEndpoint.parse("${formatHost(publicHost)}:${host.gamePort}").getOrThrow()
                JoinTarget(id, config, endpoint.host, host.gamePort, host.name, endpoint.gameAddress(host.gamePort), joinTicket)
            }.onSuccess { target ->
                Minecraft.getInstance().execute {
                    if (generation.get() != token) return@execute
                    if (target == null) {
                        joinButton?.active = true
                        redownloadButton?.active = true
                        return@execute
                    }
                    DmParticipantSession.expect(target.hostId, target.config, target.address, target.joinTicket)
                    ConnectScreen.startConnecting(
                        parent,
                        Minecraft.getInstance(),
                        ServerAddress(target.host, target.port),
                        ServerData(target.name, target.address, ServerData.Type.OTHER),
                        false,
                        null,
                    )
                }
            }.onFailure { error ->
                update(token, "加入失败：${error.message ?: "未知错误"}")
                Minecraft.getInstance().execute {
                    if (generation.get() == token) {
                        joinButton?.active = true
                        redownloadButton?.active = true
                    }
                }
            }
        }
    }

    /** Compares the static registries with the room's; [ModCheck.detail] names the differing mods. */
    private fun checkMods(client: DmHttpClient, id: UUID, host: DmHostInfo): ModCheck {
        val local = DmModFingerprints.local(null)
        if (host.modFingerprint == null || host.modFingerprint == local.registries.overall) return ModCheck(null, null)
        val room = client.getHostMods(id).getOrThrow() ?: return ModCheck(null, null)
        val difference = DmModFingerprint.diff(room.fingerprint, local)
        if (difference.isEmpty) return ModCheck(null, null)
        return ModCheck(difference, DmModFingerprint.describe(difference, room.fingerprint, local))
    }

    /**
     * A player missing mods cannot get in (NeoForge would refuse the connection anyway), other
     * version differences need a second click, and extra mods only rule out taking over.
     */
    private fun joinBlocker(mods: ModCheck, host: DmHostInfo): String? {
        val difference = mods.difference ?: return null
        return when {
            difference.missing.isNotEmpty() -> "你的整合包缺少房间需要的Mod，无法加入。${mods.detail}"
            difference.changed.isNotEmpty() && acceptedMods != host.modFingerprint -> {
                acceptedMods = host.modFingerprint
                "这些Mod和房间里的版本不同，可能无法进入，再点一次「加入/重连」继续。${mods.detail}"
            }
            else -> null
        }
    }

    private fun offerTakeover(token: Long, room: OfflineRoom) {
        Minecraft.getInstance().execute {
            if (generation.get() != token) return@execute
            offlineRoom = room
            takeoverButton?.visible = true
        }
    }

    private fun forgetOfflineRoom() {
        offlineRoom = null
        takeoverButton?.visible = false
    }

    private fun takeOver() {
        val room = offlineRoom ?: return
        generation.incrementAndGet()
        DmTakeoverService.beginManual(room.hostId, room.config, parent)
    }

    private fun update(token: Long, text: String) {
        Minecraft.getInstance().execute {
            if (generation.get() == token) message = Component.literal(text)
        }
    }

    override fun onClose() {
        generation.incrementAndGet()
        super.onClose()
    }

    override fun removed() {
        generation.incrementAndGet()
        executor.shutdownNow()
        super.removed()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Screen.render draws the blurred background first; text drawn before it would be blurred too.
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 65, 0xFFFFFF)
        MultiLineLabel.create(font, message, width - 40).renderCentered(guiGraphics, width / 2, height / 2 + 90)
    }

    private data class OfflineRoom(val hostId: UUID, val config: DmConfig)

    /** Both null when the mods match the room's or the room has not recorded any. */
    private data class ModCheck(val difference: DmModFingerprint.Difference?, val detail: String?)

    private data class JoinTarget(
        val hostId: UUID,
        val config: DmConfig,
        val host: String,
        val port: Int,
        val name: String,
        val address: String,
        val joinTicket: String,
    )

    companion object {
        private fun formatHost(value: String): String = when {
            value.startsWith("[") && value.endsWith("]") -> value
            value.contains(':') -> "[$value]"
            else -> value
        }
    }
}
