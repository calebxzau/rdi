package calebxzau.rdi.client.modcatalog

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportsRuntime
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

enum class ModPlatform {
    CURSEFORGE,
    MODRINTH
}

data class CatalogProjectRef(
    val platform: ModPlatform,
    val projectId: String
)

data class CatalogSlugRef(
    val platform: ModPlatform,
    val slug: String
)

data class CatalogModMetadata(
    val mcmodId: Int,
    val name: String,
    val nameCn: String?,
    val intro: String?,
    val logoUrl: String?,
    val projects: List<CatalogMetadataProject>
) {
    fun project(platform: ModPlatform): CatalogMetadataProject? = projects.firstOrNull { it.platform == platform }
}

data class CatalogMetadataProject(
    val platform: ModPlatform,
    val slug: String,
    val nameCnOverride: String?
)

data class CatalogFileRef(
    val platform: ModPlatform,
    val fileId: String
)

sealed interface CatalogIdentity {
    val stableKey: String

    data class Mcmod(val id: Int) : CatalogIdentity {
        override val stableKey = "mcmod:$id"
    }

    data class Unmapped(val ref: CatalogProjectRef) : CatalogIdentity {
        override val stableKey = "${ref.platform.name.lowercase()}:${ref.projectId}"
    }
}

enum class EnvironmentRequirement {
    REQUIRED,
    OPTIONAL,
    UNSUPPORTED,
    UNKNOWN
}

data class EnvironmentCompatibility(
    val client: EnvironmentRequirement = EnvironmentRequirement.UNKNOWN,
    val server: EnvironmentRequirement = EnvironmentRequirement.UNKNOWN
)

fun EnvironmentCompatibility.withFallback(
    fallback: EnvironmentCompatibility?
): EnvironmentCompatibility = EnvironmentCompatibility(
    client = client.takeUnless { it == EnvironmentRequirement.UNKNOWN }
        ?: fallback?.client
        ?: EnvironmentRequirement.UNKNOWN,
    server = server.takeUnless { it == EnvironmentRequirement.UNKNOWN }
        ?: fallback?.server
        ?: EnvironmentRequirement.UNKNOWN,
)

data class CatalogProjectSource(
    val ref: CatalogProjectRef,
    val slug: String,
    val name: String,
    val summary: String,
    val iconUrl: String?,
    val downloadCount: Long,
    val updatedAt: Instant,
    val environment: EnvironmentCompatibility,
    val contentType: CatalogContentType = CatalogContentType.MOD
)

enum class CatalogContentType {
    MOD,
    RESOURCE_PACK,
    SHADER_PACK,
    OTHER
}

data class CatalogMod(
    val identity: CatalogIdentity,
    val primaryRef: CatalogProjectRef,
    val sources: List<CatalogProjectSource>,
    val name: String,
    val nameCn: String?,
    val summary: String,
    val mcmodIconUrl: String?,
    val downloadCount: Long,
    val updatedAt: Instant,
    val environment: EnvironmentCompatibility,
    val mcmodId: Int? = (identity as? CatalogIdentity.Mcmod)?.id
) {
    init {
        require(sources.isNotEmpty()) { "Catalog mod must contain at least one source" }
        require(sources.any { it.ref == primaryRef }) { "Primary source must belong to the mod" }
    }

    val platformIconUrls: List<String>
        get() {
            val primary = sources.first { it.ref == primaryRef }
            return (listOf(primary) + sources.filterNot { it.ref == primaryRef })
                .mapNotNull { it.iconUrl?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
        }

    val iconUrls: List<String>
        get() = (platformIconUrls + listOfNotNull(mcmodIconUrl?.trim()?.takeIf(String::isNotBlank)))
            .distinct()
}

data class CatalogModDetails(
    val mod: CatalogMod,
    val description: String,
    val authors: List<String>,
    val categories: List<String>,
    val sourceUrl: String?,
    val issuesUrl: String?,
    val wikiUrl: String?
)

enum class CatalogSort {
    RELEVANCE,
    DOWNLOADS,
    UPDATED
}

data class CatalogTarget(
    val minecraftVersion: McVersion,
    val loader: ModLoader
) {
    init {
        require(minecraftVersion.enabled) { "Minecraft version is disabled" }
        require(minecraftVersion.supportsRuntime(loader)) {
            "${minecraftVersion.mcVer} does not support ${loader.name}"
        }
    }
}

class CatalogSearchCursor internal constructor(internal val state: SearchCursorState)

data class CatalogSearchRequest(
    val query: String,
    val target: CatalogTarget,
    val sort: CatalogSort = CatalogSort.RELEVANCE,
    val pageSize: Int = 20,
    val cursor: CatalogSearchCursor? = null
) {
    init {
        require(pageSize in 1..50) { "Page size must be between 1 and 50" }
    }
}

data class CatalogSearchPage(
    val items: List<CatalogMod>,
    val nextCursor: CatalogSearchCursor?,
    val estimatedTotal: Long?
)

data class CatalogDetailsRequest(
    val mod: CatalogMod
)

enum class ReleaseChannel {
    RELEASE,
    BETA,
    ALPHA
}

data class CatalogDigest(
    val algorithm: CatalogDigestAlgorithm,
    val value: String
)

data class CatalogFileHashes(
    val key: String,
    val sha1: String,
    val curseForgeFingerprint: Long?
)

enum class CatalogDigestAlgorithm {
    SHA1,
    CURSEFORGE_MURMUR2
}

enum class DependencyRequirement {
    REQUIRED,
    OPTIONAL,
    INCOMPATIBLE,
    EMBEDDED,
    TOOL,
    INCLUDED
}

sealed interface DependencyTarget {
    data class Project(val ref: CatalogProjectRef) : DependencyTarget
    data class File(val ref: CatalogFileRef) : DependencyTarget
}

internal val DependencyTarget.platform: ModPlatform
    get() = when (this) {
        is DependencyTarget.Project -> ref.platform
        is DependencyTarget.File -> ref.platform
    }

data class CatalogDependency(
    val target: DependencyTarget,
    val requirement: DependencyRequirement
)

data class CatalogFile(
    val ref: CatalogFileRef,
    val project: CatalogProjectRef,
    val displayName: String,
    val versionNumber: String?,
    val fileName: String,
    val channel: ReleaseChannel,
    val publishedAt: Instant,
    val fileSize: Long,
    val minecraftVersions: Set<String>,
    val loaders: Set<ModLoader>,
    val environment: EnvironmentCompatibility,
    val digests: Set<CatalogDigest>,
    val dependencies: List<CatalogDependency>,
    val downloadUrl: String? = null
)

fun CatalogFile.effectiveEnvironment(source: CatalogProjectSource?): EnvironmentCompatibility =
    environment.withFallback(source?.environment)

class CatalogFileCursor internal constructor(
    internal val platform: ModPlatform,
    internal val offset: Int
)

data class CatalogFileRequest(
    val mod: CatalogMod,
    val target: CatalogTarget,
    val channels: Set<ReleaseChannel> = setOf(ReleaseChannel.RELEASE, ReleaseChannel.BETA),
    val pageSize: Int = 30,
    val cursor: CatalogFileCursor? = null
) {
    init {
        require(channels.isNotEmpty()) { "At least one release channel is required" }
        require(pageSize in 1..50) { "Page size must be between 1 and 50" }
    }
}

data class CatalogFilePage(
    val items: List<CatalogFile>,
    val nextCursor: CatalogFileCursor?
)

data class LocalFileMatch(
    val path: Path,
    val files: List<CatalogFile>,
    val identity: CatalogIdentity?
)

data class LocalFileMatchReport(
    val matched: List<LocalFileMatch>,
    val unmatched: Set<Path>,
    val unreadable: Map<Path, String>
)

data class InstalledCatalogFile(
    val file: CatalogFile,
    val target: CatalogTarget
)

data class UpdateRequest(
    val installed: List<InstalledCatalogFile>
)

data class UpdateCandidate(
    val installed: CatalogFile,
    val latest: CatalogFile
)

data class UpdateReport(
    val updates: List<UpdateCandidate>,
    val unchanged: Set<CatalogFileRef>,
    val unresolved: Set<CatalogFileRef>
)

data class DependencyRequest @JvmOverloads constructor(
    val roots: List<CatalogFile>,
    val target: CatalogTarget,
    val requirements: Set<DependencyRequirement> = DependencyRequirement.entries.toSet()
)

data class DependencyNode(
    val file: CatalogFile,
    val depth: Int
)

data class DependencyEdge(
    val from: CatalogFileRef,
    val to: DependencyTarget,
    val requirement: DependencyRequirement
)

data class UnresolvedDependency(
    val from: CatalogFileRef,
    val target: DependencyTarget,
    val reason: String
)

data class DependencyGraph(
    val roots: Set<CatalogFileRef>,
    val nodes: Map<CatalogFileRef, DependencyNode>,
    val edges: List<DependencyEdge>,
    val unresolved: List<UnresolvedDependency>,
    val cycles: List<List<CatalogFileRef>>
)

data class ResolvedDownload(
    val url: String,
    val headers: Map<String, String>,
    val fileName: String,
    val digests: Set<CatalogDigest>
)

sealed interface CatalogIssue {
    data class SourceFailed(val platform: ModPlatform, val message: String) : CatalogIssue
    data class IdentityIndexUnavailable(val message: String) : CatalogIssue
    data class MissingProjects(val refs: Set<CatalogProjectRef>) : CatalogIssue
    data class MissingFiles(val refs: Set<CatalogFileRef>) : CatalogIssue
}

data class CatalogOutcome<T>(
    val value: T,
    val issues: List<CatalogIssue> = emptyList()
)

data class CatalogCachePolicy(
    val metadataTtl: Duration = Duration.ofMinutes(10),
    val negativeTtl: Duration = Duration.ofSeconds(30)
)

internal data class SearchCursorState(
    val requestKey: String,
    val localOffset: Int,
    val localExhausted: Boolean,
    val localBuffer: List<CatalogMod>,
    val fallbackQuery: String?,
    val platformOffsets: Map<ModPlatform, Int>,
    val platformBuffers: Map<ModPlatform, List<CatalogProjectSource>>,
    val exhausted: Set<ModPlatform>,
    val unavailablePlatforms: Set<ModPlatform>,
    val seenIdentities: Set<String>
)

internal data class CatalogIdentityRecord(
    val mcmodId: Int,
    val name: String,
    val nameCn: String?,
    val intro: String?,
    val logoUrl: String?,
    val projects: List<CatalogIdentityProject>
)

internal data class CatalogIdentityProject(
    val platform: ModPlatform,
    val slug: String,
    val nameCnOverride: String?
)
