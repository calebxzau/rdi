@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DmCheckpointFile(val path: String, val bytes: Long, val sha1: String)
data class DmCheckpointChunk(val region: String, val index: Int, val timestamp: Int, val length: Long, val sha1: String)
data class DmCheckpointClear(val region: String, val index: Int, val timestamp: Int)
data class DmCheckpointMetadata(
    val formatVersion: Int = 6,
    val kind: String = "world-checkpoint",
    val hostId: UUID,
    val sessionId: UUID,
    val cycleId: UUID,
    val sequence: Long,
    val capturedAt: String = Instant.now().toString(),
    val baselineComplete: Boolean,
    val files: List<DmCheckpointFile>,
    val removedFiles: List<String>,
    val directories: List<String>,
    val chunks: List<DmCheckpointChunk>,
    val clears: List<DmCheckpointClear>,
)

object DmWorldCheckpointArchive {
    data class Request(
        val hostId: UUID,
        val sessionId: UUID,
        val cycleId: UUID,
        val sequence: Long,
        val baselineComplete: Boolean,
        val worldRoot: Path,
        val changedFiles: List<String>,
        val removedFiles: List<String>,
        val directories: List<String>,
        val regionChanges: List<DmRegionChange>,
        val capturedAt: String = Instant.now().toString(),
    )

    data class Output(val path: Path, val sha1: String, val bytes: Long, val metadata: DmCheckpointMetadata)

    fun write(request: Request, output: Path): Result<Output> = runCatching {
        Files.createDirectories(output.parent)
        val files = request.changedFiles.distinct().sorted().map { relative ->
            val path = request.worldRoot.resolve(relative)
            require(Files.isRegularFile(path)) { "世界文件不存在：$relative" }
            DmCheckpointFile(relative, Files.size(path), sha1(path))
        }
        val chunks = request.regionChanges.filter { it.bytes != null }.sortedWith(compareBy({ it.key.region }, { it.key.index })).map {
            val bytes = it.bytes ?: error("缺少区块数据")
            DmCheckpointChunk(it.key.region, it.key.index, it.timestamp, bytes.size.toLong(), sha1(bytes))
        }
        val clears = request.regionChanges.filter { it.bytes == null }.sortedWith(compareBy({ it.key.region }, { it.key.index })).map {
            DmCheckpointClear(it.key.region, it.key.index, it.timestamp)
        }
        val metadata = DmCheckpointMetadata(
            hostId = request.hostId,
            sessionId = request.sessionId,
            cycleId = request.cycleId,
            sequence = request.sequence,
            capturedAt = request.capturedAt,
            baselineComplete = request.baselineComplete,
            files = files,
            removedFiles = request.removedFiles.sorted(),
            directories = request.directories.sorted(),
            chunks = chunks,
            clears = clears,
        )
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            val metadataBytes = GsonBuilder().create().toJson(metadata).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadataBytes)
            zip.closeEntry()
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("world/${file.path}"))
                Files.newInputStream(request.worldRoot.resolve(file.path)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            request.regionChanges.filter { it.bytes != null }.forEach { change ->
                val bytes = change.bytes!!
                val entry = ZipEntry("regions/${change.key.region}/${change.key.index}")
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                val crc = CRC32().apply { update(bytes) }
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        Output(output, sha1(output), Files.size(output), metadata)
    }

    private fun sha1(path: Path): String = Files.newInputStream(path).use { sha1(it) }
    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
    private fun sha1(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().toHex()
    }
    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
