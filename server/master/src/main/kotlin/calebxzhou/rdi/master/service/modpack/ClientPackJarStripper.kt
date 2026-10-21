package calebxzhou.rdi.master.service.modpack

import calebxzau.rdi.common.logging.Loggers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Removes region files (`.mca`, `.mcc`) from jars shipped in the client pack, including jars nested inside them
 * (e.g. `META-INF/jarjar`). The client never loads a world from a mod jar, and some mods ship whole worlds.
 * Jars without region files are left byte-identical.
 */
internal object ClientPackJarStripper {
    private val lgr by Loggers

    /** A larger nested jar is kept as it is. */
    const val MAX_NESTED_JAR_BYTES = 256L * 1024 * 1024
    /** Jars nested deeper than this are kept as they are. */
    private const val MAX_NESTED_DEPTH = 4

    fun isRegionFile(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".mca") || lower.endsWith(".mcc")
    }

    fun isJar(path: String): Boolean = path.lowercase(Locale.ROOT).endsWith(".jar")

    /**
     * Writes [source] without its region files to [target] and returns how many were removed. Returns 0 and leaves
     * [target] untouched when there is nothing to remove. Fails when [source] is not a readable jar.
     */
    fun strip(source: File, target: File): Result<Int> = runCatching {
        ZipFile(source).use { zip ->
            val needed = zip.entries().asSequence().any { entry ->
                isRegionFile(entry.name) || (isNestedJar(entry, 1) && nestedNeedsStrip(entry, 1) { zip.getInputStream(entry) })
            }
            if (!needed) return@runCatching 0
            var removed = 0
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                zip.comment?.let(output::setComment)
                for (entry in zip.entries()) {
                    if (isRegionFile(entry.name)) {
                        removed++
                        continue
                    }
                    val nested = if (isNestedJar(entry, 1)) stripNested(entry, 1) { zip.getInputStream(entry) } else null
                    if (nested?.stripped != null) {
                        removed += nested.removed
                        writeBytes(output, entry, nested.stripped)
                    } else {
                        // ZipFile reopens the entry, so a nested jar that was read but kept is copied from the file.
                        zip.getInputStream(entry).use { copyEntry(output, entry, it) }
                    }
                }
            }
            removed
        }
    }

    /** [original] is the nested jar as read; [stripped] is null when it is kept as it is. */
    private class Nested(val original: ByteArray?, val stripped: ByteArray?, val removed: Int)

    private fun isNestedJar(entry: ZipEntry, depth: Int): Boolean =
        !entry.isDirectory && isJar(entry.name) && depth <= MAX_NESTED_DEPTH

    /** Reads the nested jar once; a jar that cannot be read or is too large is kept as it is. */
    private fun nestedNeedsStrip(entry: ZipEntry, depth: Int, open: () -> InputStream): Boolean {
        val bytes = readNested(entry, open) ?: return false
        return runCatching { needsStrip(bytes, depth) }.getOrElse { exception ->
            lgr.warn(exception) { "客户端包内嵌jar无法读取，保持原样: ${entry.name}" }
            false
        }
    }

    private fun needsStrip(jar: ByteArray, depth: Int): Boolean =
        ZipInputStream(ByteArrayInputStream(jar)).use { input ->
            generateSequence { input.nextEntry }.any { entry ->
                isRegionFile(entry.name) ||
                    (isNestedJar(entry, depth + 1) && nestedNeedsStrip(entry, depth + 1) { NonClosingInputStream(input) })
            }
        }

    private fun stripNested(entry: ZipEntry, depth: Int, open: () -> InputStream): Nested {
        val bytes = readNested(entry, open) ?: return Nested(null, null, 0)
        return runCatching {
            if (!needsStrip(bytes, depth)) return@runCatching Nested(bytes, null, 0)
            var removed = 0
            val buffer = ByteArrayOutputStream(bytes.size)
            ZipOutputStream(buffer).use { output ->
                ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                    for (inner in generateSequence { input.nextEntry }) {
                        if (isRegionFile(inner.name)) {
                            removed++
                            continue
                        }
                        if (!isNestedJar(inner, depth + 1)) {
                            copyEntry(output, inner, input)
                            continue
                        }
                        val nested = stripNested(inner, depth + 1) { NonClosingInputStream(input) }
                        // A ZipInputStream entry can be read only once, so a kept nested jar is written from its bytes.
                        val kept = nested.stripped ?: nested.original
                            ?: throw IOException("内嵌jar超过${MAX_NESTED_JAR_BYTES}字节: ${inner.name}")
                        removed += nested.removed
                        writeBytes(output, inner, kept)
                    }
                }
            }
            Nested(bytes, buffer.toByteArray(), removed)
        }.getOrElse { exception ->
            lgr.warn(exception) { "客户端包内嵌jar无法处理，保持原样: ${entry.name}" }
            Nested(bytes, null, 0)
        }
    }

    /** Null when the nested jar exceeds [MAX_NESTED_JAR_BYTES]. */
    private fun readNested(entry: ZipEntry, open: () -> InputStream): ByteArray? {
        if (entry.size > MAX_NESTED_JAR_BYTES) {
            lgr.warn { "客户端包内嵌jar超过${MAX_NESTED_JAR_BYTES}字节，保持原样: ${entry.name}" }
            return null
        }
        val output = ByteArrayOutputStream(if (entry.size > 0) entry.size.toInt() else 8192)
        open().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_NESTED_JAR_BYTES) {
                    lgr.warn { "客户端包内嵌jar超过${MAX_NESTED_JAR_BYTES}字节，保持原样: ${entry.name}" }
                    return null
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun newEntry(source: ZipEntry): ZipEntry = ZipEntry(source.name).apply {
        method = source.method
        time = source.time
        source.extra?.let { extra = it }
        source.comment?.let { comment = it }
    }

    /** Recompresses deflated entries; stored entries keep their size and CRC. */
    private fun copyEntry(output: ZipOutputStream, source: ZipEntry, input: InputStream) {
        if (source.method == ZipEntry.STORED && (source.crc < 0 || source.size < 0)) {
            writeBytes(output, source, input.readBytes())
            return
        }
        val target = newEntry(source)
        if (source.method == ZipEntry.STORED) {
            target.size = source.size
            target.compressedSize = source.size
            target.crc = source.crc
        }
        output.putNextEntry(target)
        input.copyTo(output)
        output.closeEntry()
    }

    private fun writeBytes(output: ZipOutputStream, source: ZipEntry, bytes: ByteArray) {
        val target = newEntry(source)
        if (source.method == ZipEntry.STORED) {
            target.size = bytes.size.toLong()
            target.compressedSize = bytes.size.toLong()
            target.crc = CRC32().apply { update(bytes) }.value
        }
        output.putNextEntry(target)
        output.write(bytes)
        output.closeEntry()
    }

    /** Lets a callee read the current entry of a [ZipInputStream] without closing the stream. */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
