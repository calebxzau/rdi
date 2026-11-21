package calebxzau.rdi.mc.v20.fabric.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkCommands
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientCommands
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientState
import calebxzau.rdi.mc.v20.server.syncchunk.SyncChunkSavedData20
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.server.IntegratedServer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

object SyncChunkIntegratedFabric {
    private val logger = LoggerFactory.getLogger(SyncChunkIntegratedFabric::class.java)
    private val worldFolder = System.getProperty("rdi.syncChunkMarkingWorld")
    private var enteredWorld = false

    @JvmStatic
    val marking = System.getProperty("rdi.syncChunkMarking") == "true"

    private fun isMarkingWorld(server: MinecraftServer): Boolean =
        server is IntegratedServer && worldFolder != null &&
            server.getWorldPath(LevelResource.ROOT).normalize().fileName?.toString() == worldFolder

    fun register() {
        if (!marking || worldFolder == null) return
        CommandRegistrationCallback.EVENT.register commands@{ dispatcher, _, environment ->
            if (environment == Commands.CommandSelection.DEDICATED) return@commands
            SyncChunkCommands.register(dispatcher, { server ->
                if (isMarkingWorld(server)) SyncChunkSavedData20.of(server)
                else Result.failure(IllegalStateException("The save import copy is not open"))
            }, ::pushToClient)
            // A Fabric client command with this root would consume the server's subcommands as well.
            dispatcher.register(
                Commands.literal("syncchunk")
                    .then(Commands.literal("show").executes { context ->
                        showOutlines(context.source) { feedback ->
                            SyncChunkClientCommands.toggleShow({ it.isMemoryConnection }, feedback, feedback)
                        }
                    })
                    .then(Commands.literal("here").executes { context ->
                        showOutlines(context.source) { feedback -> SyncChunkClientCommands.toggleHere(feedback) }
                    }),
            )
        }
        ClientPlayConnectionEvents.JOIN.register { listener, _, client ->
            val server = client.singleplayerServer
            if (server != null && listener.connection.isMemoryConnection && isMarkingWorld(server)) {
                enteredWorld = true
                SyncChunkClientState.showSet = true
                server.execute { pushToClient(server) }
                client.player?.displayClientMessage(
                    Component.literal("标记模式：站在要保留的区块里输入/syncchunk add，未标记的区块将在房间中重新生成。完成后保存并退出。")
                        .withStyle(ChatFormatting.GOLD),
                    false,
                )
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { listener, _ ->
            SyncChunkClientState.clear(listener.connection)
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (enteredWorld && client.screen is TitleScreen) client.stop()
        }
    }

    private fun showOutlines(source: CommandSourceStack, toggle: ((Component) -> Unit) -> Unit): Int {
        val server = source.server
        if (!isMarkingWorld(server)) {
            source.sendFailure(Component.literal("请先打开要标记的存档副本"))
            return 0
        }
        val client = Minecraft.getInstance()
        client.execute {
            if (client.singleplayerServer === server) {
                toggle { message -> client.player?.displayClientMessage(message, false) }
            }
        }
        return 1
    }

    private fun pushToClient(server: MinecraftServer) {
        val chunks = SyncChunkSavedData20.of(server).mapCatching { SyncChunkList.of(it.snapshot()) }.getOrElse { error ->
            logger.error("Failed to show marked chunks", error)
            return
        }
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            if (minecraft.singleplayerServer === server) {
                minecraft.connection?.connection?.let { SyncChunkClientState.replace(it, chunks) }
            }
        }
    }
}
