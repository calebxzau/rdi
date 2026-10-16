package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.master.net.param
import calebxzhou.rdi.master.net.paramNull
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.service.UpdateService.mcCoreModFile
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.zip.ZipFile

private val CLIENT_LIBS_DIR = File("client-libs").also { it.mkdirs() }
private val updatersDir get() = CLIENT_LIBS_DIR.resolve("updaters").also { it.mkdirs() }
private val updaterWin get() = updatersDir.resolve("updater.exe.zst")
private val updaterWinHash get() = updatersDir.resolve("updater.exe.sha1")
// Legacy client-libs/releases/ is no longer read; keep it only as an archive.
private val uiReleaseStore = UiReleaseStore(CLIENT_LIBS_DIR.resolve("ui"))

private suspend fun RoutingContext.respondUiLibrary(release: UiReleaseStore.Release, name: String) {
    uiReleaseStore.requireLibrary(release, name)
    val entrySize = ZipFile(release.archive).use { zip ->
        val entry = zip.getEntry(name)
        if (entry == null || entry.isDirectory) throw RequestError("无此文件")
        entry.size
    }
    call.respondOutputStream(
        contentType = ContentType.Application.OctetStream,
        contentLength = entrySize.takeIf { it >= 0L }
    ) {
        ZipFile(release.archive).use { zip ->
            val entry = zip.getEntry(name) ?: throw RequestError("无此文件")
            zip.getInputStream(entry).use { input -> input.copyTo(this) }
        }
    }
}

// rdi.sh for Linux/macOS; one script serves every platform for now.
private val nixScript get() = CLIENT_LIBS_DIR.resolve("scripts").resolve("rdi.sh")

private fun requireNixScript(): File {
    val script = nixScript
    if (!script.isFile) throw RequestError("找不到启动脚本")
    return script
}

private suspend fun RoutingContext.scriptPlatform(): String = UiReleaseStore.requireScriptPlatform(param("platform"))

// format=text answers in plain text so rdi.sh needs no JSON parser.
private suspend fun RoutingContext.wantsText(): Boolean = paramNull("format") == "text"

private val mcCoreFilePrefix = "rdi-5-mc-client"

fun Route.updateRoutes() = route("/update") {
    // Windows start.exe routes.
    get("/ui/libs") {
        response(data = uiReleaseStore.loadLatest(UiReleaseStore.WINDOWS_X64).manifest)
    }
    get("/ui/libs/{version}") {
        response(data = uiReleaseStore.load(param("version"), UiReleaseStore.WINDOWS_X64).manifest)
    }
    get("/ui/lib/{version}/{name}") {
        respondUiLibrary(uiReleaseStore.load(param("version"), UiReleaseStore.WINDOWS_X64), param("name"))
    }
    get("/ui/lib/{name}") {
        respondUiLibrary(uiReleaseStore.loadLatest(UiReleaseStore.WINDOWS_X64), param("name"))
    }
    get("/ui/ver"){
        response(data = uiReleaseStore.loadLatest(UiReleaseStore.WINDOWS_X64).version)
    }
    // Linux/macOS rdi.sh routes.
    route("/ui/p/{platform}") {
        get("/ver") {
            val release = uiReleaseStore.loadLatest(scriptPlatform())
            if (wantsText()) call.respondText("${release.version}\n") else response(data = release.version)
        }
        get("/libs/{version}") {
            val release = uiReleaseStore.load(param("version"), scriptPlatform())
            if (wantsText()) {
                call.respondText(UiReleaseStore.manifestText(release.manifest))
            } else {
                response(data = release.manifest)
            }
        }
        get("/lib/{version}/{name}") {
            respondUiLibrary(uiReleaseStore.load(param("version"), scriptPlatform()), param("name"))
        }
    }
    route("/script/{platform}") {
        get {
            scriptPlatform()
            call.respondFile(requireNixScript())
        }
        get("/hash") {
            scriptPlatform()
            call.respondText("${requireNixScript().sha1}\n")
        }
    }
    /*get("/ui/hash") {
        response(data = uiFile.sha1)
    } */
    get("/mc/{ver}/hash"){
        val jarFile = mcCoreModFile()
        response(data = jarFile.sha1)
    }

    get("/mc/{ver}"){
        val jarFile = mcCoreModFile()
        call.respondFile(jarFile)
    }
    route("/updater"){
        get {
            call.respondFile(updaterWin)
        }
        get("/hash"){
            val hash = updaterWinHash.readText().trim()
            if (hash.isBlank()) throw RequestError("updater hash为空")
            response(data = hash)
        }
    }
}



object UpdateService {
    suspend fun RoutingContext.mcCoreModFile(): File {
        var ver = param("ver")
        //rdi 5.1x compatible
        if (ver=="1.19.2-forge" || ver=="1.18.2-forge" || ver=="1.16.5-forge" || ver=="1.12.2-cleanroom") ver = "1.20.1-forge"
        val jarFile = CLIENT_LIBS_DIR.resolve("$mcCoreFilePrefix-$ver.jar")
        if (!jarFile.exists()) throw RequestError("无此版本的MC核心库")
        return jarFile
    }
}
