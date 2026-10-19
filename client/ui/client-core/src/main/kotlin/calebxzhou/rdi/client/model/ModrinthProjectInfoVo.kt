package calebxzhou.rdi.client.model

data class ModrinthProjectInfoVo(
    val source: ModCatalogSource = ModCatalogSource.MODRINTH,
    val projectId: String,
    val slug: String,
    val title: String,
    val summary: String,
    val description: String,
    val downloadsText: String,
    val followsText: String,
    val iconUrl: String?,
    val categories: List<ModrinthProjectCategoryVo>,
    val gallery: List<ModrinthProjectGalleryVo>,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val versionIds: List<String>,
    val versions: List<ModrinthProjectVersionVo>,
    val sourceUrl: String? = null,
    val mcmodId: Int? = null
)

data class ModrinthProjectGalleryVo(
    val url: String,
    val rawUrl: String?,
    val featured: Boolean,
    val name: String?
)

data class ModrinthProjectVersionVo(
    val id: String,
    val name: String,
    val versionNumber: String,
    val changelog: String,
    val publishedText: String,
    val rawPublished: String,
    val downloadsText: String,
    val versionType: String?,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val environment: String?,
    val minecraftJavaServer: String?,
    val dependencies: List<ModrinthProjectVersionDependencyVo>,
    val primaryFile: ModrinthProjectVersionFileVo?,
    val files: List<ModrinthProjectVersionFileVo>
)

data class ModrinthProjectVersionDependencyVo(
    val versionId: String?,
    val projectId: String?,
    val dependencyType: String?,
    val source: ModCatalogSource = ModCatalogSource.MODRINTH,
    val relationLabel: String = dependencyType ?: "dependency",
    val required: Boolean = dependencyType == "required"
)

data class ModrinthProjectVersionFileVo(
    val fileId: String?,
    val filename: String,
    val url: String,
    val size: Long?,
    val sizeText: String,
    val sha1: String?,
    val sha512: String?,
    val murmur2: String? = null,
    val fileType: String?,
    val primary: Boolean
)
