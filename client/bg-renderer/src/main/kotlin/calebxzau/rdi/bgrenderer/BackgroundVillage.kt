package calebxzau.rdi.bgrenderer

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** One small house: footprint in block columns, walls above a one-block cobblestone foundation. */
internal data class VillageHouse(
    val minX: Int,
    val minZ: Int,
    val width: Int,
    val depth: Int,
    val wallHeight: Int
) {
    val maxX: Int get() = minX + width
    val maxZ: Int get() = minZ + depth
}

/**
 * A hamlet on the near lake shore, right of the view center. Fronts (doors and lit windows)
 * face +Z, toward the camera.
 */
internal val VILLAGE_HOUSES = listOf(
    VillageHouse(38, -76, 5, 5, 3),
    VillageHouse(47, -67, 7, 5, 3),
    VillageHouse(57, -79, 5, 6, 3),
    VillageHouse(65, -69, 6, 5, 4)
)

/** Columns within this distance of a house footprint (plus one) blend from the natural terrain to its pad. */
internal const val VILLAGE_PAD_BLEND = 4.0

internal data class HouseBlock(val material: BlockMaterial, val box: StaticAabb)

/** Each pad sits at the natural terrain height of its house center, rounded to a block. */
internal val VILLAGE_PAD_HEIGHTS: List<Int> by lazy {
    VILLAGE_HOUSES.map { house ->
        floor(baseTerrainHeight((house.minX + house.maxX) / 2.0, (house.minZ + house.maxZ) / 2.0) + 0.5).toInt()
    }
}

/**
 * Flattens the ground under each house (footprint plus a one-block apron) and blends it into the
 * hillside. Each column follows only its nearest house, so neighboring pads never pull on each other.
 */
internal fun flattenVillagePads(height: Double, x: Double, z: Double): Double {
    var nearest = -1
    var nearestDistance = VILLAGE_PAD_BLEND
    VILLAGE_HOUSES.forEachIndexed { index, house ->
        val dx = max(max(house.minX - 1 - x, x - (house.maxX + 1)), 0.0)
        val dz = max(max(house.minZ - 1 - z, z - (house.maxZ + 1)), 0.0)
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < nearestDistance) {
            nearest = index
            nearestDistance = distance
        }
    }
    if (nearest < 0) return height
    val t = (nearestDistance / VILLAGE_PAD_BLEND).coerceIn(0.0, 1.0)
    val weight = 1.0 - t * t * (3.0 - 2.0 * t)
    return height + (VILLAGE_PAD_HEIGHTS[nearest] - height) * weight
}

/** A meadow in front of the hamlet keeps the lit house fronts visible from the summit. */
internal fun inVillageMeadow(x: Int, z: Int): Boolean = x in 32 until 80 && z in -62 until -38

/** Whether a column lies within [margin] blocks of any house footprint. */
internal fun nearVillage(x: Int, z: Int, margin: Int): Boolean = VILLAGE_HOUSES.any { house ->
    x >= house.minX - margin && x < house.maxX + margin && z >= house.minZ - margin && z < house.maxZ + margin
}

/**
 * Boxes of one house. Corner logs come first so the coplanar wall faces are cut around them,
 * and windows/door protrude slightly so they replace the wall surface behind them.
 */
internal fun houseBlocks(house: VillageHouse, padHeight: Int): List<HouseBlock> = buildList {
    val floorY = padHeight.toFloat()
    val wallBottom = floorY + 1f
    val wallTop = wallBottom + house.wallHeight
    val x0 = house.minX.toFloat()
    val x1 = house.maxX.toFloat()
    val z0 = house.minZ.toFloat()
    val z1 = house.maxZ.toFloat()
    val centerZ = floor((z0 + z1) / 2f)
    listOf(x0, x1 - 1f).forEach { x ->
        listOf(z0, z1 - 1f).forEach { z -> add(HouseBlock(BlockMaterial.OAK_LOG, StaticAabb(x, wallBottom, z, x + 1f, wallTop, z + 1f))) }
    }
    // Front (+Z): door and a lit window; sides and back: one lit window each.
    add(HouseBlock(BlockMaterial.SPRUCE_PLANKS, StaticAabb(x0 + 1f, wallBottom, z1 - 0.5f, x0 + 2f, wallBottom + 2f, z1 + WINDOW_OUTSET)))
    add(HouseBlock(BlockMaterial.GLOWSTONE, StaticAabb(x1 - 2f, wallBottom + 1f, z1 - 0.5f, x1 - 1f, wallBottom + 2f, z1 + WINDOW_OUTSET)))
    add(HouseBlock(BlockMaterial.GLOWSTONE, StaticAabb(x1 - 0.5f, wallBottom + 1f, centerZ, x1 + WINDOW_OUTSET, wallBottom + 2f, centerZ + 1f)))
    add(HouseBlock(BlockMaterial.GLOWSTONE, StaticAabb(x0 - WINDOW_OUTSET, wallBottom + 1f, centerZ, x0 + 0.5f, wallBottom + 2f, centerZ + 1f)))
    add(HouseBlock(BlockMaterial.GLOWSTONE, StaticAabb(x0 + 2f, wallBottom + 1f, z0 - WINDOW_OUTSET, x0 + 3f, wallBottom + 2f, z0 + 0.5f)))
    add(HouseBlock(BlockMaterial.OAK_PLANKS, StaticAabb(x0, wallBottom, z0, x1, wallTop, z1)))
    add(HouseBlock(BlockMaterial.COBBLESTONE, StaticAabb(x0, floorY, z0, x1, wallBottom, z1)))
    // Gable roof along x: each layer steps in one block from both z sides.
    var layer = 0
    while (true) {
        val roofZ0 = z0 - 1f + layer
        val roofZ1 = z1 + 1f - layer
        if (roofZ1 - roofZ0 < 1f) break
        add(HouseBlock(BlockMaterial.SPRUCE_PLANKS, StaticAabb(x0 - 1f, wallTop + layer, roofZ0, x1 + 1f, wallTop + layer + 1f, roofZ1)))
        layer++
    }
    add(HouseBlock(BlockMaterial.LANTERN, StaticAabb(x0 + 2.15f, wallBottom + 1.6f, z1 + 0.1f, x0 + 2.55f, wallBottom + 2.0f, z1 + 0.5f)))
}

private const val WINDOW_OUTSET = 0.06f

/** Appends every house's exposed faces as static geometry with box-corner AO. */
internal fun buildVillageMesh(builder: SceneMeshBuilder): Int {
    var quads = 0
    VILLAGE_HOUSES.forEachIndexed { index, house ->
        val blocks = houseBlocks(house, VILLAGE_PAD_HEIGHTS[index])
        val boxes = blocks.map { it.box }
        blocks.forEachIndexed { blockIndex, block ->
            exposedStaticFaces(blockIndex, boxes).forEach { quad ->
                val ao = FloatArray(4) { corner ->
                    cornerAmbientOcclusion(
                        block.box,
                        quad.points[corner * 3], quad.points[corner * 3 + 1], quad.points[corner * 3 + 2],
                        quad.direction.normalX, quad.direction.normalY, quad.direction.normalZ,
                        boxes
                    )
                }
                builder.quad(block.material, quad.points, quad.direction.normalX, quad.direction.normalY, quad.direction.normalZ, 0, ao)
                quads++
            }
        }
    }
    return quads
}
