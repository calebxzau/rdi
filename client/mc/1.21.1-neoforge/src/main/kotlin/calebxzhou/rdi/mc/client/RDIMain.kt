package calebxzhou.rdi.mc.client

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mediaproc.MediaProcWarmup
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpServer
import calebxzhou.rdi.mc.client.mcpimpl211.McpGameImpl
import calebxzhou.rdi.mc.client.mcpimpl211.Search
import calebxzhou.rdi.mc.client.rcmd.RcmdClientBridge211
import calebxzau.rdi.mc.client.preview.ItemPreviewExporter
import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.rcmd.RcmdClientCommands
import com.google.common.net.HostAndPort
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientChatEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * calebxzhou @ 2026-01-10 22:33
 */
@Mod("rdi")
@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
class RDIMain {

    companion object {
        init {
            ZstdCompressionPipeline.verifyNativeLoaded()
        }

        val SCREENSHOT_EXECUTOR: ExecutorService =
            Executors.newSingleThreadExecutor(ThreadFactory { task: Runnable? ->
                val thread = Thread(task, "rdi-mcp-screenshot.md")
                thread.setDaemon(true)
                thread
            })
        @JvmField
        var JOIN_BUTTON: Button =
            Button.builder(Component.literal("进入地图 · " + RDI.HOST_NAME), Button.OnPress { _ ->
                val hp = HostAndPort.fromString(RDI.GAME_IP)
                ConnectScreen.startConnecting(
                    TitleScreen(),
                    Minecraft.getInstance(),
                    ServerAddress(hp.getHost(), hp.getPort()),
                    ServerData("rdi", RDI.GAME_IP, ServerData.Type.OTHER),
                    false,
                    null
                )
            }).bounds(100, 0, 200, 50).build()

        @JvmStatic
        fun layoutJoinButton(screenWidth: Int) {
            JOIN_BUTTON.setX(screenWidth / 2 - 100)
            JOIN_BUTTON.setY(0)
            JOIN_BUTTON.setWidth(200)
            JOIN_BUTTON.setHeight(20)
        }
        @SubscribeEvent
        @JvmStatic
        fun onResourceReload(event: RegisterClientReloadListenersEvent) {
            event.registerReloadListener(ResourceManagerReloadListener {
                Search.refreshResourceIndex()
            })
        }
        @SubscribeEvent
        @JvmStatic
        fun onClientSetup(event: FMLClientSetupEvent) {
            // Loading FFmpeg the first time takes a while; keep it off the render thread and the first sound.
            Thread.ofPlatform().daemon().name("rdi-mediaproc-warmup").start {
                MediaProcWarmup.warmUp().onFailure { error ->
                    LoggerFactory.getLogger(RDIMain::class.java).error("Failed to load the FFmpeg media runtime", error)
                }
            }
        }
        @SubscribeEvent
        @JvmStatic
        fun registerClientCommands(event: RegisterClientCommandsEvent) {
            event.dispatcher.register(
                Commands.literal("rdi")
                    .then(Commands.literal("preview")
                        .then(Commands.literal("export").executes { context ->
                            ItemPreviewExporter.forceExport()
                            context.source.sendSuccess({ Component.literal("预览图集导出已排队") }, false)
                            1
                        })
                    )
            )
        }

        @SubscribeEvent @JvmStatic
        fun onClientChat(event: ClientChatEvent) {
            val message = event.message
            if (!RcmdClientCommands.isRcmd(message)) {
                return
            }
            val minecraft = Minecraft.getInstance()
            val result = RcmdClientCommands.dispatch(RcmdClientBridge211(minecraft), message)
            if (!result.found) {
                return
            }
            event.setCanceled(true)
            RcmdClientCommands.reply(RcmdClientBridge211(minecraft), result.result)
        }

        @SubscribeEvent
        @JvmStatic
        fun onRenderFrame(event: RenderFrameEvent.Post) {
            ItemPreviewExporter.advance()
        }

        @SubscribeEvent
        @JvmStatic
        fun onClientJoinServer(event: ClientPlayerNetworkEvent.LoggingIn) {
            ItemPreviewExporter.onWorldEntered()
            StandardMcpServer.start(McpGameImpl, null)
                .onSuccess { port -> sendMcpUrlMessage(event.player, port) }
                .onFailure { it.printStackTrace() }

            Minecraft.getInstance().gui.apply {
                setTimes(10, 200, 20)
                setSubtitle(Component.literal("请务必阅读 公告置顶说明书"))
                setTitle(Component.empty())
            }
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

        @SubscribeEvent
        @JvmStatic
        fun onClientLeaveServer(event: ClientPlayerNetworkEvent.LoggingOut) {
            ItemPreviewExporter.onWorldLeft()
            StandardMcpServer.stop()
        }
    }
}
