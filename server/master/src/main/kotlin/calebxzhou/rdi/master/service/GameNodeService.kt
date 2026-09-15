package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.model.ServerEntry
import calebxzhou.rdi.master.CONF
import calebxzhou.rdi.master.GameNodeRuleConfig
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.clientIp
import calebxzhou.rdi.master.net.param
import calebxzhou.rdi.master.net.paramNull
import calebxzhou.rdi.master.net.response
import io.ktor.server.routing.*
import java.time.LocalTime
import java.time.ZoneId

private val ipv4Regex =
    Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

fun Route.gameNodeRoutes() {
    get("/server-entry") {
        response(
            data = GameNodeService.resolveServerEntry(
                ipv4 = call.paramNull("myIp")?:call.clientIp,
                gameBackup = call.paramNull("gameBackup") == "true",
                call.paramNull("forceMain") == "true",
            )
        )
    }
}

object GameNodeService {
    fun isProxyIpAllowed(ip: String): Boolean =
        ProxyAccessPolicy.isAllowed(ip, CONF.gameNode.nodes.map { it.gameAddr })

    fun selectBackendHost(clientIp: String): String =
        BackendHostPolicy.select(clientIp, CONF.server.gameHost, CONF.server.remoteGameHost)

    fun resolveServerEntry(ipv4: String, gameBackup: Boolean = false, forceMain: Boolean = false): ServerEntry {
        requireIpv4(ipv4)
        val region = CarrierDetectService.detectResult(ipv4)
        val node = selectNode(region, gameBackup, forceMain)
        val backupApi = CONF.server.bgpUrl.trim()
        val useBackupNode = backupApi.isNotBlank() && !region.isCT && isPeekHour()
        return ServerEntry(
            api = backupApi.takeIf { useBackupNode },
            useBackupNode = useBackupNode,
            nodeName = node.name + "-" + region.province.substring(0..1) + region.carrierName,
            gameAddr = node.gameAddr
        )
    }

    private fun isPeekHour(): Boolean {
        val now = LocalTime.now(BEIJING_ZONE_ID)
        return now.hour in 18..23
    }

    private fun requireIpv4(ipv4: String) {
        if (!ipv4Regex.matches(ipv4)) {
            throw ParamError("myIp格式错误")
        }
    }

    private fun selectNode(region: Ip2RegionResult, gameBackup: Boolean, forceMain: Boolean): GameNodeRuleConfig {
        val nodes = CONF.gameNode.nodes
        if (forceMain) {
            return nodes.firstOrNull { it.id == FALLBACK_GAME_NODE_ID } ?: throw ParamError("未配置主节点")
        }
        if (gameBackup) {
            nodes.firstOrNull { it.gameBackup }?.let { return it }
        }
        return nodes.firstOrNull { it.matches(region) }
        // `id=0`是显式声明的国内兜底节点，不再依赖配置顺序里的“第一个节点”。
            ?: nodes.firstOrNull { it.id == FALLBACK_GAME_NODE_ID }
            ?: throw ParamError("未配置主节点")
    }

    private fun GameNodeRuleConfig.matches(region: Ip2RegionResult): Boolean {
        if (peekHourOnly && !isPeekHour()) {
            return false
        }
        // `matchOutsideChina`是兜底的国际节点规则：
        // 只要IP不在中国，或者命中了港澳台，就直接匹配。
        if (matchOutsideChina && (!region.isChina || region.province in listOf("香港", "澳门", "台湾"))) {
            return true
        }
        // 运营商名称可能同时出现“电信/中国电信”这两种写法，
        // 所以这里先做一次归一化，再比较，避免只修单向前缀导致配置和region写法不一致时漏匹配。
        if (carriers.isNotEmpty() && carriers.none { region.carrierName == it }) {
            return false
        }
        // 如果规则声明了省份，则当前IP所在省份必须命中其中一个省份，否则排除。
        // 这里用`contains`而不是全等，是为了兼容诸如“广西壮族自治区”这类更长的region文本。
        if (provinces.isNotEmpty() && provinces.none { region.province.contains(it) }) {
            return false
        }
        // 走到这里说明已通过前面的carrier/province过滤。
        // 但完全没写任何匹配条件的节点不应该在这里被视为命中，
        // 它会作为`selectNode()`里的最后fallback节点使用。
        return carriers.isNotEmpty() || provinces.isNotEmpty()
    }

}

object ProxyAccessPolicy {
    fun isAllowed(ip: String, gameAddresses: Iterable<String>): Boolean =
        ip == "127.0.0.1" || gameAddresses.any { it.substringBeforeLast(':') == ip }
}

object BackendHostPolicy {
    fun select(clientIp: String, gameHost: String, remoteGameHost: String?): String =
        if (clientIp == "127.0.0.1") {
            gameHost
        } else {
            remoteGameHost?.trim()?.takeIf { it.isNotEmpty() } ?: gameHost
        }
}

private const val FALLBACK_GAME_NODE_ID = 0
private val BEIJING_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
