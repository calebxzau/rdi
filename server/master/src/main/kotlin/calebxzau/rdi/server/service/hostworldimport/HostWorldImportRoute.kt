package calebxzau.rdi.server.service.hostworldimport

import calebxzau.rdi.common.model.HostWorldImportCreateDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.ok
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.service.host.HostService.hostContext
import calebxzhou.rdi.master.service.host.HostService.needOwner
import calebxzhou.rdi.master.service.player
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.bson.types.ObjectId
import org.koin.ktor.ext.getKoin
import java.util.UUID

/** `/host/{hostId}/world-import`, owner only. Mounted inside the host routes. */
fun Route.hostWorldImportRoutes() = route("/world-import") {
    post("/precheck") {
        val ctx = call.hostContext().needOwner
        response(data = call.importService().precheck(ctx.host, ctx.player._id, call.receive<HostWorldImportPrecheckDto>()))
    }
    post {
        val ctx = call.hostContext().needOwner
        response(data = call.importService().create(ctx.host, ctx.player._id, call.receive<HostWorldImportCreateDto>()))
    }
    route("/{importId}") {
        get {
            val ctx = call.hostContext().needOwner
            response(data = call.importService().status(ctx.host._id, call.importId(), ctx.player._id))
        }
        delete {
            val ctx = call.hostContext().needOwner
            call.importService().cancel(ctx.host._id, call.importId(), ctx.player._id)
            ok()
        }
        put("/parts/{index}") {
            val ctx = call.hostContext().needOwner
            val sha1 = call.request.headers["X-Part-SHA1"] ?: throw ParamError("缺少分片SHA-1")
            val lengthHeader = call.request.headers[HttpHeaders.ContentLength]
            val length = lengthHeader?.toLongOrNull() ?: if (lengthHeader == null) null else throw ParamError("分片长度不正确")
            val index = call.parameters["index"]?.toIntOrNull() ?: throw ParamError("分片序号不正确")
            call.importService().uploadPart(ctx.host._id, call.importId(), ctx.player._id, index, length, sha1, call.receiveChannel())
            ok()
        }
        post("/complete") {
            val ctx = call.hostContext().needOwner
            response(data = call.importService().complete(ctx.host._id, call.importId(), ctx.player._id))
        }
    }
}

/** `POST /admin/host/{hostId}/world-import/{importId}/resolve`, administrators only (§10.5). */
fun Route.hostWorldImportAdminRoutes() = route("/admin/host/{hostId}/world-import/{importId}") {
    post("/resolve") {
        val admin = call.player()
        if (!admin.isDav) throw RequestError("无权限")
        val hostId = call.parameters["hostId"]?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: throw ParamError("房间ID不正确")
        response(data = call.importService().resolve(hostId, call.importId(), admin.name, call.receive<HostWorldImportResolveDto>()))
    }
}

private fun ApplicationCall.importService(): HostWorldImportService = application.getKoin().get()

private fun ApplicationCall.importId(): UUID {
    val value = parameters["importId"] ?: throw ParamError("导入任务ID不正确")
    return runCatching { UUID.fromString(value) }.getOrElse { throw ParamError("导入任务ID不正确") }
}
