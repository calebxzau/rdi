package calebxzau.rdi.bgrenderer

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_TEXTURE_3D
import org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL12.glTexImage3D
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.GL_TEXTURE1
import org.lwjgl.opengl.GL13.GL_TEXTURE2
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL15.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15.glBindBuffer
import org.lwjgl.opengl.GL15.glBufferData
import org.lwjgl.opengl.GL15.glDeleteBuffers
import org.lwjgl.opengl.GL15.glGenBuffers
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import kotlin.math.floor

/**
 * Low-lying valley fog: a soft horizontal slab whose density is broken into banks by a tileable
 * 3D noise texture. It is ray-marched at half resolution only inside the slab, stops at the scene
 * depth or the water plane, and is composited before Bloom (see `HDR_COPY_FRAGMENT_SHADER`).
 */
internal const val VALLEY_FOG_WIDTH = RENDER_WIDTH / 2
internal const val VALLEY_FOG_HEIGHT = RENDER_HEIGHT / 2
/** The dense core spans bottom..top; density fades in over ±softness around both edges. */
internal const val VALLEY_FOG_BOTTOM = 2f
internal const val VALLEY_FOG_TOP = 10f
internal const val VALLEY_FOG_SOFTNESS = 3f
internal const val VALLEY_FOG_DENSITY = 0.075f
/** Beyond the lake the layer rises with distance so fog also pools at the foot of the far range. */
internal const val VALLEY_FOG_RISE_START_Z = -160f
internal const val VALLEY_FOG_RISE_PER_BLOCK = 0.05f
internal const val VALLEY_FOG_MAX_RISE = 14f
/** World-space reach: no fog in front of the summit (z > start), full fog from the lake onward. */
internal const val VALLEY_FOG_START_Z = -50f
internal const val VALLEY_FOG_FULL_Z = -120f
internal const val VALLEY_FOG_MAX_DISTANCE = 900f
internal const val VALLEY_FOG_STEP_LENGTH = 6f
internal const val VALLEY_FOG_MIN_STEPS = 6
internal const val VALLEY_FOG_MAX_STEPS = 24
internal const val VALLEY_FOG_NOISE_SIZE = 64
internal const val VALLEY_FOG_NOISE_SEED = 0x0F06
/** Noise texture coordinates per world unit: banks stretched along x into strips, over thin vertical layers. */
internal const val VALLEY_FOG_COARSE_SCALE_X = 1f / 420f
internal const val VALLEY_FOG_COARSE_SCALE_Y = 1f / 40f
internal const val VALLEY_FOG_COARSE_SCALE_Z = 1f / 180f
internal const val VALLEY_FOG_DETAIL_SCALE_X = 1f / 140f
internal const val VALLEY_FOG_DETAIL_SCALE_Y = 1f / 18f
internal const val VALLEY_FOG_DETAIL_SCALE_Z = 1f / 70f
/** Drift in texture units per second; the detail layer moves faster than the banks. */
internal const val VALLEY_FOG_WIND_X = 0.0009
internal const val VALLEY_FOG_WIND_Z = 0.0004
internal const val VALLEY_FOG_DETAIL_WIND_FACTOR = 1.7
/** Constant-density stand-in used by the planar reflection, as a fraction of the full density. */
internal const val VALLEY_FOG_REFLECTION_COVERAGE = 0.45f
/** Density factor over the open lake, so the water and its reflections show through the mist. */
internal const val VALLEY_FOG_OVER_LAKE = 0.3f

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Top of the dense core at world z; it rises past [VALLEY_FOG_RISE_START_Z] up to [VALLEY_FOG_MAX_RISE]. */
internal fun valleyFogTop(z: Float): Float =
    VALLEY_FOG_TOP + ((VALLEY_FOG_RISE_START_Z - z) * VALLEY_FOG_RISE_PER_BLOCK).coerceIn(0f, VALLEY_FOG_MAX_RISE)

/** CPU mirror of the shader's vertical density profile at world z. */
internal fun valleyFogLayerWeight(y: Float, z: Float): Float {
    val top = valleyFogTop(z)
    val lower = smoothstep(VALLEY_FOG_BOTTOM - VALLEY_FOG_SOFTNESS, VALLEY_FOG_BOTTOM + VALLEY_FOG_SOFTNESS, y)
    val upper = 1f - smoothstep(top - VALLEY_FOG_SOFTNESS, top + VALLEY_FOG_SOFTNESS, y)
    return lower * upper
}

/** CPU mirror of the shader's horizontal reach mask along world z. */
internal fun valleyFogReach(z: Float): Float = 1f - smoothstep(VALLEY_FOG_FULL_Z, VALLEY_FOG_START_Z, z)

/**
 * The part of a ray inside the fog slab, clipped to `[0, maxDistance]`, as (start, end) distances;
 * null when the ray never enters it. Mirrors the shader's slab intersection.
 */
internal fun valleyFogSlabSegment(eyeY: Float, directionY: Float, maxDistance: Float): Pair<Float, Float>? {
    val low = VALLEY_FOG_BOTTOM - VALLEY_FOG_SOFTNESS
    val high = VALLEY_FOG_TOP + VALLEY_FOG_MAX_RISE + VALLEY_FOG_SOFTNESS
    val start: Float
    val end: Float
    if (kotlin.math.abs(directionY) < 1.0e-5f) {
        if (eyeY < low || eyeY > high) return null
        start = 0f
        end = maxDistance
    } else {
        val a = (low - eyeY) / directionY
        val b = (high - eyeY) / directionY
        start = maxOf(minOf(a, b), 0f)
        end = minOf(maxOf(a, b), maxDistance)
    }
    return if (end > start) start to end else null
}

/** Number of march steps for a slab segment; matches the shader's clamp. */
internal fun valleyFogSteps(segment: Float): Int =
    kotlin.math.ceil(segment / VALLEY_FOG_STEP_LENGTH).toInt().coerceIn(VALLEY_FOG_MIN_STEPS, VALLEY_FOG_MAX_STEPS)

/**
 * Coarse and detail noise offsets, each wrapped to `[0, 1)`; the noise texture repeats every
 * texture unit, so wrapping is seamless and the offsets keep full precision for any uptime.
 */
internal fun valleyFogWindOffsets(elapsedSeconds: Double): FloatArray {
    fun wrap(value: Double): Float = (value - floor(value)).toFloat()
    val detail = elapsedSeconds * VALLEY_FOG_DETAIL_WIND_FACTOR
    return floatArrayOf(
        wrap(elapsedSeconds * VALLEY_FOG_WIND_X), 0f, wrap(elapsedSeconds * VALLEY_FOG_WIND_Z),
        wrap(-detail * VALLEY_FOG_WIND_X), 0f, wrap(detail * VALLEY_FOG_WIND_Z * 0.5)
    )
}

/**
 * Tileable 3D value-noise FBM (`size`³ bytes, x fastest). Each octave's lattice wraps at the tile
 * edge, and the result is stretched to the full 0..255 range.
 */
internal fun buildValleyFogNoise(size: Int = VALLEY_FOG_NOISE_SIZE, seed: Int = VALLEY_FOG_NOISE_SEED): ByteArray {
    val values = FloatArray(size * size * size)
    var amplitude = 0.5f
    var periods = 4
    repeat(4) { octave ->
        val lattice = FloatArray(periods * periods * periods) { index ->
            val x = index % periods
            val y = (index / periods) % periods
            val z = index / (periods * periods)
            TerrainNoise.hash01(x, z, (seed + octave * 7907) xor (y * 0x45D9F3B)).toFloat()
        }
        val cellsPerVoxel = periods.toFloat() / size
        fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)
        fun at(x: Int, y: Int, z: Int): Float =
            lattice[(Math.floorMod(z, periods) * periods + Math.floorMod(y, periods)) * periods + Math.floorMod(x, periods)]
        for (z in 0 until size) for (y in 0 until size) for (x in 0 until size) {
            val px = (x + 0.5f) * cellsPerVoxel
            val py = (y + 0.5f) * cellsPerVoxel
            val pz = (z + 0.5f) * cellsPerVoxel
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val z0 = floor(pz).toInt()
            val fx = fade(px - x0)
            val fy = fade(py - y0)
            val fz = fade(pz - z0)
            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
            val value = lerp(
                lerp(lerp(at(x0, y0, z0), at(x0 + 1, y0, z0), fx), lerp(at(x0, y0 + 1, z0), at(x0 + 1, y0 + 1, z0), fx), fy),
                lerp(lerp(at(x0, y0, z0 + 1), at(x0 + 1, y0, z0 + 1), fx), lerp(at(x0, y0 + 1, z0 + 1), at(x0 + 1, y0 + 1, z0 + 1), fx), fy),
                fz
            )
            values[(z * size + y) * size + x] += value * amplitude
        }
        amplitude *= 0.5f
        periods *= 2
    }
    val min = values.min()
    val max = values.max()
    return ByteArray(values.size) { index -> ((values[index] - min) / (max - min) * 255f + 0.5f).toInt().toByte() }
}

/** Owns the noise volume, the half-resolution fog target and the march program; render thread only. */
internal class ValleyFogRenderer(private val width: Int, private val height: Int) : AutoCloseable {
    private val noiseTexture = glGenTextures()
    private val framebuffer = glGenFramebuffers()
    /** RGB: in-scattered light (linear HDR); A: transmittance of the scene behind. */
    val texture = glGenTextures()
    private val vertexArray = glGenVertexArrays()
    private val vertexBuffer = glGenBuffers()
    private val program = createProgram(POST_VERTEX_SHADER, VALLEY_FOG_FRAGMENT_SHADER)
    private val sceneDepthLocation = glGetUniformLocation(program, "uSceneDepth")
    private val noiseLocation = glGetUniformLocation(program, "uFogNoise")
    private val shadowMapLocation = glGetUniformLocation(program, "uShadowMap")
    private val inverseViewProjectionLocation = glGetUniformLocation(program, "uInverseViewProjection")
    private val shadowViewProjectionLocation = glGetUniformLocation(program, "uShadowViewProjection")
    private val shadowDepthRangeLocation = glGetUniformLocation(program, "uShadowDepthRange")
    private val eyePositionLocation = glGetUniformLocation(program, "uEyePosition")
    private val sunDirectionLocation = glGetUniformLocation(program, "uSunDirection")
    private val coarseWindLocation = glGetUniformLocation(program, "uCoarseWind")
    private val detailWindLocation = glGetUniformLocation(program, "uDetailWind")
    private val matrixBuffer = BufferUtils.createFloatBuffer(16)

    init {
        val noise = buildValleyFogNoise()
        glBindTexture(GL_TEXTURE_3D, noiseTexture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage3D(
            GL_TEXTURE_3D, 0, GL_R8, VALLEY_FOG_NOISE_SIZE, VALLEY_FOG_NOISE_SIZE, VALLEY_FOG_NOISE_SIZE,
            0, GL_RED, GL_UNSIGNED_BYTE, BufferUtils.createByteBuffer(noise.size).put(noise).flip()
        )
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_REPEAT)
        glBindTexture(GL_TEXTURE_3D, 0)

        glBindTexture(GL_TEXTURE_2D, texture)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, 0L)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        glDrawBuffer(GL_COLOR_ATTACHMENT0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Background valley fog framebuffer is incomplete"
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        glBindVertexArray(vertexArray)
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferData(GL_ARRAY_BUFFER, BufferUtils.createFloatBuffer(FULLSCREEN_TRIANGLE.size).put(FULLSCREEN_TRIANGLE).flip(), GL_STATIC_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)
        glEnableVertexAttribArray(0)
        glBindVertexArray(0)
    }

    /** Marches the fog for the frame just rendered; the post-processor restores the scene GL state afterwards. */
    fun render(
        elapsedSeconds: Double,
        eye: Vector3f,
        inverseViewProjection: Matrix4f,
        sceneDepthTexture: Int,
        shadowMapTexture: Int,
        shadowViewProjection: Matrix4f,
        shadowDepthRange: Float,
        sun: SunState
    ) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glViewport(0, 0, width, height)
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, sceneDepthTexture)
        glUniform1i(sceneDepthLocation, 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_3D, noiseTexture)
        glUniform1i(noiseLocation, 1)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, shadowMapTexture)
        glUniform1i(shadowMapLocation, 2)
        uploadMatrix(inverseViewProjectionLocation, inverseViewProjection)
        uploadMatrix(shadowViewProjectionLocation, shadowViewProjection)
        glUniform1f(shadowDepthRangeLocation, shadowDepthRange)
        glUniform3f(eyePositionLocation, eye.x, eye.y, eye.z)
        glUniform3f(sunDirectionLocation, sun.directionX, sun.directionY, sun.directionZ)
        val wind = valleyFogWindOffsets(elapsedSeconds)
        glUniform3f(coarseWindLocation, wind[0], wind[1], wind[2])
        glUniform3f(detailWindLocation, wind[3], wind[4], wind[5])
        glBindVertexArray(vertexArray)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_3D, 0)
        glActiveTexture(GL_TEXTURE0)
        glUseProgram(0)
    }

    private fun uploadMatrix(location: Int, matrix: Matrix4f) {
        matrixBuffer.clear()
        matrix.get(matrixBuffer)
        matrixBuffer.position(0)
        glUniformMatrix4fv(location, false, matrixBuffer)
    }

    override fun close() {
        glDeleteTextures(noiseTexture)
        glDeleteTextures(texture)
        glDeleteFramebuffers(framebuffer)
        glDeleteProgram(program)
        glDeleteBuffers(vertexBuffer)
        glDeleteVertexArrays(vertexArray)
    }
}

/** Fog constants shared by the march and the reflection's analytic stand-in. */
internal const val VALLEY_FOG_CONSTANTS = """
const float FOG_BOTTOM = ${VALLEY_FOG_BOTTOM};
const float FOG_TOP = ${VALLEY_FOG_TOP};
const float FOG_SOFTNESS = ${VALLEY_FOG_SOFTNESS};
const float FOG_DENSITY = ${VALLEY_FOG_DENSITY};
const float FOG_RISE_START_Z = ${VALLEY_FOG_RISE_START_Z};
const float FOG_RISE_PER_BLOCK = ${VALLEY_FOG_RISE_PER_BLOCK};
const float FOG_MAX_RISE = ${VALLEY_FOG_MAX_RISE};
const float FOG_START_Z = ${VALLEY_FOG_START_Z};
const float FOG_FULL_Z = ${VALLEY_FOG_FULL_Z};
const float FOG_REFLECTION_COVERAGE = ${VALLEY_FOG_REFLECTION_COVERAGE};
// Linear HDR light the fog scatters: blue-gray sky light and warm low sun.
const vec3 FOG_AMBIENT_LIGHT = vec3(0.30, 0.34, 0.44);
const vec3 FOG_SUN_LIGHT = vec3(0.52, 0.38, 0.22);

float valleyFogTop(float z) {
    return FOG_TOP + clamp((FOG_RISE_START_Z - z) * FOG_RISE_PER_BLOCK, 0.0, FOG_MAX_RISE);
}

float valleyFogLayerWeight(float y, float z) {
    float top = valleyFogTop(z);
    float lower = smoothstep(FOG_BOTTOM - FOG_SOFTNESS, FOG_BOTTOM + FOG_SOFTNESS, y);
    float upper = 1.0 - smoothstep(top - FOG_SOFTNESS, top + FOG_SOFTNESS, y);
    return lower * upper;
}

float valleyFogReach(float z) {
    return 1.0 - smoothstep(FOG_FULL_Z, FOG_START_Z, z);
}

// Constant-density slab stand-in for the planar reflection: no noise, only the dense core.
vec3 applyReflectedValleyFog(vec3 color, vec3 from, vec3 to) {
    float y0 = from.y;
    float y1 = to.y;
    float inside;
    if (abs(y1 - y0) < 1.0e-4) {
        inside = (y0 >= FOG_BOTTOM && y0 <= FOG_TOP) ? 1.0 : 0.0;
    } else {
        float a = clamp((FOG_BOTTOM - y0) / (y1 - y0), 0.0, 1.0);
        float b = clamp((FOG_TOP - y0) / (y1 - y0), 0.0, 1.0);
        inside = abs(b - a);
    }
    float reach = valleyFogReach(0.5 * (from.z + to.z));
    float opticalDepth = FOG_DENSITY * FOG_REFLECTION_COVERAGE * inside * length(to - from) * reach;
    return mix(FOG_AMBIENT_LIGHT, color, exp(-opticalDepth));
}
"""

internal const val VALLEY_FOG_FRAGMENT_SHADER = """
#version 450 core
in vec2 texCoord;
uniform sampler2D uSceneDepth;
uniform sampler3D uFogNoise;
uniform sampler2DShadow uShadowMap;
uniform mat4 uInverseViewProjection;
uniform mat4 uShadowViewProjection;
uniform float uShadowDepthRange;
uniform vec3 uEyePosition;
uniform vec3 uSunDirection;
uniform vec3 uCoarseWind;
uniform vec3 uDetailWind;
out vec4 color;

${GOLDEN_SKY_FUNCTIONS}
${ATMOSPHERE_FUNCTIONS}
${VALLEY_FOG_CONSTANTS}

const float MAX_DISTANCE = ${VALLEY_FOG_MAX_DISTANCE};
const float STEP_LENGTH = ${VALLEY_FOG_STEP_LENGTH};
const int MIN_STEPS = ${VALLEY_FOG_MIN_STEPS};
const int MAX_STEPS = ${VALLEY_FOG_MAX_STEPS};
const vec3 COARSE_SCALE = vec3(${VALLEY_FOG_COARSE_SCALE_X}, ${VALLEY_FOG_COARSE_SCALE_Y}, ${VALLEY_FOG_COARSE_SCALE_Z});
const vec3 DETAIL_SCALE = vec3(${VALLEY_FOG_DETAIL_SCALE_X}, ${VALLEY_FOG_DETAIL_SCALE_Y}, ${VALLEY_FOG_DETAIL_SCALE_Z});
const vec2 LAKE_CENTER = vec2(${LAKE_CENTER_X}, ${LAKE_CENTER_Z});
const vec2 LAKE_RADII = vec2(${LAKE_RADIUS_X}, ${LAKE_RADIUS_Z});
const float FOG_OVER_LAKE = ${VALLEY_FOG_OVER_LAKE};

float fogDensity(vec3 position) {
    float envelope = valleyFogLayerWeight(position.y, position.z) * valleyFogReach(position.z);
    if (envelope <= 0.0) return 0.0;
    float coarse = texture(uFogNoise, position * COARSE_SCALE + uCoarseWind).r;
    float detail = texture(uFogNoise, position * DETAIL_SCALE + uDetailWind).r;
    float banks = smoothstep(0.40, 0.72, coarse * 0.7 + detail * 0.3);
    float overLake = 1.0 - smoothstep(0.6, 1.0, length((position.xz - LAKE_CENTER) / LAKE_RADII));
    return FOG_DENSITY * envelope * banks * mix(1.0, FOG_OVER_LAKE, overLake);
}

// Henyey-Greenstein, normalized so isotropic scattering is 1; mild forward scattering.
float fogPhase(float cosTheta) {
    const float g = 0.35;
    float denominator = 1.0 + g * g - 2.0 * g * cosTheta;
    return (1.0 - g * g) / (denominator * sqrt(denominator));
}

float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

void main() {
    float depth = texture(uSceneDepth, texCoord).r;
    vec4 surface = uInverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 ray = surface.xyz / surface.w - uEyePosition;
    float sceneDistance = length(ray);
    vec3 direction = ray / sceneDistance;
    if (depth >= 0.9999) sceneDistance = MAX_DISTANCE;
    // The captured depth is from before the water pass, so the lake surface is intersected analytically.
    if (direction.y < -1.0e-4) sceneDistance = min(sceneDistance, (ATMOSPHERE_WATER_LEVEL - uEyePosition.y) / direction.y);

    float slabLow = FOG_BOTTOM - FOG_SOFTNESS;
    float slabHigh = FOG_TOP + FOG_MAX_RISE + FOG_SOFTNESS;
    float start;
    float end;
    if (abs(direction.y) < 1.0e-5) {
        bool insideSlab = uEyePosition.y >= slabLow && uEyePosition.y <= slabHigh;
        start = 0.0;
        end = insideSlab ? sceneDistance : 0.0;
    } else {
        float a = (slabLow - uEyePosition.y) / direction.y;
        float b = (slabHigh - uEyePosition.y) / direction.y;
        start = max(min(a, b), 0.0);
        end = min(max(a, b), sceneDistance);
    }
    if (end <= start) {
        color = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float segment = end - start;
    int steps = int(clamp(ceil(segment / STEP_LENGTH), float(MIN_STEPS), float(MAX_STEPS)));
    float stepLength = segment / float(steps);
    float t = start + stepLength * interleavedGradientNoise(gl_FragCoord.xy);
    vec3 toSun = normalize(-uSunDirection);
    vec3 sunLight = FOG_SUN_LIGHT * fogPhase(dot(direction, toSun));
    vec3 haze = atmosphereHazeColor(direction, toSun);
    float shadowBias = 0.5 / uShadowDepthRange;
    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps || transmittance < 0.01) break;
        vec3 position = uEyePosition + direction * t;
        float density = fogDensity(position);
        if (density > 0.0) {
            float stepTransmittance = exp(-density * stepLength);
            vec4 shadowClip = uShadowViewProjection * vec4(position, 1.0);
            vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;
            float sunVisibility = 1.0;
            if (all(greaterThanEqual(shadowCoord, vec3(0.0))) && all(lessThanEqual(shadowCoord, vec3(1.0)))) {
                sunVisibility = texture(uShadowMap, vec3(shadowCoord.xy, shadowCoord.z - shadowBias));
            }
            // Lower fog sees less sky and sun; distant fog fades toward the same haze as the terrain.
            float heightShade = mix(0.72, 1.0, clamp((position.y - FOG_BOTTOM) / (valleyFogTop(position.z) - FOG_BOTTOM), 0.0, 1.0));
            vec3 light = (FOG_AMBIENT_LIGHT + sunLight * sunVisibility) * heightShade;
            float atmosphere = exp(-atmosphereOpticalDepth(uEyePosition, position));
            light = mix(haze, light, atmosphere);
            scattered += transmittance * (1.0 - stepTransmittance) * light;
            transmittance *= stepTransmittance;
        }
        t += stepLength;
    }
    color = vec4(scattered, transmittance);
}
"""
