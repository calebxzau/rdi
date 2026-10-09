package calebxzau.mc.common2021

import calebxzhou.rdi.mc.common.RDI
import com.mojang.blaze3d.platform.Window
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage

/** Shared by the 1.20/1.21 clients; excluded from the legacy client and plain Java common module. */
object RdiWindow {
    private val logger = LoggerFactory.getLogger("rdi-window")
    private var initialized = false
    private var customTitle: String? = null

    @JvmStatic
    fun apply(window: Window, supportsIcon: Boolean) {
        if (initialized) return
        initialized = true
        RDI.applyWindowProperties(
            { icon ->
                if (supportsIcon) {
                    setIcon(window, icon)
                } else {
                    logger.error("当前窗口平台不支持通过rdi.window.icon设置图标")
                }
            },
            { value ->
                customTitle = value
                window.setTitle(value)
            },
            { message, error -> logger.error(message, error) }
        )
    }

    @JvmStatic
    fun getTitle(): String? = customTitle

    private fun setIcon(window: Window, icon: BufferedImage) {
        val size = minOf(icon.width, icon.height)
        val offsetX = (icon.width - size) / 2
        val offsetY = (icon.height - size) / 2
        val pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(size, size), 4))
        try {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val argb = icon.getRGB(offsetX + x, offsetY + y)
                    pixels.put((argb ushr 16).toByte())
                    pixels.put((argb ushr 8).toByte())
                    pixels.put(argb.toByte())
                    pixels.put((argb ushr 24).toByte())
                }
            }
            pixels.flip()
            MemoryStack.stackPush().use { stack ->
                val images = GLFWImage.malloc(1, stack)
                images.width(size).height(size).pixels(pixels)
                GLFW.glfwSetWindowIcon(window.window, images)
            }
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }
}
