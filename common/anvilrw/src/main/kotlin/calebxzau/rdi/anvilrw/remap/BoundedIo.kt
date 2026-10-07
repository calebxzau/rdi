package calebxzau.rdi.anvilrw.remap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

/** Region chunk compression types (the low 7 bits of the chunk header byte). */
internal object ChunkCompression {
    const val GZIP = 1
    const val ZLIB = 2
    const val NONE = 3
    const val LZ4 = 4
    const val ZSTD = 8
    const val CUSTOM = 127
}

/**
 * Decompression that never allocates more than a caller-given limit, and the matching compression.
 * Output is collected in fixed-size blocks and copied once into an exact-size array, so the peak
 * memory of one call is about twice the decompressed size.
 */
internal object BoundedIo {
    private const val BLOCK_SIZE = 64 * 1024
    private const val ZSTD_LEVEL = 3

    fun readAtMost(input: InputStream, maxBytes: Int, what: String): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val blocks = ArrayList<ByteArray>()
        var total = 0L
        var lastBlockSize = 0
        while (true) {
            val block = ByteArray(BLOCK_SIZE)
            var filled = 0
            var endOfStream = false
            while (filled < BLOCK_SIZE) {
                val read = input.read(block, filled, BLOCK_SIZE - filled)
                if (read < 0) {
                    endOfStream = true
                    break
                }
                filled += read
                total += read
                if (total > maxBytes) {
                    throw RemapLimitExceededException("${what} exceeds ${maxBytes} bytes")
                }
            }
            if (filled > 0) {
                blocks += block
                lastBlockSize = filled
            }
            if (endOfStream) break
        }
        val result = ByteArray(total.toInt())
        var position = 0
        blocks.forEachIndexed { index, block ->
            val size = if (index == blocks.lastIndex) lastBlockSize else BLOCK_SIZE
            System.arraycopy(block, 0, result, position, size)
            position += size
        }
        return result
    }

    fun gunzip(data: ByteArray, maxBytes: Int, what: String): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { readAtMost(it, maxBytes, what) }

    fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size / 4 + 64)
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    /** Decompresses a region chunk payload of the given [type]. */
    fun decompressChunk(type: Int, data: ByteArray, maxBytes: Int, what: String): ByteArray =
        when (type) {
            ChunkCompression.GZIP -> GZIPInputStream(ByteArrayInputStream(data)).use { readAtMost(it, maxBytes, what) }
            ChunkCompression.ZLIB -> InflaterInputStream(ByteArrayInputStream(data)).use { readAtMost(it, maxBytes, what) }
            ChunkCompression.NONE -> {
                if (data.size > maxBytes) throw RemapLimitExceededException("${what} exceeds ${maxBytes} bytes")
                data
            }
            ChunkCompression.ZSTD -> withZstd {
                com.github.luben.zstd.ZstdInputStream(
                    ByteArrayInputStream(data),
                    com.github.luben.zstd.RecyclingBufferPool.INSTANCE,
                ).use { readAtMost(it, maxBytes, what) }
            }
            else -> throw IOException("Unsupported chunk compression type ${type}")
        }

    /** Compresses chunk NBT with the given [type], so a rewritten chunk keeps its compression. */
    fun compressChunk(type: Int, data: ByteArray): ByteArray =
        when (type) {
            ChunkCompression.GZIP -> gzip(data)
            ChunkCompression.ZLIB -> {
                val output = ByteArrayOutputStream(data.size / 4 + 64)
                DeflaterOutputStream(output).use { it.write(data) }
                output.toByteArray()
            }
            ChunkCompression.NONE -> data
            ChunkCompression.ZSTD -> withZstd { com.github.luben.zstd.Zstd.compress(data, ZSTD_LEVEL) }
            else -> throw IOException("Unsupported chunk compression type ${type}")
        }

    /** zstd-jni is a compile-only dependency of this module; report its absence as an I/O failure. */
    private inline fun <T> withZstd(block: () -> T): T =
        try {
            block()
        } catch (missing: LinkageError) {
            throw IOException("zstd support is not available", missing)
        }
}
