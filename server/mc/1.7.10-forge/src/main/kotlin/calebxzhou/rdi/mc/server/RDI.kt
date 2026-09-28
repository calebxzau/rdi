package calebxzhou.rdi.mc.server

import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.common.WebSocketClient
import calebxzhou.rdi.mc.common.WsMessage
import calebxzhou.rdi.mc.rcmd.Rcmd
import calebxzhou.rdi.mc.rcmd.RcmdSource
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState
import calebxzhou.rdi.mc.rcmd.chat.RChatMessage
import calebxzhou.rdi.mc.rcmd.tpa.TpaService
import calebxzhou.rdi.mc.server.mcp.McpServerNetwork1710
import calebxzhou.rdi.mc.server.network.RServerNetwork
import calebxzhou.rdi.mc.server.rcmd.PlayerNbtChatRangeStore
import calebxzhou.rdi.mc.server.rcmd.RcmdServerCommands1710
import calebxzhou.rdi.mc.server.rcmd.RcmdServerSource1710
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLServerStartedEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import cpw.mods.fml.common.event.FMLServerStoppedEvent
import cpw.mods.fml.common.eventhandler.EventPriority
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.PlayerEvent
import cpw.mods.fml.common.gameevent.TickEvent
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.util.ChatComponentText
import net.minecraftforge.common.DimensionManager
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.ServerChatEvent
import net.minecraftforge.event.world.BlockEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.UUID

@Mod(
    modid = "rdi",
    name = "rdi",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*"
)
class RDI {
    private val pendingJoinMessages = mutableListOf<PendingJoinMessage>()
    private var serverTick = 0L

    init {
        RServerNetwork.register()
        McpServerNetwork1710.register()
        FMLCommonHandler.instance().bus().register(this)
        MinecraftForge.EVENT_BUS.register(this)
    }

    @Mod.EventHandler
    fun starting(event: FMLServerStartingEvent) {
        server = event.server as DedicatedServer
    }

    @Mod.EventHandler
    fun started(event: FMLServerStartedEvent?) {
        RcmdServerCommands1710.init(server)
        applyGameRules()
        WebSocketClient.start(WsHandler1710(server))
    }

    @Mod.EventHandler
    fun stopped(event: FMLServerStoppedEvent) {
        WebSocketClient.stop()
        PlayerChatRangeState.clear()
        TpaService.clear()
    }

    @SubscribeEvent
    fun onPlayerJoin(event: PlayerEvent.PlayerLoggedInEvent) {

        val player = event.player as EntityPlayerMP
        val playerId = player.getUniqueID()
        PlayerChatRangeState.restore(playerId, PlayerNbtChatRangeStore(player))
        pendingJoinMessages += PendingJoinMessage(playerId, serverTick + JOIN_MESSAGE_DELAY_TICKS)

        RServerNetwork.sendLastTo(player)

        if (!RDI.isAllOp()) {
            return
        }

        val profile = player.gameProfile
        server.configurationManager.func_152605_a(profile)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        serverTick++
        val iterator = pendingJoinMessages.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (serverTick < pending.sendAtTick) {
                continue
            }
            iterator.remove()
            val player = findOnlinePlayer(pending.playerId)
            if (player != null) {
                sendJoinMessages(player)
            }
        }
    }

    private fun findOnlinePlayer(playerId: UUID): EntityPlayerMP? {
        for (onlinePlayer in server.configurationManager.playerEntityList) {
            if (onlinePlayer is EntityPlayerMP && onlinePlayer.getUniqueID() == playerId) {
                return onlinePlayer
            }
        }
        return null
    }

    private fun sendJoinMessages(player: EntityPlayerMP) {
        val range = PlayerChatRangeState.get(player.getUniqueID())
        player.addChatMessage(ChatComponentText("当前聊天范围：" + range.displayName))
    }

    @SubscribeEvent
    fun onPlayerLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val playerId = event.player.getUniqueID()
        pendingJoinMessages.removeAll { it.playerId == playerId }
        PlayerChatRangeState.remove(playerId)
        TpaService.removeRelated(playerId)
    }

    @SubscribeEvent
    fun onServerChat(event: ServerChatEvent) {
        val message = event.message
        val player = event.player
        if (Rcmd.isRcmd(message)) {
            event.setCanceled(true)
            val source: RcmdSource = RcmdServerSource1710(player)
            val result = RcmdServerCommands1710.dispatcher().execute(source, message)
            RcmdServerCommands1710.reply(source, result)
            return
        }

        if (PlayerChatRangeState.isGlobal(player.getUniqueID())) {
            event.setCanceled(true)
            val chatMessage = RChatMessage(
                UUID.randomUUID().toString(),
                RDI.HOST_ID,
                player.getUniqueID().toString(),
                player.getCommandSenderName(),
                message,
                System.currentTimeMillis(),
                true
            )
            if (WebSocketClient.sendMessage<RChatMessage>(WsMessage.Channel.Chat, chatMessage)) {
                val component = ChatComponentText("[公共] " + player.getCommandSenderName() + ": " + message)
                server.configurationManager.playerEntityList
                    .filterIsInstance<EntityPlayerMP>()
                    .filter { PlayerChatRangeState.isGlobal(it.uniqueID) }
                    .forEach { it.addChatMessage(component) }
            } else {
                player.addChatMessage(ChatComponentText("聊天服务未连接"))
            }
        } else {
            val sent = WebSocketClient.sendMessage<RChatMessage>(
                WsMessage.Channel.Chat, RChatMessage(
                    UUID.randomUUID().toString(),
                    RDI.HOST_ID,
                    player.getUniqueID().toString(),
                    player.getCommandSenderName(),
                    message,
                    System.currentTimeMillis(),
                    false
                )
            )
            if (!sent) {
                event.setCanceled(true)
                player.addChatMessage(ChatComponentText("聊天服务未连接"))
            }
        }
    }

    companion object {
        private val LOGGER: Logger = LogManager.getLogger("rdi")
        private const val JOIN_MESSAGE_DELAY_TICKS = 60L
        private lateinit var server: DedicatedServer

        private fun applyGameRules() {
            for (world in DimensionManager.getWorlds()) {
                if (world == null) {
                    continue
                }

                for (key in world.getGameRules().getRules()) {
                    val envValue = System.getenv("GAME_RULE_" + key)
                    if (envValue == null || envValue.isEmpty()) {
                        continue
                    }

                    world.getGameRules().setOrCreateGameRule(key, envValue)
                    LOGGER.info(
                        "SET GAME RULE {}={} dim={} world={}",
                        key,
                        envValue,
                        world.provider.dimensionId,
                        world.getWorldInfo().getWorldName()
                    )
                }
            }
        }
    }
}

private data class PendingJoinMessage(val playerId: UUID, val sendAtTick: Long)
