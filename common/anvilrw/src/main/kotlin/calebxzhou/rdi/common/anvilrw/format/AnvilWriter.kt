package calebxzhou.rdi.common.anvilrw.format

import calebxzhou.rdi.common.anvilrw.core.Region
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.CHUNKS_PER_REGION
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.SECTOR_SIZE_BYTES
import calebxzhou.rdi.common.anvilrw.util.AnvilUtils
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AnvilWriter(private val anvilFile: File) : Closeable {
    private val raf: RandomAccessFile = RandomAccessFile(anvilFile, "rw")
    var backupEnabled: Boolean = true

    val filePath: String get() = anvilFile.absolutePath

    val canWrite: Boolean
        get() = anvilFile.canWrite() || (!anvilFile.exists() && anvilFile.parentFile.canWrite())

    fun writeRegion(region: Region) {
        writeAnvilFile(region)
    }

    fun validateRegion(region: Region): Boolean {
        return region.chunks.size <= CHUNKS_PER_REGION
    }

    fun validateChunk(chunk: calebxzhou.rdi.common.anvilrw.core.Chunk): Boolean {
        require(!chunk.isExternalPayload) {
            "Cannot write an externally stored chunk payload through AnvilWriter"
        }
        return chunk.index in 0 until CHUNKS_PER_REGION
    }

    fun flush() {
        raf.fd.sync()
    }

    override fun close() {
        raf.close()
    }

    private fun writeAnvilFile(region: Region) {
        region.chunks.forEach(::validateChunk)
        if (backupEnabled && Files.exists(anvilFile.toPath())) {
            val backupFile = File(anvilFile.path + ".bak")
            Files.copy(anvilFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("Created backup of file ${anvilFile.name}")
        }

        var currentSectorOffset = 2 // Start after header (2 sectors = 8KiB)

        for (chunk in region.chunks) {
            if (chunk.dataSize > 0) {
                val fullPayload = chunk.payload.getFullPayload()
                val sectorsNeeded = AnvilUtils.calculateSectorCount(fullPayload.size)
                chunk.location.offset = currentSectorOffset
                chunk.location.sectorCount = sectorsNeeded
                currentSectorOffset += sectorsNeeded
            } else {
                chunk.location.offset = 0
                chunk.location.sectorCount = 0
            }
        }

        for ((i, chunk) in region.chunks.withIndex()) {
            raf.seek(i * 4L)
            val offset = chunk.location.offset
            val sectorCount = chunk.location.sectorCount

            require(offset in 0..0xFFFFFF) { "Sector offset out of range: $offset" }
            require(sectorCount in 0..0xFF) { "Sector count out of range: $sectorCount" }

            raf.writeByte((offset shr 16) and 0xFF)
            raf.writeByte((offset shr 8) and 0xFF)
            raf.writeByte(offset and 0xFF)
            raf.writeByte(chunk.location.sectorCount and 0xFF)

            raf.seek(i * 4L + SECTOR_SIZE_BYTES.toLong())
            raf.writeInt(chunk.timestamp)
        }

        for (chunk in region.chunks) {
            if (chunk.location.offset == 0) continue

            raf.seek(chunk.location.offset * SECTOR_SIZE_BYTES.toLong())
            val fullPayload = chunk.payload.getFullPayload()
            val paddedData = AnvilUtils.padToSectorSize(fullPayload)

            val writeOffset = chunk.location.offset * SECTOR_SIZE_BYTES.toLong()
            if (!AnvilUtils.isSectorAligned(writeOffset)) {
                throw IOException(
                    "Chunk offset is not sector-aligned. Offset: $writeOffset, should be multiple of ${AnvilUtils.SECTOR_SIZE}"
                )
            }

            raf.write(paddedData)
        }

        raf.setLength(currentSectorOffset.toLong() * SECTOR_SIZE_BYTES)
    }
}
