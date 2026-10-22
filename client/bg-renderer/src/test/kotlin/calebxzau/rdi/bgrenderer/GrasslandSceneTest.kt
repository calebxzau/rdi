package calebxzau.rdi.bgrenderer

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GrasslandSceneTest {
    @Test
    fun fiftyRunnersStayInsideFieldAndMoveAtBoundedSpeed() {
        val simulation = GrasslandSimulation(43)
        simulation.setPlayers(List(50) { "skin-$it" })
        val initial = simulation.runners.map { it.x to it.z }
        repeat(2400) {
            val before = simulation.runners.map { it.x to it.z }
            simulation.advance(0.05)
            simulation.runners.forEachIndexed { index, runner ->
                assertTrue(runner.x in CROWD_MIN_X..CROWD_MAX_X)
                assertTrue(runner.z in CROWD_MIN_Z..CROWD_MAX_Z)
                assertTrue(hypot(runner.x - before[index].first, runner.z - before[index].second) <= runner.speed * 0.05f + 0.001f)
                assertTrue(runner.yaw.isFinite() && runner.phase.isFinite())
            }
        }
        assertNotEquals(initial, simulation.runners.map { it.x to it.z })
    }

    @Test
    fun loadingAnotherSkinPreservesExistingRunnerAndCapsPopulation() {
        val simulation = GrasslandSimulation(1)
        simulation.setPlayers(listOf("first"))
        val first = simulation.runners.first()
        simulation.advance(0.05)
        val position = first.x to first.z
        simulation.setPlayers(listOf("first", "first") + List(80) { "skin-$it" })
        assertEquals(50, simulation.runners.size)
        assertSame(first, simulation.runners.first())
        assertEquals(position, first.x to first.z)
        simulation.setPlayers(listOf("replacement"))
        assertEquals(listOf("replacement"), simulation.runners.map { it.id })
    }

    @Test
    fun pauseDoesNotMoveAndLongGapDoesNotTeleport() {
        val simulation = GrasslandSimulation(2)
        simulation.setPlayers(listOf("skin"))
        val runner = simulation.runners.single()
        val before = runner.x to runner.z
        repeat(100) { simulation.advance(0.0) }
        assertEquals(before, runner.x to runner.z)
        simulation.advance(3600.0)
        assertTrue(hypot(runner.x - before.first, runner.z - before.second) <= runner.speed * 0.1f + 0.001f)
    }

    @Test
    fun simulationIsRepeatableAndIndependentOfRenderRate() {
        val slow = GrasslandSimulation(9)
        val fast = GrasslandSimulation(9)
        val ids = List(12) { "skin-$it" }
        slow.setPlayers(ids)
        fast.setPlayers(ids)
        repeat(200) { slow.advance(0.05) }
        repeat(600) { fast.advance(1.0 / 60.0) }
        slow.runners.zip(fast.runners).forEach { (a, b) ->
            assertTrue(abs(a.x - b.x) < 0.0001f)
            assertTrue(abs(a.z - b.z) < 0.0001f)
        }
    }

    @Test
    fun nearbyRunnersSteerApart() {
        val simulation = GrasslandSimulation(1)
        simulation.setPlayers(listOf("a", "b"))
        val (a, b) = simulation.runners.toList()
        a.x = -0.3f; b.x = 0.3f
        a.z = -10f; b.z = -10f
        a.yaw = 0f; b.yaw = 0f
        a.targetX = 0f; b.targetX = 0f
        a.targetZ = 0f; b.targetZ = 0f
        repeat(20) { simulation.advance(0.05) }
        assertTrue(hypot(a.x - b.x, a.z - b.z) > 1f)
    }

    @Test
    fun meadowHasNoWaterAndGroundFacesPointUp() {
        val mesh = buildGrasslandMesh()
        assertEquals(0, mesh.batches.single { it.material == BlockMaterial.WATER }.indexCount)
        assertTrue(mesh.vertices.size / SCENE_VERTEX_FLOATS < 150_000)
        val ground = mesh.batches.single { it.material == BlockMaterial.GRASS_TOP }
        for (offset in ground.firstIndex until ground.firstIndex + ground.indexCount step 3) {
            val a = mesh.indices[offset] * SCENE_VERTEX_FLOATS
            val b = mesh.indices[offset + 1] * SCENE_VERTEX_FLOATS
            val c = mesh.indices[offset + 2] * SCENE_VERTEX_FLOATS
            val up = (mesh.vertices[b + 2] - mesh.vertices[a + 2]) * (mesh.vertices[c] - mesh.vertices[a]) -
                (mesh.vertices[b] - mesh.vertices[a]) * (mesh.vertices[c + 2] - mesh.vertices[a + 2])
            assertTrue(up > 0)
            assertEquals(GRASSLAND_Y, mesh.vertices[a + 1])
        }
    }
}
