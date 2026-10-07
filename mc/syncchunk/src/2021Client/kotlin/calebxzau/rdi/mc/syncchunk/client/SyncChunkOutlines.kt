package calebxzau.rdi.mc.syncchunk.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.FogRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.world.phys.Vec3

/** Full-height chunk outlines: green for the room's sync chunks, yellow for the player's chunk. */
object SyncChunkOutlines {
    fun isEnabled(): Boolean = SyncChunkClientState.showSet || SyncChunkClientState.showHere

    /**
     * Draws the enabled outlines. Exactly one of [poseStack] and the model-view matrix may hold the camera
     * rotation, or it is applied twice. Fog is off so tall or far outlines don't fade.
     */
    fun render(poseStack: PoseStack, cameraPos: Vec3) {
        val showSet = SyncChunkClientState.showSet
        val showHere = SyncChunkClientState.showHere
        if (!showSet && !showHere) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val bufferSource = minecraft.renderBuffers().bufferSource()
        val lines = bufferSource.getBuffer(SyncChunkRenderTypes.LINES)
        val minY = level.minBuildHeight
        val maxY = level.maxBuildHeight
        if (showSet) {
            val connection = minecraft.connection?.connection
            val chunks = connection?.let { SyncChunkClientState.chunksIn(it, level.dimension().location().toString()) }
            chunks?.forEach { chunk ->
                addChunkBox(poseStack, lines, cameraPos, chunk.chunkX, chunk.chunkZ, minY, maxY, 0.0f, 1.0f, 0.0f)
            }
        }
        if (showHere) {
            minecraft.player?.chunkPosition()?.let { chunk ->
                addChunkBox(poseStack, lines, cameraPos, chunk.x, chunk.z, minY, maxY, 1.0f, 1.0f, 0.0f)
            }
        }
        val fogStart = RenderSystem.getShaderFogStart()
        FogRenderer.setupNoFog()
        try {
            bufferSource.endBatch(SyncChunkRenderTypes.LINES)
        } finally {
            RenderSystem.setShaderFogStart(fogStart)
        }
    }

    private fun addChunkBox(
        poseStack: PoseStack,
        lines: VertexConsumer,
        cameraPos: Vec3,
        chunkX: Int,
        chunkZ: Int,
        minY: Int,
        maxY: Int,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        val minX = chunkX * 16.0 - cameraPos.x
        val minZ = chunkZ * 16.0 - cameraPos.z
        LevelRenderer.renderLineBox(
            poseStack, lines, minX, minY - cameraPos.y, minZ,
            minX + 16.0, maxY - cameraPos.y, minZ + 16.0,
            red, green, blue, 1.0f,
        )
    }
}
