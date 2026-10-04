package calebxzau.rdi.mc.client.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.network.NetworkEvent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientChunkEmptyNbtTest {
    companion object {
        init {
            SharedConstants.tryDetectVersion()
            // Plain JUnit has no ModLauncher-generated event constructors. As in
            // RClientBatchingTest, prime listener lists through real event instances.
            NetworkEvent { error("The cache test must not dispatch network events") }.listenerList
            NetworkEvent.GatherLoginPayloadsEvent(arrayListOf(), false).listenerList
            Bootstrap.bootStrap()
            for (block in BuiltInRegistries.BLOCK) for (state in block.stateDefinition.possibleStates) {
                if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) Block.BLOCK_STATE_REGISTRY.add(state)
            }
        }
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun emptyPacketNbtSurvivesCaptureAndDoesNotStopCacheWriter(): Unit {
        val position = BlockPos(1, 0, 2)
        val entity = ChestBlockEntity(position, Blocks.CHEST.defaultBlockState())
        val original = ClientboundBlockEntityDataPacket.create(entity) { CompoundTag() }
        assertNull(original.tag, "Minecraft 1.20.1 normalizes an empty update tag to null")
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        val packet = try {
            original.write(buffer)
            ClientboundBlockEntityDataPacket(buffer)
        } finally {
            buffer.release()
        }
        val handoff = ClientChunkDeltaPacketHandoff(1, 4096)
        val updates = try {
            assertTrue(handoff.capture(packet, 4096) > 0)
            val captured = assertNotNull(handoff.consume(packet)).updates().single()
            assertNull(assertIs<ClientChunkCacheWrite.BlockEntityChange>(captured.updates().single()).tag())
            captured.updates()
        } finally {
            handoff.close()
        }

        val key = ClientChunkRegionSink.Key(ResourceLocation("minecraft", "overworld"), 0, 0)
        val base = ClientChunkSnapshot.Snapshot(key.dimension(), 0, 0, 0, 1, 1, 1,
            byteArrayOf(1, 2, 3), CompoundTag(), emptyList())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = ClientChunkDeltaWriter(directory, failures::add, 8, 4096)
        try {
            assertTrue(writer.submit(ClientChunkCacheWrite(key, 1, 1, base, emptyList(), 128)))
            assertTrue(writer.submit(ClientChunkCacheWrite(key, 2, 2, null, updates, 128)))
            assertNotNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
            assertTrue(writer.stats().accepting)
            assertTrue(writer.submit(ClientChunkCacheWrite(key, 3, 3, null, listOf(
                ClientChunkCacheWrite.BlockChange(position.asLong(), Block.getId(Blocks.STONE.defaultBlockState()), false),
            ), 128)))
            val terrain = assertNotNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), terrain.blockStates.getValue(position.asLong()).stateId)
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertTrue(failures.isEmpty(), failures.toString())
        assertEquals(3L, writer.stats().processed)
        ClientChunkDeltaStore(directory).use { reopened ->
            val terrain = assertNotNull(reopened.readTerrain(key))
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), terrain.blockStates.getValue(position.asLong()).stateId)
        }
    }
}
