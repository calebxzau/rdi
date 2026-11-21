package calebxzau.rdi.mc.client.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkCommands
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientState
import calebxzau.rdi.mc.syncchunk.server.SyncChunkSavedData
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.server.IntegratedServer
import net.minecraft.commands.Commands
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.event.CommandEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import org.slf4j.LoggerFactory

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object SyncChunkIntegratedNeoForge {
    private val logger = LoggerFactory.getLogger(SyncChunkIntegratedNeoForge::class.java)
    val marking = System.getProperty("rdi.syncChunkMarking") == "true"
    private val worldFolder = System.getProperty("rdi.syncChunkMarkingWorld")
    private var enteredWorld = false

    private fun isMarkingWorld(server: MinecraftServer): Boolean =
        marking && server is IntegratedServer &&
            server.getWorldPath(LevelResource.ROOT).normalize().fileName?.toString() == worldFolder

    fun supportsOutlines(connection: Connection): Boolean =
        connection.isMemoryConnection && Minecraft.getInstance().singleplayerServer?.let(::isMarkingWorld) == true

    @SubscribeEvent
    @JvmStatic
    fun registerCommands(event: RegisterCommandsEvent) {
        if (!marking || event.commandSelection == Commands.CommandSelection.DEDICATED) return
        SyncChunkCommands.register(event.dispatcher, { server ->
            if (isMarkingWorld(server)) SyncChunkSavedData.of(server)
            else Result.failure(IllegalStateException("The save import copy is not open"))
        }, ::pushToClient)
    }

    @SubscribeEvent
    @JvmStatic
    fun onLoggingIn(event: ClientPlayerNetworkEvent.LoggingIn) {
        if (!supportsOutlines(event.connection)) return
        val server = Minecraft.getInstance().singleplayerServer ?: return
        enteredWorld = true
        SyncChunkClientState.showSet = true
        server.execute { pushToClient(server) }
        event.player.displayClientMessage(
            Component.literal("标记模式：站在要保留的区块里输入/syncchunk add，未标记的区块将在房间中重新生成。完成后保存并退出。")
                .withStyle(ChatFormatting.GOLD),
            false,
        )
    }

    /** Returning to the title screen from the pause menu happens after Minecraft joins the server thread. */
    @SubscribeEvent
    @JvmStatic
    fun onScreenOpening(event: ScreenEvent.Opening) {
        if (enteredWorld && event.newScreen is TitleScreen) Minecraft.getInstance().stop()
    }

    @SubscribeEvent
    @JvmStatic
    fun onScreenInit(event: ScreenEvent.Init.Post) {
        if (!marking || event.screen !is PauseScreen) return
        event.listenersList.filterIsInstance<AbstractWidget>()
            .filter { (it.message.contents as? TranslatableContents)?.key == "menu.shareToLan" }
            .forEach { it.active = false }
    }

    @SubscribeEvent
    @JvmStatic
    fun onCommand(event: CommandEvent) {
        if (!marking) return
        val context = event.parseResults.context
        if (context.source.server !is IntegratedServer || context.nodes.firstOrNull()?.node?.name != "publish") return
        context.source.sendFailure(Component.literal("标记存档时不能对局域网开放"))
        event.isCanceled = true
    }

    private fun pushToClient(server: MinecraftServer) {
        val chunks = SyncChunkSavedData.of(server).mapCatching { SyncChunkList.of(it.snapshot()) }.getOrElse { error ->
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
