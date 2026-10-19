package calebxzhou.rdi.client.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginInfo(
    val qq: String,
    val name: String? = "RDI玩家",
    val pwd: String,
    var lastLoggedTime: Long = 0L,
)
