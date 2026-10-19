package calebxzhou.rdi.client.service

import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.modcatalog.CatalogContentType
import calebxzau.rdi.client.modcatalog.CatalogFile
import calebxzau.rdi.client.modcatalog.CatalogFileRef
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.hashCatalogFile
import calebxzhou.rdi.common.util.openChineseZip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files

data class ResolvedArchiveMod(
    val archiveKey: String,
    val file: CatalogFile,
    val mod: CatalogMod,
)

enum class ResolutionStage { URL_HINTS, EMBEDDED_HASHES }

sealed interface ContentResolutionUpdate {
    data class Resolved(
        val items: Map<String, ResolvedArchiveMod>,
        val stage: ResolutionStage,
    ) : ContentResolutionUpdate

    data class Failed(val stage: ResolutionStage, val cause: Throwable) : ContentResolutionUpdate
    data object Complete : ContentResolutionUpdate
}

fun interface ModpackArchiveContentResolutionSource {
    fun resolve(
        preview: ModpackArchivePreview,
        completedStages: Set<ResolutionStage>,
    ): Flow<ContentResolutionUpdate>
}

fun ModpackArchiveContentResolutionSource.resolve(
    preview: ModpackArchivePreview,
): Flow<ContentResolutionUpdate> = resolve(preview, emptySet())

class ModpackArchiveContentResolver(
    private val catalog: ModCatalog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModpackArchiveContentResolutionSource {
    override fun resolve(
        preview: ModpackArchivePreview,
        completedStages: Set<ResolutionStage>,
    ): Flow<ContentResolutionUpdate> = channelFlow {
        if (!archiveUnchanged(preview)) {
            send(ContentResolutionUpdate.Complete)
            return@channelFlow
        }

        val jobs = ResolutionStage.entries.filterNot(completedStages::contains).map { stage ->
            launch {
                try {
                    val items = when (stage) {
                        ResolutionStage.URL_HINTS -> resolveUrlHints(preview)
                        ResolutionStage.EMBEDDED_HASHES -> resolveEmbedded(preview)
                    }
                    send(ContentResolutionUpdate.Resolved(items, stage))
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    lgr.warn(cause) { "整合包内容识别失败：$stage" }
                    send(ContentResolutionUpdate.Failed(stage, cause))
                }
            }
        }
        jobs.joinAll()
        send(ContentResolutionUpdate.Complete)
    }

    private suspend fun resolveUrlHints(preview: ModpackArchivePreview): Map<String, ResolvedArchiveMod> {
        val hints = preview.files.asSequence()
            .filter { it.contentType == ModpackArchiveContentType.MOD }
            .mapNotNull { file -> resolveArchiveModIdentity(file.urls)?.let { file to it } }
            .toList()
        if (hints.isEmpty()) return emptyMap()
        val refs = hints.mapTo(linkedSetOf()) { (_, hint) ->
            when (hint) {
                is ArchiveUrlIdentity.Modrinth -> CatalogFileRef(ModPlatform.MODRINTH, hint.fileId)
                is ArchiveUrlIdentity.CurseForge -> CatalogFileRef(ModPlatform.CURSEFORGE, hint.fileId.toString())
            }
        }
        val files = catalog.getFiles(refs).getOrThrow().value
        val accepted = hints.mapNotNull { (archiveFile, hint) ->
            val ref = when (hint) {
                is ArchiveUrlIdentity.Modrinth -> CatalogFileRef(ModPlatform.MODRINTH, hint.fileId)
                is ArchiveUrlIdentity.CurseForge -> CatalogFileRef(ModPlatform.CURSEFORGE, hint.fileId.toString())
            }
            val file = files[ref] ?: return@mapNotNull null
            if (hint is ArchiveUrlIdentity.Modrinth && file.project.projectId != hint.projectId) {
                lgr.debug { "Modrinth下载地址项目不匹配：${archiveFile.targetPath}" }
                return@mapNotNull null
            }
            archiveFile.key to file
        }
        return resolveProjects(accepted)
    }

    private suspend fun resolveEmbedded(preview: ModpackArchivePreview): Map<String, ResolvedArchiveMod> =
        withContext(ioDispatcher) {
            if (preview.embeddedMods.isEmpty()) return@withContext emptyMap()
            val hashes = preview.archive.toFile().openChineseZip().use { zip ->
                preview.embeddedMods.mapNotNull { embedded ->
                    val entry = zip.getEntry(embedded.entryName) ?: return@mapNotNull null
                    hashCatalogFile(embedded.key) { zip.getInputStream(entry) }
                        .onFailure { lgr.warn(it) { "内嵌Mod读取失败：${embedded.targetPath}" } }
                        .getOrNull()
                }
            }
            if (hashes.isEmpty()) return@withContext emptyMap()
            val matched = catalog.matchFiles(hashes).getOrThrow()
            resolveProjects(matched.map { it.key to it.value })
        }

    private suspend fun resolveProjects(
        files: List<Pair<String, CatalogFile>>,
    ): Map<String, ResolvedArchiveMod> {
        if (files.isEmpty()) return emptyMap()
        val mods = catalog.getMods(files.mapTo(linkedSetOf()) { it.second.project }).getOrThrow().value
        return files.mapNotNull { (key, file) ->
            val mod = mods[file.project] ?: return@mapNotNull null
            val source = mod.sources.firstOrNull { it.ref == file.project }
            if (source?.contentType != CatalogContentType.MOD) {
                lgr.debug { "归档Mod未获得有效项目资料：$key" }
                return@mapNotNull null
            }
            key to ResolvedArchiveMod(key, file, mod)
        }.toMap()
    }

    private fun archiveUnchanged(preview: ModpackArchivePreview): Boolean =
        Files.isRegularFile(preview.archive) &&
            Files.size(preview.archive) == preview.archiveSize &&
            Files.getLastModifiedTime(preview.archive).toMillis() == preview.archiveModifiedAt
}
