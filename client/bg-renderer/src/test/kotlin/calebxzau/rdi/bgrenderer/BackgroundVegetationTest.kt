package calebxzau.rdi.bgrenderer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundVegetationTest {
    private val grid by lazy { TerrainGrid() }
    private val trees by lazy { placeTrees(grid) }

    @Test
    fun forestIsDeterministicAndOnlyGrowsOnDryGentleLand() {
        assertEquals(trees, placeTrees(grid))
        assertTrue(trees.size in 4_000..20_000, "tree count ${trees.size}")
        trees.forEach { tree ->
            assertEquals(grid.heightAt(tree.x, tree.z), tree.baseY)
            assertTrue(tree.baseY >= WATER_LEVEL + 1f, "$tree")
            // The tree line belongs to the range beyond the lake; the near high ground is wooded.
            if (tree.z < HIGH_RANGE_START_Z) assertTrue(tree.baseY <= TREE_LINE, "$tree")
            assertTrue(terrainCellVisible(tree.x, tree.z, 1))
            val dx = tree.x + 0.5f - PLAYER_X
            val dz = tree.z + 0.5f - PLAYER_Z
            assertTrue(dx * dx + dz * dz >= SUMMIT_CLEARING_RADIUS * SUMMIT_CLEARING_RADIUS, "tree crowds the summit: $tree")
            assertTrue(!nearVillage(tree.x, tree.z, 4) && !inVillageMeadow(tree.x, tree.z), "tree in the hamlet: $tree")
            assertTrue(lakeDistance(tree.x + 0.5, tree.z + 0.5) >= LAKE_SHORE_CLEARANCE, "tree on the lake shore: $tree")
            assertTrue(tree.tint in 0.88f..1.1f)
        }
        assertTrue(trees.map { it.x to it.z }.toSet().size == trees.size, "two trees share a column")
    }

    @Test
    fun speciesFollowAltitudeAndTrunksFollowSpecies() {
        trees.forEach { tree ->
            // Spruce beyond the lake from 34 (mixed from 26); leafy everywhere nearer, however high.
            val highland = tree.z < HIGH_RANGE_START_Z
            when {
                highland && tree.baseY >= 34 -> assertEquals(TreeSpecies.Spruce, tree.species, "$tree")
                !highland || tree.baseY < 26 -> assertTrue(tree.species != TreeSpecies.Spruce, "$tree")
            }
            val range = when (tree.species) {
                TreeSpecies.Oak -> 4..6
                TreeSpecies.Birch -> 5..7
                TreeSpecies.Spruce -> 6..9
            }
            assertTrue(tree.trunkHeight in range, "$tree")
        }
        TreeSpecies.entries.forEach { species -> assertTrue(trees.any { it.species == species }, "no $species") }
        val camera = BackgroundScene.camera(0.0)
        trees.forEach { tree ->
            val dx = tree.x + 0.5f - camera.eyeX
            val dz = tree.z + 0.5f - camera.eyeZ
            assertEquals(sqrt(dx * dx + dz * dz) < TREE_DETAIL_DISTANCE, tree.detailed)
            assertEquals(sqrt(dx * dx + dz * dz) >= TREE_DISTANT_DISTANCE, tree.distant)
        }
        assertTrue(trees.count { it.detailed } < trees.size / 5, "most trees should be the cheap far shapes")
    }

    @Test
    fun noCanopyBlocksTheViewOfThePlayer() {
        // Conservative canopy bounds: radius 2 around the trunk, up to two blocks above it.
        val boxes = trees.map { tree ->
            StaticAabb(
                tree.x - 2f, tree.baseY.toFloat(), tree.z - 2f,
                tree.x + 3f, tree.baseY + tree.trunkHeight + 2f, tree.z + 3f
            )
        }
        listOf(0.0, CAMERA_CYCLE_SECONDS / 4.0, CAMERA_CYCLE_SECONDS * 3.0 / 4.0).forEach { time ->
            val camera = BackgroundScene.camera(time)
            listOf(0f, 0.9f, 1.8f).forEach { height ->
                val targetY = PLAYER_FEET_Y + height
                boxes.forEach { box ->
                    assertTrue(
                        !segmentHitsBox(camera.eyeX, camera.eyeY, camera.eyeZ, PLAYER_X, targetY, PLAYER_Z, box),
                        "canopy $box hides the player at t=$time"
                    )
                }
            }
        }
    }

    private fun segmentHitsBox(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, box: StaticAabb): Boolean {
        var enter = 0f
        var exit = 1f
        val starts = floatArrayOf(ax, ay, az)
        val deltas = floatArrayOf(bx - ax, by - ay, bz - az)
        val mins = floatArrayOf(box.minX, box.minY, box.minZ)
        val maxs = floatArrayOf(box.maxX, box.maxY, box.maxZ)
        for (axis in 0 until 3) {
            if (kotlin.math.abs(deltas[axis]) < 1e-6f) {
                if (starts[axis] < mins[axis] || starts[axis] > maxs[axis]) return false
            } else {
                val t0 = (mins[axis] - starts[axis]) / deltas[axis]
                val t1 = (maxs[axis] - starts[axis]) / deltas[axis]
                enter = max(enter, min(t0, t1))
                exit = min(exit, max(t0, t1))
                if (enter > exit) return false
            }
        }
        return true
    }

    @Test
    fun treeShapesUseTheSpeciesMaterialsAndCheapFarForms() {
        fun quads(tree: TreeInstance): Map<BlockMaterial, Int> {
            val builder = SceneMeshBuilder()
            emitTree(builder, tree)
            return BlockMaterial.entries.associateWith { builder.vertexCount(it) / 4 }.filterValues { it > 0 }
        }
        // Near oaks: a trimmed 5x5 cross (two boxes), a 3x3 layer and a plus on top, every face kept.
        val oak = TreeInstance(0, 0, 10, TreeSpecies.Oak, 5, detailed = true, tint = 1f)
        assertEquals(mapOf(BlockMaterial.OAK_LOG to 4, BlockMaterial.OAK_LEAVES to 30), quads(oak))
        // Far oaks: one cross without the faces the camera never sees (-Z, the underside, the far x side).
        assertEquals(mapOf(BlockMaterial.OAK_LOG to 3, BlockMaterial.OAK_LEAVES to 8), quads(oak.copy(detailed = false)))
        assertEquals(mapOf(BlockMaterial.OAK_LOG to 2, BlockMaterial.OAK_LEAVES to 6), quads(oak.copy(x = 100, detailed = false)))
        assertEquals(mapOf(BlockMaterial.OAK_LOG to 2, BlockMaterial.OAK_LEAVES to 6), quads(oak.copy(x = -100, detailed = false)))
        val birch = oak.copy(species = TreeSpecies.Birch)
        assertEquals(setOf(BlockMaterial.BIRCH_LOG, BlockMaterial.BIRCH_LEAVES), quads(birch).keys)
        val spruce = TreeInstance(0, 0, 40, TreeSpecies.Spruce, 8, detailed = true, tint = 1f)
        val spruceQuads = quads(spruce)
        assertEquals(4, spruceQuads[BlockMaterial.SPRUCE_LOG])
        // Rings from two blocks above the ground up to the trunk top, plus the tip.
        assertEquals((spruce.trunkHeight - 2 + 1) * 6, spruceQuads[BlockMaterial.SPRUCE_LEAVES])
        // Distant trees: one canopy box without a trunk, minus the faces the camera never sees.
        assertEquals(mapOf(BlockMaterial.OAK_LEAVES to 4), quads(oak.copy(detailed = false, distant = true)))
        assertEquals(mapOf(BlockMaterial.OAK_LEAVES to 3), quads(oak.copy(x = 100, detailed = false, distant = true)))
        assertEquals(mapOf(BlockMaterial.SPRUCE_LEAVES to 4), quads(spruce.copy(detailed = false, distant = true)))
        // Far spruces: a narrow body under a thin tip; undersides stay only above eye height.
        assertEquals(mapOf(BlockMaterial.SPRUCE_LOG to 3, BlockMaterial.SPRUCE_LEAVES to 8), quads(spruce.copy(detailed = false)))
        assertEquals(
            mapOf(BlockMaterial.SPRUCE_LOG to 3, BlockMaterial.SPRUCE_LEAVES to 10),
            quads(spruce.copy(baseY = PLAYER_FEET_Y.toInt() + 20, detailed = false))
        )
        // Every canopy covers the trunk top, so the log needs no top face.
        val builder = SceneMeshBuilder()
        emitTree(builder, oak)
        val mesh = builder.build(StaticAabb(0f, 0f, 0f, 1f, 1f, 1f))
        val logBatch = mesh.batches.single { it.material == BlockMaterial.OAK_LOG }
        val logNormalsY = (logBatch.firstIndex until logBatch.firstIndex + logBatch.indexCount)
            .map { mesh.vertices[mesh.indices[it] * SCENE_VERTEX_FLOATS + 4] }
        assertTrue(logNormalsY.all { it == 0f })
    }

    @Test
    fun farTreesOnlyDropFacesTheCameraNeverSees() {
        val eyes = (0 until 24).map { BackgroundScene.camera(CAMERA_CYCLE_SECONDS * it / 24.0) }
        trees.filter { !it.detailed }.forEach { tree ->
            val canopy = StaticAabb(
                tree.x - 2f, tree.baseY.toFloat(), tree.z - 2f,
                tree.x + 3f, tree.baseY + tree.trunkHeight + 3f, tree.z + 3f
            )
            farTreeHiddenFaces(canopy).forEach { face ->
                eyes.forEach { eye ->
                    // A face is seen only from in front of its plane; mirroring across the water keeps x and z.
                    val inFront = when (face) {
                        StaticFaceDirection.PositiveX -> eye.eyeX > canopy.maxX
                        StaticFaceDirection.NegativeX -> eye.eyeX < canopy.minX
                        StaticFaceDirection.NegativeZ -> eye.eyeZ < canopy.minZ
                        StaticFaceDirection.NegativeY -> eye.eyeY < canopy.minY
                        else -> true
                    }
                    assertTrue(!inFront, "$face of $tree faces the camera at $eye")
                }
            }
        }
    }

    @Test
    fun theLeftWoodsAreOneContinuousForest() {
        val cells = HashSet<Pair<Int, Int>>()
        for (x in -140 until -36 step TREE_SPACING) for (z in -128 until -48 step TREE_SPACING) {
            if (leftWoodsDistance(x + 2.5, z + 2.5) < 0.7 && terrainCellVisible(x, z, 1)) cells += x to z
        }
        val wooded = cells.count { (x, z) -> trees.any { it.x in x until x + TREE_SPACING && it.z in z until z + TREE_SPACING } }
        assertTrue(cells.size > 50, "left woods cells: ${cells.size}")
        // Every cell keeps its tree except the near-tree thinning (and the 5% skip every cell has).
        assertTrue(wooded > cells.size / 2, "wooded cells: $wooded of ${cells.size}")
        assertTrue(trees.filter { leftWoodsDistance(it.x + 0.5, it.z + 0.5) < 1.0 }.all { it.species != TreeSpecies.Spruce })
    }

    @Test
    fun theLakeShowsOverTheForest() {
        val camera = BackgroundScene.camera(0.0)
        val canopies = trees.map { tree ->
            StaticAabb(
                tree.x - 2f, tree.baseY.toFloat(), tree.z - 2f,
                tree.x + 3f, tree.baseY + tree.trunkHeight + 2f, tree.z + 3f
            )
        }
        var water = 0
        var visible = 0
        for (x in -40 until 200 step 4) for (z in -210 until -128 step 4) {
            if (grid.heightAt(x, z) >= WATER_LEVEL) continue
            water++
            val tx = x + 0.5f
            val tz = z + 0.5f
            val terrainClear = (1 until 200).all { step ->
                val t = step / 200f
                grid.heightAtPoint(camera.eyeX + (tx - camera.eyeX) * t, camera.eyeZ + (tz - camera.eyeZ) * t) <=
                    camera.eyeY + (WATER_LEVEL - camera.eyeY) * t
            }
            if (terrainClear && canopies.none { segmentHitsBox(camera.eyeX, camera.eyeY, camera.eyeZ, tx, WATER_LEVEL, tz, it) }) visible++
        }
        assertTrue(water > 300, "lake columns: $water")
        assertTrue(visible > water / 4, "visible lake columns: $visible of $water")
    }

    @Test
    fun grassTuftsDecorateOnlyTheSummitAndUpperFlanks() {
        val tufts = placeGrassTufts(grid)
        assertEquals(tufts, placeGrassTufts(grid))
        assertTrue(tufts.size in 60..400, "tuft count ${tufts.size}")
        tufts.forEach { tuft ->
            val columnX = kotlin.math.floor(tuft.x).toInt()
            val columnZ = kotlin.math.floor(tuft.z).toInt()
            assertEquals(grid.heightAt(columnX, columnZ).toFloat(), tuft.y)
            assertTrue(tuft.y >= HILL_TOP_Y - 12f && tuft.y <= HILL_TOP_Y && tuft.size in 0.6f..1.0f, "$tuft")
            assertTrue(SUMMIT_BOULDERS.none { it.x == columnX && it.z == columnZ }, "tuft on a boulder: $tuft")
            assertTrue(tuft.x - columnX in 0.3f..0.7f && tuft.z - columnZ in 0.3f..0.7f)
            assertTrue(!(columnX == 0 && columnZ == 0), "tuft on the player's column")
        }
        val builder = SceneMeshBuilder()
        emitGrassTuft(builder, tufts.first())
        val mesh = builder.build(StaticAabb(0f, 0f, 0f, 1f, 1f, 1f))
        assertEquals(8, builder.vertexCount(BlockMaterial.SHORT_GRASS))
        (0 until 8).forEach { vertex ->
            val offset = vertex * SCENE_VERTEX_FLOATS
            val kind = mesh.vertices[offset + 9]
            val y = mesh.vertices[offset + 1]
            // Bottom corners stay rooted; only the tips sway.
            assertEquals(if (y > tufts.first().y) FOLIAGE_SWAY_KIND.toFloat() else 0f, kind)
            assertEquals(1f, mesh.vertices[offset + 4], "plants keep an upward normal")
            assertTrue(mesh.vertices[offset + 10] in 0f..1f && mesh.vertices[offset + 11] in 0f..1f)
        }
    }

    @Test
    fun summitBouldersRestOnTheTerracesBesideThePlayer() {
        val camera = BackgroundScene.camera(0.0)
        SUMMIT_BOULDERS.forEach { boulder ->
            val y = grid.heightAt(boulder.x, boulder.z).toFloat()
            assertTrue(y in (HILL_TOP_Y - 6f)..HILL_TOP_Y.toFloat(), "$boulder rests at $y")
            assertTrue(kotlin.math.abs(boulder.x) >= 2, "$boulder crowds the player")
            val box = StaticAabb(boulder.x.toFloat(), y, boulder.z.toFloat(), boulder.x + 1f, y + 1f, boulder.z + 1f)
            assertTrue(!segmentHitsBox(camera.eyeX, camera.eyeY, camera.eyeZ, PLAYER_X, PLAYER_FEET_Y, PLAYER_Z, box))
        }
        val builder = SceneMeshBuilder()
        emitSummitBoulders(builder, grid)
        // Five faces each: the underside rests on the ground.
        assertEquals(5 * 4, builder.vertexCount(BlockMaterial.COBBLESTONE))
    }

    @Test
    fun villageHousesStandOnFlatPadsFacingTheCamera() {
        assertEquals(VILLAGE_HOUSES.size, VILLAGE_PAD_HEIGHTS.size)
        VILLAGE_HOUSES.forEachIndexed { index, house ->
            val pad = VILLAGE_PAD_HEIGHTS[index]
            assertTrue(pad >= 4, "pad $pad must stay dry")
            assertEquals(0, grid.bandIndexAt(house.minX, house.minZ), "houses use full-detail terrain")
            for (x in house.minX - 1..house.maxX) for (z in house.minZ - 1..house.maxZ) {
                assertEquals(pad, grid.heightAt(x, z), "column ($x, $z) of house $index")
            }
            val blocks = houseBlocks(house, pad)
            val windows = blocks.filter { it.material == BlockMaterial.GLOWSTONE }
            assertEquals(4, windows.size)
            assertTrue(windows.any { it.box.maxZ > house.maxZ }, "a lit window faces the camera")
            assertTrue(blocks.any { it.material == BlockMaterial.SPRUCE_PLANKS && it.box.maxZ > house.maxZ && it.box.maxY - it.box.minY == 2f }, "door")
            assertEquals(1, blocks.count { it.material == BlockMaterial.LANTERN })
            assertEquals(pad.toFloat(), blocks.single { it.material == BlockMaterial.COBBLESTONE }.box.minY)
            val roof = blocks.filter { it.material == BlockMaterial.SPRUCE_PLANKS && it.box.minX < house.minX }
            assertTrue(roof.size >= 3)
            roof.zipWithNext().forEach { (lower, upper) ->
                assertEquals(lower.box.maxY, upper.box.minY)
                assertTrue(upper.box.maxZ - upper.box.minZ < lower.box.maxZ - lower.box.minZ)
            }
        }
        // Houses never overlap one another.
        VILLAGE_HOUSES.forEachIndexed { i, a ->
            VILLAGE_HOUSES.drop(i + 1).forEach { b ->
                assertTrue(a.maxX + 1 <= b.minX || b.maxX + 1 <= a.minX || a.maxZ + 1 <= b.minZ || b.maxZ + 1 <= a.minZ, "$a overlaps $b")
            }
        }
        val builder = SceneMeshBuilder()
        assertTrue(buildVillageMesh(builder) > 0)
        assertTrue(builder.vertexCount(BlockMaterial.GLOWSTONE) > 0)
    }
}
