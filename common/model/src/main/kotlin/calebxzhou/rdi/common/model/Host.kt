package calebxzhou.rdi.common.model

import calebxzhou.rdi.model.Role
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.util.UUID
val HOST_ALLOW_FILE_EXT = setOf("txt","js","json","json5","jsonc","md","ini","toml","yaml","yml","cfg","zs","properties","snbt","mcmeta","bak","lang","lua","mcfunction","xml")
val HOST_OPR_DIR = mapOf(
    "config" to "配置",
    "datapacks" to "数据包",
    "tacz" to "TaCZ",
    "kubejs" to "KJS",
    "scripts" to "CrT",
)
@Serializable
data class Host(
    @Contextual
    val _id: ObjectId = ObjectId(),
    val name: String,
    val intro: String? = null,
    @Contextual
    val ownerId: ObjectId,
    @Contextual
    val modpackId: ObjectId,
    //版本可能会重新发布 此时id会变 所以不用packVerId
    var packVer: String = "latest",
    @Contextual
    val worldId: ObjectId? = null,
    var port: Int,
    val difficulty: Int,
    val gameMode: Int,
    val levelType: String,
    val gameRules: MutableMap<String, String> = mutableMapOf(),
    //白名单 只有成员才能进
    val whitelist: Boolean = false,
    //允许作弊（全op）
    val allowCheats: Boolean = false,
    val members: List<Member> = arrayListOf(),
    val banlist: List<@Contextual ObjectId> = arrayListOf(),
    //整合包外的附加mod
    var extraMods: List<Mod> = arrayListOf(),
    var disabledMods: List<Mod> = arrayListOf(),
    private var version: Int? = null,
    @Contextual
    val baseWorldId: UUID? = null,
    val generatorSettings: String? = null,
) {
    val realVersion get() = version?:1
    companion object {
        var portNow: Int = 0
        fun getGameModeText(modeId: Int): String {
            return when (modeId) {
                0 -> "survival"
                1 -> "creative"
                2 -> "adventure"
                else -> "survival"
            }
        }

        fun getDifficultyText(diffId: Int): String {
            return when (diffId) {
                0 -> "peaceful"
                1 -> "easy"
                2 -> "normal"
                3 -> "hard"
                else -> "normal"
            }
        }

    }
    @Serializable
    data class Member(
        @Contextual
        val id: ObjectId,
        val role: Role
    )

    @Serializable
    data class BriefVo(
        @Contextual
        val _id: ObjectId = ObjectId(),
        val name: String,
        val intro: String? = null,
        val iconUrl: String? = null,
        @Contextual
        val ownerId: ObjectId = ObjectId(),
        val modpackName: String,
        val packVer: String,
        val version: Int,
        var port: Int,
        val playable: Boolean = true,
        val isMember: Boolean = false,
        val role: Role? = null,
        val onlinePlayerIds: List<@Contextual ObjectId> = arrayListOf(),
        /** Real run state, unlike [playable] which is also true for members of a stopped room. */
        val status: HostStatus = HostStatus.UNKNOWN,
    ) {
        companion object {
            val TEST = BriefVo(
                name = "啊实打实大苏打实打实的",
                intro = "都是大大实打实大苏打实打实的得分风格风格风格非官方",
                modpackName = "测试测试测试测试测试",
                packVer = "1.0.0",
                version = 2,
                port = 55555,
                onlinePlayerIds = arrayListOf(
                    ObjectId(), ObjectId(), ObjectId(), ObjectId(), ObjectId(), ObjectId(), ObjectId(), ObjectId(),
                )
            )
        }
    }

    @Serializable
    data class DetailVo(
        @Contextual
        val _id: ObjectId = ObjectId(),
        val name: String,
        val intro: String? = null,
        val iconUrl: String? = null,
        @Contextual
        val ownerId: ObjectId = ObjectId(),
        val modpack: Modpack.BriefVo,
        val packVer: String,
        val version: Int,
        @Contextual
        val worldId: ObjectId? = null,
        var port: Int,
        val difficulty: Int,
        val gameMode: Int,
        val levelType: String,
        val gameRules: MutableMap<String, String> = mutableMapOf(),
        val whitelist: Boolean = false,
        val allowCheats: Boolean = false,
        val members: List<Member> = arrayListOf(),
        val extraMods: List<Mod> = arrayListOf(),
        val disabledMods: List<Mod> = arrayListOf(),
        val onlinePlayerIds: List<@Contextual ObjectId> = arrayListOf(),
    )

    @Serializable
    data class CreateDto(
        val name: String,
        @Contextual
        val modpackId: ObjectId,
        val packVer: String,
        val difficulty: Int,
        val gameMode: Int,
        val levelType: String,
        val allowCheats: Boolean,
        val whitelist: Boolean,
        val gameRules: MutableMap<String, String>,
        @Contextual
        val baseWorldId: UUID? = null,
    )

    @Serializable
    data class OptionsDto(
        val name: String? = null,
        @Contextual
        val modpackId: ObjectId? = null,
        val packVer: String? = null,
        val difficulty: Int? = null,
        val gameMode: Int? = null,
        val levelType: String? = null,
        val whitelist: Boolean? = null,
        val allowCheats: Boolean? = null,
        val gameRules: Map<String, String>? = null
    )

    @Serializable
    data class ConfigFileEntry(
        val path: String,
        val size: Long,
        val updateTime: Long
    )

    @Serializable
    data class ConfigFileContentVo(
        val path: String,
        val content: String,
        val size: Long,
        val updateTime: Long
    )

    @Serializable
    data class ConfigFileSaveDto(
        val path: String,
        val content: String
    )

    @Serializable
    data class FileEntry(
        val path: String,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val updateTime: Long
    )

    @Serializable
    data class FileDeleteDto(
        val path: String
    )

    @Serializable
    data class FileReadDto(
        val path: String
    )

    @Serializable
    data class FileContentVo(
        val path: String,
        val content: String,
        val size: Long,
        val updateTime: Long
    )

    @Serializable
    data class FileCreateDto(
        val path: String,
        val directory: Boolean = false,
        val content: String = ""
    )

    @Serializable
    data class FileWriteDto(
        val path: String,
        val content: String
    )

    @Serializable
    data class FileRenameDto(
        val from: String,
        val to: String
    )

    @Serializable
    data class FileUploadVo(
        val path: String,
        val size: Long,
        val updateTime: Long
    )

    @Serializable
    data class DeleteDto(
        val deleteWorld: Boolean = false
    )

}

/**
 * calebxzhou @ 2025-10-06 19:38
 */
fun Host.hasMember(id: ObjectId): Boolean {
    return members.any { it.id == id }
}
val Host.owner
    get() =  members.find { it.role== Role.OWNER }
fun Host.isOwner(acc: RAccount) = owner?.id == acc._id
// Check whether a user is OWNER or ADMIN of this team
fun Host.isAdmin(acc: RAccount): Boolean = isAdmin(acc._id)
fun Host.isAdmin(uid: ObjectId): Boolean =
    members.any { it.id == uid && (it.role == Role.OWNER || it.role == Role.ADMIN) }
fun Host.DetailVo.isAdmin(acc: RAccount): Boolean = isAdmin(acc._id)
fun Host.DetailVo.isAdmin(uid: ObjectId): Boolean =
    this.ownerId==uid||members.any { it.id == uid && (it.role == Role.OWNER || it.role == Role.ADMIN) }
