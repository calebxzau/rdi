package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogMod
import java.util.concurrent.ConcurrentHashMap

data class CachedCatalogModIcon(val iconData: ByteArray?)

private val localIconCache = ConcurrentHashMap<String, CachedCatalogModIcon>()

fun CatalogMod.peekLocalIcon(): CachedCatalogModIcon? = localIconCache[localIconCacheKey]

suspend fun CatalogMod.loadLocalIcon(): Result<ByteArray?> {
    val cacheKey = localIconCacheKey
    localIconCache[cacheKey]?.let { return Result.success(it.iconData) }
    // Catalog entries do not carry a content digest. Local content metadata is
    // therefore resolved only through UiMod/ClientContentStore, never by
    // scanning the historical download directory.
    val result = Result.success<ByteArray?>(null)
    localIconCache[cacheKey] = CachedCatalogModIcon(null)
    return result
}

private val CatalogMod.localIconCacheKey: String
    get() = buildString {
        append(identity.stableKey)
        sources.forEach { source ->
            append('|')
            append(source.ref.platform.name)
            append(':')
            append(source.slug)
        }
    }
