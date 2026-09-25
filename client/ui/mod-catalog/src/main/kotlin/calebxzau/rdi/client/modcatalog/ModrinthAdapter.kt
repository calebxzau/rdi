package calebxzau.rdi.client.modcatalog

import calebxzhou.rdi.common.model.ModLoader
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

internal class ModrinthAdapter(
    httpClient: HttpClient,
    config: ModrinthConfig,
    networkPolicy: CatalogNetworkPolicy,
    private val json: Json,
    onWarning: (Throwable) -> Unit = {}
) : PlatformAdapter {
    override val platform = ModPlatform.MODRINTH
    private val transport = CatalogHttpTransport(
        httpClient = httpClient,
        platform = platform,
        officialBaseUrl = config.baseUrl,
        mirrorBaseUrl = networkPolicy.mirrorBase(platform),
        defaultHeaders = mapOf(HttpHeaders.UserAgent to config.userAgent),
        preferMirror = networkPolicy.preferMirrorProvider,
        json = json,
        onWarning = onWarning
    )

    override suspend fun search(
        query: String,
        target: CatalogTarget,
        sort: CatalogSort,
        offset: Int,
        limit: Int
    ): SourcePage {
        val facets = listOf(
            listOf("project_type:mod"),
            listOf("versions:${target.minecraftVersion.mcVer}"),
            listOf("categories:${target.loader.modrinthName()}")
        )
        val response = get<MrSearchResponse>(
            "search",
            buildMap {
                query.trim().takeIf(String::isNotBlank)?.let { put("query", it) }
                put("facets", json.encodeToString(facets))
                put("index", sort.modrinthName())
                put("offset", offset.toString())
                put("limit", limit.toString())
            }
        )
        val items = response.hits.filter { it.projectType.equals("mod", ignoreCase = true) }.map { it.toSource() }
        val next = (offset + response.hits.size).takeIf {
            response.hits.isNotEmpty() && it < response.totalHits
        }
        return SourcePage(items, next, response.totalHits)
    }

    override suspend fun getProjects(ids: Set<String>): AdapterResult<CatalogProjectSource> {
        if (ids.isEmpty()) return AdapterResult(emptyMap(), emptySet())
        val projects = ids.chunked(BATCH_SIZE).flatMap { chunk ->
            get<List<MrProjectDto>>("projects", mapOf("ids" to json.encodeToString(chunk)))
        }.associate { it.id to it.toSource() }
        return AdapterResult(projects, ids - projects.keys)
    }

    override suspend fun resolveSlugs(slugs: List<String>, target: CatalogTarget): SlugResolution {
        val requested = slugs.map(::normalizeProjectSlug).distinct()
        if (requested.isEmpty()) return SlugResolution(emptyMap(), emptySet())
        val projects = slugs.distinctBy(::normalizeProjectSlug).chunked(SLUG_BATCH_SIZE).flatMap { chunk ->
            get<List<MrProjectDto>>("projects", mapOf("ids" to json.encodeToString(chunk)))
        }.filter { project ->
            project.projectType.equals("mod", ignoreCase = true) &&
                target.minecraftVersion.mcVer in project.gameVersions &&
                target.loader.modrinthName() in project.loaders
        }.associateBy({ normalizeProjectSlug(it.slug) }, { it.toSource() })
        return SlugResolution(projects, requested.toSet() - projects.keys)
    }

    override suspend fun getDetails(ref: CatalogProjectRef): CatalogModDetailsSource {
        require(ref.platform == platform)
        val project = get<MrProjectDto>("project/${ref.projectId}")
        return CatalogModDetailsSource(
            project = project.toSource(),
            description = project.body.orEmpty(),
            authors = emptyList(),
            categories = project.categories,
            sourceUrl = "https://modrinth.com/mod/${project.slug}",
            issuesUrl = project.issuesUrl,
            wikiUrl = project.wikiUrl
        )
    }

    override suspend fun listFiles(
        project: CatalogProjectRef,
        target: CatalogTarget,
        offset: Int,
        limit: Int
    ): AdapterFileList {
        require(project.platform == platform)
        val versions = get<List<MrVersionDto>>(
            "project/${project.projectId}/version",
            mapOf(
                "game_versions" to json.encodeToString(listOf(target.minecraftVersion.mcVer)),
                "loaders" to json.encodeToString(listOf(target.loader.modrinthName()))
            )
        ).map { it.toFile() }
            .distinctBy(CatalogFile::ref)
            .sortedByDescending(CatalogFile::publishedAt)
        return AdapterFileList.Complete(versions)
    }

    override suspend fun getFiles(ids: Set<String>): AdapterResult<CatalogFile> {
        if (ids.isEmpty()) return AdapterResult(emptyMap(), emptySet())
        val files = ids.chunked(BATCH_SIZE).flatMap { chunk ->
            get<List<MrVersionDto>>("versions", mapOf("ids" to json.encodeToString(chunk)))
        }.associate { it.id to it.toFile() }
        return AdapterResult(files, ids - files.keys)
    }

    override suspend fun matchFiles(files: List<CatalogFileHashes>): Map<String, CatalogFile> {
        val byHash = files.groupBy { it.sha1.lowercase() }
        val matches = linkedMapOf<String, CatalogFile>()
        byHash.keys.chunked(BATCH_SIZE).forEach { hashes ->
            val found = post<Map<String, MrVersionDto>>(
                "version_files",
                json.encodeToString(MrHashRequest(hashes))
            )
            found.forEach { (hash, version) ->
                val requestedHash = hash.trim().lowercase()
                val exactFiles = version.files.filter { file ->
                    file.hashes["sha1"]?.trim()?.equals(requestedHash, ignoreCase = true) == true
                }
                require(exactFiles.size == 1) { "Modrinth版本文件SHA-1匹配不唯一：$requestedHash" }
                val mapped = version.toFile(exactFiles.single())
                byHash[requestedHash].orEmpty().forEach { matches[it.key] = mapped }
            }
        }
        return matches
    }

    override suspend fun getChangelog(file: CatalogFileRef): String {
        require(file.platform == platform)
        return get<MrVersionDto>("version/${file.fileId}").changelog.orEmpty()
    }

    override suspend fun resolveDownload(file: CatalogFile): ResolvedDownload {
        require(file.ref.platform == platform)
        val url = file.downloadUrl?.validatedDownloadUrl(platform) ?: get<MrVersionDto>(
            "version/${file.ref.fileId}"
        ).primaryFile()?.url?.trim()?.takeIf(String::isNotBlank)?.validatedDownloadUrl(platform)
        ?: throw CatalogException.DownloadUnavailable(file.ref)
        return ResolvedDownload(url, emptyMap(), file.fileName, file.digests)
    }

    private fun MrProjectDto.toSource() = CatalogProjectSource(
        ref = CatalogProjectRef(platform, id),
        slug = slug,
        name = title,
        summary = description.orEmpty().trim(),
        iconUrl = iconUrl,
        downloadCount = downloads,
        updatedAt = updated.toInstant(),
        environment = EnvironmentCompatibility(clientSide.toRequirement(), serverSide.toRequirement()),
        contentType = projectType.toContentType()
    )

    private fun MrSearchHitDto.toSource() = CatalogProjectSource(
        ref = CatalogProjectRef(platform, projectId),
        slug = slug,
        name = title,
        summary = description.orEmpty().trim(),
        iconUrl = iconUrl,
        downloadCount = downloads,
        updatedAt = dateModified.toInstant(),
        environment = EnvironmentCompatibility(clientSide.toRequirement(), serverSide.toRequirement()),
        contentType = projectType.toContentType()
    )

    private fun String.toContentType(): CatalogContentType = when (lowercase()) {
        "mod" -> CatalogContentType.MOD
        "resourcepack" -> CatalogContentType.RESOURCE_PACK
        "shader" -> CatalogContentType.SHADER_PACK
        else -> CatalogContentType.OTHER
    }

    private fun MrVersionDto.toFile(file: MrFileDto = primaryFile() ?: throw invalidResponse()): CatalogFile {
        val sha1 = file.hashes["sha1"]?.trim()?.takeIf(String::isNotBlank) ?: throw invalidResponse()
        val fileName = file.filename.trim().takeIf(String::isNotBlank) ?: throw invalidResponse()
        val downloadUrl = file.url?.trim()?.takeIf(String::isNotBlank)
            ?.validatedDownloadUrl(platform)
        val ref = CatalogFileRef(platform, id)
        return CatalogFile(
            ref = ref,
            project = CatalogProjectRef(platform, projectId),
            displayName = name.trim().ifBlank { versionNumber },
            versionNumber = versionNumber,
            fileName = fileName,
            channel = versionType.toReleaseChannel(),
            publishedAt = datePublished.toInstant(),
            fileSize = file.size,
            minecraftVersions = gameVersions.toSet(),
            loaders = loaders.mapNotNull(String::toModLoader).toSet(),
            environment = EnvironmentCompatibility(),
            digests = setOf(CatalogDigest(CatalogDigestAlgorithm.SHA1, sha1.lowercase())),
            dependencies = dependencies.mapNotNull { it.toDomain() },
            downloadUrl = downloadUrl
        )
    }

    private fun MrVersionDto.primaryFile(): MrFileDto? = files.firstOrNull(MrFileDto::primary) ?: files.firstOrNull()

    private fun MrDependencyDto.toDomain(): CatalogDependency? {
        val target = when {
            !versionId.isNullOrBlank() -> DependencyTarget.File(CatalogFileRef(platform, versionId))
            !projectId.isNullOrBlank() -> DependencyTarget.Project(CatalogProjectRef(platform, projectId))
            else -> return null
        }
        val requirement = when (dependencyType) {
            "required" -> DependencyRequirement.REQUIRED
            "optional" -> DependencyRequirement.OPTIONAL
            "incompatible" -> DependencyRequirement.INCOMPATIBLE
            "embedded" -> DependencyRequirement.EMBEDDED
            else -> return null
        }
        return CatalogDependency(target, requirement)
    }

    private suspend inline fun <reified T> get(
        path: String,
        parameters: Map<String, String> = emptyMap()
    ): T = transport.get(path, parameters).decode(platform, json)

    private suspend inline fun <reified T> post(path: String, body: String): T =
        transport.post(path, body).decode(platform, json)

    private fun invalidResponse() = CatalogException.InvalidResponse(platform)

    private fun String?.toInstant(): Instant = try {
        this?.let(Instant::parse) ?: Instant.EPOCH
    } catch (cause: Exception) {
        throw CatalogException.InvalidResponse(platform, cause)
    }

    private fun String?.toRequirement(): EnvironmentRequirement = when (this?.lowercase()) {
        "required" -> EnvironmentRequirement.REQUIRED
        "optional" -> EnvironmentRequirement.OPTIONAL
        "unsupported" -> EnvironmentRequirement.UNSUPPORTED
        else -> EnvironmentRequirement.UNKNOWN
    }

    private fun String?.toReleaseChannel(): ReleaseChannel = when (this) {
        "release" -> ReleaseChannel.RELEASE
        "beta" -> ReleaseChannel.BETA
        "alpha" -> ReleaseChannel.ALPHA
        else -> throw invalidResponse()
    }

    companion object {
        private const val BATCH_SIZE = 100
        private const val SLUG_BATCH_SIZE = 50
    }
}

private fun ModLoader.modrinthName(): String = when (this) {
    ModLoader.forge -> "forge"
    ModLoader.neoforge -> "neoforge"
    ModLoader.Fabric -> "fabric"
}

private fun CatalogSort.modrinthName(): String = when (this) {
    CatalogSort.RELEVANCE -> "relevance"
    CatalogSort.DOWNLOADS -> "downloads"
    CatalogSort.UPDATED -> "updated"
}

private fun String.toModLoader(): ModLoader? = when (lowercase()) {
    "forge" -> ModLoader.forge
    "neoforge" -> ModLoader.neoforge
    "fabric" -> ModLoader.Fabric
    else -> null
}

@Serializable
private data class MrSearchResponse(
    val hits: List<MrSearchHitDto> = emptyList(),
    @SerialName("total_hits") val totalHits: Long = 0
)

@Serializable
private data class MrSearchHitDto(
    val slug: String,
    val title: String,
    val description: String? = null,
    @SerialName("project_type") val projectType: String,
    val downloads: Long = 0,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("project_id") val projectId: String,
    @SerialName("date_modified") val dateModified: String? = null,
    @SerialName("client_side") val clientSide: String? = null,
    @SerialName("server_side") val serverSide: String? = null
)

@Serializable
private data class MrProjectDto(
    val id: String,
    val slug: String,
    val title: String,
    val description: String? = null,
    val body: String? = null,
    @SerialName("project_type") val projectType: String = "mod",
    val downloads: Long = 0,
    @SerialName("icon_url") val iconUrl: String? = null,
    val updated: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("client_side") val clientSide: String? = null,
    @SerialName("server_side") val serverSide: String? = null,
    @SerialName("issues_url") val issuesUrl: String? = null,
    @SerialName("wiki_url") val wikiUrl: String? = null
)

@Serializable
private data class MrVersionDto(
    val id: String,
    val name: String,
    @SerialName("version_number") val versionNumber: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("version_type") val versionType: String? = null,
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("date_published") val datePublished: String? = null,
    val files: List<MrFileDto> = emptyList(),
    val dependencies: List<MrDependencyDto> = emptyList(),
    val changelog: String? = null
)

@Serializable
private data class MrFileDto(
    val filename: String,
    val url: String? = null,
    val primary: Boolean = false,
    val size: Long = 0,
    val hashes: Map<String, String> = emptyMap()
)

@Serializable
private data class MrDependencyDto(
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("dependency_type") val dependencyType: String? = null
)

@Serializable
private data class MrHashRequest(
    val hashes: List<String>,
    val algorithm: String = "sha1"
)
