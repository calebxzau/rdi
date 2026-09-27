package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.service.ModpackModProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

internal object RemovedModCleanupService {
    suspend fun cleanup(versionDir: Path, loader: ModLoader = ModLoader.forge): Result<List<String>> = try {
        Result.success(withContext(Dispatchers.IO) {
            val modsDir = versionDir.resolve("mods")
            val modsAttributes = try {
                Files.readAttributes(modsDir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return@withContext emptyList()
            }
            check(modsAttributes.isDirectory) { "整合包mods路径不是目录: $modsDir" }

            val removed = mutableListOf<String>()
            Files.newDirectoryStream(modsDir).use { entries ->
                entries
                    .filter { path ->
                        (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) &&
                            matchesRemovedSlug(path.fileName.toString(), loader)
                    }
                    .sortedBy { it.fileName.toString() }
                    .forEach { path ->
                        if (Files.deleteIfExists(path)) {
                            removed += path.fileName.toString()
                        }
                    }
            }
            removed
        })
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    internal fun matchesRemovedSlug(fileName: String, loader: ModLoader = ModLoader.forge): Boolean {
        val normalizedName = fileName.lowercase(Locale.ROOT)
        if (!normalizedName.endsWith(".jar")) return false

        val stem = normalizedName.removeSuffix(".jar")
        return ModpackModProcessor.removedSlugsFor(loader).any { slug ->
            val normalizedSlug = slug.lowercase(Locale.ROOT)
            stem == normalizedSlug ||
                stem.startsWith("${normalizedSlug}_") ||
                stem.startsWith("${normalizedSlug}-")
        }
    }
}
