package calebxzau.rdi.server.service.hostrollback

import calebxzau.rdi.common.model.HostRollbackCreateDto
import calebxzhou.rdi.master.net.ok
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.service.host.HostControlService.graceStop
import calebxzhou.rdi.master.service.host.HostService.hostContext
import calebxzhou.rdi.master.service.host.HostService.needOwner
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val rollbackService = HostRollbackService()

/** `/host/{hostId}/rollback`, owner only. Mounted inside the host routes. */
fun Route.hostRollbackRoutes() = route("/rollback") {
    get {
        val ctx = call.hostContext().needOwner
        response(data = rollbackService.status(ctx.host))
    }
    post {
        val ctx = call.hostContext().needOwner
        val dto = call.receive<HostRollbackCreateDto>()
        response(data = rollbackService.request(ctx.host, ctx.player._id, dto) { ctx.graceStop() })
    }
    delete {
        val ctx = call.hostContext().needOwner
        rollbackService.cancel(ctx.host)
        ok()
    }
}
