package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.common.model.ServerEntry
import calebxzhou.rdi.common.net.httpRequest
import calebxzhou.rdi.common.util.ok
import calebxzau.rdi.client.lgr
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ipv4Regex =
    Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")


internal suspend fun refreshNodeSettings(
    gameBackup: Boolean = false,
    forceMain: Boolean = false,
): Result<ServerEntry> = runCatching {
    val ipv4 = runCatching { detectPublicIpv4() }.getOrElse {
        lgr.warn(it) { "无法读取ip信息" }
        return Result.failure(it)
    }
    val routeLookupServer = if (DEBUG) RServer.DBG else RServer.OFFICIAL_NNG

    val params = buildMap<String, Any> {
        put("myIp", ipv4)
        if (gameBackup) put("gameBackup", true)
        if (forceMain) put("forceMain", true)
    }
    val entry = runCatching {
        routeLookupServer.makeRequest<ServerEntry>("server-entry", params = params).data
    }.getOrElse {
        lgr.warn(it) { "获取游戏节点失败，继续使用本地回退节点 " }
        return Result.failure(it)
    } ?: return Result.failure(IllegalStateException("服务端未返回节点信息"))
    RServer.updateServerEntry(entry)
    val routeState = RServer.routeState.value
    lgr.info { "服务端为当前IP选择节点${routeState.nodeName ?: entry.nodeName} -> ${RServer.currentGameAddr}，backup=${routeState.useBackupNode}" }
    return ok(entry)
}

//frp节点不会显示原ip
private suspend fun detectPublicIpv4() = withContext(Dispatchers.IO) {
    val ipv4 = httpRequest {
        url("https://ip.3322.net/")
        method = HttpMethod.Get
    }.bodyAsText().trim()
    require(ipv4Regex.matches(ipv4)) { "无效IPv4: $ipv4" }
    ipv4
}
