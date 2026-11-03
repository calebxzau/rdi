package calebxzau.rdi.mc.client.dm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmTakeoverServiceTest {
    private val id = UUID.fromString("0199a000-0000-7000-8000-000000000001")

    private fun host(state: String?, gamePort: Int? = null, takeover: String? = null) =
        DmHostInfo(id, "room", state, gamePort, takeover = takeover)

    @Test
    fun takesOverOnlyWhenTheHostIsReportedOffline(): Unit {
        assertEquals(DmTakeoverService.Step.Claim, DmTakeoverService.afterDisconnect(host("Offline"), 0))
        assertEquals(DmTakeoverService.Step.WaitForHost, DmTakeoverService.afterDisconnect(host("Unavailable"), 0))
        // A host that saves and quits stays "Leaving" while it uploads the final checkpoint.
        assertEquals(DmTakeoverService.Step.WaitForHost, DmTakeoverService.afterDisconnect(host("Leaving"), 0))
        assertFalse(DmTakeoverService.canStartManually(host("Leaving")))
    }

    @Test
    fun anOnlineAnswerRightAfterTheDisconnectMayStillBeStale(): Unit {
        val grace = DmTakeoverService.ONLINE_GRACE_MILLIS
        assertEquals(DmTakeoverService.Step.WaitForHost, DmTakeoverService.afterDisconnect(host("Online", 26000), 0))
        assertEquals(DmTakeoverService.Step.WaitForHost, DmTakeoverService.afterDisconnect(host("Online", 26000), grace - 1))
        assertEquals(DmTakeoverService.Step.Abort, DmTakeoverService.afterDisconnect(host("Online", 26000), grace))
        // The late offline report still leads to a takeover.
        assertEquals(DmTakeoverService.Step.Claim, DmTakeoverService.afterDisconnect(host("Offline"), grace - 1))
    }

    @Test
    fun theJoinScreenOffersATakeoverOnlyForAnOfflineHost(): Unit {
        assertTrue(DmTakeoverService.canStartManually(host("Offline")))
        assertTrue(DmTakeoverService.canStartManually(host("Offline", takeover = "Electing")))
        assertFalse(DmTakeoverService.canStartManually(host("Online", 26000)))
        assertFalse(DmTakeoverService.canStartManually(host("Unavailable")))
        assertFalse(DmTakeoverService.canStartManually(host(null)))
    }

    @Test
    fun losersReconnectToTheNewHostOrClaimAgainWhenTheWinnerGivesUp(): Unit {
        assertEquals(DmTakeoverService.Step.Reconnect, DmTakeoverService.whileOthersTakeOver(host("Online", 26001)))
        assertEquals(DmTakeoverService.Step.WaitForHost, DmTakeoverService.whileOthersTakeOver(host("Offline", takeover = "Claimed")))
        assertEquals(DmTakeoverService.Step.Claim, DmTakeoverService.whileOthersTakeOver(host("Offline")))
        assertEquals(DmTakeoverService.Step.WaitForHost, DmTakeoverService.whileOthersTakeOver(host("Unavailable")))
    }
}
