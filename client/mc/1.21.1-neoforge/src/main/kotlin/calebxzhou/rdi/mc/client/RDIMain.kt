package calebxzhou.rdi.mc.client

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpServer
import calebxzhou.rdi.mc.client.mcpimpl211.McpGameImpl
import calebxzhou.rdi.mc.client.mcpimpl211.Search
import calebxzhou.rdi.mc.client.rcmd.RcmdClientBridge211
import calebxzau.rdi.mc.client.preview.ItemPreviewExporter
import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.rcmd.RcmdClientCommands
import com.google.common.net.HostAndPort
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.SectionPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientChatEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import calebxzhou.rdi.mc.common.SectionPos as RdiSectionPos

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
        private val SYNC_CHUNK_LINES: RenderType = RenderType.create(
            "rdi_sync_chunk_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                .setLineState(RenderStateShard.LineStateShard(OptionalDouble.empty()))
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(false)
        )

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
        @SubscribeEvent @JvmStatic
        fun onClientChat(event: ClientChatEvent) {
            val message = event.message
            if (message.trim() == "\\preview export") {
                event.isCanceled = true
                ItemPreviewExporter.forceExport()
                Minecraft.getInstance().gui.chat.addMessage(Component.literal("预览图集导出已排队"))
                return
            }
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
            RDI.SYNC_CHUNKS.clear()
        }

        @SubscribeEvent @JvmStatic
        fun onRenderLevelStage(event: RenderLevelStageEvent) {
            if (
                event.stage !== RenderLevelStageEvent.Stage.AFTER_WEATHER ||
                (!RDI.SHOW_SET_SYNC_CHUNKS && !RDI.SHOW_NOW_SYNC_CHUNK)
            ) {
                return
            }
            val minecraft = Minecraft.getInstance()
            val dimensionId = minecraft.level?.dimension()?.location()?.toString() ?: return
            val cameraEntity = event.camera.getEntity() ?: return
            val cameraPos = event.getCamera().getPosition()
            val bufferSource = minecraft.renderBuffers().bufferSource()
            val renderType = SYNC_CHUNK_LINES
            val vertexConsumer = bufferSource.getBuffer(renderType)
            val poseStack = event.getPoseStack()
            if (RDI.SHOW_SET_SYNC_CHUNKS) {
                addSyncChunkOutlines(
                    poseStack,
                    vertexConsumer,
                    cameraPos.x,
                    cameraPos.y,
                    cameraPos.z,
                    RDI.SYNC_CHUNKS[dimensionId].orEmpty(),
                    0.0f,
                    1.0f,
                    0.0f
                )
            }

            if (RDI.SHOW_NOW_SYNC_CHUNK) {
                val currentChunk = net.minecraft.world.level.ChunkPos(cameraEntity.blockPosition())
                addChunkBox(
                    poseStack,
                    vertexConsumer,
                    cameraPos.x,
                    cameraPos.y,
                    cameraPos.z,
                    currentChunk.minBlockX,
                    minecraft.level?.minBuildHeight ?: -64,
                    currentChunk.minBlockZ,
                    minecraft.level?.maxBuildHeight ?: 320,
                    1.0f,
                    1.0f,
                    0.0f
                )
            }

            bufferSource.endBatch(renderType)
        }

        private fun addSyncChunkOutlines(
            poseStack: PoseStack,
            vertexConsumer: VertexConsumer,
            cameraX: Double,
            cameraY: Double,
            cameraZ: Double,
            sections: List<RdiSectionPos>,
            red: Float,
            green: Float,
            blue: Float
        ) {
            val level = Minecraft.getInstance().level ?: return
            sections.forEach { chunk ->
                addChunkBox(poseStack, vertexConsumer, cameraX, cameraY, cameraZ,
                    chunk.chunkX * 16, level.minBuildHeight, chunk.chunkZ * 16, level.maxBuildHeight,
                    red, green, blue)
            }
        }

        private fun addChunkBox(
            poseStack: PoseStack,
            vertexConsumer: VertexConsumer,
            cameraX: Double,
            cameraY: Double,
            cameraZ: Double,
            minBlockX: Int,
            minBlockY: Int,
            minBlockZ: Int,
            maxBlockY: Int,
            red: Float,
            green: Float,
            blue: Float,
        ) {
            val minX = minBlockX - cameraX
            val minY = minBlockY - cameraY
            val minZ = minBlockZ - cameraZ
            LevelRenderer.renderLineBox(
                poseStack, vertexConsumer, minX, minY, minZ,
                minX + 16.0, maxBlockY - cameraY, minZ + 16.0,
                red, green, blue, 1.0f,
            )
        }
    }
}
