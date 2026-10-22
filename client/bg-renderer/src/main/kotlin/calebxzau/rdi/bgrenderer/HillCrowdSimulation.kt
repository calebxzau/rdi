package calebxzau.rdi.bgrenderer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin

internal data class HillPathNode(val x: Int, val z: Int, val height: Float)

internal const val HILL_SUMMIT_SPEED = 1.6
internal const val HILL_SLOPE_SPEED = HILL_SUMMIT_SPEED * 1.5

/** Cell-center steps follow the rendered one-block terrain, avoiding the summit's two loose blocks. */
internal class HillCrowdRoute {
    var summitStartIndex = 0
        private set
    var summitLapEndIndex = 0
        private set
    var descentStartIndex = 0
        private set
    val nodes: List<HillPathNode> = buildList {
        // Back edge -> right edge -> front edge -> left edge, then cross the back again to exit.
        // The two visits to the shared back edge are interleaved by the queue's headway below.
        val waypoints = listOf(-24 to 12, -7 to 0, -3 to -1, 3 to -1, 3 to 3, -3 to 3, -3 to -1, 3 to -1, 8 to 3, 24 to 12)
        fun append(x: Int, z: Int) {
            check(SUMMIT_BOULDERS.none { it.x == x && it.z == z }) { "Hill route crosses a summit boulder" }
            add(HillPathNode(x, z, terrainCellHeight(x + 0.5, z + 0.5, 1).toFloat()))
        }
        append(waypoints.first().first, waypoints.first().second)
        for ((leg, endpoints) in waypoints.zipWithNext().withIndex()) {
            val (start, end) = endpoints
            var x = start.first
            var z = start.second
            val dx = abs(end.first - x)
            val dz = abs(end.second - z)
            var movedX = 0
            var movedZ = 0
            // Interleave cardinal steps: never cut diagonally through a higher neighboring column.
            while (x != end.first || z != end.second) {
                if (x != end.first && (z == end.second || (movedX + 0.5) * dz <= (movedZ + 0.5) * dx)) {
                    x += if (end.first > x) 1 else -1
                    movedX++
                } else {
                    z += if (end.second > z) 1 else -1
                    movedZ++
                }
                append(x, z)
            }
            when (leg) {
                1 -> summitStartIndex = lastIndex
                5 -> summitLapEndIndex = lastIndex
                6 -> descentStartIndex = lastIndex
            }
        }
    }
    val length: Double = (nodes.size - 1).toDouble()
    private val arrivalSeconds = DoubleArray(nodes.size).also { times ->
        for (index in 0 until nodes.lastIndex) times[index + 1] = times[index] + 1.0 / segmentSpeed(index)
    }
    val travelSeconds: Double = arrivalSeconds.last()
    private val lapSeconds = arrivalSeconds[summitLapEndIndex] - arrivalSeconds[summitStartIndex]
    // A full lap takes an integer plus half a queue interval: the second crossing falls between
    // newcomers, including at the merge/split corners. Extra queue time is spent offscreen.
    val headwaySeconds: Double = lapSeconds / (floor(lapSeconds / 1.8) + 0.5)
    val cycleSeconds: Double = maxOf(MAX_BACKGROUND_PLAYERS.toDouble(), ceil(travelSeconds / headwaySeconds)) * headwaySeconds
    val initialSlot: Int = ceil(arrivalSeconds[summitStartIndex] / headwaySeconds).toInt()

    fun segmentSpeed(index: Int): Double = if (index in summitStartIndex until descentStartIndex) HILL_SUMMIT_SPEED else HILL_SLOPE_SPEED

    fun wrapTime(seconds: Double): Double {
        val wrapped = seconds % cycleSeconds
        return when {
            wrapped < 1e-9 || cycleSeconds - wrapped < 1e-9 -> 0.0
            abs(wrapped - travelSeconds) < 1e-9 -> travelSeconds
            else -> wrapped
        }
    }

    fun distanceAtTime(seconds: Double): Double {
        val time = seconds.coerceIn(0.0, travelSeconds)
        val found = arrivalSeconds.binarySearch(time)
        if (found >= 0) return found.toDouble()
        val index = (-found - 2).coerceIn(0, nodes.lastIndex - 1)
        return index + (time - arrivalSeconds[index]) * segmentSpeed(index)
    }

    fun wrapDistance(distance: Double): Double {
        val remainder = ((distance % length) + length) % length
        // Equal elapsed time split into different frame sizes can land either side of an integer.
        return if (abs(remainder - round(remainder)) < 1e-9) round(remainder) % length else remainder
    }

    fun pose(distance: Double, runner: HillRunner) {
        val wrapped = wrapDistance(distance)
        val index = floor(wrapped).toInt()
        val t = (wrapped - index).toFloat()
        val a = nodes[index]
        val b = nodes[index + 1]
        val rise = b.height - a.height
        // Lift before crossing the riser; on descent, clear the edge before dropping.
        // Both feet stay above the full column throughout the move, even with a wide stance.
        val travel = when {
            rise > 0f -> smooth((t - 0.3f) / 0.7f)
            rise < 0f -> smooth(t / 0.7f)
            else -> t
        }
        val lift = when {
            rise > 0f -> smooth(t / 0.3f)
            rise < 0f -> smooth((t - 0.7f) / 0.3f)
            else -> t
        }
        runner.x = a.x + 0.5f + (b.x - a.x) * travel
        runner.z = a.z + 0.5f + (b.z - a.z) * travel
        runner.y = a.height + rise * lift + abs(sin(runner.phase)) * 0.06f +
            if (rise != 0f) sin(t * PI.toFloat()) * 0.12f else 0f
        val currentYaw = heading(index)
        // Begin turning before a corner and finish just after it, rather than snapping 90 degrees.
        runner.yaw = if (t < 0.5f) {
            val previous = heading((index - 1).coerceAtLeast(0))
            currentYaw + angleDelta(currentYaw, previous) * (0.5f - t)
        } else {
            val next = heading((index + 1).coerceAtMost(nodes.lastIndex - 1))
            currentYaw + angleDelta(currentYaw, next) * (t - 0.5f)
        }
        runner.shadowX = floor(runner.x) + 0.5f
        runner.shadowZ = floor(runner.z) + 0.5f
        runner.shadowY = if (floor(runner.x).toInt() == a.x && floor(runner.z).toInt() == a.z) a.height else b.height
    }

    private fun heading(index: Int): Float = atan2(
        (nodes[index + 1].x - nodes[index].x).toFloat(),
        (nodes[index + 1].z - nodes[index].z).toFloat()
    )

    private fun angleDelta(from: Float, to: Float): Float = atan2(sin(to - from), cos(to - from))
    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
}

internal class HillRunner(override val id: String, val slot: Int) : BackgroundCrowdPose {
    override var visible = true
    override var x = 0f
    override var y = 0f
    override var z = 0f
    override var yaw = 0f
    override var phase = 0f
    override var shadowX = 0f
    override var shadowY = 0f
    override var shadowZ = 0f
}

/** Time-spaced actors share the same speed profile and interleave at the summit's shared path. */
internal class HillCrowdSimulation(val route: HillCrowdRoute = HillCrowdRoute()) : BackgroundCrowdSimulation {
    private val actors = linkedMapOf<String, HillRunner>()
    override val runners: Collection<HillRunner> get() = actors.values
    private var elapsedSeconds = 0.0

    override fun setPlayers(ids: List<String>) {
        val wanted = ids.distinct().take(MAX_BACKGROUND_PLAYERS)
        actors.keys.retainAll(wanted.toSet())
        val used = actors.values.map { it.slot }.toMutableSet()
        for (id in wanted) {
            actors.getOrPut(id) {
                // Spread early arrivals over both slopes, then fill the intervening slots.
                val slot = (0 until MAX_BACKGROUND_PLAYERS).map { (route.initialSlot + it * 17) % MAX_BACKGROUND_PLAYERS }.first { it !in used }
                used += slot
                HillRunner(id, slot).also { updatePose(it) }
            }
        }
    }

    override fun advance(deltaSeconds: Double) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) return
        elapsedSeconds = (elapsedSeconds + deltaSeconds.coerceAtMost(0.1)) % route.cycleSeconds
        runners.forEach(::updatePose)
    }

    private fun updatePose(runner: HillRunner) {
        val seconds = route.wrapTime(elapsedSeconds + runner.slot * route.headwaySeconds)
        runner.visible = seconds < route.travelSeconds
        if (!runner.visible) return
        val position = route.wrapDistance(route.distanceAtTime(seconds))
        runner.phase = ((position * 4.0 + (runner.id.hashCode() and 255) * 0.024) % (2.0 * PI)).toFloat()
        route.pose(position, runner)
    }
}
