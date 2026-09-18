package calebxzau.rdi.mc.client.dm

import java.net.URI

data class DmConfig(val baseUri: URI) {
    companion object {
        fun fromSystemProperty(value: String? = System.getProperty("rdi.dm.server")): Result<DmConfig?> = runCatching {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return@runCatching null
            val uri = URI(text)
            require(uri.scheme == "http" || uri.scheme == "https") { "DM服务器地址必须使用http或https" }
            require(uri.userInfo == null) { "DM服务器地址不能包含用户信息" }
            require(!uri.host.isNullOrBlank()) { "DM服务器地址缺少主机名" }
            require(uri.query == null && uri.fragment == null) { "DM服务器地址不能包含查询参数或片段" }
            val path = uri.path.ifEmpty { "/" }.let { if (it.endsWith('/')) it else "$it/" }
            DmConfig(uri.resolve(path))
        }
    }
}
