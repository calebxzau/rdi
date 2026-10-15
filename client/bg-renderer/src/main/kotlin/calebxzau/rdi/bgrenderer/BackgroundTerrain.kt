package calebxzau.rdi.bgrenderer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal const val TERRAIN_SEED = 0x5EED
/** Side faces next to the terrain edge or a deeper void extend down to this height. */
internal const val TERRAIN_FLOOR_Y = -8
/** Half-angle of the horizontal wedge that keeps terrain cells; covers the FOV, camera swing and a margin. */
internal const val TERRAIN_WEDGE_HALF_ANGLE_DEGREES = 66f
/** Cells farther than this from the camera are skipped: the atmosphere has dissolved them into the sky. */
internal const val TERRAIN_MAX_DISTANCE = WORLD_FADE_END + 40f
/** Snow, rock and the tree line belong to the range beyond the lake; nearer ground is grassland at any height. */
internal const val HIGH_RANGE_START_Z = -215
/** Extra width on the sun side so long low-sun shadows from just outside the view are still cast. */
internal const val TERRAIN_SUN_SIDE_MARGIN = 40f
internal const val TERRAIN_WEDGE_APEX_Z = PLAYER_Z + CAMERA_ORBIT_RADIUS + 6f
internal const val SCENE_VERTEX_BUDGET = 900_000

/** One rectangular level of detail; cells are [cellSize] blocks wide, and finer bands own their area. */
internal data class TerrainBand(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val cellSize: Int
) {
    init {
        require(cellSize > 0)
        require((maxX - minX) % cellSize == 0 && (maxZ - minZ) % cellSize == 0)
        require(Math.floorMod(minX, cellSize) == 0 && Math.floorMod(minZ, cellSize) == 0)
    }

    val columns: Int get() = (maxX - minX) / cellSize
    val rows: Int get() = (maxZ - minZ) / cellSize

    fun contains(x: Int, z: Int): Boolean = x >= minX && x < maxX && z >= minZ && z < maxZ
}

/** Nested bands; every band edge is a multiple of the next band's cell size so cells never straddle bands. */
internal val TERRAIN_BANDS = listOf(
    TerrainBand(-96, 96, -96, 40, 1),
    TerrainBand(-224, 224, -224, 40, 2),
    TerrainBand(-768, 768, -576, 40, 4),
    // The wide, high view reaches the haze-dissolved horizon; the outer ring only needs coarse cells.
    TerrainBand(-1024, 1024, -1024, 40, 8)
)

/** Deterministic hash-based value noise; integer arithmetic wraps like a 32-bit hash. */
internal object TerrainNoise {
    fun hash01(ix: Int, iz: Int, seed: Int): Double {
        var h = seed xor (ix * 0x27D4EB2D) xor (iz * 0x165667B1)
        h = (h xor (h ushr 15)) * 0x2C1B3C6D
        h = (h xor (h ushr 12)) * 0x297A2D39
        h = h xor (h ushr 15)
        return (h and 0xFFFFFF).toDouble() / 16_777_215.0
    }

    /** Quintic-faded value noise in `-1..1`. */
    fun value(x: Double, z: Double, seed: Int): Double {
        val x0 = floor(x)
        val z0 = floor(z)
        val fx = x - x0
        val fz = z - z0
        val ix = x0.toInt()
        val iz = z0.toInt()
        val ux = fx * fx * fx * (fx * (fx * 6.0 - 15.0) + 10.0)
        val uz = fz * fz * fz * (fz * (fz * 6.0 - 15.0) + 10.0)
        val a = hash01(ix, iz, seed)
        val b = hash01(ix + 1, iz, seed)
        val c = hash01(ix, iz + 1, seed)
        val d = hash01(ix + 1, iz + 1, seed)
        val v = a + (b - a) * ux + (c - a) * uz + (a - b - c + d) * ux * uz
        return v * 2.0 - 1.0
    }

    fun fbm(x: Double, z: Double, seed: Int, octaves: Int = 4): Double {
        var total = 0.0
        var amplitude = 0.5
        var frequency = 1.0
        var norm = 0.0
        repeat(octaves) { octave ->
            total += value(x * frequency, z * frequency, seed + octave * 1013) * amplitude
            norm += amplitude
            amplitude *= 0.5
            frequency *= 2.0
        }
        return total / norm
    }

    /** Ridged multifractal in `0..1`; sharp crests suit blocky mountain silhouettes. */
    fun ridged(x: Double, z: Double, seed: Int, octaves: Int = 5): Double {
        var total = 0.0
        var amplitude = 0.5
        var frequency = 1.0
        var norm = 0.0
        var weight = 1.0
        repeat(octaves) { octave ->
            var n = 1.0 - abs(value(x * frequency, z * frequency, seed + octave * 7919))
            n = n * n * weight
            weight = (n * 1.6).coerceIn(0.0, 1.0)
            total += n * amplitude
            norm += amplitude
            amplitude *= 0.5
            frequency *= 2.0
        }
        return total / norm
    }
}

private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/**
 * Snow-capped peaks behind the valley: x, z, apex height, footprint radius and ridge phase.
 * Laid out after the reference composition: a tall horn right of center with two shoulders,
 * a tall peak at the left edge, a lower chain across the middle and low peaks behind the sunset glow.
 * Sized for the high, wide view, so the range fills about a fifth of the frame height.
 */
private val MOUNTAIN_PEAKS = arrayOf(
    doubleArrayOf(185.0, -385.0, 211.0, 156.0, 0.4),
    doubleArrayOf(105.0, -440.0, 177.0, 130.0, 2.1),
    doubleArrayOf(270.0, -425.0, 180.0, 136.0, 4.0),
    doubleArrayOf(-380.0, -410.0, 195.0, 169.0, 1.3),
    doubleArrayOf(-210.0, -480.0, 167.0, 143.0, 3.2),
    doubleArrayOf(-70.0, -525.0, 174.0, 150.0, 5.1),
    doubleArrayOf(35.0, -505.0, 161.0, 130.0, 0.9),
    doubleArrayOf(410.0, -505.0, 164.0, 162.0, 2.7)
)

private fun mountainHeight(x: Double, z: Double): Double {
    val warpedX = x + TerrainNoise.fbm(x * 0.009, z * 0.009, TERRAIN_SEED + 820) * 30.0
    val warpedZ = z + TerrainNoise.fbm(x * 0.009, z * 0.009, TERRAIN_SEED + 840) * 30.0
    var massif = 0.0
    MOUNTAIN_PEAKS.forEach { (peakX, peakZ, peakHeight, radius, phase) ->
        val dx = warpedX - peakX
        val dz = warpedZ - peakZ
        // The footprint swells along a few spur directions, so ridges run down from each summit.
        val angle = atan2(dz, dx)
        val spurs = 1.0 + 0.30 * sin(3.0 * angle + phase) + 0.16 * sin(5.0 * angle + phase * 1.7) +
            0.12 * TerrainNoise.value(angle * 2.0 + phase * 5.0, 0.5, TERRAIN_SEED + 860)
        val distance = sqrt(dx * dx + dz * dz) / (radius * spurs)
        // A concave profile, steep near the summit and gentle at the foot, reads as a pointed horn.
        massif = max(massif, peakHeight * (1.0 - distance).coerceIn(0.0, 1.0).pow(1.6))
    }
    // Ridged detail cuts gullies and crags into the high slopes.
    val crags = (TerrainNoise.ridged(x * 0.04, z * 0.04, TERRAIN_SEED + 980, 3) - 0.5) * 34.0 * (massif / 90.0).coerceIn(0.0, 1.0)
    val foothills = 14.0 + 12.0 * TerrainNoise.fbm(x * 0.012, z * 0.012, TERRAIN_SEED + 950)
    // A low, jagged back range keeps the horizon closed between the peaks.
    val rim = smoothstep(-460.0, -570.0, z) * (30.0 + 22.0 * TerrainNoise.ridged(x * 0.015, z * 0.015, TERRAIN_SEED + 900))
    val raw = max(massif + crags, max(foothills, rim))
    // Softly compress the tallest summits so they stay below the cloud layer.
    val compressed = if (raw > PEAK_COMPRESSION_START) PEAK_COMPRESSION_START + (raw - PEAK_COMPRESSION_START) * 0.5 else raw
    // The range rises from just behind the ridges, so its broad foot does not end in a wall.
    return compressed * smoothstep(-250.0, -360.0, z)
}

private const val PEAK_COMPRESSION_START = 196.0

/**
 * The fixed golden-valley layout: a summit for the player, a low forested hill on the left,
 * a lake in the middle distance, two ridge lines and a snowy range at the back, with the
 * village's foundation pads flattened in.
 */
internal fun terrainHeight(x: Double, z: Double): Double = flattenVillagePads(baseTerrainHeight(x, z), x, z)

/** The natural layout before the village pads are flattened in. */
internal fun baseTerrainHeight(x: Double, z: Double): Double {
    val seed = TERRAIN_SEED
    var h = 7.0 + TerrainNoise.fbm(x * 0.02, z * 0.02, seed) * 2.6 +
        TerrainNoise.fbm(x * 0.08, z * 0.08, seed + 50, 2) * 0.8

    // Rolling hills to the right of the view.
    val right = smoothstep(30.0, 80.0, x) * (1.0 - smoothstep(-80.0, -100.0, z))
    h += right * (2.0 + TerrainNoise.fbm(x * 0.03, z * 0.03, seed + 200) * 3.5)

    // The valley floor slopes gently down to the lake, so the forest in front of it sits low enough
    // for the water to show over the crowns; it stays a block above the water outside the lake itself.
    val lake = lakeDistance(x, z)
    val dip = LAKE_APPROACH_DEPTH * (1.0 - smoothstep(1.0, 3.0, lake))
    if (dip > 0.0) h = max(h - dip, min(h, WATER_LEVEL + 1.2))

    // Two broken ridge lines; their gaps and valleys hold the phase-2 valley fog.
    RIDGES.forEach { (centerZ, width, base, amplitude, ridgeSeed) ->
        val wobble = TerrainNoise.fbm(x * 0.01, 0.0, ridgeSeed.toInt() + 7) * 18.0
        val band = exp(-((z - centerZ + wobble) / width).pow(2))
        val crest = base + amplitude * TerrainNoise.fbm(x * 0.018, z * 0.018, ridgeSeed.toInt())
        val gaps = smoothstep(-0.35, 0.15, TerrainNoise.fbm(x * 0.008, z * 0.008, ridgeSeed.toInt() + 3))
        h = max(h, band * gaps * crest)
    }

    h = max(h, mountainHeight(x, z))

    // Left woods: a low, rounded hill well below the summit, gentle enough for a tree on every column.
    val woods = 1.0 - smoothstep(0.0, 1.0, leftWoodsDistance(x, z))
    h += LEFT_WOODS_HEIGHT * woods * (0.85 + 0.3 * TerrainNoise.fbm(x * 0.04, z * 0.04, seed + 1300))

    // The lake and its river.
    val carve = 1.0 - smoothstep(0.6, 1.0, lake)
    h = h * (1.0 - carve) + (WATER_LEVEL - 2.6) * carve

    // The player's summit: a small flat top, one-block terraces all around (toward the camera too, so
    // the bottom of the frame shows grass-block sides), then a steeper flank down to the valley.
    val hillZ = if (z < 1.0) (z - 1.0) * 1.25 else (z - 1.0) * 0.9
    // Low-frequency, low-amplitude wobble keeps each terrace edge a continuous curve instead of lone blocks.
    val hillDistance = sqrt(x * x + hillZ * hillZ) + TerrainNoise.fbm(x * 0.08, z * 0.08, seed + 1700) * 0.9
    h = max(h, HILL_TOP_Y - summitDrop(hillDistance))
    return h
}

/** Flat top radius, then [SUMMIT_TERRACE_SLOPE] (a step every one or two blocks), then the flank. */
internal const val SUMMIT_FLAT_RADIUS = 2.5
internal const val SUMMIT_TERRACE_WIDTH = 6.0
internal const val SUMMIT_TERRACE_SLOPE = 0.85
internal const val SUMMIT_FLANK_SLOPE = 1.3
/** The left woods: a rounded hill about this many blocks above the valley floor, left of the summit. */
internal const val LEFT_WOODS_CENTER_X = -88.0
internal const val LEFT_WOODS_CENTER_Z = -88.0
internal const val LEFT_WOODS_RADIUS_X = 52.0
internal const val LEFT_WOODS_RADIUS_Z = 40.0
internal const val LEFT_WOODS_HEIGHT = 14.0

/** Normalized distance from the center of the left woods, with a slightly wavy edge; 1 is its foot. */
internal fun leftWoodsDistance(x: Double, z: Double): Double {
    val woodsX = (x - LEFT_WOODS_CENTER_X) / LEFT_WOODS_RADIUS_X
    val woodsZ = (z - LEFT_WOODS_CENTER_Z) / LEFT_WOODS_RADIUS_Z
    return sqrt(woodsX * woodsX + woodsZ * woodsZ) + TerrainNoise.fbm(x * 0.03, z * 0.03, TERRAIN_SEED + 1200) * 0.15
}

internal fun summitDrop(distance: Double): Double {
    val terrace = (distance - SUMMIT_FLAT_RADIUS).coerceIn(0.0, SUMMIT_TERRACE_WIDTH)
    val flank = (distance - SUMMIT_FLAT_RADIUS - SUMMIT_TERRACE_WIDTH).coerceAtLeast(0.0)
    return terrace * SUMMIT_TERRACE_SLOPE + flank * SUMMIT_FLANK_SLOPE
}

private val RIDGES = arrayOf(
    // centerZ, width, base, amplitude, seed; both stand behind the lake.
    doubleArrayOf(-252.0, 18.0, 20.0, 10.0, (TERRAIN_SEED + 300).toDouble()),
    doubleArrayOf(-290.0, 22.0, 30.0, 14.0, (TERRAIN_SEED + 400).toDouble())
)

/** A broad lake across the middle distance, right of center, in front of the ridges. */
internal const val LAKE_CENTER_X = 70.0
internal const val LAKE_CENTER_Z = -185.0
internal const val LAKE_RADIUS_X = 125.0
internal const val LAKE_RADIUS_Z = 40.0
/** How far the valley floor dips toward the lake shore. */
private const val LAKE_APPROACH_DEPTH = 3.5

/**
 * Normalized distance from the lake or the river that winds in from the left and joins its west
 * end: open water below about 0.6, the shore near 1.
 */
internal fun lakeDistance(x: Double, z: Double): Double {
    val lakeX = (x - LAKE_CENTER_X) / LAKE_RADIUS_X
    val lakeZ = (z - LAKE_CENTER_Z) / LAKE_RADIUS_Z
    val lake = sqrt(lakeX * lakeX + lakeZ * lakeZ) + TerrainNoise.fbm(x * 0.03, z * 0.03, TERRAIN_SEED + 1500) * 0.26
    val riverCenter = -176.0 + sin(x / 32.0) * 10.0
    val river = abs(z - riverCenter) / 8.0 +
        (1.0 - smoothstep(-260.0, -220.0, x)) * 3.0 + smoothstep(-70.0, -30.0, x) * 3.0
    return min(lake, river)
}

/**
 * Column height of a [cellSize]-wide cell, rounded to a multiple of the cell size so distant
 * terrain forms chunky cubes instead of thin contour stripes of one-block steps.
 */
internal fun terrainCellHeight(centerX: Double, centerZ: Double, cellSize: Int): Int =
    floor(terrainHeight(centerX, centerZ) / cellSize + 0.5).toInt() * cellSize

/**
 * In the range beyond the lake, snow sits on gentle columns above a slightly noisy line, with bare
 * rock outcrops and steep faces; a narrow rock band lies just below the snow and steep high slopes
 * are rock too. Everything lower, and all nearer ground (the summit included, however high), is
 * grass; steps keep their dirt/stone strata on the side faces.
 */
internal fun terrainTopMaterial(height: Int, x: Double, z: Double, steepness: Int, cellSize: Int): BlockMaterial {
    if (height <= 0) return BlockMaterial.GRAVEL
    if (height.toFloat() < WATER_LEVEL) return BlockMaterial.SAND
    if (z >= HIGH_RANGE_START_Z) return BlockMaterial.GRASS_TOP
    val snowLine = snowLine(x, z)
    return when {
        height >= snowLine && steepness <= 3 * cellSize && !rockOutcrop(x, z) -> BlockMaterial.SNOW
        height >= snowLine - ROCK_BAND_HEIGHT -> BlockMaterial.STONE
        height >= MOUNTAIN_ROCK_HEIGHT && steepness > 2 * cellSize -> BlockMaterial.STONE
        else -> BlockMaterial.GRASS_TOP
    }
}

/** The slightly noisy height above which gentle columns are snow. */
internal fun snowLine(x: Double, z: Double): Double = 92.0 + 8.0 * TerrainNoise.fbm(x * 0.02, z * 0.02, TERRAIN_SEED + 2100)

/** Bare rock outcrops break up the snowfields like the dark bands on real peaks. */
internal fun rockOutcrop(x: Double, z: Double): Boolean = TerrainNoise.fbm(x * 0.05, z * 0.05, TERRAIN_SEED + 2200, 2) > 0.42

internal const val SNOW_SIDE_DEPTH = 4
internal const val DEFAULT_DIRT_DEPTH = 5

/**
 * Depth where grassy columns turn from dirt to stone: 3..5 blocks, varying slowly, so tall faces
 * such as the left cliff show patches of grey rock under the dirt like natural strata.
 */
internal fun nearDirtDepth(x: Int, z: Int): Int =
    3 + floor((TerrainNoise.fbm(x * 0.09, z * 0.09, TERRAIN_SEED + 2400, 2) * 0.5 + 0.5) * 3.0).toInt().coerceIn(0, 2)
internal const val ROCK_BAND_HEIGHT = 8
/** From this cell size up, grassy steps are green down their first cell of height (see [terrainSideLayers]). */
internal const val GREEN_STEP_CELL_SIZE = 4

/**
 * Vertical material layers below a column top, as (start depth, end depth, material).
 * Distant coarse cells are seen almost only by their side faces, where repeated grass-block
 * sides would read as brown dirt stripes; their grassy steps stay green like vegetated slopes.
 */
internal fun terrainSideLayers(
    top: BlockMaterial,
    cellSize: Int = 1,
    dirtDepth: Int = DEFAULT_DIRT_DEPTH
): List<Triple<Int, Int, BlockMaterial>> = when (top) {
    BlockMaterial.GRASS_TOP -> if (cellSize >= GREEN_STEP_CELL_SIZE) listOf(
        Triple(0, cellSize, BlockMaterial.GRASS_TOP),
        Triple(cellSize, cellSize + 2, BlockMaterial.DIRT),
        Triple(cellSize + 2, Int.MAX_VALUE, BlockMaterial.STONE)
    ) else listOf(
        Triple(0, 1, BlockMaterial.GRASS_SIDE),
        Triple(1, dirtDepth, BlockMaterial.DIRT),
        Triple(dirtDepth, Int.MAX_VALUE, BlockMaterial.STONE)
    )
    // The range is seen from below, mostly by its side faces: snow runs down each step and only
    // tall (steep) steps show bare rock beneath it.
    BlockMaterial.SNOW -> listOf(
        Triple(0, SNOW_SIDE_DEPTH, BlockMaterial.SNOW),
        Triple(SNOW_SIDE_DEPTH, Int.MAX_VALUE, BlockMaterial.STONE)
    )
    BlockMaterial.SAND -> listOf(
        Triple(0, 3, BlockMaterial.SAND),
        Triple(3, Int.MAX_VALUE, BlockMaterial.STONE)
    )
    BlockMaterial.GRAVEL -> listOf(
        Triple(0, 2, BlockMaterial.GRAVEL),
        Triple(2, Int.MAX_VALUE, BlockMaterial.STONE)
    )
    else -> listOf(Triple(0, Int.MAX_VALUE, top))
}

/** Snow stays bright in the shade it is mostly seen in, and the high range's rock is dark, as on real peaks. */
internal const val SNOW_BRIGHTNESS = 2.1f
internal const val MOUNTAIN_ROCK_TINT = 0.55f
/** Lower stone (cliff and ridge strata) is a little darker than the texture, so lit cliff faces do not glare. */
internal const val CLIFF_ROCK_TINT = 0.8f
/** Stone from this column height up is dark mountain rock; lower stone (cliff and ridge strata) uses [CLIFF_ROCK_TINT]. */
internal const val MOUNTAIN_ROCK_HEIGHT = 84

internal fun mountainTint(material: BlockMaterial, columnTop: Int): Float = when {
    material == BlockMaterial.SNOW -> SNOW_BRIGHTNESS
    material == BlockMaterial.STONE && columnTop >= MOUNTAIN_ROCK_HEIGHT -> MOUNTAIN_ROCK_TINT
    material == BlockMaterial.STONE -> CLIFF_ROCK_TINT
    else -> 1f
}

/** Gentle low-frequency brightness variation, like Minecraft's biome color blending. */
internal fun terrainTint(x: Float, z: Float): Float =
    (0.93 + 0.14 * (TerrainNoise.value(x * 0.045, z * 0.045, TERRAIN_SEED + 2300) * 0.5 + 0.5)).toFloat()

/** Cell heights of every band, sampled once at the cell centers. */
internal class TerrainGrid(val bands: List<TerrainBand> = TERRAIN_BANDS) {
    private val heights: List<IntArray> = bands.map { band ->
        IntArray(band.columns * band.rows) { index ->
            val ix = index % band.columns
            val iz = index / band.columns
            terrainCellHeight(
                band.minX + (ix + 0.5) * band.cellSize,
                band.minZ + (iz + 0.5) * band.cellSize,
                band.cellSize
            )
        }
    }

    /** Index of the finest band owning the block column, or -1 outside the terrain. */
    fun bandIndexAt(x: Int, z: Int): Int = bands.indexOfFirst { it.contains(x, z) }

    fun cellHeight(bandIndex: Int, ix: Int, iz: Int): Int = heights[bandIndex][iz * bands[bandIndex].columns + ix]

    /** Height of the column containing block (x, z) at its owning band's resolution. */
    fun heightAt(x: Int, z: Int): Int {
        val bandIndex = bandIndexAt(x, z)
        if (bandIndex < 0) return TERRAIN_FLOOR_Y
        val band = bands[bandIndex]
        return cellHeight(bandIndex, Math.floorDiv(x - band.minX, band.cellSize), Math.floorDiv(z - band.minZ, band.cellSize))
    }

    fun heightAtPoint(x: Float, z: Float): Int = heightAt(floor(x).toInt(), floor(z).toInt())
}

/** Whether a cell lies inside the horizontal view wedge (plus the sun-side shadow margin). */
internal fun terrainCellVisible(x0: Int, z0: Int, cellSize: Int): Boolean {
    val centerX = x0 + cellSize * 0.5f
    val centerZ = z0 + cellSize * 0.5f
    val along = TERRAIN_WEDGE_APEX_Z - centerZ
    if (along < -cellSize) return false
    val halfWidth = along * kotlin.math.tan(Math.toRadians(TERRAIN_WEDGE_HALF_ANGLE_DEGREES.toDouble())).toFloat() + cellSize
    val offset = centerX - PLAYER_X
    val inWedge = if (offset < 0f) -offset <= halfWidth else offset <= halfWidth + TERRAIN_SUN_SIDE_MARGIN
    val fromEyeZ = centerZ - (PLAYER_Z + CAMERA_ORBIT_RADIUS)
    return inWedge && offset * offset + fromEyeZ * fromEyeZ <= TERRAIN_MAX_DISTANCE * TERRAIN_MAX_DISTANCE
}

/** Statistics of one terrain build, reported by the preview tool and checked by tests. */
internal data class TerrainMeshStats(
    val cells: Int,
    val mergedTopQuads: Int,
    val topQuads: Int,
    val sideQuads: Int
)

private const val TOP_AO_STEP = 0.14f
/** Keeps greedy-merge keys non-negative for lake floors and the terrain floor. */
private const val MERGE_HEIGHT_OFFSET = 1024
private const val SIDE_BOTTOM_AO = 0.86f

/** Appends the visible terrain cells as indexed static quads (kind 0). */
internal fun buildTerrainMesh(builder: SceneMeshBuilder, grid: TerrainGrid): TerrainMeshStats {
    var cells = 0
    var mergedTopQuads = 0
    var topQuads = 0
    var sideQuads = 0
    grid.bands.forEachIndexed { bandIndex, band ->
        val size = band.cellSize
        val columns = band.columns
        val rows = band.rows
        val emitted = BooleanArray(columns * rows)
        val mergeKey = IntArray(columns * rows) { -1 }
        val materials = arrayOfNulls<BlockMaterial>(columns * rows)
        for (iz in 0 until rows) {
            for (ix in 0 until columns) {
                val x0 = band.minX + ix * size
                val z0 = band.minZ + iz * size
                if (grid.bandIndexAt(x0, z0) != bandIndex || !terrainCellVisible(x0, z0, size)) continue
                val index = iz * columns + ix
                val height = grid.cellHeight(bandIndex, ix, iz)
                val steepness = maxOf(
                    abs(height - grid.heightAt(x0 - 1, z0)),
                    abs(height - grid.heightAt(x0 + size, z0)),
                    abs(height - grid.heightAt(x0, z0 - 1)),
                    abs(height - grid.heightAt(x0, z0 + size))
                )
                val material = terrainTopMaterial(height, x0 + size * 0.5, z0 + size * 0.5, steepness, size)
                emitted[index] = true
                materials[index] = material
                cells++

                val ao = topCornerAo(grid, x0, z0, size, height)
                if (ao.all { it == 1f }) {
                    mergeKey[index] = (height + MERGE_HEIGHT_OFFSET) * BlockMaterial.entries.size + material.ordinal
                } else {
                    emitTop(builder, material, x0.toFloat(), z0.toFloat(), (x0 + size).toFloat(), (z0 + size).toFloat(), height.toFloat(), ao)
                    topQuads++
                }
                sideQuads += emitSides(builder, grid, x0, z0, size, height, material)
            }
        }
        // Greedy rectangles over unoccluded tops with the same height and material.
        val consumed = BooleanArray(columns * rows)
        for (iz in 0 until rows) {
            var ix = 0
            while (ix < columns) {
                val index = iz * columns + ix
                val key = mergeKey[index]
                if (key < 0 || consumed[index]) {
                    ix++
                    continue
                }
                var width = 1
                while (ix + width < columns && mergeKey[index + width] == key && !consumed[index + width]) width++
                var depth = 1
                rows@ while (iz + depth < rows) {
                    val rowStart = (iz + depth) * columns + ix
                    for (offset in 0 until width) {
                        if (mergeKey[rowStart + offset] != key || consumed[rowStart + offset]) break@rows
                    }
                    depth++
                }
                for (dz in 0 until depth) for (dx in 0 until width) consumed[(iz + dz) * columns + ix + dx] = true
                val material = requireNotNull(materials[index])
                val height = key / BlockMaterial.entries.size - MERGE_HEIGHT_OFFSET
                val minX = (band.minX + ix * size).toFloat()
                val minZ = (band.minZ + iz * size).toFloat()
                emitTop(builder, material, minX, minZ, minX + width * size, minZ + depth * size, height.toFloat(), null)
                mergedTopQuads++
                ix += width
            }
        }
    }
    return TerrainMeshStats(cells, mergedTopQuads, topQuads, sideQuads)
}

/** Minecraft-style corner AO in the top-quad corner order (minX,maxZ), (maxX,maxZ), (maxX,minZ), (minX,minZ). */
internal fun topCornerAo(grid: TerrainGrid, x0: Int, z0: Int, size: Int, height: Int): FloatArray {
    val x1 = x0 + size
    val z1 = z0 + size
    return floatArrayOf(
        cornerAo(grid, x0.toFloat(), z1.toFloat(), -1f, 1f, height),
        cornerAo(grid, x1.toFloat(), z1.toFloat(), 1f, 1f, height),
        cornerAo(grid, x1.toFloat(), z0.toFloat(), 1f, -1f, height),
        cornerAo(grid, x0.toFloat(), z0.toFloat(), -1f, -1f, height)
    )
}

private fun cornerAo(grid: TerrainGrid, cornerX: Float, cornerZ: Float, signX: Float, signZ: Float, height: Int): Float {
    val sideA = grid.heightAtPoint(cornerX + signX * 0.5f, cornerZ - signZ * 0.5f) > height
    val sideB = grid.heightAtPoint(cornerX - signX * 0.5f, cornerZ + signZ * 0.5f) > height
    val diagonal = grid.heightAtPoint(cornerX + signX * 0.5f, cornerZ + signZ * 0.5f) > height
    val level = if (sideA && sideB) 3 else (if (sideA) 1 else 0) + (if (sideB) 1 else 0) + (if (diagonal) 1 else 0)
    return 1f - TOP_AO_STEP * level
}

private fun emitTop(
    builder: SceneMeshBuilder,
    material: BlockMaterial,
    minX: Float,
    minZ: Float,
    maxX: Float,
    maxZ: Float,
    y: Float,
    ao: FloatArray?
) {
    val points = floatArrayOf(minX, y, maxZ, maxX, y, maxZ, maxX, y, minZ, minX, y, minZ)
    val tint = if (material == BlockMaterial.GRASS_TOP) floatArrayOf(
        terrainTint(minX, maxZ), terrainTint(maxX, maxZ), terrainTint(maxX, minZ), terrainTint(minX, minZ)
    ) else mountainTint(material, y.toInt()).let { floatArrayOf(it, it, it, it) }
    if (ao == null) {
        builder.quad(material, points, 0f, 1f, 0f, 0, tint = tint)
    } else {
        builder.quad(material, points, 0f, 1f, 0f, 0, ao, tint, flipDiagonal = ao[0] + ao[2] < ao[1] + ao[3])
    }
}

/** The four horizontal side directions: outward normal and the edge each cell side lies on. */
private enum class SideDirection(val normalX: Int, val normalZ: Int) {
    PositiveX(1, 0), NegativeX(-1, 0), PositiveZ(0, 1), NegativeZ(0, -1)
}

private fun emitSides(
    builder: SceneMeshBuilder,
    grid: TerrainGrid,
    x0: Int,
    z0: Int,
    size: Int,
    height: Int,
    topMaterial: BlockMaterial
): Int {
    var quads = 0
    SideDirection.entries.forEach { direction ->
        // Walk the edge one block at a time so finer neighbors across a band edge are respected.
        var segmentStart = 0
        var segmentHeight = Int.MIN_VALUE
        for (step in 0..size) {
            val neighborHeight = if (step == size) Int.MIN_VALUE else {
                val sampleX = when (direction) {
                    SideDirection.PositiveX -> x0 + size
                    SideDirection.NegativeX -> x0 - 1
                    else -> x0 + step
                }
                val sampleZ = when (direction) {
                    SideDirection.PositiveZ -> z0 + size
                    SideDirection.NegativeZ -> z0 - 1
                    else -> z0 + step
                }
                grid.heightAt(sampleX, sampleZ)
            }
            if (step == 0) {
                segmentHeight = neighborHeight
                continue
            }
            if (neighborHeight != segmentHeight || step == size) {
                if (segmentHeight < height) {
                    quads += emitSideSegment(
                        builder, direction, x0, z0, size, segmentStart, step,
                        max(segmentHeight, TERRAIN_FLOOR_Y), height, topMaterial
                    )
                }
                segmentStart = step
                segmentHeight = neighborHeight
            }
        }
    }
    return quads
}

private fun emitSideSegment(
    builder: SceneMeshBuilder,
    direction: SideDirection,
    x0: Int,
    z0: Int,
    size: Int,
    from: Int,
    to: Int,
    bottom: Int,
    top: Int,
    topMaterial: BlockMaterial
): Int {
    var quads = 0
    val dirtDepth = if (size == 1) nearDirtDepth(x0, z0) else DEFAULT_DIRT_DEPTH
    terrainSideLayers(topMaterial, size, dirtDepth).forEach { (startDepth, endDepth, material) ->
        val layerTop = top - startDepth
        val layerBottom = if (endDepth == Int.MAX_VALUE) bottom else max(top - endDepth, bottom)
        if (layerTop <= layerBottom) return@forEach
        val yb = layerBottom.toFloat()
        val yt = layerTop.toFloat()
        val bottomAo = if (layerBottom == bottom && bottom > TERRAIN_FLOOR_Y) SIDE_BOTTOM_AO else 1f
        val points = when (direction) {
            SideDirection.PositiveX -> {
                val x = (x0 + size).toFloat()
                val za = (z0 + from).toFloat()
                val zb = (z0 + to).toFloat()
                floatArrayOf(x, yb, zb, x, yb, za, x, yt, za, x, yt, zb)
            }
            SideDirection.NegativeX -> {
                val x = x0.toFloat()
                val za = (z0 + from).toFloat()
                val zb = (z0 + to).toFloat()
                floatArrayOf(x, yb, za, x, yb, zb, x, yt, zb, x, yt, za)
            }
            SideDirection.PositiveZ -> {
                val z = (z0 + size).toFloat()
                val xa = (x0 + from).toFloat()
                val xb = (x0 + to).toFloat()
                floatArrayOf(xa, yb, z, xb, yb, z, xb, yt, z, xa, yt, z)
            }
            SideDirection.NegativeZ -> {
                val z = z0.toFloat()
                val xa = (x0 + from).toFloat()
                val xb = (x0 + to).toFloat()
                floatArrayOf(xb, yb, z, xa, yb, z, xa, yt, z, xb, yt, z)
            }
        }
        val tint = mountainTint(material, top)
        builder.quad(
            material,
            points,
            direction.normalX.toFloat(),
            0f,
            direction.normalZ.toFloat(),
            0,
            floatArrayOf(bottomAo, bottomAo, 1f, 1f),
            floatArrayOf(tint, tint, tint, tint)
        )
        quads++
    }
    return quads
}

/** Bounds of the near terrain that the static shadow map frames, extended toward the sun for casters. */
internal fun terrainShadowBounds(grid: TerrainGrid): StaticAabb {
    var minY = Int.MAX_VALUE
    var maxY = Int.MIN_VALUE
    for (x in SHADOW_MIN_X until SHADOW_MAX_X step 4) {
        for (z in SHADOW_MIN_Z until SHADOW_MAX_Z step 4) {
            val height = grid.heightAt(x, z)
            minY = min(minY, height)
            maxY = max(maxY, height)
        }
    }
    return StaticAabb(
        SHADOW_MIN_X.toFloat(), (minY - 2).toFloat(), SHADOW_MIN_Z.toFloat(),
        SHADOW_MAX_X.toFloat(), (maxY + 2).toFloat(), SHADOW_MAX_Z.toFloat()
    )
}

private const val SHADOW_MIN_X = -172
private const val SHADOW_MAX_X = 320
private const val SHADOW_MIN_Z = -240
private const val SHADOW_MAX_Z = 32
