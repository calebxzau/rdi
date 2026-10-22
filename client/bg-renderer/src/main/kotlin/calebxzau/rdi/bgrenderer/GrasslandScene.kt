package calebxzau.rdi.bgrenderer

import calebxzau.rdi.playermodel.core.PlayerSkinTexture
import kotlin.math.*
import kotlin.random.Random

enum class BackgroundSceneKind { GoldenValley, Grassland }

/** Stable identity is the skin URL, not an account identifier. */
data class BackgroundCrowdPlayer(val id: String, val skin: PlayerSkinTexture)

const val MAX_BACKGROUND_PLAYERS = 50
internal const val GRASSLAND_Y = 84f
internal const val CROWD_MIN_X = -22f
internal const val CROWD_MAX_X = 22f
internal const val CROWD_MIN_Z = -32f
internal const val CROWD_MAX_Z = 4f

internal fun grasslandCamera() = CameraPose(0f, GRASSLAND_Y + 11f, 25f, 0f, GRASSLAND_Y + 1f, -12f, 0f)

// Authored tint selected for midday lighting and tonemapping against the reference
// grass-block top (average display RGB approximately #436939).
private val GRASSLAND_GRASS_COLOR = SkyColor(0.320f, 0.453f, 0.269f)

internal fun buildGrasslandMesh(): SceneMesh {
    val builder = SceneMeshBuilder(mapOf(
        BlockMaterial.GRASS_TOP to GRASSLAND_GRASS_COLOR,
        BlockMaterial.SHORT_GRASS to GRASSLAND_GRASS_COLOR
    ))
    // Large coplanar tiles repeat the block texture without constructing a world/chunk grid.
    for (z in -1024 until 1024 step 16) for (x in -1024 until 1024 step 16) {
        val tint = (0.94 + TerrainNoise.fbm(x * 0.025, z * 0.025, 319) * 0.06).toFloat()
        builder.quad(BlockMaterial.GRASS_TOP, floatArrayOf(
            x.toFloat(), GRASSLAND_Y, z.toFloat(),
            x.toFloat(), GRASSLAND_Y, z + 16f,
            x + 16f, GRASSLAND_Y, z + 16f,
            x + 16f, GRASSLAND_Y, z.toFloat()
        ), 0f, 1f, 0f, 0, tint = FloatArray(4) { tint })
    }
    val random = Random(731)
    repeat(1400) {
        val x = random.nextFloat() * 180f - 90f
        val z = random.nextFloat() * 160f - 125f
        // Keep the playing field mostly mown, with taller grass around its edges.
        val inField = x in CROWD_MIN_X..CROWD_MAX_X && z in CROWD_MIN_Z..CROWD_MAX_Z
        if (!inField || random.nextFloat() < 0.15f) {
            emitGrassTuft(builder, GrassTuft(x, GRASSLAND_Y, z, if (inField) 0.25f else 0.5f + random.nextFloat() * 0.4f, 0.95f))
        }
    }
    // Loose patches of taller grass continue the meadow into the distance.
    repeat(32) { index ->
        val centerX = -140f + index * 9f + random.nextFloat() * 6f - 3f
        val centerZ = -100f - random.nextFloat() * 70f
        val radius = 3f + random.nextFloat() * 5f
        repeat(12 + random.nextInt(25)) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val distance = sqrt(random.nextFloat()) * radius
            emitGrassTuft(builder, GrassTuft(
                centerX + cos(angle) * distance,
                GRASSLAND_Y,
                centerZ + sin(angle) * distance,
                0.55f + random.nextFloat() * 0.75f,
                0.88f + random.nextFloat() * 0.12f
            ))
        }
    }
    return builder.build(StaticAabb(-160f, GRASSLAND_Y - 1f, -180f, 160f, GRASSLAND_Y + 2f, 40f))
}

internal class GrasslandRunner(override val id: String, seed: Long) : BackgroundCrowdPose {
    val random = Random(seed xor id.hashCode().toLong())
    override var x = CROWD_MIN_X + 2f + random.nextFloat() * (CROWD_MAX_X - CROWD_MIN_X - 4f)
    override var z = CROWD_MIN_Z + 2f + random.nextFloat() * (CROWD_MAX_Z - CROWD_MIN_Z - 4f)
    override var yaw = random.nextFloat() * (2f * PI.toFloat())
    val speed = 2.2f + random.nextFloat() * 1.5f
    override var phase = random.nextFloat() * (2f * PI.toFloat())
    override val y: Float get() = GRASSLAND_Y + abs(sin(phase)) * 0.08f
    override val shadowY: Float get() = GRASSLAND_Y
    var targetX = 0f
    var targetZ = 0f
    var targetAge = 0f

    init { chooseTarget() }

    fun chooseTarget() {
        targetX = CROWD_MIN_X + 3f + random.nextFloat() * (CROWD_MAX_X - CROWD_MIN_X - 6f)
        targetZ = CROWD_MIN_Z + 3f + random.nextFloat() * (CROWD_MAX_Z - CROWD_MIN_Z - 6f)
        targetAge = 0f
    }
}

/** Fixed-step movement, bounded catch-up, and stable actors across incremental skin arrivals. */
internal class GrasslandSimulation(private val seed: Long) : BackgroundCrowdSimulation {
    private val actors = linkedMapOf<String, GrasslandRunner>()
    override val runners: Collection<GrasslandRunner> get() = actors.values
    private var accumulated = 0.0

    override fun setPlayers(ids: List<String>) {
        val wanted = ids.distinct().take(MAX_BACKGROUND_PLAYERS)
        actors.keys.retainAll(wanted.toSet())
        wanted.forEach { actors.getOrPut(it) { GrasslandRunner(it, seed) } }
    }

    override fun advance(deltaSeconds: Double) {
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0) return
        accumulated += deltaSeconds.coerceAtMost(0.1)
        val step = 1.0 / 60.0
        while (accumulated + 1e-9 >= step) {
            update(step.toFloat())
            accumulated -= step
        }
    }

    private fun update(dt: Float) {
        for (runner in runners) {
            runner.targetAge += dt
            if (hypot(runner.targetX - runner.x, runner.targetZ - runner.z) < 1.5f || runner.targetAge > 14f) runner.chooseTarget()
            var dx = runner.targetX - runner.x
            var dz = runner.targetZ - runner.z
            val distance = hypot(dx, dz).coerceAtLeast(0.001f)
            dx /= distance
            dz /= distance
            for (other in runners) {
                if (other === runner) continue
                val ox = runner.x - other.x
                val oz = runner.z - other.z
                val squared = ox * ox + oz * oz
                if (squared in 0.0001f..3.24f) {
                    dx += ox / squared * 1.2f
                    dz += oz / squared * 1.2f
                }
            }
            if (runner.x < CROWD_MIN_X + 3f) dx += 3f
            if (runner.x > CROWD_MAX_X - 3f) dx -= 3f
            if (runner.z < CROWD_MIN_Z + 3f) dz += 3f
            if (runner.z > CROWD_MAX_Z - 3f) dz -= 3f
            val desired = atan2(dx, dz)
            val turn = atan2(sin(desired - runner.yaw), cos(desired - runner.yaw))
            runner.yaw += turn.coerceIn(-2.8f * dt, 2.8f * dt)
            val oldX = runner.x
            val oldZ = runner.z
            runner.x = (runner.x + sin(runner.yaw) * runner.speed * dt).coerceIn(CROWD_MIN_X, CROWD_MAX_X)
            runner.z = (runner.z + cos(runner.yaw) * runner.speed * dt).coerceIn(CROWD_MIN_Z, CROWD_MAX_Z)
            runner.phase = (runner.phase + hypot(runner.x - oldX, runner.z - oldZ) * 4f) % (2f * PI.toFloat())
        }
    }
}
