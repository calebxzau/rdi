package calebxzau.rdi.bgrenderer

import calebxzau.rdi.playermodel.core.PlayerSkinTexture
import calebxzau.rdi.render.GlfwRuntime
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_RENDERER
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO

/**
 * Dev-only GPU check: renders the real background pipeline on the local GPU, writes PNG frames
 * and reports frame timings. Run from `client/ui` with `:bg-renderer:renderPreview`.
 * Arguments: output directory, then optional elapsed times in seconds.
 */
fun main(args: Array<String>) {
    val outputDirectory = File(args.getOrElse(0) { "build/bg-preview" }).apply { mkdirs() }
    val times = args.drop(1).map(String::toDouble).ifEmpty { listOf(0.0, 15.0, 45.0) }
    val lease = GlfwRuntime.acquire()
    var window = NULL
    try {
        window = GlfwRuntime.createHiddenWindow("rdi-background-preview")
        check(window != NULL) { "Failed to create the preview OpenGL context" }
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
        println("GL renderer: ${glGetString(GL_RENDERER)}")
        val buildStarted = System.nanoTime()
        BackgroundFramePipeline(123L).use { pipeline ->
            println("Scene build: ${(System.nanoTime() - buildStarted) / 1_000_000}ms")
            val initStarted = System.nanoTime()
            pipeline.initialize()
            println("GL initialize (uploads + shadow bake): ${(System.nanoTime() - initStarted) / 1_000_000}ms")
            val mesh = pipeline.sceneMesh
            println("Scene vertices=${mesh.vertices.size / SCENE_VERTEX_FLOATS} indices=${mesh.indices.size} terrain=${pipeline.terrainStats} vegetation=${pipeline.vegetationStats}")
            writeShadowMap(pipeline.shadowMapTexture, File(outputDirectory, "shadow-depth.png"))
            val appearance = loadPreviewAppearance()
            val pixels = BufferUtils.createByteBuffer(RENDER_WIDTH * RENDER_HEIGHT * 4)
            repeat(30) { frame -> pipeline.render(frame * 0.05, appearance, pixels) }
            val samples = (0 until 60).map { frame ->
                val started = System.nanoTime()
                pipeline.render(10.0 + frame * 0.05, appearance, pixels)
                System.nanoTime() - started
            }
            println(
                "Frame time over ${samples.size} frames: avg=${"%.2f".format(samples.average() / 1_000_000.0)}ms " +
                    "max=${"%.2f".format(samples.max() / 1_000_000.0)}ms (fallback threshold 45ms)"
            )
            pipeline.render(0.0, appearance, pixels)
            reportShadowContrast(pipeline.shadowMapTexture, pixels)
            times.forEach { time ->
                pipeline.render(time, appearance, pixels)
                val file = File(outputDirectory, "frame-${time}.png")
                ImageIO.write(toImage(pixels), "png", file)
                println("Wrote ${file.absolutePath}")
            }
        }
    } finally {
        if (window != NULL) {
            glfwMakeContextCurrent(NULL)
            GlfwRuntime.destroyWindow(window)
        }
        GL.setCapabilities(null)
        lease.close()
    }
}

/** Writes the static shadow depth map with its value range stretched to the full gray scale. */
private fun writeShadowMap(texture: Int, file: File) {
    val size = STATIC_SHADOW_MAP_SIZE
    val depths = BufferUtils.createFloatBuffer(size * size)
    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture)
    org.lwjgl.opengl.GL11.glGetTexImage(
        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, depths
    )
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    for (i in 0 until size * size) {
        val depth = depths.get(i)
        if (depth < 1f) {
            min = minOf(min, depth)
            max = maxOf(max, depth)
        }
    }
    println("Shadow depth range of covered texels: $min..$max")
    val image = BufferedImage(size / 2, size / 2, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size / 2) for (x in 0 until size / 2) {
        val depth = depths.get((size - 1 - y * 2) * size + x * 2)
        val gray = if (depth >= 1f) 0 else (255 * (1f - (depth - min) / (max - min + 1e-6f))).toInt().coerceIn(0, 255)
        image.setRGB(x, y, (gray shl 16) or (gray shl 8) or gray)
    }
    ImageIO.write(image, "png", file)
}

/**
 * Classifies visible near grass tops as shadowed or lit with the CPU copy of the shadow map and
 * compares their rendered brightness; a working GPU lookup makes the shadowed ones clearly darker.
 */
private fun reportShadowContrast(texture: Int, pixels: ByteBuffer) {
    val size = STATIC_SHADOW_MAP_SIZE
    val depths = BufferUtils.createFloatBuffer(size * size)
    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture)
    org.lwjgl.opengl.GL11.glGetTexImage(
        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, depths
    )
    val grid = TerrainGrid()
    val frame = buildStaticShadowFrame(listOf(terrainShadowBounds(grid)), BackgroundScene.sun(), lightDistance = 400f)
    val pose = BackgroundScene.camera(0.0)
    val viewProjection = org.joml.Matrix4f().perspective(
        Math.toRadians(CAMERA_FOV_DEGREES.toDouble()).toFloat(), RENDER_WIDTH.toFloat() / RENDER_HEIGHT, CAMERA_NEAR_PLANE, CAMERA_FAR_PLANE
    ).lookAt(pose.eyeX, pose.eyeY, pose.eyeZ, pose.targetX, pose.targetY, pose.targetZ, 0f, 1f, 0f)
    val shadowed = ArrayList<Double>()
    val lit = ArrayList<Double>()
    for (x in -96 until 96) for (z in -96 until 0) {
        val height = grid.heightAt(x, z)
        if (height < 3 || topCornerAo(grid, x, z, 1, height).any { it < 1f }) continue
        val point = org.joml.Vector4f(x + 0.5f, height.toFloat(), z + 0.5f, 1f)
        val shadowClip = org.joml.Vector4f(point).mul(frame.viewProjection)
        val u = shadowClip.x * 0.5f + 0.5f
        val v = shadowClip.y * 0.5f + 0.5f
        if (u !in 0f..1f || v !in 0f..1f) continue
        val stored = depths.get((v * size).toInt().coerceIn(0, size - 1) * size + (u * size).toInt().coerceIn(0, size - 1))
        val inShadow = shadowClip.z * 0.5f + 0.5f - 0.0006f > stored
        val clip = org.joml.Vector4f(point).mul(viewProjection)
        if (clip.w <= 0f) continue
        val px = ((clip.x / clip.w * 0.5f + 0.5f) * RENDER_WIDTH).toInt()
        val py = ((clip.y / clip.w * 0.5f + 0.5f) * RENDER_HEIGHT).toInt()
        if (px !in 0 until RENDER_WIDTH || py !in 0 until RENDER_HEIGHT) continue
        val offset = (py * RENDER_WIDTH + px) * 4
        val green = (pixels.get(offset + 1).toInt() and 0xFF).toDouble()
        if (inShadow) shadowed += green else lit += green
    }
    println(
        "Shadow contrast on visible grass tops: shadowed n=${shadowed.size} avgG=${"%.1f".format(shadowed.average())}, " +
            "lit n=${lit.size} avgG=${"%.1f".format(lit.average())}"
    )
}

private fun loadPreviewAppearance(): BackgroundPlayerAppearance? {
    val file = File("../ui/assets/src/main/resources/assets/skins/steve.png")
    if (!file.isFile) {
        println("Steve skin not found at ${file.absolutePath}; rendering without the player")
        return null
    }
    val image = ImageIO.read(file)
    val rgba = ByteArray(image.width * image.height * 4)
    var offset = 0
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            rgba[offset++] = (argb shr 16).toByte()
            rgba[offset++] = (argb shr 8).toByte()
            rgba[offset++] = argb.toByte()
            rgba[offset++] = (argb ushr 24).toByte()
        }
    }
    return BackgroundPlayerAppearance(PlayerSkinTexture(image.width, image.height, rgba))
}

/** Converts bottom-row-first BGRA readback into a top-down image. */
private fun toImage(pixels: ByteBuffer): BufferedImage {
    val image = BufferedImage(RENDER_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until RENDER_HEIGHT) {
        val row = (RENDER_HEIGHT - 1 - y) * RENDER_WIDTH * 4
        for (x in 0 until RENDER_WIDTH) {
            val offset = row + x * 4
            val blue = pixels.get(offset).toInt() and 0xFF
            val green = pixels.get(offset + 1).toInt() and 0xFF
            val red = pixels.get(offset + 2).toInt() and 0xFF
            image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
        }
    }
    return image
}
