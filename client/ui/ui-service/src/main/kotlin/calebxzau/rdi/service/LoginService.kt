package calebxzau.rdi.service

import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzau.rdi.client.logic.LoginController
import calebxzau.rdi.client.logic.LoginGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Only this service reads stored passwords; public snapshots contain metadata only. */
internal class LoginService(
    private val readCredentials: () -> LocalCredentials,
    gateway: LoginGateway,
    private val currentUid: () -> String,
    private val version: String = "dev",
) {
    private val controller = LoginController(gateway)

    suspend fun accounts(): JsonObject = withContext(Dispatchers.IO) {
        val credentials = readCredentials()
        val entries = credentials.loginInfos.entries.sortedByDescending { it.value.lastLoggedTime }
        buildJsonObject {
            put("version", version)
            put("accounts", buildJsonArray {
                entries.forEach { (id, info) -> add(buildJsonObject {
                    put("id", id)
                    put("account", info.qq)
                    put("name", info.name ?: "RDI玩家")
                    put("lastLoggedTime", info.lastLoggedTime)
                }) }
            })
            entries.firstOrNull()?.let { put("lastId", it.key) }
            if (!credentials.autoLoginDisabled) entries.firstOrNull()?.let { put("autoLoginId", it.key) }
        }
    }

    suspend fun login(params: JsonObject, saved: Boolean): JsonObject {
        val (account, password) = if (saved) {
            val id = params["id"]?.jsonPrimitive?.content.orEmpty()
            val info = withContext(Dispatchers.IO) { readCredentials().loginInfos[id] }
                ?: error("历史账号已不存在，请重新输入账号和密码")
            info.qq to info.pwd
        } else {
            params["usr"]?.jsonPrimitive?.content.orEmpty() to params["pwd"]?.jsonPrimitive?.content.orEmpty()
        }
        controller.login(account, password).getOrThrow()
        return buildJsonObject { put("loggedIn", true); put("uid", currentUid()) }
    }
}
