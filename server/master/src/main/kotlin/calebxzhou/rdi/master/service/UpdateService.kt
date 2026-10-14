package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.master.net.param
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
private val uiReleasesDir get() = CLIENT_LIBS_DIR.resolve("releases")
private val uiVersionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
private val sha1Pattern = Regex("^[0-9a-fA-F]{40}$")

private data class UiRelease(
    val version: String,
    val archive: File,
    val manifest: Map<String, String>,
)

private fun validateUiVersion(rawVersion: String): String {
    val version = rawVersion.trim()
    if (!uiVersionPattern.matches(version)) throw RequestError("非法UI版本")
    return version
}

private fun validateUiManifest(manifest: Map<String, String>): Map<String, String> {
    if (manifest.isEmpty() || manifest.keys.none { it.endsWith(".jar", ignoreCase = true) }) {
        throw RequestError("UI库manifest为空或缺少JAR")
    }
    if (manifest.any { (name, hash) ->
            name.isBlank() || name == "." || name == ".." ||
                name.contains('/') || name.contains('\\') || name.contains(':') ||
                !sha1Pattern.matches(hash)
        }) {
        throw RequestError("UI库manifest包含非法文件")
    }
    return manifest
}

private fun loadUiRelease(rawVersion: String): UiRelease {
    val version = validateUiVersion(rawVersion)
    val archive = uiReleasesDir.resolve("$version.zip")
    val manifestFile = uiReleasesDir.resolve("$version.json")
    if (!archive.isFile) throw RequestError("找不到UI版本ZIP: $version")
    if (!manifestFile.isFile) throw RequestError("找不到UI版本manifest: $version")
    val manifest = runCatching {
        serdesJson.decodeFromString<Map<String, String>>(manifestFile.readText())
    }.getOrElse { throw RequestError("UI版本manifest格式错误: $version") }
    return UiRelease(version, archive, validateUiManifest(manifest))
}

private fun loadLatestUiRelease(): UiRelease {
    val latestFile = uiReleasesDir.resolve("latest.txt")
    if (!latestFile.isFile) throw RequestError("找不到UI最新版本")
    return loadUiRelease(latestFile.readText())
}

private suspend fun RoutingContext.respondUiLibrary(release: UiRelease, name: String) {
    if (name.isBlank() || name == "." || name == ".." ||
        name.contains('/') || name.contains('\\') || name.contains(':')
    ) {
        throw RequestError("非法文件名")
    }
    if (name !in release.manifest) throw RequestError("无此文件")
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

private val mcCoreFilePrefix = "rdi-5-mc-client"

fun Route.updateRoutes() = route("/update") {
    get("/ui/libs") {
        response(data = loadLatestUiRelease().manifest)
    }
    get("/ui/libs/{version}") {
        response(data = loadUiRelease(param("version")).manifest)
    }
    get("/ui/lib/{version}/{name}") {
        respondUiLibrary(loadUiRelease(param("version")), param("name"))
    }
    get("/ui/lib/{name}") {
        respondUiLibrary(loadLatestUiRelease(), param("name"))
    }
    get("/ui/ver"){
        response(data = loadLatestUiRelease().version)
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
