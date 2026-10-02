package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.world.level.ChunkPos
import java.util.Arrays

/** Main-thread cursor: completions continue a pass; a game tick starts the next pass. */
internal class ChunkCacheCandidateScan {
    private var positions = LongArray(0)
    private var cursor = 0

    fun update(centerX: Int, centerZ: Int, radius: Int) {
        require(radius in 0..34)
        val width = radius * 2 + 1
        // The low bits retain the original Z/X order for equal distances, without boxed sorting.
        positions = LongArray(width * width) { index ->
            val dx = index % width - radius
            val dz = index / width - radius
            ((dx * dx + dz * dz).toLong() shl 32) or index.toLong()
        }
        Arrays.sort(positions)
        for (index in positions.indices) {
            val offset = positions[index].toInt()
            positions[index] = ChunkPos.asLong(centerX + offset % width - radius, centerZ + offset / width - radius)
        }
        restart()
    }

    fun restart() {
        cursor = 0
    }

    fun hasNext(): Boolean = cursor < positions.size

    fun next(): Long {
        check(hasNext()) { "Chunk candidate scan is exhausted" }
        return positions[cursor++]
    }
}
