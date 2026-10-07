package calebxzau.rdi.anvilrw.remap

import java.nio.ByteBuffer
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NbtBytePatcherTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val pclTarget = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val onlineTarget = UUID.fromString("66778899-aabb-ccdd-eeff-001100000000")
    private val patcher = NbtBytePatcher(mapOf(pcl to pclTarget, online to onlineTarget))

    private fun dashless(uuid: UUID) = uuid.toString().replace("-", "")

    private fun patch(root: TCompound): NbtPatchResult = patcher.patch(JavaNbt.write(root)).getOrThrow()

    @Test
    fun modifiedUtf8StringsSurviveByteForByte() {
        val text = "中文😀\u0000end"
        val before = compound(
            "name" to TString(text),
            "Owner" to uuidInts(pcl),
            "sign" to TString("emoji 🎉 ${pcl} 尾"),
            "nul" to TString("\u0000"),
        )
        val after = compound(
            "name" to TString(text),
            "Owner" to uuidInts(pclTarget),
            "sign" to TString("emoji 🎉 ${pclTarget} 尾"),
            "nul" to TString("\u0000"),
        )

        val result = patch(before)

        assertTrue(result.changed)
        assertContentEquals(JavaNbt.write(after), result.bytes)
        assertEquals(after, JavaNbt.read(result.bytes))
        assertEquals(mapOf(pcl to 2), result.replacements)
    }

    @Test
    fun replacesEveryFullUuidFormKeepingStyle() {
        val upperDashed = pcl.toString().uppercase()
        val before = compound(
            "ints" to uuidInts(pcl),
            "longs" to TLongArray(listOf(online.mostSignificantBits, online.leastSignificantBits)),
            "OwnerUUIDMost" to TLong(pcl.mostSignificantBits),
            "OwnerUUIDLeast" to TLong(pcl.leastSignificantBits),
            "dashed" to TString(pcl.toString()),
            "upper" to TString(upperDashed),
            "dashless" to TString(dashless(online)),
            "dashlessUpper" to TString(dashless(online).uppercase()),
            "json" to TString("""{"text":"${online}","extra":"${pcl}"}"""),
            "list" to TList(NbtTag.STRING, listOf(TString(pcl.toString()), TString("x"))),
            "listOfCompounds" to TList(NbtTag.COMPOUND, listOf(compound("Owner" to uuidInts(online)))),
            pcl.toString() to TInt(7),
        )
        val after = compound(
            "ints" to uuidInts(pclTarget),
            "longs" to TLongArray(listOf(onlineTarget.mostSignificantBits, onlineTarget.leastSignificantBits)),
            "OwnerUUIDMost" to TLong(pclTarget.mostSignificantBits),
            "OwnerUUIDLeast" to TLong(pclTarget.leastSignificantBits),
            "dashed" to TString(pclTarget.toString()),
            "upper" to TString(pclTarget.toString().uppercase()),
            "dashless" to TString(dashless(onlineTarget)),
            "dashlessUpper" to TString(dashless(onlineTarget).uppercase()),
            "json" to TString("""{"text":"${onlineTarget}","extra":"${pclTarget}"}"""),
            "list" to TList(NbtTag.STRING, listOf(TString(pclTarget.toString()), TString("x"))),
            "listOfCompounds" to TList(NbtTag.COMPOUND, listOf(compound("Owner" to uuidInts(onlineTarget)))),
            pclTarget.toString() to TInt(7),
        )

        val result = patch(before)

        assertContentEquals(JavaNbt.write(after), result.bytes)
        assertEquals(mapOf(pcl to 7, online to 5), result.replacements)
    }

    @Test
    fun mostLeastPairsNeedTheSamePrefix() {
        val before = compound(
            "AMost" to TLong(pcl.mostSignificantBits),
            "BLeast" to TLong(pcl.leastSignificantBits),
        )
        val result = patch(before)
        assertFalse(result.changed)
    }

    @Test
    fun renamedKeyThatCollidesIsAConflict() {
        val root = compound(pcl.toString() to TInt(1), pclTarget.toString() to TInt(2))
        val failure = patcher.patch(JavaNbt.write(root)).exceptionOrNull()
        assertIs<NbtKeyConflictException>(failure)
    }

    @Test
    fun partialValuesNeverMatch() {
        val root = compound(
            "msbAsLong" to TLong(12300L),
            "sharedFirstInts" to TIntArray(listOf(0, 12300, 1, 2)),
            "halfLongs" to TLongArray(listOf(pcl.mostSignificantBits, 5L)),
            "onlyMost" to TLong(pcl.mostSignificantBits),
            "prefix" to TString("00000000-0000-300c"),
            "threeInts" to TIntArray(uuidInts(pcl).value.take(3)),
            "fiveInts" to TIntArray(uuidInts(pcl).value + 0),
        )
        val bytes = JavaNbt.write(root)
        val result = patcher.patch(bytes).getOrThrow()
        assertFalse(result.changed)
        assertSame(bytes, result.bytes)
    }

    @Test
    fun hexBoundariesAreRespected() {
        val unchanged = listOf(
            "${pcl}a",
            "a${pcl}",
            "-${pcl}",
            "${pcl}-x",
            "${dashless(pcl)}0",
            "f${dashless(pcl)}",
        )
        unchanged.forEach { text ->
            assertFalse(patch(compound("s" to TString(text))).changed, text)
        }
        val replaced = mapOf(
            "-${dashless(pcl)}" to "-${dashless(pclTarget)}",
            "x${dashless(pcl)}y" to "x${dashless(pclTarget)}y",
            "g${pcl}g" to "g${pclTarget}g",
            "${pcl}${pcl}" to "${pcl}${pcl}",
        )
        replaced.forEach { (text, expected) ->
            val result = patch(compound("s" to TString(text)))
            assertEquals(compound("s" to TString(expected)), JavaNbt.read(result.bytes), text)
        }
    }

    @Test
    fun structuralErrorsFailInEveryMode() {
        val valid = JavaNbt.write(compound("a" to TInt(1)))
        val broken = listOf(
            "unknown tag" to byteArrayOf(10, 0, 0, 13, 0, 1, 'a'.code.toByte(), 0),
            "root not compound" to byteArrayOf(8, 0, 0, 0, 0),
            "negative array" to doc(NbtTag.INT_ARRAY, ByteBuffer.allocate(4).putInt(-1).array()),
            "truncated" to valid.copyOf(valid.size - 1),
            "trailing" to valid + 0,
            "end list with elements" to doc(NbtTag.LIST, byteArrayOf(0, 0, 0, 0, 1)),
            "huge long array" to doc(NbtTag.LONG_ARRAY, ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()),
            "huge int array" to doc(NbtTag.INT_ARRAY, ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()),
            "huge byte array" to doc(NbtTag.BYTE_ARRAY, ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()),
            "huge list" to doc(NbtTag.LIST, byteArrayOf(NbtTag.COMPOUND.toByte(), 0x7f, -1, -1, -1)),
            "huge string" to doc(NbtTag.STRING, byteArrayOf(-1, -1, 'a'.code.toByte())),
        )
        broken.forEach { (name, bytes) ->
            assertIs<NbtFormatException>(patcher.patch(bytes).exceptionOrNull(), name)
            assertIs<NbtFormatException>(NbtBytePatcher.validate(bytes).exceptionOrNull(), name)
            assertIs<NbtFormatException>(NbtMetadataReader.open(bytes).exceptionOrNull(), name)
        }
    }

    @Test
    fun depthLimitMatchesMinecraft() {
        assertTrue(NbtBytePatcher.validate(nested(512)).isSuccess)
        assertIs<NbtFormatException>(NbtBytePatcher.validate(nested(513)).exceptionOrNull())
    }

    @Test
    fun extractsExactCompoundPayload() {
        val player = compound("UUID" to uuidInts(pcl), "Name" to TString("玩家😀"), "Inventory" to TList(NbtTag.END, emptyList()))
        val level = compound("Data" to compound("Version" to compound("Name" to TString("1.20.1")), "Player" to player))
        val reader = NbtMetadataReader.open(JavaNbt.write(level)).getOrThrow()

        val payload = reader.compoundPayload("Data", "Player")!!

        assertContentEquals(JavaNbt.payload(player), payload)
        assertEquals(player, JavaNbt.read(NbtMetadataReader.rootDocument(payload)))
    }

    @Test
    fun readsLevelMetadata() {
        val level = compound(
            "Data" to compound(
                "Version" to compound("Name" to TString("1.20.1")),
                "DataVersion" to TInt(3465),
                "ServerBrands" to TList(NbtTag.STRING, listOf(TString("forge"), TString("品牌😀"))),
                "hardcore" to TByte(1),
                "Player" to compound("UUID" to uuidInts(pcl)),
            ),
            "fml" to compound(
                "LoadingModList" to TList(
                    NbtTag.COMPOUND,
                    listOf(compound("ModId" to TString("minecraft")), compound("ModId" to TString("forge"))),
                ),
            ),
        )
        val bytes = JavaNbt.write(level)

        val metadata = NbtMetadataReader.open(bytes).getOrThrow().levelMetadata().getOrThrow()

        assertEquals("1.20.1", metadata.versionName)
        assertEquals(3465, metadata.dataVersion)
        assertEquals(listOf("forge", "品牌😀"), metadata.serverBrands)
        assertTrue(metadata.hardcore)
        assertEquals(1, bytes[metadata.hardcoreByteOffset!!].toInt())
        assertEquals(pcl, metadata.singleplayerUuid)
        assertEquals(listOf("minecraft", "forge"), metadata.fmlModIds)
    }

    @Test
    fun levelMetadataIsLenientLikeMinecraft() {
        val level = compound("Data" to compound("hardcore" to TInt(1), "ServerBrands" to TString("forge")))
        val metadata = NbtMetadataReader.open(JavaNbt.write(level)).getOrThrow().levelMetadata().getOrThrow()

        assertFalse(metadata.hardcore)
        assertEquals(null, metadata.hardcoreByteOffset)
        assertEquals(emptyList(), metadata.serverBrands)
        assertEquals(null, metadata.fmlModIds)
        assertEquals(null, metadata.versionName)
    }

    @Test
    fun randomDocumentsWithoutSourceUuidsAreUnchanged() {
        val random = Random(20261006)
        repeat(200) { iteration ->
            val root = randomCompound(random, 0)
            val bytes = JavaNbt.write(root)
            val result = patcher.patch(bytes).getOrThrow()
            assertFalse(result.changed, "iteration ${iteration}")
            assertSame(bytes, result.bytes)
        }
    }

    @Test
    fun rejectsAmbiguousMappings() {
        assertFailsWith<IllegalArgumentException> { NbtBytePatcher(mapOf(pcl to pclTarget, online to pclTarget)) }
        assertFailsWith<IllegalArgumentException> { NbtBytePatcher(mapOf(pcl to online, online to onlineTarget)) }
        assertFailsWith<IllegalArgumentException> { NbtBytePatcher(mapOf(pcl to pcl)) }
    }

    @Test
    fun swapExchangesTwoProfilesEverywhere() {
        val swap = NbtBytePatcher(mapOf(pcl to online, online to pcl))
        val before = compound(
            "Owner" to uuidInts(pcl),
            "Friend" to uuidInts(online),
            "text" to TString("${pcl} ${online}"),
            pcl.toString() to TString("pcl slot"),
            online.toString() to TString("online slot"),
            "OwnerMost" to TLong(online.mostSignificantBits),
            "OwnerLeast" to TLong(online.leastSignificantBits),
        )
        val after = compound(
            "Owner" to uuidInts(online),
            "Friend" to uuidInts(pcl),
            "text" to TString("${online} ${pcl}"),
            online.toString() to TString("pcl slot"),
            pcl.toString() to TString("online slot"),
            "OwnerMost" to TLong(pcl.mostSignificantBits),
            "OwnerLeast" to TLong(pcl.leastSignificantBits),
        )

        val result = swap.patch(JavaNbt.write(before)).getOrThrow()

        assertContentEquals(JavaNbt.write(after), result.bytes)
        assertEquals(mapOf(pcl to 3, online to 4), result.replacements)
    }

    @Test
    fun boundedDecompressionStopsAtTheLimit() {
        val compressed = BoundedIo.gzip(ByteArray(2 * 1024 * 1024))
        assertFailsWith<RemapLimitExceededException> { BoundedIo.gunzip(compressed, 1024 * 1024, "test") }
        assertEquals(2 * 1024 * 1024, BoundedIo.gunzip(compressed, 2 * 1024 * 1024, "test").size)
        listOf(0, 1, 65535, 65536, 65537, 200_000).forEach { size ->
            val data = Random(size).nextBytes(size)
            assertContentEquals(data, BoundedIo.gunzip(BoundedIo.gzip(data), size, "test"))
        }
    }

    /** A document whose root holds one entry of [type] with the given raw payload. */
    private fun doc(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(10, 0, 0, type.toByte(), 0, 1, 'v'.code.toByte()) + payload + byteArrayOf(0)

    /** A root compound with [depth] nested child compounds below it. */
    private fun nested(depth: Int): ByteArray {
        var tag = compound()
        repeat(depth) { tag = compound("c" to tag) }
        return JavaNbt.write(tag)
    }

    private fun randomCompound(random: Random, depth: Int): TCompound =
        TCompound(List(random.nextInt(0, 6)) { "k${it}_${randomText(random)}" to randomTag(random, depth + 1) })

    private fun randomTag(random: Random, depth: Int): Tag {
        val choice = if (depth > 4) random.nextInt(0, 8) else random.nextInt(0, 12)
        return when (choice) {
            0 -> TByte(random.nextInt().toByte())
            1 -> TShort(random.nextInt().toShort())
            2 -> TInt(random.nextInt())
            3 -> TLong(random.nextLong())
            4 -> TDouble(random.nextDouble())
            5 -> TString(randomText(random))
            6 -> uuidInts(UUID(random.nextLong(), random.nextLong()))
            7 -> TByteArray(random.nextBytes(random.nextInt(0, 40)).toList())
            8 -> TList(NbtTag.STRING, List(random.nextInt(0, 4)) { TString(randomText(random)) })
            9 -> TList(NbtTag.COMPOUND, List(random.nextInt(0, 3)) { randomCompound(random, depth) })
            10 -> randomCompound(random, depth)
            else -> TLongArray(listOf(random.nextLong(), random.nextLong()))
        }
    }

    private fun randomText(random: Random): String = when (random.nextInt(0, 4)) {
        0 -> UUID(random.nextLong(), random.nextLong()).toString()
        1 -> dashless(UUID(random.nextLong(), random.nextLong())).uppercase()
        2 -> "中文😀\u0000" + random.nextInt()
        else -> "t" + random.nextLong().toString(16)
    }
}
