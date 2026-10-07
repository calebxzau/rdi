package calebxzau.rdi.anvilrw.remap

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveUuidRemapperTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val pclTarget = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val onlineTarget = UUID.fromString("66778899-aabb-ccdd-eeff-001100000000")
    private val mapping = mapOf(pcl to pclTarget, online to onlineTarget)

    private fun chunkNbt(x: Int, z: Int, owner: UUID?): ByteArray {
        val entries = mutableListOf<Pair<String, Tag>>("xPos" to TInt(x), "zPos" to TInt(z), "sign" to TString("木牌😀"))
        owner?.let { entries += "Owner" to uuidInts(it) }
        return JavaNbt.write(TCompound(entries))
    }

    private fun region(save: SyntheticSave, folder: String, rx: Int, rz: Int, vararg chunks: Pair<Int, Int>, owner: UUID? = pcl): SyntheticSave {
        val dir = save.root.resolve(folder)
        Files.createDirectories(dir)
        val builder = TestRegion(dir, rx, rz)
        chunks.forEach { (x, z) -> builder.chunk((x and 31) + (z and 31) * 32, ChunkCompression.ZLIB, chunkNbt(x, z, owner)) }
        builder.write()
        return save
    }

    /** Reads the chunks of an output region file as `(x, z) -> NBT`. */
    private fun readChunks(file: Path): Map<Pair<Int, Int>, TCompound> {
        val bytes = Files.readAllBytes(file)
        val chunks = HashMap<Pair<Int, Int>, TCompound>()
        for (i in 0 until 1024) {
            val location = ByteBuffer.wrap(bytes, i * 4, 4).int
            if (location == 0) continue
            val start = (location ushr 8) * 4096
            val length = ByteBuffer.wrap(bytes, start, 4).int
            val type = bytes[start + 4].toInt() and 0x7f
            val nbt = JavaNbt.read(BoundedIo.decompressChunk(type, bytes.copyOfRange(start + 5, start + 4 + length), Int.MAX_VALUE, "test"))
            val values = nbt.entries.toMap()
            chunks[(values["xPos"] as TInt).value to (values["zPos"] as TInt).value] = nbt
        }
        return chunks
    }

    private fun readGzipNbt(file: Path): Map<String, Tag> =
        JavaNbt.read(BoundedIo.gunzip(Files.readAllBytes(file), Int.MAX_VALUE, "test")).entries.toMap()

    private fun remap(save: SyntheticSave, mapping: Map<UUID, UUID>, syncChunks: List<SaveSyncChunk>? = null): Pair<Path, Result<RemapReport>> {
        val target = createTempDirectory("remap-out")
        val snapshot = SaveSnapshot.take(save.root).getOrThrow()
        return target to SaveUuidRemapper(mapping, syncChunks, parallelism = 2).remapTree(save.root, target, snapshot)
    }

    @Test
    fun remapsAWholeSave() {
        val save = SyntheticSave()
            .levelDat(host = SyntheticSave.player(pcl, "Inventory" to TList(NbtTag.COMPOUND, listOf(compound("id" to TString("from level.dat"))))), hardcore = true)
            .gzipNbt("level.dat_old", compound("Data" to compound("hardcore" to TByte(1))))
            .gzipNbt("playerdata/${pcl}.dat", SyntheticSave.player(pcl, "Inventory" to TList(NbtTag.COMPOUND, listOf(compound("id" to TString("stale"))))))
            .gzipNbt("playerdata/${online}.dat", SyntheticSave.player(online))
            .gzipNbt("playerdata/${online}.dat_old", SyntheticSave.player(online))
            .text("stats/${online}.json", """{"stats":{},"owner":"${online}"}""")
            .text("advancements/${pcl}.json", "{}")
            .gzipNbt("data/claims.dat", compound("data" to compound("owner" to TString(pcl.toString()))))
            .bytes("data/raw.dat", JavaNbt.write(compound("owner" to uuidInts(online))))
            .bytes("config/gbk.toml", "玩家=\"${pcl}\"".toByteArray(charset("GBK")))
            .bytes("mod/blob.bin", byteArrayOf(1, 2, 3) + uuidBytes(online) + byteArrayOf(4))
            .bytes("mod/clean.bin", byteArrayOf(1, 2, 3))
            .text("mod/pairs.snbt", "{OwnerMost: ${pcl.mostSignificantBits}L, OwnerLeast: ${pcl.leastSignificantBits}L}")
            .bytes("session.lock", byteArrayOf())
        Files.createDirectories(save.root.resolve("empty/dir"))
        region(save, "region", 0, 0, 0 to 0, 1 to 0)
        region(save, "DIM-1/region", -1, 0, -1 to 0, owner = online)
        val sourceBefore = SaveSnapshot.take(save.root).getOrThrow()

        val (out, result) = remap(save, mapping)
        val report = result.getOrThrow()

        assertEquals(uuidInts(pclTarget), readGzipNbt(out.resolve("playerdata/${pclTarget}.dat"))["UUID"])
        assertEquals(
            TList(NbtTag.COMPOUND, listOf(compound("id" to TString("from level.dat")))),
            readGzipNbt(out.resolve("playerdata/${pclTarget}.dat"))["Inventory"],
            "the owner's playerdata comes from Data.Player",
        )
        assertEquals(uuidInts(onlineTarget), readGzipNbt(out.resolve("playerdata/${onlineTarget}.dat"))["UUID"])
        assertTrue(Files.exists(out.resolve("playerdata/${onlineTarget}.dat_old")))
        assertEquals("""{"stats":{},"owner":"${onlineTarget}"}""", Files.readString(out.resolve("stats/${onlineTarget}.json")))
        assertTrue(Files.exists(out.resolve("advancements/${pclTarget}.json")))
        val level = readGzipNbt(out.resolve("level.dat"))["Data"] as TCompound
        assertEquals(TByte(0), level.entries.toMap()["hardcore"])
        assertEquals(uuidInts(pclTarget), (level.entries.toMap()["Player"] as TCompound).entries.toMap()["UUID"])
        assertEquals(TByte(0), (readGzipNbt(out.resolve("level.dat_old"))["Data"] as TCompound).entries.toMap()["hardcore"])
        assertEquals(TString(pclTarget.toString()), (readGzipNbt(out.resolve("data/claims.dat"))["data"] as TCompound).entries.toMap()["owner"])
        assertEquals(uuidInts(onlineTarget), JavaNbt.read(Files.readAllBytes(out.resolve("data/raw.dat"))).entries.toMap()["owner"])
        assertEquals(uuidInts(pclTarget), readChunks(out.resolve("region/r.0.0.mca")).getValue(0 to 0).entries.toMap()["Owner"])
        assertEquals(uuidInts(onlineTarget), readChunks(out.resolve("DIM-1/region/r.-1.0.mca")).getValue(-1 to 0).entries.toMap()["Owner"])
        assertContentEquals(Files.readAllBytes(save.root.resolve("config/gbk.toml")), Files.readAllBytes(out.resolve("config/gbk.toml")))
        assertFalse(Files.exists(out.resolve("session.lock")))
        assertTrue(Files.isDirectory(out.resolve("empty/dir")))

        assertTrue(report.hardcoreSwitchedOff)
        assertTrue(report.hostDataFromLevelDat)
        assertNull(report.syncFilter)
        assertEquals(5, report.pathsRenamed)
        assertEquals(
            listOf(
                UnhandledFile("config/gbk.toml", "非UTF-8文本，未自动迁移"),
                UnhandledFile("mod/blob.bin", "未识别的文件格式"),
                UnhandledFile("mod/pairs.snbt", "含有无法自动迁移的玩家ID写法"),
            ),
            report.possiblyUnmigrated,
        )
        assertTrue(report.replacementsBySource.getValue(pcl) > 0 && report.replacementsBySource.getValue(online) > 0)

        val sourceAfter = SaveSnapshot.take(save.root).getOrThrow()
        assertEquals(sourceBefore.files, sourceAfter.files, "the source is never modified")
    }

    @Test
    fun keepsOnlyMarkedChunks() {
        val save = SyntheticSave().levelDat()
        region(save, "region", 0, 0, 0 to 0, 1 to 0, 5 to 5)
        region(save, "entities", 0, 0, 0 to 0, 1 to 0)
        region(save, "poi", 0, 0, 1 to 0)
        region(save, "region", 1, 0, 32 to 0)
        region(save, "DIM-1/region", 0, 0, 0 to 0)
        region(save, "dimensions/twilightforest/twilight_forest/region", 0, 0, 3 to 4)
        region(save, "dimensions/removedmod/void/region", 0, 0, 0 to 0)
        region(save, "data/somemod/region", 0, 0, 7 to 7)
        save.bytes("region/c.9.9.mcc", byteArrayOf(1))
        val marks = listOf(
            SaveSyncChunk("minecraft:overworld", 0, 0, pclTarget),
            SaveSyncChunk("minecraft:overworld", 2, 2, pclTarget),
            SaveSyncChunk("twilightforest:twilight_forest", 3, 4, pclTarget),
        )

        val (out, result) = remap(save, mapping, marks)
        val report = result.getOrThrow()

        assertEquals(setOf(0 to 0), readChunks(out.resolve("region/r.0.0.mca")).keys)
        assertEquals(setOf(0 to 0), readChunks(out.resolve("entities/r.0.0.mca")).keys)
        assertFalse(Files.exists(out.resolve("poi/r.0.0.mca")))
        assertFalse(Files.exists(out.resolve("region/r.1.0.mca")))
        assertFalse(Files.exists(out.resolve("region/c.9.9.mcc")))
        assertFalse(Files.exists(out.resolve("DIM-1/region/r.0.0.mca")))
        assertFalse(Files.exists(out.resolve("dimensions/removedmod/void/region/r.0.0.mca")))
        assertEquals(setOf(3 to 4), readChunks(out.resolve("dimensions/twilightforest/twilight_forest/region/r.0.0.mca")).keys)
        assertEquals(setOf(7 to 7), readChunks(out.resolve("data/somemod/region/r.0.0.mca")).keys, "not a chunk store")
        assertEquals(SyncFilterReport(markedChunkCount = 3, presentChunkCount = 2, droppedFileCount = 4), report.syncFilter)
        assertTrue(report.bytesAfter < report.bytesBefore)
    }

    @Test
    fun swapKeepsTwoSeparatePlayers() {
        val m = online
        val r = pclTarget
        val save = SyntheticSave()
            .levelDat(host = SyntheticSave.player(m, "name" to TString("M from level.dat")))
            .gzipNbt("playerdata/${m}.dat", SyntheticSave.player(m, "name" to TString("M stale")))
            .gzipNbt("playerdata/${r}.dat", SyntheticSave.player(r, "name" to TString("R")))
            .text("ftbteams/player/${m}.snbt", "{ id: \"${m}\" }")
            .text("ftbteams/player/${r}.snbt", "{ id: \"${r}\" }")
        region(save, "region", 0, 0, 0 to 0, owner = m)
        region(save, "region", 1, 0, 32 to 0, owner = r)

        val (out, result) = remap(save, mapOf(m to r, r to m))
        result.getOrThrow()

        readGzipNbt(out.resolve("playerdata/${r}.dat")).let {
            assertEquals(uuidInts(r), it["UUID"])
            assertEquals(TString("M from level.dat"), it["name"])
        }
        readGzipNbt(out.resolve("playerdata/${m}.dat")).let {
            assertEquals(uuidInts(m), it["UUID"])
            assertEquals(TString("R"), it["name"])
        }
        assertEquals("{ id: \"${r}\" }", Files.readString(out.resolve("ftbteams/player/${r}.snbt")))
        assertEquals("{ id: \"${m}\" }", Files.readString(out.resolve("ftbteams/player/${m}.snbt")))
        assertEquals(uuidInts(r), readChunks(out.resolve("region/r.0.0.mca")).getValue(0 to 0).entries.toMap()["Owner"])
        assertEquals(uuidInts(m), readChunks(out.resolve("region/r.1.0.mca")).getValue(32 to 0).entries.toMap()["Owner"])
    }

    @Test
    fun failuresLeaveNothingBehind() {
        val conflict = SyntheticSave().levelDat()
            .gzipNbt("playerdata/${pcl}.dat", SyntheticSave.player(pcl))
            .gzipNbt("playerdata/${pclTarget}.dat", SyntheticSave.player(pclTarget))
        val corrupt = SyntheticSave().levelDat().bytes("playerdata/${online}.dat", byteArrayOf(1, 2, 3))
        val badChunk = SyntheticSave().levelDat()
        Files.createDirectories(badChunk.root.resolve("region"))
        TestRegion(badChunk.root.resolve("region"), 0, 0).chunk(0, ChunkCompression.ZLIB, ByteArray(0), rawPayload = byteArrayOf(9, 9, 9)).write()

        mapOf(
            "path conflict" to (conflict to PathConflictException::class),
            "corrupt playerdata" to (corrupt to java.io.IOException::class),
            "corrupt chunk" to (badChunk to RegionFormatException::class),
        ).forEach { (name, case) ->
            val (out, result) = remap(case.first, mapping)
            val failure = result.exceptionOrNull()
            assertTrue(case.second.isInstance(failure), "${name}: ${failure}")
            assertEquals(emptyList(), out.listDirectoryEntries(), name)
        }
    }

    @Test
    fun changedSourceFails() {
        val save = SyntheticSave().levelDat().text("config/a.txt", "a")
        val snapshot = SaveSnapshot.take(save.root).getOrThrow()
        Files.writeString(save.root.resolve("config/a.txt"), "changed")
        val out = createTempDirectory("remap-changed")

        val failure = SaveUuidRemapper(mapping).remapTree(save.root, out, snapshot).exceptionOrNull()

        assertIs<SaveChangedException>(failure)
        assertEquals(emptyList(), out.listDirectoryEntries())
    }

    @Test
    fun chunkStoreDimensions() {
        assertEquals("minecraft:overworld", SaveUuidRemapper.chunkStoreDimension("region/r.0.0.mca"))
        assertEquals("minecraft:overworld", SaveUuidRemapper.chunkStoreDimension("poi/r.0.0.mca"))
        assertEquals("minecraft:the_nether", SaveUuidRemapper.chunkStoreDimension("DIM-1/entities/r.0.0.mca"))
        assertEquals("minecraft:the_end", SaveUuidRemapper.chunkStoreDimension("DIM1/region/c.1.2.mcc"))
        assertEquals("a:b/c", SaveUuidRemapper.chunkStoreDimension("dimensions/a/b/c/region/r.0.0.mca"))
        assertNull(SaveUuidRemapper.chunkStoreDimension("data/x/region/r.0.0.mca"))
        assertNull(SaveUuidRemapper.chunkStoreDimension("dimensions/a/region/r.0.0.mca"))
        assertNull(SaveUuidRemapper.chunkStoreDimension("r.0.0.mca"))
    }

    private fun uuidBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}
