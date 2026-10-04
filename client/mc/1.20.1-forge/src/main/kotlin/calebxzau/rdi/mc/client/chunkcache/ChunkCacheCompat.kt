package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.nbt.NbtAccounter
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import java.io.IOException
import java.nio.file.Path

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
}
