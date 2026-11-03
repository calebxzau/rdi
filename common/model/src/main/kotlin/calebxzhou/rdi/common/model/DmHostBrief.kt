package calebxzhou.rdi.common.model

import kotlinx.serialization.Serializable

/** Whether a room on dm-master is being hosted right now. */
@Serializable
enum class DmHostState {
    Online,
    Offline,
    /** dm-master cannot tell at the moment. */
    Unavailable,
    /** The host is saving its final checkpoint before going offline. */
    Leaving,
}

/** One row of a player's room list on dm-master. */
@Serializable
data class DmHostBrief(
    val id: String,
    val name: String,
    val state: DmHostState,
)
