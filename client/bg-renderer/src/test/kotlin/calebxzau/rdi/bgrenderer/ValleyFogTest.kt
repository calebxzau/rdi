package calebxzau.rdi.bgrenderer

import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValleyFogTest {
    @Test
    fun layerIsDenseInTheValleyAndFreeAtTheSummitAndAbove() {
        val lake = -120f
        assertEquals(0f, valleyFogLayerWeight(VALLEY_FOG_BOTTOM - VALLEY_FOG_SOFTNESS, lake))
        assertEquals(0f, valleyFogLayerWeight(VALLEY_FOG_TOP + VALLEY_FOG_SOFTNESS, lake))
        assertEquals(1f, valleyFogLayerWeight((VALLEY_FOG_BOTTOM + VALLEY_FOG_TOP) / 2f, lake), absoluteTolerance = 1e-5f)
        assertTrue(valleyFogLayerWeight(7f, lake) > 0.9f, "valley floor")
        assertTrue(valleyFogLayerWeight(WATER_LEVEL, lake) > 0.3f, "lake surface")
        assertEquals(0f, valleyFogLayerWeight(HILL_TOP_Y.toFloat(), PLAYER_Z), "summit")
        val camera = BackgroundScene.camera(0.0)
        assertEquals(0f, valleyFogLayerWeight(camera.eyeY, camera.eyeZ), "camera")
        // The layer rises toward the far range but never reaches the camera height.
        assertEquals(VALLEY_FOG_TOP, valleyFogTop(lake))
        assertTrue(valleyFogTop(-400f) > valleyFogTop(-250f) && valleyFogTop(-250f) > VALLEY_FOG_TOP)
        assertEquals(VALLEY_FOG_TOP + VALLEY_FOG_MAX_RISE, valleyFogTop(-2000f))
        assertTrue(VALLEY_FOG_TOP + VALLEY_FOG_MAX_RISE + VALLEY_FOG_SOFTNESS < camera.eyeY - 1f)
        var previous = 0f
        for (step in 0..40) {
            val y = VALLEY_FOG_BOTTOM - VALLEY_FOG_SOFTNESS + step * 0.2f
            val weight = valleyFogLayerWeight(y, lake)
            assertTrue(weight >= previous - 1e-6f)
            previous = weight
        }
    }

    @Test
    fun reachKeepsTheFrontOfTheSummitClearAndCoversTheLakeOnward() {
        assertEquals(0f, valleyFogReach(PLAYER_Z))
        assertEquals(0f, valleyFogReach(VALLEY_FOG_START_Z))
        assertEquals(1f, valleyFogReach(VALLEY_FOG_FULL_Z))
        assertEquals(1f, valleyFogReach(-118f), absoluteTolerance = 0.01f)
        assertEquals(1f, valleyFogReach(-400f))
        assertTrue(valleyFogReach(-85f) in 0.3f..0.7f)
    }

    @Test
    fun slabSegmentClipsRaysFromAboveInsideAndBelow() {
        val low = VALLEY_FOG_BOTTOM - VALLEY_FOG_SOFTNESS
        val high = VALLEY_FOG_TOP + VALLEY_FOG_MAX_RISE + VALLEY_FOG_SOFTNESS
        // From the camera looking down at 10°: enters at the top plane, leaves at the bottom plane.
        val eyeY = 30.5f
        val down = -kotlin.math.sin(Math.toRadians(10.0)).toFloat()
        val segment = assertNotNull(valleyFogSlabSegment(eyeY, down, 10_000f))
        assertEquals((high - eyeY) / down, segment.first, absoluteTolerance = 1e-3f)
        assertEquals((low - eyeY) / down, segment.second, absoluteTolerance = 1e-3f)
        // Clipped by an opaque surface before the slab bottom.
        assertEquals(80f, assertNotNull(valleyFogSlabSegment(eyeY, down, 80f)).second)
        // Looking up from above never enters; a surface before the slab hides it.
        assertNull(valleyFogSlabSegment(eyeY, 0.2f, 10_000f))
        assertNull(valleyFogSlabSegment(eyeY, down, (high - eyeY) / down - 1f))
        // Horizontal rays only count when they start inside.
        assertEquals(0f to 300f, valleyFogSlabSegment(8f, 0f, 300f))
        assertNull(valleyFogSlabSegment(eyeY, 0f, 300f))
        // Starting inside and looking up: from the eye to the top plane.
        val inside = assertNotNull(valleyFogSlabSegment(8f, 0.5f, 10_000f))
        assertEquals(0f, inside.first)
        assertEquals((high - 8f) / 0.5f, inside.second, absoluteTolerance = 1e-3f)
    }

    @Test
    fun marchStepsScaleWithTheSegmentWithinBounds() {
        assertEquals(VALLEY_FOG_MIN_STEPS, valleyFogSteps(1f))
        assertEquals(10, valleyFogSteps(10 * VALLEY_FOG_STEP_LENGTH))
        assertEquals(VALLEY_FOG_MAX_STEPS, valleyFogSteps(5000f))
        assertEquals(RENDER_WIDTH / 2, VALLEY_FOG_WIDTH)
        assertEquals(RENDER_HEIGHT / 2, VALLEY_FOG_HEIGHT)
    }

    @Test
    fun windOffsetsStayWrappedAndMoveContinuously() {
        listOf(0.0, 1.0, 3600.0, 1.0e9).forEach { time ->
            valleyFogWindOffsets(time).forEach { assertTrue(it >= 0f && it < 1f, "offset $it at $time") }
        }
        val before = valleyFogWindOffsets(500.0)
        val after = valleyFogWindOffsets(500.1)
        for (axis in before.indices) {
            val delta = abs(after[axis] - before[axis])
            assertTrue(delta < 0.001f || delta > 0.999f, "axis $axis jumped by $delta")
        }
        assertTrue(valleyFogWindOffsets(0.0).all { it == 0f })
        // One full coarse period returns to the same offset along x (compared around the wrap).
        val start = valleyFogWindOffsets(10.0)[0]
        val period = valleyFogWindOffsets(10.0 + 1.0 / VALLEY_FOG_WIND_X)[0]
        val difference = abs(start - period)
        assertTrue(minOf(difference, 1f - difference) < 1e-4f)
    }

    @Test
    fun noiseVolumeIsDeterministicFullRangeAndTileable() {
        val size = VALLEY_FOG_NOISE_SIZE
        val noise = buildValleyFogNoise()
        assertEquals(size * size * size, noise.size)
        assertTrue(noise.contentEquals(buildValleyFogNoise()))
        val values = noise.map { it.toInt() and 0xFF }
        assertEquals(0, values.min())
        assertEquals(255, values.max())
        assertTrue(values.average() in 90.0..165.0, "mean ${values.average()}")
        fun at(x: Int, y: Int, z: Int) = noise[(Math.floorMod(z, size) * size + Math.floorMod(y, size)) * size + Math.floorMod(x, size)].toInt() and 0xFF
        var interior = 0.0
        var seam = 0.0
        var samples = 0
        for (a in 0 until size step 3) for (b in 0 until size step 3) {
            interior += abs(at(31, a, b) - at(32, a, b)) + abs(at(a, 31, b) - at(a, 32, b)) + abs(at(a, b, 31) - at(a, b, 32))
            seam += abs(at(size - 1, a, b) - at(0, a, b)) + abs(at(a, size - 1, b) - at(a, 0, b)) + abs(at(a, b, size - 1) - at(a, b, 0))
            samples++
        }
        // Crossing the tile edge changes the noise no more than any interior neighbor step.
        assertTrue(seam / samples < interior / samples * 1.5 + 1.0, "seam ${seam / samples} vs interior ${interior / samples}")
    }

    @Test
    fun shadersShareTheKotlinFogConstants() {
        assertTrue(VALLEY_FOG_CONSTANTS.contains("const float FOG_BOTTOM = ${VALLEY_FOG_BOTTOM};"))
        assertTrue(VALLEY_FOG_CONSTANTS.contains("const float FOG_TOP = ${VALLEY_FOG_TOP};"))
        assertTrue(VALLEY_FOG_CONSTANTS.contains("const float FOG_DENSITY = ${VALLEY_FOG_DENSITY};"))
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("const int MAX_STEPS = ${VALLEY_FOG_MAX_STEPS};"))
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("int steps = int(clamp(ceil(segment / STEP_LENGTH), float(MIN_STEPS), float(MAX_STEPS)));"))
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("(ATMOSPHERE_WATER_LEVEL - uEyePosition.y) / direction.y"))
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("sunVisibility = texture(uShadowMap"))
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("light = mix(haze, light, atmosphere);"))
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("color = vec4(scattered, transmittance);"))
        // No history buffers: the jitter is a fixed screen-space pattern.
        assertTrue(VALLEY_FOG_FRAGMENT_SHADER.contains("interleavedGradientNoise(gl_FragCoord.xy)"))
        assertTrue(!VALLEY_FOG_FRAGMENT_SHADER.contains("uTime"))
        assertTrue(FRAGMENT_SHADER.contains("if (uFogFromWater) litColor = applyReflectedValleyFog(litColor, fogStart, vWorldPosition);"))
        assertTrue(FRAGMENT_SHADER.indexOf("applyReflectedValleyFog(litColor") < FRAGMENT_SHADER.indexOf("litColor = applyAtmosphere"))
        assertTrue(HDR_COPY_FRAGMENT_SHADER.contains("color = vec4(max(raw * fog.a + fog.rgb, vec3(0.0)), 1.0);"))
        assertTrue(HDR_COPY_FRAGMENT_SHADER.contains("texelFetch(uValleyFog, texel, 0)"))
    }

    @Test
    fun cloudThinningKeepsOrDropsWholeCloudsAcrossTileEdges() {
        val width = 8
        val height = 8
        fun texture(vararg covered: Pair<Int, Int>): ByteArray {
            val rgba = ByteArray(width * height * 4) { -1 }
            for (index in 0 until width * height) rgba[index * 4 + 3] = 0
            covered.forEach { (x, y) -> rgba[(y * width + x) * 4 + 3] = -1 }
            return rgba
        }
        // One cloud wraps across the right/left edge, another is a separate block.
        val clouds = arrayOf(7 to 2, 0 to 2, 1 to 2, 4 to 5, 4 to 6, 5 to 6)
        fun alpha(rgba: ByteArray, x: Int, y: Int) = rgba[(y * width + x) * 4 + 3].toInt() and 0xFF
        val keepAll = texture(*clouds).also { thinCloudCover(it, width, height, 1.01f, 1) }
        clouds.forEach { (x, y) -> assertEquals(255, alpha(keepAll, x, y)) }
        val dropAll = texture(*clouds).also { thinCloudCover(it, width, height, 0f, 1) }
        clouds.forEach { (x, y) -> assertEquals(0, alpha(dropAll, x, y)) }
        repeat(20) { seed ->
            val thinned = texture(*clouds).also { thinCloudCover(it, width, height, 0.5f, seed) }
            val wrapped = listOf(7 to 2, 0 to 2, 1 to 2).map { (x, y) -> alpha(thinned, x, y) }.distinct()
            val block = listOf(4 to 5, 4 to 6, 5 to 6).map { (x, y) -> alpha(thinned, x, y) }.distinct()
            assertEquals(1, wrapped.size, "wrapped cloud was split for seed $seed")
            assertEquals(1, block.size, "block cloud was split for seed $seed")
        }
        // Colors are untouched; only coverage changes.
        assertTrue((0 until width * height).all { index -> dropAll[index * 4] == (-1).toByte() })
    }

    @Test
    fun vanillaCloudTextureIsThinnedToRoughlyTheKeepFraction() {
        val image = requireNotNull(javaClass.classLoader.getResourceAsStream("assets/minecraft/textures/environment/clouds.png")).use(ImageIO::read)
        val rgba = ByteArray(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            rgba[(y * image.width + x) * 4 + 3] = (image.getRGB(x, y) ushr 24).toByte()
        }
        fun coverage(bytes: ByteArray) = (0 until image.width * image.height).count { (bytes[it * 4 + 3].toInt() and 0xFF) >= 26 }
        val before = coverage(rgba)
        thinCloudCover(rgba, image.width, image.height, CLOUD_COVER_KEEP_FRACTION, CLOUD_COVER_SEED)
        val kept = coverage(rgba).toDouble() / before
        assertTrue(kept in 0.25..0.65, "kept $kept of the cloud cover")
    }
}
