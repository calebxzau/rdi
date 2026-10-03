package calebxzau.rdi.mc.v20.server.scoreboard

import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.concurrent.atomic.AtomicLong

/**
 * Vanilla 1.20.1 re-adding a member to its current team removes and re-adds it, so the server
 * broadcasts a leave and a join that leave every client unchanged. Mods that refresh team
 * membership every tick turn that into constant traffic.
 */
object RedundantTeamJoin {
    private val skipped = AtomicLong()

    /** True when [name] already belongs to [team]; the caller then skips the join and reports success. */
    @JvmStatic
    fun shouldSkip(scoreboard: Scoreboard, name: String, team: PlayerTeam): Boolean {
        if (scoreboard.getPlayersTeam(name) !== team || !team.players.contains(name)) return false
        skipped.incrementAndGet()
        return true
    }

    /** Returns the joins skipped since the previous call. */
    fun drainSkipped(): Long = skipped.getAndSet(0L)
}
