package calebxzau.rdi.server.service.dmticket

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a ticket may be used for. Tickets never work for another purpose. */
enum class DmTicketAudience(val wireName: String, val lifetimeSeconds: Long, val needsRoom: Boolean) {
    Api("api", 30 * 60, false),
    Tunnel("tunnel", 60, true),
    Join("join", 60, true);

    companion object {
        fun fromWireName(name: String): DmTicketAudience? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Signs short-lived DM tickets, so that dm-master and player-run hosts can check who a player is
 * with the public key alone, without ever seeing the account token.
 *
 * Wire format: `base64url(payload JSON).base64url(Ed25519 signature of the first part)`, no padding.
 */
class DmTicketIssuer(
    private val keyId: String,
    private val privateKey: PrivateKey,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newTicketId: () -> String = ::newUuidV7,
) {
    fun issue(audience: DmTicketAudience, playerId: UUID, playerName: String, room: UUID?): Result<String> = runCatching {
        require(audience.needsRoom == (room != null)) {
            if (audience.needsRoom) "${audience.wireName}票据必须指定房间" else "${audience.wireName}票据不能指定房间"
        }
        val issuedAt = clockSeconds()
        val payload = buildJsonObject {
            put("v", 1)
            put("kid", keyId)
            put("aud", audience.wireName)
            put("sub", playerId.toString())
            put("name", playerName)
            room?.let { put("room", it.toString()) }
            put("jti", newTicketId())
            put("iat", issuedAt)
            put("exp", issuedAt + audience.lifetimeSeconds)
        }
        val encodedPayload = URL_ENCODER.encodeToString(Json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(encodedPayload.toByteArray(Charsets.US_ASCII))
            sign()
        }
        "$encodedPayload.${URL_ENCODER.encodeToString(signature)}"
    }

    companion object {
        private const val ALGORITHM = "Ed25519"
        private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun loadPrivateKey(pkcs8Base64: String): Result<PrivateKey> = runCatching {
            require(pkcs8Base64.isNotBlank()) { "dmTicket.privateKey未配置" }
            KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8Base64.trim())))
        }

        @OptIn(ExperimentalUuidApi::class)
        private fun newUuidV7(): String = Uuid.generateV7().toString()
    }
}
