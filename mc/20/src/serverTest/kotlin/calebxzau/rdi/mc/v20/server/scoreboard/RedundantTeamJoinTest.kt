package calebxzau.rdi.mc.v20.server.scoreboard

import net.minecraft.world.scores.Scoreboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedundantTeamJoinTest {
    private val scoreboard = Scoreboard()
    private val hostile = scoreboard.addPlayerTeam("Hostile")
    private val passive = scoreboard.addPlayerTeam("PassiveOrNeutral")
    private val entity = "0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b"

    @Test
    fun joiningTheCurrentTeamIsSkipped() {
        scoreboard.addPlayerToTeam(entity, hostile)
        RedundantTeamJoin.drainSkipped()
        assertTrue(RedundantTeamJoin.shouldSkip(scoreboard, entity, hostile))
        assertTrue(RedundantTeamJoin.shouldSkip(scoreboard, entity, hostile))
        assertEquals(2, RedundantTeamJoin.drainSkipped())
        assertEquals(0, RedundantTeamJoin.drainSkipped())
    }

    @Test
    fun switchingTeamsIsNotSkipped() {
        scoreboard.addPlayerToTeam(entity, hostile)
        assertFalse(RedundantTeamJoin.shouldSkip(scoreboard, entity, passive))
    }

    @Test
    fun firstJoinIsNotSkipped() {
        assertFalse(RedundantTeamJoin.shouldSkip(scoreboard, entity, hostile))
    }

    @Test
    fun joinAfterLeavingIsNotSkipped() {
        scoreboard.addPlayerToTeam(entity, hostile)
        scoreboard.removePlayerFromTeam(entity, hostile)
        assertFalse(RedundantTeamJoin.shouldSkip(scoreboard, entity, hostile))
    }

    @Test
    fun joinAfterTeamRemovalIsNotSkipped() {
        scoreboard.addPlayerToTeam(entity, hostile)
        scoreboard.removePlayerTeam(hostile)
        val recreated = scoreboard.addPlayerTeam("Hostile")
        assertFalse(RedundantTeamJoin.shouldSkip(scoreboard, entity, recreated))
        assertFalse(RedundantTeamJoin.shouldSkip(scoreboard, entity, hostile))
    }
}
