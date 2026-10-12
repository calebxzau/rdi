package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.zstdcodec.v21.IdlePayload21
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.lwjgl.glfw.GLFW

/**
 * Reports to the server when the player stepped away: the pause menu is open or the window is
 * minimized for [AWAY_DELAY_MILLIS]. Coming back is reported at once. A window that merely lost focus
 * without pausing still counts as watched, for example on a second monitor.
 */
@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object RClientIdle {
    private const val AWAY_DELAY_MILLIS = 2_000L

    private var reportedAway = false
    private var awaySinceMillis = 0L

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar(IdlePayload21.VERSION)
            .optional()
            .playToServer(IdlePayload21.TYPE, IdlePayload21.STREAM_CODEC) { _, _ -> }
    }

    @JvmStatic
    @SubscribeEvent
    fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val listener = minecraft.connection
        if (minecraft.player == null || listener == null || listener.connection.isMemoryConnection ||
            !listener.hasChannel(IdlePayload21.TYPE)
        ) {
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
    private fun reset() {
        reportedAway = false
        awaySinceMillis = 0L
    }

    private fun isAway(minecraft: Minecraft): Boolean =
        minecraft.screen is PauseScreen ||
            GLFW.glfwGetWindowAttrib(minecraft.window.window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE

    private fun report(away: Boolean) {
        reportedAway = away
        PacketDistributor.sendToServer(IdlePayload21(away))
    }
}
