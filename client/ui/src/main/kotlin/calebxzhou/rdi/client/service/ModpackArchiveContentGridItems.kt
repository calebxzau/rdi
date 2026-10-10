package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.ui.comp.ContentGridItem
import calebxzhou.rdi.client.ui.comp.ContentGridType
import calebxzhou.rdi.client.ui.comp.ContentSelectionState
import calebxzhou.rdi.common.model.Mod
import calebxzau.rdi.client.modcatalog.CatalogContentType
import calebxzau.rdi.client.modcatalog.CatalogDigestAlgorithm
import calebxzau.rdi.client.modcatalog.CatalogFile
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.modcatalog.CatalogProjectSource
import calebxzau.rdi.client.modcatalog.EnvironmentCompatibility
import calebxzau.rdi.client.modcatalog.EnvironmentRequirement
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.effectiveEnvironment

/**
 * Converts an archive preview into the value objects consumed by [ContentGrid].
 *
 * Resolution is intentionally only a metadata enrichment step here.  The
 * archive remains the source of file names, paths, URLs, requiredness and
 * selection state; a catalog match only changes a MOD into a [ContentGridItem.ModItem].
 */
fun ModpackArchivePreview.toContentGridItems(
    resolvedMods: Map<String, ResolvedArchiveMod> = emptyMap(),
    selectedOptionalFiles: Set<String> = optionalFiles.mapTo(linkedSetOf(), ModpackArchiveFile::key),
): List<ContentGridItem> = buildList(files.size + embeddedMods.size) {
    files.forEach { file ->
        val selection = file.selectionState(selectedOptionalFiles)
        val resolved = resolvedMods[file.key]
        val resolvedItem = resolved
            ?.takeIf { it.archiveKey == file.key }
            ?.toArchiveModItem(file, selection)
        add(resolvedItem ?: file.toContentItem(selection, resolved))
    }
    embeddedMods.forEach { embedded ->
        val selection = ContentSelectionState.REQUIRED
        val resolved = resolvedMods[embedded.key]
        val resolvedItem = resolved
            ?.takeIf { it.archiveKey == embedded.key }
            ?.toEmbeddedModItem(embedded, selection)
        add(resolvedItem ?: embedded.toContentItem())
    }
}

private fun ModpackArchiveFile.selectionState(
    selectedOptionalFiles: Set<String>,
): ContentSelectionState = when {
    required -> ContentSelectionState.REQUIRED
    key in selectedOptionalFiles -> ContentSelectionState.SELECTED
    else -> ContentSelectionState.UNSELECTED
}

private fun ModpackArchiveFile.toContentItem(
    selection: ContentSelectionState,
    resolved: ResolvedArchiveMod?,
): ContentGridItem.ContentItem {
    val source = resolved?.mod?.sourceFor(resolved.file)
    val card = resolved?.mod?.toCardVo(source)?.let { remote ->
        remote.copy(
            iconUrls = (remote.iconUrls + iconUrls).distinct(),
            version = simplifyModVersion(resolved.file.versionNumber),
        )
    }
    return ContentGridItem.ContentItem(
        key = key,
        type = contentType.toGridType(),
        name = displayName,
        fileName = targetPath.archiveFileName(),
        targetPath = targetPath,
        required = required,
        selectionState = selection,
        card = card,
        archiveDisplayName = displayName,
        archiveSummary = summary,
    )
}

private fun ModpackArchiveEmbeddedMod.toContentItem(): ContentGridItem.ContentItem =
    ContentGridItem.ContentItem(
        key = key,
        type = ContentGridType.MOD,
        name = displayName,
        fileName = targetPath.archiveFileName(),
        targetPath = targetPath,
        required = true,
        selectionState = ContentSelectionState.REQUIRED,
        archiveDisplayName = displayName,
    )

private fun ResolvedArchiveMod.toArchiveModItem(
    archiveFile: ModpackArchiveFile,
    selection: ContentSelectionState,
): ContentGridItem.ModItem? {
    if (archiveFile.contentType != ModpackArchiveContentType.MOD) return null
    return toUiMod(downloadUrls = archiveFile.urls)?.let { uiMod ->
        ContentGridItem.ModItem(
            mod = uiMod,
            archiveKey = archiveFile.key,
            archiveFileName = archiveFile.targetPath.archiveFileName(),
            archiveTargetPath = archiveFile.targetPath,
            required = archiveFile.required,
            selectionState = selection,
        )
    }
}

private fun ResolvedArchiveMod.toEmbeddedModItem(
    embedded: ModpackArchiveEmbeddedMod,
    selection: ContentSelectionState,
): ContentGridItem.ModItem? = toUiMod(downloadUrls = emptyList())?.let { uiMod ->
    ContentGridItem.ModItem(
        mod = uiMod,
        archiveKey = embedded.key,
        archiveFileName = embedded.targetPath.archiveFileName(),
        archiveTargetPath = embedded.targetPath,
        required = true,
        selectionState = selection,
    )
}

/**
 * Builds the legacy MOD model used by the existing card.  The source lookup
 * is exact: a project from the other platform (or another project on the
 * same platform) is not accepted as the archive MOD's metadata.
 */
fun ResolvedArchiveMod.toLegacyMod(downloadUrls: List<String>): Mod? {
    val source = mod.sourceFor(file) ?: return null
    if (source.contentType != CatalogContentType.MOD) return null
    val platform = file.ref.platform.toLegacyPlatform() ?: return null
    val hash = file.archiveHash(platform) ?: return null
    val side = file.effectiveEnvironment(source).toLegacySide()
    return Mod(
        platform = platform,
        projectId = file.project.projectId,
        slug = source.slug,
        fileId = file.ref.fileId,
        hash = hash,
        side = side,
        downloadUrls = downloadUrls,
    )
}

private fun ResolvedArchiveMod.toUiMod(downloadUrls: List<String>): UiMod? {
    val legacy = toLegacyMod(downloadUrls) ?: return null
    val source = mod.sourceFor(file) ?: return null
    return UiMod(
        mod = legacy,
        card = Mod.CardVo(
            name = mod.name,
            nameCn = mod.nameCn,
            intro = mod.summary,
            iconUrls = mod.iconUrls,
            side = legacy.side,
            version = simplifyModVersion(file.versionNumber),
        ),
    )
}

private fun CatalogMod.sourceFor(file: CatalogFile): CatalogProjectSource? =
    sources.firstOrNull { it.ref == file.project }

internal fun CatalogFile.archiveHash(platform: String): String? {
    val algorithms = when (platform) {
        "cf" -> listOf(CatalogDigestAlgorithm.CURSEFORGE_MURMUR2, CatalogDigestAlgorithm.SHA1)
        "mr" -> listOf(CatalogDigestAlgorithm.SHA1)
        else -> emptyList()
    }
    return algorithms.asSequence()
        .mapNotNull { algorithm -> digests.firstOrNull { it.algorithm == algorithm }?.value }
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
}

private fun ModPlatform.toLegacyPlatform(): String? = when (this) {
    ModPlatform.CURSEFORGE -> "cf"
    ModPlatform.MODRINTH -> "mr"
}

private fun EnvironmentCompatibility.toLegacySide(): Mod.Side = when {
    client == EnvironmentRequirement.UNSUPPORTED && server != EnvironmentRequirement.UNSUPPORTED -> Mod.Side.SERVER
    server == EnvironmentRequirement.UNSUPPORTED && client != EnvironmentRequirement.UNSUPPORTED -> Mod.Side.CLIENT
    client == EnvironmentRequirement.UNKNOWN && server == EnvironmentRequirement.UNKNOWN -> Mod.Side.BOTH
    else -> Mod.Side.BOTH
}

private fun ModpackArchiveContentType.toGridType(): ContentGridType = when (this) {
    ModpackArchiveContentType.MOD -> ContentGridType.MOD
    ModpackArchiveContentType.RESOURCE_PACK -> ContentGridType.RESOURCE_PACK
    ModpackArchiveContentType.SHADER_PACK -> ContentGridType.SHADER_PACK
    ModpackArchiveContentType.DATA_PACK -> ContentGridType.DATA_PACK
    ModpackArchiveContentType.OTHER -> ContentGridType.OTHER
}

private fun String.archiveFileName(): String = substringAfterLast('/').ifBlank { this }
