package calebxzau.rdi.mc.chunkcache

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TerrainHashTest {
    @Test
    fun canonicalDigestMatchesReferenceBytesForNegativeRangeAndLargeIds() {
        val digest = TerrainHash.sha1(
            minSection = -7,
            sectionCount = 2,
            blockStateAt = TerrainIdReader { section, x, y, z -> 128 + section * 300 + y * 17 + z * 3 + x },
            biomeAt = TerrainIdReader { section, x, y, z -> 200 + section * 64 + y * 8 + z * 2 + x },
        )

        assertContentEquals(
            byteArrayOf(
                0xbd.toByte(), 0x4b, 0x9f.toByte(), 0xb3.toByte(), 0xc8.toByte(), 0x26, 0xb3.toByte(), 0x33,
                0x48, 0x91.toByte(), 0xec.toByte(), 0x07, 0x54, 0xdb.toByte(), 0xfe.toByte(), 0x48,
                0x62, 0x29, 0xdc.toByte(), 0x6a,
            ),
            digest,
        )
    }

    @Test
    fun uniformSectionDigestMatchesReferenceBytes() {
        val digest = TerrainHash.sha1(3, 1, { _, _, _, _ -> 9 }, { _, _, _, _ -> 1 })

        assertContentEquals(
            byteArrayOf(
                0x6d, 0xd1.toByte(), 0xa8.toByte(), 0x26, 0xc9.toByte(), 0xef.toByte(), 0x83.toByte(), 0x06,
                0x54, 0x24, 0x7b, 0xdb.toByte(), 0xef.toByte(), 0x38, 0xfe.toByte(), 0xc0.toByte(),
                0x4e, 0x64, 0xfe.toByte(), 0x74,
            ),
            digest,
        )
    }

    @Test
    fun uniformShortcutEqualsScannedSection() {
        val biomes = IntArray(TerrainHash.BIOMES_PER_SECTION) { it % 3 }
        val scanned = TerrainHash.sectionSha1(IntArray(TerrainHash.BLOCKS_PER_SECTION) { 42 }, biomes)

        assertContentEquals(scanned, TerrainHash.uniformSectionSha1(42, biomes))
        val oneDifferent = IntArray(TerrainHash.BLOCKS_PER_SECTION) { 42 }.also { it[4095] = 43 }
        assertNotEquals(scanned.toList(), TerrainHash.sectionSha1(oneDifferent, biomes).toList())
    }

    @Test
    fun chunkDigestRejectsMalformedSectionHashes() {
        assertFailsWith<IllegalArgumentException> { TerrainHash.chunkSha1(0, emptyArray()) }
        assertFailsWith<IllegalArgumentException> { TerrainHash.chunkSha1(0, arrayOf(ByteArray(19))) }
        assertFailsWith<IllegalArgumentException> {
            TerrainHash.sectionSha1(IntArray(TerrainHash.BLOCKS_PER_SECTION - 1), IntArray(TerrainHash.BIOMES_PER_SECTION))
        }
        assertFailsWith<IllegalArgumentException> { TerrainHash.uniformSectionSha1(-1, IntArray(TerrainHash.BIOMES_PER_SECTION)) }
    }

    @Test
    fun terrainIdReaderUsesPrimitiveJvmSignature() {
        val method = TerrainIdReader::class.java.getMethod(
            "get",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        kotlin.test.assertEquals(Int::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun sameSemanticValuesProduceSameHash() {
        val first = hash({ section, x, y, z -> (section * 3 + x + y * 2 + z * 5) % 17 }, { section, x, y, z -> (section + x + y + z) % 4 })
        val second = hash({ section, x, y, z -> (section * 3 + x + y * 2 + z * 5) % 17 }, { section, x, y, z -> (section + x + y + z) % 4 })

        assertContentEquals(first, second)
        kotlin.test.assertEquals(20, first.size)
    }

    @Test
    fun blockBiomeAndSectionRangeChangesAffectHash() {
        val baseline = hash({ _, x, y, z -> (x + y + z) % 3 }, { _, x, y, z -> (x + y + z) % 2 })
        val changedBlock = hash({ section, x, y, z -> if (section == 0 && x == 7 && y == 4 && z == 9) 42 else (x + y + z) % 3 }, { _, x, y, z -> (x + y + z) % 2 })
        val changedBiome = hash({ _, x, y, z -> (x + y + z) % 3 }, { section, x, y, z -> if (section == 0 && x == 2 && y == 1 && z == 3) 8 else (x + y + z) % 2 })
        val changedRange = TerrainHash.sha1(5, 1, { _, x, y, z -> (x + y + z) % 3 }, { _, x, y, z -> (x + y + z) % 2 })

        assertNotEquals(baseline.toList(), changedBlock.toList())
        assertNotEquals(baseline.toList(), changedBiome.toList())
        assertNotEquals(baseline.toList(), changedRange.toList())
    }

    @Test
    fun callbackOrderIsCanonicalAndInvalidInputsAreRejected() {
        val seenBlocks = ArrayList<Int>(4096)
        val seenBiomes = ArrayList<Int>(64)
        TerrainHash.sha1(0, 1, { _, x, y, z -> seenBlocks += (y shl 8) or (z shl 4) or x; 0 },
            { _, x, y, z -> seenBiomes += (y shl 4) or (z shl 2) or x; 0 })

        kotlin.test.assertEquals(0, seenBlocks.first())
        kotlin.test.assertEquals(1, seenBlocks[1])
        kotlin.test.assertEquals((1 shl 4), seenBlocks[16])
        kotlin.test.assertEquals(1 shl 8, seenBlocks[256])
        kotlin.test.assertEquals(0, seenBiomes.first())
        kotlin.test.assertEquals(1, seenBiomes[1])
        kotlin.test.assertEquals(4, seenBiomes[4])
        kotlin.test.assertEquals(16, seenBiomes[16])

        assertFailsWith<IllegalArgumentException> {
            TerrainHash.sha1(0, 0, { _, _, _, _ -> 0 }, { _, _, _, _ -> 0 })
        }
        assertFailsWith<IllegalArgumentException> {
            TerrainHash.sha1(0, 1, { _, _, _, _ -> -1 }, { _, _, _, _ -> 0 })
        }
        assertFailsWith<IllegalArgumentException> {
            TerrainHash.sha1(0, 1, { _, _, _, _ -> 0 }, { _, _, _, _ -> -1 })
        }
    }

    private fun hash(
        blocks: TerrainIdReader,
        biomes: TerrainIdReader,
    ): ByteArray = TerrainHash.sha1(0, 2, blocks, biomes)
}
