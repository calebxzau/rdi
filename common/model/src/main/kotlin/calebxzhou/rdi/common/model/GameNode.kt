package calebxzhou.rdi.common.model

import kotlinx.serialization.Serializable

@Serializable
data class GameNodeEntry(val name: String, val gameAddr: String)

@Serializable
data class ServerEntry(
    val api: String? = null,
    val useBackupNode: Boolean = false,
    val nodeName: String,
    val gameAddr: String,
    val candidates: List<GameNodeEntry> = emptyList(),
    val sessionResume: Boolean = false,
)
