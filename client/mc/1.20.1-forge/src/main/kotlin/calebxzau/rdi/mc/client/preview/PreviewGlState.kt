package calebxzau.rdi.mc.client.preview

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL20C
import org.lwjgl.system.MemoryStack

/** Forge1.20.1 has no GlStateBackup. Restore driver values and Minecraft's cached values together. */
internal class PreviewGlState private constructor() {
    private val blend = GL11C.glIsEnabled(GL11C.GL_BLEND)
    private val depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST)
    private val cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE)
    private val scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST)
    private val polygonFill = GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL)
    private val polygonLine = GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_LINE)
    private val logic = GL11C.glIsEnabled(GL11C.GL_COLOR_LOGIC_OP)
    private val stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST)
    private val srcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB)
    private val dstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB)
    private val srcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA)
    private val dstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA)
    private val equationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB)
    private val equationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA)
    private val depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)
    private val depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)
    private val polygonFactor = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_FACTOR)
    private val polygonUnits = GL11C.glGetFloat(GL11C.GL_POLYGON_OFFSET_UNITS)
    private val logicOp = GL11C.glGetInteger(GL11C.GL_LOGIC_OP_MODE)
    private val stencilFunc = GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC)
    private val stencilRef = GL11C.glGetInteger(GL11C.GL_STENCIL_REF)
    private val stencilValueMask = GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK)
    private val stencilWriteMask = GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK)
    private val stencilFail = GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL)
    private val stencilDepthFail = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL)
    private val stencilPass = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS)
    private val colorMask = MemoryStack.stackPush().use { stack ->
        val values = stack.malloc(4)
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, values)
        BooleanArray(4) { values.get(it).toInt() != 0 }
    }

    fun restore() {
        // Set the driver explicitly too: custom renderers can bypass GlStateManager's cache.
        toggle(GL11C.GL_BLEND, blend)
        if (blend) GlStateManager._enableBlend() else GlStateManager._disableBlend()
        GL14C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha)
        GL20C.glBlendEquationSeparate(equationRgb, equationAlpha)
        toggle(GL11C.GL_DEPTH_TEST, depth)
        if (depth) GlStateManager._enableDepthTest() else GlStateManager._disableDepthTest()
        GL11C.glDepthMask(depthMask)
        GlStateManager._depthMask(depthMask)
        GL11C.glDepthFunc(depthFunc)
        GlStateManager._depthFunc(depthFunc)
        toggle(GL11C.GL_CULL_FACE, cull)
        if (cull) GlStateManager._enableCull() else GlStateManager._disableCull()
        toggle(GL11C.GL_SCISSOR_TEST, scissor)
        if (scissor) GlStateManager._enableScissorTest() else GlStateManager._disableScissorTest()
        toggle(GL11C.GL_POLYGON_OFFSET_FILL, polygonFill)
        if (polygonFill) GlStateManager._enablePolygonOffset() else GlStateManager._disablePolygonOffset()
        toggle(GL11C.GL_POLYGON_OFFSET_LINE, polygonLine)
        GL11C.glPolygonOffset(polygonFactor, polygonUnits)
        GlStateManager._polygonOffset(polygonFactor, polygonUnits)
        toggle(GL11C.GL_COLOR_LOGIC_OP, logic)
        if (logic) GlStateManager._enableColorLogicOp() else GlStateManager._disableColorLogicOp()
        GL11C.glLogicOp(logicOp)
        GlStateManager._logicOp(logicOp)
        toggle(GL11C.GL_STENCIL_TEST, stencil)
        GL11C.glStencilFunc(stencilFunc, stencilRef, stencilValueMask)
        GlStateManager._stencilFunc(stencilFunc, stencilRef, stencilValueMask)
        GL11C.glStencilMask(stencilWriteMask)
        GlStateManager._stencilMask(stencilWriteMask)
        GL11C.glStencilOp(stencilFail, stencilDepthFail, stencilPass)
        GlStateManager._stencilOp(stencilFail, stencilDepthFail, stencilPass)
        GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
        GlStateManager._colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
    }

    private fun toggle(capability: Int, enabled: Boolean) {
        if (enabled) GL11C.glEnable(capability) else GL11C.glDisable(capability)
    }

    companion object {
        fun capture(): PreviewGlState {
            RenderSystem.assertOnRenderThread()
            return PreviewGlState()
        }
    }
}
