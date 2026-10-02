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
                0xba.toByte(), 0x20, 0xea.toByte(), 0xc2.toByte(), 0x68, 0x2e, 0xfd.toByte(), 0x0b,
                0x78, 0x3d, 0x98.toByte(), 0xf7.toByte(), 0x56, 0x8c.toByte(), 0xe0.toByte(),
                0x8d.toByte(), 0x9a.toByte(), 0x1e, 0x68, 0xe9.toByte(),
            ),
            digest,
        )
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
