package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** JSON form of backup points and `index.json`; decoding rejects the whole file on any invalid field. */
object SyncChunkBackupJson {
    const val FORMAT_VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val HASH = Regex("[0-9a-f]{40}")

    fun encodePoint(point: SyncChunkBackupPoint): String = gson.toJson(JsonObject().apply {
        addProperty("formatVersion", FORMAT_VERSION)
        addProperty("id", point.id)
        addProperty("kind", point.kind.name)
        addProperty("createdAt", point.createdAt)
        addProperty("gameTime", point.gameTime)
        add("changed", encodeChanges(point.changed))
        addProperty("addedBytes", point.addedBytes)
        add("rollbackId", point.rollbackId?.let(::jsonString) ?: JsonNull.INSTANCE)
        add("chunks", JsonArray().apply {
            point.chunks.forEach { chunk ->
                add(JsonObject().apply {
                    addProperty("dimension", chunk.key.dimensionId)
                    addProperty("x", chunk.key.chunkX)
                    addProperty("z", chunk.key.chunkZ)
                    add("terrain", chunk.terrain?.let(::jsonString) ?: JsonNull.INSTANCE)
                    add("entities", chunk.entities?.let(::jsonString) ?: JsonNull.INSTANCE)
                    add("poi", chunk.poi?.let(::jsonString) ?: JsonNull.INSTANCE)
                })
            }
        })
        add("files", JsonArray().apply {
            point.files.forEach { file ->
                add(JsonObject().apply {
                    addProperty("path", file.path)
                    addProperty("hash", file.hash)
                    addProperty("size", file.size)
                })
            }
        })
    })

    fun decodePoint(text: String): Result<SyncChunkBackupPoint> = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.int("formatVersion") == FORMAT_VERSION) { "回档点格式版本不支持" }
        SyncChunkBackupPoint(
            id = root.string("id"),
            kind = SyncChunkBackupKind.valueOf(root.string("kind")),
            createdAt = root.long("createdAt"),
            gameTime = root.long("gameTime"),
            changed = decodeChanges(root.obj("changed")),
            addedBytes = root.long("addedBytes"),
            rollbackId = root.nullableString("rollbackId"),
            chunks = root.array("chunks").map { element ->
                val chunk = element.asJsonObject
                val dimension = chunk.string("dimension")
                require(SyncChunkList.isValidDimensionId(dimension)) { "回档点区块维度ID无效：${dimension}" }
                SyncChunkBackupChunk(
                    key = SyncChunkKey(dimension, chunk.int("x"), chunk.int("z")),
                    terrain = chunk.nullableHash("terrain"),
                    entities = chunk.nullableHash("entities"),
                    poi = chunk.nullableHash("poi"),
                )
            },
            files = root.array("files").map { element ->
                val file = element.asJsonObject
                val path = file.string("path")
                require(SyncChunkBackupFileRules.isSafeRelativePath(path)) { "回档点文件路径无效：${path}" }
                SyncChunkBackupFile(path, file.hash("hash"), file.long("size"))
            },
        )
    }

    fun encodeIndex(index: SyncChunkBackupIndex): String = gson.toJson(JsonObject().apply {
        addProperty("formatVersion", FORMAT_VERSION)
        addProperty("worldToken", index.worldToken)
        addProperty("updatedAt", index.updatedAt)
        add("storage", JsonObject().apply {
            addProperty("usedBytes", index.usedBytes)
            addProperty("maxBytes", index.maxBytes)
        })
        add("points", JsonArray().apply {
            index.points.forEach { point ->
                add(JsonObject().apply {
                    addProperty("id", point.id)
                    addProperty("kind", point.kind.name)
                    addProperty("createdAt", point.createdAt)
                    addProperty("gameTime", point.gameTime)
                    addProperty("chunkCount", point.chunkCount)
                    addProperty("playerCount", point.playerCount)
                    add("changed", encodeChanges(point.changed))
                    addProperty("addedBytes", point.addedBytes)
                    add("rollbackId", point.rollbackId?.let(::jsonString) ?: JsonNull.INSTANCE)
                })
            }
        })
    })

    fun decodeIndex(text: String): Result<SyncChunkBackupIndex> = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.int("formatVersion") == FORMAT_VERSION) { "回档点索引格式版本不支持" }
        val storage = root.obj("storage")
        SyncChunkBackupIndex(
            worldToken = root.string("worldToken"),
            updatedAt = root.long("updatedAt"),
            usedBytes = storage.long("usedBytes"),
            maxBytes = storage.long("maxBytes"),
            points = root.array("points").map { element ->
                val point = element.asJsonObject
                SyncChunkBackupPointSummary(
                    id = point.string("id"),
                    kind = SyncChunkBackupKind.valueOf(point.string("kind")),
                    createdAt = point.long("createdAt"),
                    gameTime = point.long("gameTime"),
                    chunkCount = point.int("chunkCount"),
                    playerCount = point.int("playerCount"),
                    changed = decodeChanges(point.obj("changed")),
                    addedBytes = point.long("addedBytes"),
                    rollbackId = point.nullableString("rollbackId"),
                )
            },
        )
    }

    fun encodeRollbackRequest(request: SyncChunkRollbackRequest): String = gson.toJson(JsonObject().apply {
        addProperty("formatVersion", FORMAT_VERSION)
        addProperty("id", request.id)
        addProperty("pointId", request.pointId)
        addProperty("worldToken", request.worldToken)
        addProperty("requestedBy", request.requestedBy)
        addProperty("requestedAt", request.requestedAt)
        addProperty("state", request.state.name)
        add("beforePointId", request.beforePointId?.let(::jsonString) ?: JsonNull.INSTANCE)
        addProperty("abandoned", request.abandoned)
    })

    fun decodeRollbackRequest(text: String): Result<SyncChunkRollbackRequest> = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.int("formatVersion") == FORMAT_VERSION) { "回档请求格式版本不支持" }
        SyncChunkRollbackRequest(
            id = root.string("id"),
            pointId = root.string("pointId"),
            worldToken = root.string("worldToken"),
            requestedBy = root.string("requestedBy"),
            requestedAt = root.long("requestedAt"),
            state = SyncChunkRollbackState.valueOf(root.string("state")),
            beforePointId = root.nullableString("beforePointId"),
            abandoned = root.field("abandoned").asBoolean,
        )
    }

    fun encodeRollbackResult(result: SyncChunkRollbackResult): String = gson.toJson(JsonObject().apply {
        addProperty("formatVersion", FORMAT_VERSION)
        add("requestId", result.requestId?.let(::jsonString) ?: JsonNull.INSTANCE)
        add("pointId", result.pointId?.let(::jsonString) ?: JsonNull.INSTANCE)
        add("beforePointId", result.beforePointId?.let(::jsonString) ?: JsonNull.INSTANCE)
        addProperty("outcome", result.outcome.name)
        add("message", result.message?.let(::jsonString) ?: JsonNull.INSTANCE)
        addProperty("finishedAt", result.finishedAt)
    })

    fun decodeRollbackResult(text: String): Result<SyncChunkRollbackResult> = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        require(root.int("formatVersion") == FORMAT_VERSION) { "回档结果格式版本不支持" }
        SyncChunkRollbackResult(
            requestId = root.nullableString("requestId"),
            pointId = root.nullableString("pointId"),
            beforePointId = root.nullableString("beforePointId"),
            outcome = SyncChunkRollbackOutcome.valueOf(root.string("outcome")),
            message = root.nullableString("message"),
            finishedAt = root.long("finishedAt"),
        )
    }

    private fun encodeChanges(changes: SyncChunkBackupChanges) = JsonObject().apply {
        addProperty("terrainChunks", changes.terrainChunks)
        addProperty("entityChunks", changes.entityChunks)
        addProperty("poiChunks", changes.poiChunks)
        addProperty("players", changes.players)
        addProperty("files", changes.files)
    }

    private fun decodeChanges(json: JsonObject) = SyncChunkBackupChanges(
        terrainChunks = json.int("terrainChunks"),
        entityChunks = json.int("entityChunks"),
        poiChunks = json.int("poiChunks"),
        players = json.int("players"),
        files = json.int("files"),
    )

    private fun jsonString(value: String): JsonElement = JsonPrimitive(value)

    private fun JsonObject.field(name: String): JsonElement =
        get(name)?.takeUnless { it.isJsonNull } ?: throw IllegalArgumentException("缺少字段${name}")

    private fun JsonObject.string(name: String): String = field(name).asString
    private fun JsonObject.int(name: String): Int = field(name).asInt
    private fun JsonObject.long(name: String): Long = field(name).asLong
    private fun JsonObject.obj(name: String): JsonObject = field(name).asJsonObject
    private fun JsonObject.array(name: String): JsonArray = field(name).asJsonArray
    private fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.hash(name: String): String =
        string(name).also { require(HASH.matches(it)) { "字段${name}不是有效的哈希：${it}" } }

    private fun JsonObject.nullableHash(name: String): String? =
        nullableString(name)?.also { require(HASH.matches(it)) { "字段${name}不是有效的哈希：${it}" } }
}
