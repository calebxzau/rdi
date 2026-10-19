package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.getMetadataOrEmpty
import calebxzau.rdi.client.modcatalog.toCatalogSlugRef
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.common.model.Mod

object LocalModCardResolver {
    suspend fun resolve(
        mods: List<UiMod>,
        context: ModCardResolveContext,
        contentStore: ClientContentStore = ClientContentStores.shared,
    ): Map<String, Mod.CardVo> {
        val refs = mods.mapNotNull { it.mod.toCatalogSlugRef() }.toSet()
        val metadata = context.modCatalog.getMetadataOrEmpty(refs)
        return mods.toLocalCardVos(metadata, contentStore)
    }
}
