package calebxzhou.rdi.master.service.modpack

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.util.ok
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.*
import calebxzhou.rdi.master.service.ModpackContext
import calebxzhou.rdi.master.service.DownloadQuotaService
import calebxzhou.rdi.master.service.player
import calebxzhou.rdi.master.service.CLIENT_ONLY_MARK_PREFIX
import calebxzhou.rdi.master.service.clientPackFile
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.addVersionMod
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.addVersionMods
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.changeOptions
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.createVersion
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.createWithVersion
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.preflight
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.validateVersionUpload
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.deleteModpack
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.deleteVersion
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.modpackGuardContext
import calebxzhou.rdi.master.service.modpack.ModpackBuildService.rebuildVersion
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.removeVersionMod
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.removeVersionMods
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.replaceVersionMod
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.replaceVersionMods
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.requireAuthor
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.requireCanManageVersion
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.getUploaderPolicy
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.resolveUploader
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.updateUploaderPolicy
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.updateVersionBaseWorld
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.toBriefVo
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.toDetailVo
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.validateVerName
import calebxzhou.rdi.common.util.sha1
import calebxzau.rdi.server.service.baseworld.BaseWorldService
import org.bson.types.ObjectId
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.getKoin

fun Route.modpackRoutes() {

    route("/modpack") {
        get {
            response(data = ModpackQueryService.listAll())
        }
        get("/list-simple") {
            val hasIconOnly = call.paramNull("hasIconOnly")?.trim()?.toBooleanStrictOrNull() ?: false
            response(data = ModpackQueryService.listSimple(hasIconOnly))
        }
        post("/infos") {
            response(
                data = ModpackQueryService.toModpackVoList(
                    ModpackQueryService.listByIds(call.receive<List<ObjectId>>())
                )
            )
        }
        post("/missing") {
            response(data = ModpackQueryService.findMissingIds(call.receive<List<ObjectId>>()))
        }
        post("/preflight") {
            call.receive<ModpackUploadPreflightDto>().preflight(call.player())
            ok()
        }
        route("/upload-sessions") {
            post {
                response(
                    data = parallelUploadService.create(
                        call.uid,
                        call.receive<ModpackUploadSessionCreateDto>()
                    ).getOrThrow()
                )
            }
            route("/{uploadId}") {
                get {
                    response(data = parallelUploadService.status(call.uid, uploadSessionId()).getOrThrow())
                }
                delete {
                    parallelUploadService.cancel(call.uid, uploadSessionId()).getOrThrow()
                    ok()
                }
                put("/parts/{index}") {
                    val partSha1 = call.request.headers[PART_SHA1_HEADER]
                        ?: throw ParamError("缺少分片SHA-1")
                    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    parallelUploadService.uploadPart(
                        ownerId = call.uid,
                        id = uploadSessionId(),
                        index = param("index").toIntOrNull() ?: throw ParamError("分片序号无效"),
                        declaredLength = contentLength,
                        expectedSha1 = partSha1,
                        source = call.receiveChannel()
                    ).getOrThrow()
                    ok()
                }
                post("/complete") {
                    response(data = parallelUploadService.complete(call.uid, uploadSessionId()).getOrThrow())
                }
            }
        }
        post("/from-upload") {
            val dto = call.receive<ModpackCreateFromUploadDto>()
            val player = call.player()
            parallelUploadService.withReadyUpload(player._id, dto.uploadId) { uploadFile ->
                dto.modpack.createWithVersion(player, uploadFile)
            }.getOrThrow()
            ok()
        }
        get("/my") {
            val mods = ModpackQueryService.listByAuthor(call.uid)
            response(data = mods)
        }
        get("/uploadable") {
            response(data = ModpackQueryService.listUploadable(call.player()))
        }
        route("/{modpackId}") {
            //旧版接口 保持兼容
            get {
                response(data = call.modpackGuardContext().toDetailVo())
            }
            get("/brief") {
                response(data = call.modpackGuardContext().modpack.toBriefVo())
            }
            get("/detail") {
                response(data = call.modpackGuardContext().toDetailVo())
            }
            get("/name") {
                response(data = call.modpackGuardContext().modpack.name)
            }
            post("/play") {
                ModpackQueryService.incrementPlayCount(idParam("modpackId"))
                ok()
            }
            delete {
                call.modpackGuardContext().requireAuthor().deleteModpack()
                ok()
            }
            put("/options") {
                val ctx = call.modpackGuardContext().requireAuthor()
                ctx.changeOptions(call.receive<Modpack.OptionsDto>())
                ok()
            }
            route("/uploaders") {
                get {
                    response(data = call.modpackGuardContext().requireAuthor().getUploaderPolicy())
                }
                post("/resolve") {
                    response(
                        data = call.modpackGuardContext()
                            .requireAuthor()
                            .resolveUploader(call.receive<Modpack.UploaderResolveDto>())
                    )
                }
                put {
                    call.modpackGuardContext()
                        .requireAuthor()
                        .updateUploaderPolicy(call.receive<Modpack.UploaderPolicyUpdateDto>())
                    ok()
                }
            }
            route("/version/{verName}") {
                get {
                    call.modpackGuardContext().versionNull?.let { response(data = it) }
                        ?: throw RequestError("无此版本")
                }
                put("/baseworld") {
                    val ctx = call.modpackGuardContext().requireCanManageVersion()
                    ctx.updateVersionBaseWorld(
                        call.receive<Modpack.VersionBaseWorldUpdateDto>(),
                        call.application.getKoin().get<BaseWorldService>(),
                    )
                    ok()
                }
                get("/client") {
                    val ctx = call.modpackGuardContext()
                    val file = ctx.version.clientPackFile
                    DownloadQuotaService.reserve(ctx.player._id, file.length())
                    call.respondFile(file)
                }
                get("/client/hash") {
                    call.modpackGuardContext().version.clientPackFile.let { response(data = it.sha1) }
                }
                route("/mods"){
                    get{
                        call.modpackGuardContext().version.mods.let { response(data = it) }
                    }
                    post{
                        val ctx = call.modpackGuardContext().requireCanManageVersion()
                        ctx.addVersionMod(call.receive<Mod>())
                        ok()
                    }
                    post("/batch") {
                        val ctx = call.modpackGuardContext().requireCanManageVersion()
                        ctx.addVersionMods(call.receive<List<Mod>>())
                        ok()
                    }
                    put{
                        val ctx = call.modpackGuardContext().requireCanManageVersion()
                        ctx.replaceVersionMod(
                            projectId = param("projectId"),
                            fileId = param("fileId"),
                            newMod = call.receive<Mod>()
                        )
                        ok()
                    }
                    put("/batch") {
                        val ctx = call.modpackGuardContext().requireCanManageVersion()
                        ctx.replaceVersionMods(call.receive<List<ModBatchReplaceItem>>())
                        ok()
                    }
                    delete {
                        val ctx = call.modpackGuardContext().requireCanManageVersion()
                        ctx.removeVersionMod(
                            projectId = param("projectId"),
                            fileId = param("fileId")
                        )
                        ok()
                    }
                    delete("/batch") {
                        val ctx = call.modpackGuardContext().requireCanManageVersion()
                        ctx.removeVersionMods(call.receive<List<ModRef>>())
                        ok()
                    }
                }
                delete {
                    call.modpackGuardContext().requireCanManageVersion().deleteVersion()
                    ok()

                }
                post("/rebuild") {
                    call.modpackGuardContext().requireCanManageVersion().rebuildVersion()

                    ok()

                }
                post("/from-upload") {
                    val ctx = call.modpackGuardContext()
                    val verName = ctx.validateVersionUpload(param("verName"))
                    val dto = call.receive<ModpackVersionCreateFromUploadDto>()
                    parallelUploadService.withReadyUpload(call.uid, dto.uploadId) { uploadFile ->
                        ctx.createVersion(verName, uploadFile, dto.mods, dto.clientExtras)
                    }.getOrThrow()
                    ok()
                }
            }


        }

        get("/search/{modpackName}") {
            val name = call.parameters["modpackName"]?.trim()
                ?: throw ParamError("缺少参数: modpackName")
            val modpacks = ModpackQueryService.searchByName(name)
            response(data = modpacks)
        }
        get("/search") {
            response(data = ModpackQueryService.search(call))
        }


    }
}
