package calebxzau.rdi.mc.v20.l2

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L2NameWindowTest {
    private val firstId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun waitsForOneSecondAndDoesNotExtendDeadline() {
        val window = L2NameWindow()
        window.offer(mapOf("movement" to mapOf(firstId to "Boots")), 0L)
        window.offer(mapOf("attack" to mapOf(secondId to "Charm")), 900_000_000L)

        assertNull(window.due(999_999_999L))
        assertEquals(
            mapOf(
                "movement" to mapOf(firstId to "Boots"),
                "attack" to mapOf(secondId to "Charm")
            ),
            window.due(1_000_000_000L)
        )
    }

    @Test
    fun mergesPartialAttributesAndKeepsOnlyLatestNamesPerAttribute() {
        val window = L2NameWindow()
        window.reset(mapOf("movement" to mapOf(firstId to "Boots")))
        window.offer(mapOf("movement" to mapOf(firstId to "Potion")), 5L)
        window.offer(mapOf("attack" to mapOf(secondId to "Charm")), 100L)
        window.offer(mapOf("movement" to mapOf(firstId to "New Boots")), 200L)

        assertEquals(
            mapOf(
                "movement" to mapOf(firstId to "New Boots"),
                "attack" to mapOf(secondId to "Charm")
            ),
            window.due(1_000_000_005L)
        )
    }

    @Test
    fun explicitEmptyMapClearsAttributeAndMissingAttributeIsUntouched() {
        val window = L2NameWindow()
        window.reset(
            mapOf(
                "movement" to mapOf(firstId to "Boots"),
                "attack" to mapOf(secondId to "Charm")
            )
        )
        window.offer(mapOf("movement" to emptyMap()), 0L)

        assertEquals(mapOf("movement" to emptyMap()), window.due(1_000_000_000L))
    }

    @Test
    fun restoresBaselineWithinWindowAndCancelsAtoBtoA() {
        val baseline = mapOf("movement" to mapOf(firstId to "Boots"))
        val window = L2NameWindow()
        window.reset(baseline)
        window.offer(mapOf("movement" to mapOf(firstId to "Potion")), 10L)
        window.offer(baseline, 300_000_000L)

        assertNull(window.due(2_000_000_000L))
    }

    @Test
    fun mapIterationOrderDoesNotCreateAChange() {
        val window = L2NameWindow()
        val baseline = linkedMapOf(firstId to "Boots", secondId to "Charm")
        window.reset(mapOf("movement" to baseline))
        window.offer(mapOf("movement" to linkedMapOf(secondId to "Charm", firstId to "Boots")), 0L)

        assertNull(window.due(2_000_000_000L))
    }

    @Test
    fun copiesInputAndReturnedSnapshots() {
        val names = linkedMapOf(firstId to "Boots")
        val updates = linkedMapOf<String, Map<UUID, String>>("movement" to names)
        val window = L2NameWindow()
        window.offer(updates, 0L)
        names[firstId] = "Mutated input"
        updates.clear()

        val result = assertNotNull(window.due(1_000_000_000L))
        assertEquals("Boots", result.getValue("movement").getValue(firstId))
        assertTrue(runCatching {
            @Suppress("UNCHECKED_CAST")
            (result as MutableMap<String, Map<UUID, String>>).clear()
        }.isFailure)
        assertNull(window.due(2_000_000_000L))
    }

    @Test
    fun resetCopiesBaselineInput() {
        val names = linkedMapOf(firstId to "Boots")
        val baseline = linkedMapOf<String, Map<UUID, String>>("movement" to names)
        val window = L2NameWindow()
        window.reset(baseline)
        names[firstId] = "Mutated baseline"
        baseline.clear()
        window.offer(mapOf("movement" to mapOf(firstId to "Boots")), 0L)

        assertNull(window.due(2_000_000_000L))
    }

    @Test
    fun resetAndClearDiscardPendingState() {
        val window = L2NameWindow()
        window.offer(mapOf("movement" to mapOf(firstId to "Boots")), 0L)
        window.reset(mapOf("movement" to mapOf(firstId to "Baseline")))
        assertNull(window.due(1_000_000_000L))

        window.offer(mapOf("movement" to mapOf(firstId to "New Boots")), 2_000_000_000L)
        window.clear()
        assertNull(window.due(4_000_000_000L))
        window.offer(mapOf("movement" to mapOf(firstId to "After clear")), 5_000_000_000L)
        assertEquals(
            mapOf("movement" to mapOf(firstId to "After clear")),
            window.due(6_000_000_000L)
        )
        assertNull(window.due(7_000_000_000L))
    }

    @Test
    fun deadlineComparisonHandlesLongWraparound() {
        val window = L2NameWindow()
        val start = Long.MAX_VALUE - 500_000_000L
        window.offer(mapOf("movement" to mapOf(firstId to "Boots")), start)

        assertNull(window.due(Long.MIN_VALUE + 499_999_998L))
        assertEquals(
            mapOf("movement" to mapOf(firstId to "Boots")),
            window.due(Long.MIN_VALUE + 499_999_999L + 1L)
        )
    }
}
