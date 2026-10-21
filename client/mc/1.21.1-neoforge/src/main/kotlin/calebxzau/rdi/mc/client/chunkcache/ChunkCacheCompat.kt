package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePausedPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtAccounterException
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import java.io.IOException
import java.nio.file.Path

/** A chunk cache message the client sends; each version has its own payload type. */
internal typealias ChunkCacheServerboundPayload = CustomPacketPayload

/** Minecraft 1.21.1 APIs used by the shared client chunk cache in `mc/chunkcache/src/client`. */
object ChunkCacheCompat {
    @JvmStatic
    @Throws(IOException::class)
    fun openRegion(name: String, dimension: ResourceKey<Level>, path: Path, folder: Path): RegionFile =
        RegionFile(RegionStorageInfo(name, dimension, "chunk"), path, folder, ClientRegionZstd.version(), false)

    @JvmStatic
    fun nbtAccounter(maxBytes: Long, maxDepth: Int): NbtAccounter = NbtAccounter(maxBytes, maxDepth)

    @JvmStatic
    fun isNbtLimitFailure(failure: Throwable): Boolean = failure is NbtAccounterException

    fun sendToServer(listener: ClientPacketListener, payload: ChunkCacheServerboundPayload) =
        listener.send(ServerboundCustomPayloadPacket(payload))

    fun canReportPause(listener: ClientPacketListener): Boolean = listener.hasChannel(ChunkCachePausedPayload.TYPE)

    fun restoreReuse(metadata: ByteArray, sections: ByteArray, level: ClientLevel): ClientboundLevelChunkWithLightPacket =
        ChunkReuseCodec.restore(metadata, sections, level.registryAccess())

    /** The 1.21 server tracks the client's effective render distance, so this equals its view distance. */
    fun trackedChunkRadius(minecraft: Minecraft, @Suppress("UNUSED_PARAMETER") listener: ClientPacketListener): Int =
        minecraft.options.effectiveRenderDistance

    fun disconnect(listener: ClientPacketListener, reason: Component) = listener.disconnect(reason)
}
