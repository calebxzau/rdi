package calebxzau.rdi.bgrenderer

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

internal const val TREE_SEED = 0x7EE5
/** One candidate tree per jittered grid cell of this many blocks. */
internal const val TREE_SPACING = 5
/** Trees closer than this (horizontally, from the camera) get full Minecraft shapes; farther ones are simplified. */
internal const val TREE_DETAIL_DISTANCE = 150f
/** In the range beyond the lake, no trees above this height; its rock band and snow stay bare. */
internal const val TREE_LINE = 76
/** The summit and its upper flanks stay clear so canopies never crowd the player. */
internal const val SUMMIT_CLEARING_RADIUS = 15f
/** A meadow strip around the lake (in [lakeDistance] units) stays treeless so the water shows over the forest. */
internal const val LAKE_SHORE_CLEARANCE = 1.3
/** Fraction of near (detailed) trees dropped, so canopies stand apart instead of forming one carpet. */
internal const val NEAR_TREE_THINNING = 0.4
/** Trees farther than this from the camera become single boxes (and half of them are skipped). */
internal const val TREE_DISTANT_DISTANCE = 420f
/** Vertex kind of swaying grass-tuft tops (kind 0 is static, kind 1 water). */
internal const val FOLIAGE_SWAY_KIND = 2

internal enum class TreeSpecies(val log: BlockMaterial, val leaves: BlockMaterial) {
    Oak(BlockMaterial.OAK_LOG, BlockMaterial.OAK_LEAVES),
    Birch(BlockMaterial.BIRCH_LOG, BlockMaterial.BIRCH_LEAVES),
    Spruce(BlockMaterial.SPRUCE_LOG, BlockMaterial.SPRUCE_LEAVES)
}

/** A tree rooted on top of column (x, z). */
internal data class TreeInstance(
    val x: Int,
    val z: Int,
    val baseY: Int,
    val species: TreeSpecies,
    val trunkHeight: Int,
    val detailed: Boolean,
    val tint: Float,
    /** Far beyond the detail distance: one canopy box without a trunk. */
    val distant: Boolean = false
)

private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/**
 * Deterministic forest: one jittered candidate per [TREE_SPACING] cell inside the view wedge,
 * thinned by a low-frequency noise into woods and clearings. Oaks and birches fill the valley and
 * all near high ground, spruces take over on the ridges and mountain slopes beyond the lake. The
 * area spans the whole wide view up to where the atmosphere dissolves the terrain.
 */
internal fun placeTrees(grid: TerrainGrid): List<TreeInstance> = buildList {
    val camera = BackgroundScene.camera(0.0)
    val minCellX = Math.floorDiv(-900, TREE_SPACING)
    val maxCellX = Math.floorDiv(900, TREE_SPACING)
    val minCellZ = Math.floorDiv(-760, TREE_SPACING)
    val maxCellZ = Math.floorDiv(20, TREE_SPACING)
    for (cellZ in minCellZ..maxCellZ) {
        for (cellX in minCellX..maxCellX) {
            val jitterX = TerrainNoise.hash01(cellX, cellZ, TREE_SEED)
            val jitterZ = TerrainNoise.hash01(cellX, cellZ, TREE_SEED + 1)
            val keep = TerrainNoise.hash01(cellX, cellZ, TREE_SEED + 2)
            val variant = TerrainNoise.hash01(cellX, cellZ, TREE_SEED + 3)
            val x = cellX * TREE_SPACING + floor(jitterX * (TREE_SPACING - 1)).toInt()
            val z = cellZ * TREE_SPACING + floor(jitterZ * (TREE_SPACING - 1)).toInt()
            if (!terrainCellVisible(x, z, 1)) continue
            val height = grid.heightAt(x, z)
            val highland = z < HIGH_RANGE_START_Z
            if (height.toFloat() < WATER_LEVEL + 1f || (highland && height > TREE_LINE)) continue
            val fromPlayerX = x + 0.5f - PLAYER_X
            val fromPlayerZ = z + 0.5f - PLAYER_Z
            if (fromPlayerX * fromPlayerX + fromPlayerZ * fromPlayerZ < SUMMIT_CLEARING_RADIUS * SUMMIT_CLEARING_RADIUS) continue
            if (nearVillage(x, z, 4) || inVillageMeadow(x, z)) continue
            if (lakeDistance(x + 0.5, z + 0.5) < LAKE_SHORE_CLEARANCE) continue
            val steepness = maxOf(
                abs(height - grid.heightAt(x - 2, z)), abs(height - grid.heightAt(x + 2, z)),
                abs(height - grid.heightAt(x, z - 2)), abs(height - grid.heightAt(x, z + 2))
            )
            if (steepness > 4) continue
            // Woods and clearings, except the left woods, which stay one continuous forest.
            val forest = TerrainNoise.fbm(x * 0.011, z * 0.011, TREE_SEED + 10)
            val woods = 1.0 - smoothstep(0.8, 1.1, leftWoodsDistance(x + 0.5, z + 0.5))
            if (keep >= max(smoothstep(-0.55, 0.05, forest), woods) * 0.95) continue
            // Spruce takes over on the ridges and the foot of the range beyond the lake; the summit,
            // its flanks and the left woods stay leafy like the reference.
            val species = when {
                highland && height >= 34 -> TreeSpecies.Spruce
                highland && height >= 26 -> if (variant < 0.5) TreeSpecies.Spruce else TreeSpecies.Oak
                variant < 0.25 -> TreeSpecies.Birch
                else -> TreeSpecies.Oak
            }
            val trunkHeight = when (species) {
                TreeSpecies.Oak -> 4 + floor(variant * 3).toInt()
                TreeSpecies.Birch -> 5 + floor(variant * 3).toInt()
                TreeSpecies.Spruce -> 6 + floor(variant * 4).toInt()
            }
            val distanceX = x + 0.5f - camera.eyeX
            val distanceZ = z + 0.5f - camera.eyeZ
            val distance = sqrt(distanceX * distanceX + distanceZ * distanceZ)
            val detailed = distance < TREE_DETAIL_DISTANCE
            val distant = distance >= TREE_DISTANT_DISTANCE
            val thinning = TerrainNoise.hash01(cellX, cellZ, TREE_SEED + 4)
            if (detailed && thinning < NEAR_TREE_THINNING) continue
            if (distant && thinning < 0.5) continue
            add(
                TreeInstance(
                    x = x,
                    z = z,
                    baseY = height,
                    species = species,
                    trunkHeight = trunkHeight,
                    detailed = detailed,
                    tint = (0.88 + 0.22 * jitterZ).toFloat(),
                    distant = distant
                )
            )
        }
    }
}

private val TRUNK_SKIP = setOf(StaticFaceDirection.PositiveY, StaticFaceDirection.NegativeY)

/** How far the eye swings sideways, and its lowest height, along the camera track. */
private val EYE_HALF_SPAN_X = kotlin.math.sin(CAMERA_SWING_RADIANS) * CAMERA_ORBIT_RADIUS
private const val LOWEST_EYE_Y = PLAYER_FEET_Y + CAMERA_HEIGHT_ABOVE_PLAYER - CAMERA_HEIGHT_SWING

/**
 * Faces of a far tree box that the camera never sees, nor its water reflection (which shares the
 * eye's x and z): the -Z side (every far tree stands in front of the camera), the x side facing
 * away from the eye, and undersides below the lowest eye height. Far trees are most of the scene's
 * vertices, so this keeps the mesh well inside its budget. The shadow bake culls back faces, and
 * the sun (ahead and to the right) still sees each kept canopy top, so far trees still cast shadows.
 */
internal fun farTreeHiddenFaces(box: StaticAabb): Set<StaticFaceDirection> = buildSet {
    add(StaticFaceDirection.NegativeZ)
    if (box.minX > PLAYER_X + EYE_HALF_SPAN_X) add(StaticFaceDirection.PositiveX)
    if (box.maxX < PLAYER_X - EYE_HALF_SPAN_X) add(StaticFaceDirection.NegativeX)
    if (box.minY <= LOWEST_EYE_Y) add(StaticFaceDirection.NegativeY)
}

/**
 * Minecraft-shaped trees from a few boxes. Leaves are drawn double-sided with alpha cutout, so
 * the far side of a canopy shows through its holes like fancy leaves. Far trees drop the faces
 * the camera never sees ([farTreeHiddenFaces]).
 */
internal fun emitTree(builder: SceneMeshBuilder, tree: TreeInstance) {
    val x = tree.x.toFloat()
    val z = tree.z.toFloat()
    val base = tree.baseY.toFloat()
    val top = base + tree.trunkHeight
    if (tree.distant) {
        // A few pixels wide at this distance: one canopy box, no trunk.
        val radius = if (tree.species == TreeSpecies.Spruce) 1 else 2
        val bottom = if (tree.species == TreeSpecies.Spruce) base + 2f else top - 2f
        val canopy = StaticAabb(x - radius, bottom, z - radius, x + 1f + radius, top + 1f, z + 1f + radius)
        builder.box(tree.species.leaves, canopy, tint = tree.tint, skip = farTreeHiddenFaces(canopy))
        return
    }
    fun box(material: BlockMaterial, box: StaticAabb, tint: Float, skip: Set<StaticFaceDirection> = emptySet()) =
        builder.box(material, box, tint = tint, skip = if (tree.detailed) skip else skip + farTreeHiddenFaces(box))
    box(tree.species.log, StaticAabb(x, base, z, x + 1f, top, z + 1f), 1f, TRUNK_SKIP)
    fun leaves(radius: Int, bottom: Float, height: Float) = box(
        tree.species.leaves,
        StaticAabb(x - radius, bottom, z - radius, x + 1f + radius, bottom + height, z + 1f + radius),
        tree.tint
    )
    // A square layer with its corners trimmed: two overlapping boxes forming a cross (a plus at radius 1).
    fun leafCross(radius: Int, bottom: Float, height: Float) {
        val inner = radius - 1
        box(tree.species.leaves, StaticAabb(x - radius, bottom, z - inner, x + 1f + radius, bottom + height, z + 1f + inner), tree.tint)
        box(tree.species.leaves, StaticAabb(x - inner, bottom, z - radius, x + 1f + inner, bottom + height, z + 1f + radius), tree.tint)
    }
    when (tree.species) {
        // Minecraft's oak canopy with its corners trimmed so crowns read round and stand apart.
        TreeSpecies.Oak, TreeSpecies.Birch -> if (tree.detailed) {
            leafCross(2, top - 2f, 2f)
            leaves(1, top, 1f)
            leafCross(1, top + 1f, 1f)
        } else {
            leafCross(2, top - 2f, 3f)
        }
        TreeSpecies.Spruce -> if (tree.detailed) {
            // Alternating wide and narrow rings, narrowing to a single-block tip.
            var y = top - 1f
            var layer = 0
            while (y >= base + 2f) {
                leaves(if (layer % 2 == 0 || y > top - 3f) 1 else 2, y, 1f)
                y -= 1f
                layer++
            }
            leaves(0, top, 1f)
        } else {
            // A narrow body under a thin tip, so distant stands read as pointed conifers.
            leaves(1, base + 2f, top - base - 3f)
            leaves(0, top - 1f, 3f)
        }
    }
}

/** A tuft of short grass: two crossed quads on a summit or hillside column. */
internal data class GrassTuft(val x: Float, val y: Float, val z: Float, val size: Float, val tint: Float)

/** Grass tufts on the visible foreground: the summit and the upper hill flanks. */
internal fun placeGrassTufts(grid: TerrainGrid): List<GrassTuft> = buildList {
    for (z in -26 until 14) {
        for (x in -26 until 27) {
            val chance = TerrainNoise.hash01(x, z, TREE_SEED + 20).toFloat()
            if (chance >= GRASS_TUFT_CHANCE) continue
            // Keep the player's own column and the loose blocks clear.
            if (abs(x + 0.5f - PLAYER_X) < 1f && abs(z + 0.5f - PLAYER_Z) < 1f) continue
            if (SUMMIT_BOULDERS.any { it.x == x && it.z == z }) continue
            val height = grid.heightAt(x, z)
            if (height < HILL_TOP_Y - 12 || height > HILL_TOP_Y) continue
            val offsetX = TerrainNoise.hash01(x, z, TREE_SEED + 21).toFloat()
            val offsetZ = TerrainNoise.hash01(x, z, TREE_SEED + 22).toFloat()
            add(
                GrassTuft(
                    x = x + 0.3f + offsetX * 0.4f,
                    y = height.toFloat(),
                    z = z + 0.3f + offsetZ * 0.4f,
                    size = 0.6f + 0.4f * chance / GRASS_TUFT_CHANCE,
                    tint = 0.92f + 0.16f * offsetX
                )
            )
        }
    }
}

private const val GRASS_TUFT_CHANCE = 0.35f

/** A loose block resting on a summit terrace, like the stone block beside the player in the reference. */
internal data class SummitBoulder(val x: Int, val z: Int, val material: BlockMaterial)

/** One cobblestone block right of the player and a stone block to the left, both on lower terraces. */
internal val SUMMIT_BOULDERS = listOf(
    SummitBoulder(4, 2, BlockMaterial.COBBLESTONE),
    SummitBoulder(-5, 1, BlockMaterial.STONE)
)

private val BOULDER_SKIP = setOf(StaticFaceDirection.NegativeY)

internal fun emitSummitBoulders(builder: SceneMeshBuilder, grid: TerrainGrid) {
    SUMMIT_BOULDERS.forEach { boulder ->
        val y = grid.heightAt(boulder.x, boulder.z).toFloat()
        val box = StaticAabb(boulder.x.toFloat(), y, boulder.z.toFloat(), boulder.x + 1f, y + 1f, boulder.z + 1f)
        builder.box(boulder.material, box, tint = CLIFF_ROCK_TINT, skip = BOULDER_SKIP)
    }
}

/** The two diagonal quads; tops sway (kind [FOLIAGE_SWAY_KIND]) and the normal points up like Minecraft plants. */
internal fun emitGrassTuft(builder: SceneMeshBuilder, tuft: GrassTuft) {
    val half = tuft.size * 0.45f
    val top = tuft.y + tuft.size
    val tint = floatArrayOf(tuft.tint, tuft.tint, tuft.tint, tuft.tint)
    // Inset UVs keep fract() inside one tile at the quad edges.
    val uvs = floatArrayOf(0.001f, 0.999f, 0.999f, 0.999f, 0.999f, 0.001f, 0.001f, 0.001f)
    val kinds = intArrayOf(0, 0, FOLIAGE_SWAY_KIND, FOLIAGE_SWAY_KIND)
    listOf(1f to 1f, 1f to -1f).forEach { (dirX, dirZ) ->
        val points = floatArrayOf(
            tuft.x - half * dirX, tuft.y, tuft.z - half * dirZ,
            tuft.x + half * dirX, tuft.y, tuft.z + half * dirZ,
            tuft.x + half * dirX, top, tuft.z + half * dirZ,
            tuft.x - half * dirX, top, tuft.z - half * dirZ
        )
        builder.quad(BlockMaterial.SHORT_GRASS, points, 0f, 1f, 0f, 0, tint = tint, uvs = uvs, kinds = kinds)
    }
}

/** Counts of one vegetation build, reported by the preview tool and checked by tests. */
internal data class VegetationStats(val trees: Int, val detailedTrees: Int, val grassTufts: Int)

internal fun buildVegetationMesh(builder: SceneMeshBuilder, grid: TerrainGrid): VegetationStats {
    val camera = BackgroundScene.camera(0.0)
    // Near-to-far order lets each canopy depth-reject the ones behind it.
    val trees = placeTrees(grid).sortedBy { tree ->
        val dx = tree.x + 0.5f - camera.eyeX
        val dz = tree.z + 0.5f - camera.eyeZ
        dx * dx + dz * dz
    }
    trees.forEach { emitTree(builder, it) }
    val tufts = placeGrassTufts(grid)
    tufts.forEach { emitGrassTuft(builder, it) }
    emitSummitBoulders(builder, grid)
    return VegetationStats(trees.size, trees.count { it.detailed }, tufts.size)
}
