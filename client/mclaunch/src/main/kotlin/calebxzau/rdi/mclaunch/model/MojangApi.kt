package calebxzau.rdi.mclaunch.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Data classes used by the Minecraft launcher metadata and loader manifests. */
@Serializable
data class MojangVersionManifest(
    val id: String,
    val type: String? = null,
    val time: String? = null,
    val releaseTime: String? = null,
    val mainClass: String? = null,
    val clientVersion: String? = null,
    val downloads: MojangVersionDownloads? = null,
    val assetIndex: MojangAssetIndex? = null,
    val assets: String? = null,
    val complianceLevel: Int? = null,
    val libraries: List<MojangLibrary> = emptyList(),
    val logging: Map<String, MojangLoggingConfig>? = null,
    val minimumLauncherVersion: Int? = null,
    val arguments: MojangArguments = MojangArguments(),
    val minecraftArguments: String? = null,
    val inheritsFrom: String? = null,
    val jar: String? = null,
    val javaVersion: MojangJavaVersion? = null,
)

@Serializable
data class MojangArguments(
    val game: List<JsonElement> = emptyList(),
    val jvm: List<JsonElement> = emptyList(),
)

@Serializable
data class MojangVersionDownloads(
    val client: MojangDownloadArtifact? = null,
    @SerialName("client_mappings") val clientMappings: MojangDownloadArtifact? = null,
    val server: MojangDownloadArtifact? = null,
    @SerialName("server_mappings") val serverMappings: MojangDownloadArtifact? = null,
)

@Serializable
data class MojangDownloadArtifact(
    val sha1: String = "",
    val size: Long = 0,
    val url: String = "",
    val path: String? = null,
)

@Serializable
data class MojangAssetIndex(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long? = null,
    val url: String,
)

@Serializable
data class MojangAssetIndexFile(
    val objects: Map<String, MojangAssetObject> = emptyMap(),
)

@Serializable
data class MojangAssetObject(
    val hash: String,
    val size: Long,
)

@Serializable
data class MojangLoggingConfig(
    val argument: String,
    val file: MojangLoggingFile,
    val type: String,
)

@Serializable
data class MojangLoggingFile(
    val id: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
data class MojangJavaVersion(
    val component: String,
    val majorVersion: Int,
)

@Serializable
data class MojangLibrary(
    val name: String,
    val downloads: MojangLibraryDownloads = MojangLibraryDownloads(),
    val rules: List<MojangRule>? = null,
    val natives: Map<String, String>? = null,
    val url: String? = null,
    val checksums: List<String> = emptyList(),
    val sha1: String? = null,
    val size: Long? = null,
)

@Serializable
data class MojangLibraryDownloads(
    val artifact: MojangDownloadArtifact? = null,
    val classifiers: Map<String, MojangDownloadArtifact>? = null,
)

@Serializable
data class MojangRule(
    val action: MojangRuleAction,
    val features: Map<String, Boolean>? = null,
    val os: MojangRuleOs? = null,
)

@Serializable
enum class MojangRuleAction {
    allow,
    disallow,
}

@Serializable
data class MojangRuleOs(
    val name: String? = null,
    val arch: String? = null,
    val version: String? = null,
)
