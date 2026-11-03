package calebxzau.rdi.mc.client.dm

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DmTicketClientTest {
    private val minute = 60L * 1_000_000_000
    private val room: UUID = UUID.fromString("01a1805e-32ec-7129-ac00-1e7dd4b4d153")
    private var now = 0L
    private val requests = mutableListOf<Pair<DmTicketAudience, UUID?>>()
    private var answer: () -> Result<String> = { Result.success("ticket-${requests.size}") }
    private val client = DmTicketClient(
        fetch = { audience, target ->
            requests += audience to target
            answer()
        },
        nanoTime = { now },
    )

    @Test
    fun anApiTicketIsReusedUntilHalfItsLifetime() {
        assertEquals("ticket-1", client.apiTicket().getOrThrow())
        now += 14 * minute
        assertEquals("ticket-1", client.apiTicket().getOrThrow())
        now += 2 * minute
        assertEquals("ticket-2", client.apiTicket().getOrThrow())
        assertEquals(listOf<Pair<DmTicketAudience, UUID?>>(DmTicketAudience.Api to null, DmTicketAudience.Api to null), requests)
    }

    @Test
    fun theCurrentTicketKeepsWorkingWhileTheRdiMasterIsAway() {
        client.apiTicket().getOrThrow()
        answer = { Result.failure(java.io.IOException("connection refused")) }

        now += 20 * minute
        assertEquals("ticket-1", client.apiTicket().getOrThrow())

        now += 10 * minute
        assertIs<java.io.IOException>(client.apiTicket().exceptionOrNull())
    }

    @Test
    fun anExpiredLoginIsReportedEvenWithAUsableTicket() {
        client.apiTicket().getOrThrow()
        answer = { Result.failure(DmLoginExpiredException()) }
        now += 20 * minute
        assertIs<DmLoginExpiredException>(client.apiTicket().exceptionOrNull())
    }

    @Test
    fun aRefusedTicketIsNotUsedAgain() {
        client.apiTicket().getOrThrow()
        client.invalidateApiTicket()
        assertEquals("ticket-2", client.apiTicket().getOrThrow())
    }

    @Test
    fun roomTicketsAreNeverReused() {
        assertEquals("ticket-1", client.joinTicket(room).getOrThrow())
        assertEquals("ticket-2", client.joinTicket(room).getOrThrow())
        assertEquals("ticket-3", client.tunnelTicket(room).getOrThrow())
        assertEquals(
            listOf<Pair<DmTicketAudience, UUID?>>(DmTicketAudience.Join to room, DmTicketAudience.Join to room, DmTicketAudience.Tunnel to room),
            requests,
        )
    }

    @Test
    fun theMastersAnswerIsParsed() {
        assertEquals("abc", parseTicketResponse(200, """{"code":0,"msg":"","data":"abc"}""").getOrThrow())
        assertIs<DmLoginExpiredException>(parseTicketResponse(401, """{"code":-401,"msg":"未登录"}""").exceptionOrNull())
        assertIs<DmLoginExpiredException>(parseTicketResponse(401, "").exceptionOrNull())
        assertEquals("票据用途不正确", parseTicketResponse(200, """{"code":-1,"msg":"票据用途不正确"}""").exceptionOrNull()?.message)
        assertTrue(parseTicketResponse(502, "<html>").isFailure)
        assertTrue(parseTicketResponse(200, """{"code":0,"msg":""}""").isFailure)
        assertFailsWith<DmLoginExpiredException> { parseTicketResponse(401, "").getOrThrow() }
    }
}
