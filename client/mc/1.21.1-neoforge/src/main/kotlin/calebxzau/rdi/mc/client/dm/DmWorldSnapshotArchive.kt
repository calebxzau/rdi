package calebxzau.rdi.mc.client.dm

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Builds the v5 `world-snapshot` upload: metadata, the changed members, and the digest of
 * the whole ZIP.
 *
 * The manifest always describes the complete WorldData file set and every column observed
 * this cycle. The ZIP only carries the members whose content differs from the pinned base;
 * Master resolves the rest against that base and republishes one complete archive.
 */
object DmWorldSnapshotArchive {
    const val FORMAT_VERSION = 5
    const val KIND = "world-snapshot"
    const val CAPTURE_MODE = "memory-and-files"
    const val WORLD_PREFIX = "world/"
    const val CHUNK_PREFIX = "chunks/"
    const val MAX_METADATA_BYTES = 32L * 1024L * 1024L
    const val MAX_ARCHIVE_MEMBERS = 200_000
    const val MAX_ARCHIVE_SOURCE_BYTES = 1024L * 1024L * 1024L

    private val gson = GsonBuilder().create()

    data class Request(
        val hostId: UUID,
        val sessionId: UUID,
        val cycleId: UUID,
        val sequence: Long,
        val baseSnapshotId: UUID?,
        val revision: Long,
        val capturedAt: String,
        val policy: DmWorldSyncPolicy,
        val files: List<DmWorldFileEntry>,
        val directories: List<String>,
        val columns: List<DmWorldColumnEntry>,
        val plan: DmWorldSyncPlan,
        val limits: DmWorldSnapshotLimits? = null,
    )

    data class Output(
        val archive: Path,
        val zipBytes: Long,
        val worldPayloadBytes: Long,
        val chunkPayloadBytes: Long,
    )

    /** Relative member paths of one column, in the order the digest frames them. */
    fun columnMembers(record: DmSnapshotBatchWriter.Record): List<String> = buildList {
        add(CHUNK_PREFIX + record.terrainPath)
        record.entitiesPath?.let { add(CHUNK_PREFIX + it) }
        record.poiPath?.let { add(CHUNK_PREFIX + it) }
    }

    /**
     * Computes the framed content digest of every staged column.
     *
     * The framing is part of the protocol: for terrain, entities, and POI in that order,
     * the big-endian kind length, the kind bytes, a presence byte, and then either -1 or
     * the member length followed by its content. Master recomputes the same digest, so this
     * must not change without changing both sides.
     */
    fun columnEntries(
        staging: Path,
        records: List<DmSnapshotBatchWriter.Record>,
        cancelled: () -> Boolean,
    ): List<DmWorldColumnEntry> = records.map { record ->
        val digest = MessageDigest.getInstance("SHA-1")
        frame(digest, staging, "terrain", CHUNK_PREFIX + record.terrainPath, cancelled)
        frame(digest, staging, "entities", record.entitiesPath?.let { CHUNK_PREFIX + it }, cancelled)
        frame(digest, staging, "poi", record.poiPath?.let { CHUNK_PREFIX + it }, cancelled)
        DmWorldColumnEntry(
            record.column,
            record.entitiesPath != null,
            record.poiPath != null,
            digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    fun write(
        staging: Path,
        archive: Path,
        request: Request,
        records: List<DmSnapshotBatchWriter.Record>,
        cancelled: () -> Boolean,
    ): Output {
        val metadata = metadata(request)
        val bytes = gson.toJson(metadata).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= minOf(MAX_METADATA_BYTES, request.limits?.maxMetadataBytes ?: MAX_METADATA_BYTES)) {
            "世界快照metadata超过大小上限"
        }
        Files.createDirectories(staging)
        Files.write(
            staging.resolve("metadata.json"),
            bytes,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        check(!cancelled()) { "世界同步已取消" }

        val changedFiles = request.plan.changedFiles.toSet()
        val updatedColumns = request.plan.updatedColumns.toSet()
        val worldMembers = request.files
            .filter { it.path in changedFiles }
            .map { WORLD_PREFIX + it.path }
        val chunkMembers = records
            .filter { it.column in updatedColumns }
            .flatMap(::columnMembers)
        val maxNbtBytes = request.limits?.maxNbtBytes ?: Long.MAX_VALUE
        chunkMembers.forEach { member ->
            require(Files.size(staging.resolve(member)) <= maxNbtBytes) {
                "世界快照区块NBT超过Master限制：$member"
            }
        }
        val included = buildSet {
            add("metadata.json")
            addAll(worldMembers)
            addAll(chunkMembers)
        }
        require(included.size == worldMembers.size + chunkMembers.size + 1) {
            "世界快照成员路径重复"
        }
        DmSnapshotArchive.pack(
            staging,
            archive,
            included,
            MAX_ARCHIVE_MEMBERS,
            minOf(MAX_ARCHIVE_SOURCE_BYTES, request.limits?.maxExpandedBytes ?: MAX_ARCHIVE_SOURCE_BYTES),
        ).getOrThrow()
        val maxUploadBytes = request.limits?.maxUploadBytes
        if (maxUploadBytes != null) {
            require(Files.size(archive) <= maxUploadBytes) { "世界快照上传ZIP超过Master限制" }
        }

        return Output(
            archive,
            Files.size(archive),
            worldMembers.sumOf { Files.size(staging.resolve(it)) },
            chunkMembers.sumOf { Files.size(staging.resolve(it)) },
        )
    }

    /** SHA-1 of the whole upload ZIP, sent as `X-DM-SHA1`. */
    fun sha1(archive: Path, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(archive).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "世界同步已取消" }
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun metadata(request: Request): JsonObject = JsonObject().apply {
        addProperty("formatVersion", FORMAT_VERSION)
        addProperty("kind", KIND)
        addProperty("hostId", request.hostId.toString())
        addProperty("sessionId", request.sessionId.toString())
        addProperty("cycleId", request.cycleId.toString())
        addProperty("sequence", request.sequence)
        if (request.baseSnapshotId == null) add("baseSnapshotId", com.google.gson.JsonNull.INSTANCE)
        else addProperty("baseSnapshotId", request.baseSnapshotId.toString())
        addProperty("syncChunkRevision", request.revision)
        addProperty("capturedAt", request.capturedAt)
        addProperty("captureMode", CAPTURE_MODE)
        // This is one save-triggered cycle, not a restore-safe or same-tick snapshot.
        addProperty("restoreSafe", false)
        add("world", JsonObject().apply {
            add("excludedFiles", jsonStrings(request.policy.excludedFiles.sorted()))
            add("excludedDirectories", jsonStrings(request.policy.excludedDirectories.sorted()))
            add("directories", jsonStrings(request.directories))
            add("files", JsonArray().apply {
                request.files.forEach { file ->
                    add(JsonObject().apply {
                        addProperty("path", file.path)
                        addProperty("bytes", file.bytes)
                        addProperty("sha1", file.sha1)
                    })
                }
            })
        })
        add("chunks", JsonArray().apply {
            request.columns.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("dimensionId", entry.column.dimensionId)
                    addProperty("chunkX", entry.column.chunkX)
                    addProperty("chunkZ", entry.column.chunkZ)
                    addProperty("entitiesPresent", entry.entitiesPresent)
                    addProperty("poiPresent", entry.poiPresent)
                    addProperty("sha1", entry.sha1)
                })
            }
        })
    }

    private fun jsonStrings(values: Collection<String>): JsonArray =
        JsonArray().apply { values.forEach(::add) }

    private fun frame(
        digest: MessageDigest,
        staging: Path,
        kind: String,
        relativePath: String?,
        cancelled: () -> Boolean,
    ) {
        val kindBytes = kind.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(kindBytes.size).array())
        digest.update(kindBytes)
        if (relativePath == null) {
            digest.update(byteArrayOf(0))
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(-1L).array())
            return
        }
        digest.update(byteArrayOf(1))
        val stagingRoot = staging.toAbsolutePath().normalize()
        val path = stagingRoot.resolve(relativePath).normalize()
        require(path.startsWith(stagingRoot)) { "世界快照内容路径越界" }
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Files.size(path)).array())
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "世界同步已取消" }
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
}
