package calebxzhou.rdi.mc.server

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.server.region.RegionZstdCodec
import calebxzau.mc.common2021.RdiLoggingConfiguration
import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.common.WebSocketClient
import calebxzau.mc.common2021.mcs
import calebxzau.mc.common2021.sendMessage
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState
import calebxzhou.rdi.mc.rcmd.tpa.TpaService
import calebxzhou.rdi.mc.server.firmsection.FirmSectionService
import calebxzhou.rdi.mc.server.network.RServerNetwork
import calebxzhou.rdi.mc.server.rcmd.PlayerNbtChatRangeStore
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameRules
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * calebxzhou @ 2026-01-07 11:00
 */
@Mod("rdi")
@EventBusSubscriber(modid = "rdi")
class RDIMain {
    init {
        RdiLoggingConfiguration.reapplyConfiguredLog4j2()
    }

    companion object {
        init {
            ZstdCompressionPipeline.verifyNativeLoaded()
        }

        val lgr: Logger = LogManager.getLogger("rdi")


        @SubscribeEvent
        @JvmStatic
        fun started(e: ServerStartedEvent) {
            WebSocketClient.start(WsHandler211(e.getServer() as DedicatedServer))
        }

        @SubscribeEvent @JvmStatic
        fun stopped(e: ServerStoppedEvent) {
            RegionZstdCodec.closeAll()
            PlayerChatRangeState.clear()
            TpaService.clear()
            WebSocketClient.stop()
        }

        @SubscribeEvent @JvmStatic
        fun starting(e: ServerStartingEvent) {
            val server = e.getServer() as DedicatedServer
            mcs = server
            GameRules.visitGameRuleTypes(object : GameRules.GameRuleTypeVisitor {
                override fun <T : GameRules.Value<T>> visit(key: GameRules.Key<T>, type: GameRules.Type<T>) {
                    val gameRuleEnv = System.getenv("GAME_RULE_" + key.getId())

                    if (gameRuleEnv != null) {
                        val rule: GameRules.Value<*> = server.gameRules.getRule<T>(key)

                        if (rule is GameRules.BooleanValue) {
                            rule.set(gameRuleEnv.toBoolean(), server)
                            lgr.info("SET GAME RULE {}={}  B", key, gameRuleEnv)
                        } else if (rule is GameRules.IntegerValue) {
                            rule.set(gameRuleEnv.toInt(), server)
                            lgr.info("SET GAME RULE {}={}  I", key, gameRuleEnv)
                        }
                    }
                }
            })
        }

        @SubscribeEvent @JvmStatic
        fun onPlayerJoin(e: PlayerEvent.PlayerLoggedInEvent) {
            val player = e.entity as ServerPlayer
            PlayerChatRangeState.restore(player.uuid, PlayerNbtChatRangeStore(player))
            if (RDI.isAllOp()) {
                player.server.playerList.op(player.gameProfile)
            }
            val server = player.server
            val playerId = player.uuid
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute {
                server.execute {
                    val onlinePlayer = server.playerList.getPlayer(playerId)
                    if (onlinePlayer != null) {
                        sendJoinMessages(onlinePlayer)
                    }
                }
            }
            //------
            RServerNetwork.sendLastTo(player)
            RServerNetwork.sendFirmSectionsTo(player)
        }

        private fun sendJoinMessages(player: ServerPlayer) {
            val range = PlayerChatRangeState.get(player.getUUID())
            val result = FirmSectionService.list(player)
            player.sendMessage("当前聊天范围：" + range.displayName)
            player.sendSystemMessage(Component.literal("点此打开RDI说明书").withStyle(ChatFormatting.UNDERLINE).withStyle(
                Style.EMPTY.withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL,"https://craftrdi.feishu.cn/wiki/U8LRwMpUliuxW5kZLvCcxonNnkd"))))
        }

        @SubscribeEvent @JvmStatic
        fun onPlayerLogout(e: PlayerEvent.PlayerLoggedOutEvent) {
            val player = e.entity as ServerPlayer
            PlayerChatRangeState.remove(player.getUUID())
            TpaService.removeRelated(player.getUUID())
        }
    }
}
