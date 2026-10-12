package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncChunkRollbackTest {
    private val host: Path = Files.createTempDirectory("syncchunk-rollback")
    private val root = SyncChunkBackupStore.rootOf(host)
    private val token = "0199c2a1-7f3e-7c4b-9a10-3b6e2d4f8a11"
    private val minute = 60_000L
    private var clock = 1_000_000L * minute

    private val home = SyncChunkKey("minecraft:overworld", 0, 0)
    private val farm = SyncChunkKey("minecraft:the_nether", 3, -2)
    private val player = "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat"

    @AfterTest
    fun cleanUp() {
        host.toFile().deleteRecursively()
    }

    @Test
    fun requestAndResultSurviveTheirFiles() {
        assertNull(SyncChunkRollbackFiles.readRequest(root).getOrThrow())
        assertNull(SyncChunkRollbackFiles.readResult(root).getOrThrow())

        val requested = request("point-a")
        SyncChunkRollbackFiles.writeRequest(root, requested).getOrThrow()
        assertEquals(requested, SyncChunkRollbackFiles.readRequest(root).getOrThrow())

        val applying = requested.copy(state = SyncChunkRollbackState.Applying, beforePointId = "point-before")
        SyncChunkRollbackFiles.writeRequest(root, applying).getOrThrow()
        assertEquals(applying, SyncChunkRollbackFiles.readRequest(root).getOrThrow())
        assertEquals(1, Files.list(root).use { it.count() })

        val result = SyncChunkRollbackResult(null, null, null, SyncChunkRollbackOutcome.Failed, "回档请求无法读取", clock)
        SyncChunkRollbackFiles.writeResult(root, result).getOrThrow()
        assertEquals(result, SyncChunkRollbackFiles.readResult(root).getOrThrow())

        SyncChunkRollbackFiles.deleteRequest(root).getOrThrow()
        assertNull(SyncChunkRollbackFiles.readRequest(root).getOrThrow())
    }

    @Test
    fun invalidRequestsAreRejected() {
        assertFailsWith<IllegalArgumentException> { request("p").copy(state = SyncChunkRollbackState.Applying) }
        assertFailsWith<IllegalStateException> { request("p").abandon() }
        root.toFile().mkdirs()
        root.resolve(SyncChunkRollbackFiles.REQUEST).writeText("{\"formatVersion\":1}")
        assertTrue(SyncChunkRollbackFiles.readRequest(root).isFailure)
    }

    @Test
    fun abandoningRestoresTheBeforeRollbackPoint() {
        val applying = request("target").copy(state = SyncChunkRollbackState.Applying, beforePointId = "before")
        val abandoned = applying.abandon()
        assertEquals("before", abandoned.pointId)
        assertTrue(abandoned.abandoned)
        assertEquals(setOf("before"), abandoned.pinnedPointIds())
        assertEquals(setOf("target", "before"), applying.pinnedPointIds())
    }

    @Test
    fun beforeRollbackPointIsStoredEvenWhenNothingChanged() {
        val store = open()
        val auto = assertNotNull(capture(store, SyncChunkBackupKind.Auto) { fill("home-v1", "inventory-v1") }).point
        val before = assertNotNull(capture(store, SyncChunkBackupKind.BeforeRollback, rollbackId = "rollback-1") { fill("home-v1", "inventory-v1") }).point
        assertEquals(SyncChunkBackupKind.BeforeRollback, before.kind)
        assertEquals("rollback-1", before.rollbackId)
        assertEquals(0L, before.addedBytes)
        assertEquals(listOf(auto.id, before.id), store.points().map { it.id })
        assertEquals("rollback-1", open().point(before.id)!!.rollbackId)
    }

    @Test
    fun pendingRollbackPointsSurviveRetentionAndTheSizeLimit() {
        val store = open(maxBytes = 1)
        val target = assertNotNull(capture(store) { fill("home-v1", "inventory-v1") }).point
        SyncChunkRollbackFiles.writeRequest(root, request(target.id)).getOrThrow()
        clock += 8 * 24 * 60 * minute
        val newest = assertNotNull(capture(store) { fill("home-v2", "inventory-v2") }).point
        assertEquals(listOf(target.id, newest.id), store.points().map { it.id })

        SyncChunkRollbackFiles.deleteRequest(root).getOrThrow()
        val next = assertNotNull(capture(store) { fill("home-v3", "inventory-v3") })
        assertEquals(listOf(next.point.id), store.points().map { it.id })
    }

    @Test
    fun unreadableRequestStopsPruning() {
        val store = open(maxBytes = 1)
        val first = assertNotNull(capture(store) { fill("home-v1", "inventory-v1") }).point
        root.resolve(SyncChunkRollbackFiles.REQUEST).writeText("{")
        val second = assertNotNull(capture(store) { fill("home-v2", "inventory-v2") })
        assertEquals(0, second.droppedPoints)
        assertEquals(listOf(first.id, second.point.id), store.points().map { it.id })
    }

    @Test
    fun objectsAreReadBackAndDamageIsDetected() {
        val store = open()
        val point = store.point(assertNotNull(capture(store) { fill("home-v1", "inventory-v1") }).point.id)!!
        val terrain = point.chunks.single { it.key == home }.terrain!!
        assertContentEquals("home-v1".toByteArray(), store.readObject(terrain).getOrThrow())
        assertContentEquals("inventory-v1".toByteArray(), store.readFile(point.files.single()).getOrThrow())
        assertTrue(store.readFile(point.files.single().copy(size = 1)).isFailure)
        assertTrue(store.readObject("d".repeat(40)).isFailure)

        val file = objectFile(terrain)
        val damaged = file.readBytes()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 0x55).toByte()
        file.writeBytes(damaged)
        assertTrue(store.readObject(terrain).isFailure)
        file.writeBytes(damaged.copyOf(damaged.size - 3))
        assertTrue(store.readObject(terrain).isFailure)
    }

    @Test
    fun targetMustBelongToThisWorldAndStillExist() {
        val store = open()
        val point = assertNotNull(capture(store) { fill("home-v1", "inventory-v1") }).point
        assertEquals(point.id, SyncChunkRollbackPlan.target(store, request(point.id)).getOrThrow().id)
        assertTrue(SyncChunkRollbackPlan.target(store, request(point.id).copy(worldToken = "0199c2a1-0000-7000-8000-000000000000")).isFailure)
        assertTrue(SyncChunkRollbackPlan.target(store, request("missing")).isFailure)
    }

    @Test
    fun beforeRollbackCoversTheTargetAndTheLatestChunks() {
        val store = open()
        val target = assertNotNull(capture(store) { fill("home-v1", "inventory-v1") }).point
        capture(store) {
            fill("home-v2", "inventory-v1")
            putChunk(farm, part("farm"), SyncChunkBackupPart.Absent, SyncChunkBackupPart.Absent).getOrThrow()
        }
        assertEquals(listOf(home, farm), SyncChunkRollbackPlan.beforeRollbackKeys(store, store.point(target.id)!!))
    }

    private fun request(pointId: String) = SyncChunkRollbackRequest(
        id = SyncChunkBackupIds.newUuidV7(clock).toString(),
        pointId = pointId,
        worldToken = token,
        requestedBy = "665f00000000000000000001",
        requestedAt = clock,
    )

    private fun open(maxBytes: Long = SyncChunkBackupStore.MAX_BYTES): SyncChunkBackupStore =
        SyncChunkBackupStore.open(root, token, maxBytes, clock).getOrThrow().first

    private fun capture(
        store: SyncChunkBackupStore,
        kind: SyncChunkBackupKind = SyncChunkBackupKind.Auto,
        rollbackId: String? = null,
        block: SyncChunkBackupStore.Session.() -> Unit,
    ): SyncChunkBackupStore.CommitResult? {
        clock += 5 * minute
        val session = store.begin(SyncChunkBackupIds.newUuidV7(clock).toString(), kind, clock, clock / 50, rollbackId)
        session.block()
        return session.commit(clock).getOrThrow()
    }

    private fun SyncChunkBackupStore.Session.fill(home: String, inventory: String) {
        putChunk(this@SyncChunkRollbackTest.home, part(home), part("entities"), SyncChunkBackupPart.Absent).getOrThrow()
        putFile(player, inventory.toByteArray()).getOrThrow()
    }

    private fun part(content: String): SyncChunkBackupPart.Present {
        val raw = content.toByteArray()
        return SyncChunkBackupPart.Present(SyncChunkBackupIds.sha1Hex(raw), raw)
    }

    private fun objectFile(hash: String): Path =
        root.resolve("objects").resolve(hash.substring(0, 2)).resolve("${hash}${SyncChunkBackupStore.OBJECT_SUFFIX}")
}
