package calebxzau.rdi.mc.server.scoreboard

import calebxzau.rdi.mc.v20.server.scoreboard.RedundantTeamJoin
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager

/** Reports how many no-op team joins were skipped, hourly and at shutdown. */
@Mod.EventBusSubscriber(modid = "rdi")
object RServerTeamSync {
    private val logger = LogManager.getLogger("rdi.team-sync")
    private const val REPORT_TICKS = 20 * 60 * 60
    private var ticks = 0

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || ++ticks < REPORT_TICKS) return
        ticks = 0
        report("last hour")
    }

    @SubscribeEvent
    @JvmStatic
    fun onStopped(event: ServerStoppedEvent) {
        ticks = 0
        report("since last report")
    }

    private fun report(period: String) {
        val skipped = RedundantTeamJoin.drainSkipped()
        if (skipped > 0) logger.info("Skipped {} redundant team joins ({})", skipped, period)
    }
}
