package calebxzhou.rdi.master.service.modpack

import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.common.model.ModpackFileManifest
import calebxzhou.rdi.common.archive.MODPACK_ARCHIVE_READ_LIMITS
import calebxzhou.rdi.common.archive.forEachArchiveEntryStreaming
import calebxzhou.rdi.common.archive.normalizeSafeArchivePath
import calebxzhou.rdi.common.archive.resolveClientPackEntryPath
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.util.digestHex
import calebxzhou.rdi.common.util.sha1dig
import calebxzhou.rdi.master.service.clientManifestFile
import calebxzhou.rdi.master.service.clientPackFile
import calebxzhou.rdi.master.service.fullManifestFile
import calebxzhou.rdi.master.service.fullPackFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * File manifests (path, size, SHA-1) of a built version, stored next to its archives:
 * `<ver>-manifest-client.json` hashes the files of the client pack as players get them, and
 * `<ver>-manifest-full.json` hashes the uploaded pack with the [ModpackFileManifest.Side] of each file.
 */
object ModpackManifestService {
    private val lgr by Loggers
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val generateLocks = ConcurrentHashMap<String, Mutex>()

    private class HashedFile(val path: String, val size: Long, val sha1: String)

    /** Rewrites both manifests from the version's current archives. */
    internal fun generate(version: Modpack.Version): Result<Unit> = runCatching {
        val fullPack = version.fullPackFile
        if (!fullPack.exists()) throw RequestError("版本压缩文件不存在: ${fullPack.name}")
        val clientFiles = version.clientPackFile.takeIf(File::exists)?.let(::hashFiles).orEmpty()
        val fullEntryPaths = mutableListOf<String>()
        val fullFiles = hashFiles(fullPack, fullEntryPaths)
        val sides = resolveSides(fullFiles.map { it.path }, clientFiles.map { it.path }, fullEntryPaths)
        write(
            version.clientManifestFile,
            manifest(version, clientFiles.map { ModpackFileManifest.Entry(it.path, it.size, it.sha1) }),
        )
        write(
            version.fullManifestFile,
            manifest(version, fullFiles.mapIndexed { index, file ->
                ModpackFileManifest.Entry(file.path, file.size, file.sha1, sides[index])
            }),
        )
        lgr.info { "整合包${version.modpackId} V${version.name}文件清单已生成：客户端${clientFiles.size}个，完整${fullFiles.size}个" }
    }

    /** Returns the client manifest, generating it first when it is missing or older than the archives. */
    internal suspend fun clientManifest(version: Modpack.Version): ModpackFileManifest =
        read(version, version.clientManifestFile)

    /** Returns the full manifest, generating it first when it is missing or older than the archives. */
    internal suspend fun fullManifest(version: Modpack.Version): ModpackFileManifest =
        read(version, version.fullManifestFile)

    internal fun deleteManifests(version: Modpack.Version) {
        version.clientManifestFile.delete()
        version.fullManifestFile.delete()
    }

    private suspend fun read(version: Modpack.Version, file: File): ModpackFileManifest {
        if (version.status != Modpack.Status.OK) throw RequestError("此版本未准备好或构建失败")
        return withContext(Dispatchers.IO) {
            if (isStale(version)) {
                generateLocks.computeIfAbsent("${version.modpackId}/${version.name}") { Mutex() }.withLock {
                    if (isStale(version)) generate(version).getOrThrow()
                }
            }
            json.decodeFromString<ModpackFileManifest>(file.readText())
        }
    }

    private fun isStale(version: Modpack.Version): Boolean {
        val archives = listOf(version.fullPackFile, version.clientPackFile).filter(File::exists)
        return listOf(version.clientManifestFile, version.fullManifestFile).any { manifest ->
            !manifest.exists() || archives.any { it.lastModified() > manifest.lastModified() }
        }
    }

    private fun manifest(version: Modpack.Version, files: List<ModpackFileManifest.Entry>) =
        ModpackFileManifest(version.modpackId.toHexString(), version.name, files)

    /** Hashes the files of [archive]; [entryPaths] also receives the directories. */
    private fun hashFiles(archive: File, entryPaths: MutableList<String>? = null): List<HashedFile> {
        val files = mutableListOf<HashedFile>()
        val buffer = ByteArray(128 * 1024)
        forEachArchiveEntryStreaming(archive, MODPACK_ARCHIVE_READ_LIMITS) { entry, input ->
            entryPaths?.add(normalizeSafeArchivePath(entry.path))
            if (entry.isDirectory) return@forEachArchiveEntryStreaming
            val digest = sha1dig()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            files += HashedFile(normalizeSafeArchivePath(entry.path), entry.size, digest.digestHex())
        }
        return files
    }

    /**
     * Sides of the uploaded files: a file is on the client when it wins its client path and that path
     * survived into the client pack, and on the server when a host install copies it. [entryPaths]
     * picks the server root the way a host install does, directories included.
     */
    internal fun resolveSides(
        fullPaths: List<String>,
        clientPaths: List<String>,
        entryPaths: List<String> = fullPaths,
    ): List<ModpackFileManifest.Side> {
        val clientPathSet = clientPaths.map { it.lowercase(Locale.ROOT) }.toSet()
        val clientMapped = fullPaths.map { resolveClientPackEntryPath(it) }
        val clientWinners = mutableMapOf<String, Int>()
        clientMapped.forEachIndexed { index, mapped ->
            mapped ?: return@forEachIndexed
            val key = mapped.relativePath.lowercase(Locale.ROOT)
            val previous = clientWinners[key]
            if (previous == null || mapped.priority > clientMapped[previous]!!.priority) clientWinners[key] = index
        }
        val serverRoot = ModpackArchiveService.resolveServerInstallArchiveRootForBuild(entryPaths)
        return fullPaths.mapIndexed { index, path ->
            val clientKey = clientMapped[index]?.relativePath?.lowercase(Locale.ROOT)
            val onClient = clientKey != null && clientWinners[clientKey] == index && clientKey in clientPathSet
            val onServer = ModpackArchiveService.extractServerInstallRelativePathForBuild(path, serverRoot)
                ?.let(ModpackArchiveService::isInstalledOnHost) == true
            when {
                onClient && onServer -> ModpackFileManifest.Side.Both
                onClient -> ModpackFileManifest.Side.ClientOnly
                onServer -> ModpackFileManifest.Side.ServerOnly
                else -> ModpackFileManifest.Side.Neither
            }
        }
    }

    private fun write(file: File, manifest: ModpackFileManifest) {
        val target = file.toPath()
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".${file.name}-", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(manifest))
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
