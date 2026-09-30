package calebxzau.rdi.mc.client.l2

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class L2AttributeNameCacheTest {
    @Test
    fun replacesOnlyMentionedAttributesAndRetainsMissingModifiers() {
        val cache = L2AttributeNameCache()
        val modifierId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val otherModifierId = UUID.fromString("00000000-0000-0000-0000-000000000002")

        cache.replaceMentioned(
            mapOf(
                "minecraft:generic.movement_speed" to mapOf(modifierId to "Boots"),
                "minecraft:generic.attack_damage" to mapOf(otherModifierId to "Accessory"),
            )
        )
        cache.replaceMentioned(
            mapOf("minecraft:generic.movement_speed" to mapOf(modifierId to "Potion"))
        )

        assertEquals(
            mapOf(modifierId to "Potion"),
            cache.namesFor("minecraft:generic.movement_speed")
        )
        assertEquals(
            mapOf(otherModifierId to "Accessory"),
            cache.namesFor("minecraft:generic.attack_damage")
        )
        assertNull(cache.namesFor("minecraft:generic.max_health"))
    }

    @Test
    fun explicitEmptyListClearsAttributeAndClearResetsEverything() {
        val cache = L2AttributeNameCache()
        val modifierId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        cache.replaceMentioned(mapOf("minecraft:generic.movement_speed" to mapOf(modifierId to "Boots")))

        cache.replaceMentioned(mapOf("minecraft:generic.movement_speed" to emptyMap()))
        assertEquals(emptyMap(), cache.namesFor("minecraft:generic.movement_speed"))

        cache.replaceMentioned(mapOf("minecraft:generic.movement_speed" to mapOf(modifierId to "Boots")))
        cache.clear()
        assertNull(cache.namesFor("minecraft:generic.movement_speed"))
    }
}
