package calebxzhou.rdi.common.model

import calebxzau.rdi.common.model.Content
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.util.UUID

@Serializable
data class ModpackUploadSessionCreateDto(
    val fileName: String,
    val size: Long,
    val sha1: String
)

@Serializable
data class ModpackUploadSessionVo(
    @Contextual val id: UUID,
    val fileName: String,
    val size: Long,
    val sha1: String,
    val partSize: Int,
    val partCount: Int,
    val uploadedParts: List<Int>,
    val ready: Boolean,
    val expiresAt: Long,
    val maxParallelParts: Int = 8
)

@Serializable
data class ModpackCreateFromUploadDto(
    @Contextual val uploadId: UUID,
    val modpack: Modpack.CreateWithVersionDto
)

@Serializable
data class ModpackVersionCreateFromUploadDto(
    @Contextual val uploadId: UUID,
    val mods: MutableList<Mod>,
    val clientExtras: MutableList<Content> = arrayListOf(),
)

/** Metadata-only validation request sent before creating an upload task. */
@Serializable
data class ModpackUploadPreflightDto(
    @Contextual val modpackId: ObjectId? = null,
    val name: String,
    val verName: String,
    val mcVer: McVersion,
    val modLoader: ModLoader,
    val iconUrl: String? = null,
    val sourceUrl: String? = null,
    val info: String? = null,
    val categories: List<Modpack.Category> = emptyList(),
)
