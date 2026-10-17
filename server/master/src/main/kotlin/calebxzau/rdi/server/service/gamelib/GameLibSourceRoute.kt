package calebxzau.rdi.server.service.gamelib

import calebxzhou.rdi.common.net.GameLibMaven
import calebxzhou.rdi.master.CONF
import calebxzhou.rdi.master.GAME_LIBS_DIR
import calebxzhou.rdi.master.service.DownloadQuotaService
import calebxzhou.rdi.master.service.player
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

private val gameLibSourceService by lazy {
    GameLibSourceService(
        cacheDir = GAME_LIBS_DIR.resolve("maven-cache"),
        installedLibsRoot = GAME_LIBS_DIR,
        useMirror = { CONF.download.useMirror },
    )
}

/** `GET /game-lib/maven/{path...}?sha1=`, the launcher's last-resort library source. Needs login. */
fun Route.gameLibSourceRoutes() = get("${GameLibMaven.ROUTE}/{path...}") {
    val player = call.player()
    val path = call.parameters.getAll("path").orEmpty().joinToString("/")
    val file = gameLibSourceService.resolve(path, call.request.queryParameters[GameLibMaven.SHA1_PARAM]).getOrThrow()
    DownloadQuotaService.reserve(player._id, file.length())
    call.respondFile(file)
}
