package calebxzau.rdi.mc.syncchunk.server

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncChunkServerCommandsTest {
    @Test
    fun registersOnlyNewRootAndParsesExplicitSignedChunkCoordinates(): Unit {
        val dispatcher = dispatcher()

        assertTrue(dispatcher.root.getChild("syncchunk") != null)
        assertFalse(dispatcher.root.getChild("rdi")?.getChild("chunk") != null)

        val parsed = dispatcher.parse("syncchunk del example:removed_dimension -12 0", null)
        assertTrue(parsed.exceptions.isEmpty())
        assertTrue(parsed.context.command != null)
        assertEquals(listOf("syncchunk", "del", "dimension", "chunkX", "chunkZ"), parsed.context.nodes.map { it.node.name })
    }

    @Test
    fun rejectsInvalidDimensionAndIncompleteExplicitCoordinates(): Unit {
        val dispatcher = dispatcher()

        assertTrue(dispatcher.parse("syncchunk del example:invalid# 1 2", null).exceptions.isNotEmpty())
        val incomplete = dispatcher.parse("syncchunk del example:dimension 1", null)
        assertEquals(null, incomplete.context.command)
        assertEquals(listOf("syncchunk", "del", "dimension", "chunkX"), incomplete.context.nodes.map { it.node.name })
    }

    private fun dispatcher(): CommandDispatcher<CommandSourceStack> =
        CommandDispatcher<CommandSourceStack>().apply { SyncChunkServerCommands.register(this) }
}
