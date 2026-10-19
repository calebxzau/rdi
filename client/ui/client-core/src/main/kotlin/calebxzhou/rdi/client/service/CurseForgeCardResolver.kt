package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.service.CurseForgeService
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.getMetadataOrEmpty

object CurseForgeCardResolver : ModCardResolver {
    override val platform: String = "cf"

    override suspend fun resolve(
        mods: List<UiMod>,
        context: ModCardResolveContext
    ): Map<String, Mod.CardVo> {
        val targetMods = mods.filter { it.platform.equals(platform, ignoreCase = true) }
        if (targetMods.isEmpty()) return emptyMap()

        val projectIds = targetMods.mapNotNull { it.projectId.toIntOrNull() }.distinct()
        if (projectIds.isEmpty()) return emptyMap()

        val projectIdToFile = targetMods.associateBy(
            keySelector = { it.mod.projectKey() },
            valueTransform = { it.file }
        )

        val infos = CurseForgeService.getModsInfo(projectIds)
        val refs = infos.map { CatalogSlugRef(ModPlatform.CURSEFORGE, it.slug) }.toSet()
        val metadata = context.modCatalog.getMetadataOrEmpty(refs)
        return infos.associate { info ->
            val projectKey = info.id.toString()
            val ref = CatalogSlugRef(ModPlatform.CURSEFORGE, info.slug)
            projectKey to info.toUiCardVo(metadata[ref], projectIdToFile[projectKey])
        }
    }
}
