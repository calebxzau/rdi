package calebxzau.rdi.client.service

import calebxzau.rdi.anvilrw.remap.SaveMarkingIdentity
import calebxzau.rdi.anvilrw.remap.SaveMarkingPlayer
import calebxzau.rdi.anvilrw.remap.NbtMetadataReader
import calebxzau.rdi.anvilrw.remap.RawRegionFile
import calebxzau.rdi.anvilrw.remap.SaveSnapshot
import calebxzau.rdi.anvilrw.remap.SaveSourceLock
import calebxzau.rdi.anvilrw.remap.SaveSyncChunk
import calebxzau.rdi.anvilrw.remap.SaveSyncChunks
import calebxzau.rdi.anvilrw.remap.SaveUuidRemapper
import calebxzau.rdi.anvilrw.remap.dimensionStorageFolder
import calebxzau.rdi.client.RDIClient
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** A marking copy of a save, remembered so a later attempt can reuse it and its marks. */
@Serializable
data class SaveImportMarkingRecord(
    val sourcePath: String,
    val hostId: String,
    val copyPath: String,
    /** The UUID the game is launched with in marking mode: `Data.Player.UUID`, else the RDI account. */
    val launchUuid: String,
    val createdAt: Long,
    val identityVersion: Int = 0,
    val originalOwnerUuid: String? = null,
    val selectedPlayerUuid: String? = null,
    val selectedPlayerName: String? = null,
    /** Original overwritten files, outside the playable/uploaded world. */
    val identityBackupPath: String? = null,
) {
    val copy: Path get() = Path.of(copyPath)
    val copyFolderName: String get() = copy.fileName.toString()
}

data class SaveImportMarkingSummary(
    val marked: List<SaveSyncChunk>,
    /** Marked chunks per dimension. */
    val markedByDimension: Map<String, Int>,
    /** Marked chunks that exist in the save's `region/` stores. */
    val presentCount: Int,
    /** Players whose last position is in a dimension or chunk that is not kept. */
    val playersOutside: List<UUID>,
)

/**
 * The marking copy (plan §9.1, with the marking mode decided after v5): the save is copied into the
 * host modpack's `saves/` folder, the game opens it with the selected role UUID, and the player
 * marks chunks with `/syncchunk add`. The original save is only read, under its `session.lock`.
 */
class HostSaveImportMarkingService(
    private val recordsFile: File = RDIClient.DIR.resolve("save-import-marking.json"),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun players(source: Path): Result<List<SaveMarkingPlayer>> = runCatching {
        SaveSourceLock.acquire(source).getOrThrow().use { SaveMarkingIdentity.players(source).getOrThrow() }
    }

    /** The remembered copy of [source] for [hostId], if it still exists. */
    fun findReusable(source: Path, hostId: String): SaveImportMarkingRecord? =
        records().lastOrNull { it.sourcePath == source.normalizedString() && it.hostId == hostId && Files.isDirectory(it.copy) }

    /**
     * Copies [source] into [savesDir] as `<name>-RDI导入` (or `-2`, `-3`…), holding the source's lock and
     * verifying the copy against a snapshot. [rdiUuid] is used for the launch when the save has no
     * existing player role.
     */
    fun createCopy(
        source: Path,
        hostId: String,
        savesDir: Path,
        rdiUuid: UUID,
        selectedPlayer: UUID? = null,
        ensureActive: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<SaveImportMarkingRecord> = runCatching {
        val sourceRoot = source.toAbsolutePath().normalize()
        val saves = savesDir.toAbsolutePath().normalize()
        require(!saves.startsWith(sourceRoot)) { "整合包目录不能位于存档目录里" }
        val lock = SaveSourceLock.acquire(sourceRoot).getOrElse { throw it.toPlayerError() }
        val record = lock.use {
            val snapshot = SaveSnapshot.take(sourceRoot).getOrElse { throw it.toPlayerError() }
            val roles = SaveMarkingIdentity.players(sourceRoot).getOrElse { throw it.toPlayerError() }
            val selected = selectedPlayer ?: roles.firstOrNull()?.player?.uuid
            val role = selected?.let { uuid -> roles.firstOrNull { it.player.uuid == uuid }
                ?: throw RequestError("所选角色已不存在，请重新选择存档") }
            Files.createDirectories(saves)
            val target = freeCopyFolder(saves, sourceRoot.fileName.toString())
            // Staging is outside saves/: Minecraft must never discover a half-prepared world.
            val stagingRoot = saves.parent.resolve("rdi-save-import-staging")
            require(!stagingRoot.startsWith(sourceRoot)) { "准备目录不能位于原存档内" }
            Files.createDirectories(stagingRoot)
            val staging = Files.createTempDirectory(stagingRoot, "prepare-")
            val world = staging.resolve("world")
            snapshot.copyTo(world, ensureActive) { done -> progress(done, snapshot.totalBytes) }
                .getOrElse { throw it.toPlayerError() }
            val owner = SaveSourceLock.acquire(world).getOrThrow().use {
                SaveMarkingIdentity.prepare(world, selected, staging.resolve("backup")).getOrThrow()
            }
            ensureActive()
            Files.move(world, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            SaveImportMarkingRecord(
                sourceRoot.normalizedString(), hostId, target.normalizedString(), (selected ?: rdiUuid).toString(), clock(),
                identityVersion = 1, originalOwnerUuid = owner?.toString(),
                selectedPlayerUuid = selected?.toString(), selectedPlayerName = role?.displayName,
                identityBackupPath = staging.resolve("backup").normalizedString(),
            )
        }
        saveRecords(records().filterNot { it.copyPath == record.copyPath } + record)
        record
    }

    /**
     * A copy opened normally (not in marking mode) is played as the RDI account, which writes the
     * owner's data under the wrong UUID; such a copy must be recreated.
     */
    fun checkIdentity(record: SaveImportMarkingRecord): Result<Unit> = runCatching {
        require(record.identityVersion in 0..1) { "导入副本版本不受支持，请重新创建副本" }
        require(record.selectedPlayerUuid == null || record.selectedPlayerUuid == record.launchUuid) { "副本角色记录不一致，请重新创建副本" }
        SaveSourceLock.acquire(record.copy).getOrElse { throw it.toPlayerError() }.use {
            val current = NbtMetadataReader.readLevelDat(record.copy.resolve("level.dat"))
                .getOrElse { throw RequestError("副本的level.dat无效", it) }.singleplayerUuid
            if ((current != null && current.toString() != record.launchUuid) ||
                (record.identityVersion == 1 && record.selectedPlayerUuid != null && current == null)) {
                throw RequestError("副本中的角色身份发生变化，请重新创建副本")
            }
            if (record.identityVersion == 0 && current == null &&
                SaveMarkingIdentity.players(record.copy).getOrThrow().any { it.player.uuid.toString() != record.launchUuid }) {
                throw RequestError("旧副本无法确认标记角色，请重新创建副本")
            }
            record.originalOwnerUuid?.takeIf { it != record.launchUuid }?.let { owner ->
                val uuid = UUID.fromString(owner)
                val savedOwner = NbtMetadataReader.openGzipFile(record.copy.resolve("playerdata/${uuid}.dat"))
                    .getOrElse { throw RequestError("副本中的原主人数据缺失或损坏，请重新创建副本", it) }
                require(savedOwner.root.uniqueChild("UUID")?.uuidOrNull() == uuid) { "副本中的原主人身份发生变化，请重新创建副本" }
            }
        }
    }

    /**
     * Refuses a copy whose chunks use a compression the game cannot read. The game would treat such
     * chunks as missing, regenerate them and save the new terrain over them during marking.
     */
    fun checkReadable(record: SaveImportMarkingRecord): Result<Unit> = runCatching {
        val copy = record.copy
        val counts = HashMap<Int, Int>()
        Files.walk(copy).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".mca") }
                .filter { SaveUuidRemapper.chunkStoreDimension(copy.relativize(it).joinToString("/")) != null }
                .forEach { region ->
                    RawRegionFile.compressionTypes(region).getOrThrow().forEach { (type, count) -> counts.merge(type, count, Int::plus) }
                }
        }
        val unreadable = counts.filterKeys { it !in GAME_READABLE_COMPRESSIONS }
        if (unreadable.isNotEmpty()) {
            val names = unreadable.keys.sorted().joinToString("、") { type ->
                when (type) {
                    4 -> "LZ4"
                    127 -> "自定义压缩"
                    else -> "编号${type}"
                }
            }
            throw RequestError("存档中有${unreadable.values.sum()}个区块使用游戏无法读取的压缩方式（${names}），进入游戏后会被重新生成并覆盖，无法安全标记")
        }
    }

    /** What was marked in the copy (plan §4 step 6). */
    fun summary(record: SaveImportMarkingRecord): Result<SaveImportMarkingSummary> = runCatching {
        val copy = record.copy
        val syncFile = copy.resolve(SaveSyncChunks.RELATIVE_PATH)
        val marked = if (Files.isRegularFile(syncFile, LinkOption.NOFOLLOW_LINKS)) {
            SaveSyncChunks.parse(Files.readAllBytes(syncFile)).getOrElse { throw RequestError("同步区块列表无效", it) }
        } else {
            emptyList()
        }
        val present = marked.count { chunk ->
            val folder = dimensionStorageFolder(copy, chunk.dimensionId).getOrThrow()
            val region = folder.resolve("region").resolve("r.${chunk.chunkX shr 5}.${chunk.chunkZ shr 5}.mca")
            Files.isRegularFile(region, LinkOption.NOFOLLOW_LINKS) &&
                (chunk.chunkX to chunk.chunkZ) in RawRegionFile.presentChunks(region).getOrThrow()
        }
        val keep = marked.groupBy({ it.dimensionId }) { it.chunkX to it.chunkZ }.mapValues { it.value.toSet() }
        SaveImportMarkingSummary(
            marked = marked,
            markedByDimension = marked.groupingBy { it.dimensionId }.eachCount(),
            presentCount = present,
            playersOutside = playerPositions(copy).filter { (_, position) ->
                val (dimension, chunk) = position
                chunk !in keep[dimension].orEmpty()
            }.map { it.first },
        )
    }

    /** Deletes the copy and forgets it. */
    fun delete(record: SaveImportMarkingRecord): Result<Unit> = runCatching {
        if (Files.exists(record.copy, LinkOption.NOFOLLOW_LINKS)) {
            SaveSourceLock.acquire(record.copy).getOrElse { throw it.toPlayerError() }.close()
            record.copy.toFile().deleteRecursivelyNoSymlink()
        }
        saveRecords(records().filterNot { it.copyPath == record.copyPath })
    }

    fun records(): List<SaveImportMarkingRecord> =
        if (recordsFile.isFile) runCatching { JSON.decodeFromString<List<SaveImportMarkingRecord>>(recordsFile.readText()) }.getOrElse { error ->
            calebxzhou.rdi.client.net.lgr.error(error) { "读取存档导入副本记录失败" }
            emptyList()
        } else emptyList()

    private fun saveRecords(records: List<SaveImportMarkingRecord>) {
        recordsFile.parentFile?.mkdirs()
        val temporary = File(recordsFile.path + ".tmp")
        temporary.writeText(JSON.encodeToString(records))
        Files.move(temporary.toPath(), recordsFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Last positions of the owner (`Data.Player`) and every playerdata file, as `(dimension, chunk)`. */
    private fun playerPositions(save: Path): List<Pair<UUID, Pair<String, Pair<Int, Int>>>> {
        val positions = LinkedHashMap<UUID, Pair<String, Pair<Int, Int>>>()
        val playerdata = save.resolve("playerdata")
        if (Files.isDirectory(playerdata, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(playerdata, "*.dat").use { files ->
                files.forEach { file ->
                    val uuid = runCatching { UUID.fromString(file.fileName.toString().removeSuffix(".dat")) }.getOrNull() ?: return@forEach
                    readPlayer(file)?.let { position(it) }?.let { positions[uuid] = it }
                }
            }
        }
        readPlayer(save.resolve("level.dat"))?.child("Data")?.child("Player")?.let { owner ->
            val uuid = owner.child("UUID")?.uuidOrNull()
            val position = position(owner)
            if (uuid != null && position != null) positions[uuid] = position
        }
        return positions.toList()
    }

    /** Positions are informational; an unreadable file is logged and left out of the summary. */
    private fun readPlayer(file: Path): calebxzau.rdi.anvilrw.remap.NbtNode? =
        NbtMetadataReader.openGzipFile(file).getOrElse { error ->
            calebxzhou.rdi.client.net.lgr.warn(error) { "读取玩家位置失败: ${file}" }
            null
        }?.root

    private fun position(player: calebxzau.rdi.anvilrw.remap.NbtNode): Pair<String, Pair<Int, Int>>? {
        val pos = player.child("Pos")?.listElements()?.mapNotNull { it.doubleOrNull() }?.takeIf { it.size == 3 } ?: return null
        val dimension = player.child("Dimension")?.stringOrNull()?.let(SaveSyncChunks::canonicalDimensionId) ?: "minecraft:overworld"
        return dimension to (Math.floorDiv(Math.floor(pos[0]).toLong(), 16L).toInt() to Math.floorDiv(Math.floor(pos[2]).toLong(), 16L).toInt())
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** gzip, zlib, uncompressed, and RDI's zstd (ID8, registered by the RDI client mod). */
        private val GAME_READABLE_COMPRESSIONS = setOf(1, 2, 3, 8)

        internal fun freeCopyFolder(saves: Path, sourceName: String): Path {
            val base = "${sourceName}-RDI导入"
            return generateSequence(1) { it + 1 }
                .map { index -> saves.resolve(if (index == 1) base else "${base}-${index}") }
                .first { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        }

        private fun Path.normalizedString(): String = toAbsolutePath().normalize().toString()

        /** The player-facing message for a failure of the save lock, snapshot or copy (plan §8.0). */
        internal fun Throwable.toPlayerError(): Throwable = when (this) {
            is calebxzau.rdi.anvilrw.remap.SaveInUseException -> RequestError("该存档正在被游戏使用，请先在游戏中退出该存档", this)
            is calebxzau.rdi.anvilrw.remap.SaveLinkException -> RequestError("存档中包含快捷方式或链接，无法导入", this)
            is calebxzau.rdi.anvilrw.remap.SaveChangedException -> RequestError("复制期间存档内容发生了变化，请关闭正在使用该存档的程序后重试", this)
            else -> this
        }
    }
}
