package calebxzau.rdi.mc.client.dm

import calebxzhou.rdi.mc.common.RDI
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** What a ticket may be used for; the RDI master signs this into the ticket. */
enum class DmTicketAudience(val wireName: String) {
    Api("api"),
    Tunnel("tunnel"),
    Join("join"),
}

/** The account token was refused, which only a fresh login fixes. */
class DmLoginExpiredException : IllegalStateException("登录已过期，请重启RDI启动器后再试")

/** Short-lived proof of who this player is. Tickets go to DM services and hosts; the account token never does. */
interface DmTicketSource {
    fun apiTicket(): Result<String>
    fun invalidateApiTicket()
    fun tunnelTicket(room: UUID): Result<String>
    fun joinTicket(room: UUID): Result<String>
}

/**
 * Keeps one API ticket and replaces it halfway through its 30 minutes, so that a player who gets
 * disconnected while the RDI master is away still holds a ticket for the takeover. Age is measured
 * on this machine's monotonic clock, so a wrong system clock does not matter.
 */
class DmTicketClient(
    private val fetch: (DmTicketAudience, UUID?) -> Result<String>,
    private val nanoTime: () -> Long = System::nanoTime,
) : DmTicketSource {
    private class Cached(val ticket: String, val fetchedAt: Long)

    private var cached: Cached? = null

    override fun apiTicket(): Result<String> = synchronized(this) {
        val current = cached
        if (current != null && nanoTime() - current.fetchedAt < API_REFRESH_NANOS) {
            return@synchronized Result.success(current.ticket)
        }
        fetch(DmTicketAudience.Api, null).map { ticket ->
            cached = Cached(ticket, nanoTime())
            ticket
        }.recoverCatching { error ->
            // Still valid for a while, and the RDI master may be back before it runs out.
            if (current != null && error !is DmLoginExpiredException && nanoTime() - current.fetchedAt < API_USABLE_NANOS) {
                logger.warn("Cannot refresh the DM ticket; keeping the current one", error)
                current.ticket
            } else {
                throw error
            }
        }
    }

    override fun invalidateApiTicket() {
        synchronized(this) { cached = null }
    }

    override fun tunnelTicket(room: UUID): Result<String> = fetch(DmTicketAudience.Tunnel, room)

    override fun joinTicket(room: UUID): Result<String> = fetch(DmTicketAudience.Join, room)

    private companion object {
        val logger = LoggerFactory.getLogger("rdi-dm-tickets")
        const val API_REFRESH_NANOS = 15L * 60 * 1_000_000_000
        // A little under the ticket's 30 minutes, for the time the request took.
        const val API_USABLE_NANOS = 29L * 60 * 1_000_000_000
    }
}

internal fun parseTicketResponse(status: Int, body: String): Result<String> = runCatching {
    if (status == 401) throw DmLoginExpiredException()
    val json = runCatching { JsonParser.parseString(body).asJsonObject }
        .getOrElse { throw IllegalStateException("账号服务返回HTTP$status", it) }
    if (status != 200 || json.get("code")?.asInt != 0) {
        error(json.get("msg")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: "账号服务返回HTTP$status")
    }
    json.get("data")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: error("账号服务没有返回票据")
}

/** Tickets from the RDI master, paid for with the account token the launcher gave the game. */
object DmTickets : DmTicketSource by DmTicketClient(::fetchTicket)

private val ticketHttp: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}

private fun fetchTicket(audience: DmTicketAudience, room: UUID?): Result<String> = runCatching {
    // Development clients have no launcher to get a token from.
    val token = System.getProperty("rdi.dm.devJwt")?.takeIf { RDI.DEBUG }
        ?: Minecraft.getInstance().user.accessToken
    if (token.isBlank() || token == "0") throw DmLoginExpiredException()
    val form = buildString {
        append("aud=").append(audience.wireName)
        room?.let { append("&room=").append(URLEncoder.encode(it.toString(), Charsets.UTF_8)) }
    }
    val request = HttpRequest.newBuilder(URI("${RDI.IHQ_URL.trimEnd('/')}/player/dm-ticket"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()
    val response = ticketHttp.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
    parseTicketResponse(response.statusCode(), response.body()).getOrThrow()
}
