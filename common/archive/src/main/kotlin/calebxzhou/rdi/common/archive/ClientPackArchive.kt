package calebxzhou.rdi.common.archive

import java.io.File
import java.nio.file.Files
import java.util.Locale

data class ClientPackEntryPath(val relativePath: String, val priority: Int)

fun resolveClientPackEntryPath(path: String, allowUnprefixed: Boolean = false): ClientPackEntryPath? {
    val normalized = normalizeSafeArchivePath(path)
    if (normalized.isEmpty()) return null
    val segments = normalized.split('/')
    if (segments.first().lowercase(Locale.ROOT) in setOf("server", "server-overrides")) return null
    val clientIndex = segments.indexOf("client-overrides")
    val commonIndex = segments.indexOf("overrides")
    val layerIndex = when {
        clientIndex >= 0 && (commonIndex < 0 || clientIndex < commonIndex) -> clientIndex
        commonIndex >= 0 -> commonIndex
        else -> -1
    }
    if (layerIndex >= 0) {
        if (segments.take(layerIndex).any { it.lowercase(Locale.ROOT) in setOf("server", "server-overrides") }) return null
        val relative = segments.drop(layerIndex + 1).joinToString("/")
        return relative.takeIf(String::isNotEmpty)?.let {
            ClientPackEntryPath(it, if (segments[layerIndex] == "client-overrides") 2 else 1)
        }
    }
    if (segments.first() in setOf("manifest.json", "modrinth.index.json")) return null
    if (allowUnprefixed || segments.first() in setOf("gtnh", "resourcepacks", "shaderpacks")) {
        return ClientPackEntryPath(normalized, 0)
    }
    return null
}

/** Extracts full layered uploads and already flattened client artifacts using the same precedence. */
fun extractClientPackArchiveToDir(
    archiveFile: File,
    targetDir: File,
    layered: Boolean = false,
    onProgress: (doneFiles: Int, totalFiles: Int, currentPath: String) -> Unit = { _, _, _ -> },
) {
    val metadata = mutableListOf<StreamingTarEntry>()
    forEachArchiveEntryStreaming(archiveFile, MODPACK_ARCHIVE_READ_LIMITS) { entry, _ -> metadata += entry }
    data class Selected(val index: Int, val path: ClientPackEntryPath)
    val selected = linkedMapOf<String, Selected>()
    metadata.forEachIndexed { index, entry ->
        if (entry.isDirectory) return@forEachIndexed
        val mapped = if (layered) {
            resolveClientPackEntryPath(entry.path) ?: return@forEachIndexed
        } else {
            ClientPackEntryPath(normalizeSafeArchivePath(entry.path), 0)
        }
        val key = mapped.relativePath.lowercase(Locale.ROOT)
        val old = selected[key]
        require(old == null || old.path.priority != mapped.priority) {
            "客户端整合包包含重复文件路径: ${mapped.relativePath}"
        }
        if (old == null || old.path.priority < mapped.priority) selected[key] = Selected(index, mapped)
    }
    selected.keys.forEach { path ->
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            require(parent !in selected) { "客户端整合包文件与目录路径冲突: $path" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    val byIndex = selected.values.associateBy(Selected::index)
    val root = targetDir.toPath().toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(root)) { "客户端整合包目标目录是符号链接: $root" }
    var index = 0
    var done = 0
    forEachArchiveEntryStreaming(archiveFile, MODPACK_ARCHIVE_READ_LIMITS) { _, input ->
        val winner = byIndex[index++] ?: return@forEachArchiveEntryStreaming
        val target = root.resolve(winner.path.relativePath).normalize()
        require(target.startsWith(root)) { "非法客户端整合包路径: ${winner.path.relativePath}" }
        var current = root
        root.relativize(target).forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "客户端整合包目标包含符号链接: $current" }
        }
        Files.createDirectories(target.parent)
        Files.newOutputStream(target).use { input.copyTo(it, 128 * 1024) }
        onProgress(++done, selected.size.coerceAtLeast(1), winner.path.relativePath)
    }
}
