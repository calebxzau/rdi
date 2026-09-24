package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.SequenceInputStream
import java.math.BigInteger
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import calebxzau.rdi.mc.client.syncchunk.DmSyncChunkEntry
import calebxzau.rdi.mc.client.syncchunk.DmSyncChunkLimits
import calebxzau.rdi.mc.client.syncchunk.DmSyncChunkMutation
import calebxzau.rdi.mc.client.syncchunk.DmSyncChunkSnapshot
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.client.syncchunk.SyncChunkRequestError

data class DmHostInfo(val id: UUID, val name: String, val state: String? = null, val gamePort: Int? = null)
data class DmGatewayInfo(val publicHost: String?, val tunnelPort: Int)
/** Limits Master publishes so a client can fail fast before building an oversized cycle. */
data class DmWorldSnapshotLimits(
    val maxUploadBytes: Long,
    val maxExpandedBytes: Long,
    val maxFileBytes: Long,
    val maxNbtBytes: Long,
    val maxMetadataBytes: Long,
    val maxWorldEntries: Int,
    val maxPathBytes: Int,
    val maxPathDepth: Int,
)

/** A published, self-contained world snapshot backed by an expanded directory. */
data class DmWorldSnapshotRecord(
    val snapshotId: UUID,
    val sessionId: UUID,
    val sequence: Long,
    val revision: Long,
    val cycleId: UUID,
    val manifestSha1: String,
    val expandedBytes: Long,
    val worldFiles: Int,
    val worldBytes: Long,
    val storedColumns: Int,
    val totalColumns: Int,
    val complete: Boolean,
    val capturedAt: String,
    val storedAt: Long,
)

/**
 * Master's receipt for one upload.
 *
 * `upload*` describes the bytes this client sent; `manifestSha1` and `expandedBytes`
 * describe the complete directory-backed snapshot Master published. They are different
 * objects and are never conflated.
 */
data class DmWorldSnapshotReceipt(
    val snapshotId: UUID,
    val sessionId: UUID,
    val sequence: Long,
    val revision: Long,
    val cycleId: UUID,
    val uploadSha1: String,
    val uploadZipBytes: Long,
    val manifestSha1: String,
    val expandedBytes: Long,
    val worldFiles: Int,
    val worldBytes: Long,
    val observedColumns: Int,
    val updatedColumns: Int,
    val storedColumns: Int,
    val totalColumns: Int,
    val complete: Boolean,
)

data class DmWorldSnapshotStatus(
    val latest: DmWorldSnapshotRecord?,
    val lastUpload: DmWorldSnapshotReceipt?,
    val storedColumns: Int,
    val totalColumns: Int,
    val complete: Boolean,
    val limits: DmWorldSnapshotLimits,
)

/** What this client asserts the receipt must confirm before it advances its baseline. */
data class DmWorldSnapshotExpectation(
    val sessionId: UUID,
    val sequence: Long,
    val revision: Long,
    val cycleId: UUID,
    val uploadSha1: String,
    val uploadZipBytes: Long,
    val worldFiles: Int,
    val worldBytes: Long,
    val observedColumns: Int,
    val updatedColumns: Int,
)

internal const val WORLD_SNAPSHOT_API_VERSION = 2

internal fun validateWorldSnapshotCoverage(observed: Int, stored: Int, total: Int) {
    require(observed >= 0 && stored >= 0 && total >= 0) { "世界快照响应覆盖范围无效" }
    require(observed <= total && stored <= total) { "世界快照响应覆盖范围无效" }
}

/** Reject responses from the pre-directory world snapshot contract before field parsing. */
internal fun requireWorldSnapshotApiVersion(json: JsonObject) {
    val version = json.get("apiVersion")
    if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber ||
        version.asString != WORLD_SNAPSHOT_API_VERSION.toString()
    ) {
        throw SyncChunkRequestError("VersionMismatch", "世界同步版本不匹配")
    }
}

internal fun validateWorldSnapshotLimits(limits: DmWorldSnapshotLimits) {
    require(limits.maxUploadBytes > 0L)
    require(limits.maxExpandedBytes > 0L)
    require(limits.maxFileBytes > 0L)
    require(limits.maxNbtBytes > 0L)
    require(limits.maxMetadataBytes > 0L)
    require(limits.maxWorldEntries > 0)
    require(limits.maxPathBytes > 0)
    require(limits.maxPathDepth > 0)
}

internal fun validateWorldSnapshotPublishedSession(
    manifestSessionId: UUID,
    expectedPublishedSessionId: UUID,
) {
    require(manifestSessionId == expectedPublishedSessionId) {
        "世界快照manifest的published session不匹配"
    }
}

class DmHttpClient(private val config: DmConfig) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun info(): Result<DmGatewayInfo> = getJson("info").mapCatching { json ->
        DmGatewayInfo(json.stringOrNull("publicHost"), json.get("tunnelPort").asInt)
    }

    fun getHost(id: UUID): Result<DmHostInfo> = getJson("hosts/$id").mapCatching(::hostInfo)

    fun createHost(name: String, archive: Path, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<DmHostInfo> = runCatching {
        require(name.isNotBlank()) { "房间名称不能为空" }
        require('\r' !in name && '\n' !in name) { "房间名称不能包含换行" }
        require(Files.isRegularFile(archive)) { "初始化资料不存在" }
        val boundary = "rdi-${UUID.randomUUID()}"
        val size = Files.size(archive)
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n$name\r\n".toByteArray(Charsets.UTF_8)
        val filePrefix = "--$boundary\r\nContent-Disposition: form-data; name=\"worldInit\"; filename=\"world-init.zip\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val uploaded = java.util.concurrent.atomic.AtomicLong()
        val request = request("hosts")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofInputStream {
                SequenceInputStream(
                    ByteArrayInputStream(prefix),
                    SequenceInputStream(
                        ByteArrayInputStream(filePrefix),
                        SequenceInputStream(
                            ProgressInputStream(Files.newInputStream(archive)) { value ->
                                onProgress(uploaded.addAndGet(value), size)
                            },
                            ByteArrayInputStream(suffix),
                        ),
                    ),
                )
            })
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        hostInfo(parseResponse(response.body(), response.statusCode()))
    }

    fun downloadWorldInit(id: UUID, destination: Path): Result<Unit> = runCatching {
        val response = client.send(
            request("hosts/$id/world-init").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        if (!contentType.lowercase().startsWith("application/zip")) {
            val body = response.body().use { it.readBytes().toString(Charsets.UTF_8) }
            parseResponse(body, response.statusCode())
            error("DM服务器没有返回初始化ZIP")
        }
        destination.parent?.let(Files::createDirectories)
        response.body().use { input ->
            BufferedInputStream(input).use { source ->
                Files.newOutputStream(destination).use { output ->
                    BufferedOutputStream(output).use { target -> source.copyTo(target) }
                }
            }
        }
    }

    fun getSyncChunks(hostId: UUID, sessionId: UUID): Result<DmSyncChunkSnapshot> =
        syncChunksRequest("hosts/$hostId/sync-chunks", sessionId, "GET", null) { json ->
            parseSyncSnapshot(json, mutation = false).snapshot
        }

    fun addSyncChunk(
        hostId: UUID,
        sessionId: UUID,
        expectedRevision: Long,
        playerId: UUID,
        chunk: SyncChunkKey,
    ): Result<DmSyncChunkMutation> = syncChunkMutation(
        hostId, sessionId, expectedRevision, playerId, chunk, "PUT",
    )

    fun removeSyncChunk(
        hostId: UUID,
        sessionId: UUID,
        expectedRevision: Long,
        playerId: UUID,
        chunk: SyncChunkKey,
    ): Result<DmSyncChunkMutation> = syncChunkMutation(
        hostId, sessionId, expectedRevision, playerId, chunk, "DELETE",
    )

    fun getWorldSnapshotStatus(hostId: UUID, sessionId: UUID): Result<DmWorldSnapshotStatus> = runCatching {
        val response = client.send(
            request("hosts/$hostId/world-snapshot/status")
                .header("X-DM-Session", sessionId.toString())
                .timeout(Duration.ofSeconds(10))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )
        val data = parseSnapshotResponse(response.body(), response.statusCode())
        requireWorldSnapshotApiVersion(data)
        val storedColumns = data.requiredNonNegativeInt("storedColumns")
        val totalColumns = data.requiredNonNegativeInt("totalColumns")
        validateWorldSnapshotCoverage(0, storedColumns, totalColumns)
        val latest = data.get("latest")?.takeUnless { it.isJsonNull }?.let(::parseSnapshotRecord)
        val lastUpload = data.get("lastUpload")?.takeUnless { it.isJsonNull }?.let(::parseSnapshotReceipt)
        latest?.let {
            require(it.complete == (it.storedColumns == it.totalColumns)) {
                "世界快照latest的complete与覆盖范围不一致"
            }
        }
        lastUpload?.let {
            validateWorldSnapshotCoverage(it.observedColumns, it.storedColumns, it.totalColumns)
            require(it.updatedColumns <= it.observedColumns) { "世界快照lastUpload覆盖范围无效" }
            require(it.complete == (it.storedColumns == it.totalColumns)) {
                "世界快照lastUpload的complete与覆盖范围不一致"
            }
        }
        val complete = data.requiredBoolean("complete")
        require(complete == (storedColumns == totalColumns)) {
            "世界快照状态complete与覆盖范围不一致"
        }
        DmWorldSnapshotStatus(
            latest,
            lastUpload,
            storedColumns,
            totalColumns,
            complete,
            parseLimits(data.getAsJsonObject("limits") ?: throw SyncChunkRequestError("InvalidResponse", "世界快照状态响应缺少limits")),
        )
    }

    /**
     * Reads one fixed snapshot's manifest as the incremental base.
     *
     * The snapshot is addressed by id, never through a mutable "latest" pointer, so the
     * base this client diffs against is the same archive Master resolves reuse against.
     */
    fun getWorldSnapshotManifest(
        hostId: UUID,
        sessionId: UUID,
        snapshotId: UUID,
        publishedSessionId: UUID = sessionId,
    ): Result<DmWorldSyncBaseline> = runCatching {
        val response = client.send(
            request("hosts/$hostId/world-snapshot/$snapshotId/manifest")
                .header("X-DM-Session", sessionId.toString())
                .timeout(Duration.ofSeconds(30))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )
        val data = parseSnapshotResponse(response.body(), response.statusCode())
        requireWorldSnapshotApiVersion(data)
        val returned = parseCanonicalUuid(data.requiredString("snapshotId"), "snapshotId")
        if (returned != snapshotId) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照manifest返回了其他快照")
        }
        parseManifest(
            snapshotId,
            hostId,
            publishedSessionId,
            data.getAsJsonObject("manifest")
                ?: throw SyncChunkRequestError("InvalidResponse", "世界快照响应缺少manifest"),
        )
    }

    fun uploadWorldSnapshot(
        hostId: UUID,
        archive: Path,
        expectation: DmWorldSnapshotExpectation,
    ): Result<DmWorldSnapshotReceipt> = runCatching {
        require(expectation.sequence > 0) { "世界快照sequence无效" }
        require(expectation.revision >= 0) { "同步区块revision无效" }
        require(expectation.uploadSha1.matches(SHA1_PATTERN)) { "世界快照SHA-1无效" }
        require(Files.isRegularFile(archive)) { "世界快照ZIP不存在" }
        val archiveBytes = Files.size(archive)
        require(archiveBytes > 0 && archiveBytes == expectation.uploadZipBytes) { "世界快照ZIP大小无效" }
        val request = request("hosts/$hostId/world-snapshot")
            .header("Content-Type", "application/zip")
            .header("X-DM-Session", expectation.sessionId.toString())
            .header("X-DM-Snapshot-Sequence", expectation.sequence.toString())
            .header("X-DM-Sync-Revision", expectation.revision.toString())
            .header("X-DM-SHA1", expectation.uploadSha1)
            .header("X-DM-Sync-Cycle", expectation.cycleId.toString())
            .timeout(Duration.ofSeconds(DmWorldSyncTiming.UPLOAD_TIMEOUT_SECONDS))
            .PUT(HttpRequest.BodyPublishers.ofFile(archive))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val data = parseSnapshotResponse(response.body(), response.statusCode())
        val receipt = parseSnapshotReceipt(data)
        checkReceipt(receipt, expectation)
        receipt
    }

    fun downloadWorldSnapshot(
        hostId: UUID,
        sessionId: UUID,
        snapshotId: UUID,
        destination: Path,
    ): Result<Unit> = runCatching {
        val response = client.send(
            request("hosts/$hostId/world-snapshot/$snapshotId")
                .header("X-DM-Session", sessionId.toString())
                .GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        if (!contentType.lowercase().startsWith("application/zip")) {
            val body = response.body().use { it.readBytes().toString(Charsets.UTF_8) }
            parseSnapshotResponse(body, response.statusCode())
            throw SyncChunkRequestError("InvalidResponse", "DM服务器没有返回世界快照ZIP")
        }
        destination.parent?.let(Files::createDirectories)
        response.body().use { input ->
            BufferedInputStream(input).use { source ->
                Files.newOutputStream(destination).use { output ->
                    BufferedOutputStream(output).use { target -> source.copyTo(target) }
                }
            }
        }
    }

    /** Every field the client asserted must come back unchanged before the baseline moves. */
    private fun checkReceipt(receipt: DmWorldSnapshotReceipt, expectation: DmWorldSnapshotExpectation) {
        validateWorldSnapshotCoverage(receipt.observedColumns, receipt.storedColumns, receipt.totalColumns)
        require(receipt.updatedColumns <= receipt.observedColumns) { "世界快照响应覆盖范围无效" }
        require(receipt.complete == (receipt.storedColumns == receipt.totalColumns)) {
            "世界快照响应complete与覆盖范围不一致"
        }
        require(
            receipt.sessionId == expectation.sessionId &&
                receipt.sequence == expectation.sequence &&
                receipt.revision == expectation.revision &&
                receipt.cycleId == expectation.cycleId &&
                receipt.uploadSha1 == expectation.uploadSha1 &&
                receipt.uploadZipBytes == expectation.uploadZipBytes &&
                receipt.worldFiles == expectation.worldFiles &&
                receipt.worldBytes == expectation.worldBytes &&
                receipt.observedColumns == expectation.observedColumns &&
                receipt.updatedColumns == expectation.updatedColumns,
        ) {
            "世界快照响应确认内容与请求不一致"
        }
    }

    private fun getJson(path: String): Result<JsonObject> = runCatching {
        val response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        parseResponse(response.body(), response.statusCode())
    }

    private fun syncChunkMutation(
        hostId: UUID,
        sessionId: UUID,
        expectedRevision: Long,
        playerId: UUID,
        chunk: SyncChunkKey,
        method: String,
    ): Result<DmSyncChunkMutation> = runCatching {
        require(expectedRevision >= 0) { "同步区块revision无效" }
        val body = JsonObject().apply {
            addProperty("sessionId", sessionId.toString())
            addProperty("expectedRevision", expectedRevision)
            addProperty("playerId", playerId.toString())
            add("chunk", chunk.toJson())
        }.toString()
        val response = client.send(
            request("hosts/$hostId/sync-chunks")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )
        parseSyncSnapshot(parseSyncResponse(response.body(), response.statusCode()), mutation = true)
    }

    private fun <T> syncChunksRequest(
        path: String,
        sessionId: UUID,
        method: String,
        body: String?,
        parse: (JsonObject) -> T,
    ): Result<T> = runCatching {
        val builder = request(path)
            .header("X-DM-Session", sessionId.toString())
            .timeout(Duration.ofSeconds(10))
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        parse(parseSyncResponse(response.body(), response.statusCode()))
    }

    private fun parseSyncResponse(body: String, status: Int): JsonObject {
        val json = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw SyncChunkRequestError("InvalidResponse", "DM服务器返回了无效的同步区块响应", it) }
        if (status != 200) {
            val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            throw SyncChunkRequestError(data?.stringOrNull("reason") ?: "Http$status", json.stringOrNull("msg") ?: "DM服务器返回HTTP$status")
        }
        if (json.get("code")?.asInt != 0) {
            val data = json.getAsJsonObject("data")
            val reason = data?.stringOrNull("reason") ?: "RequestFailed"
            throw SyncChunkRequestError(reason, json.stringOrNull("msg") ?: "DM服务器请求失败")
        }
        return json.getAsJsonObject("data")
            ?: throw SyncChunkRequestError("InvalidResponse", "DM服务器缺少同步区块数据")
    }

    private fun parseSyncSnapshot(json: JsonObject, mutation: Boolean): DmSyncChunkMutation =
        runCatching { syncChunkSnapshot(json, mutation) }.getOrElse { error ->
            if (error is SyncChunkRequestError) throw error
            throw SyncChunkRequestError("InvalidResponse", "DM服务器返回了无效的同步区块数据", error)
        }

    private fun syncChunkSnapshot(json: JsonObject, mutation: Boolean): DmSyncChunkMutation {
        listOf("sections", "sectionY", "ownerUuid", "maxPerson").firstOrNull(json::has)?.let {
            throw SyncChunkRequestError("InvalidResponse", "同步区块响应包含旧字段：$it")
        }
        val revision = json.get("revision")?.takeUnless { it.isJsonNull }?.asLong
            ?: throw SyncChunkRequestError("InvalidResponse", "同步区块revision缺失")
        require(revision >= 0) { "同步区块revision无效" }
        val chunksJson = json.getAsJsonArray("chunks")
            ?: throw SyncChunkRequestError("InvalidResponse", "同步区块列表缺失")
        val entries = chunksJson.map { value ->
            val item = value.asJsonObject
            listOf("sectionY", "ownerUuid").firstOrNull(item::has)?.let {
                throw SyncChunkRequestError("InvalidResponse", "同步区块响应包含旧字段：$it")
            }
            val key = SyncChunkKey(
                item.get("dimensionId")?.asString ?: error("同步区块dimensionId缺失"),
                item.get("chunkX")?.asInt ?: error("同步区块chunkX缺失"),
                item.get("chunkZ")?.asInt ?: error("同步区块chunkZ缺失"),
            )
            val ownerText = item.get("ownerId")?.asString ?: throw SyncChunkRequestError("InvalidResponse", "同步区块ownerId缺失")
            val owner = runCatching { UUID.fromString(ownerText) }
                .getOrElse { throw SyncChunkRequestError("InvalidResponse", "同步区块ownerId无效", it) }
            if (owner.toString() != ownerText) {
                throw SyncChunkRequestError("InvalidResponse", "同步区块ownerId格式无效")
            }
            DmSyncChunkEntry(key, owner)
        }
        require(entries.map { it.key }.distinct().size == entries.size) {
            "同步区块响应包含重复坐标"
        }
        val limits = json.getAsJsonObject("limits")
            ?: throw SyncChunkRequestError("InvalidResponse", "同步区块限制缺失")
        if (limits.has("maxPerson")) {
            throw SyncChunkRequestError("InvalidResponse", "同步区块限制包含旧字段：maxPerson")
        }
        val maxTotalLong = limits.get("maxTotal")?.asLong ?: error("同步区块maxTotal缺失")
        require(maxTotalLong in 1..Int.MAX_VALUE.toLong()) {
            "同步区块限制无效"
        }
        val maxTotal = maxTotalLong.toInt()
        val snapshot = DmSyncChunkSnapshot(revision, entries.toList(), DmSyncChunkLimits(maxTotal))
        val outcome = if (mutation) json.stringOrNull("outcome")?.let {
            runCatching { DmSyncChunkMutation.Outcome.valueOf(it) }
                .getOrElse { throw SyncChunkRequestError("InvalidResponse", "同步区块操作结果无效", it) }
        } ?: throw SyncChunkRequestError("InvalidResponse", "同步区块操作结果缺失") else null
        return DmSyncChunkMutation(snapshot, outcome ?: DmSyncChunkMutation.Outcome.AlreadyAbsent)
    }

    private fun SyncChunkKey.toJson(): JsonObject = JsonObject().apply {
        addProperty("dimensionId", dimensionId)
        addProperty("chunkX", chunkX)
        addProperty("chunkZ", chunkZ)
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(
        config.baseUri.resolve(path.trimStart('/')),
    ).timeout(Duration.ofMinutes(10))

    private fun parseResponse(body: String, status: Int): JsonObject {
        require(status == 200) { "DM服务器返回HTTP$status" }
        val json = JsonParser.parseString(body).asJsonObject
        require(json.get("code")?.asInt == 0) { json.get("msg")?.asString ?: "DM服务器请求失败" }
        return json.getAsJsonObject("data") ?: JsonObject()
    }

    private fun parseSnapshotResponse(body: String, status: Int): JsonObject {
        val json = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { error ->
                throw SyncChunkRequestError("InvalidResponse", "DM服务器返回了无效的世界快照响应", error)
            }
        if (status != 200) {
            val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            throw SyncChunkRequestError(
                data?.stringOrNull("reason") ?: "Http$status",
                json.stringOrNull("msg") ?: "DM服务器返回HTTP$status",
            )
        }
        if (json.get("code")?.asInt != 0) {
            val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            throw SyncChunkRequestError(
                data?.stringOrNull("reason") ?: "RequestFailed",
                json.stringOrNull("msg") ?: "DM服务器请求失败",
            )
        }
        return json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw SyncChunkRequestError("InvalidResponse", "DM服务器缺少世界快照数据")
    }

    internal fun parseSnapshotRecord(element: com.google.gson.JsonElement): DmWorldSnapshotRecord {
        val json = element.asObject("latest")
        val storedColumns = json.requiredNonNegativeInt("storedColumns")
        val totalColumns = json.requiredNonNegativeInt("totalColumns")
        validateWorldSnapshotCoverage(0, storedColumns, totalColumns)
        return DmWorldSnapshotRecord(
            json.requiredSnapshotId(),
            parseCanonicalUuid(json.requiredString("sessionId"), "sessionId"),
            json.requiredPositiveLong("sequence"),
            json.requiredNonNegativeLong("syncChunkRevision"),
            json.requiredCycleId(),
            json.requiredSha1("manifestSha1"),
            json.requiredPositiveLong("expandedBytes"),
            json.requiredNonNegativeInt("worldFiles"),
            json.requiredNonNegativeLong("worldBytes"),
            storedColumns,
            totalColumns,
            json.requiredBoolean("complete"),
            json.requiredTimestamp("capturedAt"),
            json.requiredNonNegativeLong("storedAt"),
        )
    }

    internal fun parseSnapshotReceipt(
        element: com.google.gson.JsonElement,
    ): DmWorldSnapshotReceipt {
        val json = element.asObject("lastUpload")
        requireWorldSnapshotApiVersion(json)
        return DmWorldSnapshotReceipt(
            json.requiredSnapshotId(),
            parseCanonicalUuid(json.requiredString("sessionId"), "sessionId"),
            json.requiredPositiveLong("sequence"),
            json.requiredNonNegativeLong("syncChunkRevision"),
            json.requiredCycleId(),
            json.requiredSha1("uploadSha1"),
            json.requiredPositiveLong("uploadZipBytes"),
            json.requiredSha1("manifestSha1"),
            json.requiredPositiveLong("expandedBytes"),
            json.requiredNonNegativeInt("worldFiles"),
            json.requiredNonNegativeLong("worldBytes"),
            json.requiredNonNegativeInt("observedColumns"),
            json.requiredNonNegativeInt("updatedColumns"),
            json.requiredNonNegativeInt("storedColumns"),
            json.requiredNonNegativeInt("totalColumns"),
            json.requiredBoolean("complete"),
        )
    }

    private fun parseLimits(json: JsonObject): DmWorldSnapshotLimits = DmWorldSnapshotLimits(
        json.requiredPositiveLong("maxUploadBytes"),
        json.requiredPositiveLong("maxExpandedBytes"),
        json.requiredPositiveLong("maxFileBytes"),
        json.requiredPositiveLong("maxNbtBytes"),
        json.requiredPositiveLong("maxMetadataBytes"),
        json.requiredPositiveInt("maxWorldEntries"),
        json.requiredPositiveInt("maxPathBytes"),
        json.requiredPositiveInt("maxPathDepth"),
    ).also(::validateWorldSnapshotLimits)

    /** Turns a published v5 manifest into the local incremental baseline. */
    private fun parseManifest(
        snapshotId: UUID,
        expectedHostId: UUID,
        expectedSessionId: UUID,
        json: JsonObject,
    ): DmWorldSyncBaseline {
        if (json.requiredSignedLong("formatVersion") != DmWorldSnapshotArchive.FORMAT_VERSION.toLong() ||
            json.requiredString("kind") != DmWorldSnapshotArchive.KIND
        ) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照manifest不是v5 world-snapshot")
        }
        if (json.get("baseSnapshotId")?.isJsonNull != true) {
            throw SyncChunkRequestError("InvalidResponse", "已发布的世界快照manifest不应引用base")
        }
        if (parseCanonicalUuid(json.requiredString("hostId"), "hostId") != expectedHostId
        ) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照manifest的host不匹配")
        }
        val publishedSessionId = parseCanonicalUuid(json.requiredString("sessionId"), "sessionId")
        try {
            validateWorldSnapshotPublishedSession(publishedSessionId, expectedSessionId)
        } catch (error: IllegalArgumentException) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照manifest的published session不匹配", error)
        }
        if (json.requiredString("captureMode") != DmWorldSnapshotArchive.CAPTURE_MODE ||
            json.requiredBoolean("restoreSafe")
        ) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照manifest的采集模式无效")
        }
        json.requiredPositiveLong("sequence")
        json.requiredCycleId()
        json.requiredTimestamp("capturedAt")
        json.requiredNonNegativeLong("syncChunkRevision")
        val world = json.getAsJsonObject("world")
            ?: throw SyncChunkRequestError("InvalidResponse", "世界快照manifest缺少world")
        val files = LinkedHashMap<String, DmWorldFileEntry>()
        val filesJson = world.getAsJsonArray("files")
            ?: throw SyncChunkRequestError("InvalidResponse", "世界快照manifest缺少files")
        filesJson.forEach { element ->
            val entry = element.asObject("files")
            val path = entry.requiredString("path")
            DmWorldDataCapture.validatePath(path)
            val file = DmWorldFileEntry(
                path,
                entry.requiredNonNegativeLong("bytes"),
                entry.requiredSha1("sha1"),
            )
            if (files.put(path, file) != null) {
                throw SyncChunkRequestError("InvalidResponse", "世界快照manifest包含重复文件：$path")
            }
        }
        require(files.containsKey("level.dat")) { "世界快照manifest缺少level.dat" }
        require(world.has("directories") && world.has("excludedFiles") && world.has("excludedDirectories")) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照manifest缺少目录或排除策略")
        }
        val directories = world.stringSet("directories")
        directories.forEach(DmWorldDataCapture::validatePath)
        world.stringSet("excludedFiles").forEach(DmWorldDataCapture::validatePath)
        world.stringSet("excludedDirectories").forEach(DmWorldDataCapture::validatePath)
        DmWorldDataCapture.validatePathConflicts(files.keys, directories)
        val columns = LinkedHashMap<DmSnapshotArchive.Column, DmWorldColumnEntry>()
        val chunksJson = json.getAsJsonArray("chunks")
            ?: throw SyncChunkRequestError("InvalidResponse", "世界快照manifest缺少chunks")
        chunksJson.forEach { element ->
            val entry = element.asObject("chunks")
            val column = DmSnapshotArchive.Column(
                entry.requiredString("dimensionId"),
                entry.requiredInt("chunkX"),
                entry.requiredInt("chunkZ"),
            )
            val value = DmWorldColumnEntry(
                column,
                entry.requiredBoolean("entitiesPresent"),
                entry.requiredBoolean("poiPresent"),
                entry.requiredSha1("sha1"),
            )
            if (columns.put(column, value) != null) {
                throw SyncChunkRequestError("InvalidResponse", "世界快照manifest包含重复区块")
            }
        }
        return DmWorldSyncBaseline(
            snapshotId,
            json.requiredNonNegativeLong("syncChunkRevision"),
            files,
            directories,
            world.stringSet("excludedFiles"),
            world.stringSet("excludedDirectories"),
            columns,
        )
    }

    private fun com.google.gson.JsonElement.asObject(field: String): JsonObject {
        if (!isJsonObject) throw SyncChunkRequestError("InvalidResponse", "世界快照响应${field}必须是对象")
        return asJsonObject
    }

    private fun JsonObject.stringSet(name: String): Set<String> {
        val array = getAsJsonArray(name) ?: return emptySet()
        val values = LinkedHashSet<String>()
        array.forEach { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}包含非字符串")
            }
            if (!values.add(element.asString)) {
                throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}包含重复项")
            }
        }
        return values
    }

    private fun JsonObject.requiredSnapshotId(): UUID {
        val value = parseCanonicalUuid(requiredString("snapshotId"), "snapshotId")
        if (value.version() != 7 || value.variant() != 2) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应snapshotId必须是UUIDv7")
        }
        return value
    }

    private fun JsonObject.requiredCycleId(): UUID {
        val value = parseCanonicalUuid(requiredString("cycleId"), "cycleId")
        if (value.version() != 7 || value.variant() != 2) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应cycleId必须是UUIDv7")
        }
        return value
    }

    private fun JsonObject.requiredSha1(name: String): String {
        val value = requiredString(name)
        if (!value.matches(SHA1_PATTERN)) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效")
        }
        return value
    }

    private fun JsonObject.requiredTimestamp(name: String): String {
        val value = requiredString(name)
        runCatching { Instant.parse(value) }.getOrElse { error ->
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效", error)
        }
        return value
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应缺少或无效的$name")
        }
        return value.asBoolean
    }

    private fun JsonObject.requiredPositiveLong(name: String): Long {
        val value = requiredSignedLong(name)
        if (value <= 0L) throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效")
        return value
    }

    private fun JsonObject.requiredNonNegativeLong(name: String): Long {
        val value = requiredSignedLong(name)
        if (value < 0L) throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效")
        return value
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = requiredSignedLong(name)
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}超出范围")
        }
        return value.toInt()
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应缺少或无效的$name")
        }
        return value.asString
    }

    private fun JsonObject.requiredSignedLong(name: String): Long {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应缺少或无效的$name")
        }
        val text = value.asJsonPrimitive.toString()
        if (!SIGNED_INTEGER_PATTERN.matches(text)) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}必须是整数")
        }
        val number = runCatching { BigInteger(text) }.getOrElse {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效", it)
        }
        if (number < LONG_MIN || number > LONG_MAX) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}超出范围")
        }
        return number.toLong()
    }

    private fun parseCanonicalUuid(value: String, field: String): UUID {
        val uuid = runCatching { UUID.fromString(value) }.getOrElse { error ->
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${field}无效", error)
        }
        if (uuid.toString() != value) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${field}格式无效")
        }
        return uuid
    }

    private fun hostInfo(json: JsonObject): DmHostInfo {
        val id = UUID.fromString(json.get("id").asString)
        return DmHostInfo(id, json.get("name").asString, json.stringOrNull("state"), json.intOrNull("gamePort"))
    }

    private class ProgressInputStream(
        input: java.io.InputStream,
        private val progress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var read: Long = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) {
                read += count
                progress(count.toLong())
            }
            return count
        }

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                read++
                progress(1)
            }
            return value
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt

    private fun JsonObject.requiredNonNegativeInt(name: String): Int {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应缺少或无效的$name")
        }
        val number = value.asLong
        if (number !in 0..Int.MAX_VALUE.toLong()) {
            throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效")
        }
        return number.toInt()
    }

    private fun JsonObject.requiredPositiveInt(name: String): Int {
        val value = requiredNonNegativeInt(name)
        if (value <= 0) throw SyncChunkRequestError("InvalidResponse", "世界快照响应${name}无效")
        return value
    }

    private companion object {
        val SIGNED_INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")
        val SHA1_PATTERN = Regex("[0-9a-f]{40}")
        val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    }
}
