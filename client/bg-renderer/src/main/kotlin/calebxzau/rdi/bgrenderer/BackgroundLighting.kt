package calebxzau.rdi.bgrenderer

import org.lwjgl.opengl.GL20.*
import kotlin.math.cos
import kotlin.math.sin

/** Light intensities are linear HDR values; sky palette colors in GLSL are display-space. */
internal data class BackgroundLighting(
    val noon: Boolean,
    val sun: SunState,
    val ambient: SkyColor,
    val direct: SkyColor,
    val cloudHeight: Float,
    val cloudCoverage: Float,
    val sunHalfSize: Float
)

internal fun backgroundLighting(scene: BackgroundSceneKind): BackgroundLighting = when (scene) {
    BackgroundSceneKind.GoldenValley -> BackgroundLighting(
        false, BackgroundScene.sun(), SkyColor(0.33f, 0.40f, 0.56f), SkyColor(1.70f, 1.18f, 0.66f),
        CLOUD_HEIGHT, CLOUD_COVER_KEEP_FRACTION, SUN_BILLBOARD_HALF_SIZE
    )
    BackgroundSceneKind.Grassland -> {
        val elevation = Math.toRadians(70.0)
        val azimuth = Math.toRadians(35.0)
        BackgroundLighting(
            true,
            SunState((-sin(azimuth) * cos(elevation)).toFloat(), -sin(elevation).toFloat(), (cos(azimuth) * cos(elevation)).toFloat()),
            SkyColor(0.26f, 0.35f, 0.48f), SkyColor(1.12f, 1.10f, 1.04f),
            144f, 0.65f, 10f
        )
    }
}

/** Upload once when a scene's programs are initialized. Missing uniforms are ignored by GL. */
internal fun configureBackgroundLighting(program: Int, lighting: BackgroundLighting) {
    glUseProgram(program)
    glUniform1i(glGetUniformLocation(program, "uNoon"), if (lighting.noon) 1 else 0)
    glUniform3f(glGetUniformLocation(program, "uAmbientLight"), lighting.ambient.red, lighting.ambient.green, lighting.ambient.blue)
    glUniform3f(glGetUniformLocation(program, "uDirectLight"), lighting.direct.red, lighting.direct.green, lighting.direct.blue)
    glUseProgram(0)
}
