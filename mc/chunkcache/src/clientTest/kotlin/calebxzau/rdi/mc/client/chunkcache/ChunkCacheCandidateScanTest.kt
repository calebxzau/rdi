package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.world.level.ChunkPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheCandidateScanTest {
    @Test
    fun primitiveOrderMatchesNearestFirstWithStableTies(): Unit {
        for ((centerX, centerZ, radius) in listOf(Triple(0, 0, 12), Triple(-321, 278, 4), Triple(312, 312, 34))) {
            val expected = buildList {
                for (z in centerZ - radius..centerZ + radius) for (x in centerX - radius..centerX + radius) {
                    add(ChunkPos.asLong(x, z))
                }
            }.sortedBy { position ->
                val dx = ChunkPos.getX(position) - centerX
                val dz = ChunkPos.getZ(position) - centerZ
                dx * dx + dz * dz
            }
            val scan = ChunkCacheCandidateScan()
            scan.update(centerX, centerZ, radius)
            assertEquals(expected, drain(scan))
            assertFalse(scan.hasNext())
        }
    }

    @Test
    fun completionPassContinuesWithoutRepeatingPrefixAndNextTickRevisitsCandidates(): Unit {
        val scan = ChunkCacheCandidateScan()
        scan.update(0, 0, 2)
        val first = scan.next()
        val second = scan.next()
        // A preparation completion resumes the same scan, even after another completion exhausted it.
        val remaining = drain(scan)
        assertEquals(23, remaining.size)
        assertFalse(first in remaining)
        assertFalse(second in remaining)
        assertEquals(23, remaining.toSet().size)
        assertFalse(scan.hasNext())
        // The next game tick must reconsider loaded/unloaded chunks, retired pins and expired retries.
        scan.restart()
        assertEquals(listOf(first, second) + remaining, drain(scan))
    }

    @Test
    fun movingOrChangingRadiusReplacesAnUnfinishedPass(): Unit {
        val scan = ChunkCacheCandidateScan()
        assertFalse(scan.hasNext())
        scan.update(0, 0, 12)
        scan.next()
        scan.update(-312, 312, 1)
        val moved = drain(scan)
        assertEquals(9, moved.size)
        assertEquals(ChunkPos.asLong(-312, 312), moved.first())
        assertTrue(moved.all { ChunkPos.getX(it) in -313..-311 && ChunkPos.getZ(it) in 311..313 })
        scan.update(-312, 312, 0)
        assertEquals(listOf(ChunkPos.asLong(-312, 312)), drain(scan))
    }

    private fun drain(scan: ChunkCacheCandidateScan): List<Long> = buildList {
        while (scan.hasNext()) add(scan.next())
    }
}
