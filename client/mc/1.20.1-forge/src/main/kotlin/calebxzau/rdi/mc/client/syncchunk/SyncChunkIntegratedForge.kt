package calebxzau.rdi.mc.client.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkCommands
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.client.SyncChunkClientState
import calebxzau.rdi.mc.v20.server.syncchunk.SyncChunkSavedData20
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.MinecraftServer
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.slf4j.LoggerFactory

/**
 * Save import marking mode (launcher flag `-Drdi.syncChunkMarking=true`): the launcher opens a copy of
 * the save in singleplayer, and the player marks the chunks to keep with `/syncchunk add|del|list`, as
 * on hosts. The list is stored in the copy with [SyncChunkSavedData20], so the file and its limits are
 * exactly those of hosts. Nothing here is active without the flag.
 *
 * Multiplayer and LAN are unavailable in this mode, and leaving the world closes the game, which hands
 * control back to the launcher.
 */
object SyncChunkIntegratedForge {
    private val logger = LoggerFactory.getLogger(SyncChunkIntegratedForge::class.java)

    @JvmField
    val MARKING: Boolean = System.getProperty("rdi.syncChunkMarking") == "true"

    @Volatile
    private var enteredWorld = false

    /** Registers the handlers on the Forge event bus when marking mode is on. */
    @JvmStatic
    fun register() {
        if (!MARKING) return
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(Handlers)
        logger.info("Sync chunk marking mode is on")
    }

    /** Server thread: shows the current list as outlines on this client. */
    private fun pushToClient(server: MinecraftServer) {
        val list = SyncChunkSavedData20.of(server).mapCatching { SyncChunkList.of(it.snapshot()) }.getOrElse { exception ->
            logger.error("Failed to read the marked chunks", exception)
            return
        }
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            minecraft.connection?.connection?.let { SyncChunkClientState.replace(it, list) }
        }
    }

    object Handlers {
        @SubscribeEvent
        fun registerCommands(event: RegisterCommandsEvent) {
            SyncChunkCommands.register(event.dispatcher, SyncChunkSavedData20::of, ::pushToClient)
        }

        @SubscribeEvent
        fun onLoggingIn(event: ClientPlayerNetworkEvent.LoggingIn) {
            enteredWorld = true
            SyncChunkClientState.showSet = true
            Minecraft.getInstance().singleplayerServer?.let { server -> server.execute { pushToClient(server) } }
            event.player.displayClientMessage(
                Component.literal("标记模式：站在要保留的区块里输入/syncchunk add，未标记的区块会在房间中重新生成。完成后保存并退出即可。")
                    .withStyle(ChatFormatting.GOLD),
                false,
            )
        }

        /** Leaving the world returns to the title screen; close the game there, after the save finished. */
        @SubscribeEvent
        fun onScreenOpening(event: ScreenEvent.Opening) {
            if (enteredWorld && event.newScreen is TitleScreen) Minecraft.getInstance().stop()
        }

        /** The pause menu's "Open to LAN" is disabled in marking mode. */
        @SubscribeEvent
        fun onScreenInit(event: ScreenEvent.Init.Post) {
            if (event.screen !is PauseScreen) return
            event.listenersList.filterIsInstance<AbstractWidget>()
                .filter { (it.message.contents as? TranslatableContents)?.key == "menu.shareToLan" }
                .forEach { it.active = false }
        }
    }
}
