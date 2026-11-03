package calebxzau.rdi.server.service.dmticket

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class DmTicketIssuerTest {
    private val player = UUID.fromString("68b314bb-adaf-52dd-ab96-b5ed00000000")
    private val room = UUID.fromString("01a1805e-32ec-7129-ac00-1e7dd4b4d153")
    private val issuer = DmTicketIssuer(
        keyId = "test",
        privateKey = DmTicketIssuer.loadPrivateKey(VECTOR_PRIVATE_KEY).getOrThrow(),
        clockSeconds = { 1800000000 },
        newTicketId = { "01a1805e-32ec-7129-ac00-000000000001" },
    )

    @Test
    fun aJoinTicketMatchesTheVectorSharedWithDmMasterAndTheClient() {
        assertEquals(VECTOR_JOIN_TICKET, issuer.issue(DmTicketAudience.Join, player, "测试玩家", room).getOrThrow())
    }

    @Test
    fun eachAudienceHasItsOwnLifetimeAndPayload() {
        val api = payload(issuer.issue(DmTicketAudience.Api, player, "Steve", null).getOrThrow())
        assertEquals("api", api["aud"]?.jsonPrimitive?.content)
        assertEquals(1800000000L + 1800, api["exp"]?.jsonPrimitive?.long)
        assertFalse("room" in api)

        val tunnel = payload(issuer.issue(DmTicketAudience.Tunnel, player, "Steve", room).getOrThrow())
        assertEquals("tunnel", tunnel["aud"]?.jsonPrimitive?.content)
        assertEquals(1800000000L + 60, tunnel["exp"]?.jsonPrimitive?.long)
        assertEquals(room.toString(), tunnel["room"]?.jsonPrimitive?.content)
    }

    @Test
    fun aRoomIsRequiredExactlyWhereTheAudienceNeedsOne() {
        assertTrue(issuer.issue(DmTicketAudience.Join, player, "Steve", null).isFailure)
        assertTrue(issuer.issue(DmTicketAudience.Tunnel, player, "Steve", null).isFailure)
        assertTrue(issuer.issue(DmTicketAudience.Api, player, "Steve", room).isFailure)
    }

    @Test
    fun audiencesAreParsedByTheirWireNameOnly() {
        assertEquals(DmTicketAudience.Join, DmTicketAudience.fromWireName("join"))
        assertEquals(null, DmTicketAudience.fromWireName("Join"))
        assertEquals(null, DmTicketAudience.fromWireName("admin"))
    }

    @Test
    fun aMissingOrBrokenPrivateKeyIsReported() {
        assertTrue(DmTicketIssuer.loadPrivateKey("").isFailure)
        assertTrue(DmTicketIssuer.loadPrivateKey("not base64!").isFailure)
    }

    private fun payload(ticket: String) = Json.parseToJsonElement(
        String(java.util.Base64.getUrlDecoder().decode(ticket.substringBefore('.')), Charsets.UTF_8),
    ).jsonObject

    private companion object {
        const val VECTOR_PRIVATE_KEY = "MC4CAQAwBQYDK2VwBCIEIMXoGSwj4I+cck5oqWBwkda1aqDthAAkE5gQlKBGca+P"
        const val VECTOR_JOIN_TICKET = "eyJ2IjoxLCJraWQiOiJ0ZXN0IiwiYXVkIjoiam9pbiIsInN1YiI6IjY4YjMxNGJiLWFkYWYtNTJkZC1hYjk2LWI1ZWQwMDAwMDAwMCIsIm5hbWUiOiLmtYvor5XnjqnlrrYiLCJyb29tIjoiMDFhMTgwNWUtMzJlYy03MTI5LWFjMDAtMWU3ZGQ0YjRkMTUzIiwianRpIjoiMDFhMTgwNWUtMzJlYy03MTI5LWFjMDAtMDAwMDAwMDAwMDAxIiwiaWF0IjoxODAwMDAwMDAwLCJleHAiOjE4MDAwMDAwNjB9.ViZDtUbdMF4AgGdVbH-SA6PsRfQ5dIZhdPnq8vI5ugQUvGXcUwCRXkpRsozNUmr5DdoelkqFFxAVc3tDPZqsAw"
    }
}
