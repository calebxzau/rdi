package calebxzau.rdi.mc.client.dm

import calebxzhou.rdi.mc.common.RDI
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.server.IntegratedServer
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** The player a join ticket proves, or why it proves nothing. */
sealed interface DmJoinCheck {
    data class Allowed(val playerId: UUID, val playerName: String) : DmJoinCheck
    data class Refused(val reason: String) : DmJoinCheck
}

/**
 * Checks the join tickets guests present to this host. The host holds only public keys, so it
 * learns who a guest is but can never pretend to be them anywhere else.
 */
class DmJoinTicketVerifier(
    private val keys: List<PublicKey>,
    private val nowSeconds: () -> Long,
) {
    private val used = HashMap<String, Long>()

    fun check(ticket: String?, room: UUID): DmJoinCheck {
        if (ticket.isNullOrEmpty() || ticket.length > MAX_TICKET_CHARS) return Refused.NO_TICKET
        val encodedPayload = ticket.substringBefore('.', "")
        val encodedSignature = ticket.substringAfter('.', "")
        val signature = decode(encodedSignature) ?: return Refused.BAD_TICKET
        // Nothing in the payload is read until a trusted key has signed it.
        if (keys.none { key -> signedBy(key, encodedPayload, signature) }) return Refused.BAD_TICKET
        val payload = decode(encodedPayload)
            ?.let { runCatching { JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonObject }.getOrNull() }
            ?: return Refused.BAD_TICKET
        val fields = runCatching { Fields.of(payload) }.getOrNull() ?: return Refused.BAD_TICKET
        if (fields.version != 1 || fields.audience != DmTicketAudience.Join.wireName || fields.room != room) {
            return Refused.BAD_TICKET
        }
        val now = nowSeconds()
        if (now > fields.expires + CLOCK_LEEWAY_SECONDS) return Refused.EXPIRED
        if (!markUsed(fields.ticketId, fields.expires + CLOCK_LEEWAY_SECONDS, now)) return Refused.EXPIRED
        return DmJoinCheck.Allowed(fields.player, fields.name)
    }

    private fun markUsed(ticketId: String, forgetAfter: Long, now: Long): Boolean = synchronized(used) {
        used.values.removeIf { it < now }
        used.putIfAbsent(ticketId, forgetAfter) == null
    }

    private class Fields(
        val version: Int,
        val audience: String,
        val player: UUID,
        val name: String,
        val room: UUID,
        val ticketId: String,
        val expires: Long,
    ) {
        companion object {
            fun of(json: JsonObject) = Fields(
                json.get("v").asInt,
                json.get("aud").asString,
                UUID.fromString(json.get("sub").asString),
                json.get("name").asString,
                UUID.fromString(json.get("room").asString),
                json.get("jti").asString,
                json.get("exp").asLong,
            )
        }
    }

    private object Refused {
        val NO_TICKET = DmJoinCheck.Refused("请通过「加入DM房间」进入")
        val BAD_TICKET = DmJoinCheck.Refused("账号验证失败，请重新进入房间")
        val EXPIRED = DmJoinCheck.Refused("进入房间的凭证已失效，请重新进入房间")
    }

    private fun signedBy(key: PublicKey, payload: String, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance(ALGORITHM).run {
            initVerify(key)
            update(payload.toByteArray(Charsets.US_ASCII))
            verify(signature)
        }
    }.getOrDefault(false)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_TICKET_CHARS = 2048
        private const val CLOCK_LEEWAY_SECONDS = 30L
        private const val ALGORITHM = "Ed25519"
        // DER header of an X.509 Ed25519 public key; the raw 32 bytes follow it.
        private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun publicKey(rawBase64: String): Result<PublicKey> = runCatching {
            val raw = Base64.getDecoder().decode(rawBase64.trim())
            require(raw.size == 32) { "DM票据公钥必须是32字节" }
            KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
        }
    }
}

/** Who may log in to a world this client is hosting as a DM room. */
object DmJoinGate {
    /**
     * The production public keys of the RDI master's `dmTicket.privateKey`, base64 of the raw 32
     * bytes. With none listed every guest is refused, so a build cannot silently accept anyone.
     */
    internal val BUILT_IN_KEYS = listOf("mTa7w4O1Al1wF2Cc8ZrYja2MkVdSTiyJA2LPEhJLWWg=")

    private val logger = LoggerFactory.getLogger("rdi-dm-join")

    /** Server time minus this machine's, so a wrong clock on the host does not lock everyone out. */
    @Volatile
    private var clockOffsetSeconds = 0L

    private val verifier by lazy { DmJoinTicketVerifier(loadKeys()) { System.currentTimeMillis() / 1000 + clockOffsetSeconds } }

    fun syncClock(serverTimeSeconds: Long) {
        clockOffsetSeconds = serverTimeSeconds - System.currentTimeMillis() / 1000
    }

    /** Null when [server] is not hosting a DM room, so a plain LAN world works as before. */
    @JvmStatic
    fun check(server: MinecraftServer, ticket: String?): DmJoinCheck? {
        val room = (server as? IntegratedServer)?.let(DmHostService::hostedRoom) ?: return null
        return verifier.check(ticket, room)
    }

    private fun loadKeys(): List<PublicKey> {
        val developmentKey = System.getProperty("rdi.dm.ticketPublicKey")?.takeIf { RDI.DEBUG }
        val keys = (BUILT_IN_KEYS + listOfNotNull(developmentKey)).mapNotNull { encoded ->
            DmJoinTicketVerifier.publicKey(encoded)
                .onFailure { error -> logger.error("Ignoring an invalid DM ticket key", error) }
                .getOrNull()
        }
        if (keys.isEmpty()) logger.error("No DM ticket key is configured; every guest will be refused")
        return keys
    }
}
