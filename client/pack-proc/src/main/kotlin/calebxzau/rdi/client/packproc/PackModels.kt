package calebxzau.rdi.client.packproc

import calebxzau.rdi.common.model.Content
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import java.io.File

enum class LocalModpackSourceType {
    MODRINTH,
    CURSEFORGE
}

data class ServerExtraFile(
    val sourceFile: File,
    val relativePath: String
)

/** A matched client pack kept in the extracted source tree for archive/test use. */
data class EmbeddedClientExtraSource(
    val content: Content,
    val stagedFile: File,
    val sourceRelativePath: String,
    /**
     * The source-file SHA-1 verified while matching.  CurseForge Content.hash is
     * its Murmur2 fingerprint, so the SHA-1 is kept only in this transient
     * source record for local-byte verification.
     */
    val verifiedSha1: String,
)

data class LoadedLocalModpack(
    val sourceType: LocalModpackSourceType,
    val sourceDir: File,
    val packName: String,
    val packVersion: String,
    val mcVersion: McVersion,
    val modloader: ModLoader,
    val mods: List<Mod>,
    val embeddedModOriginalFileNames: Map<String, String> = emptyMap(),
    val embeddedModSources: List<EmbeddedModSource> = emptyList(),
    val clientExtras: List<Content> = emptyList(),
    val embeddedClientExtraSources: List<EmbeddedClientExtraSource> = emptyList(),
    val serverExtraFiles: List<ServerExtraFile> = emptyList(),
    val containsExcludedMcaFiles: Boolean = false
)

/** A matched embedded mod staged for the caller to import into its content store. */
data class EmbeddedModSource(
    val mod: Mod,
    val stagedFile: File,
    val originalFileName: String
)

data class UploadPayload(
    val sourceType: LocalModpackSourceType,
    val sourceDir: File,
    var mods: MutableList<Mod>,
    val mcVersion: McVersion,
    val modloader: ModLoader,
    val sourceName: String,
    val sourceVersion: String,
    var embeddedModOriginalFileNames: Map<String, String> = emptyMap(),
    var embeddedModSources: List<EmbeddedModSource> = emptyList(),
    var clientExtras: MutableList<Content> = mutableListOf(),
    var embeddedClientExtraSources: List<EmbeddedClientExtraSource> = emptyList(),
    val serverExtraFiles: List<ServerExtraFile> = emptyList()
)

data class LoadedServerPackResult(
    val mods: List<Mod>,
    val serverExtraFiles: List<ServerExtraFile>,
    val embeddedModSources: List<EmbeddedModSource> = emptyList(),
    val containsExcludedMcaFiles: Boolean = false
)

data class PackProcessingPaths(
    val workDir: File
)

fun LoadedLocalModpack.toUploadPayload(): UploadPayload = UploadPayload(
    sourceType = sourceType,
    sourceDir = sourceDir,
    mods = mods.toMutableList(),
    mcVersion = mcVersion,
    modloader = modloader,
    sourceName = packName,
    sourceVersion = packVersion,
    embeddedModOriginalFileNames = embeddedModOriginalFileNames,
    embeddedModSources = embeddedModSources,
    clientExtras = clientExtras.toMutableList(),
    embeddedClientExtraSources = embeddedClientExtraSources,
    serverExtraFiles = serverExtraFiles
)

fun UploadPayload.toLoadedLocalModpack(): LoadedLocalModpack = LoadedLocalModpack(
    sourceType = sourceType,
    sourceDir = sourceDir,
    packName = sourceName,
    packVersion = sourceVersion,
    mcVersion = mcVersion,
    modloader = modloader,
    mods = mods.toList(),
    embeddedModOriginalFileNames = embeddedModOriginalFileNames,
    embeddedModSources = embeddedModSources,
    clientExtras = clientExtras,
    embeddedClientExtraSources = embeddedClientExtraSources,
    serverExtraFiles = serverExtraFiles
)
