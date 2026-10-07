package calebxzau.rdi.common.model

import calebxzhou.rdi.common.model.ModLoader
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.util.UUID

/** Side-effect-free checks before a singleplayer save is uploaded into a host. */
@Serializable
data class HostWorldImportPrecheckDto(
    /** `Data.Version.Name` of the save, e.g. `1.20.1`. */
    val mcVersionName: String,
    /** The loader that saved the save last (from `fml.LoadingModList`). */
    val loader: ModLoader,
    /** Accounts that will become members: mapped players and existing RDI players of the save. */
    val memberPlayerIds: List<@Contextual ObjectId> = emptyList(),
)

@Serializable
data class HostWorldImportPrecheckVo(
    val memberWarnings: List<HostWorldImportMemberWarning>,
    /** Game rules set on the host that the import clears in favour of the save's own. */
    val gameRuleOverrideCount: Int,
)

@Serializable
data class HostWorldImportCreateDto(
    val size: Long,
    val sha1: String,
    val mcVersionName: String,
    val loader: ModLoader,
    val memberPlayerIds: List<@Contextual ObjectId> = emptyList(),
)

/** A player who cannot be added as a member; the import still succeeds without them. */
@Serializable
data class HostWorldImportMemberWarning(
    @Contextual
    val playerId: ObjectId,
    val reason: String,
)

@Serializable
enum class HostWorldImportStatus {
    Uploading,
    Queued,
    Processing,
    Ready,
    Failed,
}

@Serializable
data class HostWorldImportSessionVo(
    @Contextual
    val id: UUID,
    val size: Long,
    val partSize: Int,
    val partCount: Int,
    val uploadedParts: List<Int>,
    val expiresAt: Long,
    val status: HostWorldImportStatus = HostWorldImportStatus.Uploading,
    val errorMessage: String? = null,
    val memberWarnings: List<HostWorldImportMemberWarning> = emptyList(),
)

/** A save player UUID that is the Microsoft profile bound to an RDI account. */
@Serializable
data class SavePlayerMsidMatchVo(
    @Contextual
    val msid: UUID,
    @Contextual
    val id: ObjectId,
    val name: String,
)
