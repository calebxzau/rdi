package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

data class SavePlayerPosition(val dimension: String, val x: Double, val y: Double, val z: Double)

data class SaveMarkingPlayer(val player: SavePlayer, val position: SavePlayerPosition?) {
    val displayName: String get() = player.verifiedName ?: player.nameHints.firstOrNull()?.name
        ?: if (player.isSingleplayerHost) "单机存档主人" else "角色${player.uuid.toString().take(8)}"
}

/** Callers hold the save lock. Preparation is only allowed in an unpublished staging copy. */
object SaveMarkingIdentity {
    fun players(world: Path): Result<List<SaveMarkingPlayer>> = remapResult {
        val scan = SavePlayerScanner().scan(world).getOrThrow()
        val level = NbtMetadataReader.openGzipFile(world.resolve("level.dat")).getOrThrow()
        val ownerData = level.root.uniqueChild("Data")?.uniqueChild("Player")
        require(ownerData == null || ownerData.uniqueChild("UUID")?.uuidOrNull() == scan.singleplayerHostUuid && scan.singleplayerHostUuid != null) {
            "单机存档主人的UUID无效"
        }
        scan.players.sortedWith(compareByDescending<SavePlayer> { it.isSingleplayerHost }.thenByDescending { it.lastPlayed })
            .map { player ->
                val data = if (player.isSingleplayerHost) requireNotNull(level.root.child("Data")?.child("Player"))
                    else NbtMetadataReader.openGzipFile(world.resolve("playerdata/${player.uuid}.dat")).getOrThrow().root
                require(data.uniqueChild("UUID")?.uuidOrNull() == player.uuid) { "玩家文件与角色UUID不一致：${player.uuid}" }
                val coordinates = data.child("Pos")?.listElements()?.mapNotNull { it.doubleOrNull() }
                    ?.takeIf { it.size == 3 && it.all(Double::isFinite) }
                val dimension = data.child("Dimension")?.stringOrNull()
                SaveMarkingPlayer(player, if (coordinates != null && dimension != null)
                    SavePlayerPosition(dimension, coordinates[0], coordinates[1], coordinates[2]) else null)
            }
    }

    /** Backups are outside the world, so neither Minecraft nor the uploader sees them. */
    fun prepare(world: Path, selected: UUID?, backup: Path): Result<UUID?> = remapResult {
        SaveSnapshot.take(world).getOrThrow() // Reject links before reading or writing any nested path.
        val levelPath = world.resolve("level.dat")
        val level = NbtMetadataReader.openGzipFile(levelPath).getOrThrow()
        val owner = level.levelMetadata().getOrThrow().singleplayerUuid
        val roles = players(world).getOrThrow()
        require(if (selected == null) roles.isEmpty() else roles.any { it.player.uuid == selected }) { "所选角色已不存在，请重新选择存档" }
        if (selected == null) return@remapResult owner
        val ownerPayload = level.compoundPayload("Data", "Player")
        val selectedPayload = if (selected == owner) requireNotNull(ownerPayload) else
            requireNotNull(NbtMetadataReader.openGzipFile(world.resolve("playerdata/${selected}.dat")).getOrThrow().compoundPayload())
        val changes = linkedMapOf<Path, ByteArray>()
        if (owner != null) changes[world.resolve("playerdata/${owner}.dat")] =
            BoundedIo.gzip(NbtMetadataReader.rootDocument(requireNotNull(ownerPayload)))
        changes[levelPath] = BoundedIo.gzip(level.replaceCompound(listOf("Data"), "Player", selectedPayload).getOrThrow())
        val oldLevel = world.resolve("level.dat_old")
        if (Files.exists(oldLevel, LinkOption.NOFOLLOW_LINKS)) {
            changes[oldLevel] = BoundedIo.gzip(NbtMetadataReader.openGzipFile(oldLevel).getOrThrow()
                .replaceCompound(listOf("Data"), "Player", selectedPayload).getOrThrow())
        }
        // Finish all backups before any replacement. An interrupted staging copy is never published.
        Files.createDirectories(backup)
        changes.keys.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
            val destination = backup.resolve(world.relativize(path))
            Files.createDirectories(destination.parent)
            Files.copy(path, destination)
        }
        changes.forEach { (path, bytes) ->
            Files.createDirectories(path.parent)
            java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
        }
        require(NbtMetadataReader.readLevelDat(levelPath).getOrThrow().singleplayerUuid == selected)
        owner
    }
}
