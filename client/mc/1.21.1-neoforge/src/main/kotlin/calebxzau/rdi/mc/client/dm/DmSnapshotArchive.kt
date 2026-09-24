package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.chunk.storage.RegionFileVersion
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DmSnapshotArchive {
    private const val SECTOR_BYTES = 4096L
    private const val HEADER_BYTES = 8192L
    private const val MAX_RECORD_BYTES = 64L * 1024L * 1024L
    private const val MAX_ARCHIVE_FILES = 1024
    private const val MAX_ARCHIVE_SOURCE_BYTES = 257L * 1024L * 1024L
    private const val NAMESPACE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789_.-"
    private const val PATH_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789/._-"

    data class Column(val dimensionId: String, val chunkX: Int, val chunkZ: Int)

    fun columns(chunks: Collection<SyncChunkKey>): List<Column> = chunks
        .map { Column(it.dimensionId, it.chunkX, it.chunkZ) }
        .distinct()
        .sortedWith(compareBy<Column> { it.dimensionId }.thenBy { it.chunkX }.thenBy { it.chunkZ })

    fun dimensionPath(dimensionId: String): String {
        val separator = dimensionId.indexOf(':')
        require(separator > 0 && separator == dimensionId.lastIndexOf(':')) { "dimensionId must contain exactly one ':'" }
        val namespace = dimensionId.substring(0, separator)
        val path = dimensionId.substring(separator + 1)
        require(namespace != "." && namespace != "..") { "dimension namespace must not be '.' or '..'" }
        require(namespace.all { it in NAMESPACE_CHARS }) { "dimension namespace contains invalid characters" }
        require(path.isNotEmpty() && path.all { it in PATH_CHARS }) { "dimension path contains invalid characters" }
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "dimension path contains an empty or dot segment"
        }
        return "$namespace/$path"
    }

    fun readRecord(directory: Path, x: Int, z: Int): Result<CompoundTag?> = runCatching {
        readRecordInternal(directory, x, z)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(IllegalStateException("Failed to read chunk ($x,$z): ${it.message}", it)) },
    )

    fun prepareTerrain(source: CompoundTag): CompoundTag {
        val copy = source.copy()
        copy.remove("Heightmaps")
        copy.remove("isLightOn")
        val sections = copy.getList("sections", 10)
        for (index in 0 until sections.size) {
            sections.getCompound(index).remove("BlockLight")
            sections.getCompound(index).remove("SkyLight")
        }
        return copy
    }

    fun writeRecord(
        staging: Path,
        column: Column,
        kind: String,
        tag: CompoundTag,
        maxBytesRemaining: Long? = null,
    ): Result<Long> = runCatching {
        require(kind == "terrain" || kind == "entities" || kind == "poi") { "Unsupported snapshot record kind: $kind" }
        require(column.dimensionId.isNotEmpty()) { "dimensionId must not be empty" }
        val recordLimit = minOf(MAX_RECORD_BYTES, maxBytesRemaining ?: MAX_RECORD_BYTES)
        require(recordLimit > 0L) { "Snapshot staging aggregate limit exhausted" }
        val target = staging.resolve("dimensions")
            .resolve(dimensionPath(column.dimensionId))
            .resolve(kind)
            .resolve("${column.chunkX}.${column.chunkZ}.nbt")
            .toAbsolutePath()
            .normalize()
        val stagingRoot = staging.toAbsolutePath().normalize()
        require(target.startsWith(stagingRoot)) { "Snapshot record path escapes staging" }
        Files.createDirectories(target.parent)
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { file ->
            BoundedOutputStream(file, recordLimit).use { bounded ->
                NbtIo.write(tag, bounded.dataOutput)
            }
        }
        Files.size(target)
    }

    /**
     * Packs staged files into [archive].
     *
     * The default bounds are the manual snapshot-test column limits. World snapshots pass
     * their own, larger bounds because a WorldData archive legitimately contains far more
     * members than one batch of columns.
     */
    fun pack(
        staging: Path,
        archive: Path,
        includedRelativePaths: Set<String>? = null,
        maxFiles: Int = MAX_ARCHIVE_FILES,
        maxSourceBytes: Long = MAX_ARCHIVE_SOURCE_BYTES,
    ): Result<Long> = runCatching {
        require(maxFiles > 0) { "Snapshot archive file limit must be positive" }
        require(maxSourceBytes > 0L) { "Snapshot archive byte limit must be positive" }
        require(Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) { "Snapshot staging directory does not exist" }
        val stagingRoot = staging.toAbsolutePath().normalize()
        val archivePath = archive.toAbsolutePath().normalize()
        require(!archivePath.startsWith(stagingRoot)) { "Snapshot archive must be outside staging" }
        val allFiles = Files.walk(stagingRoot).use { paths ->
            paths.filter { path ->
                if (Files.isSymbolicLink(path)) {
                    throw IOException("Snapshot staging contains a symbolic link: $path")
                }
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }.map { path ->
                path to stagingRoot.relativize(path).toString().replace('\\', '/')
            }.sorted { left, right -> left.second.compareTo(right.second) }.toList()
        }
        val files = if (includedRelativePaths == null) {
            allFiles
        } else {
            val normalized = includedRelativePaths.map { relative ->
                require(relative.isNotEmpty() && !Path.of(relative).isAbsolute) { "Snapshot archive path must be relative" }
                val path = relative.replace('\\', '/')
                require(path == relative && path != "." && !path.startsWith("../") && "/../" !in path) {
                    "Snapshot archive path escapes staging"
                }
                path
            }.toSet()
            require(normalized.size == includedRelativePaths.size) { "Snapshot archive paths contain duplicates" }
            allFiles.filter { (_, relative) -> relative in normalized }
        }
        require(files.size <= maxFiles) { "Snapshot staging contains too many files" }
        var sourceBytes = 0L
        for ((file, _) in files) {
            sourceBytes = Math.addExact(sourceBytes, Files.size(file))
            require(sourceBytes <= maxSourceBytes) { "Snapshot staging exceeds ${maxSourceBytes}字节" }
        }
        archivePath.parent?.let(Files::createDirectories)
        Files.newOutputStream(archivePath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            ZipOutputStream(output).use { zip ->
                for ((file, entryName) in files) {
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.newInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        Files.size(archivePath)
    }

    private fun readRecordInternal(directory: Path, x: Int, z: Int): CompoundTag? {
        val regionX = Math.floorDiv(x, 32)
        val regionZ = Math.floorDiv(z, 32)
        val localX = Math.floorMod(x, 32)
        val localZ = Math.floorMod(z, 32)
        val region = directory.resolve("r.$regionX.$regionZ.mca")
        if (!Files.exists(region, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(region, LinkOption.NOFOLLOW_LINKS)) { "Anvil region is not a regular file: $region" }

        RandomAccessFile(region.toFile(), "r").use { file ->
            val fileLength = file.length()
            if (fileLength < HEADER_BYTES) throw IOException("Anvil header is truncated")
            file.seek((localX + localZ * 32L) * 4L)
            val location = file.readInt()
            if (location == 0) return null
            val sectorOffset = (location ushr 8).toLong()
            val sectorCount = location and 0xff
            if (sectorOffset < 2L || sectorCount <= 0) throw IOException("Invalid Anvil location entry")
            val recordStart = Math.multiplyExact(sectorOffset, SECTOR_BYTES)
            val allocatedPayload = sectorCount * SECTOR_BYTES - 4L
            if (recordStart < 0L || Math.addExact(recordStart, 5L) > fileLength) {
                throw IOException("Anvil record header is truncated")
            }

            file.seek(recordStart)
            val length = file.readInt()
            val versionByte = file.readUnsignedByte()
            if (length <= 0 || length.toLong() > allocatedPayload) throw IOException("Invalid Anvil record length: $length")
            val recordEnd = Math.addExact(recordStart, Math.addExact(4L, length.toLong()))
            if (recordEnd > fileLength) throw IOException("Anvil record payload is truncated")
            if ((versionByte and 0x80) != 0) {
                if (length != 1) throw IOException("External Anvil record must have length 1")
                val sidecar = directory.resolve("c.$x.$z.mcc")
                require(Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                    "External Anvil record is missing sidecar: $sidecar"
                }
                require(Files.size(sidecar) <= MAX_RECORD_BYTES) { "Compressed record exceeds 64 MiB" }
                return Files.newInputStream(sidecar).use { input ->
                    decode(input, versionByte and 0x7f)
                }
            }
            if (length <= 1) throw IOException("Invalid Anvil record length: $length")
            val compressionId = versionByte and 0x7f
            val compressedLength = length - 1
            if (compressedLength.toLong() > MAX_RECORD_BYTES) throw IOException("Compressed record exceeds 64 MiB")
            val bytes = ByteArray(compressedLength)
            file.readFully(bytes)
            return decode(bytes.inputStream(), compressionId)
        }
    }

    private fun decode(input: InputStream, compressionId: Int): CompoundTag {
        if (compressionId == 127) throw IOException("Custom Anvil compression is unsupported")
        val version = RegionFileVersion.fromId(compressionId)
            ?: throw IOException("Unknown Anvil compression version: $compressionId")
        val compressed = LimitedInputStream(input, MAX_RECORD_BYTES)
        val decompressed = version.wrap(compressed)
        val bounded = LimitedInputStream(decompressed, MAX_RECORD_BYTES)
        return decompressed.use {
            DataInputStream(bounded).use { data ->
                val tag = NbtIo.read(data, NbtAccounter.create(MAX_RECORD_BYTES))
                if (data.read() != -1) throw IOException("NBT record contains trailing bytes")
                tag
            }
        }
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            if (count >= limit) throw IOException("Stream exceeds ${limit} bytes")
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (count >= limit) throw IOException("Stream exceeds ${limit} bytes")
            val allowed = minOf(length.toLong(), limit - count).toInt()
            val read = delegate.read(buffer, offset, allowed)
            if (read > 0) count += read
            return read
        }

        override fun close() = delegate.close()
    }

    private class BoundedOutputStream(
        output: OutputStream,
        private val limit: Long,
    ) : OutputStream() {
        private val delegate = output
        private var count = 0L
        val dataOutput = java.io.DataOutputStream(this)

        override fun write(value: Int) {
            checkCapacity(1)
            delegate.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkCapacity(length.toLong())
            delegate.write(buffer, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() {
            dataOutput.flush()
            delegate.close()
        }

        private fun checkCapacity(incoming: Long) {
            require(incoming <= limit - count) { "NBT record exceeds 64 MiB" }
        }
    }
}
