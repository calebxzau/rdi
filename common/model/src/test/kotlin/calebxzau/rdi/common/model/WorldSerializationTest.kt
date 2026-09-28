package calebxzau.rdi.common.model

import calebxzhou.rdi.common.model.World
import calebxzhou.rdi.common.serdesJson
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorldSerializationTest {
    @Test
    fun legacySectionMetadataDoesNotPreventReadingWorld(): Unit {
        val legacy = """
            {
                "_id": "00112233445566778899aabb",
                "name": "Existing world",
                "ownerId": "aabbccddeeff001122334455",
                "modpackId": "11223344556677889900aabb",
                "size": 42,
                "sections": [{"dimension": "minecraft:overworld", "chunkPos": 12, "sectionY": 4}]
            }
        """.trimIndent()

        val world = serdesJson.decodeFromString<World>(legacy)

        assertEquals(
            World(
                _id = ObjectId("00112233445566778899aabb"),
                name = "Existing world",
                ownerId = ObjectId("aabbccddeeff001122334455"),
                modpackId = ObjectId("11223344556677889900aabb"),
                size = 42,
            ),
            world,
        )
        assertFalse(serdesJson.encodeToString(world).contains("\"sections\""))
    }
}
