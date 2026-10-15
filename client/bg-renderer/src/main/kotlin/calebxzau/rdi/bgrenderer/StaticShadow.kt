package calebxzau.rdi.bgrenderer

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal const val STATIC_SHADOW_MAP_SIZE = 2048

/** A world-space box used both for static occupancy/AO and shadow framing. */
internal data class StaticAabb(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float
) {
    fun contains(x: Float, y: Float, z: Float, padding: Float = 0f): Boolean =
        x >= minX - padding && x <= maxX + padding &&
            y >= minY - padding && y <= maxY + padding &&
            z >= minZ - padding && z <= maxZ + padding

    fun contains(
        x: Float,
        y: Float,
        z: Float,
        paddingX: Float,
        paddingY: Float,
        paddingZ: Float
    ): Boolean =
        x >= minX - paddingX && x <= maxX + paddingX &&
            y >= minY - paddingY && y <= maxY + paddingY &&
            z >= minZ - paddingZ && z <= maxZ + paddingZ

    fun corners(): Array<Vector3f> = arrayOf(
        Vector3f(minX, minY, minZ), Vector3f(maxX, minY, minZ),
        Vector3f(minX, maxY, minZ), Vector3f(maxX, maxY, minZ),
        Vector3f(minX, minY, maxZ), Vector3f(maxX, minY, maxZ),
        Vector3f(minX, maxY, maxZ), Vector3f(maxX, maxY, maxZ)
    )
}

internal fun staticAabb(x: Float, y: Float, z: Float, sizeX: Float, sizeY: Float, sizeZ: Float): StaticAabb {
    val halfX = sizeX * 0.5f
    val halfY = sizeY * 0.5f
    val halfZ = sizeZ * 0.5f
    return StaticAabb(x - halfX, y - halfY, z - halfZ, x + halfX, y + halfY, z + halfZ)
}

private const val AO_PROBE = 0.02f

/** Minecraft-style corner AO for one static cube face. */
internal fun cornerAmbientOcclusion(
    source: StaticAabb,
    cornerX: Float,
    cornerY: Float,
    cornerZ: Float,
    normalX: Float,
    normalY: Float,
    normalZ: Float,
    staticAabbs: List<StaticAabb>
): Float {
    val centerX = (source.minX + source.maxX) * 0.5f
    val centerY = (source.minY + source.maxY) * 0.5f
    val centerZ = (source.minZ + source.maxZ) * 0.5f
    val tangentAX: Float
    val tangentAY: Float
    val tangentAZ: Float
    val tangentBX: Float
    val tangentBY: Float
    val tangentBZ: Float
    when {
        abs(normalX) > 0.5f -> {
            tangentAX = 0f; tangentAY = 1f; tangentAZ = 0f
            tangentBX = 0f; tangentBY = 0f; tangentBZ = 1f
        }
        abs(normalY) > 0.5f -> {
            tangentAX = 1f; tangentAY = 0f; tangentAZ = 0f
            tangentBX = 0f; tangentBY = 0f; tangentBZ = 1f
        }
        else -> {
            tangentAX = 1f; tangentAY = 0f; tangentAZ = 0f
            tangentBX = 0f; tangentBY = 1f; tangentBZ = 0f
        }
    }
    val signA = if (cornerX * tangentAX + cornerY * tangentAY + cornerZ * tangentAZ -
        (centerX * tangentAX + centerY * tangentAY + centerZ * tangentAZ) >= 0f
    ) 1f else -1f
    val signB = if (cornerX * tangentBX + cornerY * tangentBY + cornerZ * tangentBZ -
        (centerX * tangentBX + centerY * tangentBY + centerZ * tangentBZ) >= 0f
    ) 1f else -1f

    fun occupied(offsetAX: Float, offsetAY: Float, offsetAZ: Float, offsetBX: Float, offsetBY: Float, offsetBZ: Float): Boolean {
        val sampleX = cornerX + normalX * AO_PROBE + offsetAX + offsetBX
        val sampleY = cornerY + normalY * AO_PROBE + offsetAY + offsetBY
        val sampleZ = cornerZ + normalZ * AO_PROBE + offsetAZ + offsetBZ
        return staticAabbs.any {
            it !== source && it.contains(
                sampleX, sampleY, sampleZ,
                abs(normalX) * AO_PROBE,
                abs(normalY) * AO_PROBE,
                abs(normalZ) * AO_PROBE
            )
        }
    }

    val sideA = occupied(tangentAX * signA * AO_PROBE, tangentAY * signA * AO_PROBE, tangentAZ * signA * AO_PROBE, 0f, 0f, 0f)
    val sideB = occupied(0f, 0f, 0f, tangentBX * signB * AO_PROBE, tangentBY * signB * AO_PROBE, tangentBZ * signB * AO_PROBE)
    val diagonal = occupied(
        tangentAX * signA * AO_PROBE, tangentAY * signA * AO_PROBE, tangentAZ * signA * AO_PROBE,
        tangentBX * signB * AO_PROBE, tangentBY * signB * AO_PROBE, tangentBZ * signB * AO_PROBE
    )
    val level = if (sideA && sideB) 3 else (if (sideA) 1 else 0) + (if (sideB) 1 else 0) + (if (diagonal) 1 else 0)
    return 1f - 0.14f * level
}

internal data class StaticShadowFrame(
    val view: Matrix4f,
    val viewProjection: Matrix4f,
    val minLightX: Float,
    val maxLightX: Float,
    val minLightY: Float,
    val maxLightY: Float,
    val near: Float,
    val far: Float
)

/** Computes a stable orthographic shadow frame that contains every static box corner. */
internal fun buildStaticShadowFrame(
    staticAabbs: List<StaticAabb>,
    sun: SunState,
    lightDistance: Float = 128f,
    xyMargin: Float = 4f,
    depthMargin: Float = 8f
): StaticShadowFrame {
    require(staticAabbs.isNotEmpty()) { "Static shadow frame requires static geometry" }
    var minWorldX = Float.POSITIVE_INFINITY
    var minWorldY = Float.POSITIVE_INFINITY
    var minWorldZ = Float.POSITIVE_INFINITY
    var maxWorldX = Float.NEGATIVE_INFINITY
    var maxWorldY = Float.NEGATIVE_INFINITY
    var maxWorldZ = Float.NEGATIVE_INFINITY
    staticAabbs.forEach { box ->
        minWorldX = min(minWorldX, box.minX)
        minWorldY = min(minWorldY, box.minY)
        minWorldZ = min(minWorldZ, box.minZ)
        maxWorldX = max(maxWorldX, box.maxX)
        maxWorldY = max(maxWorldY, box.maxY)
        maxWorldZ = max(maxWorldZ, box.maxZ)
    }
    val center = Vector3f(
        (minWorldX + maxWorldX) * 0.5f,
        (minWorldY + maxWorldY) * 0.5f,
        (minWorldZ + maxWorldZ) * 0.5f
    )
    val lightEye = Vector3f(
        center.x - sun.directionX * lightDistance,
        center.y - sun.directionY * lightDistance,
        center.z - sun.directionZ * lightDistance
    )
    val lightView = Matrix4f().lookAt(lightEye, center, Vector3f(0f, 0f, 1f))
    var minLightX = Float.POSITIVE_INFINITY
    var minLightY = Float.POSITIVE_INFINITY
    var minLightZ = Float.POSITIVE_INFINITY
    var maxLightX = Float.NEGATIVE_INFINITY
    var maxLightY = Float.NEGATIVE_INFINITY
    var maxLightZ = Float.NEGATIVE_INFINITY
    staticAabbs.forEach { box ->
        box.corners().forEach { corner ->
            val transformed = Vector4f(corner, 1f).mul(lightView)
            minLightX = min(minLightX, transformed.x)
            minLightY = min(minLightY, transformed.y)
            minLightZ = min(minLightZ, transformed.z)
            maxLightX = max(maxLightX, transformed.x)
            maxLightY = max(maxLightY, transformed.y)
            maxLightZ = max(maxLightZ, transformed.z)
        }
    }
    val framedMinX = minLightX - xyMargin
    val framedMaxX = maxLightX + xyMargin
    val framedMinY = minLightY - xyMargin
    val framedMaxY = maxLightY + xyMargin
    val near = max(0.1f, -maxLightZ - depthMargin)
    val far = max(near + 1f, -minLightZ + depthMargin)
    val projection = Matrix4f().ortho(framedMinX, framedMaxX, framedMinY, framedMaxY, near, far)
    return StaticShadowFrame(
        view = lightView,
        viewProjection = projection.mul(lightView),
        minLightX = framedMinX,
        maxLightX = framedMaxX,
        minLightY = framedMinY,
        maxLightY = framedMaxY,
        near = near,
        far = far
    )
}

internal fun buildStaticShadowViewProjection(staticAabbs: List<StaticAabb>, sun: SunState): Matrix4f =
    buildStaticShadowFrame(staticAabbs, sun).viewProjection
