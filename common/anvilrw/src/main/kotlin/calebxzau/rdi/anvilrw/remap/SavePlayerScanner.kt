package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

enum class SaveLoader { Forge, NeoForge }

enum class SavePlayerKind { Online, Offline, Rdi, Unknown }

enum class NameHintSource { Mojang, UserCache, FtbTeams, FtbQuests }

data class NameHint(val name: String, val source: NameHintSource)

data class SavePlayer(
    val uuid: UUID,
    val kind: SavePlayerKind,
    /** Ordered by confidence. Mojang hints are added by the client after the scan. */
    val nameHints: List<NameHint>,
    /** Set for an offline player when a hint's offline UUID equals [uuid]. */
    val verifiedName: String?,
    val isSingleplayerHost: Boolean,
    /** The playerdata file's modification time, or `level.dat`'s for the singleplayer host. */
    val lastPlayed: Instant?,
    /** `stats/<uuid>.json` → `minecraft:custom` → `minecraft:play_time`. */
    val playTimeTicks: Long?,
)

data class SaveScanResult(
    val mcVersionName: String?,
    val dataVersion: Int?,
    /** `Data.ServerBrands`, the history of brands that ever opened the save. */
    val serverBrands: List<String>,
    /** Mod IDs of the root `fml.LoadingModList`, or null when the save has no `fml` compound. */
    val fmlModIds: List<String>?,
    /** The loader that saved the save last, from [fmlModIds]; null without `fml`. */
    val lastSavedLoader: SaveLoader?,
    val isHardcore: Boolean,
    /** `Data.Player.UUID`. The marking copy is launched with this UUID. */
    val singleplayerHostUuid: UUID?,
    val players: List<SavePlayer>,
    /** `data/rdi_sync_chunks.dat`, or null when the save has none. */
    val syncChunks: List<SaveSyncChunk>?,
    val totalBytes: Long,
)

/**
 * Finds the players of a save and the facts the import prechecks need. Pure file I/O: no network
 * access and no writes. All NBT is read through [NbtMetadataReader], within [RemapLimits].
 *
 * Links and special files fail the scan ([SaveLinkException]), so the owner learns about them before
 * anything is copied.
 */
class SavePlayerScanner {
    fun scan(worldDir: Path): Result<SaveScanResult> = remapResult {
        val snapshot = SaveSnapshot.take(worldDir).getOrThrow()
        val levelBytes = readStrictNbt(snapshot, "level.dat")
        val metadata = NbtMetadataReader.open(levelBytes).getOrThrow().levelMetadata().getOrThrow()
        val hostUuid = metadata.singleplayerUuid

        val playerUuids = LinkedHashSet<UUID>()
        snapshot.files.keys.sorted().forEach { path ->
            val stem = PLAYERDATA.matchEntire(path)?.groupValues?.get(1) ?: return@forEach
            playerUuids += UUID.fromString(stem)
        }
        hostUuid?.let { playerUuids += it }

        val userCache = readUserCache(worldDir)
        val players = playerUuids.map { uuid ->
            val hints = buildList {
                userCache[uuid]?.let { add(NameHint(it, NameHintSource.UserCache)) }
                readSnbtString(snapshot, "ftbteams/player/${uuid}.snbt", FTB_TEAMS_NAME)
                    ?.let { add(NameHint(it, NameHintSource.FtbTeams)) }
                readSnbtString(snapshot, "ftbquests/${uuid}.snbt", FTB_QUESTS_NAME)
                    ?.substringBeforeLast('#')
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(NameHint(it, NameHintSource.FtbQuests)) }
            }.distinctBy { it.name to it.source }
            val kind = classify(uuid)
            val isHost = uuid == hostUuid
            val modified = (if (isHost) "level.dat" else "playerdata/${uuid}.dat").let { snapshot.files[it]?.lastModified }
            SavePlayer(
                uuid = uuid,
                kind = kind,
                nameHints = hints,
                verifiedName = if (kind == SavePlayerKind.Offline) hints.firstOrNull { offlineUuid(it.name) == uuid }?.name else null,
                isSingleplayerHost = isHost,
                lastPlayed = modified?.toInstant(),
                playTimeTicks = readPlayTime(snapshot, uuid),
            )
        }

        val syncChunks = if (SaveSyncChunks.RELATIVE_PATH in snapshot.files) {
            val state = snapshot.files.getValue(SaveSyncChunks.RELATIVE_PATH)
            if (state.size > RemapLimits.STRICT_NBT_FILE_BYTES) {
                throw RemapLimitExceededException("${SaveSyncChunks.RELATIVE_PATH} exceeds ${RemapLimits.STRICT_NBT_FILE_BYTES} bytes")
            }
            SaveSyncChunks.parse(snapshot.readFile(SaveSyncChunks.RELATIVE_PATH).getOrThrow()).getOrThrow()
        } else {
            null
        }

        SaveScanResult(
            mcVersionName = metadata.versionName,
            dataVersion = metadata.dataVersion,
            serverBrands = metadata.serverBrands,
            fmlModIds = metadata.fmlModIds,
            lastSavedLoader = metadata.fmlModIds?.let { ids ->
                when {
                    "neoforge" in ids -> SaveLoader.NeoForge
                    "forge" in ids -> SaveLoader.Forge
                    else -> null
                }
            },
            isHardcore = metadata.hardcore,
            singleplayerHostUuid = hostUuid,
            players = players,
            syncChunks = syncChunks,
            totalBytes = snapshot.totalBytes,
        )
    }

    private fun readStrictNbt(snapshot: SaveSnapshot, relativePath: String): ByteArray {
        val state = snapshot.files[relativePath] ?: throw java.io.FileNotFoundException("${relativePath} is missing")
        if (state.size > RemapLimits.STRICT_NBT_FILE_BYTES) {
            throw RemapLimitExceededException("${relativePath} exceeds ${RemapLimits.STRICT_NBT_FILE_BYTES} bytes")
        }
        return BoundedIo.gunzip(snapshot.readFile(relativePath).getOrThrow(), RemapLimits.STRICT_NBT_FILE_BYTES, relativePath)
    }

    /** A small text file of the save, or null when it is absent, too large or not UTF-8. */
    private fun readText(snapshot: SaveSnapshot, relativePath: String): String? {
        val state = snapshot.files[relativePath] ?: return null
        if (state.size > HINT_FILE_BYTES) return null
        val bytes = snapshot.readFile(relativePath).getOrThrow()
        return if (TextUuidPatcher.isStrictUtf8(bytes)) bytes.toString(Charsets.UTF_8) else null
    }

    private fun readSnbtString(snapshot: SaveSnapshot, relativePath: String, pattern: Regex): String? =
        readText(snapshot, relativePath)?.let { pattern.find(it) }?.groupValues?.get(1)?.let(::unescape)

    private fun readPlayTime(snapshot: SaveSnapshot, uuid: UUID): Long? =
        readText(snapshot, "stats/${uuid}.json")?.let { PLAY_TIME.find(it) }?.groupValues?.get(1)?.toLongOrNull()

    /**
     * `usercache.json` of the game directory, when the save lives in `<game dir>/saves/<world>`.
     * Expired entries are still valid hints. It is outside the save, so it is read directly.
     */
    private fun readUserCache(worldDir: Path): Map<UUID, String> {
        val parent = worldDir.toAbsolutePath().normalize().parent ?: return emptyMap()
        if (parent.fileName?.toString() != "saves") return emptyMap()
        val file = parent.parent?.resolve("usercache.json") ?: return emptyMap()
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > HINT_FILE_BYTES) return emptyMap()
        val bytes = Files.readAllBytes(file)
        if (!TextUuidPatcher.isStrictUtf8(bytes)) return emptyMap()
        val names = HashMap<UUID, String>()
        for (entry in JSON_OBJECT.findAll(bytes.toString(Charsets.UTF_8))) {
            val name = JSON_NAME.find(entry.value)?.groupValues?.get(1)?.let(::unescape) ?: continue
            val uuid = JSON_UUID.find(entry.value)?.groupValues?.get(1) ?: continue
            names.putIfAbsent(UUID.fromString(uuid), name)
        }
        return names
    }

    companion object {
        /** Name hint files are small; anything larger is ignored rather than read. */
        private const val HINT_FILE_BYTES = 4L * 1024 * 1024

        private val PLAYERDATA = Regex("""playerdata/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.dat""")
        private val PLAY_TIME = Regex(""""minecraft:play_time"\s*:\s*(\d+)""")
        private val FTB_TEAMS_NAME = Regex("""\bplayer_name\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val FTB_QUESTS_NAME = Regex("""\bname\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val JSON_OBJECT = Regex("""\{[^{}]*}""")
        private val JSON_NAME = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val JSON_UUID = Regex(""""uuid"\s*:\s*"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"""")

        /** RDI identities are ObjectId-derived: 12 id bytes followed by 4 zero bytes. */
        fun isRdiUuid(uuid: UUID): Boolean = (uuid.leastSignificantBits and 0xffffffffL) == 0L

        fun classify(uuid: UUID): SavePlayerKind = when {
            isRdiUuid(uuid) -> SavePlayerKind.Rdi
            uuid.version() == 4 -> SavePlayerKind.Online
            uuid.version() == 3 -> SavePlayerKind.Offline
            else -> SavePlayerKind.Unknown
        }

        fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:${name}".toByteArray(Charsets.UTF_8))

        private fun unescape(value: String): String = value.replace(Regex("""\\(.)"""), "$1")
    }
}
