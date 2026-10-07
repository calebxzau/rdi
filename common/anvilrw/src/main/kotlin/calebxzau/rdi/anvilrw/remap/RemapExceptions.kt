package calebxzau.rdi.anvilrw.remap

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** NBT bytes are not a structurally valid document. [offset] is the absolute offset in the input. */
class NbtFormatException(message: String, val offset: Int) : IOException("${message} (offset ${offset})")

/** Renaming a compound key would produce two equal keys in the same compound. */
class NbtKeyConflictException(message: String) : IOException(message)

/** Data exceeded one of the [RemapLimits]. */
class RemapLimitExceededException(message: String) : IOException(message)

/** A region file or one of its chunks cannot be read strictly. */
class RegionFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A file handed to the text rewriter is not strictly valid UTF-8, or contains NUL bytes. */
class NotUtf8TextException(message: String) : IOException(message)

/** A dimension ID that is not a valid resource location or cannot be used as a save folder. */
class InvalidDimensionIdException(message: String) : IOException(message)

/** The save is open in Minecraft or another program that holds its `session.lock`. */
class SaveInUseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The save contains a symbolic link, junction or other special file. */
class SaveLinkException(message: String) : IOException(message)

/** The save changed after its snapshot was taken. */
class SaveChangedException(message: String) : IOException(message)

/** `data/rdi_sync_chunks.dat` is missing required fields, has invalid entries or too many entries. */
class SyncChunkListException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Renaming player UUIDs in paths would write two files to the same output path. */
class PathConflictException(message: String) : IOException(message)

/**
 * Like [runCatching], but never turns cancellation into a failure value: a [CancellationException]
 * thrown by an `ensureActive` hook is rethrown so callers can stop normally.
 */
internal inline fun <T> remapResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
