package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogDetailsRequest
import calebxzau.rdi.client.modcatalog.CatalogFile
import calebxzau.rdi.client.modcatalog.CatalogFileHashes
import calebxzau.rdi.client.modcatalog.CatalogFilePage
import calebxzau.rdi.client.modcatalog.CatalogFileRef
import calebxzau.rdi.client.modcatalog.CatalogFileRequest
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.modcatalog.CatalogModDetails
import calebxzau.rdi.client.modcatalog.CatalogModMetadata
import calebxzau.rdi.client.modcatalog.CatalogOutcome
import calebxzau.rdi.client.modcatalog.CatalogSearchPage
import calebxzau.rdi.client.modcatalog.CatalogSearchRequest
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.DependencyGraph
import calebxzau.rdi.client.modcatalog.DependencyRequest
import calebxzau.rdi.client.modcatalog.LocalFileMatchReport
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ResolvedDownload
import calebxzau.rdi.client.modcatalog.UpdateReport
import calebxzau.rdi.client.modcatalog.UpdateRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class ModpackArchiveContentResolverTest {
    @Test
    fun `changed archive completes without consulting catalog`() = runBlocking {
        val archive = Files.createTempFile("rdi-content-resolver-changed", ".zip")
        try {
            val catalog = NoCallModCatalog()
            val updates = ModpackArchiveContentResolver(catalog)
                .resolve(preview(archive, archiveSize = Files.size(archive) + 1))
                .toList()

            assertEquals(listOf(ContentResolutionUpdate.Complete), updates)
            assertTrue(catalog.calls.isEmpty())
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    @Test
    fun `completed stages emit only complete without consulting catalog`() = runBlocking {
        val archive = Files.createTempFile("rdi-content-resolver-complete", ".zip")
        try {
            val catalog = NoCallModCatalog()
            val updates = ModpackArchiveContentResolver(catalog)
                .resolve(
                    preview(
                        archive,
                        archiveSize = Files.size(archive),
                        archiveModifiedAt = Files.getLastModifiedTime(archive).toMillis(),
                    ),
                    completedStages = ResolutionStage.entries.toSet(),
                )
                .toList()

            assertEquals(listOf(ContentResolutionUpdate.Complete), updates)
            assertTrue(catalog.calls.isEmpty())
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    private fun preview(
        archive: Path,
        archiveSize: Long,
        archiveModifiedAt: Long = Files.getLastModifiedTime(archive).toMillis(),
    ) = ModpackArchivePreview(
        archive = archive,
        archiveSize = archiveSize,
        archiveModifiedAt = archiveModifiedAt,
        format = ModpackArchiveFormat.MODRINTH,
        name = "test",
        summary = null,
        mcVersion = calebxzhou.rdi.common.model.McVersion.V211,
        modLoader = calebxzhou.rdi.common.model.ModLoader.neoforge,
        files = emptyList(),
        embeddedMods = emptyList(),
        overrides = emptyList(),
    )
}

private class NoCallModCatalog : ModCatalog {
    val calls = mutableListOf<String>()

    override suspend fun getMetadata(refs: Set<CatalogSlugRef>): Result<Map<CatalogSlugRef, CatalogModMetadata>> =
        error("catalog should not be called: getMetadata")

    override suspend fun searchMetadata(query: String, limit: Int): Result<List<CatalogModMetadata>> =
        error("catalog should not be called: searchMetadata")

    override suspend fun search(request: CatalogSearchRequest): Result<CatalogOutcome<CatalogSearchPage>> =
        error("catalog should not be called: search")

    override suspend fun getMods(refs: Set<calebxzau.rdi.client.modcatalog.CatalogProjectRef>):
        Result<CatalogOutcome<Map<calebxzau.rdi.client.modcatalog.CatalogProjectRef, CatalogMod>>> =
        error("catalog should not be called: getMods")

    override suspend fun getFiles(refs: Set<CatalogFileRef>): Result<CatalogOutcome<Map<CatalogFileRef, CatalogFile>>> =
        error("catalog should not be called: getFiles")

    override suspend fun getDetails(request: CatalogDetailsRequest): Result<CatalogOutcome<CatalogModDetails>> =
        error("catalog should not be called: getDetails")

    override suspend fun listFiles(request: CatalogFileRequest): Result<CatalogOutcome<CatalogFilePage>> =
        error("catalog should not be called: listFiles")

    override suspend fun matchFiles(hashes: List<CatalogFileHashes>): Result<Map<String, CatalogFile>> =
        error("catalog should not be called: matchFiles")

    override suspend fun matchLocalFiles(paths: List<Path>): Result<CatalogOutcome<LocalFileMatchReport>> =
        error("catalog should not be called: matchLocalFiles")

    override suspend fun findUpdates(request: UpdateRequest): Result<CatalogOutcome<UpdateReport>> =
        error("catalog should not be called: findUpdates")

    override suspend fun resolveDependencies(request: DependencyRequest): Result<CatalogOutcome<DependencyGraph>> =
        error("catalog should not be called: resolveDependencies")

    override suspend fun getChangelog(file: CatalogFileRef): Result<String> =
        error("catalog should not be called: getChangelog")

    override suspend fun resolveDownload(file: CatalogFile): Result<ResolvedDownload> =
        error("catalog should not be called: resolveDownload")

    override fun close() = Unit
}
