package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogModMetadata
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.modcatalog.CatalogProjectSource
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.toCatalogSlugRef
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.common.model.CurseForgeModInfo
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModrinthProject
import calebxzhou.rdi.common.service.ModService.buildIconUrls
import calebxzhou.rdi.common.service.ModService.modLogo
import calebxzhou.rdi.common.service.ModService.readModMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.jar.JarFile

internal typealias LocalCardContentReader = suspend (List<ContentRequest>) -> Result<Map<String, Path>>

internal suspend fun UiMod.toLocalCardVo(
    metadata: CatalogModMetadata?,
    contentStore: ClientContentStore = ClientContentStores.shared,
    cachedContentReader: LocalCardContentReader = { requests ->
        contentStore.useCached(requests) { it }
    },
): Mod.CardVo? {
    val localMeta = readTrustedContentMetaBatch(listOf(this), cachedContentReader)[trustedContentRequestId()]
        ?: readLegacyModCardMeta()
    return toLocalCardVo(metadata, localMeta)
}

internal suspend fun List<UiMod>.toLocalCardVos(
    metadata: Map<CatalogSlugRef, CatalogModMetadata>,
    contentStore: ClientContentStore = ClientContentStores.shared,
    cachedContentReader: LocalCardContentReader = { requests ->
        contentStore.useCached(requests) { it }
    },
): Map<String, Mod.CardVo> {
    val trustedMeta = readTrustedContentMetaBatch(this, cachedContentReader)
    return withContext(Dispatchers.IO) {
        buildMap {
            for (uiMod in this@toLocalCardVos) {
                val localMeta = trustedMeta[uiMod.trustedContentRequestId()]
                    ?: uiMod.readLegacyModCardMeta()
                uiMod.toLocalCardVo(
                    metadata = uiMod.mod.toCatalogSlugRef()?.let(metadata::get),
                    localMeta = localMeta,
                )?.let { put(uiMod.mod.projectKey(), it) }
            }
        }
    }
}

private fun UiMod.toLocalCardVo(
    metadata: CatalogModMetadata?,
    localMeta: LocalModCardMeta?,
): Mod.CardVo? {
    val iconBytes = localMeta?.iconBytes
    val introText = localMeta?.introText
    val version = localMeta?.version
    if (metadata == null && iconBytes == null && introText == null && version == null) return null

    return metadata?.toUiCardVo(localMeta, mod.side)?.copy(
        intro = metadata.intro?.takeIf(String::isNotBlank) ?: introText ?: "暂无介绍"
    ) ?: Mod.CardVo(
        name = mod.slug.ifBlank { mod.projectId },
        intro = introText ?: "暂无介绍",
        iconData = iconBytes,
        side = mod.side,
        version = version,
    )
}

private suspend fun readTrustedContentMetaBatch(
    mods: List<UiMod>,
    cachedContentReader: LocalCardContentReader,
): Map<String, LocalModCardMeta> {
    val requests = mods.asSequence()
        .filter { it.mod.hasTrustedContentHash() }
        .map { uiMod ->
            uiMod.mod.toClientContentRequest().copy(
                // Card hydration is a read projection. A missing cache must not
                // start a download just because a Mod carries download sources.
                allowNetwork = false,
                sources = emptyList(),
            )
        }
        .distinctBy { it.id }
        .toList()
    if (requests.isEmpty()) return emptyMap()

    return cachedContentReader(requests).map { paths ->
        withContext(Dispatchers.IO) {
            paths.mapNotNull { (id, path) ->
                runCatching { path.toFile().readLocalModCardMeta() }
                    .getOrNull()
                    ?.let { id to it }
            }.toMap()
        }
    }.getOrElse { emptyMap() }
}

private fun UiMod.trustedContentRequestId(): String? = mod
    .takeIf { it.hasTrustedContentHash() }
    ?.toClientContentRequest()
    ?.id

private fun UiMod.readLegacyModCardMeta(): LocalModCardMeta? {
    return file?.let {
        runCatching { it.readLocalModCardMeta() }.getOrNull()
    }
}

private fun Mod.hasTrustedContentHash(): Boolean {
    val normalizedHash = hash.trim()
    return when (platform.lowercase()) {
        "cf" -> normalizedHash.toULongOrNull()?.let { it <= UInt.MAX_VALUE.toULong() } == true
        else -> normalizedHash.matches(SHA1_HASH_PATTERN)
    }
}

private val SHA1_HASH_PATTERN = Regex("[0-9a-fA-F]{40}")

internal fun CurseForgeModInfo.toUiCardVo(metadata: CatalogModMetadata?, modFile: File? = null): Mod.CardVo {
    val icons = buildIconUrls(logo?.thumbnailUrl, logo?.url, metadata?.logoUrl)
    val resolvedName = name.ifBlank { slug }
    val localMeta = modFile?.let {
        runCatching { it.readLocalModCardMeta() }.getOrNull()
    }
    val iconBytes = localMeta?.iconBytes
    val introText = summary?.takeIf { it.isNotBlank() }?.trim()
        ?: localMeta?.introText
        ?: "暂无介绍"

    return metadata?.toUiCardVo(localMeta)?.copy(
        intro = metadata.intro?.takeIf(String::isNotBlank) ?: introText,
        iconUrls = icons
    ) ?: Mod.CardVo(
        name = resolvedName,
        nameCn = null,
        intro = introText,
        iconData = iconBytes,
        iconUrls = icons,
        side = Mod.Side.BOTH,
        version = localMeta?.version,
    )
}

internal fun ModrinthProject.toUiCardVo(metadata: CatalogModMetadata?, modFile: File? = null): Mod.CardVo {
    val icons = buildIconUrls(iconUrl, metadata?.logoUrl)
    val resolvedName = title.ifBlank { slug }
    val localMeta = modFile?.let {
        runCatching { it.readLocalModCardMeta() }.getOrNull()
    }
    val iconBytes = localMeta?.iconBytes
    val introText = description?.takeIf { it.isNotBlank() }?.trim()
        ?: localMeta?.introText
        ?: "暂无介绍"

    return metadata?.toUiCardVo(localMeta)?.copy(
        intro = metadata.intro?.takeIf(String::isNotBlank) ?: introText,
        iconUrls = icons
    ) ?: Mod.CardVo(
        name = resolvedName,
        nameCn = null,
        intro = introText,
        iconData = iconBytes,
        iconUrls = icons,
        side = Mod.Side.BOTH,
        version = localMeta?.version,
    )
}

data class LocalModCardMeta(
    val iconBytes: ByteArray? = null,
    val introText: String? = null,
    val version: String? = null,
)

internal fun File.readLocalModCardMeta(): LocalModCardMeta = JarFile(this).use { jar ->
    val meta = jar.readModMeta()
    LocalModCardMeta(
        iconBytes = jar.modLogo,
        introText = meta?.description?.takeIf { it.isNotBlank() },
        version = jar.readDisplayVersion(meta?.version),
    )
}

/** The catalog-side projection shared by archive and workspace content cards. */
fun CatalogMod.toCardVo(
    source: CatalogProjectSource? = null,
    localMeta: LocalModCardMeta? = null,
    side: Mod.Side = Mod.Side.BOTH,
): Mod.CardVo = Mod.CardVo(
    name = name.ifBlank { source?.name.orEmpty() },
    nameCn = nameCn,
    intro = summary.ifBlank { source?.summary.orEmpty() }.ifBlank {
        localMeta?.introText.orEmpty()
    },
    iconData = localMeta?.iconBytes,
    iconUrls = (iconUrls + listOfNotNull(source?.iconUrl)).distinct(),
    side = side,
    version = localMeta?.version,
)

/** Merge a remote catalog card while retaining local JAR metadata. */
internal fun Mod.CardVo.mergeLocalFirst(remote: Mod.CardVo): Mod.CardVo = remote.copy(
    name = remote.name.ifBlank { name },
    nameCn = remote.nameCn ?: nameCn,
    intro = remote.intro.ifBlank { intro },
    iconData = iconData ?: remote.iconData,
    iconUrls = buildIconUrls(remote.iconUrls + iconUrls),
    side = side,
    version = version ?: remote.version,
)

private fun CatalogModMetadata.toUiCardVo(
    localMeta: LocalModCardMeta? = null,
    side: Mod.Side = Mod.Side.BOTH
): Mod.CardVo = Mod.CardVo(
    name = name,
    nameCn = nameCn,
    intro = intro.orEmpty(),
    iconData = localMeta?.iconBytes,
    iconUrls = buildIconUrls(logoUrl),
    side = side,
    version = localMeta?.version,
)
