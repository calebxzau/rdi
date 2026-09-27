package calebxzhou.rdi.client.model

import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.displaySlugOrProject
import java.io.File
import java.util.Locale

data class UiMod(
    val mod: Mod,
    val card: Mod.CardVo? = null,
    val file: File? = null,
    val fabricEnvironmentSide: Mod.Side? = null,
) {
    val key: String
        get() = mod.uiModKey

    val platform: String
        get() = mod.platform

    val projectId: String
        get() = mod.projectId

    val slug: String
        get() = mod.slug

    val fileId: String
        get() = mod.fileId

    val hash: String
        get() = mod.hash

    val side: Mod.Side
        get() = mod.side

    val displayName: String
        get() = card?.name?.takeIf { it.isNotBlank() } ?: mod.displaySlugOrProject

    val displayNameCn: String?
        get() = card?.nameCn?.takeIf { it.isNotBlank() }

    val primaryName: String
        get() = displayNameCn?.trim() ?: displayName.trim()

    val secondaryName: String?
        get() = when {
            displayNameCn != null -> displayName.trim().takeIf(String::isNotEmpty)
            card == null -> slug.trim().takeIf { it.isNotEmpty() && !it.equals(primaryName, ignoreCase = true) }
            else -> null
        }

    val intro: String
        get() = card?.intro.orEmpty()

    val iconData: ByteArray?
        get() = card?.iconData

    val iconUrls: List<String>
        get() = card?.iconUrls.orEmpty()

    val searchText: String = listOfNotNull(
        primaryName,
        secondaryName,
        slug,
        projectId,
        mod.fileName
    ).joinToString("\n").lowercase(Locale.ROOT)

    fun withMod(mod: Mod): UiMod = copy(mod = mod)

    fun withFile(file: File?): UiMod = copy(file = file)

    fun withSide(side: Mod.Side): UiMod = copy(
        mod = mod.copy(side = side),
        card = card?.copy(side = side)
    )

    fun toMod(): Mod = mod.copy(
        platform = mod.platform,
        projectId = mod.projectId,
        slug = mod.slug,
        fileId = mod.fileId,
        hash = mod.hash,
        side = mod.side,
        downloadUrls = mod.downloadUrls.toList()
    )
}

val Mod.uiModKey: String
    get() = "$platform:$projectId:$fileId:$hash"

fun Mod.toUiMod(card: Mod.CardVo? = null, file: File? = null): UiMod = UiMod(
    mod = this,
    card = card,
    file = file
)
