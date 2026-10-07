package calebxzau.rdi.anvilrw.remap

import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * The regular files and directories of a save at one moment, used to detect changes while the save is
 * copied or rewritten. Paths are relative and `/`-separated; `session.lock` is never included.
 *
 * Only regular files and directories are accepted: a symbolic link, a Windows junction or any other
 * special file fails the snapshot with [SaveLinkException]. Nothing is ever followed.
 *
 * Known limitation: a program that ignores `session.lock`, rewrites a file at the same size and
 * restores its modification time is not detected.
 */
class SaveSnapshot private constructor(
    val root: Path,
    val files: Map<String, FileState>,
    val directories: Set<String>,
) {
    data class FileState(val size: Long, val lastModified: FileTime)

    val totalBytes: Long get() = files.values.sumOf { it.size }

    /** Walks [root] again and fails with [SaveChangedException] if anything differs. */
    fun verify(): Result<Unit> = remapResult {
        val now = walk(root)
        if (now.directories != directories) throw SaveChangedException(describe(directories, now.directories, "directory"))
        if (now.files != files) {
            val changed = (files.keys + now.files.keys).filter { files[it] != now.files[it] }.sorted()
            throw SaveChangedException("Files changed since the snapshot: ${changed.take(5)}")
        }
    }

    /** Fails with [SaveChangedException] if [relativePath] is no longer the regular file in this snapshot. */
    fun checkUnchanged(relativePath: String): Result<Unit> = remapResult {
        checkFile(relativePath)
    }

    /**
     * Reads [relativePath] in one pass, without following links. The file must still match this
     * snapshot, and exactly the recorded number of bytes must be read.
     */
    fun readFile(relativePath: String): Result<ByteArray> = remapResult {
        val state = checkFile(relativePath)
        if (state.size > Int.MAX_VALUE - 8) throw RemapLimitExceededException("${relativePath} is too large to read at once")
        val bytes = try {
            openFile(relativePath).use { BoundedIo.readAtMost(it, state.size.toInt(), relativePath) }
        } catch (_: RemapLimitExceededException) {
            throw SaveChangedException("${relativePath} grew while reading")
        }
        if (bytes.size.toLong() != state.size) throw SaveChangedException("${relativePath} changed while reading")
        bytes
    }

    /**
     * Streams [relativePath] to [destination] (which must not exist) without following links, passing
     * every block read to [observer]. Exactly the recorded number of bytes must be copied.
     */
    fun copyFile(relativePath: String, destination: Path, observer: (ByteArray, Int) -> Unit = { _, _ -> }): Result<Unit> = remapResult {
        val state = checkFile(relativePath)
        val buffer = ByteArray(COPY_BUFFER)
        var copied = 0L
        openFile(relativePath).use { input ->
            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > state.size) throw SaveChangedException("${relativePath} grew while copying")
                    observer(buffer, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        if (copied != state.size) throw SaveChangedException("${relativePath} changed while copying")
    }

    /**
     * Copies every directory and file of this snapshot from [root] to the empty directory [target],
     * then walks [root] again. Any added, removed, changed or linked entry fails the copy with
     * [SaveChangedException] or [SaveLinkException]; the caller removes [target] on failure.
     */
    fun copyTo(target: Path, ensureActive: () -> Unit = {}, progress: (Long) -> Unit = {}): Result<Unit> = remapResult {
        Files.createDirectories(target)
        for (directory in directories.sorted()) Files.createDirectories(target.resolve(directory))
        var copied = 0L
        for (relativePath in files.keys.sorted()) {
            ensureActive()
            val state = checkFile(relativePath)
            val destination = target.resolve(relativePath)
            Files.createDirectories(destination.parent)
            val written = openFile(relativePath).use { input ->
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { input.copyTo(it) }
            }
            if (written != state.size) throw SaveChangedException("${relativePath} changed while copying")
            Files.setLastModifiedTime(destination, state.lastModified)
            copied += written
            progress(copied)
        }
        verify().getOrThrow()
    }

    private fun checkFile(relativePath: String): FileState {
        val state = files[relativePath] ?: throw SaveChangedException("${relativePath} is not in the snapshot")
        val attributes = try {
            Files.readAttributes(resolve(relativePath), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            throw SaveChangedException("${relativePath} was removed since the snapshot")
        }
        if (attributes.isSymbolicLink || attributes.isOther) throw SaveLinkException("${relativePath} became a link or special file")
        if (!attributes.isRegularFile || FileState(attributes.size(), attributes.lastModifiedTime()) != state) {
            throw SaveChangedException("${relativePath} changed since the snapshot")
        }
        return state
    }

    private fun openFile(relativePath: String): InputStream =
        Channels.newInputStream(FileChannel.open(resolve(relativePath), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))

    private fun resolve(relativePath: String): Path = root.resolve(relativePath)

    private fun describe(before: Set<String>, after: Set<String>, kind: String): String =
        "A ${kind} was added or removed since the snapshot: ${((before - after) + (after - before)).sorted().take(5)}"

    companion object {
        private const val COPY_BUFFER = 64 * 1024

        /** Walks [root] without following links. */
        fun take(root: Path): Result<SaveSnapshot> = remapResult { walk(root) }

        private fun walk(root: Path): SaveSnapshot {
            val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink || rootAttributes.isOther) {
                throw SaveLinkException("${root} is not a plain directory")
            }
            val files = HashMap<String, FileState>()
            val directories = HashSet<String>()
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || attrs.isOther) throw SaveLinkException("${dir} is a link or special directory")
                    if (dir != root) directories += relative(root, dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relativePath = relative(root, file)
                    if (attrs.isSymbolicLink || attrs.isOther || !attrs.isRegularFile) {
                        throw SaveLinkException("${relativePath} is a link or special file")
                    }
                    if (relativePath != SaveSourceLock.FILE_NAME) {
                        files[relativePath] = FileState(attrs.size(), attrs.lastModifiedTime())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
            })
            return SaveSnapshot(root, files, directories)
        }

        private fun relative(root: Path, path: Path): String =
            root.relativize(path).joinToString("/") { it.toString() }
    }
}
