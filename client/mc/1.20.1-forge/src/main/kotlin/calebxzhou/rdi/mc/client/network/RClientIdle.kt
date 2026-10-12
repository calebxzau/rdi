package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.v20.forge.idle.IdleChannel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import org.lwjgl.glfw.GLFW

/**
 * Reports to the server when the player stepped away: the pause menu is open or the window is
 * minimized for [AWAY_DELAY_MILLIS]. Coming back is reported at once. A window that merely lost focus
 * without pausing still counts as watched, for example on a second monitor.
 */
object RClientIdle {
    private const val AWAY_DELAY_MILLIS = 2_000L

    private var reportedAway = false
    private var awaySinceMillis = 0L

    /** Called at the end of every client tick on the render thread. */
    fun onClientTick(minecraft: Minecraft) {
        val connection = minecraft.connection?.connection
        if (minecraft.player == null || connection == null || connection.isMemoryConnection || !IdleChannel.isSupported(connection)) {
            reset()
            return
        }
        val now = System.currentTimeMillis()
        val away = isAway(minecraft)
        if (!away) {
            awaySinceMillis = 0L
            if (reportedAway) report(false)
            return
        }
        if (awaySinceMillis == 0L) awaySinceMillis = now
        if (!reportedAway && now - awaySinceMillis >= AWAY_DELAY_MILLIS) report(true)
    }

    /** Forgets the state when the session ends; the server forgets its side on logout. */
    fun reset() {
        reportedAway = false
        awaySinceMillis = 0L
    }

    private fun isAway(minecraft: Minecraft): Boolean =
        minecraft.screen is PauseScreen ||
            GLFW.glfwGetWindowAttrib(minecraft.window.window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE

    private fun report(away: Boolean) {
        reportedAway = away
        IdleChannel.sendToServer(away)
    }
}
