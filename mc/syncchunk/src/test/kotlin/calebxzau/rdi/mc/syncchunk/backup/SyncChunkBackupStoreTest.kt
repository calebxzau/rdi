package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import com.github.luben.zstd.ZstdInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncChunkBackupStoreTest {
    private val host: Path = Files.createTempDirectory("syncchunk-backup-store")
    private val root = SyncChunkBackupStore.rootOf(host)
    private val token = "0199c2a1-7f3e-7c4b-9a10-3b6e2d4f8a11"
    private val minute = 60_000L
    private var clock = 1_000_000L * minute

    private val home = SyncChunkKey("minecraft:overworld", 0, 0)
    private val farm = SyncChunkKey("minecraft:overworld", 1, 0)
    private val player = "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat"

    @AfterTest
    fun cleanUp() {
        host.toFile().deleteRecursively()
    }

    @Test
    fun firstPointStoresEverythingAndWritesTheIndex() {
        val store = open()
        val result = assertNotNull(capture(store) {
            putChunk(home, part("home-v1"), SyncChunkBackupPart.Absent, part("poi")).getOrThrow()
            putFile(player, "inventory-v1".toByteArray()).getOrThrow()
        })

        val point = result.point
        assertEquals(1, point.chunkCount)
        assertEquals(1, point.playerCount)
        assertEquals(SyncChunkBackupChanges(1, 1, 1, 1, 1), point.changed)
        assertTrue(point.addedBytes > 0)
        assertEquals(3, objectCount())

        val index = SyncChunkBackupJson.decodeIndex(root.resolve(SyncChunkBackupStore.INDEX).readText()).getOrThrow()
        assertEquals(token, index.worldToken)
        assertEquals(listOf(point.id), index.points.map { it.id })
        assertEquals(store.usedBytes, index.usedBytes)
        assertEquals(SyncChunkBackupStore.MAX_BYTES, index.maxBytes)
        assertEquals("${token}\n", root.resolve(SyncChunkBackupStore.WORLD_TOKEN).readText())
    }

    @Test
    fun objectsHoldTheRawBytesAsChecksummedZstd() {
        val store = open()
        val raw = "inventory-v1".repeat(100).toByteArray()
        capture(store) { putFile(player, raw).getOrThrow() }
        val file = objectFile(store.latest()!!.files.single().hash)
        val stored = Files.readAllBytes(file)
        assertTrue(stored.size < raw.size)
        assertContentEquals(raw, ZstdInputStream(Files.newInputStream(file)).use { it.readBytes() })

        // A flipped byte must fail the frame checksum instead of decoding to wrong data.
        stored[stored.size - 6] = (stored[stored.size - 6].toInt() xor 0x01).toByte()
        assertTrue(runCatching { ZstdInputStream(stored.inputStream()).use { it.readBytes() } }.isFailure)
    }

    @Test
    fun unchangedCaptureCreatesNoPoint() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val usedBefore = store.usedBytes

        assertNull(capture(store) { fill(home = "home-v1", inventory = "inventory-v1") })
        assertEquals(1, store.points().size)
        assertEquals(usedBefore, store.usedBytes)
    }

    @Test
    fun onlyChangedPartsAreStoredAgain() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val objectsBefore = objectCount()

        val point = assertNotNull(capture(store) { fill(home = "home-v2", inventory = "inventory-v1") }).point
        assertEquals(SyncChunkBackupChanges(terrainChunks = 1, entityChunks = 0, poiChunks = 0, players = 0, files = 0), point.changed)
        assertEquals(objectsBefore + 1, objectCount())
        assertEquals(2, store.points().size)
    }

    @Test
    fun contentThatChangesBackReusesTheOldObject() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        capture(store) { fill(home = "home-v2", inventory = "inventory-v1") }
        val objects = objectCount()

        val point = assertNotNull(capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }).point
        assertEquals(1, point.changed.terrainChunks)
        assertEquals(0, point.addedBytes)
        assertEquals(objects, objectCount())
    }

    @Test
    fun unreadablePartsAndFilesKeepThePreviousCopy() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val before = store.latest()!!

        capture(store) {
            putChunk(home, SyncChunkBackupPart.Unavailable, SyncChunkBackupPart.Unavailable, SyncChunkBackupPart.Unavailable).getOrThrow()
            assertTrue(reuseFile(player))
            putChunk(farm, part("farm"), SyncChunkBackupPart.Absent, SyncChunkBackupPart.Absent).getOrThrow()
        }
        val after = store.latest()!!
        assertEquals(before.chunks.single { it.key == home }, after.chunks.single { it.key == home })
        assertEquals(before.files, after.files)
    }

    @Test
    fun unreadableNewChunkIsLeftOutInsteadOfRecordedAsEmpty() {
        val store = open()
        capture(store) {
            assertFalse(putChunk(home, part("home"), SyncChunkBackupPart.Unavailable, SyncChunkBackupPart.Absent).getOrThrow())
            assertFalse(reuseChunk(farm))
            putFile(player, "inventory".toByteArray()).getOrThrow()
        }
        assertEquals(emptyList(), store.latest()!!.chunks)
        assertEquals(1, objectCount())
    }

    @Test
    fun expiredPointsAreDroppedAndOnlyUnreferencedObjectsDeleted() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val shared = store.latest()!!.files.single().hash
        clock += 25 * 60 * minute
        capture(store) { fill(home = "home-v2", inventory = "inventory-v1") }
        clock += 8 * 24 * 60 * minute

        val result = assertNotNull(capture(store) { fill(home = "home-v3", inventory = "inventory-v1") })
        assertEquals(2, result.droppedPoints)
        assertEquals(listOf(result.point.id), store.points().map { it.id })
        assertTrue(objectFile(shared).exists())
        assertEquals(store.latest()!!.objectHashes().size, objectCount())
        assertEquals(1, Files.list(root.resolve("points")).use { it.count() })
    }

    @Test
    fun overTheSizeLimitDropsTheOldestButKeepsTheNewest() {
        val store = open(maxBytes = 1)
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val newest = assertNotNull(capture(store) { fill(home = "home-v2", inventory = "inventory-v2") })
        assertEquals(1, newest.droppedPoints)
        assertEquals(listOf(newest.point.id), store.points().map { it.id })
        assertEquals(store.latest()!!.objectHashes().size, objectCount())
    }

    @Test
    fun abortRemovesObjectsOnlyThatSessionWrote() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val objects = objectCount()
        val session = store.begin(SyncChunkBackupIds.newUuidV7(clock).toString(), SyncChunkBackupKind.Auto, clock, 0)
        session.fill(home = "home-v2", inventory = "inventory-v1")
        assertEquals(objects + 1, objectCount())
        session.abort().getOrThrow()
        assertEquals(objects, objectCount())
    }

    @Test
    fun reopeningLoadsPointsAndRemovesOrphans() {
        val store = open()
        capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }
        val orphan = objectFile("c".repeat(40))
        orphan.parent.createDirectories()
        Files.write(orphan, byteArrayOf(1))
        val broken = root.resolve("points").resolve("broken.json")
        broken.writeText("{")

        val (reopened, report) = SyncChunkBackupStore.open(root, token, now = clock).getOrThrow()
        assertFalse(report.discardedForOtherWorld)
        assertEquals(1, report.orphanObjects)
        assertEquals(listOf("broken.json"), report.invalidPoints.map { it.name })
        assertFalse(broken.exists())
        assertFalse(orphan.exists())
        assertEquals(store.points(), reopened.points())
        assertEquals(store.usedBytes, reopened.usedBytes)
        assertNull(capture(reopened) { fill(home = "home-v1", inventory = "inventory-v1") })
    }

    @Test
    fun pointWithMissingObjectIsRemovedOnOpen() {
        val store = open()
        val older = assertNotNull(capture(store) { fill(home = "home-v1", inventory = "inventory-v1") }).point
        val olderHome = store.point(older.id)!!.chunks.single().terrain!!
        val newer = assertNotNull(capture(store) { fill(home = "home-v2", inventory = "inventory-v1") }).point
        val newerPoint = store.point(newer.id)!!
        Files.delete(objectFile(olderHome))

        val (reopened, report) = SyncChunkBackupStore.open(root, token, now = clock).getOrThrow()
        assertEquals(listOf(older.id), report.invalidPoints.map { it.name })
        assertEquals(0, report.orphanObjects)
        assertEquals(listOf(newer.id), reopened.points().map { it.id })
        assertFalse(root.resolve("points").resolve("${older.id}.json").exists())
        assertEquals(newerPoint.objectHashes().size, objectCount())
        newerPoint.objectHashes().forEach { assertTrue(objectFile(it).exists()) }

        val onDisk = Files.walk(root.resolve("objects")).use { stream -> stream.asSequence().filter { Files.isRegularFile(it) }.sumOf { Files.size(it) } } +
            Files.size(root.resolve("points").resolve("${newer.id}.json"))
        val index = SyncChunkBackupJson.decodeIndex(root.resolve(SyncChunkBackupStore.INDEX).readText()).getOrThrow()
        assertEquals(onDisk, index.usedBytes)
        assertEquals(listOf(newer.id), index.points.map { it.id })
        assertNull(capture(reopened) { fill(home = "home-v2", inventory = "inventory-v1") })
    }

    @Test
    fun anotherWorldsPointsAreDiscarded() {
        capture(open()) { fill(home = "home-v1", inventory = "inventory-v1") }

        val (store, report) = SyncChunkBackupStore.open(root, "0199c2a1-0000-7000-8000-000000000000", now = clock).getOrThrow()
        assertTrue(report.discardedForOtherWorld)
        assertEquals(emptyList(), store.points())
        assertEquals(0, objectCount())
        assertEquals("0199c2a1-0000-7000-8000-000000000000\n", root.resolve(SyncChunkBackupStore.WORLD_TOKEN).readText())
    }

    @Test
    fun worldTokenIsCreatedOnceAndReused() {
        val world = host.resolve("world")
        val token = SyncChunkBackupWorldToken.readOrCreate(world).getOrThrow()
        assertEquals(token, SyncChunkBackupWorldToken.readOrCreate(world) { error("must not create") }.getOrThrow())
        assertFalse(SyncChunkBackupFileRules.includes(SyncChunkBackupWorldToken.RELATIVE_PATH))

        world.resolve(SyncChunkBackupWorldToken.RELATIVE_PATH).writeText("garbage")
        assertTrue(SyncChunkBackupWorldToken.readOrCreate(world).isFailure)
    }

    private fun open(maxBytes: Long = SyncChunkBackupStore.MAX_BYTES): SyncChunkBackupStore =
        SyncChunkBackupStore.open(root, token, maxBytes, clock).getOrThrow().first

    private fun capture(store: SyncChunkBackupStore, block: SyncChunkBackupStore.Session.() -> Unit): SyncChunkBackupStore.CommitResult? {
        clock += 5 * minute
        val session = store.begin(SyncChunkBackupIds.newUuidV7(clock).toString(), SyncChunkBackupKind.Auto, clock, clock / 50)
        session.block()
        return session.commit(clock).getOrThrow()
    }

    private fun SyncChunkBackupStore.Session.fill(home: String, inventory: String) {
        putChunk(this@SyncChunkBackupStoreTest.home, part(home), part("entities"), SyncChunkBackupPart.Absent).getOrThrow()
        putFile(player, inventory.toByteArray()).getOrThrow()
    }

    private fun part(content: String): SyncChunkBackupPart.Present {
        val raw = content.toByteArray()
        return SyncChunkBackupPart.Present(SyncChunkBackupIds.sha1Hex(raw), raw)
    }

    private fun objectFile(hash: String): Path = root.resolve("objects").resolve(hash.substring(0, 2)).resolve("${hash}${SyncChunkBackupStore.OBJECT_SUFFIX}")

    private fun objectCount(): Int {
        val objects = root.resolve("objects")
        if (!Files.isDirectory(objects)) return 0
        return Files.walk(objects).use { stream -> stream.asSequence().count { Files.isRegularFile(it) } }
    }
}
