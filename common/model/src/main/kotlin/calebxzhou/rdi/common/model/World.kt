package calebxzhou.rdi.common.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

@Serializable
data class World(
    @Contextual val _id: ObjectId = ObjectId(),
    val name: String,
    //所属玩家
    @Contextual
    val ownerId: ObjectId,
    //如果mount不同的modpack  警告用户可能坏档
    @Contextual
    val modpackId: ObjectId,
    val size: Long = 0,
) {
    @Serializable
    enum class Scale(val level: Int) {   // 缩放等级 0~7
        L0(0),
        L1(1),
        L2(2),
        L3(3),
        L4(4),
        L5(5),
        L6(6),
        L7(7),
        ;

        /** 1像素代表多少个方块 (matches the red top row in your table) */
        val blocksByPixel: Int = 1 shl level          // 1, 2, 4, 8, 16, 32, 64, 128

        /** 地图覆盖范围边长（多少个方块）— matches the red bottom row */
        val mapSizeInBlocks: Int = 128 shl level      // 128 256 512 1024 2048 4096 8192 16384

        /** 地图覆盖多少个区块（16×16方块） */
        val mapSizeInChunks: Int = 8 shl level        // 8 16 32 64 128 256 512 1024

        /** 地图永远是 128×128 像素（Minecraft 地图物品固定大小） */
        val pixelSize: Int = 128

        // 实用帮助函数
        fun blocksToPixels(blocks: Int): Int = blocks / blocksByPixel
        fun pixelsToBlocks(pixels: Int): Int = pixels * blocksByPixel

        companion object {
            fun fromLevel(level: Int): Scale =
                entries.firstOrNull { it.level == level }
                    ?: throw IllegalArgumentException("Scale level must be 0~7, got $level")

            fun fromBlocksByPixel(bpp: Int): Scale =
                entries.firstOrNull { it.blocksByPixel == bpp }
                    ?: throw IllegalArgumentException("Invalid blocksByPixel: $bpp")
        }
    }

    @Serializable
    data class Vo(
        @Contextual val id: ObjectId = ObjectId(),
        val name: String,
        //所属玩家
        @Contextual
        val ownerId: ObjectId,
        //bytes max 2GB
        val size: Long,
        //如果mount不同的modpack  警告用户可能坏档
        @Contextual
        val modpackId: ObjectId,
        val modpackName: String,
        val modpackIconUrl: String?,
    )
    @Serializable
    data class SurfaceMapDto(
        val map: List<List<String>>   // 128 rows × 128 columns, each value is block id like minecraft:grass_block
    ) {
        init {
            require(map.size == 128) { "SurfaceMap must have exactly 128 rows" }
            require(map.all { it.size == 128 }) { "Every row must have exactly 128 columns" }
        }
    }

    @Serializable
    data class SurfaceChunkDto(
        val chunkX: Int,
        val chunkZ: Int,
        val side: Int,
        val data: IntArray
    )

    @Serializable
    data class SurfaceChunkCoordDto(
        val chunkX: Int,
        val chunkZ: Int
    )

    @Serializable
    data class SurfaceRegionDto(
        val regionX: Int,
        val regionZ: Int,
        val side: Int,
        val data: IntArray
    )

    @Serializable
    data class SurfaceRegionCoordDto(
        val regionX: Int,
        val regionZ: Int
    )

    @Serializable
    data class SurfaceQueryDto(
        val scale: Int,
        val palette: List<String>,
        val chunks: List<SurfaceChunkDto>,
        val missing: List<SurfaceChunkCoordDto> = emptyList(),
        val regions: List<SurfaceRegionDto> = emptyList(),
        val missingRegions: List<SurfaceRegionCoordDto> = emptyList()
    )
}

class BlockColors {
    companion object {
        const val DEFAULT_MAP_COLOR = "#00000000"

        private fun isAirBlock(name: String): Boolean {
            val id = name.removePrefix("minecraft:")
            return id == "air" || id == "cave_air" || id == "void_air" || id == "barrier" || id == "structure_void"
        }

        fun toColorHex(blockName: String, heightDelta: Int = Int.MIN_VALUE): String {
            if (isAirBlock(blockName)) return DEFAULT_MAP_COLOR
            val id = blockName.removePrefix("minecraft:").lowercase()

            val base = when {
                id.contains("water") || id.contains("kelp") || id.contains("seagrass") -> intArrayOf(63, 118, 228)
                id.contains("ice") || id.contains("snow") -> intArrayOf(226, 235, 242)
                id.contains("grass") || id.contains("leaf") || id.contains("moss") || id.contains("vine")
                        || id.contains("azalea") || id.contains("bamboo") || id.contains("cactus")
                        || id.contains("crop") || id.contains("sapling") -> intArrayOf(95, 159, 53)

                id.contains("sand") || id.contains("end_stone") || id.contains("sponge")
                        || id.contains("dripstone") || id.contains("bone") -> intArrayOf(218, 210, 158)

                id.contains("dirt") || id.contains("mud") || id.contains("podzol")
                        || id.contains("farmland") || id.contains("rooted") -> intArrayOf(134, 96, 67)

                id.contains("log") || id.contains("wood") || id.contains("plank")
                        || id.contains("chest") || id.contains("barrel") || id.contains("crafting_table")
                        || id.contains("ladder") -> intArrayOf(154, 122, 74)

                id.contains("lava") || id.contains("magma") || id.contains("fire")
                        || id.contains("netherrack") || id.contains("nether_brick")
                        || id.contains("crimson") -> intArrayOf(180, 68, 58)

                id.contains("deepslate") || id.contains("blackstone") || id.contains("basalt")
                        || id.contains("obsidian") -> intArrayOf(65, 69, 76)

                id.contains("stone") || id.contains("andesite") || id.contains("diorite")
                        || id.contains("granite") || id.contains("ore") || id.contains("cobblestone")
                        || id.contains("tuff") || id.contains("brick") || id.contains("terracotta")
                        || id.contains("concrete") -> intArrayOf(126, 126, 126)

                id.contains("clay") || id.contains("quartz") || id.contains("calcite")
                        || id.contains("wool") || id.contains("glass") -> intArrayOf(208, 208, 208)

                id.contains("copper") -> intArrayOf(191, 123, 84)
                id.contains("amethyst") || id.contains("purpur") -> intArrayOf(153, 118, 169)
                id.contains("gold") -> intArrayOf(249, 219, 96)
                else -> intArrayOf(140, 140, 140)
            }

            val shadeFactor = when {
                heightDelta == Int.MIN_VALUE -> 1.0
                heightDelta > 1 -> 1.14
                heightDelta > 0 -> 1.08
                heightDelta < -1 -> 0.82
                heightDelta < 0 -> 0.92
                else -> 1.0
            }

            val r = (base[0] * shadeFactor).toInt().coerceIn(0, 255)
            val g = (base[1] * shadeFactor).toInt().coerceIn(0, 255)
            val b = (base[2] * shadeFactor).toInt().coerceIn(0, 255)
            return "#%02X%02X%02X".format(r, g, b)
        }
    }
}
@Serializable
data class RegionSurfaceMap(
    @Contextual val _id: ObjectId,
    @Contextual val worldId: ObjectId,
    val dimension: String,
    val regionX: Int,
    val regionZ: Int,
    //last modify time
    val chunkTimes: IntArray,
    val chunks: List<ChunkSurfaceMap>
)

@Serializable
data class ChunkSurfaceMap(
    val chunkX: Int,
    val chunkZ: Int,
    val data: PalettedContainer.Snapshot<String>
)
