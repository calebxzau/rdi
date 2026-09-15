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
        private val FIRM_SECTION_LINES: RenderType = RenderType.create(
            "rdi_firm_section_lines",
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
            RDI.FIRM_CHUNKS.clear()
        }

        @SubscribeEvent @JvmStatic
        fun onRenderLevelStage(event: RenderLevelStageEvent) {
            if (
                event.stage !== RenderLevelStageEvent.Stage.AFTER_WEATHER ||
                (!RDI.SHOW_SET_FIRM_SECTIONS && !RDI.SHOW_NOW_FIRM_SECTION)
            ) {
                return
            }
            val minecraft = Minecraft.getInstance()
            val dimensionId = minecraft.level?.dimension()?.location()?.toString() ?: return
            val cameraEntity = event.camera.getEntity() ?: return
            val cameraPos = event.getCamera().getPosition()
            val bufferSource = minecraft.renderBuffers().bufferSource()
            val renderType = FIRM_SECTION_LINES
            val vertexConsumer = bufferSource.getBuffer(renderType)
            val poseStack = event.getPoseStack()
            if (RDI.SHOW_SET_FIRM_SECTIONS) {
                addFirmSectionOutlines(
                    poseStack,
                    vertexConsumer,
                    cameraPos.x,
                    cameraPos.y,
                    cameraPos.z,
                    RDI.FIRM_CHUNKS[dimensionId].orEmpty(),
                    0.0f,
                    1.0f,
                    0.0f
                )
            }

            if (RDI.SHOW_NOW_FIRM_SECTION) {
                val currentSection = SectionPos.of(cameraEntity)
                addSectionBox(
                    poseStack,
                    vertexConsumer,
                    cameraPos.x,
                    cameraPos.y,
                    cameraPos.z,
                    currentSection.minBlockX(),
                    currentSection.minBlockY(),
                    currentSection.minBlockZ(),
                    1.0f,
                    1.0f,
                    0.0f
                )
            }

            bufferSource.endBatch(renderType)
        }

        private fun addFirmSectionOutlines(
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
            val sectionKeys = sections.mapTo(mutableSetOf()) { FirmSectionRenderKey(it.chunkX, it.index, it.chunkZ) }
            val lines = linkedSetOf<FirmSectionLine>()
            for (section in sectionKeys) {
                addCandidateLines(section, lines)
            }
            lines.asSequence()
                .filter { it.isOuterLine(sectionKeys) }
                .forEach { line ->
                    addSectionGridLine(poseStack, vertexConsumer, cameraX, cameraY, cameraZ, line, red, green, blue)
                }
        }

        private fun addCandidateLines(section: FirmSectionRenderKey, lines: MutableSet<FirmSectionLine>) {
            val x = section.x
            val y = section.y
            val z = section.z
            lines += FirmSectionLine.of(x, y, z, x + 1, y, z)
            lines += FirmSectionLine.of(x, y + 1, z, x + 1, y + 1, z)
            lines += FirmSectionLine.of(x, y, z + 1, x + 1, y, z + 1)
            lines += FirmSectionLine.of(x, y + 1, z + 1, x + 1, y + 1, z + 1)
            lines += FirmSectionLine.of(x, y, z, x, y + 1, z)
            lines += FirmSectionLine.of(x + 1, y, z, x + 1, y + 1, z)
            lines += FirmSectionLine.of(x, y, z + 1, x, y + 1, z + 1)
            lines += FirmSectionLine.of(x + 1, y, z + 1, x + 1, y + 1, z + 1)
            lines += FirmSectionLine.of(x, y, z, x, y, z + 1)
            lines += FirmSectionLine.of(x + 1, y, z, x + 1, y, z + 1)
            lines += FirmSectionLine.of(x, y + 1, z, x, y + 1, z + 1)
            lines += FirmSectionLine.of(x + 1, y + 1, z, x + 1, y + 1, z + 1)
        }

        private fun FirmSectionLine.isOuterLine(sections: Set<FirmSectionRenderKey>): Boolean {
            val around = when {
                x1 != x2 -> booleanArrayOf(
                    FirmSectionRenderKey(x1, y1 - 1, z1 - 1) in sections,
                    FirmSectionRenderKey(x1, y1, z1 - 1) in sections,
                    FirmSectionRenderKey(x1, y1 - 1, z1) in sections,
                    FirmSectionRenderKey(x1, y1, z1) in sections
                )
                y1 != y2 -> booleanArrayOf(
                    FirmSectionRenderKey(x1 - 1, y1, z1 - 1) in sections,
                    FirmSectionRenderKey(x1, y1, z1 - 1) in sections,
                    FirmSectionRenderKey(x1 - 1, y1, z1) in sections,
                    FirmSectionRenderKey(x1, y1, z1) in sections
                )
                else -> booleanArrayOf(
                    FirmSectionRenderKey(x1 - 1, y1 - 1, z1) in sections,
                    FirmSectionRenderKey(x1, y1 - 1, z1) in sections,
                    FirmSectionRenderKey(x1 - 1, y1, z1) in sections,
                    FirmSectionRenderKey(x1, y1, z1) in sections
                )
            }
            val occupiedCount = around.count { it }
            return occupiedCount == 1 ||
                occupiedCount == 3 ||
                occupiedCount == 2 && ((around[0] && around[3]) || (around[1] && around[2]))
        }

        private fun addSectionGridLine(
            poseStack: PoseStack,
            vertexConsumer: VertexConsumer,
            cameraX: Double,
            cameraY: Double,
            cameraZ: Double,
            line: FirmSectionLine,
            red: Float,
            green: Float,
            blue: Float
        ) {
            val pose = poseStack.last()
            val x1 = (line.x1 * 16 - cameraX).toFloat()
            val y1 = (line.y1 * 16 - cameraY).toFloat()
            val z1 = (line.z1 * 16 - cameraZ).toFloat()
            val x2 = (line.x2 * 16 - cameraX).toFloat()
            val y2 = (line.y2 * 16 - cameraY).toFloat()
            val z2 = (line.z2 * 16 - cameraZ).toFloat()
            val normalX = if (line.x1 != line.x2) 1.0f else 0.0f
            val normalY = if (line.y1 != line.y2) 1.0f else 0.0f
            val normalZ = if (line.z1 != line.z2) 1.0f else 0.0f
            vertexConsumer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, 1.0f).setNormal(pose, normalX, normalY, normalZ)
            vertexConsumer.addVertex(pose, x2, y2, z2).setColor(red, green, blue, 1.0f).setNormal(pose, normalX, normalY, normalZ)
        }

        private fun addSectionBox(
            poseStack: PoseStack,
            vertexConsumer: VertexConsumer,
            cameraX: Double,
            cameraY: Double,
            cameraZ: Double,
            section: RdiSectionPos,
            red: Float,
            green: Float,
            blue: Float
        ) {
            addSectionBox(
                poseStack,
                vertexConsumer,
                cameraX,
                cameraY,
                cameraZ,
                section.chunkX * 16,
                section.index * 16,
                section.chunkZ * 16,
                red,
                green,
                blue
            )
        }

        private fun addSectionBox(
            poseStack: PoseStack,
            vertexConsumer: VertexConsumer,
            cameraX: Double,
            cameraY: Double,
            cameraZ: Double,
            minBlockX: Int,
            minBlockY: Int,
            minBlockZ: Int,
            red: Float,
            green: Float,
            blue: Float
        ) {
            val minX = minBlockX - cameraX
            val minY = minBlockY - cameraY
            val minZ = minBlockZ - cameraZ
            LevelRenderer.renderLineBox(
                poseStack,
                vertexConsumer,
                minX,
                minY,
                minZ,
                minX + 16.0,
                minY + 16.0,
                minZ + 16.0,
                red,
                green,
                blue,
                1.0f
            )
        }


    }

    private data class FirmSectionRenderKey(val x: Int, val y: Int, val z: Int)

    private data class FirmSectionLine(
        val x1: Int,
        val y1: Int,
        val z1: Int,
        val x2: Int,
        val y2: Int,
        val z2: Int
    ) {
        companion object {
            fun of(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): FirmSectionLine {
                return if (
                    x1 < x2 ||
                    x1 == x2 && y1 < y2 ||
                    x1 == x2 && y1 == y2 && z1 <= z2
                ) {
                    FirmSectionLine(x1, y1, z1, x2, y2, z2)
                } else {
                    FirmSectionLine(x2, y2, z2, x1, y1, z1)
                }
            }
        }
    }
}
