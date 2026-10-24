package calebxzau.rdi.bgrenderer

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HillCrowdSimulationTest {
    private val route = HillCrowdRoute()

    @Test
    fun routeClimbsLeftCrossesSummitAndDescendsRightWithoutCrossingBouldersOrAccountPlayer() {
        assertTrue(route.nodes.first().x < -15 && route.nodes.last().x > 15)
        assertTrue(route.nodes.first().height < HILL_TOP_Y - 10)
        assertTrue(route.nodes.last().height < HILL_TOP_Y - 10)
        assertEquals(HILL_TOP_Y.toFloat(), route.nodes.maxOf { it.height })
        route.nodes.zipWithNext().forEach { (a, b) ->
            assertEquals(1, abs(b.x - a.x) + abs(b.z - a.z))
            assertTrue(abs(b.height - a.height) <= 2f)
        }
        route.nodes.forEach { node ->
            assertTrue(SUMMIT_BOULDERS.none { it.x == node.x && it.z == node.z })
            assertTrue(hypot(node.x + 0.5f - PLAYER_X, node.z + 0.5f - PLAYER_Z) >= 1f)
        }
        val lap = route.nodes.subList(route.summitStartIndex, route.summitLapEndIndex + 1)
        assertEquals(lap.first(), lap.last())
        assertTrue(lap.zipWithNext().any { (a, b) -> b.x < a.x && a.z == 3 })
        assertTrue(lap.zipWithNext().any { (a, b) -> b.z < a.z && a.x == -3 })
        assertTrue(route.descentStartIndex > route.summitLapEndIndex)
    }

    @Test
    fun slopesAreOnePointFiveTimesFasterAndSummitKeepsOriginalSpeed() {
        var seconds = 0.0
        for (index in 0 until route.nodes.lastIndex) {
            val expectedSpeed = if (index in route.summitStartIndex until route.descentStartIndex) 1.6 else 2.4
            assertEquals(expectedSpeed, route.segmentSpeed(index), 1e-12)
            val duration = 1.0 / expectedSpeed
            assertEquals(index + 0.5, route.distanceAtTime(seconds + duration * 0.5), 1e-9)
            seconds += duration
        }
        assertEquals(route.travelSeconds, seconds, 1e-9)
    }

    @Test
    fun summitLapAndExitCrossingStayOnTopTerraceIncludingBothFeet() {
        val runner = HillRunner("summit", 0)
        for (index in route.summitStartIndex..route.descentStartIndex) {
            val node = route.nodes[index]
            assertEquals(HILL_TOP_Y.toFloat(), node.height, "Summit node $index: $node")
        }
        // Check the whole move, including the inset corners, rather than only waypoint heights.
        for (sample in route.summitStartIndex * 100..route.descentStartIndex * 100) {
            route.pose(sample / 100.0, runner)
            assertEquals(HILL_TOP_Y.toFloat(), runner.shadowY, "Shadow at $sample")
            for (dx in listOf(-0.4f, 0.4f)) for (dz in listOf(-0.4f, 0.4f)) {
                val x = floor((runner.x + dx).toDouble()) + 0.5
                val z = floor((runner.z + dz).toDouble()) + 0.5
                assertEquals(HILL_TOP_Y, terrainCellHeight(x, z, 1), "Foot at $sample: ($x, $z)")
            }
        }
    }

    @Test
    fun bothFeetClearRenderedTerrainThroughoutEveryStep() {
        val runner = HillRunner("skin", 0)
        repeat((route.length * 100).toInt()) { index ->
            route.pose(index / 100.0, runner)
            for (dx in listOf(-0.4f, 0.4f)) for (dz in listOf(-0.4f, 0.4f)) {
                val height = terrainCellHeight(floor((runner.x + dx).toDouble()) + 0.5, floor((runner.z + dz).toDouble()) + 0.5, 1)
                assertTrue(runner.y + 0.0001f >= height, "Feet intersect terrain at $index: ${runner.x}, ${runner.y}, ${runner.z} vs $height")
            }
            assertEquals(terrainCellHeight(runner.shadowX.toDouble(), runner.shadowZ.toDouble(), 1).toFloat(), runner.shadowY)
        }
    }

    @Test
    fun loopEndpointsStayOffscreenThroughoutCameraOrbit() {
        val projection = Matrix4f().perspective(Math.toRadians(CAMERA_FOV_DEGREES.toDouble()).toFloat(), RENDER_WIDTH.toFloat() / RENDER_HEIGHT, CAMERA_NEAR_PLANE, CAMERA_FAR_PLANE)
        repeat(121) { frame ->
            val camera = BackgroundScene.camera(frame * CAMERA_CYCLE_SECONDS / 120.0)
            val view = Matrix4f().lookAt(camera.eyeX, camera.eyeY, camera.eyeZ, camera.targetX, camera.targetY, camera.targetZ, 0f, 1f, 0f)
            val transform = Matrix4f(projection).mul(view)
            for (node in listOf(route.nodes.first(), route.nodes.last())) {
                for (dx in listOf(-0.6f, 0.6f)) for (height in listOf(0f, 2f)) {
                    val projected = transform.transformProject(Vector3f(node.x + 0.5f + dx, node.height + height, node.z + 0.5f))
                    assertTrue(abs(projected.x) > 1.05f || abs(projected.y) > 1.05f)
                }
            }
        }
    }

    @Test
    fun arrivalsKeepExistingPositionsAndUniqueSlotsWithPopulationLimit() {
        val simulation = HillCrowdSimulation(route)
        simulation.setPlayers(listOf("first"))
        simulation.advance(0.05)
        val first = simulation.runners.single()
        val before = listOf(first.x, first.y, first.z, first.phase)
        simulation.setPlayers(listOf("first", "first") + List(80) { "skin-$it" })
        assertSame(first, simulation.runners.first())
        assertEquals(before, listOf(first.x, first.y, first.z, first.phase))
        assertEquals(50, simulation.runners.size)
        assertEquals(50, simulation.runners.map { it.slot }.distinct().size)
        simulation.setPlayers(listOf("replacement", "first"))
        assertSame(first, simulation.runners.first())
        assertEquals(2, simulation.runners.size)
        assertEquals(2, simulation.runners.map { it.slot }.distinct().size)
    }

    @Test
    fun pauseInvalidTimeAndLongGapCannotAdvanceByUnboundedAmount() {
        val simulation = HillCrowdSimulation(route)
        val reference = HillCrowdSimulation(route)
        simulation.setPlayers(listOf("skin"))
        reference.setPlayers(listOf("skin"))
        val runner = simulation.runners.single()
        val before = listOf(runner.x, runner.y, runner.z, runner.phase)
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach(simulation::advance)
        assertEquals(before, listOf(runner.x, runner.y, runner.z, runner.phase))
        simulation.advance(3600.0)
        reference.advance(0.1)
        assertEquals(reference.runners.single().x, runner.x)
        assertEquals(reference.runners.single().y, runner.y)
        assertEquals(reference.runners.single().z, runner.z)
    }

    @Test
    fun fiftyPlayersKeepTheirSpacingAcrossMultipleLoopsAndRenderRates() {
        val slow = HillCrowdSimulation(route)
        val fast = HillCrowdSimulation(route)
        val ids = List(50) { "skin-$it" }
        slow.setPlayers(ids)
        fast.setPlayers(ids)
        repeat(4500) {
            slow.advance(0.05)
            repeat(3) { fast.advance(1.0 / 60.0) }
            slow.runners.zip(fast.runners).forEach { (a, b) ->
                assertEquals(a.visible, b.visible)
                if (!a.visible) return@forEach
                assertTrue(abs(a.x - b.x) < 0.0001f && abs(a.y - b.y) < 0.0001f && abs(a.z - b.z) < 0.0001f,
                    "Frame $it actor ${a.id}: (${a.x}, ${a.y}, ${a.z}) vs (${b.x}, ${b.y}, ${b.z})")
                assertTrue(a.yaw.isFinite() && a.phase.isFinite())
            }
            val runners = slow.runners.filter { it.visible }
            for (a in runners.indices) for (b in a + 1 until runners.size) {
                val first = runners[a]
                val second = runners[b]
                val distanceSquared = (first.x - second.x) * (first.x - second.x) + (first.y - second.y) * (first.y - second.y) + (first.z - second.z) * (first.z - second.z)
                assertTrue(distanceSquared > 0.36f, "Frame $it: ${first.id} (${first.x}, ${first.y}, ${first.z}) and ${second.id} (${second.x}, ${second.y}, ${second.z}) bunched together")
            }
        }
    }
}
