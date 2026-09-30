package calebxzhou.rdi.mc.client

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.mc.common2021.RdiBatchChannel
import calebxzau.rdi.mc.v20.client.RoomJoinUi20
// import calebxzhou.rdi.mc.client.chunkcache.RdiChunkCacheClient
// import calebxzhou.rdi.mc.client.chunkcache.RdiChunkCacheClientHandler
import calebxzhou.rdi.mc.client.network.RClientNetwork
import calebxzhou.rdi.mc.client.network.RClientBatching
import calebxzau.rdi.mc.v20.client.GlobalPlayerListState
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpServer
import calebxzhou.rdi.mc.client.mcpimpl.McpGameImpl
import calebxzhou.rdi.mc.client.mcpimpl.McpNetwork
import calebxzhou.rdi.mc.common.RDI
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager

/**
 * calebxzhou @ 2026-01-06 19:19
 */
@Mod("rdi")
@Mod.EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
class RDIMain {
    init {
        RClientNetwork.register()
        RdiBatchChannel.register()
        calebxzau.rdi.mc.v20.forge.l2.L2NameChannel.register()
        McpNetwork.register()
        LogManager.getLogger("rdi").info("❄❄❄❄❄❄❄❄RDI客户端核心模块已加载❄❄❄❄❄❄❄❄")
    }

    companion object {
        init {
            ZstdCompressionPipeline.verifyNativeLoaded()
        }

        @JvmField
        var JOIN_BUTTON: Button = RoomJoinUi20.JOIN_BUTTON

        @JvmStatic
        fun layoutJoinButton(screenWidth: Int) {
            RoomJoinUi20.layoutJoinButton(screenWidth)
        }

        @SubscribeEvent
        @JvmStatic
        fun onClientJoinServer(event: ClientPlayerNetworkEvent.LoggingIn) {
            GlobalPlayerListState.beginSession(event.connection)
            RClientBatching.onJoin(event.connection)
            /* RdiChunkCacheClientHandler.clearDeferredPackets()
            RdiChunkCacheClient.open(
                Minecraft.getInstance().gameDirectory.toPath(),
                "${RDI.HOST_NAME}\n${RDI.GAME_IP}",
            )
            RdiChunkCacheClient.sendManifestAndReady() */
            StandardMcpServer.start(McpGameImpl, null)
                .onSuccess { port -> sendMcpUrlMessage(event.player, port) }
                .onFailure { it.printStackTrace() }
        }

        @SubscribeEvent
        @JvmStatic
        fun onClientLeaveServer(event: ClientPlayerNetworkEvent.LoggingOut) {
            calebxzau.rdi.mc.client.l2.RClientL2Names.clear()
            GlobalPlayerListState.endSession(event.connection)
            RClientBatching.onLeave(event.connection)
            StandardMcpServer.stop()
            /* RdiChunkCacheClientHandler.clearDeferredPackets()
            RdiChunkCacheClient.close() */
        }

        private fun sendMcpUrlMessage(player: LocalPlayer, port: Int) {
            val url = "http://127.0.0.1:$port/mcp"
            player.displayClientMessage(
                Component.literal("点此复制AI MCP URL").withStyle(ChatFormatting.UNDERLINE)
                    .withStyle { style ->
                        style.withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, url))
                    },
                false,
            )
        }
    }
}
