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
import net.neoforged.neoforge.event.tick.ServerTickEvent
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
                null
            }
            val snapshotCommand = Commands.literal("snapshot-test").executes { context ->
                    val result = DmSnapshotTestService.start(DmSnapshotMode.Disk)
                    result.onFailure { context.source.sendFailure(Component.literal(it.message ?: "无法开始同步区块快照测试")) }
                    result.onSuccess { context.source.sendSuccess({ Component.literal("同步区块快照测试（disk）已开始，结果将保存在本地") }, false) }
                    if (result.isSuccess) 1 else 0
                }
                .then(Commands.literal("disk").executes { context ->
                        val result = DmSnapshotTestService.start(DmSnapshotMode.Disk)
                        result.onFailure { context.source.sendFailure(Component.literal(it.message ?: "无法开始同步区块快照测试")) }
                        result.onSuccess { context.source.sendSuccess({ Component.literal("同步区块快照测试（disk）已开始，结果将保存在本地") }, false) }
                        if (result.isSuccess) 1 else 0
                    })
                    .then(Commands.literal("memory").executes { context ->
                        val result = DmSnapshotTestService.start(DmSnapshotMode.Memory)
                        result.onFailure { context.source.sendFailure(Component.literal(it.message ?: "无法开始同步区块快照测试")) }
                        result.onSuccess { context.source.sendSuccess({ Component.literal("同步区块快照测试（memory）已开始，结果将保存在本地") }, false) }
                        if (result.isSuccess) 1 else 0
                    })
            val command = Commands.literal("dm").then(snapshotCommand)
            if (config != null) {
                command
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
            }
            event.dispatcher.register(command)
            if (config != null) logger.debug("DM commands enabled for {}", config.baseUri)
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
                DmSnapshotTestService.cancel()
                DmWorldSyncService.stop()
                DmHostCreationService.cancel()
                DmHostService.stop()
            }
        }

        @SubscribeEvent
        @JvmStatic
        fun onGameShuttingDown(@Suppress("UNUSED_PARAMETER") event: GameShuttingDownEvent) {
            onClientThread {
                DmSnapshotTestService.cancel()
                DmWorldSyncService.stop()
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

        @SubscribeEvent
        @JvmStatic
        fun onServerTick(event: ServerTickEvent.Pre) {
            DmSnapshotTestService.onServerTick(event)
        }

        @SubscribeEvent
        @JvmStatic
        fun onServerTickPost(event: ServerTickEvent.Post) {
            DmSnapshotTestService.onServerTickPost(event)
            DmWorldSyncService.onServerTickPost(event)
        }

        private fun onClientThread(action: () -> Unit) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.isSameThread()) action() else minecraft.execute(Runnable { action() })
        }
    }
}
