package calebxzau.rdi.mc.client.dm

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.event.GameShuttingDownEvent
import org.slf4j.LoggerFactory

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
class DmClientEvents {
    companion object {
        private val logger = LoggerFactory.getLogger(DmClientEvents::class.java)

        @SubscribeEvent
        @JvmStatic
        fun registerCommands(event: RegisterClientCommandsEvent) {
            val config = DmConfig.fromSystemProperty().getOrElse { error ->
                logger.error("Invalid rdi.dm.server; DM commands disabled", error)
                return
            } ?: return
            event.dispatcher.register(
                Commands.literal("dm")
                    .then(
                        Commands.literal("create")
                            .then(
                                Commands.argument("name", StringArgumentType.greedyString())
                                    .executes { context -> create(context.source, StringArgumentType.getString(context, "name")) }
                            )
                            .executes { context ->
                                context.source.sendFailure(Component.literal("用法：/dm create <名称>"))
                                0
                            }
                    )
                    .then(
                        Commands.literal("status").executes { context ->
                            context.source.sendSuccess({ Component.literal(DmHostService.status()) }, false)
                            1
                        }
                    )
            )
            logger.debug("DM commands enabled for {}", config.baseUri)
        }

        private fun create(source: CommandSourceStack, name: String): Int {
            val result = runCatching { DmHostCreationService.create(name) }.getOrElse { Result.failure(it) }
            result.onFailure { source.sendFailure(Component.literal(it.message ?: "无法创建DM房间")) }
            result.onSuccess { source.sendSuccess({ Component.literal("DM房间创建中") }, false) }
            return if (result.isSuccess) 1 else 0
        }

        @SubscribeEvent
        @JvmStatic
        fun onLoggingIn(@Suppress("UNUSED_PARAMETER") event: ClientPlayerNetworkEvent.LoggingIn) {
            onClientThread { DmHostService.autoStartIfAssociated() }
        }

        @SubscribeEvent
        @JvmStatic
        fun onLoggingOut(@Suppress("UNUSED_PARAMETER") event: ClientPlayerNetworkEvent.LoggingOut) {
            onClientThread {
                DmHostCreationService.cancel()
                DmHostService.stop()
            }
        }

        @SubscribeEvent
        @JvmStatic
        fun onGameShuttingDown(@Suppress("UNUSED_PARAMETER") event: GameShuttingDownEvent) {
            onClientThread {
                DmHostCreationService.cancel()
                DmHostService.stop()
            }
        }

        @SubscribeEvent
        @JvmStatic
        fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
            onClientThread {
                DmHostCreationService.onTick()
                DmHostService.onTick()
            }
        }

        private fun onClientThread(action: () -> Unit) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.isSameThread()) action() else minecraft.execute(Runnable { action() })
        }
    }
}
