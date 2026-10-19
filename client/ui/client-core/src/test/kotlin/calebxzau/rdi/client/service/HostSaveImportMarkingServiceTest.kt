package calebxzau.rdi.client.service

import calebxzhou.rdi.common.exception.RequestError
import calebxzau.rdi.anvilrw.remap.NbtMetadataReader
import calebxzau.rdi.anvilrw.remap.SaveSourceLock
import kotlin.test.assertContentEquals
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostSaveImportMarkingServiceTest {
    private val root = createTempDirectory("marking-service")
    private val service = HostSaveImportMarkingService(root.resolve("records.json").toFile(), clock = { 42 })
    private val owner = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val rdi = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val friend = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")

    private fun save(name: String = "新的世界", host: UUID? = owner): Path {
        val save = root.resolve("source").resolve(name)
        Files.createDirectories(save.resolve("region"))
        Files.write(save.resolve("level.dat"), levelDat(host, x = 8.0, z = 8.0))
        Files.write(save.resolve("region/r.0.0.mca"), ByteArray(8192))
        return save
    }

    @Test
    fun copiesNextToEarlierCopiesAndRemembersThem() {
        val saves = root.resolve("pack/saves")
        val source = save()

        val first = service.createCopy(source, "host", saves, rdi).getOrThrow()
        val second = service.createCopy(source, "host", saves, rdi).getOrThrow()

        assertEquals("新的世界-RDI导入", first.copyFolderName)
        assertEquals("新的世界-RDI导入-2", second.copyFolderName)
        assertTrue(Files.isRegularFile(first.copy.resolve("region/r.0.0.mca")))
        SaveSourceLock.acquire(first.copy).getOrThrow().close() // Preparation releases its lock before publication.
        assertEquals(owner.toString(), first.launchUuid, "the save's owner UUID is used for the launch")
        assertEquals(second, service.findReusable(source, "host"))
        assertNull(service.findReusable(source, "other-host"))

        service.delete(second).getOrThrow()
        assertFalse(Files.exists(second.copy))
        assertEquals(first, service.findReusable(source, "host"))
    }

    @Test
    fun aSaveWithoutOwnerLaunchesAsTheRdiAccount() {
        val record = service.createCopy(save(host = null), "host", root.resolve("saves"), rdi).getOrThrow()
        assertEquals(rdi.toString(), record.launchUuid)
    }

    @Test
    fun aCopyOpenedNormallyIsRejected() {
        val record = service.createCopy(save(), "host", root.resolve("saves"), rdi).getOrThrow()
        service.checkIdentity(record).getOrThrow()

        Files.write(record.copy.resolve("level.dat"), levelDat(rdi, x = 8.0, z = 8.0))

        assertIs<RequestError>(service.checkIdentity(record).exceptionOrNull())
    }

    @Test
    fun summaryCountsMarksAndPlayersOutside() {
        val record = service.createCopy(save(), "host", root.resolve("saves"), rdi).getOrThrow()
        Files.createDirectories(record.copy.resolve("data"))
        Files.write(record.copy.resolve("data/rdi_sync_chunks.dat"), syncList(listOf("minecraft:overworld" to (0 to 0), "minecraft:the_nether" to (3 to 3))))
        Files.createDirectories(record.copy.resolve("playerdata"))
        Files.write(record.copy.resolve("playerdata/${friend}.dat"), gzip(nbt { player(friend, 100.0, 100.0) }))

        val summary = service.summary(record).getOrThrow()

        assertEquals(2, summary.marked.size)
        assertEquals(mapOf("minecraft:overworld" to 1, "minecraft:the_nether" to 1), summary.markedByDimension)
        assertEquals(0, summary.presentCount, "the region file has no generated chunks")
        assertEquals(listOf(friend), summary.playersOutside, "the owner stands in the kept chunk 0,0")
    }

    @Test
    fun copiesWithUnreadableChunkCompressionAreRefused() {
        val record = service.createCopy(save(), "host", root.resolve("saves"), rdi).getOrThrow()
        Files.write(record.copy.resolve("region/r.0.0.mca"), region(listOf(2, 8, 8 or 0x80)))
        Files.createDirectories(record.copy.resolve("data/somemod"))
        Files.write(record.copy.resolve("data/somemod/r.0.0.mca"), region(listOf(4)))
        service.checkReadable(record).getOrThrow()

        Files.createDirectories(record.copy.resolve("DIM-1/region"))
        Files.write(record.copy.resolve("DIM-1/region/r.0.0.mca"), region(listOf(4, 4)))

        val error = assertIs<RequestError>(service.checkReadable(record).exceptionOrNull())
        assertTrue(error.message!!.contains("2个区块") && error.message!!.contains("LZ4"), error.message)
    }

    @Test
    fun selectingAnotherPlayerPreservesOwnerAndOriginalBytes() {
        val source = save()
        val original = Files.readAllBytes(source.resolve("level.dat"))
        Files.write(source.resolve("level.dat_old"), original)
        Files.createDirectories(source.resolve("playerdata"))
        val staleOwner = gzip(nbt { player(owner, 999.0, 999.0) })
        val friendData = gzip(nbt { player(friend, 128.0, -32.0); string("ModString", "中文\u0000😀") })
        Files.write(source.resolve("playerdata/${owner}.dat"), staleOwner)
        Files.write(source.resolve("playerdata/${friend}.dat"), friendData)

        val roles = service.players(source).getOrThrow()
        assertEquals(owner, roles.first().player.uuid)
        assertEquals(128.0, roles.single { it.player.uuid == friend }.position!!.x)
        val record = service.createCopy(source, "host", root.resolve("pack/saves"), rdi, friend).getOrThrow()

        assertEquals(friend.toString(), record.launchUuid)
        assertEquals(owner.toString(), record.originalOwnerUuid)
        assertEquals(friend.toString(), record.selectedPlayerUuid)
        assertEquals(1, record.identityVersion)
        for (file in listOf("level.dat", "level.dat_old")) {
            val level = NbtMetadataReader.openGzipFile(record.copy.resolve(file)).getOrThrow()
            assertEquals(friend, level.levelMetadata().getOrThrow().singleplayerUuid)
            assertEquals("中文\u0000😀", level.root.child("Data")!!.child("Player")!!.child("ModString")!!.stringOrNull())
            assertEquals("1.20.1", level.levelMetadata().getOrThrow().versionName)
        }
        val preservedOwner = NbtMetadataReader.openGzipFile(record.copy.resolve("playerdata/${owner}.dat")).getOrThrow()
        assertEquals(8.0, preservedOwner.root.child("Pos")!!.listElements()!![0].doubleOrNull())
        assertContentEquals(friendData, Files.readAllBytes(record.copy.resolve("playerdata/${friend}.dat")))
        assertContentEquals(original, Files.readAllBytes(source.resolve("level.dat")))
        assertContentEquals(staleOwner, Files.readAllBytes(source.resolve("playerdata/${owner}.dat")))
        Files.walk(root.resolve("pack/rdi-save-import-staging")).use { files ->
            val backup = files.filter { it.endsWith("backup/playerdata/${owner}.dat") }.findFirst().orElseThrow()
            assertContentEquals(staleOwner, Files.readAllBytes(backup))
        }
        service.checkIdentity(record).getOrThrow()
        assertEquals(record, service.findReusable(source, "host"))
        assertContentEquals(staleOwner, Files.readAllBytes(Path.of(record.identityBackupPath!!).resolve("playerdata/${owner}.dat")))
        Files.write(record.copy.resolve("playerdata/${owner}.dat"), friendData)
        assertTrue(service.checkIdentity(record).isFailure)
    }

    @Test
    fun ownerOnlyInLevelDatIsMaterializedWhenSelectingFriend() {
        val source = save()
        Files.createDirectories(source.resolve("playerdata"))
        Files.write(source.resolve("playerdata/${friend}.dat"), gzip(nbt { player(friend, 20.0, 20.0) }))
        val record = service.createCopy(source, "host", root.resolve("saves"), rdi, friend).getOrThrow()
        assertEquals(owner, NbtMetadataReader.openGzipFile(record.copy.resolve("playerdata/${owner}.dat"))
            .getOrThrow().root.child("UUID")!!.uuidOrNull())
        assertFalse(Files.exists(source.resolve("playerdata/${owner}.dat")))
    }

    @Test
    fun aSaveWithoutSingleplayerOwnerUsesItsExistingPlayer() {
        val source = save(host = null)
        Files.createDirectories(source.resolve("playerdata"))
        Files.write(source.resolve("playerdata/${friend}.dat"), gzip(nbt { player(friend, 20.0, 20.0) }))
        val record = service.createCopy(source, "host", root.resolve("saves"), rdi, friend).getOrThrow()
        assertEquals(friend.toString(), record.launchUuid)
        assertNull(record.originalOwnerUuid)
        service.checkIdentity(record).getOrThrow()
    }

    @Test
    fun interruptedCopyIsNeverPublishedOrReused() {
        val source = save()
        val saves = root.resolve("pack/saves")
        val result = service.createCopy(source, "host", saves, rdi, ensureActive = { error("interrupted") })
        assertTrue(result.isFailure)
        assertNull(service.findReusable(source, "host"))
        Files.list(saves).use { assertEquals(0L, it.count()) }
        assertEquals(owner, NbtMetadataReader.readLevelDat(source.resolve("level.dat")).getOrThrow().singleplayerUuid)
    }

    @Test
    fun invalidPlayerAndFailedIdentityPreparationNeverPublishACopy() {
        val source = save()
        val saves = root.resolve("pack/saves")
        assertTrue(service.createCopy(source, "host", saves, rdi, friend).isFailure)
        Files.write(source.resolve("level.dat_old"), byteArrayOf(1, 2, 3))
        assertTrue(service.createCopy(source, "host", saves, rdi, owner).isFailure)
        assertNull(service.findReusable(source, "host"))
        Files.list(saves).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun oldRecordsAreCheckedAndRunningCopiesCannotBeReused() {
        val record = service.createCopy(save(), "host", root.resolve("saves"), rdi).getOrThrow()
        val legacy = record.copy(identityVersion = 0, originalOwnerUuid = null, selectedPlayerUuid = null)
        root.resolve("records.json").toFile().writeText(kotlinx.serialization.json.Json.encodeToString(listOf(legacy)))
        assertEquals(legacy, service.findReusable(Path.of(record.sourcePath), "host"))
        service.checkIdentity(legacy).getOrThrow()
        SaveSourceLock.acquire(record.copy).getOrThrow().use {
            assertTrue(service.checkIdentity(record).isFailure)
        }
        Files.write(record.copy.resolve("level.dat"), levelDat(friend, 0.0, 0.0))
        assertTrue(service.checkIdentity(legacy).isFailure)
    }

    /** A region file whose chunks have the given type bytes; only headers matter here. */
    private fun region(types: List<Int>): ByteArray {
        val bytes = java.nio.ByteBuffer.allocate(8192 + types.size * 4096)
        types.forEachIndexed { index, type ->
            val sector = 2 + index
            bytes.putInt(index * 4, (sector shl 8) or 1)
            bytes.putInt(sector * 4096, 2)
            bytes.put(sector * 4096 + 4, type.toByte())
        }
        return bytes.array()
    }

    // ---- minimal NBT writer ----

    private class Nbt(val out: DataOutputStream) {
        fun named(type: Int, name: String) {
            out.writeByte(type)
            out.writeUTF(name)
        }

        fun string(name: String, value: String) = named(8, name).also { out.writeUTF(value) }

        fun int(name: String, value: Int) = named(3, name).also { out.writeInt(value) }

        fun compound(name: String, body: Nbt.() -> Unit) {
            named(10, name)
            body()
            out.writeByte(0)
        }

        fun player(uuid: UUID, x: Double, z: Double) {
            named(11, "UUID")
            out.writeInt(4)
            out.writeLong(uuid.mostSignificantBits)
            out.writeLong(uuid.leastSignificantBits)
            named(9, "Pos")
            out.writeByte(6)
            out.writeInt(3)
            out.writeDouble(x)
            out.writeDouble(64.0)
            out.writeDouble(z)
            string("Dimension", "minecraft:overworld")
        }
    }

    private fun nbt(body: Nbt.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            Nbt(out).apply {
                named(10, "")
                body()
            }
            out.writeByte(0)
        }
        return bytes.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun levelDat(host: UUID?, x: Double, z: Double): ByteArray = gzip(nbt {
        compound("Data") {
            compound("Version") { string("Name", "1.20.1") }
            if (host != null) compound("Player") { player(host, x, z) }
        }
    })

    private fun syncList(chunks: List<Pair<String, Pair<Int, Int>>>): ByteArray = gzip(nbt {
        compound("data") {
            named(9, "chunks")
            out.writeByte(10)
            out.writeInt(chunks.size)
            chunks.forEach { (dimension, chunk) ->
                string("dimension", dimension)
                int("x", chunk.first)
                int("z", chunk.second)
                string("owner", owner.toString())
                out.writeByte(0)
            }
        }
    })
}
