package calebxzau.rdi.anvilrw.remap

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test

/**
 * Writes reference results of this module for the Rust port in `common/anvilrw-rs`, which compares
 * its own results against them. Runs only when `RDI_ANVILRW_RS_FIXTURES` names an empty or missing
 * folder (normally `common/anvilrw-rs/tests/fixtures/kotlin`); otherwise it does nothing.
 *
 * Every input is written next to what Kotlin produced from it. Errors are recorded by exception
 * class name. Modification times are left out, because git does not keep them.
 */
class RustParityFixtureTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val pclTarget = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val onlineTarget = UUID.fromString("66778899-aabb-ccdd-eeff-001100000000")
    private val mappings = linkedMapOf(
        "pair" to mapOf(pcl to pclTarget, online to onlineTarget),
        "single" to mapOf(pcl to pclTarget),
        "swap" to mapOf(pcl to online, online to pcl),
        "empty" to emptyMap(),
    )

    @Test
    fun writeFixtures() {
        val out = System.getenv("RDI_ANVILRW_RS_FIXTURES")?.let { Path.of(it) } ?: return
        check(Files.notExists(out) || Files.list(out).use { it.findFirst().isEmpty }) { "${out} must be empty" }
        Files.createDirectories(out)
        writeJson(out.resolve("mappings.json"), mappings.mapValues { (_, mapping) -> uuidMap(mapping) })
        nbtCases(out.resolve("nbt"))
        textCases(out.resolve("text"))
        pathCases(out)
        dimensionCases(out)
        levelCases(out.resolve("level"))
        replaceCases(out.resolve("replace"))
        syncCases(out.resolve("sync"))
        regionCases(out.resolve("regions"))
        saveCases(out.resolve("saves"))
        println("Rust parity fixtures written to ${out}")
    }

    // ---- NBT byte patcher ----

    private fun nbtCases(dir: Path) {
        Files.createDirectories(dir)
        val cases = ArrayList<Map<String, Any?>>()
        fun case(name: String, mapping: String, bytes: ByteArray) {
            Files.write(dir.resolve("${name}.in"), bytes)
            val result = NbtBytePatcher(mappings.getValue(mapping)).patch(bytes)
            result.onSuccess { Files.write(dir.resolve("${name}.out"), it.bytes) }
            cases += linkedMapOf(
                "name" to name,
                "mapping" to mapping,
                "error" to result.exceptionOrNull()?.let { it::class.simpleName },
                "changed" to result.getOrNull()?.changed,
                "replacements" to result.getOrNull()?.let { uuidCounts(it.replacements) },
            )
        }

        val upper = pcl.toString().uppercase()
        case("every-form", "pair", JavaNbt.write(compound(
            "ints" to uuidInts(pcl),
            "longs" to TLongArray(listOf(online.mostSignificantBits, online.leastSignificantBits)),
            "OwnerUUIDMost" to TLong(pcl.mostSignificantBits),
            "OwnerUUIDLeast" to TLong(pcl.leastSignificantBits),
            "Most" to TLong(online.mostSignificantBits),
            "Least" to TLong(online.leastSignificantBits),
            "dashed" to TString(pcl.toString()),
            "upper" to TString(upper),
            "mixed" to TString("00000000-0000-300C-9be5-0017dec2993d"),
            "dashless" to TString(dashless(online)),
            "dashlessUpper" to TString(dashless(online).uppercase()),
            "json" to TString("""{"text":"${online}","extra":"${pcl}"}"""),
            "list" to TList(NbtTag.STRING, listOf(TString(pcl.toString()), TString("x"))),
            "listOfCompounds" to TList(NbtTag.COMPOUND, listOf(compound("Owner" to uuidInts(online)))),
            "listOfLists" to TList(NbtTag.LIST, listOf(TList(NbtTag.INT_ARRAY, listOf(uuidInts(pcl))))),
            pcl.toString() to TInt(7),
        )))
        case("modified-utf8", "pair", JavaNbt.write(compound(
            "name" to TString("中文😀\u0000end"),
            "Owner" to uuidInts(pcl),
            "sign" to TString("emoji 🎉 ${pcl} 尾"),
            "nul" to TString("\u0000"),
        )))
        case("most-least-prefix", "pair", JavaNbt.write(compound(
            "AMost" to TLong(pcl.mostSignificantBits),
            "BLeast" to TLong(pcl.leastSignificantBits),
        )))
        case("key-conflict", "pair", JavaNbt.write(compound(pcl.toString() to TInt(1), pclTarget.toString() to TInt(2))))
        case("partial-values", "pair", JavaNbt.write(compound(
            "msbAsLong" to TLong(12300L),
            "sharedFirstInts" to TIntArray(listOf(0, 12300, 1, 2)),
            "halfLongs" to TLongArray(listOf(pcl.mostSignificantBits, 5L)),
            "onlyMost" to TLong(pcl.mostSignificantBits),
            "prefix" to TString("00000000-0000-300c"),
            "threeInts" to TIntArray(uuidInts(pcl).value.take(3)),
            "fiveInts" to TIntArray(uuidInts(pcl).value + 0),
        )))
        listOf(
            "${pcl}a", "a${pcl}", "-${pcl}", "${pcl}-x", "${dashless(pcl)}0", "f${dashless(pcl)}",
            "-${dashless(pcl)}", "x${dashless(pcl)}y", "g${pcl}g", "${pcl}${pcl}", "${pcl} ${pcl}",
            "${dashless(pcl)}${dashless(online)}", "${pcl}-${online}", "${dashless(pcl)}-x",
        ).forEachIndexed { index, text -> case("boundary-${index}", "pair", JavaNbt.write(compound("s" to TString(text)))) }
        case("swap", "swap", JavaNbt.write(compound(
            "Owner" to uuidInts(pcl),
            "Friend" to uuidInts(online),
            "text" to TString("${pcl} ${online}"),
            pcl.toString() to TString("pcl slot"),
            online.toString() to TString("online slot"),
            "OwnerMost" to TLong(online.mostSignificantBits),
            "OwnerLeast" to TLong(online.leastSignificantBits),
        )))
        case("empty-mapping", "empty", JavaNbt.write(compound("Owner" to uuidInts(pcl))))

        val valid = JavaNbt.write(compound("a" to TInt(1)))
        listOf(
            "unknown-tag" to byteArrayOf(10, 0, 0, 13, 0, 1, 'a'.code.toByte(), 0),
            "root-not-compound" to byteArrayOf(8, 0, 0, 0, 0),
            "negative-array" to doc(NbtTag.INT_ARRAY, ByteBuffer.allocate(4).putInt(-1).array()),
            "negative-list" to doc(NbtTag.LIST, byteArrayOf(NbtTag.INT.toByte(), -1, -1, -1, -1)),
            "truncated" to valid.copyOf(valid.size - 1),
            "trailing" to valid + 0,
            "end-list-with-elements" to doc(NbtTag.LIST, byteArrayOf(0, 0, 0, 0, 1)),
            "unknown-list-type" to doc(NbtTag.LIST, byteArrayOf(13, 0, 0, 0, 1, 0)),
            "huge-long-array" to doc(NbtTag.LONG_ARRAY, ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()),
            "huge-int-array" to doc(NbtTag.INT_ARRAY, ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()),
            "huge-byte-array" to doc(NbtTag.BYTE_ARRAY, ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()),
            "huge-list" to doc(NbtTag.LIST, byteArrayOf(NbtTag.COMPOUND.toByte(), 0x7f, -1, -1, -1)),
            "huge-int-list" to doc(NbtTag.LIST, byteArrayOf(NbtTag.INT.toByte(), 0, 0, 0, 3, 0, 0, 0, 1)),
            "huge-string" to doc(NbtTag.STRING, byteArrayOf(-1, -1, 'a'.code.toByte())),
            "empty" to ByteArray(0),
            "only-type" to byteArrayOf(10),
            "end-list-empty" to doc(NbtTag.LIST, byteArrayOf(0, 0, 0, 0, 0)),
            "named-root" to JavaNbt.write(compound("Owner" to uuidInts(pcl)), rootName = "根${pcl}"),
        ).forEach { (name, bytes) -> case("structure-${name}", "pair", bytes) }
        case("depth-512", "pair", nested(512))
        case("depth-513", "pair", nested(513))
        case("depth-list-512", "pair", nestedLists(511))
        case("depth-list-513", "pair", nestedLists(512))

        val random = Random(20261124)
        repeat(300) { index -> case("random-${index}", if (index % 5 == 4) "swap" else "pair", JavaNbt.write(randomCompound(random, 0, hits = true))) }
        writeJson(dir.resolve("cases.json"), cases)
    }

    // ---- Text patcher ----

    private fun textCases(dir: Path) {
        Files.createDirectories(dir)
        val cases = ArrayList<Map<String, Any?>>()
        fun case(name: String, mapping: String, bytes: ByteArray) {
            Files.write(dir.resolve("${name}.in"), bytes)
            val result = TextUuidPatcher(mappings.getValue(mapping)).patch(bytes)
            result.onSuccess { Files.write(dir.resolve("${name}.out"), it.bytes) }
            cases += linkedMapOf(
                "name" to name,
                "mapping" to mapping,
                "error" to result.exceptionOrNull()?.let { it::class.simpleName },
                "changed" to result.getOrNull()?.changed,
                "replacements" to result.getOrNull()?.let { uuidCounts(it.replacements) },
                "mostLeastHits" to result.getOrNull()?.mostLeastHits,
                "strictUtf8" to TextUuidPatcher.isStrictUtf8(bytes),
            )
        }
        val (a, b, c, d) = ints(pcl)
        val upper = pcl.toString().uppercase()
        case("styles", "single", "﻿中文 ${pcl}\r\n\"${dashless(pcl)}\": 1\nkey=${upper} 😀\n".toByteArray())
        case("partial", "single", "a${pcl} ${pcl}0 ${online} ${dashless(pcl).substring(1)} -${pcl}".toByteArray())
        case("int-arrays", "single", "{Owner:[I; ${a}, ${b},${c} ,  ${d}], json:[${a},${b},${c},${d}], other:[I;${a},${b},${c},0], long:[I;1,2,3,99999999999]}".toByteArray())
        case("int-arrays-whitespace", "single", "[I;\t${a},\n${b}\r,${c}\u000B,${d}\u000C] [I; ${a},${b},${c},${d}] [${a},${b},${c},${d}]".toByteArray())
        case("int-arrays-mixed-with-ascii", "pair", "[I;${a},${b},${c},${d}] ${online} [${ints(online).joinToString(",")}]".toByteArray())
        case("most-least", "single", "{OwnerMost: ${pcl.mostSignificantBits}L, OwnerLeast: ${pcl.leastSignificantBits}L, \"xMost\": ${pcl.mostSignificantBits}, \"xLeast\": ${pcl.leastSignificantBits}, yMost: 1L, yLeast: 2L, zMost: 99999999999999999999, zLeast: 1}".toByteArray())
        case("swap", "swap", "${pcl} ${online} ${ints(online).joinToString(",", "[I;", "]")}".toByteArray())
        case("gbk", "single", "玩家 ${pcl}".toByteArray(charset("GBK")))
        case("nul", "single", "a\u0000b".toByteArray())
        case("empty", "single", ByteArray(0))
        case("overlong", "single", byteArrayOf(0xC0.toByte(), 0xAF.toByte()))
        case("surrogate", "single", byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
        case("plain", "single", "plain ascii".toByteArray())
        val random = Random(1124)
        repeat(60) { index ->
            val parts = List(random.nextInt(1, 12)) {
                when (random.nextInt(0, 9)) {
                    0 -> pcl.toString()
                    1 -> dashless(online).uppercase()
                    2 -> ints(pcl).joinToString(",", "[I;", "]")
                    3 -> "OwnerMost: ${pcl.mostSignificantBits}L, OwnerLeast: ${pcl.leastSignificantBits}L"
                    4 -> "中文😀"
                    5 -> UUID(random.nextLong(), random.nextLong()).toString()
                    6 -> "\r\n"
                    7 -> random.nextLong().toString(16)
                    else -> " "
                }
            }
            case("random-${index}", if (index % 3 == 2) "swap" else "pair", parts.joinToString("").toByteArray())
        }
        writeJson(dir.resolve("cases.json"), cases)
    }

    // ---- Paths, dimensions ----

    private fun pathCases(out: Path) {
        val inputs = listOf(
            "playerdata/${pcl}.dat", "playerdata/${pcl}.dat_old", "tombstone/saved_players/${pcl}/x.json",
            "mod/${dashless(pcl)}.snbt", "mod/${pcl.toString().uppercase()}.备份.json", "playerdata/${online}.dat",
            "mod/x${pcl}.dat", "mod/${pcl}x.dat", "mod/zzzz${dashless(pcl)}.dat", "mod/.${pcl}", "region/r.0.0.mca",
            "${pcl}", "${pcl}/${online}/${dashless(pcl)}", "a//b", "",
        )
        val cases = mappings.keys.flatMap { mapping ->
            val renamer = SavePathRenamer(mappings.getValue(mapping))
            inputs.map { linkedMapOf("mapping" to mapping, "input" to it, "output" to renamer.rename(it)) }
        }
        writeJson(out.resolve("paths.json"), cases)
    }

    private fun dimensionCases(out: Path) {
        val root = Path.of("fixture-root").toAbsolutePath()
        val ids = listOf(
            "minecraft:overworld", "overworld", "minecraft:the_nether", "the_nether", "minecraft:the_end", ":the_end",
            "twilightforest:twilight_forest", "my.mod-1:a/b_2.x", "custom", "a:b/c/d", "minecraft:custom",
            "", "demo:", "demo:../../../outside", "demo:..", "demo:.", "demo:a/../../b", "demo:/tmp", "demo:a//b",
            "demo:a/", "..:x", ".:x", "demo:a.", "demo:con", "demo:nul.txt", "lpt1:x", "Demo:x", "demo:X",
            "demo:a\\b", "demo:a:b", "demo:a b", "demo:${"a".repeat(600)}", "demo:${"a".repeat(507)}", "demo:com9.x",
            "demo:com0", "demo:a.b.", "demo:é", ":", "minecraft:",
        )
        val cases = ids.map { id ->
            val result = dimensionStorageFolder(root, id)
            linkedMapOf(
                "id" to id,
                "relative" to result.getOrNull()?.let { path -> root.relativize(path).joinToString("/") { it.toString() } },
                "error" to result.exceptionOrNull()?.let { it::class.simpleName },
            )
        }
        writeJson(out.resolve("dimensions.json"), cases)
    }

    // ---- level.dat metadata and compound replacement ----

    private fun levelCases(dir: Path) {
        Files.createDirectories(dir)
        val cases = ArrayList<Map<String, Any?>>()
        fun case(name: String, root: TCompound) {
            val bytes = JavaNbt.write(root)
            Files.write(dir.resolve("${name}.nbt"), bytes)
            val metadata = NbtMetadataReader.open(bytes).getOrThrow().levelMetadata()
            cases += linkedMapOf(
                "name" to name,
                "error" to metadata.exceptionOrNull()?.let { it::class.simpleName },
                "metadata" to metadata.getOrNull()?.let(::levelJson),
            )
        }
        case("full", compound(
            "Data" to compound(
                "Version" to compound("Name" to TString("1.20.1")),
                "DataVersion" to TInt(3465),
                "ServerBrands" to TList(NbtTag.STRING, listOf(TString("forge"), TString("品牌😀"))),
                "hardcore" to TByte(1),
                "Player" to compound("UUID" to uuidInts(pcl)),
            ),
            "fml" to compound("LoadingModList" to TList(NbtTag.COMPOUND, listOf(compound("ModId" to TString("minecraft")), compound("ModId" to TString("forge"))))),
        ))
        case("lenient", compound("Data" to compound("hardcore" to TInt(1), "ServerBrands" to TString("forge"))))
        case("fml-without-list", compound("fml" to compound()))
        case("odd-types", compound("Data" to compound(
            "Version" to TString("1.20.1"),
            "DataVersion" to TLong(1),
            "ServerBrands" to TList(NbtTag.INT, listOf(TInt(1))),
            "hardcore" to TByte(-1),
            "Player" to compound("UUID" to TIntArray(listOf(1, 2, 3))),
        ), "fml" to compound("LoadingModList" to TList(NbtTag.COMPOUND, listOf(compound("ModId" to TInt(1)), compound("ModId" to TString("neoforge")))))))
        case("bad-modified-utf8", compound("Data" to compound("Version" to compound("Name" to TString("x")))).let { it })
        // Replace the "x" above with an invalid modified UTF-8 byte after writing.
        val bad = Files.readAllBytes(dir.resolve("bad-modified-utf8.nbt"))
        val xAt = bad.lastIndexOf('x'.code.toByte())
        bad[xAt] = 0xC0.toByte()
        Files.write(dir.resolve("bad-modified-utf8.nbt"), bad)
        cases.removeLast()
        cases += linkedMapOf(
            "name" to "bad-modified-utf8",
            "error" to NbtMetadataReader.open(bad).getOrThrow().levelMetadata().exceptionOrNull()?.let { it::class.simpleName },
            "metadata" to null,
        )
        val brands = listOf(
            listOf("vanilla", "fabric") to listOf("minecraft", "forge"), emptyList<String>() to listOf("minecraft", "neoforge"),
            listOf("fabric") to null, listOf("vanilla", "example,fabric") to null, listOf("fabricated", "fabric-api") to null,
            emptyList<String>() to listOf("forge", "neoforge"), listOf("forge", "fabric") to null, listOf("neoforge", "fabric") to null,
            listOf("quilt,fabric") to null, listOf("fabric") to listOf("minecraft"), listOf("vanilla") to null,
            emptyList<String>() to null, listOf("forge") to null, listOf("vanilla") to listOf("minecraft"), listOf(" fabric , vanilla") to null,
        )
        brands.forEachIndexed { index, (brandList, modIds) ->
            val root = mutableListOf<Pair<String, Tag>>("Data" to compound("ServerBrands" to TList(NbtTag.STRING, brandList.map(::TString))))
            modIds?.let { ids -> root += "fml" to compound("LoadingModList" to TList(NbtTag.COMPOUND, ids.map { compound("ModId" to TString(it)) })) }
            case("loader-${index}", TCompound(root))
        }
        writeJson(dir.resolve("cases.json"), cases)
    }

    private fun replaceCases(dir: Path) {
        Files.createDirectories(dir)
        val cases = ArrayList<Map<String, Any?>>()
        fun case(name: String, root: ByteArray, parent: List<String>, entry: String, payload: ByteArray) {
            Files.write(dir.resolve("${name}.in"), root)
            Files.write(dir.resolve("${name}.payload"), payload)
            val result = NbtMetadataReader.open(root).mapCatching { it.replaceCompound(parent, entry, payload).getOrThrow() }
            result.onSuccess { Files.write(dir.resolve("${name}.out"), it) }
            cases += linkedMapOf("name" to name, "parent" to parent, "entry" to entry, "error" to result.exceptionOrNull()?.let { it::class.simpleName })
        }
        val old = compound("UUID" to uuidInts(pcl))
        val chosen = JavaNbt.payload(compound("UUID" to uuidInts(online), "ModName" to TString("中文😀\u0000")))
        val extra = compound("Unknown" to TByteArray(listOf(0, 1, -1)), "Text" to TString("🎉\u0000"))
        case("existing", JavaNbt.write(compound("Data" to compound("Before" to extra, "Player" to old, "After" to extra), "ModRoot" to extra)), listOf("Data"), "Player", chosen)
        case("added", JavaNbt.write(compound("Data" to compound("Before" to extra, "After" to extra), "ModRoot" to extra)), listOf("Data"), "Player", chosen)
        case("duplicate", JavaNbt.write(compound("Data" to compound("Player" to old, "Player" to old))), listOf("Data"), "Player", chosen)
        case("missing-parent", JavaNbt.write(compound("Other" to compound())), listOf("Data"), "Player", chosen)
        case("parent-not-compound", JavaNbt.write(compound("Data" to TInt(1))), listOf("Data"), "Player", chosen)
        case("entry-not-compound", JavaNbt.write(compound("Data" to compound("Player" to TInt(1)))), listOf("Data"), "Player", chosen)
        case("bad-payload", JavaNbt.write(compound("Data" to compound())), listOf("Data"), "Player", byteArrayOf(1, 0))
        case("root-parent", JavaNbt.write(compound("A" to TInt(1))), emptyList(), "名字😀", chosen)
        writeJson(dir.resolve("cases.json"), cases)
    }

    // ---- data/rdi_sync_chunks.dat ----

    private fun syncCases(dir: Path) {
        Files.createDirectories(dir)
        val owner = pclTarget
        val chunk = SaveSyncChunk("minecraft:overworld", 0, 0, owner)
        val saves = linkedMapOf(
            "valid" to SyntheticSave().syncChunks(SaveSyncChunk("x", 1, 2, owner), rawDimension = "overworld"),
            "many" to SyntheticSave().syncChunks(*Array(256) { chunk.copy(chunkX = it, chunkZ = -it) }),
            "empty" to SyntheticSave().syncChunks(),
            "duplicate" to SyntheticSave().syncChunks(chunk, chunk.copy(dimensionId = "overworld")),
            "too-many" to SyntheticSave().syncChunks(*Array(257) { chunk.copy(chunkX = it) }),
            "unsafe-dimension" to SyntheticSave().syncChunks(chunk, rawDimension = "demo:../../x"),
            "bad-dimension" to SyntheticSave().syncChunks(chunk, rawDimension = "Demo:x"),
            "colon-dimension" to SyntheticSave().syncChunks(chunk, rawDimension = ":the_nether"),
            "no-list" to SyntheticSave().gzipNbt(SaveSyncChunks.RELATIVE_PATH, compound("data" to compound())),
            "bad-owner" to syncEntry(compound("dimension" to TString("minecraft:overworld"), "x" to TInt(0), "z" to TInt(0), "owner" to TString("1-2-3-4-5"))),
            "upper-owner" to syncEntry(compound("dimension" to TString("minecraft:overworld"), "x" to TInt(0), "z" to TInt(0), "owner" to TString(owner.toString().uppercase()))),
            "missing-z" to syncEntry(compound("dimension" to TString("minecraft:overworld"), "x" to TInt(0), "owner" to TString(owner.toString()))),
            "long-x" to syncEntry(compound("dimension" to TString("minecraft:overworld"), "x" to TLong(0), "z" to TInt(0), "owner" to TString(owner.toString()))),
            "not-compound" to SyntheticSave().gzipNbt(SaveSyncChunks.RELATIVE_PATH, compound("data" to compound("chunks" to TList(NbtTag.INT, listOf(TInt(1)))))),
        )
        val cases = ArrayList<Map<String, Any?>>()
        saves.forEach { (name, save) ->
            val bytes = Files.readAllBytes(save.root.resolve(SaveSyncChunks.RELATIVE_PATH))
            Files.write(dir.resolve("${name}.dat"), bytes)
            val result = SaveSyncChunks.parse(bytes)
            cases += linkedMapOf("name" to name, "error" to result.exceptionOrNull()?.let { it::class.simpleName }, "chunks" to result.getOrNull()?.map(::syncJson))
        }
        listOf("not-gzip" to byteArrayOf(1, 2, 3), "gzip-not-nbt" to BoundedIo.gzip(byteArrayOf(10, 0))).forEach { (name, bytes) ->
            Files.write(dir.resolve("${name}.dat"), bytes)
            val result = SaveSyncChunks.parse(bytes)
            cases += linkedMapOf("name" to name, "error" to result.exceptionOrNull()?.let { it::class.simpleName }, "chunks" to null)
        }
        writeJson(dir.resolve("cases.json"), cases)
    }

    private fun syncEntry(entry: TCompound) = SyntheticSave().gzipNbt(
        SaveSyncChunks.RELATIVE_PATH, compound("data" to compound("chunks" to TList(NbtTag.COMPOUND, listOf(entry)))),
    )

    // ---- Region files ----

    private fun chunkNbt(x: Int, z: Int, owner: UUID? = null, extra: Tag? = null): ByteArray {
        val entries = mutableListOf<Pair<String, Tag>>("xPos" to TInt(x), "zPos" to TInt(z), "label" to TString("区块😀"))
        owner?.let { entries += "Owner" to uuidInts(it) }
        extra?.let { entries += "extra" to it }
        return JavaNbt.write(TCompound(entries))
    }

    private class RegionCase(
        val build: (Path) -> Path,
        val keep: Set<Pair<Int, Int>>? = null,
        val threshold: Int = 256,
        val chunkLimit: Int = RemapLimits.CHUNK_DECOMPRESSED_BYTES,
        val mapping: String = "single",
    )

    private fun regionCases(dir: Path) {
        val bytes = { seed: Int, size: Int -> TByteArray(Random(seed).nextBytes(size).toList()) }
        val cases = linkedMapOf(
            "unchanged" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0)).chunk(1, ChunkCompression.GZIP, chunkNbt(1, 0))
                    .chunk(40, ChunkCompression.ZSTD, chunkNbt(8, 1), external = true).write()
            }),
            "changed" to RegionCase({ d ->
                TestRegion(d, -1, 2)
                    .chunk(0, ChunkCompression.ZLIB, chunkNbt(-32, 64, pcl), timestamp = 111)
                    .chunk(1, ChunkCompression.GZIP, chunkNbt(-31, 64), timestamp = 222)
                    .chunk(5, ChunkCompression.NONE, chunkNbt(-27, 64), timestamp = 333)
                    .chunk(7, ChunkCompression.ZSTD, chunkNbt(-25, 64, pcl), timestamp = 444)
                    .chunk(9, ChunkCompression.GZIP, chunkNbt(-23, 64, pcl), timestamp = 445)
                    .chunk(33, ChunkCompression.ZLIB, chunkNbt(-31, 65, pcl), timestamp = 555, external = true)
                    .chunk(1023, ChunkCompression.NONE, chunkNbt(-1, 95, pcl, bytes(4, 9000)), timestamp = 666)
                    .write()
            }),
            "external-stays-external" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(3, ChunkCompression.NONE, chunkNbt(3, 0, pcl, bytes(1, 50_000)), external = true).write()
            }),
            "threshold" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(3, ChunkCompression.ZLIB, chunkNbt(3, 0, pcl, bytes(2, 10_000)))
                    .chunk(4, ChunkCompression.ZLIB, chunkNbt(4, 0, pcl)).write()
            }, threshold = 2),
            "overlapping-sectors" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0, pcl))
                    .chunk(1, ChunkCompression.ZLIB, chunkNbt(1, 0), locationOverride = (2 shl 8) or 1).write()
            }),
            "trailing-partial-sector" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0, pcl)).write().also { path ->
                    val all = Files.readAllBytes(path)
                    Files.write(path, all.copyOf(8192 + 100))
                }
            }),
            "zero-length" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), lengthOverride = 0).write() }),
            "length-past-sectors" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), lengthOverride = 9000).write() }),
            "offset-in-header" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (1 shl 8) or 1).write() }),
            "count-zero" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (2 shl 8)).write() }),
            "offset-past-end" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (50 shl 8) or 1).write() }),
            "unknown-compression" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, 5, chunkNbt(0, 0), rawPayload = byteArrayOf(1, 2, 3)).write() }),
            "lz4" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.LZ4, chunkNbt(0, 0), rawPayload = byteArrayOf(1, 2, 3)).write() }),
            "custom" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.CUSTOM, chunkNbt(0, 0), rawPayload = byteArrayOf(1, 2, 3)).write() }),
            "bad-zlib" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), rawPayload = byteArrayOf(9, 9, 9, 9)).write() }),
            "truncated-zlib" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), rawPayload = BoundedIo.compressChunk(ChunkCompression.ZLIB, chunkNbt(0, 0)).let { it.copyOf(it.size - 6) }).write()
            }),
            "truncated-gzip" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.GZIP, chunkNbt(0, 0), rawPayload = BoundedIo.gzip(chunkNbt(0, 0)).let { it.copyOf(it.size - 4) }).write()
            }),
            "truncated-zstd" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZSTD, chunkNbt(0, 0), rawPayload = BoundedIo.compressChunk(ChunkCompression.ZSTD, chunkNbt(0, 0)).let { it.copyOf(it.size - 3) }).write()
            }),
            "missing-mcc" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), external = true).write().also { Files.delete(d.resolve("c.0.0.mcc")) }
            }),
            "invalid-nbt" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0).copyOf(20)).write() }),
            "truncated-header" to RegionCase({ d -> d.resolve("r.0.0.mca").also { Files.write(it, ByteArray(100)) } }),
            "not-a-region-name" to RegionCase({ d -> d.resolve("region.mca").also { Files.write(it, ByteArray(8192)) } }),
            "over-limit" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0)).write() }, chunkLimit = 16),
            "empty-file" to RegionCase({ d -> d.resolve("r.0.0.mca").also { Files.write(it, ByteArray(0)) } }),
            "empty-file-keep" to RegionCase({ d -> d.resolve("r.0.0.mca").also { Files.write(it, ByteArray(0)) } }, keep = setOf(0 to 0)),
            "header-only" to RegionCase({ d -> d.resolve("r.0.0.mca").also { Files.write(it, ByteArray(8192)) } }),
            "header-only-keep" to RegionCase({ d -> d.resolve("r.0.0.mca").also { Files.write(it, ByteArray(8192)) } }, keep = setOf(0 to 0)),
            "dropped-never-read" to RegionCase({ d ->
                TestRegion(d, 0, 0)
                    .chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0, pcl))
                    .chunk(1, ChunkCompression.ZLIB, chunkNbt(1, 0), rawPayload = byteArrayOf(9, 9, 9, 9))
                    .chunk(2, ChunkCompression.LZ4, chunkNbt(2, 0), rawPayload = byteArrayOf(1, 2, 3))
                    .chunk(3, ChunkCompression.NONE, chunkNbt(3, 0, extra = bytes(3, 10_000)))
                    .chunk(4, ChunkCompression.ZLIB, chunkNbt(4, 0), locationOverride = (1 shl 8) or 1)
                    .chunk(5, ChunkCompression.ZLIB, chunkNbt(5, 0), locationOverride = (500 shl 8) or 1)
                    .chunk(6, ChunkCompression.ZLIB, chunkNbt(6, 0).copyOf(20))
                    .chunk(7, ChunkCompression.ZLIB, chunkNbt(7, 0), external = true)
                    .write().also { Files.delete(d.resolve("c.7.0.mcc")) }
            }, keep = setOf(0 to 0), chunkLimit = 2_000),
            "keep-none" to RegionCase({ d ->
                TestRegion(d, 1, -1).chunk(0, ChunkCompression.ZLIB, chunkNbt(32, -32, pcl)).chunk(9, ChunkCompression.ZLIB, chunkNbt(41, -32), external = true).write()
            }, keep = emptySet()),
            "keep-meta" to RegionCase({ d ->
                TestRegion(d, -1, 0)
                    .chunk(0, ChunkCompression.GZIP, chunkNbt(-32, 0), timestamp = 111)
                    .chunk(1, ChunkCompression.ZLIB, chunkNbt(-31, 0), timestamp = 222)
                    .chunk(37, ChunkCompression.ZSTD, chunkNbt(-27, 1), timestamp = 333)
                    .chunk(40, ChunkCompression.ZLIB, chunkNbt(-24, 1), timestamp = 444, external = true)
                    .chunk(41, ChunkCompression.ZLIB, chunkNbt(-23, 1), timestamp = 555, external = true)
                    .write()
            }, keep = setOf(-32 to 0, -27 to 1, -24 to 1)),
            "keep-all" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0)).chunk(1, ChunkCompression.GZIP, chunkNbt(1, 0)).write()
            }, keep = setOf(0 to 0, 1 to 0)),
            "keep-changed" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0, pcl)).chunk(1, ChunkCompression.ZSTD, chunkNbt(1, 0, pcl))
                    .chunk(2, ChunkCompression.GZIP, chunkNbt(2, 0, pcl), external = true).write()
            }, keep = setOf(1 to 0, 2 to 0)),
            "keep-strict-bad-zlib" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), rawPayload = byteArrayOf(9, 9, 9, 9)).write() }, keep = setOf(0 to 0)),
            "keep-strict-offset" to RegionCase({ d -> TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (1 shl 8) or 1).write() }, keep = setOf(0 to 0)),
            "keep-strict-truncated-header" to RegionCase({ d -> d.resolve("r.0.0.mca").also { Files.write(it, ByteArray(100)) } }, keep = setOf(0 to 0)),
            "swap" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0, pcl)).chunk(1, ChunkCompression.ZLIB, chunkNbt(1, 0, online)).write()
            }, mapping = "swap"),
            "types" to RegionCase({ d ->
                TestRegion(d, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0)).chunk(1, ChunkCompression.ZSTD, chunkNbt(1, 0))
                    .chunk(2, ChunkCompression.ZSTD, chunkNbt(2, 0), external = true)
                    .chunk(3, ChunkCompression.LZ4, chunkNbt(3, 0), rawPayload = byteArrayOf(1, 2, 3))
                    .chunk(4, ChunkCompression.ZLIB, chunkNbt(4, 0), locationOverride = (500 shl 8) or 1).write()
            }),
        )
        val manifest = ArrayList<Map<String, Any?>>()
        cases.forEach { (name, case) ->
            val source = dir.resolve(name).resolve("in")
            val target = dir.resolve(name).resolve("out")
            Files.createDirectories(source)
            Files.createDirectories(target)
            val region = case.build(source)
            val patcher = NbtBytePatcher(mappings.getValue(case.mapping))
            val transform = ChunkTransform { _, _, nbt -> patcher.patch(nbt).getOrThrow().takeIf { it.changed }?.bytes }
            val result = RawRegionFile.rewrite(
                region, target.resolve(region.fileName.toString()), transform, case.chunkLimit, RemapLimits.MCC_COMPRESSED_BYTES, case.threshold,
                case.keep?.let { set -> ChunkFilter { x, z -> (x to z) in set } }, {},
            )
            val failure = result.exceptionOrNull()
            manifest += linkedMapOf(
                "name" to name,
                "file" to region.fileName.toString(),
                "mapping" to case.mapping,
                "keep" to case.keep?.sortedWith(compareBy({ it.first }, { it.second }))?.map { listOf(it.first, it.second) },
                "threshold" to case.threshold,
                "chunkLimit" to case.chunkLimit,
                "error" to failure?.let { it::class.simpleName },
                "errorCause" to failure?.cause?.let { it::class.simpleName },
                "result" to result.getOrNull()?.let {
                    linkedMapOf(
                        "chunkCount" to it.chunkCount, "changedChunkCount" to it.changedChunkCount, "copiedUnchanged" to it.copiedUnchanged,
                        "externalChunkFiles" to it.externalChunkFiles, "droppedChunkCount" to it.droppedChunkCount, "written" to it.written,
                    )
                },
                "compressionTypes" to RawRegionFile.compressionTypes(region).getOrNull()?.toSortedMap()?.mapKeys { it.key.toString() },
                "presentChunks" to RawRegionFile.presentChunks(region).getOrNull()?.map { listOf(it.first, it.second) },
            )
        }
        writeJson(dir.resolve("cases.json"), manifest)
    }

    // ---- Whole saves ----

    private fun regionIn(save: SyntheticSave, folder: String, rx: Int, rz: Int, vararg chunks: Pair<Int, Int>, owner: UUID? = pcl, type: Int = ChunkCompression.ZLIB) {
        val dir = save.root.resolve(folder)
        Files.createDirectories(dir)
        val builder = TestRegion(dir, rx, rz)
        chunks.forEach { (x, z) -> builder.chunk((x and 31) + (z and 31) * 32, type, chunkNbt(x, z, owner)) }
        builder.write()
    }

    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun saveCases(dir: Path) {
        val steve = SavePlayerScanner.offlineUuid("Steve")
        val cases = linkedMapOf<String, Pair<() -> SyntheticSave, Pair<String, List<SaveSyncChunk>?>>>(
            "whole" to ({
                SyntheticSave()
                    .levelDat(host = SyntheticSave.player(pcl, "Inventory" to TList(NbtTag.COMPOUND, listOf(compound("id" to TString("from level.dat"))))), hardcore = true)
                    .gzipNbt("level.dat_old", compound("Data" to compound("hardcore" to TByte(1))))
                    .gzipNbt("playerdata/${pcl}.dat", SyntheticSave.player(pcl, "Inventory" to TList(NbtTag.COMPOUND, listOf(compound("id" to TString("stale"))))))
                    .gzipNbt("playerdata/${online}.dat", SyntheticSave.player(online))
                    .gzipNbt("playerdata/${online}.dat_old", SyntheticSave.player(online))
                    .text("stats/${online}.json", """{"stats":{"minecraft:custom":{"minecraft:play_time":72000}},"owner":"${online}"}""")
                    .text("advancements/${pcl}.json", "{}")
                    .gzipNbt("data/claims.dat", compound("data" to compound("owner" to TString(pcl.toString()))))
                    .gzipNbt("data/clean.dat", compound("data" to compound("owner" to TString("nobody"))))
                    .bytes("data/raw.dat", JavaNbt.write(compound("owner" to uuidInts(online))))
                    .bytes("data/gzip-garbage.dat", byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 1, 2, 3) + uuidBytes(pcl))
                    .bytes("data/gzip-text.dat", BoundedIo.gzip("owner=${pcl}".toByteArray()))
                    .bytes("data/gzip-binary.dat", BoundedIo.gzip(byteArrayOf(-1, -2) + uuidBytes(pcl)))
                    .bytes("config/gbk.toml", "玩家=\"${pcl}\"".toByteArray(charset("GBK")))
                    .bytes("config/latin.dat", "joueur=\"${pcl}\" é".toByteArray(Charsets.ISO_8859_1))
                    .bytes("mod/blob.bin", byteArrayOf(1, 2, 3) + uuidBytes(online) + byteArrayOf(4))
                    .bytes("mod/ascii-in-binary.bin", byteArrayOf(-1, 0) + dashless(pcl).toByteArray() + byteArrayOf(0))
                    .bytes("mod/clean.bin", byteArrayOf(1, 2, 3))
                    .bytes("mod/key-conflict.dat", JavaNbt.write(compound(pcl.toString() to TInt(1), pclTarget.toString() to TInt(2))))
                    .text("mod/pairs.snbt", "{OwnerMost: ${pcl.mostSignificantBits}L, OwnerLeast: ${pcl.leastSignificantBits}L}")
                    .text("mod/${dashless(online)}.snbt", "{ owner: \"${dashless(online).uppercase()}\" }")
                    .text("tombstone/saved_players/${pcl}/x.json", "[]")
                    .bytes("session.lock", byteArrayOf())
                    .also {
                        regionIn(it, "region", 0, 0, 0 to 0, 1 to 0)
                        regionIn(it, "DIM-1/region", -1, 0, -1 to 0, owner = online, type = ChunkCompression.ZSTD)
                        regionIn(it, "data/somemod/region", 0, 0, 7 to 7)
                        Files.write(it.root.resolve("region/c.9.9.mcc"), byteArrayOf(1) + uuidBytes(pcl))
                    }
            } to ("pair" to null)),
            "marked" to ({
                SyntheticSave().levelDat().also {
                    regionIn(it, "region", 0, 0, 0 to 0, 1 to 0, 5 to 5)
                    regionIn(it, "entities", 0, 0, 0 to 0, 1 to 0)
                    regionIn(it, "poi", 0, 0, 1 to 0)
                    regionIn(it, "region", 1, 0, 32 to 0)
                    regionIn(it, "DIM-1/region", 0, 0, 0 to 0)
                    regionIn(it, "dimensions/twilightforest/twilight_forest/region", 0, 0, 3 to 4)
                    regionIn(it, "dimensions/removedmod/void/region", 0, 0, 0 to 0)
                    regionIn(it, "data/somemod/region", 0, 0, 7 to 7)
                    it.bytes("region/c.9.9.mcc", byteArrayOf(1))
                    it.bytes("dimensions/twilightforest/twilight_forest/poi/c.0.0.mcc", byteArrayOf(2))
                }
            } to ("pair" to listOf(
                SaveSyncChunk("minecraft:overworld", 0, 0, pclTarget),
                SaveSyncChunk("minecraft:overworld", 2, 2, pclTarget),
                SaveSyncChunk("twilightforest:twilight_forest", 3, 4, pclTarget),
            ))),
            "swap" to ({
                SyntheticSave()
                    .levelDat(host = SyntheticSave.player(online, "name" to TString("M from level.dat")))
                    .gzipNbt("playerdata/${online}.dat", SyntheticSave.player(online, "name" to TString("M stale")))
                    .gzipNbt("playerdata/${pclTarget}.dat", SyntheticSave.player(pclTarget, "name" to TString("R")))
                    .text("ftbteams/player/${online}.snbt", "{ id: \"${online}\" }")
                    .text("ftbteams/player/${pclTarget}.snbt", "{ id: \"${pclTarget}\" }")
                    .also {
                        regionIn(it, "region", 0, 0, 0 to 0, owner = online)
                        regionIn(it, "region", 1, 0, 32 to 0, owner = pclTarget)
                    }
            } to ("swapRdi" to null)),
            "path-conflict" to ({
                SyntheticSave().levelDat().gzipNbt("playerdata/${pcl}.dat", SyntheticSave.player(pcl)).gzipNbt("playerdata/${pclTarget}.dat", SyntheticSave.player(pclTarget))
            } to ("pair" to null)),
            "case-conflict" to ({
                SyntheticSave().levelDat().text("mod/${pcl}.txt", "a").text("mod/${pclTarget.toString().uppercase()}.txt", "b")
            } to ("pair" to null)),
            "corrupt-playerdata" to ({ SyntheticSave().levelDat().bytes("playerdata/${online}.dat", byteArrayOf(1, 2, 3)) } to ("pair" to null)),
            "corrupt-level" to ({ SyntheticSave().bytes("level.dat", BoundedIo.gzip(byteArrayOf(10, 0, 0, 99))) } to ("pair" to null)),
            "corrupt-chunk" to ({
                SyntheticSave().levelDat().also {
                    Files.createDirectories(it.root.resolve("region"))
                    TestRegion(it.root.resolve("region"), 0, 0).chunk(0, ChunkCompression.ZLIB, ByteArray(0), rawPayload = byteArrayOf(9, 9, 9)).write()
                }
            } to ("pair" to null)),
            "bad-region-name" to ({ SyntheticSave().levelDat().bytes("region/x.mca", ByteArray(0)) } to ("pair" to null)),
            "scan" to ({
                SyntheticSave()
                    .levelDat(host = SyntheticSave.player(pcl, "Pos" to TList(NbtTag.DOUBLE, listOf(TDouble(1.5), TDouble(64.0), TDouble(-3.25))), "Dimension" to TString("minecraft:the_nether")), hardcore = true)
                    .gzipNbt("playerdata/${online}.dat", SyntheticSave.player(online, "Pos" to TList(NbtTag.DOUBLE, listOf(TDouble(-17.0), TDouble(70.0), TDouble(33.9))), "Dimension" to TString("overworld")))
                    .gzipNbt("playerdata/${steve}.dat", SyntheticSave.player(steve))
                    .gzipNbt("playerdata/${pclTarget}.dat", SyntheticSave.player(pclTarget, "Pos" to TList(NbtTag.DOUBLE, listOf(TDouble(Double.NaN), TDouble(0.0), TDouble(0.0))), "Dimension" to TString("minecraft:overworld")))
                    .gzipNbt("playerdata/${pcl}.dat_old", SyntheticSave.player(pcl))
                    .text("playerdata/not-a-uuid.dat", "x")
                    .text("stats/${steve}.json", """{"stats":{"minecraft:custom":{"minecraft:play_time":72000,"minecraft:jump":3}},"DataVersion":3465}""")
                    .text("stats/${online}.json", """{"stats":{"minecraft:custom":{"minecraft:play_time" : 99999999999999999999}}}""")
                    .text("ftbteams/player/${steve}.snbt", "{\n\tid: \"${steve}\"\n\tplayer_name: \"Steve\"\n}")
                    .text("ftbteams/player/${online}.snbt", "{\n\tplayer_name: \"A\\\"lex\\\\\"\n}")
                    .text("ftbquests/${online}.snbt", "{\n\tname: \"Alex#1a2b3c4d\"\n}")
                    .text("ftbquests/${steve}.snbt", "{\n\tname: \"#only\"\n}")
                    .syncChunks(SaveSyncChunk("minecraft:the_nether", -3, 7, pclTarget), SaveSyncChunk("minecraft:overworld", -2, 2, pclTarget))
                    .also {
                        Files.writeString(it.root.parent.parent.resolve("usercache.json"),
                            """[{"name":"Notch","uuid":"${online}","expiresOn":"2020-01-01 00:00:00 +0800"},{"name":"No\"tch2","uuid":"${online}"},{"uuid":"${steve}","name":"Steve"}]""")
                    }
            } to ("pair" to null)),
        )
        val manifest = ArrayList<Map<String, Any?>>()
        val allMappings = mappings + mapOf("swapRdi" to mapOf(online to pclTarget, pclTarget to online))
        cases.forEach { (name, case) ->
            val (build, config) = case
            val (mappingName, sync) = config
            val synthetic = build()
            val game = synthetic.root.parent.parent
            val caseDir = dir.resolve(name)
            copyTree(game, caseDir.resolve("in"))
            val source = caseDir.resolve("in").resolve("saves").resolve("world")
            val target = caseDir.resolve("out")
            val snapshot = SaveSnapshot.take(source).getOrThrow()
            val report = SaveUuidRemapper(allMappings.getValue(mappingName), sync, parallelism = 2).remapTree(source, target, snapshot)
            if (report.isFailure) Files.createDirectories(target)
            val scan = SavePlayerScanner().scan(source)
            val marking = SaveMarkingIdentity.players(source)
            manifest += linkedMapOf(
                "name" to name,
                "mapping" to mappingName,
                "syncChunks" to sync?.map(::syncJson),
                "error" to report.exceptionOrNull()?.let { it::class.simpleName },
                "report" to report.getOrNull()?.let(::reportJson),
                "scanError" to scan.exceptionOrNull()?.let { it::class.simpleName },
                "scan" to scan.getOrNull()?.let(::scanJson),
                "markingError" to marking.exceptionOrNull()?.let { it::class.simpleName },
                "marking" to marking.getOrNull()?.map(::markingJson),
            )
        }
        writeJson(dir.resolve("mappings.json"), allMappings.mapValues { (_, mapping) -> uuidMap(mapping) })
        writeJson(dir.resolve("cases.json"), manifest)
        prepareCases(dir.resolve("prepare"))
    }

    /** SaveMarkingIdentity.prepare on a copy of each input; the result is the prepared world and its backup. */
    private fun prepareCases(dir: Path) {
        val owner = pcl
        val friend = online
        val rdi = pclTarget
        val inputs = linkedMapOf<String, Pair<SyntheticSave, UUID?>>(
            "friend" to (SyntheticSave()
                .levelDat(compound("UUID" to uuidInts(owner), "InventoryMarker" to TInt(1)))
                .gzipNbt("level.dat_old", compound("Data" to compound("Player" to compound("UUID" to uuidInts(owner)), "Keep" to TString("old"))))
                .gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(friend), "InventoryMarker" to TInt(2)))
                .gzipNbt("playerdata/${rdi}.dat", compound("UUID" to uuidInts(rdi), "InventoryMarker" to TInt(3))) to friend),
            "owner" to (SyntheticSave()
                .levelDat(compound("UUID" to uuidInts(owner), "InventoryMarker" to TInt(1)))
                .gzipNbt("playerdata/${owner}.dat", compound("UUID" to uuidInts(owner), "InventoryMarker" to TInt(0))) to owner),
            "no-owner" to (SyntheticSave().levelDat().gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(friend))) to friend),
            "no-roles" to (SyntheticSave().levelDat() to null),
            "missing-role" to (SyntheticSave().levelDat().gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(friend))) to rdi),
            "mismatch" to (SyntheticSave().levelDat().gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(owner))) to friend),
            "duplicate-player" to (SyntheticSave().levelDat(compound("UUID" to uuidInts(owner)), extraData = listOf("Player" to compound("UUID" to uuidInts(friend)))) to owner),
        )
        val manifest = ArrayList<Map<String, Any?>>()
        inputs.forEach { (name, input) ->
            val (save, selected) = input
            val caseDir = dir.resolve(name)
            copyTree(save.root, caseDir.resolve("in"))
            val work = createTempDirectory("prepare-work")
            val world = work.resolve("world")
            copyTree(save.root, world)
            val result = SaveMarkingIdentity.prepare(world, selected, work.resolve("backup"))
            copyTree(world, caseDir.resolve("out"))
            if (Files.isDirectory(work.resolve("backup"))) copyTree(work.resolve("backup"), caseDir.resolve("backup"))
            manifest += linkedMapOf(
                "name" to name,
                "selected" to selected?.toString(),
                "error" to result.exceptionOrNull()?.let { it::class.simpleName },
                "errorMessage" to result.exceptionOrNull()?.message,
                "owner" to result.getOrNull()?.toString(),
            )
        }
        writeJson(dir.resolve("cases.json"), manifest)
    }

    // ---- JSON shapes ----

    private fun levelJson(metadata: LevelMetadata) = linkedMapOf(
        "versionName" to metadata.versionName,
        "dataVersion" to metadata.dataVersion,
        "serverBrands" to metadata.serverBrands,
        "hardcore" to metadata.hardcore,
        "hardcoreByteOffset" to metadata.hardcoreByteOffset,
        "singleplayerUuid" to metadata.singleplayerUuid?.toString(),
        "fmlModIds" to metadata.fmlModIds,
        "loaderDetection" to metadata.loaderDetection.name,
    )

    private fun syncJson(chunk: SaveSyncChunk) =
        linkedMapOf("dimensionId" to chunk.dimensionId, "chunkX" to chunk.chunkX, "chunkZ" to chunk.chunkZ, "owner" to chunk.owner.toString())

    private fun reportJson(report: RemapReport) = linkedMapOf(
        "filesScanned" to report.filesScanned,
        "filesRewritten" to report.filesRewritten,
        "pathsRenamed" to report.pathsRenamed,
        "replacementsBySource" to uuidCounts(report.replacementsBySource),
        "possiblyUnmigrated" to report.possiblyUnmigrated.map { linkedMapOf("relativePath" to it.relativePath, "reason" to it.reason) },
        "hardcoreSwitchedOff" to report.hardcoreSwitchedOff,
        "hostDataFromLevelDat" to report.hostDataFromLevelDat,
        "syncFilter" to report.syncFilter?.let {
            linkedMapOf("markedChunkCount" to it.markedChunkCount, "presentChunkCount" to it.presentChunkCount, "droppedFileCount" to it.droppedFileCount)
        },
        "bytesBefore" to report.bytesBefore,
    )

    private fun scanJson(scan: SaveScanResult) = linkedMapOf(
        "mcVersionName" to scan.mcVersionName,
        "dataVersion" to scan.dataVersion,
        "serverBrands" to scan.serverBrands,
        "fmlModIds" to scan.fmlModIds,
        "loaderDetection" to scan.loaderDetection.name,
        "isHardcore" to scan.isHardcore,
        "singleplayerHostUuid" to scan.singleplayerHostUuid?.toString(),
        "players" to scan.players.map(::playerJson),
        "syncChunks" to scan.syncChunks?.map(::syncJson),
        "totalBytes" to scan.totalBytes,
    )

    private fun playerJson(player: SavePlayer) = linkedMapOf(
        "uuid" to player.uuid.toString(),
        "kind" to player.kind.name,
        "nameHints" to player.nameHints.map { linkedMapOf("name" to it.name, "source" to it.source.name) },
        "verifiedName" to player.verifiedName,
        "isSingleplayerHost" to player.isSingleplayerHost,
        "hasLastPlayed" to (player.lastPlayed != null),
        "playTimeTicks" to player.playTimeTicks,
    )

    private fun markingJson(marking: SaveMarkingPlayer) = linkedMapOf(
        "uuid" to marking.player.uuid.toString(),
        "displayName" to marking.displayName,
        "position" to marking.position?.let { linkedMapOf("dimension" to it.dimension, "x" to it.x, "y" to it.y, "z" to it.z) },
    )

    // ---- Helpers ----

    private fun dashless(uuid: UUID) = uuid.toString().replace("-", "")

    private fun ints(uuid: UUID): List<Int> = uuidInts(uuid).value

    private fun uuidMap(mapping: Map<UUID, UUID>) = mapping.entries.associate { (k, v) -> k.toString() to v.toString() }

    private fun uuidCounts(counts: Map<UUID, Int>) = counts.entries.sortedBy { it.key.toString() }.associate { (k, v) -> k.toString() to v }

    private fun doc(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(10, 0, 0, type.toByte(), 0, 1, 'v'.code.toByte()) + payload + byteArrayOf(0)

    private fun nested(depth: Int): ByteArray {
        var tag = compound()
        repeat(depth) { tag = compound("c" to tag) }
        return JavaNbt.write(tag)
    }

    /** A root compound holding [depth] nested lists, the innermost one holding an empty compound. */
    private fun nestedLists(depth: Int): ByteArray {
        var tag: Tag = compound()
        var type = NbtTag.COMPOUND
        repeat(depth) {
            tag = TList(type, listOf(tag))
            type = NbtTag.LIST
        }
        return JavaNbt.write(compound("l" to tag))
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { path ->
                val destination = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            }
        }
    }

    private fun randomCompound(random: Random, depth: Int, hits: Boolean): TCompound {
        val entries = ArrayList<Pair<String, Tag>>()
        repeat(random.nextInt(0, 6)) { entries += "k${it}_${randomText(random, hits)}" to randomTag(random, depth + 1, hits) }
        if (hits && random.nextInt(0, 6) == 0) {
            val prefix = "p${random.nextInt(0, 3)}"
            val uuid = if (random.nextBoolean()) pcl else online
            entries += "${prefix}Most" to TLong(uuid.mostSignificantBits)
            entries += "${prefix}Least" to TLong(uuid.leastSignificantBits)
        }
        if (hits && random.nextInt(0, 8) == 0) entries += (if (random.nextBoolean()) pcl.toString() else dashless(online).uppercase()) to TByte(1)
        return TCompound(entries.distinctBy { it.first })
    }

    private fun randomTag(random: Random, depth: Int, hits: Boolean): Tag {
        val choice = if (depth > 4) random.nextInt(0, 9) else random.nextInt(0, 14)
        return when (choice) {
            0 -> TByte(random.nextInt().toByte())
            1 -> TShort(random.nextInt().toShort())
            2 -> TInt(random.nextInt())
            3 -> TLong(random.nextLong())
            4 -> TDouble(random.nextDouble())
            5 -> TString(randomText(random, hits))
            6 -> uuidInts(if (hits && random.nextBoolean()) pcl else UUID(random.nextLong(), random.nextLong()))
            7 -> TByteArray(random.nextBytes(random.nextInt(0, 40)).toList())
            8 -> TLongArray(if (hits && random.nextBoolean()) listOf(online.mostSignificantBits, online.leastSignificantBits) else listOf(random.nextLong(), random.nextLong()))
            9 -> TList(NbtTag.STRING, List(random.nextInt(0, 4)) { TString(randomText(random, hits)) })
            10 -> TList(NbtTag.COMPOUND, List(random.nextInt(0, 3)) { randomCompound(random, depth, hits) })
            11 -> randomCompound(random, depth, hits)
            12 -> TList(NbtTag.INT_ARRAY, List(random.nextInt(0, 3)) { uuidInts(if (random.nextBoolean()) online else UUID(random.nextLong(), 0)) })
            else -> TList(NbtTag.FLOAT, List(random.nextInt(0, 4)) { TFloat(random.nextFloat()) })
        }
    }

    private fun randomText(random: Random, hits: Boolean): String = when (random.nextInt(0, if (hits) 8 else 4)) {
        0 -> UUID(random.nextLong(), random.nextLong()).toString()
        1 -> dashless(UUID(random.nextLong(), random.nextLong())).uppercase()
        2 -> "中文😀\u0000" + random.nextInt()
        3 -> "t" + random.nextLong().toString(16)
        4 -> "x${pcl}y"
        5 -> "${dashless(online)}${random.nextInt(0, 2)}"
        6 -> "[${pcl.toString().uppercase()}]"
        else -> "${online}-${random.nextInt()}"
    }

    // ---- Minimal JSON writer ----

    private fun writeJson(file: Path, value: Any?) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json(value) + "\n")
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (ch in value) {
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch < ' ' || ch in '\u007f'..'\u009f' || ch.isSurrogate() || ch == ' ' || ch == ' ' ->
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else -> append(ch)
                }
            }
            append('"')
        }
        is Boolean, is Int, is Long -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else "\"${value}\""
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k.toString()) + ":" + json(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("Unsupported JSON value ${value::class}")
    }
}
