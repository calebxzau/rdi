package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.ProxyHostRoute
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.master.net.clientIp
import calebxzhou.rdi.master.net.err
import calebxzhou.rdi.master.net.idParam
import calebxzhou.rdi.master.net.ok
import calebxzhou.rdi.master.net.param
import calebxzhou.rdi.master.net.paramNull
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.service.GameNodeService
import calebxzhou.rdi.master.service.host.HostControlService.forceStop
import calebxzhou.rdi.master.service.host.HostControlService.graceStop
import calebxzhou.rdi.master.service.host.HostControlService.restart
import calebxzhou.rdi.master.service.host.HostControlService.sendCommand
import calebxzhou.rdi.master.service.host.HostControlService.start
import calebxzhou.rdi.master.service.host.HostControlService.status
import calebxzhou.rdi.master.service.host.HostFileService.deleteHostFile
import calebxzhou.rdi.master.service.host.HostFileService.createHostFile
import calebxzhou.rdi.master.service.host.HostFileService.listHostFiles
import calebxzhou.rdi.master.service.host.HostFileService.readHostFile
import calebxzhou.rdi.master.service.host.HostFileService.renameHostFile
import calebxzhou.rdi.master.service.host.HostFileService.searchHostFiles
import calebxzhou.rdi.master.service.host.HostFileService.updateHostFile
import calebxzhou.rdi.master.service.host.HostFileService.uploadHostFile
import calebxzhou.rdi.master.service.host.HostInstallService.createHost
import calebxzhou.rdi.master.service.host.HostMemberService.addMember
import calebxzhou.rdi.master.service.host.HostMemberService.delMember
import calebxzhou.rdi.master.service.host.HostMemberService.quit
import calebxzhou.rdi.master.service.host.HostMemberService.setRole
import calebxzhou.rdi.master.service.host.HostMemberService.transferOwnership
import calebxzhou.rdi.master.service.host.HostModsService.addDisabledMods
import calebxzhou.rdi.master.service.host.HostModsService.addExtraMods
import calebxzhou.rdi.master.service.host.HostModsService.deleteDisabledMods
import calebxzhou.rdi.master.service.host.HostModsService.deleteExtraMods
import calebxzhou.rdi.master.service.host.HostLifecycleService.changeOptions
import calebxzhou.rdi.master.service.host.HostLifecycleService.changeVersion
import calebxzhou.rdi.master.service.host.HostLifecycleService.delete
import calebxzhou.rdi.master.service.host.HostLifecycleService.resetWorld
import calebxzhou.rdi.master.service.host.HostService.hostContext
import calebxzhou.rdi.master.service.host.HostService.listenLogs
import calebxzhou.rdi.master.service.host.HostService.needAdmin
import calebxzhou.rdi.master.service.host.HostService.needOwner
import calebxzhou.rdi.master.service.host.HostService.needInvitedMember
import calebxzhou.rdi.master.service.host.HostQueryService.listAllHosts
import calebxzhou.rdi.master.service.host.HostQueryService.getBriefHost
import calebxzhou.rdi.master.service.host.HostQueryService.toDetailVo
import calebxzhou.rdi.master.service.player
import calebxzau.rdi.server.service.baseworld.BaseWorldService
import calebxzhou.rdi.model.Role
import com.github.dockerjava.api.exception.NotFoundException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.server.websocket.webSocket
import io.ktor.sse.ServerSentEvent
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.koin.ktor.ext.getKoin
import org.bson.types.ObjectId

/**
 * calebxzhou @ 2026-05-28 22:00
 */

// ---------- Routing DSL (mirrors teamRoutes style) ----------
fun Route.hostRoutes() = route("/host") {
    get("/online-player-ids") {
        response(data = HostPresenceService.getAllHostsOnlinePlayerIds())
    }
    route("") {
        post("/v2") {
            val dto = call.receive<Host.CreateDto>()
            val baseWorldService = call.application.getKoin().get<BaseWorldService>()
            call.player().createHost(dto, baseWorldService)
            ok()
        }
        get("/my/{page?}") {
            response(data = call.player().listAllHosts(myOnly = true))
        }
        //新
        get("/list/{page?}") {
            val hosts = call.player().listAllHosts(paramNull("page")?.toInt() ?: 0, myOnly = false)
            response(data = hosts)
        }
        get("/search/modpack/{modpackId}/{verName}") {
            val hosts = HostQueryService.findByModpackVersion(idParam("modpackId"), param("verName"))
            response(data = hosts)
        }

    }
    route("/{hostId}") {
        get("/status") {
            call.hostContext().host.status.let { response(data = it) }
        }
        post("/start") {
            call.hostContext().start()
            ok()
        }
        post("/stop") {
            call.hostContext().needAdmin.graceStop()
            ok()
        }
        post("/force-stop") {
            call.hostContext().needAdmin.forceStop()
            ok()
        }
        post("/command") {
            val ctx = call.hostContext().needAdmin
            response(data = ctx.sendCommand(param("command"), waitForResponse = true))
        }
        post("/restart") {
            call.hostContext().needAdmin.restart()
            ok()
        }
        put("/options") {
            val ctx = call.hostContext().needAdmin
            ctx.changeOptions(call.receive<Host.OptionsDto>())
            ok()
        }
        /*put("/gamerules") {
            call.hostContext().needAdmin.changeGameRules(call.paramT("data"))
            ok()
        }*/
        post("/update") {
            call.hostContext().needAdmin.changeVersion(paramNull("verName"))
            ok()
        }
        post("/transfer/{uid2}") {
            call.hostContext().needOwner.transferOwnership()
            ok()
        }
        delete {
            call.hostContext().needOwner.delete(call.receiveNullable<Host.DeleteDto>() ?: Host.DeleteDto())
            ok()
        }
        get {
            HostQueryService.getById(idParam("hostId"))?.let {
                response(data = it)
            } ?: err("无此房间")
        }
        post("/reset-world") {
            val baseWorldService = call.application.getKoin().get<BaseWorldService>()
            call.hostContext().needOwner.resetWorld(baseWorldService)
            ok()
        }
        get("/brief") {
            call.player().getBriefHost(idParam("hostId"))?.let {
                response(data = it)
            } ?: err("无此房间")
        }
        get("/detail") {
            val host = HostQueryService.getById(idParam("hostId")) ?: throw RequestError("无此房间")
            val player = call.player()
            val canViewMembers = host.ownerId == player._id ||
                host.members.any { it.id == player._id } || player.isDav
            val detail = host.toDetailVo()
            response(data = if (canViewMembers) detail else detail.copy(members = emptyList()))
        }
        get("/members") {
            response(data = call.hostContext().needInvitedMember.host.members)
        }
        route("/mods") {
            route("/extra"){
                post {
                    val ctx = call.hostContext().needAdmin
                    ctx.addExtraMods(call.receive())
                    ok()
                }
                delete {
                    val ctx = call.hostContext().needAdmin
                    response(data = ctx.deleteExtraMods(call.receive()))
                }
                get {
                    response(data = call.hostContext().host.extraMods)
                }
            }
            route("/disabled") {
                post {
                    val ctx = call.hostContext().needAdmin
                    response(data = ctx.addDisabledMods(call.receive()))
                }
                delete {
                    val ctx = call.hostContext().needAdmin
                    response(data = ctx.deleteDisabledMods(call.receive()))
                }
                get {
                    response(data = call.hostContext().host.disabledMods)
                }
            }
        }

        route("/files") {
            get {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.listHostFiles(paramNull("path") ?: ""))
            }
            get("/search") {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.searchHostFiles(param("query")))
            }
            post("/file") {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.uploadHostFile(call))
            }
            get("/file") {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.readHostFile(param("path")))
            }
            put("/file") {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.updateHostFile(call.receive()))
            }
            post("/create") {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.createHostFile(call.receive()))
            }
            put("/rename") {
                val ctx = call.hostContext().needAdmin
                response(data = ctx.renameHostFile(call.receive()))
            }
            delete("/file") {
                val ctx = call.hostContext().needAdmin
                ctx.deleteHostFile(call.receive())
                ok()
            }
        }
        /*post("/modpack/{modpackId}/{verName}") {
            call.hostContext().needAdmin.changeModpack(idParam("modpackId"), param("verName"))
            ok()
        }*/

        route("/log") {
            sse("/stream") {
                val ctx = try {
                    call.hostContext().needInvitedMember
                } catch (err: NotFoundException) {
                    send(ServerSentEvent(event = "error", data = "此房间已被删除"))
                    return@sse
                } catch (err: RequestError) {
                    send(ServerSentEvent(event = "error", data = err.message ?: "unknown"))
                    return@sse
                }
                ctx.listenLogs(this)

            }
        }
        put("/quit") {
            call.hostContext().quit()
            ok()
        }
        route("/member/{uid2}") {
            put("/role/{role}") {
                call.hostContext().needOwner.setRole(Role.valueOf(param("role")))
                ok()
            }
            delete {
                call.hostContext().needAdmin.delMember()
                ok()
            }
        }
        post("/member/{qq}") {
            call.hostContext().needAdmin.addMember(param("qq"))
            ok()

        }
    }


}

//单独拿出来是为了不走authentication  proxy和mc要用
fun Route.hostPlayRoutes() = route("/host") {
    get("/status") {
        val port = param("port").toInt()
        val host = HostQueryService.getByPort(port) ?: throw RequestError("无此房间")
        response(data = host.status)
    }
    get("/route") {
        val clientIp = call.clientIp
        if (!GameNodeService.isProxyIpAllowed(clientIp)) {
            throw RequestError("无权访问房间路由")
        }
        val port = param("port").toInt()
        val legacyHost = HostQueryService.getByPort(port) ?: throw RequestError("无此房间")
        response(data = ProxyHostRoute(legacyHost.status, GameNodeService.selectBackendHost(clientIp), legacyHost.port))
    }
    webSocket("/play/{hostId}") {
        val rawHostId = call.param("hostId")

        val hostId = runCatching { ObjectId(rawHostId) }.getOrNull()
        if (hostId == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "host无效"))
            return@webSocket
        }

        val host = HostQueryService.getById(hostId)
        if (host == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "未知房间"))
            return@webSocket
        }

        if (!HostRuntimeService.registerPlayableSession(hostId, this)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "重复连接"))
            return@webSocket
        }

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    HostRuntimeService.handlePlayableMessage(hostId, frame.readText())
                }
            }
        } finally {
            HostRuntimeService.unregisterPlayableSession(hostId, this)
        }
    }
}
