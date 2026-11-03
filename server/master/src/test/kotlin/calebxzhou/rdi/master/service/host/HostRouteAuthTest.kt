package calebxzhou.rdi.master.service.host

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Hosts and proxies call the public /host routes without a login, next to /host/{hostId} routes
 * that need one. Ktor 3.6.0 sent such public routes to the login check (KTOR-9925), which cut
 * every host off from the master, so an upgrade must keep this split working.
 */
class HostRouteAuthTest {
    /** The real routes, in the same order as the master. A request may fail after routing; only a 401 matters here. */
    private fun ApplicationTestBuilder.masterRoomRoutes() {
        application {
            install(WebSockets)
            install(SSE)
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(JWT.require(Algorithm.HMAC256("test")).build())
                    validate { JWTPrincipal(it.payload) }
                }
            }
            install(StatusPages) {
                exception<Throwable> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
            }
            routing {
                hostPlayRoutes()
                authenticate("auth-jwt") {
                    hostRoutes()
                }
            }
        }
    }

    @Test
    fun `proxies can look up rooms without a login`() = testApplication {
        masterRoomRoutes()

        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/host/route").status)
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/host/status").status)
    }

    @Test
    fun `rooms can open their connection without a login`() = testApplication {
        masterRoomRoutes()
        val rooms = createClient { install(ClientWebSockets) }

        // An unusable id still completes the handshake; a login check would refuse it with 401.
        rooms.webSocket("/host/play/not-a-room-id") {
            incoming.receiveCatching()
        }
    }

    @Test
    fun `room management still needs a login`() = testApplication {
        masterRoomRoutes()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/host/online-player-ids").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/host/my").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/host/v2").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/host/6ae96a61400fea4ad000af83/detail").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/host/6ae96a61400fea4ad000af83/status").status)
    }
}
