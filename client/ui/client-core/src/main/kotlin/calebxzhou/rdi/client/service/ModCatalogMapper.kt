package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.ModrinthProjectCardVo
import calebxzhou.rdi.client.model.ModrinthProjectCategoryVo
import calebxzhou.rdi.client.model.ModrinthProjectSearchResult
import calebxzhou.rdi.client.model.ModCatalogCardVo
import calebxzhou.rdi.client.model.ModCatalogSearchResult
import calebxzhou.rdi.client.model.ModCatalogSource
import calebxzhou.rdi.common.model.CurseForgeModInfo

fun ModrinthProjectSearchResult.toModCatalogSearchResult(): ModCatalogSearchResult =
    ModCatalogSearchResult(
        mods = projects.map(ModrinthProjectCardVo::toModCatalogCardVo),
        offset = offset,
        limit = limit,
        totalHits = totalHits
    )

fun ModrinthProjectCardVo.toModCatalogCardVo(): ModCatalogCardVo {
    val loaderIds = loaders.map(String::lowercase).toSet()
    return ModCatalogCardVo(
        source = ModCatalogSource.MODRINTH,
        projectId = projectId,
        slug = slug,
        title = title,
        author = author,
        summary = description,
        iconUrl = iconUrl,
        downloadsText = downloadsText,
        followsText = followsText,
        modifiedText = modifiedText,
        categories = categories.filterNot { it.id.lowercase() in loaderIds },
        gameVersions = gameVersions,
        loaders = loaders,
        clientSide = clientSide,
        serverSide = serverSide
    )
}

fun CurseForgeModInfo.toModCatalogCardVo(): ModCatalogCardVo {
    val loaders = latestFiles
        .flatMap { it.gameVersions }
        .mapNotNull(String::toRemoteLoaderId)
        .distinct()
    val gameVersions = latestFiles
        .flatMap { it.gameVersions }
        .filter(String::isMinecraftVersion)
        .distinct()
    return ModCatalogCardVo(
        source = ModCatalogSource.CURSEFORGE,
        projectId = id.toString(),
        slug = slug,
        title = name,
        author = authors.firstOrNull()?.name ?: "CurseForge",
        summary = summary?.takeIf(String::isNotBlank) ?: "暂无简介",
        iconUrl = logo?.thumbnailUrl ?: logo?.url,
        downloadsText = (downloadCount ?: 0).toCompactCountText(),
        followsText = thumbsUpCount?.toSeparatedCountText(),
        modifiedText = dateModified?.toRelativeTimeText(),
        categories = categories.mapNotNull { category ->
            val categoryId = category.slug ?: category.name ?: return@mapNotNull null
            ModrinthProjectCategoryVo(categoryId, category.name ?: categoryId.toModrinthProjectCategoryLabel())
        }.take(4),
        gameVersions = gameVersions,
        loaders = loaders,
        clientSide = null,
        serverSide = null
    )
}
