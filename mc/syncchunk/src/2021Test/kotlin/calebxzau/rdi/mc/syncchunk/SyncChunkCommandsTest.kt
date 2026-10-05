package calebxzau.rdi.mc.syncchunk

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncChunkCommandsTest {
    private val dispatcher = CommandDispatcher<CommandSourceStack>().apply {
        SyncChunkCommands.register(this, { Result.failure(IllegalStateException("unused")) })
    }

    @Test
    fun registersOnlyTheSyncchunkRoot() {
        assertEquals(listOf("syncchunk"), dispatcher.root.children.map { it.name })
        assertEquals(setOf("add", "del", "list"), dispatcher.root.getChild("syncchunk").children.map { it.name }.toSet())
    }

    @Test
    fun parsesNegativeChunkCoordinatesInAnUnloadedDimension() {
        val parsed = dispatcher.parse("syncchunk del removed_mod:old_dimension -12 0", null)

        assertTrue(parsed.exceptions.isEmpty())
        assertNotNull(parsed.context.command)
        assertEquals(listOf("syncchunk", "del", "dimension", "chunkX", "chunkZ"), parsed.context.nodes.map { it.node.name })
    }

    @Test
    fun rejectsInvalidDimensionIncompleteCoordinatesAndPagesBelowOne() {
        assertTrue(dispatcher.parse("syncchunk del example:invalid# 1 2", null).exceptions.isNotEmpty())
        assertNull(dispatcher.parse("syncchunk del example:dimension 1", null).context.command)
        assertTrue(dispatcher.parse("syncchunk list 0", null).exceptions.isNotEmpty())
        assertNotNull(dispatcher.parse("syncchunk list 2", null).context.command)
    }
}
