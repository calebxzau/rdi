package calebxzau.rdi.mc.client.dm

import com.google.common.net.HostAndPort

data class DmEndpoint private constructor(
    val host: String,
    val port: Int,
    val displayHost: String,
) {
    companion object {
        fun parse(value: String): Result<DmEndpoint> = runCatching {
            val text = value.trim()
            require(text.isNotEmpty()) { "网关地址不能为空" }
            val parsed = HostAndPort.fromString(text)
            require(parsed.hasPort()) { "网关地址必须包含端口" }
            val port = parsed.port
            require(port in 1..65535) { "网关端口必须在1到65535之间" }
            val host = parsed.host
            require(host.isNotEmpty()) { "网关主机不能为空" }
            val bracketed = text.startsWith("[")
            if (host.contains(':') && !bracketed) {
                require(false) { "IPv6网关地址必须使用方括号" }
            }
            DmEndpoint(host, port, if (host.contains(':')) "[${host}]" else host)
        }
    }

    fun gameAddress(gamePort: Int): String {
        require(gamePort in 1..65535)
        return "${displayHost}:${gamePort}"
    }
}
