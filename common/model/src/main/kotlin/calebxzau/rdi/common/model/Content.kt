package calebxzau.rdi.common.model

import kotlinx.serialization.Serializable
import java.net.URI

/** The layer that owns a Host2 content entry. */
@Serializable
enum class ContentOrigin {
    Pack,
    Extra,
}

/** Stable identity used by Host2 content mutation requests. */
@Serializable
data class ContentKey(
    val origin: ContentOrigin,
    val platform: ContentPlatform,
    val projectId: String,
)

/** Complete content snapshot entry returned by Host2 management APIs. */
@Serializable
data class ContentVo(
    val origin: ContentOrigin,
    val platform: ContentPlatform,
    val type: ContentType,
    val projectId: String,
    val fileId: String,
    val slug: String,
    val hash: String,
    val targetPath: String? = null,
    val side: ContentSide,
    val required: Boolean = true,
    val fileSize: Long,
    val enabled: Boolean = true,
)

/** Fields accepted when an Extra Content is added to a Host2. */
@Serializable
data class ContentInput(
    val platform: ContentPlatform,
    val type: ContentType,
    val projectId: String,
    val fileId: String,
    val side: ContentSide,
    val targetPath: String? = null,
)

/** Effective client-side entry; enabled is implicit because disabled entries are omitted. */
@Serializable
data class ClientContentVo(
    val origin: ContentOrigin,
    val platform: ContentPlatform,
    val type: ContentType,
    val projectId: String,
    val fileId: String,
    val slug: String,
    val hash: String,
    val targetPath: String? = null,
    val side: ContentSide,
    val required: Boolean = true,
    val fileSize: Long,
)

@Serializable
data class Content(
    val platform: ContentPlatform,
    val type: ContentType,
    val projectId: String,
    val fileId: String,
    val slug: String,
    val hash: String,
    // For mrpack, null means the platform default path.
    val path: String? = null,
    val side: ContentSide,
    val required: Boolean = true,
    val downloadUrls: List<String> = emptyList(),
){
    val defaultFileName: String
        get() {
            val extension = when (type) {
                ContentType.Mod -> "jar"
                ContentType.ResPack, ContentType.ShaderPack -> "zip"
                else -> error("此内容类型必须指定安装路径")
            }
            val platformCode = when (platform) {
                ContentPlatform.CurseForge -> "cf"
                ContentPlatform.Modrinth -> "mr"
                ContentPlatform.GitHub -> "github"
            }
            return "${slug}_${platformCode}_${hash}.${extension}"
        }

    val targetRelativePath: String
        get() {
            path?.let { return it }

            val directory = when (type) {
                ContentType.Mod -> "mods"
                ContentType.ResPack -> "resourcepacks"
                ContentType.ShaderPack -> "shaderpacks"
                else -> error("此内容类型必须指定安装路径")
            }
            return "${directory}/${defaultFileName}"
        }
}

fun normalizeClientExtraPath(rawPath: String): String {
    require(rawPath.isNotEmpty()) { "客户端额外内容路径不能为空" }
    require(!rawPath.startsWith('/') && !rawPath.startsWith('\\')) { "客户端额外内容路径不能是绝对路径" }
    require(!rawPath.matches(Regex("^[A-Za-z]:.*")) && !rawPath.startsWith("\\\\")) {
        "客户端额外内容路径不能是Windows绝对路径"
    }
    require('\u0000' !in rawPath && ':' !in rawPath) { "客户端额外内容路径包含非法字符" }
    val segments = rawPath.replace('\\', '/').split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) { "客户端额外内容路径无效: $rawPath" }
    val normalized = segments.joinToString("/")
    require(normalized.startsWith("resourcepacks/") || normalized.startsWith("shaderpacks/")) {
        "客户端额外内容必须位于resourcepacks或shaderpacks: $rawPath"
    }
    return normalized
}

fun Content.validateClientExtra(): Content {
    require(type == ContentType.ResPack || type == ContentType.ShaderPack) { "客户端额外内容类型无效" }
    val normalizedPath = normalizeClientExtraPath(targetRelativePath)
    val expectedRoot = if (type == ContentType.ResPack) "resourcepacks/" else "shaderpacks/"
    require(normalizedPath.startsWith(expectedRoot)) { "客户端额外内容类型与安装路径不匹配: $normalizedPath" }
    require(projectId.isNotBlank() && fileId.isNotBlank() && slug.isNotBlank()) { "客户端额外内容缺少平台身份" }
    val normalizedHash = hash.trim()
    when (platform) {
        ContentPlatform.Modrinth -> require(normalizedHash.matches(Regex("[0-9a-fA-F]{40}"))) { "Modrinth客户端额外内容SHA-1无效" }
        ContentPlatform.CurseForge -> require(normalizedHash.toULongOrNull()?.let { it <= UInt.MAX_VALUE.toULong() } == true) { "CurseForge客户端额外内容指纹无效" }
        ContentPlatform.GitHub -> require(normalizedHash.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) { "GitHub客户端额外内容摘要无效" }
    }
    downloadUrls.filter(String::isNotBlank).forEach { url ->
        val parsed = runCatching { URI(url.trim()) }.getOrNull()
        require(
            (parsed?.scheme.equals("http", true) || parsed?.scheme.equals("https", true)) &&
                parsed?.isOpaque == false && !parsed?.host.isNullOrBlank()
        ) { "客户端额外内容下载地址无效: $url" }
    }
    return copy(path = normalizedPath, downloadUrls = downloadUrls.map(String::trim).filter(String::isNotBlank).distinct())
}

fun List<Content>.validateAndMergeClientExtras(): List<Content> {
    val merged = linkedMapOf<String, Content>()
    for (raw in this) {
        val content = raw.validateClientExtra()
        val key = content.targetRelativePath.lowercase()
        val previous = merged[key]
        if (previous == null) {
            merged[key] = content
        } else {
            require(previous.platform == content.platform && previous.projectId == content.projectId &&
                previous.fileId == content.fileId && previous.hash.equals(content.hash, true)) {
                "客户端额外内容目标路径冲突: ${content.targetRelativePath}"
            }
            val mergedSide = when {
                previous.side == content.side -> previous.side
                previous.side == ContentSide.Both || content.side == ContentSide.Both -> ContentSide.Both
                else -> ContentSide.Both
            }
            merged[key] = previous.copy(
                side = mergedSide,
                required = previous.required || content.required,
                downloadUrls = (previous.downloadUrls + content.downloadUrls).distinct(),
            )
        }
    }
    return merged.values.toList()
}

@Serializable
enum class ContentPlatform {
    CurseForge,
    Modrinth,
    GitHub,
}

@Serializable
enum class ContentSide {
    Client,
    Server,
    Both,
}

@Serializable
enum class ContentType {
    Mod,
    ShaderPack,
    ResPack,
    DataPack,
    Other
}
