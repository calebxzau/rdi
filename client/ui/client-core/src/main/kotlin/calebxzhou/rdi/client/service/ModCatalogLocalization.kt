package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogModMetadata
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.getMetadataOrEmpty

object ModCatalogLocalization {
    suspend fun find(
        modCatalog: ModCatalog,
        platform: ModPlatform,
        slug: String?
    ): CatalogModMetadata? {
        val normalizedSlug = slug?.trim()?.takeIf(String::isNotBlank) ?: return null
        val ref = CatalogSlugRef(platform, normalizedSlug)
        return modCatalog.getMetadataOrEmpty(setOf(ref))[ref]
    }

    fun title(metadata: CatalogModMetadata?, fallback: String): String =
        metadata?.nameCn?.takeIf(String::isNotBlank)
            ?: metadata?.name?.takeIf(String::isNotBlank)
            ?: fallback

    fun intro(metadata: CatalogModMetadata?, fallback: String): String =
        metadata?.intro?.takeIf(String::isNotBlank) ?: fallback
}
