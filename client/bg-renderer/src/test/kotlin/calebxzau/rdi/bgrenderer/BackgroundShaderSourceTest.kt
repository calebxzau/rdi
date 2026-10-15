package calebxzau.rdi.bgrenderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundShaderSourceTest {
    private val rendererSource by lazy {
        java.io.File("src/main/kotlin/calebxzau/rdi/bgrenderer/BackgroundRenderer.kt").readText().replace("\r\n", "\n")
    }

    private fun body(start: String, end: String): String {
        val startIndex = rendererSource.indexOf(start).also { check(it >= 0) { "missing $start" } }
        val endIndex = rendererSource.indexOf(end, startIndex).also { check(it >= 0) { "missing $end after $start" } }
        return rendererSource.substring(startIndex, endIndex)
    }

    @Test
    fun dedicatedWaterShaderOwnsWaterAnimation() {
        assertTrue(WATER_VERTEX_SHADER.contains("uniform float uTime;"))
        assertTrue(WATER_VERTEX_SHADER.contains("position.x * ${WATER_WAVE_X_FREQUENCY} + uTime * ${WATER_WAVE_X_TIME_SPEED}) * ${WATER_WAVE_X_AMPLITUDE}"))
        assertTrue(WATER_VERTEX_SHADER.contains("position.z * ${WATER_WAVE_Z_FREQUENCY} + uTime * ${WATER_WAVE_Z_TIME_SPEED}) * ${WATER_WAVE_Z_AMPLITUDE}"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("uWaterNormalOffset"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("waterPos * 4.0"))
        assertTrue(!WATER_FRAGMENT_SHADER.contains("uniform float uTime;"))
        assertTrue(!WATER_VERTEX_SHADER.contains("vUv"))
        listOf(
            "\${WATER_WAVE_X_FREQUENCY}",
            "\${WATER_WAVE_X_TIME_SPEED}",
            "\${WATER_WAVE_X_AMPLITUDE}",
            "\${WATER_WAVE_Z_FREQUENCY}",
            "\${WATER_WAVE_Z_TIME_SPEED}",
            "\${WATER_WAVE_Z_AMPLITUDE}"
        ).forEach { token -> assertTrue(rendererSource.contains(token)) }
        assertTrue(!rendererSource.contains("materialTextures[BlockMaterial.WATER.ordinal]"))
    }

    @Test
    fun texturedSceneSamplesWithContinuousGradientsAndGoldenLight() {
        assertTrue(VERTEX_SHADER.contains("in vec2 aUv;"))
        assertTrue(VERTEX_SHADER.contains("vUv = aUv;"))
        assertTrue(!VERTEX_SHADER.contains("uBoatModel"))
        assertTrue(FRAGMENT_SHADER.contains("uniform sampler2D uTexture;"))
        assertTrue(FRAGMENT_SHADER.contains("uniform bool uAlphaCutout;"))
        assertTrue(FRAGMENT_SHADER.contains("uniform float uTextureVScale;"))
        assertTrue(FRAGMENT_SHADER.contains("uniform bool uEmissive;"))
        assertTrue(FRAGMENT_SHADER.contains("textureGrad(uTexture, uv, dFdx(vUv) * gradientScale, dFdy(vUv) * gradientScale)"))
        assertTrue(FRAGMENT_SHADER.contains("uniform sampler2DShadow uShadowMap;"))
        assertTrue(FRAGMENT_SHADER.contains("float shadowBias = mix(0.06, 0.30, 1.0 - ndotl) / uShadowDepthRange;"))
        assertTrue(FRAGMENT_SHADER.contains("shadowFactor /= 4.0;"))
        assertTrue(FRAGMENT_SHADER.contains("color = vec4(litColor, 1.0);"))
        assertTrue(FRAGMENT_SHADER.contains("vec3 ambientLight = vec3(0.33, 0.40, 0.56) * skyLight * mix(1.0, vAmbientOcclusion, 0.55);"))
        assertTrue(FRAGMENT_SHADER.contains("vec3 directLight = vec3(1.70, 1.18, 0.66) * (ndotl + translucency) * shadowFactor;"))
        // Only double-sided leaves and grass let the low sun shine through toward the viewer.
        assertTrue(FRAGMENT_SHADER.contains("float translucency = uDoubleSided ? pow(max(dot(viewDirection, lightDirection), 0.0), 3.0) * 0.45 : 0.0;"))
        assertTrue(FRAGMENT_SHADER.contains("float skyLight = 0.68 + 0.32 * normal.y;"))
        assertTrue(FRAGMENT_SHADER.contains("float emissionMask = smoothstep(0.25, 0.75, lanternBrightness);"))
        assertTrue(VERTEX_SHADER.contains("layout(location = 5) in float aAmbientOcclusion;"))
        assertTrue(VERTEX_SHADER.contains("vShadowClipPosition = uShadowViewProjection * worldPosition;"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("texture(uSceneColor, transmissionUv)"))
    }

    @Test
    fun terrainReflectionAndWaterShareOneAtmosphere() {
        assertTrue(ATMOSPHERE_FUNCTIONS.contains("const float ATMOSPHERE_DENSITY = ${ATMOSPHERE_DENSITY};"))
        assertTrue(ATMOSPHERE_FUNCTIONS.contains("const float ATMOSPHERE_HEIGHT_FALLOFF = ${ATMOSPHERE_HEIGHT_FALLOFF};"))
        assertTrue(ATMOSPHERE_FUNCTIONS.contains("const float ATMOSPHERE_WATER_LEVEL = ${WATER_LEVEL};"))
        assertTrue(ATMOSPHERE_FUNCTIONS.contains("(1.0 - exp(-climb)) / climb"))
        listOf(FRAGMENT_SHADER, WATER_FRAGMENT_SHADER).forEach { shader ->
            assertTrue(shader.contains("float atmosphereOpticalDepth(vec3 from, vec3 to)"))
            assertTrue(shader.indexOf("float sunAzimuthAlignment") < shader.indexOf("vec3 atmosphereHazeColor"))
        }
        // The reflection pass fogs only the path above the water; the water pass adds the eye-to-surface part.
        assertTrue(FRAGMENT_SHADER.contains("vec3 fogStart = uFogFromWater ? waterEntryPoint(uEyePosition, vWorldPosition) : uEyePosition;"))
        assertTrue(FRAGMENT_SHADER.indexOf("applyAtmosphere(litColor") > FRAGMENT_SHADER.indexOf("litColor += vColor * vec3(1.8, 0.72, 0.16)"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("result = applyAtmosphere(result, uEyePosition, vWorldPosition, toSun);"))
        assertTrue(WATER_FRAGMENT_SHADER.indexOf("ggxSunHighlight(surfaceNormal") < WATER_FRAGMENT_SHADER.indexOf("result = applyAtmosphere"))
        assertTrue(!WATER_FRAGMENT_SHADER.contains("uHorizonFogStart"))
        val pass = body("private fun renderScenePass", "\n    fun renderWater")
        assertTrue(pass.contains("glUniform1i(fogFromWaterLocation, if (reflection) 1 else 0)"))
        assertTrue(pass.contains("glUniform3f(eyePositionLocation, passEye.x, passEye.y, passEye.z)"))
    }

    @Test
    fun textureEncodingsKeepColorAndDataTexturesDistinct() {
        assertEquals(org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8, TextureEncoding.SrgbColor.internalFormat)
        assertEquals(org.lwjgl.opengl.GL11.GL_RGBA8, TextureEncoding.LinearData.internalFormat)
        assertTrue(rendererSource.contains("uploadClasspathTexture(path, TextureEncoding.SrgbColor, mipmaps = true)"))
        assertTrue(rendererSource.contains("\"assets/complementary/textures/cloud-water.png\",\n            TextureEncoding.LinearData"))
        assertTrue(rendererSource.contains("\"assets/complementary/textures/noise.png\",\n            TextureEncoding.LinearData"))
        assertTrue(rendererSource.contains("\"assets/minecraft/textures/environment/sun.png\",\n            TextureEncoding.SrgbColor"))
        assertTrue(rendererSource.contains("\"assets/minecraft/textures/environment/clouds.png\",\n            TextureEncoding.SrgbColor"))
        assertTrue(rendererSource.contains("uploadTexture(appearance.skin, TextureEncoding.SrgbColor)"))
        assertTrue(rendererSource.contains("uploadTexture(it, TextureEncoding.SrgbColor)"))
        assertTrue(rendererSource.contains("GL_TEXTURE_MAX_ANISOTROPY_EXT"))
    }

    @Test
    fun staticShadowShaderExcludesWaterAndKeepsCutouts() {
        assertTrue(SHADOW_VERTEX_SHADER.contains("layout(location = 3) in float aKind;"))
        assertTrue(SHADOW_FRAGMENT_SHADER.contains("if (vKind != 0) discard;"))
        assertTrue(SHADOW_FRAGMENT_SHADER.contains("uv.y *= uTextureVScale"))
        assertTrue(SHADOW_FRAGMENT_SHADER.contains("textureLod(uTexture, uv, 0.0)"))
        assertTrue(SHADOW_FRAGMENT_SHADER.contains("if (uAlphaCutout && texel.a < 0.1) discard;"))
        assertTrue(rendererSource.contains("glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_R_TO_TEXTURE)"))
        assertTrue(rendererSource.contains("glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)"))
        assertTrue(rendererSource.contains("glDrawBuffer(GL_NONE)"))
        assertTrue(rendererSource.contains("renderStaticShadowMap()"))
        assertTrue(rendererSource.contains("glActiveTexture(GL_TEXTURE5)"))
        assertTrue(rendererSource.contains("listOf(sceneMesh.shadowBounds)"))
        assertEquals(2048, STATIC_SHADOW_MAP_SIZE)
    }

    @Test
    fun staticShadowBakeRestoresAllMutatedGlState() {
        val body = body("private fun renderStaticShadowMap", "\n    fun renderSceneBeforeWater")
        val draw = body.indexOf("glDrawElements")
        val finallyBlock = body.indexOf("} finally {")
        listOf(
            "val previousDepthFunc = glGetInteger(GL_DEPTH_FUNC)",
            "val previousCullMode = glGetInteger(GL_CULL_FACE_MODE)",
            "val previousPolygonOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR)",
            "val previousPolygonOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS)",
            "val previousClearDepth = glGetFloat(GL_DEPTH_CLEAR_VALUE)",
            "val previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE)",
            "val previousProgram = glGetInteger(GL_CURRENT_PROGRAM)",
            "val previousVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING)",
            "val previousTexture0 = glGetInteger(GL_TEXTURE_BINDING_2D)"
        ).forEach { token ->
            assertTrue(body.indexOf(token) >= 0)
            assertTrue(body.indexOf(token) < draw)
        }
        assertTrue(finallyBlock > draw)
        listOf(
            "glBindTexture(GL_TEXTURE_2D, previousTexture0)",
            "glActiveTexture(previousActiveTexture)",
            "glBindVertexArray(previousVertexArray)",
            "glUseProgram(previousProgram)",
            "glDepthFunc(previousDepthFunc)",
            "glCullFace(previousCullMode)",
            "glPolygonOffset(previousPolygonOffsetFactor, previousPolygonOffsetUnits)",
            "glClearDepth(previousClearDepth.toDouble())"
        ).forEach { token -> assertTrue(body.indexOf(token, finallyBlock) >= 0) }
    }

    @Test
    fun celestialPassDrawsTheGoldenSkyAndAnEyeRelativeSun() {
        assertTrue(SUN_VERTEX_SHADER.contains("vUv = aPosition / (2.0 * ${SUN_BILLBOARD_HALF_SIZE}) + 0.5;"))
        assertTrue(SUN_FRAGMENT_SHADER.contains("texture(uSunTexture, discUv)"))
        assertTrue(SUN_FRAGMENT_SHADER.contains("float alpha = max(discCoverage, halo);"))
        assertTrue(!SUN_FRAGMENT_SHADER.contains("disc.a"))
        val celestialBody = body("private fun renderCelestial", "\n    private fun renderClouds")
        assertTrue(celestialBody.contains("BackgroundScene.sunBillboardCenter(passEye.x, passEye.y, passEye.z, sun)"))
        val premultipliedBlend = celestialBody.indexOf("glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)")
        val sunDraw = celestialBody.indexOf("glDrawArrays(GL_TRIANGLES, 0, sunVertices.size / 2)")
        val defaultBlendRestore = celestialBody.indexOf("glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)")
        assertTrue(premultipliedBlend in 0 until sunDraw)
        assertTrue(defaultBlendRestore > sunDraw)
        assertTrue(celestialBody.indexOf("glDisable(GL_DEPTH_TEST)") < celestialBody.indexOf("glDrawArrays(GL_TRIANGLES, 0, 3)"))
        assertTrue(celestialBody.lastIndexOf("glEnable(GL_DEPTH_TEST)") > sunDraw)
    }

    @Test
    fun skyShaderReconstructsWorldRayAndUsesTheSharedGoldenGradient() {
        assertTrue(SKY_FRAGMENT_SHADER.contains("uInverseProjection * vec4(vNdc, 1.0, 1.0)"))
        assertTrue(SKY_FRAGMENT_SHADER.contains("mat3(uInverseView)"))
        assertTrue(SKY_FRAGMENT_SHADER.contains("goldenSkyDisplayColor(worldRay, normalize(uSunDirection))"))
        assertTrue(SKY_FRAGMENT_SHADER.contains("Bayer8"))
        assertTrue(SKY_FRAGMENT_SHADER.contains("color = vec4(srgbToLinear(max(finalSky, vec3(0.0))), 1.0);"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("srgbToLinear(max(goldenSkyDisplayColor(reflectedDirection, toSun), vec3(0.0)))"))
        assertTrue(!SKY_FRAGMENT_SHADER.contains("clamp(finalSky, 0.0, 1.0)"))
        assertTrue(!rendererSource.contains("noonSkyDisplayColor"))
    }

    @Test
    fun hdrAndPostProcessSourcesPreserveTheFixedBloomAndTonemapPipeline() {
        assertTrue(HDR_COLOR_FORMAT == org.lwjgl.opengl.GL30.GL_R11F_G11F_B10F)
        assertTrue(FINAL_COLOR_FORMAT == org.lwjgl.opengl.GL11.GL_RGBA8)
        assertTrue(HDR_COPY_FRAGMENT_SHADER.contains("vec4 fog = upsampleValleyFog(texCoord);"))
        assertTrue(!HDR_COPY_FRAGMENT_SHADER.contains("2.2"))
        assertTrue(BLOOM_ATLAS_FRAGMENT_SHADER.contains("textureLod(uLinearHdr, bloomCoord, lod)"))
        val mainBody = FINAL_TONEMAP_FRAGMENT_SHADER.substringAfter("void main()")
        assertTrue(mainBody.indexOf("DoBloom(") < mainBody.indexOf("DoCompTonemap("))
        assertTrue(mainBody.indexOf("DoCompTonemap(") < mainBody.indexOf("DoBSLColorSaturation("))
    }

    @Test
    fun framePipelineKeepsTheFixedPassOrderAndClearsPixelsBeforeReadback() {
        val render = body("fun render(elapsed: Double", "\n    override fun close")
        val order = listOf(
            "framebuffer.bindForRender()",
            "renderer.renderSceneBeforeWater",
            "framebuffer.captureSceneInputs()",
            "framebuffer.bindForReflection()",
            "renderer.renderReflectionScene",
            "framebuffer.bindForRender()",
            "framebuffer.generateReflectionMipmaps()",
            "renderer.renderWater",
            "framebuffer.resolveRawHdr()",
            "initializedValleyFog.render",
            "initializedPostProcessor.process",
            "pixels.clear()",
            "glReadPixels"
        )
        var cursor = -1
        order.forEach { token ->
            val next = render.indexOf(token, cursor + 1)
            assertTrue(next > cursor, "$token is out of order")
            cursor = next
        }
        val loop = body("private fun renderLoop", "\n/**\n * Owns every GL object")
        assertTrue(loop.contains("pipeline.render(elapsed, appearance.get(), pixels)"))
        assertTrue(loop.indexOf("pipeline.render") < loop.indexOf("performance.record"))
        assertTrue(loop.contains("pipeline.close()"))
    }

    @Test
    fun postProcessCompositesFogBeforeBloomAndEndsOnTheFinalReadTarget() {
        val source = java.io.File("src/main/kotlin/calebxzau/rdi/bgrenderer/BackgroundPostProcessor.kt").readText().replace("\r\n", "\n")
        val process = source.substring(
            source.indexOf("fun process(rawHdrTexture: Int, valleyFogTexture: Int, sceneDepthTexture: Int)").also { check(it >= 0) },
            source.indexOf("\n    private fun drawFullscreen").also { check(it >= 0) }
        )
        val order = listOf(
            "glBindFramebuffer(GL_DRAW_FRAMEBUFFER, linearFramebuffer)",
            "glBindTexture(GL_TEXTURE_2D, valleyFogTexture)",
            "glBindTexture(GL_TEXTURE_2D, sceneDepthTexture)",
            "glUniform2f(hdrCopyDepthPlanesLocation, CAMERA_NEAR_PLANE, CAMERA_FAR_PLANE)",
            "drawFullscreen()",
            "glGenerateMipmap(GL_TEXTURE_2D)",
            "glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bloomFramebuffer)",
            "drawFullscreen()",
            "glBindFramebuffer(GL_FRAMEBUFFER, finalFramebuffer)",
            "drawFullscreen()",
            "glBindFramebuffer(GL_READ_FRAMEBUFFER, finalFramebuffer)"
        )
        var cursor = -1
        order.forEach { token ->
            val next = process.indexOf(token, cursor + 1)
            assertTrue(next > cursor, "$token is out of order")
            cursor = next
        }
    }

    @Test
    fun sceneUsesOneIndexedElementBufferForAllScenePasses() {
        assertTrue(rendererSource.contains("indexBuffer = glGenBuffers()"))
        assertTrue(rendererSource.contains("glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)"))
        assertTrue(rendererSource.contains("glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)"))
        assertTrue(rendererSource.contains("glDrawElements(GL_TRIANGLES, batch.indexCount"))
        assertTrue(rendererSource.contains("glDrawElements(GL_TRIANGLES, waterBatch.indexCount"))
        assertTrue(rendererSource.contains("glDeleteBuffers(indexBuffer)"))
        assertTrue(rendererSource.contains("it.material != BlockMaterial.WATER && it.indexCount > 0"))
    }

    @Test
    fun waterShaderUsesComplementaryNormalLayersFresnelAndDepthTransmission() {
        assertTrue(WATER_FRAGMENT_SHADER.contains("medium * 1.10 + small * 0.35 + big * 1.55 + extraBig * 0.85"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("0.27 * (1.0 - 0.60 * viewEdge)"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("fresnel = 0.02 + 0.98 * pow(1.0 - NdotV, 5.0)"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("const float BASE_WATER_OPACITY = 0.65;"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("exp(-vec3(0.22, 0.10, 0.035) * thickness)"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("candidateWorld.y <= vWorldPosition.y + 0.15"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("float reflectionLod = clamp(distanceLod + edgeLod, 0.0, 3.5);"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("float reflectionStrength = clamp(0.06 + fresnel * 0.92, 0.0, 0.92);"))
        assertTrue(WATER_FRAGMENT_SHADER.contains("return min(highlight, vec3(1.8));"))
    }

    @Test
    fun reflectionUsesGeneratedMipmapsAndClipsAtTheWaterPlane() {
        val reflectionBlock = body("glBindFramebuffer(GL_FRAMEBUFFER, reflectionFramebuffer)", "fun bindForRender")
        assertTrue(reflectionBlock.contains("GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR"))
        assertTrue(VERTEX_SHADER.contains("gl_ClipDistance[0] = worldPosition.y - uClipHeight;"))
        assertTrue(PLAYER_VERTEX_SHADER.contains("gl_ClipDistance[0] = worldPosition.y - uClipHeight;"))
        assertTrue(WATER_VERTEX_SHADER.contains("vReflectionClipPosition = uReflectionViewProjection * worldPosition;"))
    }

    @Test
    fun foliageSwaysAndDoubleSidedBatchesDrawFirstWithoutCulling() {
        assertTrue(VERTEX_SHADER.contains("uniform float uFoliageTime;"))
        assertTrue(VERTEX_SHADER.contains("position.x += sin(uFoliageTime * 2.0 + phase) * 0.07;"))
        assertTrue(VERTEX_SHADER.contains("vWorldPosition = position;"))
        // Both sway frequencies repeat within the wrapped water period, so the wrap is seamless.
        listOf(2.0, 3.0).forEach { frequency ->
            val cycles = WATER_ANIMATION_PERIOD_SECONDS * frequency / (2.0 * Math.PI)
            assertEquals(Math.round(cycles).toDouble(), cycles, absoluteTolerance = 1e-9)
        }
        assertTrue(FRAGMENT_SHADER.contains("if (uDoubleSided && !gl_FrontFacing && abs(normal.y) < 0.5) normal = -normal;"))
        val pass = body("private fun renderScenePass", "\n    fun renderWater")
        assertTrue(pass.contains("glUniform1f(foliageTimeLocation, state.waterTimeSeconds)"))
        val disable = pass.indexOf("if (batch.material.doubleSided) glDisable(GL_CULL_FACE)")
        val draw = pass.indexOf("glDrawElements(GL_TRIANGLES, batch.indexCount", disable)
        val enable = pass.indexOf("if (batch.material.doubleSided) glEnable(GL_CULL_FACE)", draw)
        assertTrue(disable in 0 until draw && draw < enable)
        assertTrue(rendererSource.contains(".sortedBy { if (it.material in VEGETATION_MATERIALS) 0 else 1 }"))
    }

    @Test
    fun playerStandsOnTheSummitFacingAwayAndUsesTheSharedSun() {
        val player = body("private fun renderPlayer", "\n    private fun ensurePlayerResources")
        assertTrue(player.contains(".translate(PLAYER_X, PLAYER_FEET_Y, PLAYER_Z)"))
        assertTrue(player.contains(".rotateY(Math.PI.toFloat())"))
        assertTrue(player.contains(".scale(PLAYER_VISUAL_SCALE)"))
        assertTrue(player.contains("glUniform3f(playerSunDirectionLocation, sun.directionX, sun.directionY, sun.directionZ)"))
        assertTrue(player.contains("pivotRotation(playerBones[PlayerModelPart.HEAD.ordinal], 0f, 24f, 0f, yaw = pose.headYaw)"))
        assertTrue(player.contains("roll = -pose.armRoll") && player.contains("roll = pose.armRoll"))
        assertTrue(player.contains("pivotRotation(playerBones[PlayerModelPart.CAPE.ordinal], 0f, 24f, -2.1f, pitch = pose.capePitch)"))
        assertTrue(PLAYER_FRAGMENT_SHADER.contains("uniform vec3 uSunDirection;"))
    }

    @Test
    fun cloudsUseVanillaFormatGoldenLightAndAlphaFade() {
        assertTrue(CLOUD_VERTEX_SHADER.contains("layout(location = 3) in vec3 aNormal;"))
        assertTrue(CLOUD_VERTEX_SHADER.contains("vNormal = aNormal;"))
        assertTrue(CLOUD_VERTEX_SHADER.contains("* ${CLOUD_HORIZONTAL_SCALE}"))
        assertTrue(CLOUD_VERTEX_SHADER.contains("vRelativePosition = position;"))
        assertTrue(CLOUD_FRAGMENT_SHADER.contains("texture(uCloudTexture, vUv)"))
        assertTrue(CLOUD_FRAGMENT_SHADER.contains("if (texel.a < 0.1) discard;"))
        assertTrue(CLOUD_FRAGMENT_SHADER.contains("smoothstep(uCloudFadeStart, uCloudFadeEnd, length(vRelativePosition.xz))"))
        assertTrue(CLOUD_FRAGMENT_SHADER.contains("color = vec4(litRgb, cloudColor.a * fade);"))
        assertTrue(!CLOUD_TEXTURE_LINEAR_FILTER)
        val cloudBody = body("private fun renderClouds", "\n    private fun uploadMatrix")
        assertTrue(cloudBody.indexOf("glUniform3f(cloudSunDirectionLocation") in 0 until cloudBody.indexOf("glDrawArrays"))
        assertTrue(cloudBody.contains("CLOUD_FADE_START") && cloudBody.contains("CLOUD_FADE_END"))
        val prepass = cloudBody.indexOf("glColorMask(false, false, false, false)")
        val colorPass = cloudBody.indexOf("glColorMask(true, true, true, true)")
        assertTrue(prepass in 0 until colorPass)
        assertTrue(cloudBody.indexOf("glDisable(GL_CULL_FACE)") < prepass)
        assertTrue(cloudBody.lastIndexOf("glEnable(GL_CULL_FACE)") > colorPass)
    }
}
