package calebxzau.rdi.mc.client.dm

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.event.GameShuttingDownEvent

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
class DmClientEvents {
    companion object {
        @SubscribeEvent
        @JvmStatic
        fun registerCommands(event: RegisterClientCommandsEvent) {
            event.dispatcher.register(
                Commands.literal("dm")
                    .then(
                        Commands.literal("host")
                            .then(
                                Commands.argument("gatewayAndRoom", StringArgumentType.greedyString())
                                    .executes { context -> host(context.source, StringArgumentType.getString(context, "gatewayAndRoom")) }
                            )
                            .executes { context ->
                                context.source.sendFailure(Component.literal("用法：/dm host <gateway:port> <room>"))
                                0
                            }
                    )
                    .then(
                        Commands.literal("status").executes { context ->
                            context.source.sendSuccess({ Component.literal(DmHostService.status()) }, false)
                            1
                        }
                    )
                    .then(
                        Commands.literal("stop").executes { context ->
                            val stopped = DmHostService.stop()
                            context.source.sendSuccess({
                                Component.literal(if (stopped) "DM房主已停止" else "DM房主未运行")
                            }, false)
                            if (stopped) 1 else 0
                        }
                    )
            )
        }

        private fun host(source: CommandSourceStack, raw: String): Int {
            val separator = raw.indexOfFirst { it.isWhitespace() }
            if (separator < 0) {
                source.sendFailure(Component.literal("用法：/dm host <gateway:port> <room>"))
                return 0
            }
            val gateway = raw.substring(0, separator)
            val room = raw.substring(separator).trim()
            if (room.isEmpty()) {
                source.sendFailure(Component.literal("用法：/dm host <gateway:port> <room>"))
                return 0
            }
            val result = DmHostService.start(gateway, room)
            result.onFailure { source.sendFailure(Component.literal(it.message ?: "无法启动DM房主")) }
            result.onSuccess { source.sendSuccess({ Component.literal("DM房主启动中") }, false) }
            return if (result.isSuccess) 1 else 0
        }

        @SubscribeEvent
        @JvmStatic
        fun onLoggingOut(@Suppress("UNUSED_PARAMETER") event: ClientPlayerNetworkEvent.LoggingOut) {
            onClientThread { DmHostService.stop() }
        }

        @SubscribeEvent
        @JvmStatic
        fun onGameShuttingDown(@Suppress("UNUSED_PARAMETER") event: GameShuttingDownEvent) {
            onClientThread { DmHostService.stop() }
        }

        @SubscribeEvent
        @JvmStatic
        fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
            onClientThread { DmHostService.onTick() }
        }

        private fun onClientThread(action: () -> Unit) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.isSameThread()) action() else minecraft.execute(Runnable { action() })
        }
    }
}
