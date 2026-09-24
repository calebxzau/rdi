package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.OutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import java.nio.file.FileVisitOption

/**
 * Background WorldData scan, staging copy, and digest pass.
 *
 * Everything under the world directory is included by default, including directories from
 * mods this code has never heard of. Only the fixed exclusions below and the room's own
 * policy remove anything.
 *
 * The stability checks here are a best-effort race detector. They prove that each copied
 * file did not change while it was being read and that the file set did not change during
 * the capture. They do not prove that an arbitrary mod's multi-file write was atomic.
 */
object DmWorldDataCapture {
    const val MAX_PATH_BYTES = 1024
    const val MAX_PATH_DEPTH = 64
    const val MAX_ENTRIES = 100_000
    const val MAX_FILE_BYTES = 256L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L

    /** Bound for reading level.dat. Deliberately not `unlimitedHeap`. */
    const val MAX_LEVEL_DATA_BYTES = 64L * 1024L * 1024L

    private val EXCLUDED_EXTENSIONS = setOf("mca", "mcc")
    private val FIXED_EXCLUDED_FILES = setOf(
        "session.lock",
        "rdi/host.json",
        // Master owns FirmSection ownership; a stale world copy must never overwrite it.
        "data/rdi_firm_sections.dat",
        "data/rdi_firm_sections.dat_old",
    )
    private val SANITIZED_LEVEL_FILES = setOf("level.dat", "level.dat_old")
    private val WINDOWS_RESERVED = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        (1..9).forEach { add("com$it"); add("lpt$it") }
    }
    private const val WINDOWS_REPARSE_POINT = 0x400

    data class Capture(
        val files: List<DmWorldFileEntry>,
        val directories: List<String>,
        val capturedBytes: Long,
        val attempts: Int,
    )

    private data class Identity(val size: Long, val lastModified: Long, val key: Any?)

    private data class Scan(
        val files: Map<String, Identity>,
        val directories: List<String>,
    )

    /**
     * Copies every included file into [stagingWorld] and returns the manifest of the copies.
     *
     * The returned bytes and digests describe the staged copies, so the level.dat player
     * removal below is already reflected. The live world is never modified.
     */
    fun capture(
        worldRoot: Path,
        stagingWorld: Path,
        policy: DmWorldSyncPolicy,
        limits: DmWorldSnapshotLimits? = null,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ): Result<Capture> = runCatching {
        val rootAttributes = Files.readAttributes(
            worldRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        rejectLink(worldRoot, rootAttributes)
        rejectWindowsReparsePoint(worldRoot)
        val root = worldRoot.toRealPath()
        val staging = stagingWorld.toAbsolutePath().normalize()
        require(!staging.startsWith(root)) { "世界同步暂存目录不能位于世界目录内" }
        var lastError: IllegalStateException? = null
        for (attempt in 1..DmWorldSyncTiming.MAX_WORLD_CAPTURE_ATTEMPTS) {
            checkProgress(cancelled, deadlineNanos)
            if (Files.exists(staging)) DmWorldInitArchive.deleteRecursively(staging).getOrThrow()
            Files.createDirectories(staging)
            val before = scan(root, policy, limits)
            require(before.files.containsKey("level.dat")) {
                "世界根目录缺少纳入同步的level.dat"
            }
            val files = copyAll(root, staging, before, limits, deadlineNanos, cancelled)
            val after = scan(root, policy, limits)
            if (before.files == after.files && before.directories == after.directories) {
                return@runCatching Capture(
                    files,
                    before.directories,
                    files.sumOf { it.bytes },
                    attempt,
                )
            }
            lastError = IllegalStateException("世界目录在采集期间发生变化")
        }
        throw lastError ?: IllegalStateException("世界目录采集失败")
    }

    // --- scanning -------------------------------------------------------

    private fun scan(root: Path, policy: DmWorldSyncPolicy, limits: DmWorldSnapshotLimits?): Scan {
        val files = LinkedHashMap<String, Identity>()
        val directories = ArrayList<String>()
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            MAX_PATH_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    rejectLink(dir, attrs)
                    rejectWindowsReparsePoint(dir)
                    val relative = relativize(root, dir)
                    validatePath(relative)
                    if (excludedDirectory(relative, policy)) return FileVisitResult.SKIP_SUBTREE
                    validatePublishedPath(relative, limits)
                    require(dir.toRealPath().startsWith(root)) {
                        "世界目录路径不在世界根目录内：$relative"
                    }
                    directories += relative
                    require(directories.size + files.size <= effectiveEntryLimit(limits)) {
                        "世界目录条目超过${MAX_ENTRIES}个"
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    rejectLink(file, attrs)
                    rejectWindowsReparsePoint(file)
                    require(attrs.isRegularFile) { "世界目录包含不受支持的特殊文件：$file" }
                    val relative = relativize(root, file)
                    validatePath(relative)
                    if (excluded(relative, policy)) return FileVisitResult.CONTINUE
                    validatePublishedPath(relative, limits)
                    require(attrs.size() <= effectiveFileLimit(limits)) { "世界文件超过大小上限：$relative" }
                    require(files.put(relative, identity(attrs)) == null) {
                        "世界目录包含重复路径：$relative"
                    }
                    require(directories.size + files.size <= effectiveEntryLimit(limits)) {
                        "世界目录条目超过${MAX_ENTRIES}个"
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                    throw IllegalStateException("无法读取世界文件：${runCatching { root.relativize(file) }.getOrDefault(file.fileName)}", error)
                }
            },
        )
        val sortedFiles = files.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to it.value }
        require(sortedFiles.containsKey("level.dat")) {
            "世界根目录缺少纳入同步的level.dat"
        }
        validatePathConflicts(sortedFiles.keys, directories)
        return Scan(sortedFiles, directories.sorted())
    }

    private fun identity(attrs: BasicFileAttributes) =
        Identity(attrs.size(), attrs.lastModifiedTime().toMillis(), attrs.fileKey())

    private fun rejectLink(path: Path, attrs: BasicFileAttributes) {
        require(!attrs.isSymbolicLink && !Files.isSymbolicLink(path)) {
            "世界目录包含符号链接：$path"
        }
        require(!attrs.isOther) { "世界目录包含不受支持的特殊文件：$path" }
    }

    private fun rejectWindowsReparsePoint(path: Path) {
        if (!shouldCheckWindowsReparsePoint()) return
        // On Windows, a provider exposing DOS attributes must also expose the raw flag;
        // a read failure is observable and must not silently weaken this check.
        val view = Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        require(view != null) { "Windows文件系统不支持DOS属性读取：$path" }
        val value = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS)
        val attributes = (value as? Number)?.toInt()
            ?: error("无法读取Windows文件属性：$path")
        require(!hasWindowsReparsePoint(attributes)) {
            "世界目录包含Windows重解析点：$path"
        }
    }

    internal fun hasWindowsReparsePoint(attributes: Int): Boolean =
        attributes and WINDOWS_REPARSE_POINT != 0

    internal fun shouldCheckWindowsReparsePoint(separator: Char = File.separatorChar): Boolean =
        separator == '\\'

    private fun relativize(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    /** A declared file must not also be a path component of another declared file. */
    internal fun validatePathConflicts(files: Collection<String>, directories: Collection<String>) {
        val canonicalFiles = files.associateBy(::canonicalPath)
        val canonicalDirectories = directories.associateBy(::canonicalPath)
        require(canonicalFiles.size == files.size) { "世界目录包含大小写冲突的文件路径" }
        require(canonicalDirectories.size == directories.size) { "世界目录包含大小写冲突的目录路径" }
        val directorySet = canonicalDirectories.keys
        canonicalFiles.forEach { (canonical, path) ->
            require(canonical !in directorySet) { "世界路径同时是文件和目录：$path" }
            var index = canonical.indexOf('/')
            while (index > 0) {
                require(canonical.substring(0, index) !in canonicalFiles) {
                    "世界文件路径与上级文件冲突：$path"
                }
                index = canonical.indexOf('/', index + 1)
            }
        }
        canonicalDirectories.forEach { (canonical, path) ->
            var index = canonical.lastIndexOf('/')
            while (index > 0) {
                require(canonical.substring(0, index) !in canonicalFiles) {
                    "世界目录路径与上级文件冲突：$path"
                }
                index = canonical.lastIndexOf('/', index - 1)
            }
        }
    }

    private fun canonicalPath(path: String): String = path.lowercase(Locale.ROOT)

    private fun validatePublishedPath(path: String, limits: DmWorldSnapshotLimits?) {
        if (limits == null) return
        require(path.toByteArray(StandardCharsets.UTF_8).size <= minOf(MAX_PATH_BYTES, limits.maxPathBytes)) {
            "世界路径超过Master限制：$path"
        }
        require(path.count { it == '/' } + 1 <= minOf(MAX_PATH_DEPTH, limits.maxPathDepth)) {
            "世界路径层级超过Master限制：$path"
        }
    }

    private fun effectiveEntryLimit(limits: DmWorldSnapshotLimits?): Int =
        minOf(MAX_ENTRIES, limits?.maxWorldEntries ?: MAX_ENTRIES)

    private fun effectiveFileLimit(limits: DmWorldSnapshotLimits?): Long =
        minOf(MAX_FILE_BYTES, limits?.maxFileBytes ?: MAX_FILE_BYTES)

    // --- exclusions -----------------------------------------------------

    private fun excludedDirectory(relative: String, policy: DmWorldSyncPolicy): Boolean =
        policy.excludedDirectories.any { relative == it || relative.startsWith("$it/") }

    private fun excluded(relative: String, policy: DmWorldSyncPolicy): Boolean {
        val extension = relative.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (extension in EXCLUDED_EXTENSIONS) return true
        if (FIXED_EXCLUDED_FILES.any { relative.equals(it, ignoreCase = true) }) return true
        if (relative in policy.excludedFiles) return true
        return excludedDirectory(relative.substringBeforeLast('/', ""), policy)
    }

    // --- path rules -----------------------------------------------------

    /**
     * Portability and safety rules for one project-relative path.
     *
     * Normal Unicode and spaces are allowed: mod data directories legitimately use them.
     * The rejected forms are the ones that cannot be reproduced as a relative path on the
     * Windows targets this project ships to, or that would escape the world directory.
     */
    fun validatePath(relative: String) {
        require(relative.isNotEmpty()) { "世界路径不能为空" }
        require(relative.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES) {
            "世界路径过长：$relative"
        }
        require('\u0000' !in relative && '\\' !in relative) { "世界路径包含无效字符：$relative" }
        require(!relative.startsWith('/') && !relative.endsWith('/')) { "世界路径必须是相对路径：$relative" }
        require(relative.none { it.code < 0x20 || it.code == 0x7f }) { "世界路径包含控制字符：$relative" }
        val segments = relative.split('/')
        require(segments.size <= MAX_PATH_DEPTH) { "世界路径层级过深：$relative" }
        segments.forEach { segment ->
            require(segment.isNotEmpty() && segment != "." && segment != "..") {
                "世界路径包含空或点段：$relative"
            }
            require(':' !in segment) { "世界路径包含盘符或数据流分隔符：$relative" }
            require(segment.none { it in "<>\"|?*" }) { "世界路径包含Windows不支持的字符：$relative" }
            require(!segment.endsWith('.') && !segment.endsWith(' ')) {
                "世界路径段不能以点或空格结尾：$relative"
            }
            require(segment.substringBefore('.').lowercase(Locale.ROOT) !in WINDOWS_RESERVED) {
                "世界路径包含Windows保留名称：$relative"
            }
        }
    }

    // --- copying --------------------------------------------------------

    private fun copyAll(
        root: Path,
        staging: Path,
        scan: Scan,
        limits: DmWorldSnapshotLimits?,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ): List<DmWorldFileEntry> {
        val entries = ArrayList<DmWorldFileEntry>(scan.files.size)
        var total = 0L
        scan.files.forEach { (relative, expected) ->
            checkProgress(cancelled, deadlineNanos)
            val source = root.resolve(relative)
            val target = staging.resolve(relative).toAbsolutePath().normalize()
            require(target.startsWith(staging)) { "世界暂存路径越界：$relative" }
            Files.createDirectories(target.parent)
            val entry = copyStable(source, target, relative, expected, limits, root, deadlineNanos, cancelled)
            total = Math.addExact(total, entry.bytes)
            require(total <= minOf(MAX_TOTAL_BYTES, limits?.maxExpandedBytes ?: MAX_TOTAL_BYTES)) {
                "世界数据总量超过上限"
            }
            entries += entry
        }
        return entries
    }

    private fun copyStable(
        source: Path,
        target: Path,
        relative: String,
        expected: Identity,
        limits: DmWorldSnapshotLimits?,
        root: Path,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ): DmWorldFileEntry {
        var failure: IllegalStateException? = null
        for (attempt in 1..DmWorldSyncTiming.MAX_FILE_COPY_ATTEMPTS) {
            checkProgress(cancelled, deadlineNanos)
            verifySourceWithinRoot(source, root)
            Files.deleteIfExists(target)
            val before = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            rejectLink(source, before)
            rejectWindowsReparsePoint(source)
            require(before.isRegularFile) { "世界文件不再是普通文件：$relative" }
            val digest = MessageDigest.getInstance("SHA-1")
            val bytes = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                if (relative in SANITIZED_LEVEL_FILES) {
                    writeSanitizedLevelData(source, output, digest, limits)
                } else {
                    copyDigesting(source, output, digest, deadlineNanos, cancelled)
                }
            }
            verifySourceWithinRoot(source, root)
            val after = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            rejectLink(source, after)
            rejectWindowsReparsePoint(source)
            if (identity(before) == expected && identity(after) == expected) {
                require(bytes <= effectiveFileLimit(limits)) { "世界文件超过大小上限：$relative" }
                return DmWorldFileEntry(relative, bytes, digest.digest().joinToString("") { "%02x".format(it) })
            }
            failure = IllegalStateException("世界文件在复制期间发生变化：$relative")
        }
        Files.deleteIfExists(target)
        throw failure ?: IllegalStateException("世界文件复制失败：$relative")
    }

    private fun copyDigesting(
        source: Path,
        output: OutputStream,
        digest: MessageDigest,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ): Long {
        var total = 0L
        Files.newInputStream(source).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkProgress(cancelled, deadlineNanos)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
                total += count
            }
        }
        return total
    }

    /**
     * Copies level data with `Data.Player` removed.
     *
     * Only the staged copy is rewritten; the live file keeps its bytes, and
     * `playerdata/<uuid>.dat` is copied verbatim like any other file.
     */
    private fun writeSanitizedLevelData(
        source: Path,
        output: OutputStream,
        digest: MessageDigest,
        limits: DmWorldSnapshotLimits?,
    ): Long {
        val tag = NbtIo.readCompressed(
            source,
            NbtAccounter.create(minOf(MAX_LEVEL_DATA_BYTES, limits?.maxNbtBytes ?: MAX_LEVEL_DATA_BYTES)),
        )
        val data = tag.get("Data")
        require(data is CompoundTag) { "${source.fileName}的Data不是NBT复合标签" }
        data.remove("Player")
        val bytes = java.io.ByteArrayOutputStream()
        NbtIo.writeCompressed(tag, bytes)
        val payload = bytes.toByteArray()
        digest.update(payload)
        output.write(payload)
        return payload.size.toLong()
    }

    private fun checkProgress(cancelled: () -> Boolean, deadlineNanos: Long) {
        check(!cancelled()) { "世界同步已取消" }
        require(deadlineNanos - System.nanoTime() > 0L) { "世界数据采集超时" }
    }

    private fun verifySourceWithinRoot(source: Path, root: Path) {
        val normalizedSource = source.toAbsolutePath().normalize()
        require(normalizedSource.startsWith(root)) {
            "世界文件路径不在世界根目录内：$source"
        }
        var ancestor: Path? = normalizedSource
        while (ancestor != null) {
            val attributes = Files.readAttributes(
                ancestor,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            rejectLink(ancestor, attributes)
            rejectWindowsReparsePoint(ancestor)
            if (ancestor == root) break
            ancestor = ancestor.parent
        }
        require(source.toRealPath().startsWith(root)) {
            "世界文件路径不在世界根目录内：$source"
        }
    }
}
