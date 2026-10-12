package calebxzau.rdi.mc.server.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupKind
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupPart
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupWorldToken
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackFiles
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackOutcome
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackRequest
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackState
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncChunkRollbackServiceTest {
    private val host: Path = Files.createTempDirectory("syncchunk-rollback-apply")
    private val worldDir: Path = host.resolve("world").createDirectories()
    private val root = SyncChunkBackupStore.rootOf(host)
    private val token = SyncChunkBackupWorldToken.readOrCreate(worldDir).getOrThrow()
    private val key = SyncChunkKey("minecraft:overworld", 3, -2)
    private val pos = ChunkPos(key.chunkX, key.chunkZ)
    private val player = "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat"
    private val laterPlayer = "playerdata/2a49bcbe-080c-4a3e-9c4a-0212b2fd2626.dat"
    private var clock = 1_700_000_000_000L

    private val folderOf: (String) -> Path = { dimensionId -> worldDir.resolve("dims").resolve(dimensionId.replace(':', '_')) }

    @AfterTest
    fun cleanUp() {
        host.toFile().deleteRecursively()
    }

    @Test
    fun restoresThePointAndTheBeforePointUndoesIt() {
        writeWorld(terrain("stone", lastUpdate = 10), entities = null, playerData = "inventory-v1")
        val target = capturePoint()
        writeWorld(terrain("water", lastUpdate = 900), entities = entities("bat"), playerData = "inventory-v2")
        writeFile(laterPlayer, "joined-later")

        // No request file pins the target here: the before-rollback point must not prune it (the target is days old).
        SyncChunkRollbackService.apply(root, worldDir, 42, request(target), folderOf)

        assertEquals("stone", readPart("region")!!.getString("block"))
        assertNull(readPart("entities"))
        assertEquals("inventory-v1", readFile(player))
        assertEquals("joined-later", readFile(laterPlayer))
        assertNull(SyncChunkRollbackFiles.readRequest(root).getOrThrow())
        val result = assertNotNull(SyncChunkRollbackFiles.readResult(root).getOrThrow())
        assertEquals(SyncChunkRollbackOutcome.Done, result.outcome)
        assertEquals(target, result.pointId)

        val store = openStore()
        val before = assertNotNull(store.point(assertNotNull(result.beforePointId)))
        assertEquals(SyncChunkBackupKind.BeforeRollback, before.kind)
        assertEquals(result.requestId, before.rollbackId)
        assertEquals(42, before.gameTime)

        applyRequested(43, request(before.id))
        assertEquals("water", readPart("region")!!.getString("block"))
        assertEquals(900, readPart("region")!!.getLong("LastUpdate"))
        assertEquals("bat", readPart("entities")!!.getString("mob"))
        assertEquals("inventory-v2", readFile(player))
    }

    @Test
    fun damagedBackupLeavesTheWorldUntouched() {
        writeWorld(terrain("stone"), entities = null, playerData = "inventory-v1")
        val target = capturePoint()
        writeWorld(terrain("water"), entities = null, playerData = "inventory-v2")
        damageObject(openStore().point(target)!!.chunks.single().terrain!!)
        val points = openStore().points().size

        applyRequested(0, request(target))

        assertEquals("water", readPart("region")!!.getString("block"))
        assertEquals("inventory-v2", readFile(player))
        assertNull(SyncChunkRollbackFiles.readRequest(root).getOrThrow())
        assertEquals(SyncChunkRollbackOutcome.Failed, SyncChunkRollbackFiles.readResult(root).getOrThrow()!!.outcome)
        assertEquals(points, openStore().points().size)
    }

    @Test
    fun requestForAReplacedWorldFails() {
        writeWorld(terrain("stone"), entities = null, playerData = "inventory-v1")
        val target = capturePoint()
        writeWorld(terrain("water"), entities = null, playerData = "inventory-v2")

        applyRequested(0, request(target).copy(worldToken = "0199c2a1-0000-7000-8000-000000000000"))

        assertEquals("water", readPart("region")!!.getString("block"))
        val result = SyncChunkRollbackFiles.readResult(root).getOrThrow()!!
        assertEquals(SyncChunkRollbackOutcome.Failed, result.outcome)
        assertTrue(result.message!!.contains("当前世界"))
    }

    @Test
    fun startedRollbackIsWrittenAgainWithoutAnotherBeforePoint() {
        writeWorld(terrain("stone"), entities = null, playerData = "inventory-v1")
        val target = capturePoint()
        writeWorld(terrain("water"), entities = null, playerData = "inventory-v2")
        val before = capturePoint()
        val points = openStore().points().size
        val applying = request(target).copy(state = SyncChunkRollbackState.Applying, beforePointId = before)

        applyRequested(0, applying)

        assertEquals("stone", readPart("region")!!.getString("block"))
        assertEquals(points, openStore().points().size)
        assertEquals(before, SyncChunkRollbackFiles.readResult(root).getOrThrow()!!.beforePointId)
    }

    @Test
    fun startedRollbackThatCannotContinueStopsTheStart() {
        writeWorld(terrain("stone"), entities = null, playerData = "inventory-v1")
        val target = capturePoint()
        val applying = request(target).copy(state = SyncChunkRollbackState.Applying, beforePointId = target)
        SyncChunkRollbackFiles.writeRequest(root, applying).getOrThrow()
        damageObject(openStore().point(target)!!.chunks.single().terrain!!)

        assertFailsWith<IllegalStateException> { applyRequested(0, applying) }
        assertEquals(applying, SyncChunkRollbackFiles.readRequest(root).getOrThrow())
        assertNull(SyncChunkRollbackFiles.readResult(root).getOrThrow())
    }

    /** Like the server start: the request is on disk while it is applied. */
    private fun applyRequested(gameTime: Long, request: SyncChunkRollbackRequest) {
        SyncChunkRollbackFiles.writeRequest(root, request).getOrThrow()
        SyncChunkRollbackService.apply(root, worldDir, gameTime, request, folderOf)
    }

    private fun request(pointId: String) = SyncChunkRollbackRequest(
        id = SyncChunkBackupIds.newUuidV7(clock).toString(),
        pointId = pointId,
        worldToken = token,
        requestedBy = "665f00000000000000000001",
        requestedAt = clock,
    )

    private fun openStore(): SyncChunkBackupStore = SyncChunkBackupStore.open(root, token, now = clock).getOrThrow().first

    /** An automatic point of what is on disk now, the way the backup service would take it. */
    private fun capturePoint(): String {
        clock += 5 * 60_000L
        val store = openStore()
        val id = SyncChunkBackupIds.newUuidV7(clock).toString()
        val session = store.begin(id, SyncChunkBackupKind.Auto, clock, 0)
        session.putChunk(
            key,
            present(readPart("region")!!, CanonicalNbtHash.VOLATILE_TERRAIN_KEYS),
            readPart("entities")?.let { present(it, emptySet()) } ?: SyncChunkBackupPart.Absent,
            SyncChunkBackupPart.Absent,
        ).getOrThrow()
        listOf(player, laterPlayer).filter { worldDir.resolve(it).exists() }.forEach { session.putFile(it, worldDir.resolve(it).readBytes()).getOrThrow() }
        assertNotNull(session.commit(clock).getOrThrow())
        return id
    }

    private fun present(tag: CompoundTag, ignored: Set<String>): SyncChunkBackupPart.Present {
        val raw = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { NbtIo.write(tag, it) } }.toByteArray()
        return SyncChunkBackupPart.Present(CanonicalNbtHash.sha1(tag, ignored), raw)
    }

    private fun terrain(block: String, lastUpdate: Long = 0) = CompoundTag().apply {
        putString("block", block)
        putLong("LastUpdate", lastUpdate)
    }

    private fun entities(mob: String) = CompoundTag().apply { putString("mob", mob) }

    private fun writeWorld(terrain: CompoundTag, entities: CompoundTag?, playerData: String) {
        storage("region").use { it.write(pos, terrain) }
        storage("entities").use { it.write(pos, entities) }
        writeFile(player, playerData)
    }

    private fun readPart(folder: String): CompoundTag? = storage(folder).use { it.read(pos) }

    private fun storage(folder: String) = RegionFileStorage(folderOf(key.dimensionId).resolve(folder), false)

    private fun writeFile(path: String, text: String) {
        worldDir.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun readFile(path: String): String = worldDir.resolve(path).readText()

    private fun damageObject(hash: String) {
        val file = root.resolve("objects").resolve(hash.substring(0, 2)).resolve("${hash}${SyncChunkBackupStore.OBJECT_SUFFIX}")
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        file.writeBytes(bytes)
    }
}
