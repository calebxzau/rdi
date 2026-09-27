package calebxzau.rdi.server.service.baseworld

import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionCreateDto
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.toUUID
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.ok
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.net.uid
import calebxzhou.rdi.master.service.player
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.ktor.ext.getKoin
import java.util.UUID

fun Route.baseWorldRoutes() = route("/baseworld") {
    get {
        val myOnly = call.request.queryParameters["myOnly"]?.equals("true", ignoreCase = true) == true
        val worlds = if (myOnly) {
            call.baseWorldService().listByOwner(call.uid.toUUID())
        } else {
            call.baseWorldService().listReleased()
        }
        response(data = worlds.getOrThrow())
    }
    post {
        val dto = call.receive<BaseWorld.CreateDto>()
        val account = call.player()
        response(
            data = call.baseWorldService().create(
                owner = account,
                ownerId = account._id.toUUID(),
                name = dto.name,
                levelType = dto.levelType,
                generatorSettings = dto.generatorSettings,
                size = dto.size,
                generated = dto.generated,
            ).getOrThrow()
        )
    }
    route("/{baseWorldId}") {
        get {
            val world = call.baseWorldService()
                .findById(call.uid.toUUID(), call.baseWorldId())
                .getOrThrow()
                ?: throw RequestError("地图模板不存在")
            response(data = world)
        }
        delete {
            if (!call.baseWorldService().delete(call.uid.toUUID(), call.baseWorldId()).getOrThrow()) {
                throw RequestError("地图模板不存在")
            }
            ok()
        }
        put {
            val player = call.player()
            response(
                data = call.baseWorldService().rename(
                    requesterId = player._id.toUUID(),
                    id = call.baseWorldId(),
                    name = call.receive<BaseWorld.NameUpdateDto>().name,
                    isDav = player.isDav,
                ).getOrThrow()
            )
        }
        route("/upload") {
            post {
                response(
                    data = call.baseWorldService().createUpload(
                        ownerId = call.uid.toUUID(),
                        worldId = call.baseWorldId(),
                        dto = call.receive<BaseWorldUploadSessionCreateDto>(),
                    ).getOrThrow()
                )
            }
            route("/{uploadId}") {
                get {
                    response(
                        data = call.baseWorldService().uploadStatus(
                            call.uid.toUUID(),
                            call.baseWorldId(),
                            call.uploadId(),
                        ).getOrThrow()
                    )
                }
                delete {
                    call.baseWorldService().cancelUpload(
                        call.uid.toUUID(),
                        call.baseWorldId(),
                        call.uploadId(),
                    ).getOrThrow()
                    ok()
                }
                put("/parts/{index}") {
                    val sha1 = call.request.headers["X-Part-SHA1"] ?: throw ParamError("缺少分片SHA-1")
                    val lengthHeader = call.request.headers[HttpHeaders.ContentLength]
                    val length = lengthHeader?.toLongOrNull()
                        ?: if (lengthHeader == null) null else throw ParamError("分片长度不正确")
                    call.baseWorldService().uploadPart(
                        ownerId = call.uid.toUUID(),
                        worldId = call.baseWorldId(),
                        uploadId = call.uploadId(),
                        index = call.partIndex(),
                        declaredLength = length,
                        sha1 = sha1,
                        source = call.receiveChannel(),
                    ).getOrThrow()
                    ok()
                }
                post("/complete") {
                    response(
                        data = call.baseWorldService().completeUpload(
                            call.uid.toUUID(),
                            call.baseWorldId(),
                            call.uploadId(),
                        ).getOrThrow()
                    )
                }
            }
        }
    }
}

private fun ApplicationCall.baseWorldService(): BaseWorldService =
    application.getKoin().get()

private suspend fun ApplicationCall.baseWorldId(): UUID {
    val value = parameters["baseWorldId"] ?: throw ParamError("地图模板ID不正确")
    return runCatching { UUID.fromString(value) }
        .getOrElse { throw ParamError("地图模板ID不正确") }
}

private suspend fun ApplicationCall.uploadId(): UUID {
    val value = parameters["uploadId"] ?: throw ParamError("上传会话ID不正确")
    return runCatching { UUID.fromString(value) }
        .getOrElse { throw ParamError("上传会话ID不正确") }
}

private suspend fun ApplicationCall.partIndex(): Int {
    val value = parameters["index"] ?: throw ParamError("分片序号不正确")
    return value.toIntOrNull() ?: throw ParamError("分片序号不正确")
}
