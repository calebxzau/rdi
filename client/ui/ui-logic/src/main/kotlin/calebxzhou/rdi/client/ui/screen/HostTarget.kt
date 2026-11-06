package calebxzhou.rdi.client.ui.screen

import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.model.Role
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** The two server contracts behind the single player-facing room UI. */
@Serializable
enum class HostKind {
    Legacy,
    Host2;

    companion object {
        /** Parses the String representation used by Navigation type-safe routes. */
        fun fromRouteValue(value: String?): HostKind =
            entries.firstOrNull { it.name == value } ?: Legacy
    }
}

/** Stable route identity. The id is kept as text so ObjectId and UUID never get mixed. */
@Serializable
data class HostTarget(
    val kind: HostKind = HostKind.Legacy,
    val id: String,
) {
    val apiRoot: String get() = "host"

    fun objectIdOrNull(): ObjectId? =
        if (kind == HostKind.Legacy) runCatching { ObjectId(id) }.getOrNull() else null

    companion object {
        fun legacy(id: ObjectId) = HostTarget(HostKind.Legacy, id.toHexString())
        fun parse(kind: HostKind, id: String): HostTarget? = when (kind) {
            HostKind.Legacy -> ObjectId.isValid(id).takeIf { it }?.let { HostTarget(kind, id) }
            HostKind.Host2 -> null
        }
    }
}

/** UI-only projection used by the combined room grid. */
data class UnifiedHostBrief(
    val target: HostTarget,
    val name: String,
    val intro: String?,
    val iconUrl: String?,
    val ownerId: ObjectId?,
    val onlinePlayerIds: List<ObjectId>,
    val modpackName: String,
    val packVersion: String,
    val playable: Boolean,
    val isMember: Boolean,
    val role: Role?,
    val status: HostStatus,
    val version: Int = 1,
) {
    val canUseMemberFeatures: Boolean get() = isMember || role in setOf(Role.OWNER, Role.ADMIN)
    val canManage: Boolean get() = role in setOf(Role.OWNER, Role.ADMIN)

    companion object {
        fun fromLegacy(host: calebxzhou.rdi.common.model.Host.BriefVo): UnifiedHostBrief =
            UnifiedHostBrief(
                target = HostTarget.legacy(host._id),
                name = host.name,
                intro = host.intro,
                iconUrl = host.iconUrl,
                ownerId = host.ownerId,
                onlinePlayerIds = host.onlinePlayerIds,
                modpackName = host.modpackName,
                packVersion = host.packVer,
                playable = host.playable,
                isMember = host.isMember,
                role = host.role,
                status = host.status,
                version = host.version,
            )

    }
}

fun HostTarget.legacyObjectIdOrNull(): ObjectId? = objectIdOrNull()
