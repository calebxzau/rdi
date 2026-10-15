package calebxzau.rdi.bgrenderer

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundTerrainTest {
    private val grid by lazy { TerrainGrid() }

    @Test
    fun noiseHashMatchesTheReferencePrototypeBitForBit() {
        // Values produced by the Python composition prototype with explicit 32-bit wrapping.
        assertEquals(0.04113400227630152, TerrainNoise.hash01(0, 0, 0x5EED), absoluteTolerance = 1e-15)
        assertEquals(0.7803994882344895, TerrainNoise.hash01(3, -7, 0x5EED), absoluteTolerance = 1e-15)
        assertEquals(0.2124269731299265, TerrainNoise.hash01(-123456, 98765, 0x5EED + 1013), absoluteTolerance = 1e-15)
    }

    @Test
    fun noiseFunctionsStayInRangeAndAreDeterministic() {
        for (i in 0 until 2000) {
            val x = i * 0.731 - 600.0
            val z = i * -0.377 + 90.0
            val value = TerrainNoise.value(x, z, TERRAIN_SEED)
            assertTrue(value in -1.0..1.0)
            assertTrue(TerrainNoise.fbm(x, z, TERRAIN_SEED) in -1.0..1.0)
            assertTrue(TerrainNoise.ridged(x, z, TERRAIN_SEED) in 0.0..1.0)
            assertEquals(value, TerrainNoise.value(x, z, TERRAIN_SEED))
        }
        // Value noise interpolates its lattice exactly.
        assertEquals(TerrainNoise.hash01(4, -9, 7) * 2.0 - 1.0, TerrainNoise.value(4.0, -9.0, 7), absoluteTolerance = 1e-12)
    }

    @Test
    fun bandsNestWithoutStraddlingCells() {
        assertEquals(listOf(1, 2, 4, 8), TERRAIN_BANDS.map { it.cellSize })
        TERRAIN_BANDS.zipWithNext().forEach { (fine, coarse) ->
            assertTrue(coarse.minX <= fine.minX && coarse.maxX >= fine.maxX)
            assertTrue(coarse.minZ <= fine.minZ && coarse.maxZ >= fine.maxZ)
            listOf(fine.minX, fine.maxX, fine.minZ, fine.maxZ).forEach { edge ->
                assertEquals(0, Math.floorMod(edge, coarse.cellSize))
            }
        }
        val camera = BackgroundScene.camera(0.0)
        assertEquals(0, grid.bandIndexAt(floor(camera.eyeX).toInt(), floor(camera.eyeZ).toInt()))
        assertEquals(-1, grid.bandIndexAt(0, 1000))
        assertEquals(TERRAIN_FLOOR_Y, grid.heightAt(0, 1000))
        // The bands reach past the distance where the atmosphere has dissolved the terrain into the sky.
        val outer = TERRAIN_BANDS.last()
        assertTrue(-outer.minZ + camera.eyeZ >= WORLD_FADE_END && outer.maxX >= WORLD_FADE_END && -outer.minX >= WORLD_FADE_END)
    }

    @Test
    fun gridHeightsAreSampledAtTheOwningCellCenter() {
        listOf(0 to 0, -95 to -95, 95 to 39, 120 to -150, -400 to -500).forEach { (x, z) ->
            val band = TERRAIN_BANDS[grid.bandIndexAt(x, z)]
            val cellX = band.minX + Math.floorDiv(x - band.minX, band.cellSize) * band.cellSize
            val cellZ = band.minZ + Math.floorDiv(z - band.minZ, band.cellSize) * band.cellSize
            val height = grid.heightAt(x, z)
            assertEquals(terrainCellHeight(cellX + band.cellSize * 0.5, cellZ + band.cellSize * 0.5, band.cellSize), height, "column ($x, $z)")
            assertEquals(0, Math.floorMod(height, band.cellSize), "column ($x, $z) is not on its band's height grid")
        }
    }

    @Test
    fun layoutHasAFlatSummitAValleyALakeAndSnowyPeaks() {
        for (x in -1..1) for (z in -1..1) assertEquals(HILL_TOP_Y, grid.heightAt(x, z), "summit column ($x, $z)")
        // Nothing between the camera and the player rises above the sight line to the player's feet.
        val camera = BackgroundScene.camera(0.0)
        for (step in 1 until 40) {
            val t = step / 40f
            val x = camera.eyeX + (PLAYER_X - camera.eyeX) * t
            val y = camera.eyeY + (PLAYER_FEET_Y - camera.eyeY) * t
            val z = camera.eyeZ + (PLAYER_Z - camera.eyeZ) * t
            assertTrue(grid.heightAtPoint(x, z) <= y, "terrain blocks the view at ($x, $z)")
        }
        assertTrue(grid.heightAt(0, -40) < HILL_TOP_Y - 10, "valley in front of the summit")
        // Snow and rock belong to the range beyond the lake; the summit stays grassy however high it is.
        assertEquals(BlockMaterial.GRASS_TOP, terrainTopMaterial(HILL_TOP_Y, 0.5, 0.5, 0, 1))
        assertEquals(BlockMaterial.GRASS_TOP, terrainTopMaterial(HILL_TOP_Y - 6, 8.5, 0.5, 6, 1))
        // The summit steps down toward the camera too, so the bottom of the frame shows grass-block sides.
        val towardCamera = (0..13).map { grid.heightAt(0, it) }
        towardCamera.zipWithNext().forEach { (near, far) -> assertTrue(far <= near, "summit rises toward the camera: $towardCamera") }
        assertTrue(towardCamera.last() <= HILL_TOP_Y - 5, "terraces below the camera: $towardCamera")
        assertTrue(towardCamera.zipWithNext().count { (near, far) -> far < near } >= 4, "terrace steps: $towardCamera")
        // The left woods are a low, gentle hill: well below the summit and never a cliff.
        val woodsTop = grid.heightAt(LEFT_WOODS_CENTER_X.toInt(), LEFT_WOODS_CENTER_Z.toInt())
        assertTrue(woodsTop in (grid.heightAt(-150, -88) + 6)..(HILL_TOP_Y - 15), "left woods top $woodsTop")
        for (x in -150 until -30 step 2) for (z in -140 until -40 step 2) {
            if (leftWoodsDistance(x.toDouble(), z.toDouble()) > 1.0 || grid.bandIndexAt(x, z) != 0) continue
            val height = grid.heightAt(x, z)
            val rise = maxOf(abs(height - grid.heightAt(x + 1, z)), abs(height - grid.heightAt(x, z + 1)))
            assertTrue(rise <= 2, "left woods step of $rise at ($x, $z)")
        }
        for (x in -120 until 40 step 2) for (z in -120 until 12 step 2) {
            assertTrue(grid.heightAt(x, z) <= HILL_TOP_Y, "column ($x, $z) rises above the summit")
        }
        var lakeColumns = 0
        for (x in 0 until 140 step 2) for (z in -190 until -150 step 2) if (grid.heightAt(x, z) < WATER_LEVEL) lakeColumns++
        assertTrue(lakeColumns > 300, "lake columns: $lakeColumns")
        var highest = Int.MIN_VALUE
        var snowColumns = 0
        for (x in -500 until 500 step 4) for (z in -576 until -300 step 4) {
            val height = grid.heightAt(x, z)
            highest = maxOf(highest, height)
            if (terrainTopMaterial(height, x + 2.0, z + 2.0, 0, 4) == BlockMaterial.SNOW) snowColumns++
        }
        assertTrue(highest in 95..(CLOUD_HEIGHT.toInt() - 8), "highest peak: $highest")
        assertTrue(snowColumns > 200, "snow columns: $snowColumns")
    }

    @Test
    fun topMaterialAndSideLayersFollowMinecraftStrata() {
        assertEquals(BlockMaterial.GRAVEL, terrainTopMaterial(0, 0.0, 0.0, 0, 1))
        assertEquals(BlockMaterial.SAND, terrainTopMaterial(2, 0.0, 0.0, 0, 1))
        assertEquals(BlockMaterial.GRASS_TOP, terrainTopMaterial(3, 0.0, 0.0, 0, 1))
        assertEquals(BlockMaterial.GRASS_TOP, terrainTopMaterial(20, 0.0, 0.0, 6, 1))
        // A snowfield column (no outcrop) is snow when gentle and rock when steep.
        val snowX = (0 until 400 step 4).map { it.toDouble() }.first { !rockOutcrop(it, -400.0) }
        val line = snowLine(snowX, -400.0)
        val high = line.toInt() + 20
        assertEquals(BlockMaterial.SNOW, terrainTopMaterial(high, snowX, -400.0, 0, 4))
        assertEquals(BlockMaterial.STONE, terrainTopMaterial(high, snowX, -400.0, 16, 4))
        // Outcrops stay bare rock even on gentle columns.
        val outcropX = (0 until 4000 step 4).map { it.toDouble() }.first { rockOutcrop(it, -400.0) }
        assertEquals(BlockMaterial.STONE, terrainTopMaterial(snowLine(outcropX, -400.0).toInt() + 20, outcropX, -400.0, 0, 4))
        // A narrow rock band below the snow; below it the forested foot of the range is grass unless steep and high.
        assertEquals(BlockMaterial.STONE, terrainTopMaterial(kotlin.math.ceil(line - 2.0).toInt(), snowX, -400.0, 0, 4))
        assertEquals(BlockMaterial.GRASS_TOP, terrainTopMaterial(kotlin.math.floor(line - ROCK_BAND_HEIGHT - 1.0).toInt(), snowX, -400.0, 0, 4))
        assertEquals(BlockMaterial.STONE, terrainTopMaterial(MOUNTAIN_ROCK_HEIGHT, snowX, -400.0, 12, 4))

        fun assertContiguous(layers: List<Triple<Int, Int, BlockMaterial>>) {
            assertEquals(0, layers.first().first)
            assertEquals(Int.MAX_VALUE, layers.last().second)
            layers.zipWithNext().forEach { (upper, lower) -> assertEquals(upper.second, lower.first) }
        }
        val grassLayers = terrainSideLayers(BlockMaterial.GRASS_TOP)
        assertEquals(listOf(BlockMaterial.GRASS_SIDE, BlockMaterial.DIRT, BlockMaterial.STONE), grassLayers.map { it.third })
        assertContiguous(grassLayers)
        // Distant coarse steps stay green for one cell of height instead of showing dirt stripes.
        val coarseGrassLayers = terrainSideLayers(BlockMaterial.GRASS_TOP, 4)
        assertEquals(listOf(BlockMaterial.GRASS_TOP, BlockMaterial.DIRT, BlockMaterial.STONE), coarseGrassLayers.map { it.third })
        assertEquals(4, coarseGrassLayers.first().second)
        assertContiguous(coarseGrassLayers)
        assertEquals(terrainSideLayers(BlockMaterial.GRASS_TOP), terrainSideLayers(BlockMaterial.GRASS_TOP, 2))
        val snowLayers = terrainSideLayers(BlockMaterial.SNOW, 4)
        assertEquals(listOf(BlockMaterial.SNOW, BlockMaterial.STONE), snowLayers.map { it.third })
        assertEquals(SNOW_SIDE_DEPTH, snowLayers.first().second)
        assertContiguous(snowLayers)
        assertEquals(listOf(BlockMaterial.STONE), terrainSideLayers(BlockMaterial.STONE).map { it.third })
        // Snow is brightened, mountain rock darkened most, and lower cliff stone a little.
        assertTrue(mountainTint(BlockMaterial.SNOW, 80) > 1f)
        assertTrue(mountainTint(BlockMaterial.STONE, MOUNTAIN_ROCK_HEIGHT) < 1f)
        assertEquals(CLIFF_ROCK_TINT, mountainTint(BlockMaterial.STONE, MOUNTAIN_ROCK_HEIGHT - 1))
        assertTrue(MOUNTAIN_ROCK_TINT < CLIFF_ROCK_TINT && CLIFF_ROCK_TINT < 1f)
        assertEquals(1f, mountainTint(BlockMaterial.GRASS_TOP, 80))
    }

    @Test
    fun wedgeKeepsTheViewAndDropsCellsBehindOrBesideTheCamera() {
        assertTrue(terrainCellVisible(0, 0, 1))
        assertTrue(terrainCellVisible(-300, -500, 4))
        assertTrue(terrainCellVisible(300, -500, 4))
        assertTrue(!terrainCellVisible(0, 36, 4))
        assertTrue(!terrainCellVisible(-120, 0, 2))
        // The sun side keeps a wider strip for shadow casters.
        assertTrue(terrainCellVisible(110, -20, 2) && !terrainCellVisible(-110, -20, 2))
        // Nothing beyond the haze-dissolved horizon is kept.
        assertTrue(terrainCellVisible(0, -(TERRAIN_MAX_DISTANCE - 40f).toInt(), 8))
        assertTrue(!terrainCellVisible(0, -(TERRAIN_MAX_DISTANCE + 40f).toInt(), 8))
        assertTrue(TERRAIN_MAX_DISTANCE > WORLD_FADE_END && TERRAIN_MAX_DISTANCE < CAMERA_FAR_PLANE)
        // Every frame corner along the camera track stays inside the wedge.
        val halfAngle = Math.toRadians(TERRAIN_WEDGE_HALF_ANGLE_DEGREES.toDouble())
        val horizontalHalfFov = kotlin.math.atan(
            kotlin.math.tan(Math.toRadians(CAMERA_FOV_DEGREES / 2.0)) * RENDER_WIDTH / RENDER_HEIGHT
        )
        assertTrue(horizontalHalfFov + CAMERA_SWING_RADIANS < halfAngle - Math.toRadians(4.0))
    }

    @Test
    fun topCornerAoDarkensOnlyCornersNextToHigherColumns() {
        val summit = topCornerAo(grid, 0, 0, 1, HILL_TOP_Y)
        assertTrue(summit.all { it == 1f })
        var occluded = 0
        for (x in -60 until 60) for (z in -60 until 0) {
            val ao = topCornerAo(grid, x, z, 1, grid.heightAt(x, z))
            assertTrue(ao.all { it in 0.58f..1f })
            if (ao.any { it < 1f }) occluded++
        }
        assertTrue(occluded > 0)
    }

    @Test
    fun terrainMeshIsWoundOutwardAndUsesAxisNormals() {
        val builder = SceneMeshBuilder()
        val stats = buildTerrainMesh(builder, grid)
        assertTrue(stats.cells > 50_000, "$stats")
        assertTrue(stats.mergedTopQuads > 0 && stats.topQuads > 0 && stats.sideQuads > 0, "$stats")
        assertTrue(stats.mergedTopQuads + stats.topQuads < stats.cells, "greedy merging did not reduce tops: $stats")
        val mesh = builder.build(terrainShadowBounds(grid))
        val floats = SCENE_VERTEX_FLOATS
        for (triangle in 0 until mesh.indices.size / 3) {
            val a = mesh.indices[triangle * 3] * floats
            val b = mesh.indices[triangle * 3 + 1] * floats
            val c = mesh.indices[triangle * 3 + 2] * floats
            val v = mesh.vertices
            val abx = v[b] - v[a]; val aby = v[b + 1] - v[a + 1]; val abz = v[b + 2] - v[a + 2]
            val acx = v[c] - v[a]; val acy = v[c + 1] - v[a + 1]; val acz = v[c + 2] - v[a + 2]
            val crossX = aby * acz - abz * acy
            val crossY = abz * acx - abx * acz
            val crossZ = abx * acy - aby * acx
            val normalX = v[a + 3]; val normalY = v[a + 4]; val normalZ = v[a + 5]
            assertEquals(1f, abs(normalX) + abs(normalY) + abs(normalZ))
            assertTrue(crossX * normalX + crossY * normalY + crossZ * normalZ > 0f, "triangle $triangle faces inward")
        }
    }

    @Test
    fun sideFacesCloseEveryStepAcrossBandEdges() {
        // Sum the exposed side area along the LOD0/LOD1 edge at z=-96 and compare with the height steps.
        val builder = SceneMeshBuilder()
        buildTerrainMesh(builder, grid)
        val mesh = builder.build(terrainShadowBounds(grid))
        val edgeZ = -96f
        var exposed = 0.0
        for (index in 0 until mesh.vertices.size / SCENE_VERTEX_FLOATS step 4) {
            val offset = index * SCENE_VERTEX_FLOATS
            val normalZ = mesh.vertices[offset + 5]
            if (abs(normalZ) != 1f) continue
            val z0 = mesh.vertices[offset + 2]
            if (abs(z0 - edgeZ) > 1e-4f) continue
            val xs = (0 until 4).map { mesh.vertices[offset + it * SCENE_VERTEX_FLOATS] }
            val ys = (0 until 4).map { mesh.vertices[offset + it * SCENE_VERTEX_FLOATS + 1] }
            val minX = xs.min()
            val maxX = xs.max()
            if (maxX <= -96f || minX >= 96f) continue
            exposed += (maxX - minX) * (ys.max() - ys.min()).toDouble()
        }
        var expected = 0.0
        for (x in -96 until 96) {
            val inside = grid.heightAt(x, -96)
            val outside = grid.heightAt(x, -97)
            // The higher column owns the face: the LOD0 cell, or the 2-block LOD1 cell across the edge.
            val visible = if (inside > outside) terrainCellVisible(x, -96, 1) else terrainCellVisible(x - Math.floorMod(x, 2), -98, 2)
            if (visible) expected += abs(inside - outside).toDouble()
        }
        assertEquals(expected, exposed, absoluteTolerance = 1e-3)
    }

    @Test
    fun shadowBoundsFrameTheNearValleyAndReachTowardTheSun() {
        val bounds = terrainShadowBounds(grid)
        assertTrue(bounds.contains(PLAYER_X, PLAYER_FEET_Y, PLAYER_Z))
        assertTrue(bounds.contains(70f, WATER_LEVEL, -122f))
        assertTrue(bounds.maxX > 250f, "sun-side casters")
        assertTrue(bounds.minY < WATER_LEVEL && bounds.maxY > HILL_TOP_Y)
        val frame = buildStaticShadowFrame(listOf(bounds), BackgroundScene.sun(), lightDistance = 400f)
        bounds.corners().forEach { corner ->
            val clip = org.joml.Vector4f(corner, 1f).mul(frame.viewProjection)
            assertTrue(clip.x / clip.w in -1f..1f && clip.y / clip.w in -1f..1f && clip.z / clip.w in -1f..1f)
        }
        val texelSize = sqrt(((frame.maxLightX - frame.minLightX) * (frame.maxLightY - frame.minLightY)).toDouble()) / STATIC_SHADOW_MAP_SIZE
        assertTrue(texelSize < 0.25, "shadow texel is $texelSize blocks")
    }
}
