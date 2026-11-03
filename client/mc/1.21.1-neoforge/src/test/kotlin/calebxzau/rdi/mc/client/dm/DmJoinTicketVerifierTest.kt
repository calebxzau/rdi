package calebxzau.rdi.mc.client.dm

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DmJoinTicketVerifierTest {
    private val player = UUID.fromString("68b314bb-adaf-52dd-ab96-b5ed00000000")
    private val room = UUID.fromString("01a1805e-32ec-7129-ac00-1e7dd4b4d153")
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private var now = 1_800_000_000L
    private val verifier = DmJoinTicketVerifier(listOf(pair.public)) { now }

    private fun sign(
        audience: String = "join",
        ticketRoom: UUID? = room,
        id: String = UUID.randomUUID().toString(),
        expires: Long = 1_800_000_060,
        signer: java.security.PrivateKey = pair.private,
    ): String {
        val roomField = ticketRoom?.let { "\"room\":\"$it\"," }.orEmpty()
        val payload = """{"v":1,"kid":"test","aud":"$audience","sub":"$player","name":"测试玩家",$roomField"jti":"$id","iat":1800000000,"exp":$expires}"""
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encoded = encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("Ed25519").run {
            initSign(signer)
            update(encoded.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$encoded.${encoder.encodeToString(signature)}"
    }

    @Test
    fun acceptsTheVectorSignedByTheRdiMaster() {
        val key = DmJoinTicketVerifier.publicKey("Q5hKLEAJnWx/a/rYnyXrdlZjA3EzEa9QqI6a1M6Mtf8=").getOrThrow()
        val check = DmJoinTicketVerifier(listOf(key)) { now }.check(VECTOR_JOIN_TICKET, room)
        assertEquals(DmJoinCheck.Allowed(player, "测试玩家"), check)
    }

    @Test
    fun aValidTicketProvesThePlayerOnlyOnce() {
        val ticket = sign()
        assertEquals(DmJoinCheck.Allowed(player, "测试玩家"), verifier.check(ticket, room))
        assertIs<DmJoinCheck.Refused>(verifier.check(ticket, room))
    }

    @Test
    fun aGuestWithoutATicketIsToldHowToJoin() {
        assertEquals(DmJoinCheck.Refused("请通过「加入DM房间」进入"), verifier.check(null, room))
        assertEquals(DmJoinCheck.Refused("请通过「加入DM房间」进入"), verifier.check("", room))
    }

    @Test
    fun aForgedOrBrokenTicketIsRefused() {
        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private
        val genuine = sign()
        val other = DmJoinTicketVerifier(emptyList()) { now }
        for (broken in listOf(
            sign(signer = otherKey),
            "no-dot",
            "a.b",
            "!!.!!",
            "${genuine.substringBefore('.')}x.${genuine.substringAfter('.')}",
            "a".repeat(DmJoinTicketVerifier.MAX_TICKET_CHARS + 1),
        )) {
            assertIs<DmJoinCheck.Refused>(verifier.check(broken, room), broken.take(20))
        }
        assertIs<DmJoinCheck.Refused>(other.check(genuine, room))
    }

    @Test
    fun aTicketForAnotherPurposeOrRoomIsRefused() {
        assertIs<DmJoinCheck.Refused>(verifier.check(sign(audience = "api", ticketRoom = null), room))
        assertIs<DmJoinCheck.Refused>(verifier.check(sign(audience = "tunnel"), room))
        assertIs<DmJoinCheck.Refused>(verifier.check(sign(ticketRoom = UUID.randomUUID()), room))
        assertIs<DmJoinCheck.Refused>(verifier.check(sign(ticketRoom = null), room))
    }

    @Test
    fun anExpiredTicketIsRefusedAfterTheLeeway() {
        val ticket = sign(expires = now)
        now += 31
        assertEquals(DmJoinCheck.Refused("进入房间的凭证已失效，请重新进入房间"), verifier.check(ticket, room))
        val inLeeway = sign(expires = now - 30)
        assertIs<DmJoinCheck.Allowed>(verifier.check(inLeeway, room))
    }

    @Test
    fun usedTicketsAreForgottenOnceTheyCouldNotWorkAnyway() {
        val first = sign(id = "same-id", expires = now)
        assertIs<DmJoinCheck.Allowed>(verifier.check(first, room))
        now += 31
        val later = sign(id = "same-id", expires = now + 60)
        assertIs<DmJoinCheck.Allowed>(verifier.check(later, room))
    }

    @Test
    fun invalidPublicKeysAreReported() {
        assertTrue(DmJoinTicketVerifier.publicKey("not base64!").isFailure)
        assertTrue(DmJoinTicketVerifier.publicKey("AAAA").isFailure)
    }

    @Test
    fun theBuiltInKeysAreUsable() {
        assertTrue(DmJoinGate.BUILT_IN_KEYS.isNotEmpty(), "a release without a key refuses every guest")
        DmJoinGate.BUILT_IN_KEYS.forEach { DmJoinTicketVerifier.publicKey(it).getOrThrow() }
    }

    private companion object {
        const val VECTOR_JOIN_TICKET = "eyJ2IjoxLCJraWQiOiJ0ZXN0IiwiYXVkIjoiam9pbiIsInN1YiI6IjY4YjMxNGJiLWFkYWYtNTJkZC1hYjk2LWI1ZWQwMDAwMDAwMCIsIm5hbWUiOiLmtYvor5XnjqnlrrYiLCJyb29tIjoiMDFhMTgwNWUtMzJlYy03MTI5LWFjMDAtMWU3ZGQ0YjRkMTUzIiwianRpIjoiMDFhMTgwNWUtMzJlYy03MTI5LWFjMDAtMDAwMDAwMDAwMDAxIiwiaWF0IjoxODAwMDAwMDAwLCJleHAiOjE4MDAwMDAwNjB9.ViZDtUbdMF4AgGdVbH-SA6PsRfQ5dIZhdPnq8vI5ugQUvGXcUwCRXkpRsozNUmr5DdoelkqFFxAVc3tDPZqsAw"
    }
}
