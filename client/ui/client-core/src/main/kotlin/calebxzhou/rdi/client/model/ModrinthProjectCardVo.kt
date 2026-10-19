package calebxzhou.rdi.client.model

data class ModrinthProjectCardVo(
    val projectId: String,
    val projectType: String,
    val slug: String,
    val title: String,
    val author: String,
    val description: String,
    val iconUrl: String?,
    val bannerUrl: String?,
    val categories: List<ModrinthProjectCategoryVo>,
    val downloadsText: String,
    val followsText: String,
    val modifiedText: String,
    val latestVersionId: String?,
    val gameVersions: List<String>,
    val loaders: List<String> = emptyList(),
    val clientSide: String? = null,
    val serverSide: String? = null
)

data class ModrinthProjectCategoryVo(
    val id: String,
    val label: String
)

data class ModrinthProjectSearchResult(
    val projects: List<ModrinthProjectCardVo>,
    val offset: Int,
    val limit: Int,
    val totalHits: Int
)
