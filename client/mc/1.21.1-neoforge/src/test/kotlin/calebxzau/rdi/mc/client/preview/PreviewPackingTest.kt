package calebxzau.rdi.mc.client.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewPackingTest {
    @Test
    fun packsRowsAndPagesWithoutPadding(): Unit {
        assertEquals(PreviewPacking.position(0, 128), PreviewPosition(page = 0, x = 0, y = 0))
        assertEquals(PreviewPacking.position(1, 128), PreviewPosition(page = 0, x = 64, y = 0))
        assertEquals(PreviewPacking.position(3, 128), PreviewPosition(page = 0, x = 64, y = 64))
        assertEquals(PreviewPacking.position(4, 128), PreviewPosition(page = 1, x = 0, y = 0))
    }

    @Test
    fun rejectsInvalidPackingInput(): Unit {
        assertFailsWith<IllegalArgumentException> { PreviewPacking.position(-1, 128) }
        assertFailsWith<IllegalArgumentException> { PreviewPacking.position(0, 63) }
        assertFailsWith<IllegalArgumentException> { PreviewPacking.position(0, 8193) }
        assertFailsWith<IllegalArgumentException> { PreviewPacking.position(0, Int.MAX_VALUE - 63) }
    }

    @Test
    fun plansExpectedPageShapes(): Unit {
        assertEquals(emptyList(), PreviewPacking.plan(0))
        assertEquals(listOf(64 to 64), PreviewPacking.plan(1).map { it.width to it.height })
        assertEquals(listOf(2048 to 64), PreviewPacking.plan(32).map { it.width to it.height })
        assertEquals(listOf(2048 to 128), PreviewPacking.plan(33).map { it.width to it.height })
        assertEquals(listOf(2048 to 2048), PreviewPacking.plan(1024).map { it.width to it.height })
        assertEquals(listOf(2048 to 2048, 64 to 64), PreviewPacking.plan(1025).map { it.width to it.height })
        assertEquals(listOf(2048 to 2048, 2048 to 2048), PreviewPacking.plan(2045).map { it.width to it.height })
    }

    @Test
    fun plansHaveNonOverlappingBoundedCells(): Unit {
        val plan = PreviewPacking.plan(1025)
        val seen = HashSet<String>()
        plan.forEach { page ->
            repeat(page.itemCount) { offset ->
                val position = page.position(offset)
                assertTrue(position.x + PreviewPacking.ICON_SIZE <= page.width)
                assertTrue(position.y + PreviewPacking.ICON_SIZE <= page.height)
                assertTrue(seen.add("${position.page}:${position.x}:${position.y}"))
            }
        }
        assertEquals(1025, seen.size)
    }

    @Test
    fun supportsHardwareFallbackCapacityAndRejectsInvalidPlans(): Unit {
        val fallback = PreviewPacking.plan(257, maxEdge = 1024)
        assertEquals(listOf(1024 to 1024, 64 to 64), fallback.map { it.width to it.height })
        assertEquals(0, fallback.first().firstItemIndex)
        assertEquals(256, fallback[1].firstItemIndex)
        assertEquals(257, fallback.sumOf { it.itemCount })

        val smallest = PreviewPacking.plan(3, maxEdge = 64)
        assertEquals(3, smallest.size)
        assertTrue(smallest.all { it.width == 64 && it.height == 64 && it.itemCount == 1 })
        assertFailsWith<IllegalArgumentException> { PreviewPacking.plan(-1) }
        assertFailsWith<IllegalArgumentException> { PreviewPacking.plan(1, maxEdge = 32) }
        assertFailsWith<IllegalArgumentException> { PreviewPacking.plan(1, maxEdge = 2049) }
    }
}
