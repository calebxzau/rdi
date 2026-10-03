package calebxzau.rdi.mc.server.attributes

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager

/** Logs each connection's attribute dedup counters; the cache itself lives on the Connection. */
@Mod.EventBusSubscriber(modid = "rdi")
object RServerAttributeDedup {
    private val logger = LogManager.getLogger("rdi.attr-dedup")

    @SubscribeEvent
    @JvmStatic
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val holder = player.connection.connection as? AttributeDedupHolder ?: return
        val state = holder.`rdi$attributeDedup`()
        synchronized(state) {
            if (!state.enabled) return
            logger.info(
                "Attribute sync for {}: packets={}, cancelled={}, trimmed={}, skippedSnapshots={}, offThreadResets={}",
                player.gameProfile.name, state.packets, state.cancelled, state.trimmed,
                state.skippedSnapshots, state.offThreadResets,
            )
        }
    }
}
