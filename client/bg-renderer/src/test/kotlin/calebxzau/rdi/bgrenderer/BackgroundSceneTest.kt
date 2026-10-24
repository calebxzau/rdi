package calebxzau.rdi.bgrenderer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

class BackgroundSceneTest {
    private val trackTimes = listOf(
        0.0,
        CAMERA_CYCLE_SECONDS / 4.0,
        CAMERA_CYCLE_SECONDS / 2.0,
        CAMERA_CYCLE_SECONDS * 3.0 / 4.0,
        CAMERA_CYCLE_SECONDS
    )

    private fun viewProjection(pose: CameraPose): Matrix4f {
        val projection = Matrix4f().perspective(
            Math.toRadians(CAMERA_FOV_DEGREES.toDouble()).toFloat(),
            RENDER_WIDTH.toFloat() / RENDER_HEIGHT,
            CAMERA_NEAR_PLANE,
            CAMERA_FAR_PLANE
        )
        val view = Matrix4f().lookAt(
            Vector3f(pose.eyeX, pose.eyeY, pose.eyeZ),
            Vector3f(pose.targetX, pose.targetY, pose.targetZ),
            Vector3f(0f, 1f, 0f)
        )
        return projection.mul(view)
    }

    @Test
    fun reflectionUsesHalfResolutionAndMirrorsAroundWater() {
        assertEquals(640, REFLECTION_WIDTH)
        assertEquals(360, REFLECTION_HEIGHT)
        assertEquals(5.0f, reflectedY(reflectedY(5.0f)), absoluteTolerance = 0.000001f)
        assertTrue(reflectedY(5.0f) < WATER_LEVEL)
        assertEquals(
            5.63f,
            reflectionCloudHeight(5f, reflectedY(5f), 0.33f),
            absoluteTolerance = 0.000001f
        )
    }

    @Test
    fun cameraOrbitsAShortArcBehindThePlayerAndReturnsToTheSamePose() {
        val start = BackgroundScene.camera(0.0)
        assertEquals(0f, start.yawRadians)
        assertEquals(PLAYER_X, start.eyeX, absoluteTolerance = 0.0001f)
        assertEquals(PLAYER_FEET_Y + CAMERA_HEIGHT_ABOVE_PLAYER, start.eyeY, absoluteTolerance = 0.0001f)
        assertEquals(PLAYER_Z + CAMERA_ORBIT_RADIUS, start.eyeZ, absoluteTolerance = 0.0001f)
        assertEquals(CAMERA_SWING_RADIANS, BackgroundScene.camera(CAMERA_CYCLE_SECONDS / 4.0).yawRadians, absoluteTolerance = 0.000001f)
        assertEquals(-CAMERA_SWING_RADIANS, BackgroundScene.camera(CAMERA_CYCLE_SECONDS * 3.0 / 4.0).yawRadians, absoluteTolerance = 0.000001f)
        assertEquals(start, BackgroundScene.camera(CAMERA_CYCLE_SECONDS))
        val before = BackgroundScene.camera(CAMERA_CYCLE_SECONDS - 0.001)
        val after = BackgroundScene.camera(CAMERA_CYCLE_SECONDS + 0.001)
        assertTrue(abs(before.yawRadians - after.yawRadians) < 0.001f)
        val nearby = BackgroundScene.camera(0.1)
        assertTrue(abs(nearby.eyeX - start.eyeX) < 0.01f)
        assertTrue(abs(nearby.eyeY - start.eyeY) < 0.01f)

        trackTimes.forEach { time ->
            val pose = BackgroundScene.camera(time)
            val radialX = pose.eyeX - PLAYER_X
            val radialZ = pose.eyeZ - PLAYER_Z
            assertEquals(CAMERA_ORBIT_RADIUS, sqrt(radialX * radialX + radialZ * radialZ), absoluteTolerance = 0.001f)
            val lookX = pose.targetX - pose.eyeX
            val lookY = pose.targetY - pose.eyeY
            val lookZ = pose.targetZ - pose.eyeZ
            val horizontal = sqrt(lookX * lookX + lookZ * lookZ)
            // The view keeps looking away from the orbit center, down by the fixed pitch.
            assertEquals(-1f, (lookX * radialX + lookZ * radialZ) / (horizontal * CAMERA_ORBIT_RADIUS), absoluteTolerance = 0.0001f)
            val pitch = Math.toDegrees(atan2(lookY.toDouble(), horizontal.toDouble())).toFloat()
            assertEquals(CAMERA_PITCH_DEGREES, pitch, absoluteTolerance = 0.01f)
        }
    }

    @Test
    fun playerStaysInTheLowerCenterOfTheFrameAcrossTheCameraTrack() {
        assertEquals(1.8f, 32f * PLAYER_VISUAL_SCALE, absoluteTolerance = 0.0001f)
        trackTimes.forEach { time ->
            val clipFeet = Vector4f(PLAYER_X, PLAYER_FEET_Y, PLAYER_Z, 1f).mul(viewProjection(BackgroundScene.camera(time)))
            val clipHead = Vector4f(PLAYER_X, PLAYER_FEET_Y + 1.8f, PLAYER_Z, 1f).mul(viewProjection(BackgroundScene.camera(time)))
            assertTrue(clipFeet.w > 0f && clipHead.w > 0f)
            val feetY = clipFeet.y / clipFeet.w
            val headY = clipHead.y / clipHead.w
            assertTrue(abs(clipFeet.x / clipFeet.w) < 0.05f, "player drifted sideways at t=$time")
            // The high, wide view: the player stands a little below the frame center, small against the valley.
            assertTrue(feetY in -0.3f..-0.1f, "player feet at NDC y=$feetY at t=$time")
            val pixelHeight = (headY - feetY) * RENDER_HEIGHT / 2f
            assertTrue(pixelHeight in 35f..60f, "player is $pixelHeight px tall at t=$time")
        }
    }

    @Test
    fun playerIdlePoseFollowsMinecraftArmBobAndStaysSubtle() {
        val start = BackgroundScene.playerIdlePose(0.0)
        assertEquals(0f, start.headYaw)
        assertEquals(0.1f, start.armRoll, absoluteTolerance = 1e-6f)
        assertEquals(0f, start.armPitch)
        listOf(0.0, 3.3, 17.0, 600.0, 86_400.0 * 30).forEach { time ->
            val pose = BackgroundScene.playerIdlePose(time)
            assertTrue(abs(pose.headYaw) <= PLAYER_LOOK_AMPLITUDE.toFloat() + 1e-6f)
            assertTrue(pose.armRoll in -1e-6f..0.1f + 1e-6f)
            assertTrue(abs(pose.armPitch) <= 0.05f + 1e-6f)
            assertTrue(pose.capePitch in 0.07f - 1e-6f..0.17f + 1e-6f)
            val next = BackgroundScene.playerIdlePose(time + 0.05)
            assertTrue(abs(next.headYaw - pose.headYaw) < 0.01f && abs(next.armRoll - pose.armRoll) < 0.01f)
        }
        assertEquals(BackgroundScene.playerIdlePose(42.0), BackgroundScene.frameState(42.0).playerPose)
    }

    @Test
    fun goldenHourSunIsLowNormalizedAndJustOutsideTheRightEdge() {
        val sun = BackgroundScene.sun()
        val length = sqrt(sun.directionX * sun.directionX + sun.directionY * sun.directionY + sun.directionZ * sun.directionZ)
        assertEquals(1f, length, absoluteTolerance = 0.0001f)
        val horizontal = sqrt(sun.directionX * sun.directionX + sun.directionZ * sun.directionZ)
        val elevation = Math.toDegrees(atan2((-sun.directionY).toDouble(), horizontal.toDouble())).toFloat()
        assertEquals(SUN_ELEVATION_DEGREES, elevation, absoluteTolerance = 0.01f)
        // The sun lights the scene from the right (+X), a little ahead of the view direction.
        assertTrue(-sun.directionX > 0.85f)
        assertTrue(-sun.directionZ < 0f)
        trackTimes.forEach { time ->
            val pose = BackgroundScene.camera(time)
            val center = BackgroundScene.sunBillboardCenter(pose.eyeX, pose.eyeY, pose.eyeZ, sun)
            val clip = Vector4f(center[0], center[1], center[2], 1f).mul(viewProjection(pose))
            // Behind the image plane or beyond the right edge: the disc itself never shows, only its sky glow.
            assertTrue(clip.w <= 0f || clip.x / clip.w > 1f)
        }
        assertEquals(SUN_BILLBOARD_DISTANCE, BackgroundScene.sunBillboardCenter(0f, 0f, 0f, sun).let {
            sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
        }, absoluteTolerance = 0.01f)
        assertTrue(SUN_BILLBOARD_DISTANCE < CAMERA_FAR_PLANE)
    }

    @Test
    fun sunBillboardBasisIsOrthonormalAndFacesTheEye() {
        val sun = BackgroundScene.sun()
        val basis = BackgroundScene.sunBillboardBasis(sun)
        val rightLength = sqrt(basis.rightX * basis.rightX + basis.rightY * basis.rightY + basis.rightZ * basis.rightZ)
        val upLength = sqrt(basis.upX * basis.upX + basis.upY * basis.upY + basis.upZ * basis.upZ)
        val dot = basis.rightX * basis.upX + basis.rightY * basis.upY + basis.rightZ * basis.upZ
        assertEquals(1f, rightLength, absoluteTolerance = 0.0001f)
        assertEquals(1f, upLength, absoluteTolerance = 0.0001f)
        assertEquals(0f, dot, absoluteTolerance = 0.0001f)
        val normalX = basis.rightY * basis.upZ - basis.rightZ * basis.upY
        val normalY = basis.rightZ * basis.upX - basis.rightX * basis.upZ
        val normalZ = basis.rightX * basis.upY - basis.rightY * basis.upX
        // The quad normal points from the billboard back toward the eye.
        assertTrue(normalX * sun.directionX + normalY * sun.directionY + normalZ * sun.directionZ > 0.999f)
    }

    @Test
    fun atmosphereOpticalDepthMatchesNumericIntegration() {
        fun numeric(from: FloatArray, to: FloatArray): Double {
            val steps = 20_000
            var sum = 0.0
            val dx = (to[0] - from[0]).toDouble()
            val dy = (to[1] - from[1]).toDouble()
            val dz = (to[2] - from[2]).toDouble()
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            for (step in 0 until steps) {
                val t = (step + 0.5) / steps
                val y = from[1] + dy * t
                sum += ATMOSPHERE_DENSITY * exp(-ATMOSPHERE_HEIGHT_FALLOFF * (y - WATER_LEVEL))
            }
            return sum / steps * length
        }
        listOf(
            floatArrayOf(0.5f, 30.5f, 14f) to floatArrayOf(150f, 84f, -410f),
            floatArrayOf(0.5f, 30.5f, 14f) to floatArrayOf(60f, 2.35f, -120f),
            floatArrayOf(-20f, 10f, 0f) to floatArrayOf(300f, 10f, -300f),
            floatArrayOf(0f, 2.35f, -100f) to floatArrayOf(0f, 60f, -400f)
        ).forEach { (from, to) ->
            val expected = numeric(from, to)
            val actual = atmosphereOpticalDepth(from[0], from[1], from[2], to[0], to[1], to[2])
            assertEquals(expected, actual.toDouble(), absoluteTolerance = expected * 1.0e-3 + 1.0e-6)
        }
        assertEquals(0f, atmosphereOpticalDepth(1f, 2f, 3f, 1f, 2f, 3f), absoluteTolerance = 0f)
    }

    @Test
    fun atmosphereKeepsTheForegroundClearAndHazesTheFarRange() {
        val eye = BackgroundScene.camera(0.0)
        fun transmittance(x: Float, y: Float, z: Float) =
            exp(-atmosphereOpticalDepth(eye.eyeX, eye.eyeY, eye.eyeZ, x, y, z))
        assertTrue(transmittance(PLAYER_X, PLAYER_FEET_Y, PLAYER_Z) > 0.95f)
        assertTrue(transmittance(LAKE_CENTER_X.toFloat(), WATER_LEVEL, LAKE_CENTER_Z.toFloat()) in 0.65f..0.9f)
        assertTrue(transmittance(150f, 80f, -410f) in 0.7f..0.95f)
        assertTrue(transmittance(-150f, 20f, -520f) < transmittance(150f, 80f, -410f))
        // The haze thins quickly with height, so snowy summits keep their contrast over a hazy valley floor.
        assertTrue(transmittance(150f, 110f, -410f) > transmittance(150f, 10f, -410f) + 0.1f)
    }

    @Test
    fun waterVertexOffsetMatchesTheRenderedVertexFormula() {
        val samples = listOf(
            Triple(0f, 0f, 0f),
            Triple(-7.25f, 25f, 3.5f),
            Triple(12.5f, -18.75f, 11.25f),
            Triple(-640f, 640f, -2f)
        )
        samples.forEach { (x, z, time) ->
            val expected = sin((x * 0.8f + time * 1.4f).toDouble()).toFloat() * 0.07f +
                cos((z * 0.5f + time * 1.0f).toDouble()).toFloat() * 0.04f
            assertEquals(expected, waterVertexOffset(x, z, time), absoluteTolerance = 0.000001f)
        }
    }

    @Test
    fun frameStateKeepsIndependentPeriodicAnimationsContinuousAtCameraBoundary() {
        val before = BackgroundScene.frameState(CAMERA_CYCLE_SECONDS - 0.001)
        val after = BackgroundScene.frameState(CAMERA_CYCLE_SECONDS + 0.001)
        assertTrue(abs(before.camera.yawRadians - after.camera.yawRadians) < 0.001f)
        assertTrue(abs(before.waterTimeSeconds - after.waterTimeSeconds) < 0.01f)
        assertTrue(abs(before.waterNormalOffset - after.waterNormalOffset) < 0.01f)
        assertEquals(BackgroundScene.waterAnimationTime(12345.678), BackgroundScene.frameState(12345.678).waterTimeSeconds)
        val cloudBefore = BackgroundScene.cloudCoordinates(CAMERA_CYCLE_SECONDS - 0.001, 0.5, 30.5, 14.0)
        val cloudAfter = BackgroundScene.cloudCoordinates(CAMERA_CYCLE_SECONDS + 0.001, 0.5, 30.5, 14.0)
        assertTrue(abs(cloudBefore.fractionX - cloudAfter.fractionX) < 0.01f)
    }

    @Test
    fun waterNormalOffsetHasStablePeriodAndContinuousWrap() {
        assertEquals(0f, BackgroundScene.waterNormalOffset(0.0), absoluteTolerance = 0.000001f)
        assertTrue(BackgroundScene.waterNormalOffset(1.0e15) in 0f..WATER_NORMAL_SCROLL_PERIOD.toFloat())
        val periodSeconds = WATER_NORMAL_SCROLL_PERIOD / WATER_NORMAL_SCROLL_SPEED
        assertEquals(
            BackgroundScene.waterNormalOffset(0.0),
            BackgroundScene.waterNormalOffset(periodSeconds),
            absoluteTolerance = 0.0001f
        )
        val before = BackgroundScene.waterNormalOffset(periodSeconds - 0.001)
        val after = BackgroundScene.waterNormalOffset(periodSeconds + 0.001)
        assertTrue(before > WATER_NORMAL_SCROLL_PERIOD.toFloat() - 0.01f)
        assertTrue(after < 0.01f)
    }

    @Test
    fun waterIsShaderOnlyWhileOtherMaterialsKeepBundledTextures() {
        assertNull(BlockMaterial.WATER.texturePath)
        assertEquals(0.08f, BlockMaterial.WATER.red)
        assertEquals(0.30f, BlockMaterial.WATER.green)
        assertEquals(0.58f, BlockMaterial.WATER.blue)
        assertTrue(BlockMaterial.entries.filter { it != BlockMaterial.WATER }.all { it.texturePath != null })
    }

    @Test
    fun authoredSrgbColorsAreConvertedToLinearSceneInputs() {
        assertEquals(0f, srgbToLinear(0f), absoluteTolerance = 0.000001f)
        assertEquals(1f, srgbToLinear(1f), absoluteTolerance = 0.000001f)
        assertEquals(0.21404114f, srgbToLinear(0.5f), absoluteTolerance = 0.000001f)
        assertEquals(0.04045f / 12.92f, srgbToLinear(0.04045f), absoluteTolerance = 0.000001f)
        assertEquals(
            ((0.04046f + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat(),
            srgbToLinear(0.04046f),
            absoluteTolerance = 0.000001f
        )

        val mesh = buildSceneMesh(123L)
        fun firstVertexColor(material: BlockMaterial): SkyColor {
            val vertex = mesh.indices[mesh.batches.single { it.material == material }.firstIndex]
            val offset = vertex * SCENE_VERTEX_FLOATS + 6
            return SkyColor(mesh.vertices[offset], mesh.vertices[offset + 1], mesh.vertices[offset + 2])
        }
        assertEquals(BlockMaterial.GRASS_SIDE.linearColor(), firstVertexColor(BlockMaterial.GRASS_SIDE))
        assertEquals(BlockMaterial.WATER.linearColor(), firstVertexColor(BlockMaterial.WATER))
        // Grass tops carry a gentle tint around the linear biome color, never the raw sRGB value.
        val grass = firstVertexColor(BlockMaterial.GRASS_TOP)
        val linearGrass = BlockMaterial.GRASS_TOP.linearColor()
        val tint = grass.green / linearGrass.green
        assertTrue(tint in 0.93f..1.07f)
        assertEquals(linearGrass.red * tint, grass.red, absoluteTolerance = 0.0001f)
        assertTrue(abs(grass.blue - BlockMaterial.GRASS_TOP.blue) > 0.1f)
    }

    @Test
    fun materialTraitsDescribeShaderSemanticsExplicitly() {
        assertTrue(BlockMaterial.LANTERN.alphaCutout)
        assertEquals(1f / 3f, BlockMaterial.LANTERN.textureVScale)
        assertTrue(BlockMaterial.LANTERN.emissive)
        assertTrue(BlockMaterial.GLOWSTONE.emissive && !BlockMaterial.GLOWSTONE.alphaCutout)
        listOf(BlockMaterial.OAK_LEAVES, BlockMaterial.BIRCH_LEAVES, BlockMaterial.SPRUCE_LEAVES, BlockMaterial.SHORT_GRASS).forEach { material ->
            assertTrue(material.alphaCutout && material.doubleSided && !material.emissive, "$material")
        }
        assertEquals(
            setOf(BlockMaterial.OAK_LEAVES, BlockMaterial.BIRCH_LEAVES, BlockMaterial.SPRUCE_LEAVES, BlockMaterial.SHORT_GRASS),
            BlockMaterial.entries.filter { it.doubleSided }.toSet()
        )
        listOf(
            BlockMaterial.GRASS_TOP, BlockMaterial.GRASS_SIDE, BlockMaterial.DIRT, BlockMaterial.STONE,
            BlockMaterial.SNOW, BlockMaterial.SAND, BlockMaterial.GRAVEL, BlockMaterial.OAK_PLANKS, BlockMaterial.OAK_LOG
        ).forEach { material ->
            assertTrue(!material.alphaCutout)
            assertEquals(1f, material.textureVScale)
            assertTrue(!material.emissive)
        }
    }

    @Test
    fun faceUvRepeatsOncePerBlockAndKeepsTheImageTopAtTheBlockTop() {
        assertEquals(listOf(2.5f, -3f), faceUv(2.5f, 7f, -3f, 0f, 1f, 0f).toList())
        assertEquals(listOf(4f, -7f), faceUv(1f, 7f, 4f, 1f, 0f, 0f).toList())
        assertEquals(listOf(1f, -7f), faceUv(1f, 7f, 4f, 0f, 0f, -1f).toList())
        // A block spanning y=6..7: v runs from fract(-7)=0 (image top row) at the top to ~1 at the bottom.
        val top = faceUv(0f, 7f, 0f, 1f, 0f, 0f)[1]
        val nearBottom = faceUv(0f, 6.01f, 0f, 1f, 0f, 0f)[1]
        assertEquals(0f, top - kotlin.math.floor(top), absoluteTolerance = 0.0001f)
        assertTrue(nearBottom - kotlin.math.floor(nearBottom) > 0.98f)
    }

    @Test
    fun sceneMaterialBatchesAreContiguousAndCoverEveryVertex() {
        val mesh = buildSceneMesh(123L)
        val vertexCount = mesh.vertices.size / SCENE_VERTEX_FLOATS
        assertEquals(0, mesh.vertices.size % SCENE_VERTEX_FLOATS)
        assertTrue(vertexCount < SCENE_VERTEX_BUDGET, "scene has $vertexCount vertices")
        assertEquals(mesh.indices.size, mesh.batches.sumOf { it.indexCount })
        assertEquals(BlockMaterial.entries.toList(), mesh.batches.map { it.material })
        var nextIndex = 0
        mesh.batches.forEach { batch ->
            assertEquals(nextIndex, batch.firstIndex)
            nextIndex += batch.indexCount
        }
        assertEquals(mesh.indices.size, nextIndex)
        // Every material is used: terrain, forest, village and water.
        BlockMaterial.entries.filter { it != BlockMaterial.OAK_LOG_TOP }.forEach { material ->
            assertTrue(mesh.batches.single { it.material == material }.indexCount > 0, "$material is empty")
        }
        assertTrue(mesh.indices.all { it in 0 until vertexCount })
        val referenced = BooleanArray(vertexCount)
        mesh.indices.forEach { referenced[it] = true }
        assertTrue(referenced.all { it })
        val kinds = (0 until vertexCount).map { mesh.vertices[it * SCENE_VERTEX_FLOATS + 9] }
        assertTrue(kinds.all { it == 0f || it == 1f || it == FOLIAGE_SWAY_KIND.toFloat() })
        assertEquals(6, kinds.count { it == 1f })
        // Only grass tips sway: two crossed quads per tuft, two top corners each.
        val swaying = (0 until vertexCount).filter { kinds[it] == FOLIAGE_SWAY_KIND.toFloat() }
        assertTrue(swaying.isNotEmpty() && swaying.size % 4 == 0)
        val grassBatch = mesh.batches.single { it.material == BlockMaterial.SHORT_GRASS }
        val grassVertices = (grassBatch.firstIndex until grassBatch.firstIndex + grassBatch.indexCount).map { mesh.indices[it] }.toSet()
        assertTrue(swaying.all { it in grassVertices })
        assertEquals(6, mesh.batches.single { it.material == BlockMaterial.WATER }.indexCount)
        val aoValues = (0 until vertexCount).map { mesh.vertices[it * SCENE_VERTEX_FLOATS + 12] }
        assertTrue(aoValues.all { it in 0.58f..1f })
        assertTrue(aoValues.any { it < 1f })
    }

    @Test
    fun waterPlaneCoversTheLakesAndItsNormalPointsUp() {
        // The water plane runs past where the atmosphere dissolves everything into the sky.
        assertTrue(WATER_EXTENT > WORLD_FADE_END + BackgroundScene.camera(0.0).eyeZ)
        val points = waterPlaneCornerPositions(1f, WATER_LEVEL)
        val ax = points[3] - points[0]
        val az = points[5] - points[2]
        val bx = points[6] - points[0]
        val bz = points[8] - points[2]
        assertTrue(az * bx - ax * bz > 0f)
    }

    @Test
    fun renderContractUses720pSixteenToNineFrameAtTargetFps() {
        assertEquals(RENDER_WIDTH, 1280)
        assertEquals(RENDER_HEIGHT, 720)
        assertEquals(RENDER_WIDTH * 9, RENDER_HEIGHT * 16)
        assertEquals(TARGET_FPS, 60)
    }

    @Test
    fun frameDeadlinesDoNotAccumulateLateWakeups() {
        var deadline = 0L
        repeat(60) {
            val wokeUp = deadline + 1_000_000L
            deadline = nextBackgroundFrameDeadline(deadline, wokeUp + 9_000_000L)
        }
        // 60 intervals stay within rounding error of one second despite 1ms wake-up jitter.
        assertTrue(abs(deadline - 1_000_000_000L) < 60L)
    }

    @Test
    fun slowFrameDiscardsMissedDeadlinesInsteadOfQueuingCatchupFrames() {
        val finished = 100_000_000L
        val next = nextBackgroundFrameDeadline(0L, finished)
        assertEquals(finished, next)
        assertEquals(finished + 1_000_000_000L / TARGET_FPS, nextBackgroundFrameDeadline(next, finished + 9_000_000L))
    }

    @Test
    fun cloudsFollowMinecraft121CellsAndFadeInsideTheirCoverage() {
        assertEquals(12f, CLOUD_HORIZONTAL_SCALE)
        assertEquals(4f, CLOUD_THICKNESS)
        assertEquals(-6, CLOUD_TILE_MIN)
        assertEquals(6, CLOUD_TILE_MAX)
        assertTrue(CLOUD_HEIGHT > 118f + 4f, "clouds must clear the highest peak")
        assertTrue(CLOUD_FADE_START < CLOUD_FADE_END)
        // The mesh reaches -CLOUD_TILE_MIN tiles of 8 cells to the -X/-Z side; the fade must finish first.
        assertTrue(CLOUD_FADE_END <= -CLOUD_TILE_MIN * 8 * CLOUD_HORIZONTAL_SCALE)
    }

    @Test
    fun cloudCoordinatesMatchMinecraftTimeScaleAnd2048Wrap() {
        val start = BackgroundScene.cloudCoordinates(0.0, 0.0, 0.0, 0.0)
        val afterSecond = BackgroundScene.cloudCoordinates(1.0, 0.0, 0.0, 0.0)
        assertEquals(0.33, start.z, absoluteTolerance = 0.000001)
        assertEquals(CLOUD_TIME_SPEED / CLOUD_HORIZONTAL_SCALE, afterSecond.x, absoluteTolerance = 0.000001)
        val wrapped = BackgroundScene.cloudCoordinates(CLOUD_COORDINATE_WRAP * CLOUD_HORIZONTAL_SCALE / CLOUD_TIME_SPEED, 0.0, 0.0, 0.0)
        assertEquals(start.x, wrapped.x, absoluteTolerance = 0.000001)
        assertEquals(start.z, wrapped.z, absoluteTolerance = 0.000001)
        assertEquals(0f, CLOUD_HEIGHT % CLOUD_THICKNESS, "cloud height on the 4-block layer grid")
        assertEquals((CLOUD_HEIGHT / CLOUD_THICKNESS).toInt(), start.floorY)
        assertEquals(0.33f, start.fractionY, absoluteTolerance = 0.0001f)
    }

    @Test
    fun cloudColorRebuildUsesMinecraftThreshold() {
        assertTrue(BackgroundScene.cloudColorChanged(null, GOLDEN_CLOUD_COLOR))
        assertTrue(!BackgroundScene.cloudColorChanged(GOLDEN_CLOUD_COLOR, SkyColor(1.0001f, 1f, 1f)))
        assertTrue(BackgroundScene.cloudColorChanged(GOLDEN_CLOUD_COLOR, SkyColor(1.02f, 1f, 1f)))
    }

    @Test
    fun fancyCloudMeshUsesConfiguredCoverageAndVertexFormatWhenTopFacesAreVisible() {
        val coordinates = BackgroundScene.cloudCoordinates(0.0, 25.0, CLOUD_HEIGHT.toDouble() - 4.0, 0.0)
        val vertices = buildFancyCloudVertices(coordinates, GOLDEN_CLOUD_COLOR_LINEAR)
        assertEquals(0, vertices.size % 12)
        assertEquals(20748, vertices.size / 12)
        assertTrue(vertices.asList().windowed(12, 12).all { it[8] == 0.8f })
    }

    @Test
    fun fancyCloudMeshOmitsTopFacesAlongTheRuntimeCameraTrack() {
        trackTimes.forEach { time ->
            val pose = BackgroundScene.camera(time)
            val coordinates = BackgroundScene.cloudCoordinates(
                time,
                pose.eyeX.toDouble(),
                pose.eyeY.toDouble(),
                pose.eyeZ.toDouble()
            )
            val vertices = buildFancyCloudVertices(coordinates, GOLDEN_CLOUD_COLOR_LINEAR)
            assertEquals(19734, vertices.size / 12)
            assertTrue(vertices.asList().windowed(12, 12).none { vertex ->
                vertex[9] == 0f && vertex[10] == 1f && vertex[11] == 0f
            })
        }
    }

    @Test
    fun fancyCloudMeshPreservesVanillaUvNormalsAndHeightBoundary() {
        val coordinates = BackgroundScene.cloudCoordinates(0.0, 0.0, 0.0, 0.0)
        val bottomOnly = buildFancyCloudVertices(coordinates, GOLDEN_CLOUD_COLOR_LINEAR)
        val first = bottomOnly.take(12)
        val firstTile = CLOUD_TILE_MIN * 8f
        assertEquals(firstTile * CLOUD_TEXTURE_COORDINATE_SCALE, first[3], absoluteTolerance = 0.000001f)
        assertEquals((firstTile + 8f) * CLOUD_TEXTURE_COORDINATE_SCALE, first[4], absoluteTolerance = 0.000001f)
        assertEquals(0f, first[9])
        assertEquals(-1f, first[10])
        assertEquals(0f, first[11])

        val topCoordinates = BackgroundScene.cloudCoordinates(0.0, 0.0, CLOUD_HEIGHT.toDouble() - 4.0, 0.0)
        val withTop = buildFancyCloudVertices(topCoordinates, GOLDEN_CLOUD_COLOR_LINEAR)
        val topBaseY = topCoordinates.floorY * CLOUD_THICKNESS
        assertTrue(withTop.asList().windowed(12, 12).any { vertex ->
            abs(vertex[1] - (topBaseY + CLOUD_THICKNESS - 0.0009765625f)) < 0.000001f &&
                vertex[9] == 0f && vertex[10] == 1f && vertex[11] == 0f
        })
        assertTrue(withTop.asList().windowed(12, 12).any { vertex -> abs(vertex[9]) == 1f })
        assertTrue(withTop.asList().windowed(12, 12).any { vertex -> abs(vertex[11]) == 1f })
    }

    @Test
    fun performanceMonitorIgnoresWarmupAndSingleSpikes() {
        val monitor = FramePerformanceMonitor(warmupFrames = 30, sampleSize = 60)
        repeat(30) { assertTrue(!monitor.record(100_000_000L)) }
        repeat(59) { assertTrue(!monitor.record(10_000_000L)) }
        assertTrue(!monitor.record(100_000_000L))
    }

    @Test
    fun performanceMonitorDetectsSustainedSlowFrames() {
        val monitor = FramePerformanceMonitor(warmupFrames = 30, sampleSize = 60)
        repeat(30) { monitor.record(10_000_000L) }
        repeat(59) { assertTrue(!monitor.record(50_000_000L)) }
        assertTrue(monitor.record(50_000_000L))
    }
}
