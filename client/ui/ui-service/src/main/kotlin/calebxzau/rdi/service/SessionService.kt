package calebxzau.rdi.service

import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.json.*

/** Decode errors are sanitized: serializers can include the original secret-bearing input. */
internal class SessionService(private val assign: (RAccount) -> Unit) {
    fun set(params: JsonObject): Result<JsonObject> = runCatching {
        val account = try {
            val jwt = params["jwt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("登录信息无效")
            serdesJson.decodeFromJsonElement<RAccount>(params["account"] ?: error("登录信息无效"))
                .also { it.pwd = ""; it.jwt = jwt }
        } catch (_: Exception) {
            // Deliberately omit the original cause, which may contain credentials.
            throw IllegalArgumentException("登录信息无效")
        }
        assign(account)
        buildJsonObject { put("uid", account._id.toHexString()) }
    }
}
