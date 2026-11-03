package calebxzau.mc.common2021

import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.Locale

internal enum class DevWindowMode {
    Normal,

    /**
     * Shows the window past the right edge of every monitor without taking focus, so it covers nothing
     * but keeps rendering. There is no minimized mode: a minimized window stalls the game's loading
     * screen, and the game's Window takes a 1x1 framebuffer if it is built while minimized.
     */
    Background;

    companion object {
        /** Null when [value] is set but is not a known mode. */
        fun parse(value: String?): DevWindowMode? {
            if (value.isNullOrBlank()) return Normal
            return when (value.trim().lowercase(Locale.ROOT)) {
                "normal" -> Normal
                "background" -> Background
                else -> null
            }
        }
    }
}

/**
 * Lets an agent start dev clients with `-Drdi.dev.window=background` without interrupting whoever is using
 * the machine. Players never set it. The dev run tasks turn FML's early window off for this mode, because
 * that window opens before any mixin can run.
 */
object RdiDevWindow {
    private const val PROPERTY = "rdi.dev.window"
    private val logger = LoggerFactory.getLogger("rdi-dev-window")

    @JvmStatic
    val isBackground: Boolean by lazy {
        val value = System.getProperty(PROPERTY)
        val mode = DevWindowMode.parse(value)
        if (mode == null) logger.warn("Ignoring unknown {}={}", PROPERTY, value)
        mode == DevWindowMode.Background
    }

    /** Must run just before the game creates its window. */
    @JvmStatic
    fun prepareHints() {
        if (!isBackground) return
        logger.info("Starting the game window in the background")
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE)
    }

    @JvmStatic
    fun backgroundX(): Int {
        val monitors = GLFW.glfwGetMonitors()
        var right = 0
        for (index in 0 until (monitors?.remaining() ?: 0)) {
            val monitor = monitors!!.get(index)
            val mode = GLFW.glfwGetVideoMode(monitor) ?: continue
            val x = IntArray(1)
            val y = IntArray(1)
            GLFW.glfwGetMonitorPos(monitor, x, y)
            right = maxOf(right, x[0] + mode.width())
        }
        return right + 64
    }

    @JvmStatic
    fun backgroundY(): Int {
        val x = IntArray(1)
        val y = IntArray(1)
        GLFW.glfwGetMonitorPos(GLFW.glfwGetPrimaryMonitor(), x, y)
        return y[0]
    }
}
