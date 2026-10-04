package calebxzhou.rdi.common.archive

import calebxzhou.rdi.common.util.openChineseZip
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

data class ArchiveReadLimits(
    val maxEntryBytes: Long = Long.MAX_VALUE,
    val maxTotalBytes: Long = Long.MAX_VALUE,
    val maxEntries: Int = Int.MAX_VALUE,
) {
    init {
        require(maxEntryBytes >= 0 && maxTotalBytes >= 0 && maxEntries >= 0)
    }
}

val MODPACK_ARCHIVE_READ_LIMITS = ArchiveReadLimits(
    maxEntryBytes = 2L * 1024 * 1024 * 1024,
    maxTotalBytes = 8L * 1024 * 1024 * 1024,
    maxEntries = 100_000,
)

/** A callback may consume a payload or skip it; skipped bytes still count towards the budget. */
fun forEachArchiveEntryStreaming(
    file: File,
    limits: ArchiveReadLimits = ArchiveReadLimits(),
    beforeChunk: () -> Unit = {},
    onEntry: (StreamingTarEntry, InputStream) -> Unit,
) {
    var entryCount = 0
    var declaredTotal = 0L
    var actualTotal = 0L
    val drainBuffer = ByteArray(128 * 1024)

    fun visit(entry: StreamingTarEntry, source: InputStream) {
        beforeChunk()
        check(!Thread.currentThread().isInterrupted) { "整合包处理已取消" }
        require(entryCount < limits.maxEntries) { "整合包文件数量超过${limits.maxEntries}个限制" }
        entryCount++
        require(!entry.isSymbolicLink && !entry.isHardLink && !entry.isSpecial && !entry.isSparse) {
            "整合包包含不支持的文件类型: ${entry.path}"
        }
        require(entry.size >= 0 && entry.size <= limits.maxEntryBytes) {
            "整合包单文件解压大小超过${limits.maxEntryBytes}字节限制: ${entry.path}"
        }
        require(!entry.isDirectory || entry.size == 0L) { "整合包目录条目大小不正确: ${entry.path}" }
        require(entry.size <= limits.maxTotalBytes - declaredTotal) {
            "整合包总解压大小超过${limits.maxTotalBytes}字节限制"
        }
        declaredTotal += entry.size
        var actualEntry = 0L
        val counted = object : FilterInputStream(source) {
            private fun account(count: Int): Int {
                if (count > 0) {
                    require(count.toLong() <= entry.size - actualEntry) {
                        "整合包文件实际大小与声明不一致: ${entry.path}"
                    }
                    require(count.toLong() <= limits.maxTotalBytes - actualTotal) {
                        "整合包总解压大小超过${limits.maxTotalBytes}字节限制"
                    }
                    actualEntry += count
                    actualTotal += count
                }
                return count
            }

            private fun checkActive() {
                beforeChunk()
                check(!Thread.currentThread().isInterrupted) { "整合包处理已取消" }
            }

            override fun read(): Int {
                checkActive()
                return source.read().also { if (it >= 0) account(1) }
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkActive()
                return account(source.read(bytes, offset, length))
            }

            override fun skip(count: Long): Long {
                var remaining = count.coerceAtLeast(0)
                while (remaining > 0) {
                    val read = read(drainBuffer, 0, minOf(remaining, drainBuffer.size.toLong()).toInt())
                    if (read < 0) break
                    remaining -= read
                }
                return count.coerceAtLeast(0) - remaining
            }

            // Payload lifetime belongs to the archive reader, particularly for TAR's shared stream.
            override fun close() = Unit
        }
        onEntry(entry, counted)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "整合包处理已取消" }
            if (counted.read(drainBuffer) < 0) break
        }
        require(actualEntry == entry.size) { "整合包文件被截断: ${entry.path}" }
    }

    when (file.detectArchiveFormat()) {
        PackArchiveFormat.ZIP -> file.openChineseZip().use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val directory = entry.isDirectory || entry.name.endsWith('/')
                zip.getInputStream(entry).use { input ->
                    visit(
                        StreamingTarEntry(entry.name, directory, entry.size, entry.time, false, false, false, false),
                        input,
                    )
                }
            }
        }
        PackArchiveFormat.TAR_ZST -> TarArchiveInputStream(
            BoundedTarInputStream(ZstdInputStream(file.inputStream().buffered()), limits, beforeChunk),
        ).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                visit(
                    StreamingTarEntry(
                        entry.name, entry.isDirectory, entry.size, entry.modTime.time,
                        entry.isSymbolicLink, entry.isLink,
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO, entry.isSparse,
                    ),
                    input,
                )
            }
        }
    }
}

/** Checks raw headers before Commons Compress buffers GNU/PAX records or recursively follows them. */
private class BoundedTarInputStream(
    private val source: InputStream,
    private val limits: ArchiveReadLimits,
    private val beforeChunk: () -> Unit,
) : InputStream() {
    private val header = ByteArray(512)
    private val skipBuffer = ByteArray(128 * 1024)
    private var headerOffset = header.size
    private var bodyRemaining = 0L
    private var declaredTotal = 0L
    private var metadataTotal = 0L
    private var consecutiveMetadata = 0
    private var headers = 0
    private var metadataHeaders = 0
    private var paxPayload: ByteArray? = null
    private var paxOffset = 0
    private var globalPax = false
    private var globalPaxSize: Long? = null
    private var nextPaxSize: Long? = null

    private fun loadHeader(): Boolean {
        var loaded = 0
        while (loaded < header.size) {
            val read = source.read(header, loaded, header.size - loaded)
            if (read < 0) {
                require(loaded == 0) { "整合包TAR文件头被截断" }
                return false
            }
            loaded += read
        }
        headerOffset = 0
        if (header.all { it == 0.toByte() }) return true
        val entry = TarArchiveEntry(header)
        val metadata = entry.isGNULongNameEntry || entry.isGNULongLinkEntry || entry.isPaxHeader || entry.isGlobalPaxHeader
        val size = if (metadata) entry.size else nextPaxSize ?: globalPaxSize ?: entry.size
        if (!metadata) nextPaxSize = null
        require(size >= 0) { "整合包TAR条目大小不正确" }
        if (metadata) {
            require(metadataHeaders < MAX_METADATA_HEADERS) { "整合包TAR文件头元数据条目过多" }
            metadataHeaders++
            require(size <= MAX_METADATA_ENTRY_BYTES) { "整合包TAR文件头元数据过大" }
            require(size <= MAX_METADATA_TOTAL_BYTES - metadataTotal) { "整合包TAR文件头元数据总量过大" }
            metadataTotal += size
            consecutiveMetadata++
            require(consecutiveMetadata <= MAX_CONSECUTIVE_METADATA) { "整合包TAR文件头元数据层数过多" }
            if (entry.isPaxHeader || entry.isGlobalPaxHeader) {
                paxPayload = ByteArray(size.toInt())
                paxOffset = 0
                globalPax = entry.isGlobalPaxHeader
            }
        } else {
            require(headers < limits.maxEntries) { "整合包文件数量超过${limits.maxEntries}个限制" }
            headers++
            consecutiveMetadata = 0
            require(!entry.isSymbolicLink && !entry.isLink && !entry.isSparse &&
                !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO) {
                "整合包包含不支持的文件类型: ${entry.name}"
            }
            require(size <= limits.maxEntryBytes) { "整合包单文件解压大小超过${limits.maxEntryBytes}字节限制: ${entry.name}" }
            require(size <= limits.maxTotalBytes - declaredTotal) { "整合包总解压大小超过${limits.maxTotalBytes}字节限制" }
            declaredTotal += size
        }
        require(size <= Long.MAX_VALUE - 511) { "整合包TAR条目大小不正确" }
        bodyRemaining = ((size + 511) / 512) * 512
        return true
    }

    private fun parsePaxSize(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val space = (offset until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() }
                ?: throw IllegalArgumentException("整合包PAX文件头不完整")
            val length = String(bytes, offset, space - offset, Charsets.US_ASCII).toIntOrNull()
                ?: throw IllegalArgumentException("整合包PAX文件头长度不正确")
            require(length > space - offset + 1 && length <= bytes.size - offset) { "整合包PAX文件头长度不正确" }
            val record = String(bytes, space + 1, offset + length - space - 2, Charsets.UTF_8)
            val key = record.substringBefore('=')
            require(!key.startsWith("GNU.sparse") && key != "SCHILY.realsize") { "整合包不支持稀疏文件" }
            if (key == "size") {
                val size = record.substringAfter('=', "").toLongOrNull()
                    ?: throw IllegalArgumentException("整合包PAX文件大小不正确")
                require(size >= 0 && size <= limits.maxEntryBytes) { "整合包单文件解压大小超过${limits.maxEntryBytes}字节限制" }
                if (globalPax) globalPaxSize = size else nextPaxSize = size
            }
            offset += length
        }
    }

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return 0
        beforeChunk()
        check(!Thread.currentThread().isInterrupted) { "整合包处理已取消" }
        if (headerOffset < header.size) {
            val count = minOf(length, header.size - headerOffset)
            header.copyInto(bytes, offset, headerOffset, headerOffset + count)
            headerOffset += count
            return count
        }
        if (bodyRemaining > 0) {
            val read = source.read(bytes, offset, minOf(length.toLong(), bodyRemaining).toInt())
            require(read >= 0) { "整合包TAR文件内容被截断" }
            paxPayload?.let { payload ->
                val copied = minOf(read, payload.size - paxOffset)
                bytes.copyInto(payload, paxOffset, offset, offset + copied)
                paxOffset += copied
                if (paxOffset == payload.size) {
                    parsePaxSize(payload)
                    paxPayload = null
                }
            }
            bodyRemaining -= read
            return read
        }
        if (!loadHeader()) return -1
        return read(bytes, offset, length)
    }

    override fun skip(count: Long): Long {
        var remaining = count.coerceAtLeast(0)
        while (remaining > 0) {
            val read = read(skipBuffer, 0, minOf(remaining, skipBuffer.size.toLong()).toInt())
            if (read < 0) break
            remaining -= read
        }
        return count.coerceAtLeast(0) - remaining
    }

    override fun close() = source.close()

    companion object {
        private const val MAX_METADATA_ENTRY_BYTES = 1024L * 1024
        private const val MAX_METADATA_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_CONSECUTIVE_METADATA = 16
        private const val MAX_METADATA_HEADERS = 100_000
    }
}

/** Reject unsafe original paths before any layer prefix is removed. */
fun normalizeSafeArchivePath(rawPath: String): String {
    val path = rawPath.replace('\\', '/')
    require(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) && '\u0000' !in path) {
        "非法整合包文件路径: $rawPath"
    }
    val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
    require(segments.none { it == ".." || ':' in it }) { "非法整合包文件路径: $rawPath" }
    return segments.joinToString("/")
}

fun validateModpackArchive(file: File, beforeChunk: () -> Unit = {}) {
    val files = HashSet<String>()
    val directories = HashSet<String>()
    forEachArchiveEntryStreaming(file, MODPACK_ARCHIVE_READ_LIMITS, beforeChunk) { entry, _ ->
        val path = normalizeSafeArchivePath(entry.path).lowercase(Locale.ROOT)
        require(path.isNotEmpty() || entry.isDirectory) { "整合包包含空文件路径" }
        if (path.isEmpty()) return@forEachArchiveEntryStreaming
        require(path !in files) { "整合包包含重复文件路径: ${entry.path}" }
        if (entry.isDirectory) {
            directories += path
        } else {
            require(path !in directories) { "整合包文件与目录路径冲突: ${entry.path}" }
            files += path
        }
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            require(parent !in files) { "整合包文件与目录路径冲突: ${entry.path}" }
            directories += parent
            parent = parent.substringBeforeLast('/', "")
        }
    }
}
