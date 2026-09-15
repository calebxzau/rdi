package calebxzau.rdi.mc.client.preview

import calebxzhou.rdi.mc.client.mixin.mPreviewRenderSystem
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.ClientHooks
import net.neoforged.neoforge.client.GlStateBackup
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL21C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL32C
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.function.Supplier

class PreviewItemRenderException(cause: Throwable) : RuntimeException(cause)
private class PreviewGlException(message: String) : IllegalStateException(message)

/** Render-thread-only item atlas target with one asynchronous page readback. */
class PreviewAtlasRenderer private constructor(
    private val target: RenderTarget,
    private val itemTarget: RenderTarget,
    val targetWidth: Int,
    val targetHeight: Int,
    val pagePlans: List<PreviewPagePlan>
) : AutoCloseable {
    private var builder = ByteBufferBuilder(BUFFER_CAPACITY)
    private var buffers = MultiBufferSource.immediate(builder)
    private var pbo = 0
    private var fence = 0L
    private var readbackPlan: PreviewPagePlan? = null
    private var readbackImage: NativeImage? = null
    private var readbackMapped: ByteBuffer? = null
    private var readbackRows = 0
    private var closed = false
    private var itemTargetWarningCount = 0

    val pageSize: Int get() = targetWidth

    /** One full state snapshot is shared by every item rendered in this batch. */
    fun beginBatch(): ItemBatch {
        assertRenderThread()
        checkOpen()
        return ItemBatch(StateGuard.capture())
    }

    inner class ItemBatch internal constructor(private val baseline: StateGuard) : AutoCloseable {
        private var finished = false

        fun renderItem(stack: ItemStack, x: Int, y: Int, pageHeight: Int) {
            check(!finished) { "preview item batch is closed" }
            renderItem(stack, x, y, pageHeight, baseline)
        }

        override fun close() {
            if (finished) return
            finished = true
            baseline.restore()
        }
    }

    fun renderItem(stack: ItemStack, x: Int, y: Int, pageHeight: Int = targetHeight) {
        assertRenderThread()
        checkOpen()
        val guard = StateGuard.capture()
        renderItem(stack, x, y, pageHeight, guard)
    }

    private fun renderItem(stack: ItemStack, x: Int, y: Int, pageHeight: Int, guard: StateGuard) {
        require(x >= 0 && y >= 0 && x + ICON_SIZE <= targetWidth && y + ICON_SIZE <= pageHeight)
        var failure: Throwable? = null
        try {
            requireNoError("before rendering item")
            bindForItem()
            val mc = Minecraft.getInstance()
            val model = mc.itemRenderer.getModel(stack, mc.level, mc.player, 0)
            val pose = PoseStack()
            pose.translate(32.0F, 32.0F, 150.0F)
            pose.scale(64.0F, -64.0F, 64.0F)
            val flat = !model.usesBlockLight()
            Lighting.setupFor3DItems()
            if (flat) Lighting.setupForFlatItems()
            withPreviewDrawScope {
                mc.itemRenderer.render(
                    stack, ItemDisplayContext.GUI, false, pose, requireBuffers(),
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY, model
                )
                RenderSystem.disableDepthTest()
                requireBuffers().endBatch()
                RenderSystem.enableDepthTest()
            }
            logItemTargetChange(stack)
            requireNoError("render item")
            copyItemToAtlas(x, y)
            requireNoError("copy item into atlas")
        } catch (t: Throwable) {
            failure = classify(t)
            if (failure is PreviewItemRenderException) {
                resetBuffers()
            }
        } finally {
            try {
                guard.restore()
            } catch (restore: Throwable) {
                failure?.let(restore::addSuppressed)
                failure = restore
            }
        }
        failure?.let { throw it }
    }

    fun beginReadback(plan: PreviewPagePlan) {
        assertRenderThread()
        checkOpen()
        check(fence == 0L) { "Preview readback already pending" }
        check(readbackMapped == null && readbackImage == null) { "Preview readback resources are still pending" }
        val previousPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING)
        val previousAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT)
        val previousRowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH)
        val previousSkipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS)
        val previousSkipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS)
        val previousReadFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING)
        val previousReadBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER)
        var localPbo = 0
        var localFence = 0L
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.frameBufferId)
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0)
            localPbo = GL15C.glGenBuffers()
            check(localPbo != 0) { "glGenBuffers returned 0" }
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, localPbo)
            GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, pageBytes(plan), GL15C.GL_STREAM_READ)
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1)
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0)
            GL11C.glReadPixels(0, targetHeight - plan.height, plan.width, plan.height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L)
            requireNoError("glReadPixels")
            localFence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(localFence != 0L) { "glFenceSync returned 0" }
            GL11C.glFlush()
            pbo = localPbo
            fence = localFence
            readbackPlan = plan
            readbackRows = 0
            localPbo = 0
            localFence = 0L
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPbo)
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, previousAlignment)
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, previousRowLength)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, previousSkipRows)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, previousSkipPixels)
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFbo)
            GL11C.glReadBuffer(previousReadBuffer)
            if (localFence != 0L) GL32C.glDeleteSync(localFence)
            if (localPbo != 0) GL15C.glDeleteBuffers(localPbo)
        }
    }

    fun pollReadback(deadlineNanos: Long): NativeImage? {
        assertRenderThread()
        checkOpen()
        val currentFence = fence
        if (currentFence == 0L) return null
        when (GL32C.glClientWaitSync(currentFence, 0, 0L)) {
            GL32C.GL_TIMEOUT_EXPIRED -> return null
            GL32C.GL_WAIT_FAILED -> error("glClientWaitSync failed")
            GL32C.GL_ALREADY_SIGNALED, GL32C.GL_CONDITION_SATISFIED -> Unit
            else -> error("Unexpected glClientWaitSync result")
        }
        if (System.nanoTime() >= deadlineNanos) return null
        val previousPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING)
        var readyImage: NativeImage? = null
        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbo)
            if (readbackMapped == null) {
                readbackMapped = GL15C.glMapBuffer(GL21C.GL_PIXEL_PACK_BUFFER, GL15C.GL_READ_ONLY)
                    ?: error("Unable to map preview readback PBO")
                val plan = checkNotNull(readbackPlan)
                readbackImage = NativeImage(NativeImage.Format.RGBA, plan.width, plan.height, false)
            }
            val plan = checkNotNull(readbackPlan)
            val mappedAddress = MemoryUtil.memAddress(checkNotNull(readbackMapped))
            val pixels = ((checkNotNull(readbackImage) as Any) as RPreviewNativeImagePixels).rdiPreviewPixels()
            readbackRows = PreviewReadbackCopy.copyRows(
                mappedAddress,
                pixels,
                plan.width,
                plan.height,
                readbackRows,
                deadlineNanos
            )
            if (readbackRows == plan.height) {
                readbackMapped = null
                check(GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER)) { "Preview PBO unmap failed" }
                GL32C.glDeleteSync(currentFence)
                GL15C.glDeleteBuffers(pbo)
                fence = 0L
                pbo = 0
                readyImage = readbackImage
                readbackImage = null
                readbackPlan = null
                readbackRows = 0
            }
        } catch (failure: Throwable) {
            runCatching { retireReadback() }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPbo)
        }
        return readyImage
    }

    fun clearPage() {
        assertRenderThread()
        checkOpen()
        val guard = StateGuard.capture()
        try {
            target.bindWrite(true)
            RenderSystem.disableScissor()
            RenderSystem.colorMask(true, true, true, true)
            RenderSystem.depthMask(true)
            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F)
            RenderSystem.clearDepth(1.0)
            RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT, false)
            requireNoError("clear page")
        } finally {
            guard.restore()
        }
    }

    override fun close() {
        assertRenderThread()
        if (closed) return
        val guard = StateGuard.capture()
        var failure: Throwable? = null
        try {
            retireReadback()
        } catch (t: Throwable) {
            failure = t
        }
        try { if (fence != 0L) GL32C.glDeleteSync(fence) } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        try {
            if (pbo != 0) GL15C.glDeleteBuffers(pbo)
        } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        fence = 0L
        pbo = 0
        try { builder.close() } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        try { target.destroyBuffers() } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        try { itemTarget.destroyBuffers() } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
        closed = true
        try { guard.restore() } catch (t: Throwable) {
            if (failure != null) t.addSuppressed(failure)
            failure = t
        }
        failure?.let { throw it }
    }

    private fun bindForItem() {
        itemTarget.bindWrite(true)
        RenderSystem.viewport(0, 0, ICON_SIZE, ICON_SIZE)
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11C.GL_LEQUAL)
        RenderSystem.disableBlend()
        RenderSystem.enableCull()
        RenderSystem.setProjectionMatrix(
            Matrix4f().setOrtho(0.0F, ICON_SIZE.toFloat(), ICON_SIZE.toFloat(), 0.0F, 1000.0F, ClientHooks.getGuiFarPlane()),
            VertexSorting.ORTHOGRAPHIC_Z
        )
        val stack = RenderSystem.getModelViewStack()
        stack.identity()
        stack.translation(0.0F, 0.0F, 10000.0F - ClientHooks.getGuiFarPlane())
        RenderSystem.applyModelViewMatrix()
        RenderSystem.disableScissor()
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F)
        RenderSystem.clearDepth(1.0)
        RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT or GL11C.GL_DEPTH_BUFFER_BIT, false)
        requireNoError("clear item scratch target")
        RenderSystem.enableScissor(0, 0, ICON_SIZE, ICON_SIZE)
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)
    }

    private fun rebindItemTargetForDraw() {
        itemTarget.bindWrite(true)
        RenderSystem.viewport(0, 0, ICON_SIZE, ICON_SIZE)
    }

    private fun logItemTargetChange(stack: ItemStack) {
        if (itemTargetWarningCount >= MAX_ITEM_TARGET_WARNINGS) return
        val framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
        val viewport = IntArray(4)
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport)
        if (framebuffer == itemTarget.frameBufferId &&
            viewport.contentEquals(intArrayOf(0, 0, ICON_SIZE, ICON_SIZE))
        ) return

        // Post-render state alone cannot establish pixel validity; the atlas copy
        // explicitly binds its source and destination framebuffers.
        itemTargetWarningCount++
        logger.warn(
            "Item preview target changed after rendering {}: framebuffer={} (expected {}), viewport={} " +
                "(expected [0, 0, {}, {}]); continuing atlas copy",
            BuiltInRegistries.ITEM.getKey(stack.item), framebuffer, itemTarget.frameBufferId,
            viewport.contentToString(), ICON_SIZE, ICON_SIZE
        )
        if (itemTargetWarningCount == MAX_ITEM_TARGET_WARNINGS) {
            logger.warn("Further item preview target-change warnings suppressed for this export")
        }
    }

    private fun copyItemToAtlas(x: Int, y: Int) {
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, itemTarget.frameBufferId)
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId)
        GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0)
        GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0)
        RenderSystem.disableScissor()
        GL30C.glBlitFramebuffer(
            0, 0, ICON_SIZE, ICON_SIZE,
            x, targetHeight - y - ICON_SIZE, x + ICON_SIZE, targetHeight - y,
            GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST
        )
    }

    private fun classify(t: Throwable): Throwable {
        if (t is PreviewGlException) return t
        if (t is VirtualMachineError || t is LinkageError || t.javaClass.name == "java.lang.ThreadDeath") return t
        val error = GL11C.glGetError()
        if (error != GL11C.GL_NO_ERROR) return IllegalStateException("OpenGL item render error 0x${error.toString(16)}", t)
        return if (t is PreviewItemRenderException) t else PreviewItemRenderException(t)
    }

    private fun resetBuffers() {
        // Never flush pending geometry here: a failed renderer may have left a
        // third-party framebuffer bound, and partial geometry is not reusable.
        builder.close()
        builder = ByteBufferBuilder(BUFFER_CAPACITY)
        buffers = MultiBufferSource.immediate(builder)
        BufferUploader.invalidate()
    }

    private fun requireBuffers() = buffers
    private fun assertRenderThread() = RenderSystem.assertOnRenderThread()
    private fun checkOpen() = check(!closed) { "Preview renderer is closed" }
    private fun requireNoError(op: String) {
        val error = GL11C.glGetError()
        if (error != GL11C.GL_NO_ERROR) throw PreviewGlException("$op failed with OpenGL error 0x${error.toString(16)}")
    }

    private fun pageBytes(plan: PreviewPagePlan): Long = plan.width.toLong() * plan.height * 4L

    private fun retireReadback() {
        val retiringPbo = pbo
        val previousPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING)
        var failure: Throwable? = null
        try {
            if (readbackMapped != null && pbo != 0) {
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pbo)
                try {
                    if (!GL15C.glUnmapBuffer(GL21C.GL_PIXEL_PACK_BUFFER)) {
                        failure = IllegalStateException("Preview PBO unmap failed")
                    }
                } catch (throwable: Throwable) {
                    failure = throwable
                }
                readbackMapped = null
            }
            try {
                readbackImage?.close()
            } catch (throwable: Throwable) {
                if (failure == null) failure = throwable else failure.addSuppressed(throwable)
            }
            readbackImage = null
            try {
                if (fence != 0L) GL32C.glDeleteSync(fence)
            } catch (throwable: Throwable) {
                if (failure == null) failure = throwable else failure.addSuppressed(throwable)
            }
            try {
                if (pbo != 0) GL15C.glDeleteBuffers(pbo)
            } catch (throwable: Throwable) {
                if (failure == null) failure = throwable else failure.addSuppressed(throwable)
            }
            fence = 0L
            pbo = 0
            readbackPlan = null
            readbackRows = 0
        } finally {
            GL15C.glBindBuffer(
                GL21C.GL_PIXEL_PACK_BUFFER,
                if (retiringPbo != 0 && previousPbo == retiringPbo) 0 else previousPbo
            )
        }
        failure?.let { throw it }
    }

    internal class StateGuard private constructor(
        private val backup: GlStateBackup,
        private val projection: Matrix4f,
        private val modelView: Matrix4f,
        private val modelViewStack: Matrix4f,
        private val textureMatrix: Matrix4f,
        private val sorting: VertexSorting,
        private val color: FloatArray,
        private val shaderTextures: IntArray,
        private val lights: Array<Vector3f?>,
        private val drawFbo: Int,
        private val readFbo: Int,
        private val readBuffer: Int,
        private val drawBuffer: Int,
        private val viewport: IntArray,
        private val scissor: IntArray,
        private val clearColor: FloatArray,
        private val clearDepth: Double,
        private val activeTexture: Int,
        private val program: Int,
        private val arrayBuffer: Int,
        private val elementBuffer: Int,
        private val vertexArray: Int,
        private val textureBindings: IntArray,
        private val shader: ShaderInstance?
    ) {
        fun restore() {
            RenderSystem.restoreGlState(backup)
            com.mojang.blaze3d.platform.GlStateManager._glUseProgram(program)
            GL30C.glBindVertexArray(vertexArray)
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer)
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementBuffer)
            for (i in textureBindings.indices) {
                val textureUnit = GL13C.GL_TEXTURE0 + i
                GL13C.glActiveTexture(textureUnit)
                com.mojang.blaze3d.platform.GlStateManager._activeTexture(textureUnit)
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0)
                com.mojang.blaze3d.platform.GlStateManager._bindTexture(0)
                com.mojang.blaze3d.platform.GlStateManager._bindTexture(textureBindings[i])
            }
            GL13C.glActiveTexture(activeTexture)
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(activeTexture)
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo)
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo)
            GL11C.glDrawBuffer(drawBuffer)
            GL11C.glReadBuffer(readBuffer)
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL11C.glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
            GL11C.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
            GL11C.glClearDepth(clearDepth)
            RenderSystem.setProjectionMatrix(projection, sorting)
            val stack = RenderSystem.getModelViewStack()
            stack.set(modelView)
            RenderSystem.applyModelViewMatrix()
            stack.set(modelViewStack)
            RenderSystem.setTextureMatrix(textureMatrix)
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3])
            for (i in shaderTextures.indices) RenderSystem.setShaderTexture(i, shaderTextures[i])
            val liveLights = mPreviewRenderSystem.rdiPreviewShaderLightDirections()
            for (index in liveLights.indices) liveLights[index] = lights[index]?.let(::Vector3f)
            RenderSystem.setShader(Supplier { shader })
            BufferUploader.invalidate()
        }

        companion object {
            fun capture(): StateGuard {
                RenderSystem.assertOnRenderThread()
                val backup = GlStateBackup()
                RenderSystem.backupGlState(backup)
                val viewport = ints(GL11C.GL_VIEWPORT, 4)
                val scissor = ints(GL11C.GL_SCISSOR_BOX, 4)
                val clear = floats(GL11C.GL_COLOR_CLEAR_VALUE, 4)
                val active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)
                val bindings = IntArray(12)
                for (i in bindings.indices) {
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i)
                    bindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)
                }
                GL13C.glActiveTexture(active)
                val lights = mPreviewRenderSystem.rdiPreviewShaderLightDirections()
                return StateGuard(
                    backup, Matrix4f(RenderSystem.getProjectionMatrix()), Matrix4f(RenderSystem.getModelViewMatrix()),
                    Matrix4f(RenderSystem.getModelViewStack()),
                    Matrix4f(RenderSystem.getTextureMatrix()), RenderSystem.getVertexSorting(), RenderSystem.getShaderColor().clone(),
                    IntArray(12) { RenderSystem.getShaderTexture(it) },
                    Array(lights.size) { index -> lights[index]?.let(::Vector3f) },
                    GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING), GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL11C.GL_READ_BUFFER), GL11C.glGetInteger(GL11C.GL_DRAW_BUFFER), viewport, scissor, clear,
                    GL11C.glGetDouble(GL11C.GL_DEPTH_CLEAR_VALUE), active, GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                    GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING), GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), bindings, RenderSystem.getShader()
                )
            }

            private fun ints(name: Int, count: Int) = MemoryStack.stackPush().use { stack ->
                val data = stack.mallocInt(count)
                GL11C.glGetIntegerv(name, data)
                IntArray(count) { data.get(it) }
            }
            private fun floats(name: Int, count: Int) = MemoryStack.stackPush().use { stack ->
                val data = stack.mallocFloat(count)
                GL11C.glGetFloatv(name, data)
                FloatArray(count) { data.get(it) }
            }
        }
    }

    companion object {
        private const val ICON_SIZE = 64
        private const val MAX_ITEM_TARGET_WARNINGS = 10
        private const val BUFFER_CAPACITY = 1 shl 20
        private const val FULL_BRIGHT = 15728880
        private val logger = LoggerFactory.getLogger(PreviewAtlasRenderer::class.java)
        private val candidates = intArrayOf(2048, 1024, 512, 256, 128, 64)
        private var activePreviewRenderer: PreviewAtlasRenderer? = null

        @JvmStatic
        fun rdiPreviewRebindItemTargetBeforeDraw() {
            activePreviewRenderer?.rebindItemTargetForDraw()
        }

        fun create(itemCount: Int): PreviewAtlasRenderer {
            RenderSystem.assertOnRenderThread()
            require(itemCount > 0) { "empty preview exports do not need a GPU renderer" }
            val maxTexture = RenderSystem.maxSupportedTextureSize()
            val maxViewport = MemoryStack.stackPush().use { stack ->
                val dims = stack.mallocInt(2)
                GL11C.glGetIntegerv(GL11C.GL_MAX_VIEWPORT_DIMS, dims)
                intArrayOf(dims.get(0), dims.get(1))
            }
            var last: Throwable? = null
            for (maxEdge in candidates) {
                val plans = PreviewPacking.plan(itemCount, maxEdge)
                val targetWidth = plans.maxOf { it.width }
                val targetHeight = plans.maxOf { it.height }
                if (targetWidth > maxTexture || targetHeight > maxTexture ||
                    targetWidth > maxViewport[0] || targetHeight > maxViewport[1]
                ) continue
                val guard = StateGuard.capture()
                var candidate: RenderTarget? = null
                var itemCandidate: RenderTarget? = null
                var renderer: PreviewAtlasRenderer? = null
                var allocationFailure: Throwable? = null
                try {
                    candidate = object : RenderTarget(false) {}
                    candidate.setClearColor(0.0F, 0.0F, 0.0F, 0.0F)
                    RenderSystem.colorMask(true, true, true, true)
                    RenderSystem.depthMask(true)
                    RenderSystem.disableScissor()
                    candidate.resize(targetWidth, targetHeight, true)
                    candidate.bindWrite(true)
                    candidate.checkStatus()
                    requireNoError("allocate preview target")
                    itemCandidate = object : RenderTarget(true) {}
                    itemCandidate.setClearColor(0.0F, 0.0F, 0.0F, 0.0F)
                    itemCandidate.resize(ICON_SIZE, ICON_SIZE, true)
                    itemCandidate.bindWrite(true)
                    itemCandidate.checkStatus()
                    requireNoError("allocate item scratch target")
                    renderer = PreviewAtlasRenderer(candidate, itemCandidate, targetWidth, targetHeight, plans)
                } catch (t: Throwable) {
                    allocationFailure = t
                    try { candidate?.destroyBuffers() } catch (destroy: Throwable) { t.addSuppressed(destroy) }
                    try { itemCandidate?.destroyBuffers() } catch (destroy: Throwable) { t.addSuppressed(destroy) }
                }
                try {
                    guard.restore()
                } catch (restore: Throwable) {
                    allocationFailure?.let(restore::addSuppressed)
                    try {
                        if (renderer != null) renderer.close() else {
                            candidate?.destroyBuffers()
                            itemCandidate?.destroyBuffers()
                        }
                    } catch (cleanup: Throwable) {
                        restore.addSuppressed(cleanup)
                    }
                    throw restore
                }
                if (allocationFailure != null) {
                    val cause = checkNotNull(allocationFailure)
                    if (cause is VirtualMachineError || cause is LinkageError || cause.javaClass.name == "java.lang.ThreadDeath") throw cause
                    last = cause
                    logger.warn("Preview atlas {}x{} unavailable; trying fallback", targetWidth, targetHeight, cause)
                    continue
                }
                logger.info("Allocated preview atlas {}x{} (max edge {})", targetWidth, targetHeight, maxEdge)
                return checkNotNull(renderer)
            }
            error("Unable to allocate preview atlas target: $last")
        }

        private fun requireNoError(operation: String) {
            val error = GL11C.glGetError()
            check(error == GL11C.GL_NO_ERROR) { "$operation failed with OpenGL error 0x${error.toString(16)}" }
        }
    }

    private inline fun <T> withPreviewDrawScope(block: () -> T): T {
        check(activePreviewRenderer == null) { "Nested preview draw scopes are not supported" }
        activePreviewRenderer = this
        return try {
            block()
        } finally {
            activePreviewRenderer = null
        }
    }

}
