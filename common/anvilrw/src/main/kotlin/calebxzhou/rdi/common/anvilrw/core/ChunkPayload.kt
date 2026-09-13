package calebxzhou.rdi.common.anvilrw.core

import calebxzhou.rdi.common.anvilrw.ChunkTooLargeException
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.MAX_CHUNK_SIZE_BYTES
import calebxzhou.rdi.common.anvilrw.util.AnvilUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.github.luben.zstd.RecyclingBufferPool
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

class ChunkPayload(payload: ByteArray, private val isExternalPayload: Boolean = false) {
    val compressionType: Byte
    var payloadLength: Int
        private set
    var length: Int
        private set
    var compressedData: ByteArray
        private set

    init {
        if (!isExternalPayload && payload.size > MAX_CHUNK_SIZE_BYTES) {
            throw ChunkTooLargeException(
                "Chunk payload exceeds maximum size. Size: ${payload.size} bytes, Maximum: $MAX_CHUNK_SIZE_BYTES bytes"
            )
        }
        payloadLength = payload.size
        if (payloadLength == 0) {
            length = 0
            compressionType = 3
            compressedData = ByteArray(0)
        } else {
            if (payloadLength < 5) {
                throw java.io.IOException(
                    "Invalid chunk payload: expected at least 5 bytes (length + compression), got $payloadLength bytes"
                )
            }
            val lengthField = AnvilUtils.readInt(payload.copyOfRange(0, 4), ByteOrder.BIG_ENDIAN)
            if (lengthField <= 0 || lengthField > payloadLength - 4) {
                throw java.io.IOException(
                    "Invalid chunk length field: $lengthField. Payload size: $payloadLength bytes"
                )
            }
            compressionType = payload[4]
            val compressedLength = lengthField - 1
            if (compressedLength < 0 || compressedLength > payloadLength - 5) {
                throw java.io.IOException(
                    "Invalid compressed data length: $compressedLength. Payload size: $payloadLength bytes"
                )
            }
            length = lengthField
            compressedData = payload.copyOfRange(5, 5 + compressedLength)
        }
    }

    fun compressAndSetData(data: ByteArray) {
        if (data.size > MAX_CHUNK_SIZE_BYTES) {
            throw ChunkTooLargeException(
                "Uncompressed chunk data exceeds maximum size. Size: ${data.size} bytes, Maximum: $MAX_CHUNK_SIZE_BYTES bytes"
            )
        }
        val buffer = compressData(data, compressionType)
        val totalPayloadSize = buffer.size + 4 + 1
        if (totalPayloadSize > MAX_CHUNK_SIZE_BYTES) {
            throw ChunkTooLargeException(
                "Compressed chunk payload exceeds maximum size. Size: $totalPayloadSize bytes, Maximum: $MAX_CHUNK_SIZE_BYTES bytes"
            )
        }
        compressedData = buffer
        length = buffer.size + 1
        payloadLength = AnvilUtils.calculateSectorCount(4 + length) * AnvilUtils.SECTOR_SIZE
    }

    fun getFullPayload(): ByteArray {
        val lengthField = compressedData.size + 1
        val buffer = ByteBuffer.allocate(4 + lengthField).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(lengthField)
        buffer.put(compressionType)
        buffer.put(compressedData)
        return AnvilUtils.padToSectorSize(buffer.array())
    }

    fun getDecompressedData(): ByteArray = decompressData(compressedData, compressionType)

    fun releaseCompressedData() {
        compressedData = ByteArray(0)
        payloadLength = 0
        length = 0
    }

    fun decompressData(data: ByteArray, compressionType: Byte): ByteArray {
        return when (compressionType.toInt()) {
            1 -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readAllBytes() }
            2 -> InflaterInputStream(ByteArrayInputStream(data)).use { it.readAllBytes() }
            3 -> data
            4 -> throw java.io.IOException("LZ4 compression (type 4) is not yet implemented")
            8 -> decompressZstd(data)
            127 -> throw java.io.IOException("Custom compression (type 127) is not supported")
            else -> throw java.io.IOException(
                "Unknown compression type: $compressionType. Supported types: 1 (GZip), 2 (Zlib), 3 (Uncompressed), 8 (Zstd)"
            )
        }
    }

    fun compressData(data: ByteArray, compressionType: Byte): ByteArray {
        return when (compressionType.toInt()) {
            1 -> {
                val byteStream = ByteArrayOutputStream()
                GZIPOutputStream(byteStream).use { it.write(data) }
                byteStream.toByteArray()
            }
            2 -> {
                val byteStream = ByteArrayOutputStream()
                DeflaterOutputStream(byteStream).use { it.write(data) }
                byteStream.toByteArray()
            }
            3 -> data
            4 -> throw java.io.IOException("LZ4 compression (type 4) is not yet implemented")
            127 -> throw java.io.IOException("Custom compression (type 127) is not supported")
            else -> throw java.io.IOException(
                "Unknown compression type: $compressionType. Supported types: 1 (GZip), 2 (Zlib), 3 (Uncompressed)"
            )
        }
    }

    override fun toString(): String =
        "ChunkPayload{payloadLength=$payloadLength, length=$length, compressionType=$compressionType, chunkData (Bytes)=${compressedData.size}}"

    companion object {
        const val MAX_ZSTD_COMPRESSED_BYTES: Int = 64 * 1024 * 1024
        const val MAX_ZSTD_DECOMPRESSED_BYTES: Int = 256 * 1024 * 1024

        internal fun decompressZstd(data: ByteArray, maxBytes: Int = MAX_ZSTD_DECOMPRESSED_BYTES): ByteArray {
            require(maxBytes >= 0) { "maxBytes must not be negative" }
            return com.github.luben.zstd.ZstdInputStream(
                ByteArrayInputStream(data),
                RecyclingBufferPool.INSTANCE
            ).use { input ->
                readAtMost(input, maxBytes)
            }
        }

        private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val allowed = maxBytes - total
                val count = input.read(buffer, 0, minOf(buffer.size, allowed + 1))
                if (count < 0) return output.toByteArray()
                if (count > allowed) {
                    throw java.io.IOException("Zstd decompressed data exceeds maximum size of $maxBytes bytes")
                }
                output.write(buffer, 0, count)
                total += count
            }
        }
    }
}
