package calebxzau.rdi.mc.server.chunkcache

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkCacheSentChunksTest {
    private val overworld = ResourceLocation("minecraft", "overworld")
    private val nether = ResourceLocation("minecraft", "the_nether")
    private val player = UUID(1, 1)

    @Test
    fun `second send of the same chunk is a revisit`() {
        val sent = ChunkCacheSentChunks()

        assertFalse(sent.markSent(player, overworld, ChunkPos.asLong(3, -4)))
        assertTrue(sent.markSent(player, overworld, ChunkPos.asLong(3, -4)))
    }

    @Test
    fun `dimensions and players are tracked separately`() {
        val sent = ChunkCacheSentChunks()
        val position = ChunkPos.asLong(0, 0)
        sent.markSent(player, overworld, position)

        assertFalse(sent.markSent(player, nether, position))
        assertFalse(sent.markSent(UUID(2, 2), overworld, position))
    }

    @Test
    fun `limit stops remembering new chunks but keeps known ones`() {
        val sent = ChunkCacheSentChunks(maxPerPlayer = 2)
        sent.markSent(player, overworld, ChunkPos.asLong(0, 0))
        sent.markSent(player, nether, ChunkPos.asLong(0, 0))

        assertFalse(sent.markSent(player, overworld, ChunkPos.asLong(1, 0)))
        assertFalse(sent.markSent(player, overworld, ChunkPos.asLong(1, 0)))
        assertTrue(sent.markSent(player, nether, ChunkPos.asLong(0, 0)))
    }

    @Test
    fun `clear forgets every player`() {
        val sent = ChunkCacheSentChunks()
        sent.markSent(player, overworld, ChunkPos.asLong(5, 5))

        sent.clear()

        assertFalse(sent.markSent(player, overworld, ChunkPos.asLong(5, 5)))
    }
}
