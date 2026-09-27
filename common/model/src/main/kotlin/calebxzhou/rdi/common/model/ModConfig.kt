package calebxzhou.rdi.common.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import net.peanuuutz.tomlkt.TomlElement

@Serializable
data class ModsTomlConfig(
    val modLoader: String? = null,
    val loaderVersion: String? = null,
    val license: String = "",
    val showAsResourcePack: Boolean = false,
    val showAsDataPack: Boolean = false,
    val services: List<String> = emptyList(),
    val properties: Map<String, TomlElement> = emptyMap(),
    val issueTrackerURL: String? = null,
    val mods: List<ModsTomlModEntry> = emptyList(),
    val dependencies: Map<String, List<ModsTomlDependency>> = emptyMap(),
    val modproperties: Map<String, Map<String, TomlElement>> = emptyMap(),
    val features: Map<String, Map<String, TomlElement>> = emptyMap()
)

@Serializable
data class ModsTomlModEntry(
    val modId: String = "",
    val namespace: String? = null,
    val version: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val logoFile: String? = null,
    val logoBlur: Boolean? = null,
    val updateJSONURL: String? = null,
    val modUrl: String? = null
)

@Serializable
data class ModsTomlDependency(
    val modId: String = "",
    val versionRange: String? = null,
    val type: String? = null,
    val reason: String? = null,
    val ordering: String? = null,
    val side: String? = null,
    val referralUrl: String? = null,
    val mandatory: Boolean? = null,
    val optional: Boolean = false
)

data class JarModMeta(
    val modIds: List<String> = emptyList(),
    val version: String? = null,
    val description: String? = null,
    val logoFile: String? = null,
    val side: Mod.Side? = null,
    val requiredDependencies: List<JarModDependency> = emptyList(),
    val nestedJarPaths: List<String> = emptyList()
) {
    val primaryModId: String?
        get() = modIds.firstOrNull()
}

data class JarModDependency(
    val modId: String,
    val versionExpression: String? = null
)

@Serializable
data class LegacyMcmodInfoEntry(
    @SerialName("modid")
    val modId: String = "",
    val version: String? = null,
    val description: String? = null,
    val logoFile: String? = null
)

@Serializable
data class LegacyMcmodInfoContainer(
    val modList: List<LegacyMcmodInfoEntry> = emptyList()
)
