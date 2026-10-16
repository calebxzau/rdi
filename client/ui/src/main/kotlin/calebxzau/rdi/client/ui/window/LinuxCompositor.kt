package calebxzau.rdi.client.ui.window

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.unix.X11
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Whether a compositing manager runs on this Linux desktop. Without one, transparent windows are drawn
 * black. AWT cannot tell: on X11 it only checks for an ARGB visual, which exists without a compositor too.
 */
object LinuxCompositor {
    private interface Xlib : Library {
        fun XGetSelectionOwner(display: X11.Display, selection: X11.Atom): X11.Window?

        companion object {
            val INSTANCE: Xlib = Native.load("X11", Xlib::class.java)
        }
    }

    fun isRunning(env: Map<String, String> = System.getenv()): Result<Boolean> = runCatching {
        // Wayland compositors always composite, XWayland windows included.
        if (!env["WAYLAND_DISPLAY"].isNullOrBlank()) return@runCatching true
        val x11 = X11.INSTANCE
        val display = x11.XOpenDisplay(null) ?: error("无法连接X11显示服务器")
        try {
            // An X11 compositing manager owns the _NET_WM_CM_S<screen> selection (EWMH).
            val selection = x11.XInternAtom(display, "_NET_WM_CM_S${x11.XDefaultScreen(display)}", false)
            val owner = Xlib.INSTANCE.XGetSelectionOwner(display, selection)
            owner != null && owner.toLong() != 0L
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    fun isRunningOrFalse(): Boolean = isRunning().getOrElse { error ->
        logger.warn(error) { "检测窗口合成器失败，将使用不透明窗口" }
        false
    }
}
