package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.getMetadataOrEmpty
import calebxzhou.rdi.client.model.ModrinthProjectCategoryVo
import calebxzhou.rdi.client.model.ModrinthProjectVersionVo
import calebxzhou.rdi.client.model.ModCatalogCardVo
import calebxzhou.rdi.client.model.ModCatalogSource
import calebxzhou.rdi.common.model.ModrinthProject
import calebxzhou.rdi.common.service.CurseForgeService
import calebxzhou.rdi.common.service.ModrinthService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object ModCatalogDependencyService {
    suspend fun loadRequiredDependencyCards(
        modCatalog: ModCatalog,
        versions: List<ModrinthProjectVersionVo>
    ): List<ModCatalogCardVo> =
        coroutineScope {
            val requiredDependencies = versions
                .flatMap { it.dependencies }
                .filter { it.required }

            val modrinthIds = requiredDependencies
                .filter { it.source == ModCatalogSource.MODRINTH }
                .mapNotNull { it.projectId?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
            val curseForgeIds = requiredDependencies
                .filter { it.source == ModCatalogSource.CURSEFORGE }
                .mapNotNull { it.projectId?.trim()?.toIntOrNull() }
                .distinct()

            val modrinthCards = async {
                if (modrinthIds.isEmpty()) {
                    emptyList()
                } else {
                    ModrinthService.getMultipleProjects(modrinthIds)
                        .map(ModrinthProject::toModCatalogCardVo)
                }
            }
            val curseForgeCards = async {
                if (curseForgeIds.isEmpty()) {
                    emptyList()
                } else {
                    CurseForgeService.getModsInfo(curseForgeIds)
                        .map { it.toModCatalogCardVo() }
                }
            }

            val cards = (modrinthCards.await() + curseForgeCards.await())
                .distinctBy { "${it.source}:${it.projectId}" }
            val refs = cards.mapNotNull { card ->
                val platform = if (card.source == ModCatalogSource.MODRINTH) {
                    ModPlatform.MODRINTH
                } else {
                    ModPlatform.CURSEFORGE
                }
                card.slug?.let { CatalogSlugRef(platform, it) }
            }.toSet()
            val metadata = modCatalog.getMetadataOrEmpty(refs)
            cards.map { card ->
                val platform = if (card.source == ModCatalogSource.MODRINTH) {
                    ModPlatform.MODRINTH
                } else {
                    ModPlatform.CURSEFORGE
                }
                val local = card.slug?.let { metadata[CatalogSlugRef(platform, it)] } ?: return@map card
                card.copy(
                    title = local.nameCn?.takeIf(String::isNotBlank) ?: local.name,
                    summary = local.intro?.takeIf(String::isNotBlank) ?: card.summary,
                    iconUrl = card.iconUrl ?: local.logoUrl
                )
            }
        }
}

private fun ModrinthProject.toModCatalogCardVo(): ModCatalogCardVo {
    val loaderIds = loaders.map(String::lowercase).toSet()
    val categoryVos = (categories + loaders)
        .distinct()
        .map { ModrinthProjectCategoryVo(it, it.toModrinthProjectCategoryLabel()) }
    return ModCatalogCardVo(
        source = ModCatalogSource.MODRINTH,
        projectId = id,
        slug = slug,
        title = title,
        author = "Modrinth",
        summary = description?.takeIf(String::isNotBlank) ?: "暂无简介",
        iconUrl = iconUrl,
        downloadsText = downloads.toCompactCountText(),
        followsText = followers.toSeparatedCountText(),
        modifiedText = updated.toRelativeTimeText(),
        categories = categoryVos.filterNot { it.id.lowercase() in loaderIds }.take(4),
        gameVersions = gameVersions,
        loaders = loaders,
        clientSide = clientSide,
        serverSide = serverSide
    )
}
