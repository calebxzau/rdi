package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtAccounterException
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import java.io.IOException
import java.nio.file.Path

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
}
