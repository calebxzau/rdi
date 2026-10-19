package calebxzhou.rdi.client.model

enum class ModCatalogSource {
    MODRINTH,
    CURSEFORGE
}

enum class ModCatalogSourceFilter {
    ALL,
    MODRINTH,
    CURSEFORGE
}

data class ModCatalogCardVo(
    val source: ModCatalogSource,
    val projectId: String,
    val slug: String?,
    val title: String,
    val author: String,
    val summary: String,
    val iconUrl: String?,
    val downloadsText: String,
    val followsText: String?,
    val modifiedText: String?,
    val categories: List<ModrinthProjectCategoryVo>,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val clientSide: String?,
    val serverSide: String?
)

data class ModCatalogSearchResult(
    val mods: List<ModCatalogCardVo>,
    val offset: Int,
    val limit: Int,
    val totalHits: Int,
    val nextModrinthOffset: Int = offset + limit,
    val nextCurseForgeOffset: Int = offset + limit,
    val hasMore: Boolean = offset + limit < totalHits
)
