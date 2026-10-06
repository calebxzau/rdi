package calebxzhou.rdi.client.ui.screen

import kotlinx.serialization.Serializable


@Serializable
object WardrobeRoute
@Serializable
data class SkinPreviewRoute(val textureId: Int)
@Serializable
data class HostCreateRoute(
    val hostId: String? = null,
    val fromAllHosts: Boolean = false,
    val kind: String = HostKind.Legacy.name,
    val sourceId: String? = null,
    val legacyVersionName: String? = null,
    val displayName: String? = null,
    val legacyMcVersion: String? = null,
)
@Serializable object ModpackLocalListRoute
@Serializable
data class ModpackPlazaRoute(
    val requiredMcVer: String? = null,
    val requiredLoader: String? = null,
    val fromHostId: String? = null,
    val fromHost2Id: String? = null,
    val fromAllHosts: Boolean = false,
    val fromHostMods: Boolean = false
)
@Serializable data class ModCatalogRoute(
    val requiredMcVer: String,
    val requiredLoader: String,
    val targetLocalVersionId: String? = null,
    /** Explicit local target. targetLocalVersionId remains the Legacy compatibility field. */
    val targetLocalKind: String? = null,
    val targetLocalId: String? = null,
    val targetHostId: String? = null,
    val targetHost2Id: String? = null,
    val fromAllHosts: Boolean = false,
    val fromHostMods: Boolean = false
)
@Serializable data class ModCatalogInfoRoute(
    val platform: String,
    val projectId: String,
    val requiredMcVer: String? = null,
    val requiredLoader: String? = null,
    val targetLocalVersionId: String? = null,
    val targetLocalKind: String? = null,
    val targetLocalId: String? = null,
    val targetHostId: String? = null,
    val targetHost2Id: String? = null,
    val fromAllHosts: Boolean = false,
    val fromHostMods: Boolean = false
)
@Serializable enum class ResourceInfoType {
    ResourcePack,
    Shader
}
@Serializable enum class CatalogLocalTargetKind { Legacy }

data class CatalogLocalTarget(val kind: CatalogLocalTargetKind, val id: String)

fun isDisabledCatalogLocalTargetKind(kindValue: String?): Boolean =
    kindValue != null && kindValue != CatalogLocalTargetKind.Legacy.name

/** Decodes the Legacy target, including routes persisted before explicit target fields existed. */
fun resolveCatalogLocalTarget(
    kindValue: String?,
    targetId: String?,
    targetLocalVersionId: String?,
): CatalogLocalTarget? {
    if (isDisabledCatalogLocalTargetKind(kindValue)) return null
    return when (kindValue) {
        CatalogLocalTargetKind.Legacy.name -> (targetId ?: targetLocalVersionId)
            ?.takeIf(String::isNotBlank)
            ?.let { CatalogLocalTarget(CatalogLocalTargetKind.Legacy, it) }
        null -> targetLocalVersionId?.takeIf(String::isNotBlank)
            ?.let { CatalogLocalTarget(CatalogLocalTargetKind.Legacy, it) }
        else -> null
    }
}

fun ModCatalogRoute.localCatalogTarget(): CatalogLocalTarget? =
    resolveCatalogLocalTarget(targetLocalKind, targetLocalId, targetLocalVersionId)

fun ModCatalogInfoRoute.localCatalogTarget(): CatalogLocalTarget? =
    resolveCatalogLocalTarget(targetLocalKind, targetLocalId, targetLocalVersionId)

fun ModCatalogInfoRoute.hasDisabledCatalogLocalTargetKind(): Boolean =
    isDisabledCatalogLocalTargetKind(targetLocalKind)

fun ResourceInfoRoute.localCatalogTarget(): CatalogLocalTarget? =
    resolveCatalogLocalTarget(targetLocalKind, targetLocalId, targetLocalVersionId)

fun ResourceInfoRoute.hasDisabledCatalogLocalTargetKind(): Boolean =
    isDisabledCatalogLocalTargetKind(targetLocalKind)

fun ModpackContentRoute.localCatalogTarget(): CatalogLocalTarget? =
    resolveCatalogLocalTarget(targetLocalKind, targetLocalId, targetLocalVersionId)

@Serializable data class ResourceInfoRoute(
    val type: String,
    val projectId: String,
    val targetLocalVersionId: String? = null,
    val targetLocalKind: String? = null,
    val targetLocalId: String? = null,
)
enum class ModpackContentType(
    val label: String,
    val icon: String,
) {
    Mods("模组", "\uDB85\uDCD3"),
    ResourcePacks("材质", "\uF001"),
    Shaders("光影", "\uDB83\uDC4C"),
}
@Serializable data class ModpackContentRoute(
    val type: String,
    val requiredMcVer: String,
    val requiredLoader: String,
    val targetLocalVersionId: String? = null,
    val targetLocalKind: String? = null,
    val targetLocalId: String? = null,
)
@Serializable enum class ModpackInfoSource {
    Legacy,
    Modpack2;

    companion object {
        fun fromRouteValue(value: String?): ModpackInfoSource =
            entries.firstOrNull { it.name == value } ?: Legacy
    }
}
@Serializable
data class ModpackInfoRoute(
    val modpackId: String,
    val fromHostId: String? = null,
    val fromAllHosts: Boolean = false,
    /** Explicit source for new routes; absent on old persisted routes means Legacy. */
    val source: String = ModpackInfoSource.Legacy.name,
    /** New source-specific identifier. Old callers continue to use modpackId. */
    val ref: String? = null,
)
@Serializable
data class ModpackUploaderManageRoute(
    val modpackId: String,
    val fromHostId: String? = null,
    val fromAllHosts: Boolean = false,
)
@Serializable
data class ModpackLocalAdvanceOptionsRoute(
    val versionId: String,
    val modpackName: String = "",
    val versionName: String = "",
)
@Serializable
data class ModpackVersionEditRoute(
    val modpackId: String,
    val verName: String,
    val fromHostId: String? = null,
    val fromAllHosts: Boolean = false
)
@Serializable
data class ModpackVersionInfoRoute(
    val modpackId: String,
    val verName: String,
    val fromHostId: String? = null,
    val fromAllHosts: Boolean = false,
)
@Serializable
data class ModpackVersionBaseWorldManageRoute(
    val modpackId: String,
    val verName: String,
    val fromHostId: String? = null,
    val fromAllHosts: Boolean = false,
)
/* @Serializable object Modpack2Upload */
@Serializable
data class ModpackUploadRoute(
    val modpackId: String? = null,
)
/* @Serializable data class Modpack2ContentRoute(val versionId: String) */
@Serializable object LoginRoute
@Serializable object MenuRoute
@Serializable object BaseWorldListRoute
@Serializable object BaseWorldUploadRoute
/*@Serializable data class AiChat(
    val mcpPort: Int? = null,
    val versionDir: String? = null,
    val chatId: String? = null
)*/
@Serializable
data class RegisterRoute(val msa: Boolean)
@Serializable
object ResetPasswordRoute
@Serializable
data class PlayerInfoRoute(val playerId: String? = null)
/* @Serializable object Friends */
@Serializable
object SettingRoute
@Serializable
object MailListScreen
@Serializable object HostListRoute
@Serializable object WorldRoute
@Serializable
data class HostInfoRoute(val hostId: String, val fromAllHosts: Boolean = false, val kind: String = HostKind.Legacy.name)
@Serializable
data class HostMembersRoute(val hostId: String, val fromAllHosts: Boolean = false, val kind: String = HostKind.Legacy.name)
@Serializable
data class HostModsRoute(val hostId: String, val fromAllHosts: Boolean = false, val kind: String = HostKind.Legacy.name)
@Serializable
data class HostWorldRoute(val hostId: String, val fromAllHosts: Boolean = false, val kind: String = HostKind.Legacy.name)
@Serializable
data class HostFilesRoute(val hostId: String, val fromAllHosts: Boolean = false, val kind: String = HostKind.Legacy.name)
@Serializable
data class HostBackendRoute(val hostId: String, val fromAllHosts: Boolean = false, val kind: String = HostKind.Legacy.name)
@Serializable
data class MailInfoRoute(val mailId: String)
//@Serializable data class WorldBirdView(val worldId: String)
@Serializable
object McPlayRoute
