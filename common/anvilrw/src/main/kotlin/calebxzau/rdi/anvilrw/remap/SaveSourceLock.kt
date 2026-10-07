package calebxzau.rdi.anvilrw.remap

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Holds a save's `session.lock` the way Minecraft's `DirectoryLock` does, so Minecraft cannot open the
 * save while it is copied, and a save that Minecraft has open is detected.
 *
 * The file is opened with `CREATE, WRITE` and is never truncated or written. Close the lock on cancel,
 * on failure and after the copy; if the process dies, the OS releases it.
 */
class SaveSourceLock private constructor(
    val world: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        const val FILE_NAME = "session.lock"

        fun acquire(world: Path): Result<SaveSourceLock> = remapResult {
            val file = world.resolve(FILE_NAME)
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw SaveLinkException("${file} is not a regular file")
            }
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val lock = try {
                channel.tryLock()
            } catch (held: OverlappingFileLockException) {
                channel.close()
                throw SaveInUseException("${world} is already locked by this process", held)
            } catch (error: IOException) {
                channel.close()
                throw SaveInUseException("${world} is locked by another program", error)
            }
            if (lock == null) {
                channel.close()
                throw SaveInUseException("${world} is locked by another program")
            }
            SaveSourceLock(world, channel, lock)
        }
    }
}
