package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkReuseCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheChannel
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import calebxzau.rdi.mc.client.chunkcache.mixin.AChunkCacheServerChunkRadius
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.nbt.NbtAccounter
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import java.io.IOException
import java.nio.file.Path

/** A chunk cache message the client sends; each version has its own payload type. */
internal typealias ChunkCacheServerboundPayload = ChunkCachePayload

/** Minecraft 1.20.1 APIs used by the shared client chunk cache in `mc/chunkcache/src/client`. */
object ChunkCacheCompat {
    // 1.20.1 reports exceeded NBT size and depth limits with plain RuntimeExceptions carrying these messages.
    private val limitMessages = listOf("Tried to read NBT tag that was too big", "Tried to read NBT tag with too high complexity")

    @JvmStatic
    @Throws(IOException::class)
    fun openRegion(@Suppress("UNUSED_PARAMETER") name: String, @Suppress("UNUSED_PARAMETER") dimension: ResourceKey<Level>,
                   path: Path, folder: Path): RegionFile =
        RegionFile(path, folder, ClientRegionZstd.version(), false)

    /** 1.20.1 has a fixed NBT depth limit of 512, so a lower [maxDepth] is not enforced; the byte quota is. */
    @JvmStatic
    fun nbtAccounter(maxBytes: Long, @Suppress("UNUSED_PARAMETER") maxDepth: Int): NbtAccounter = NbtAccounter(maxBytes)

    @JvmStatic
    fun isNbtLimitFailure(failure: Throwable): Boolean =
        failure.javaClass == RuntimeException::class.java && limitMessages.any { failure.message?.startsWith(it) == true }

    /** The Forge channel sends on the current connection; callers check that it is still [listener]'s. */
    fun sendToServer(@Suppress("UNUSED_PARAMETER") listener: ClientPacketListener, payload: ChunkCacheServerboundPayload) =
        ChunkCacheChannel.sendToServer(payload)

    fun canReportPause(listener: ClientPacketListener): Boolean = ChunkCacheChannel.isStatusRemotePresent(listener.connection)

    fun restoreReuse(metadata: ByteArray, sections: ByteArray, @Suppress("UNUSED_PARAMETER") level: ClientLevel): ClientboundLevelChunkWithLightPacket =
        ChunkReuseCodec.restore(metadata, sections)

    /** Not the render distance: a 1.20.1 server sends its whole view distance to every client. */
    fun trackedChunkRadius(@Suppress("UNUSED_PARAMETER") minecraft: Minecraft, listener: ClientPacketListener): Int =
        (listener as AChunkCacheServerChunkRadius).`rdi$serverChunkRadius`()

    fun disconnect(listener: ClientPacketListener, reason: Component) = listener.connection.disconnect(reason)
}
