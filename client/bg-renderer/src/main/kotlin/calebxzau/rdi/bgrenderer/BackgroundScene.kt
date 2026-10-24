package calebxzau.rdi.bgrenderer

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class BlockMaterial(
    val red: Float,
    val green: Float,
    val blue: Float,
    val texturePath: String?,
    val alphaCutout: Boolean = false,
    val textureVScale: Float = 1f,
    val emissive: Boolean = false,
    /** Drawn without back-face culling so leaves and grass show their far side through cutout holes. */
    val doubleSided: Boolean = false
) {
    GRASS_TOP(0.62f, 0.80f, 0.38f, "assets/minecraft/textures/block/grass_block_top.png"),
    GRASS_SIDE(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/grass_block_side.png"),
    DIRT(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/dirt.png"),
    STONE(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/stone.png"),
    SNOW(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/snow.png"),
    SAND(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/sand.png"),
    GRAVEL(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/gravel.png"),
    OAK_PLANKS(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/oak_planks.png"),
    OAK_LOG(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/oak_log.png"),
    OAK_LOG_TOP(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/oak_log_top.png"),
    BIRCH_LOG(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/birch_log.png"),
    SPRUCE_LOG(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/spruce_log.png"),
    /** Leaf and grass textures are grayscale; the colors are Minecraft's plains/birch/spruce foliage tints. */
    OAK_LEAVES(0.47f, 0.67f, 0.18f, "assets/minecraft/textures/block/oak_leaves.png", alphaCutout = true, doubleSided = true),
    BIRCH_LEAVES(0.50f, 0.66f, 0.33f, "assets/minecraft/textures/block/birch_leaves.png", alphaCutout = true, doubleSided = true),
    SPRUCE_LEAVES(0.38f, 0.60f, 0.38f, "assets/minecraft/textures/block/spruce_leaves.png", alphaCutout = true, doubleSided = true),
    SHORT_GRASS(0.62f, 0.80f, 0.38f, "assets/minecraft/textures/block/short_grass.png", alphaCutout = true, doubleSided = true),
    SPRUCE_PLANKS(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/spruce_planks.png"),
    COBBLESTONE(1.0f, 1.0f, 1.0f, "assets/minecraft/textures/block/cobblestone.png"),
    /** Lit house windows. */
    GLOWSTONE(1.0f, 0.90f, 0.70f, "assets/minecraft/textures/block/glowstone.png", emissive = true),
    LANTERN(
        1.0f,
        0.86f,
        0.54f,
        "assets/minecraft/textures/block/lantern.png",
        alphaCutout = true,
        textureVScale = 1f / 3f,
        emissive = true
    ),
    WATER(0.08f, 0.30f, 0.58f, null);

    fun linearColor(): SkyColor = SkyColor(red, green, blue).toLinear()
}

/** A world-space eye and look target; the renderer builds its view matrix from these two points. */
data class CameraPose(
    val eyeX: Float,
    val eyeY: Float,
    val eyeZ: Float,
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    val yawRadians: Float
)

/** Bone angles (radians) of the standing player's idle motion. */
data class PlayerIdlePose(
    val headYaw: Float,
    val armRoll: Float,
    val armPitch: Float,
    val capePitch: Float
)

/** The fixed golden-hour sun; [directionX]..[directionZ] point from the sun toward the scene. */
data class SunState(
    val directionX: Float,
    val directionY: Float,
    val directionZ: Float
)

data class SkyColor(val red: Float, val green: Float, val blue: Float)

/** Exact IEC 61966-2-1 sRGB transfer function for authored color inputs. */
internal fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

fun SkyColor.toLinear(): SkyColor = SkyColor(
    srgbToLinear(red),
    srgbToLinear(green),
    srgbToLinear(blue)
)

data class SunBillboardBasis(
    val rightX: Float,
    val rightY: Float,
    val rightZ: Float,
    val upX: Float,
    val upY: Float,
    val upZ: Float
)

const val WATER_LEVEL = 2.35f
const val TARGET_FPS = 60
const val RENDER_WIDTH = 1280
const val RENDER_HEIGHT = 720

/** Minecraft 1.21.1 `LevelRenderer.renderClouds`: 12-block cells and 4-block layers. */
/** A multiple of the 4-block cloud layer, so the layer splits like Minecraft's; it clears the highest peak. */
const val CLOUD_HEIGHT = 220f
const val CLOUD_HORIZONTAL_SCALE = 12f
const val CLOUD_THICKNESS = 4f
const val CLOUD_TILE_MIN = -6
const val CLOUD_TILE_MAX = 6
/** Clouds fade to transparent over this horizontal range so the gradient sky shows through. */
const val CLOUD_FADE_START = 360f
const val CLOUD_FADE_END = 560f
const val CLOUD_COORDINATE_WRAP = 2048
const val CLOUD_TEXTURE_COORDINATE_SCALE = 0.00390625f
const val CLOUD_TIME_SPEED = 0.6
const val CLOUD_COLOR_REBUILD_THRESHOLD = 2.0e-4f
/** A clear sky: only this fraction of the vanilla clouds (whole clouds, never parts) is kept. */
internal const val CLOUD_COVER_KEEP_FRACTION = 0.45f
internal const val CLOUD_COVER_SEED = 0xC10D

/** The player stands on the summit column; the camera orbits a short arc behind them. */
const val HILL_TOP_Y = 84
const val PLAYER_X = 0.5f
const val PLAYER_Z = 0.5f
const val PLAYER_FEET_Y = HILL_TOP_Y.toFloat()
internal const val PLAYER_VISUAL_SCALE = 1.8f / 32f
const val PLAYER_LOOK_PERIOD_SECONDS = 16.0
const val PLAYER_LOOK_AMPLITUDE = 0.30
const val PLAYER_CAPE_PERIOD_SECONDS = 5.0
const val PLAYER_CAPE_REST_PITCH = 0.12

const val CAMERA_CYCLE_SECONDS = 60.0
const val CAMERA_ORBIT_RADIUS = 13.5f
const val CAMERA_HEIGHT_ABOVE_PLAYER = 6.5f
const val CAMERA_HEIGHT_SWING = 0.25f
const val CAMERA_PITCH_DEGREES = -16f
const val CAMERA_LOOK_DISTANCE = 100f
const val CAMERA_SWING_RADIANS = 0.06f
const val CAMERA_FOV_DEGREES = 84f
const val CAMERA_FAR_PLANE = 1000f
const val CAMERA_NEAR_PLANE = 0.25f
const val REFLECTION_WIDTH = RENDER_WIDTH / 2
const val REFLECTION_HEIGHT = RENDER_HEIGHT / 2

/**
 * Clockwise from the camera's forward direction (-Z) toward +X: ahead and to the right, just outside
 * the right edge of the frame, so its glow and the golden band warm the right side of the sky.
 */
const val SUN_AZIMUTH_DEGREES = 72f
const val SUN_ELEVATION_DEGREES = 20f
/** The sun billboard is placed relative to each pass eye, so it behaves like an object at infinity. */
internal const val SUN_BILLBOARD_DISTANCE = 600f
internal const val SUN_BILLBOARD_HALF_SIZE = 40f

/** Exponential height fog: density(y) = density * exp(-falloff * (y - WATER_LEVEL)). */
const val ATMOSPHERE_DENSITY = 0.0026f
const val ATMOSPHERE_HEIGHT_FALLOFF = 1f / 40f
/** Terrain and water dissolve into the sky behind them over this distance, before the far plane cuts them. */
const val WORLD_FADE_START = 650f
const val WORLD_FADE_END = 950f

const val WATER_ANIMATION_PERIOD_SECONDS = 31.41592653589793
const val WATER_NORMAL_SCROLL_SPEED = 1.10 * 0.018 * 2.5
const val WATER_NORMAL_SCROLL_PERIOD = 20.0
const val WATER_EXTENT = 1100f
internal const val WATER_WAVE_X_FREQUENCY = 0.8f
internal const val WATER_WAVE_X_TIME_SPEED = 1.4f
internal const val WATER_WAVE_X_AMPLITUDE = 0.07f
internal const val WATER_WAVE_Z_FREQUENCY = 0.5f
internal const val WATER_WAVE_Z_TIME_SPEED = 1.0f
internal const val WATER_WAVE_Z_AMPLITUDE = 0.04f

internal fun waterVertexOffset(x: Float, z: Float, waterTimeSeconds: Float): Float =
    sin(x * WATER_WAVE_X_FREQUENCY + waterTimeSeconds * WATER_WAVE_X_TIME_SPEED) * WATER_WAVE_X_AMPLITUDE +
        cos(z * WATER_WAVE_Z_FREQUENCY + waterTimeSeconds * WATER_WAVE_Z_TIME_SPEED) * WATER_WAVE_Z_AMPLITUDE

fun reflectedY(y: Float): Float = 2f * WATER_LEVEL - y

/** Keeps the fractional cloud layer while moving the cloud plane into the mirrored camera's view. */
fun reflectionCloudHeight(mainEyeY: Float, reflectedEyeY: Float, fractionY: Float): Float =
    fractionY + mainEyeY - reflectedEyeY

/** Authored display-space colors of the golden-hour scene. */
val GOLDEN_HORIZON_COLOR = SkyColor(0.72f, 0.79f, 0.90f)
val GOLDEN_CLOUD_COLOR = SkyColor(1.0f, 1.0f, 1.0f)
val GOLDEN_HORIZON_COLOR_LINEAR = GOLDEN_HORIZON_COLOR.toLinear()
val GOLDEN_CLOUD_COLOR_LINEAR = GOLDEN_CLOUD_COLOR.toLinear()

data class CloudCoordinates(
    val x: Double,
    val y: Double,
    val z: Double,
    val fractionX: Float,
    val fractionY: Float,
    val fractionZ: Float,
    val floorX: Int,
    val floorY: Int,
    val floorZ: Int
)

/**
 * Optical depth of the exponential height fog between two points; the shader's
 * `atmosphereOpticalDepth` evaluates the same closed-form integral.
 */
internal fun atmosphereOpticalDepth(
    fromX: Float,
    fromY: Float,
    fromZ: Float,
    toX: Float,
    toY: Float,
    toZ: Float
): Float {
    val dx = toX - fromX
    val dy = toY - fromY
    val dz = toZ - fromZ
    val distance = sqrt(dx * dx + dy * dy + dz * dz)
    val startDensity = ATMOSPHERE_DENSITY * exp(-ATMOSPHERE_HEIGHT_FALLOFF * (fromY - WATER_LEVEL))
    val climb = ATMOSPHERE_HEIGHT_FALLOFF * dy
    val profile = if (kotlin.math.abs(climb) > 1.0e-4f) (1f - exp(-climb)) / climb else 1f - climb * 0.5f
    return startDensity * distance * profile
}

/** Deterministic scene data and animation that can be checked without a GPU. */
object BackgroundScene {
    /** Slowly orbits a short arc behind the player and keeps the view direction perpendicular to the arc. */
    fun camera(timeSeconds: Double): CameraPose {
        val phase = positiveModulo(timeSeconds, CAMERA_CYCLE_SECONDS) / CAMERA_CYCLE_SECONDS
        val wave = sin(phase * Math.PI * 2.0).toFloat()
        val yaw = wave * CAMERA_SWING_RADIANS
        val eyeX = PLAYER_X + sin(yaw) * CAMERA_ORBIT_RADIUS
        val eyeY = PLAYER_FEET_Y + CAMERA_HEIGHT_ABOVE_PLAYER + wave * CAMERA_HEIGHT_SWING
        val eyeZ = PLAYER_Z + cos(yaw) * CAMERA_ORBIT_RADIUS
        val drop = CAMERA_LOOK_DISTANCE * tan(Math.toRadians(CAMERA_PITCH_DEGREES.toDouble())).toFloat()
        return CameraPose(
            eyeX = eyeX,
            eyeY = eyeY,
            eyeZ = eyeZ,
            targetX = eyeX - sin(yaw) * CAMERA_LOOK_DISTANCE,
            targetY = eyeY + drop,
            targetZ = eyeZ - cos(yaw) * CAMERA_LOOK_DISTANCE,
            yawRadians = yaw
        )
    }

    data class FrameState(
        val camera: CameraPose,
        val waterTimeSeconds: Float,
        val waterNormalOffset: Float,
        val cloudTimeSeconds: Double,
        val playerPose: PlayerIdlePose
    )

    fun frameState(elapsedSeconds: Double): FrameState = FrameState(
        camera = camera(elapsedSeconds),
        waterTimeSeconds = waterAnimationTime(elapsedSeconds),
        waterNormalOffset = waterNormalOffset(elapsedSeconds),
        cloudTimeSeconds = elapsedSeconds,
        playerPose = playerIdlePose(elapsedSeconds)
    )

    /**
     * Minecraft's `HumanoidModel` arm bob (per tick: roll `cos(0.09t)*0.05+0.05`, pitch `sin(0.067t)*0.05`),
     * plus a slow look across the valley and a cape stirring in the breeze. Evaluated in Double so it
     * stays smooth for any uptime.
     */
    fun playerIdlePose(elapsedSeconds: Double): PlayerIdlePose {
        val ticks = elapsedSeconds * 20.0
        return PlayerIdlePose(
            headYaw = (sin(elapsedSeconds * Math.PI * 2.0 / PLAYER_LOOK_PERIOD_SECONDS) * PLAYER_LOOK_AMPLITUDE).toFloat(),
            armRoll = (cos(ticks * 0.09) * 0.05 + 0.05).toFloat(),
            armPitch = (sin(ticks * 0.067) * 0.05).toFloat(),
            capePitch = (PLAYER_CAPE_REST_PITCH + sin(elapsedSeconds * Math.PI * 2.0 / PLAYER_CAPE_PERIOD_SECONDS) * 0.05).toFloat()
        )
    }

    internal fun waterAnimationTime(elapsedSeconds: Double): Float =
        positiveModulo(elapsedSeconds, WATER_ANIMATION_PERIOD_SECONDS).toFloat()

    /** Stable, repeating scroll offset for the Complementary water normal layers. */
    fun waterNormalOffset(elapsedSeconds: Double): Float =
        positiveModulo(elapsedSeconds * WATER_NORMAL_SCROLL_SPEED, WATER_NORMAL_SCROLL_PERIOD).toFloat()

    private fun positiveModulo(value: Double, period: Double): Double = ((value % period) + period) % period

    /** Fixed low golden-hour sun to the right of the view; the single source for sky, light and shadows. */
    fun sun(): SunState {
        val azimuth = Math.toRadians(SUN_AZIMUTH_DEGREES.toDouble())
        val elevation = Math.toRadians(SUN_ELEVATION_DEGREES.toDouble())
        val towardSunX = (sin(azimuth) * cos(elevation)).toFloat()
        val towardSunY = sin(elevation).toFloat()
        val towardSunZ = (-cos(azimuth) * cos(elevation)).toFloat()
        return SunState(-towardSunX, -towardSunY, -towardSunZ)
    }

    /** Places the billboard at a fixed distance toward the sun from the pass eye. */
    fun sunBillboardCenter(eyeX: Float, eyeY: Float, eyeZ: Float, sun: SunState): FloatArray = floatArrayOf(
        eyeX - sun.directionX * SUN_BILLBOARD_DISTANCE,
        eyeY - sun.directionY * SUN_BILLBOARD_DISTANCE,
        eyeZ - sun.directionZ * SUN_BILLBOARD_DISTANCE
    )

    /** Camera-facing basis for the sun quad; because the quad is eye-relative it always faces the eye. */
    fun sunBillboardBasis(sun: SunState): SunBillboardBasis {
        val normalX = sun.directionX
        val normalY = sun.directionY
        val normalZ = sun.directionZ

        // Cross with world-up except near the pole, where Z-up keeps the basis stable.
        val nearPole = kotlin.math.abs(normalY) > 0.99f
        val referenceUpY = if (nearPole) 0f else 1f
        val referenceUpZ = if (nearPole) 1f else 0f
        val rightX = referenceUpY * normalZ - referenceUpZ * normalY
        val rightY = referenceUpZ * normalX
        val rightZ = -referenceUpY * normalX
        val rightLength = sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ)
        val normalizedRightX = rightX / rightLength
        val normalizedRightY = rightY / rightLength
        val normalizedRightZ = rightZ / rightLength
        val upX = normalY * normalizedRightZ - normalZ * normalizedRightY
        val upY = normalZ * normalizedRightX - normalX * normalizedRightZ
        val upZ = normalX * normalizedRightY - normalY * normalizedRightX
        return SunBillboardBasis(
            rightX = normalizedRightX,
            rightY = normalizedRightY,
            rightZ = normalizedRightZ,
            upX = upX,
            upY = upY,
            upZ = upZ
        )
    }

    /** Minecraft 1.21.1's cloud coordinates before the view-stack transform. */
    fun cloudCoordinates(
        timeSeconds: Double,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        cloudHeight: Float = CLOUD_HEIGHT
    ): CloudCoordinates {
        val cloudTime = timeSeconds * CLOUD_TIME_SPEED
        var x = (cameraX + cloudTime) / CLOUD_HORIZONTAL_SCALE
        val y = cloudHeight - cameraY + 0.33
        var z = cameraZ / CLOUD_HORIZONTAL_SCALE + 0.33
        x -= floor(x / CLOUD_COORDINATE_WRAP) * CLOUD_COORDINATE_WRAP
        z -= floor(z / CLOUD_COORDINATE_WRAP) * CLOUD_COORDINATE_WRAP
        val floorX = floor(x).toInt()
        val floorY = floor(y / CLOUD_THICKNESS).toInt()
        val floorZ = floor(z).toInt()
        return CloudCoordinates(
            x = x,
            y = y,
            z = z,
            fractionX = (x - floorX).toFloat(),
            fractionY = (((y / CLOUD_THICKNESS) - floorY) * CLOUD_THICKNESS).toFloat(),
            fractionZ = (z - floorZ).toFloat(),
            floorX = floorX,
            floorY = floorY,
            floorZ = floorZ
        )
    }

    fun cloudColorChanged(previous: SkyColor?, current: SkyColor): Boolean {
        if (previous == null) return true
        val red = previous.red - current.red
        val green = previous.green - current.green
        val blue = previous.blue - current.blue
        return red * red + green * green + blue * blue > CLOUD_COLOR_REBUILD_THRESHOLD
    }
}

/**
 * Clears the sky by removing whole clouds from the vanilla cloud texture: every 4-connected group of
 * covered texels (connected across the tiling edges) is kept or dropped as one, by a seeded hash.
 */
internal fun thinCloudCover(rgba: ByteArray, width: Int, height: Int, keepFraction: Float, seed: Int) {
    val count = width * height
    fun covered(index: Int) = (rgba[index * 4 + 3].toInt() and 0xFF) >= CLOUD_ALPHA_THRESHOLD
    val visited = BooleanArray(count)
    val queue = IntArray(count)
    for (start in 0 until count) {
        if (visited[start] || !covered(start)) continue
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            val neighbors = intArrayOf(
                y * width + (x + 1) % width,
                y * width + (x + width - 1) % width,
                ((y + 1) % height) * width + x,
                ((y + height - 1) % height) * width + x
            )
            neighbors.forEach { neighbor ->
                if (!visited[neighbor] && covered(neighbor)) {
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
        }
        // Scanning in index order makes `start` the group's smallest index, a stable identity for the hash.
        if (TerrainNoise.hash01(start % width, start / width, seed) >= keepFraction) {
            for (member in 0 until tail) rgba[queue[member] * 4 + 3] = 0
        }
    }
}

/** Matches the cloud shader's `texel.a < 0.1` discard. */
private const val CLOUD_ALPHA_THRESHOLD = 26

/** Counter-clockwise when viewed from above, matching the +Y normal. */
internal fun waterPlaneCornerPositions(size: Float, y: Float): FloatArray = floatArrayOf(
    -size, y, size,
    size, y, size,
    size, y, -size,
    -size, y, -size
)
