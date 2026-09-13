package calebxzhou.rdi.common.anvilrw.format

import calebxzhou.rdi.common.anvilrw.ChunkTooLargeException
import calebxzhou.rdi.common.anvilrw.core.Chunk
import calebxzhou.rdi.common.anvilrw.core.ChunkPayload
import calebxzhou.rdi.common.anvilrw.core.Location
import calebxzhou.rdi.common.anvilrw.core.Region
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.CHUNKS_PER_REGION
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.MAX_CHUNK_SIZE_BYTES
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.SECTOR_SIZE_BYTES
import calebxzhou.rdi.common.anvilrw.util.AnvilUtils
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.nio.file.Files

class AnvilReader(private val anvilFile: File) : Closeable {
    private val raf: RandomAccessFile

    init {
        validateFileFormat(anvilFile)
        raf = RandomAccessFile(anvilFile, "r")
        validateMcaHeader()
    }

    fun readRegion(): Region {
        try {
            val coordinates = AnvilUtils.parseRegionFilename(anvilFile.name)
            return readRegionWithValidation(coordinates[0], coordinates[1])
        } finally {
            raf.close()
        }
    }

    fun readChunk(chunkX: Int, chunkZ: Int): Chunk? {
        try {
            val chunkIndex = AnvilUtils.chunkCoordinatesToIndex(chunkX, chunkZ)
            val location = readChunkLocation(chunkIndex)
            val timestamp = readChunkTimestamp(chunkIndex)
            if (location.offset == 0) return null
            val chunkData = readAndValidateChunkData(location, chunkX, chunkZ)
            return Chunk(chunkIndex, location, timestamp, chunkData.payload, chunkData.external)
        } catch (e: Exception) {
            throw IOException("Critical failure reading chunk at coordinates ($chunkX,$chunkZ): ${e.message}", e)
        }
    }

    val regionCoordinates: IntArray get() = AnvilUtils.parseRegionFilename(anvilFile.name)
    val filePath: String get() = anvilFile.absolutePath
    val canRead: Boolean get() = anvilFile.exists() && anvilFile.canRead()
    val fileSize: Long get() = anvilFile.length()
    val fileFormat: String get() = "mca"

    override fun close() {
        raf.close()
    }

    private fun validateMcaHeader() {
        val fileSize = raf.length()
        val headerSize = SECTOR_SIZE_BYTES * 2
        if (fileSize < headerSize) {
            throw IOException("Invalid MCA file: file size $fileSize bytes is less than required header size $headerSize bytes")
        }
        val trailingBytes = fileSize % SECTOR_SIZE_BYTES
        if (trailingBytes != 0L) {
            // Some third-party tools may leave non-sector trailing bytes. Keep reading using only aligned sectors.
            System.err.printf(
                "Warning: MCA file %s has %d trailing non-sector bytes (size=%d). Continuing with aligned sectors only.%n",
                anvilFile.name,
                trailingBytes,
                fileSize
            )
        }
        val maxReasonableFileSize = 4L * 1024 * 1024 * 1024
        if (fileSize > maxReasonableFileSize) {
            throw IOException("File size $fileSize bytes exceeds reasonable limit $maxReasonableFileSize bytes")
        }
    }

    private fun readRegionWithValidation(regionX: Int, regionZ: Int): Region {
        val locations = readAndValidateLocationTable()
        val timestamps = readTimestampTable()
        val chunks = mutableListOf<Chunk>()
        var corruptChunkCount = 0

        for (i in 0 until CHUNKS_PER_REGION) {
            val location = locations[i]
            val timestamp = timestamps[i]
            if (location.offset == 0) {
                chunks.add(Chunk(i, Location.createEmpty(), 0, ByteArray(0)))
            } else {
                try {
                    val chunkX = regionX * 32 + (i % 32)
                    val chunkZ = regionZ * 32 + (i / 32)
                    val chunkData = readAndValidateChunkData(location, chunkX, chunkZ)
                    chunks.add(Chunk(i, location, timestamp, chunkData.payload, chunkData.external))
                } catch (e: Exception) {
                    corruptChunkCount++
                    val chunkX = regionX * 32 + (i % 32)
                    val chunkZ = regionZ * 32 + (i / 32)
                    System.err.printf(
                        "Warning: Corrupt chunk at index %d (chunk coordinates %d,%d): %s%n",
                        i, chunkX, chunkZ, e.message
                    )
                    chunks.add(Chunk(i, Location.createEmpty(), 0, ByteArray(0)))
                }
            }
        }

        if (corruptChunkCount > 0) {
            System.err.printf("Region (%d,%d): Found %d corrupt chunks, replaced with empty chunks%n",
                regionX, regionZ, corruptChunkCount)
        }

        return Region(regionX, regionZ, chunks)
    }

    private fun readAndValidateLocationTable(): Array<Location> {
        val locations = Array(CHUNKS_PER_REGION) { Location.createEmpty() }
        val fileSize = raf.length()
        raf.seek(0)

        for (i in 0 until CHUNKS_PER_REGION) {
            val locationBytes = ByteArray(4)
            val bytesRead = raf.read(locationBytes)
            if (bytesRead != 4) {
                throw IOException("Failed to read location entry $i: expected 4 bytes, got $bytesRead")
            }
            locations[i] = parseAndValidateLocation(locationBytes, i, fileSize)
        }
        return locations
    }

    private fun parseAndValidateLocation(locationBytes: ByteArray, chunkIndex: Int, fileSize: Long): Location {
        val offset = AnvilUtils.readInt(locationBytes.copyOfRange(0, 3), ByteOrder.BIG_ENDIAN)
        val sectorCount = AnvilUtils.readInt(locationBytes.copyOfRange(3, 4), ByteOrder.BIG_ENDIAN)

        if (offset != 0) {
            if (offset < 2) {
                throw IOException("Invalid chunk $chunkIndex: sector offset $offset is less than minimum 2 (sectors 0-1 reserved for header)")
            }
            if (sectorCount <= 0 || sectorCount > 255) {
                throw IOException("Invalid chunk $chunkIndex: sector count $sectorCount must be between 1 and 255 (MCA format limit)")
            }
            if (offset > 0xFFFFFF) {
                throw IOException("Invalid chunk $chunkIndex: sector offset $offset exceeds 24-bit limit ${0xFFFFFF} (MCA format constraint)")
            }
            if (!AnvilUtils.isValidChunkPlacement(offset, sectorCount, fileSize)) {
                throw IOException("Invalid chunk $chunkIndex: placement at offset $offset with $sectorCount sectors exceeds file size $fileSize")
            }
            val chunkStart = offset.toLong() * SECTOR_SIZE_BYTES
            if (chunkStart % SECTOR_SIZE_BYTES != 0L) {
                throw IOException("Invalid chunk $chunkIndex: start position $chunkStart is not sector-aligned")
            }
        } else if (sectorCount != 0) {
            throw IOException("Invalid chunk $chunkIndex: empty chunk (offset=0) must have sector count 0, got $sectorCount")
        }

        return Location(locationBytes)
    }

    private fun readTimestampTable(): IntArray {
        val timestamps = IntArray(CHUNKS_PER_REGION)
        raf.seek(SECTOR_SIZE_BYTES.toLong())

        for (i in 0 until CHUNKS_PER_REGION) {
            val timestampBytes = ByteArray(4)
            val bytesRead = raf.read(timestampBytes)
            if (bytesRead != 4) {
                throw IOException("Failed to read timestamp entry $i: expected 4 bytes, got $bytesRead")
            }
            timestamps[i] = AnvilUtils.readInt(timestampBytes, ByteOrder.BIG_ENDIAN)
            if (timestamps[i] < 0) {
                throw IOException("Invalid timestamp for chunk $i: ${timestamps[i]} (timestamps cannot be negative)")
            }
            val currentEpoch = Instant.now().epochSecond
            val maxFutureEpoch = currentEpoch + (100L * 365 * 24 * 60 * 60)
            if (timestamps[i] > maxFutureEpoch) {
                System.err.printf("Warning: chunk %d has timestamp %d which is more than 100 years in the future%n",
                    i, timestamps[i])
            }
        }
        return timestamps
    }

    private fun readChunkLocation(chunkIndex: Int): Location {
        require(chunkIndex in 0 until CHUNKS_PER_REGION) {
            "Chunk index $chunkIndex is out of range (0-${CHUNKS_PER_REGION - 1})"
        }
        raf.seek(chunkIndex * 4L)
        val locationBytes = ByteArray(4)
        val bytesRead = raf.read(locationBytes)
        if (bytesRead != 4) {
            throw IOException("Failed to read location for chunk $chunkIndex: expected 4 bytes, got $bytesRead")
        }
        return parseAndValidateLocation(locationBytes, chunkIndex, raf.length())
    }

    private fun readChunkTimestamp(chunkIndex: Int): Int {
        require(chunkIndex in 0 until CHUNKS_PER_REGION) {
            "Chunk index $chunkIndex is out of range (0-${CHUNKS_PER_REGION - 1})"
        }
        raf.seek(SECTOR_SIZE_BYTES + chunkIndex * 4L)
        val timestampBytes = ByteArray(4)
        val bytesRead = raf.read(timestampBytes)
        if (bytesRead != 4) {
            throw IOException("Failed to read timestamp for chunk $chunkIndex: expected 4 bytes, got $bytesRead")
        }
        return AnvilUtils.readInt(timestampBytes, ByteOrder.BIG_ENDIAN)
    }

    private data class ChunkReadResult(val payload: ByteArray, val external: Boolean)

    private fun readAndValidateChunkData(location: Location, chunkX: Int, chunkZ: Int): ChunkReadResult {
        val offset = location.offset
        val sectorCount = location.sectorCount
        val filePosition = offset.toLong() * AnvilUtils.SECTOR_SIZE
        val sectorDataSize = sectorCount * AnvilUtils.SECTOR_SIZE

        raf.seek(filePosition)
        val sectorData = ByteArray(sectorDataSize)
        val bytesRead = raf.read(sectorData)
        if (bytesRead != sectorDataSize) {
            throw IOException("Failed to read chunk sector data: expected $sectorDataSize bytes, got $bytesRead")
        }
        if (sectorData.size < 4) {
            throw IOException("Chunk data too small: missing length field")
        }

        val chunkLength = AnvilUtils.readInt(sectorData.copyOfRange(0, 4), ByteOrder.BIG_ENDIAN)
        if (chunkLength <= 0) {
            throw IOException("Invalid chunk length: $chunkLength (must be positive)")
        }
        if (chunkLength > MAX_CHUNK_SIZE_BYTES) {
            throw ChunkTooLargeException("Chunk length $chunkLength exceeds maximum size $MAX_CHUNK_SIZE_BYTES bytes")
        }

        val compression = sectorData[4].toInt() and 0xff
        if ((compression and 0x80) != 0) {
            if (chunkLength != 1) {
                throw IOException("External chunk ($chunkX,$chunkZ) must have length field 1, got $chunkLength")
            }
            val compressionType = compression and 0x7f
            if (compressionType != 8) {
                throw IOException("Unsupported external chunk compression type $compressionType at ($chunkX,$chunkZ)")
            }
            val sidecar = anvilFile.toPath().resolveSibling("c.$chunkX.$chunkZ.mcc")
            if (!Files.isRegularFile(sidecar) || !Files.isReadable(sidecar)) {
                throw IOException("External chunk sidecar is not a readable regular file: $sidecar")
            }
            val sidecarSize = Files.size(sidecar)
            if (sidecarSize <= 0L) {
                throw IOException("External chunk sidecar is empty: $sidecar")
            }
            if (sidecarSize > ChunkPayload.MAX_ZSTD_COMPRESSED_BYTES) {
                throw IOException("External chunk sidecar exceeds ${ChunkPayload.MAX_ZSTD_COMPRESSED_BYTES} bytes: $sidecar")
            }
            val compressed = Files.readAllBytes(sidecar)
            if (compressed.size.toLong() != sidecarSize) {
                throw IOException("External chunk sidecar changed while reading: $sidecar")
            }
            val normalized = ByteBuffer.allocate(5 + compressed.size).order(ByteOrder.BIG_ENDIAN)
                .putInt(compressed.size + 1)
                .put(8.toByte())
                .put(compressed)
                .array()
            return ChunkReadResult(normalized, true)
        }

        val totalChunkSize = chunkLength + 4
        if (totalChunkSize > sectorDataSize) {
            throw IOException("Chunk length field $chunkLength (+4 for length field = $totalChunkSize) exceeds available sector data $sectorDataSize")
        }

        val expectedSectorCount = AnvilUtils.calculateSectorCount(totalChunkSize)
        if (sectorCount < expectedSectorCount) {
            throw IOException("Sector count too small: location specifies $sectorCount sectors, but chunk size $totalChunkSize requires at least $expectedSectorCount sectors")
        }

        return ChunkReadResult(sectorData, false)
    }

    private fun validateFileFormat(file: File) {
        val filename = file.name.lowercase()
        require(filename.endsWith(FileFormat.ANVIL.extension)) {
            "Unsupported file format. Currently only .mca (Anvil) files are supported, got: $filename"
        }
    }
}
