package calebxzau.rdi.bgrenderer

import calebxzau.rdi.playermodel.core.PlayerModelPart
import calebxzau.rdi.playermodel.core.PlayerSkinTexture
import calebxzau.rdi.playermodel.core.buildPlayerMeshData
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8
import org.lwjgl.opengl.GL30.*
import kotlin.math.sin

/** All handles belong to the background context. No GPU work occurs on the Compose thread. */
internal class GrasslandCrowdRenderer(private val scene: BackgroundSceneKind = BackgroundSceneKind.Grassland) : AutoCloseable {
    private data class MeshKey(val slim: Boolean, val legacy: Boolean)
    private data class Mesh(val vao: Int, val vbo: Int, val count: Int)
    private data class Skin(val source: PlayerSkinTexture, val texture: Int)
    private val meshes = mutableMapOf<MeshKey, Mesh>()
    private val skins = mutableMapOf<String, Skin>()
    private var previousPlayers: List<BackgroundCrowdPlayer>? = null
    private var program = 0
    private var shadowProgram = 0
    private var shadowVao = 0
    private var shadowProjectionLocation = -1
    private var shadowPositionLocation = -1
    private var shadowRadiusLocation = -1
    private val viewProjection = Matrix4f()
    private val matrixBuffer = BufferUtils.createFloatBuffer(16)
    private val model = Matrix4f()
    private val bones = Array(7) { Matrix4f() }
    private var projectionLocation = -1
    private var viewLocation = -1
    private var modelLocation = -1
    private var sunLocation = -1
    private var clipHeightLocation = -1
    private val boneLocations = IntArray(7)

    fun initialize() {
        program = createProgram(PLAYER_VERTEX_SHADER, PLAYER_FRAGMENT_SHADER)
        configureBackgroundLighting(program, backgroundLighting(scene))
        projectionLocation = glGetUniformLocation(program, "uProjection")
        viewLocation = glGetUniformLocation(program, "uView")
        modelLocation = glGetUniformLocation(program, "uModel")
        sunLocation = glGetUniformLocation(program, "uSunDirection")
        clipHeightLocation = glGetUniformLocation(program, "uClipHeight")
        boneLocations.indices.forEach { boneLocations[it] = glGetUniformLocation(program, "uBones[$it]") }
        glUseProgram(program)
        glUniform1i(glGetUniformLocation(program, "uSkin"), 0)
        glUniform1i(glGetUniformLocation(program, "uCape"), 0)
        glUniform1f(glGetUniformLocation(program, "uClipHeight"), -10000f)
        glUseProgram(0)
        shadowProgram = createProgram(CROWD_SHADOW_VERTEX_SHADER, CROWD_SHADOW_FRAGMENT_SHADER)
        shadowVao = glGenVertexArrays()
        shadowProjectionLocation = glGetUniformLocation(shadowProgram, "uViewProjection")
        shadowPositionLocation = glGetUniformLocation(shadowProgram, "uPosition")
        shadowRadiusLocation = glGetUniformLocation(shadowProgram, "uRadius")
    }

    fun sync(players: List<BackgroundCrowdPlayer>) {
        if (previousPlayers === players) return
        val wanted = players.map { it.id }.toSet()
        val iterator = skins.iterator()
        while (iterator.hasNext()) {
            val (id, skin) = iterator.next()
            if (id !in wanted) {
                glDeleteTextures(skin.texture)
                iterator.remove()
            }
        }
        for (player in players) {
            val old = skins[player.id]
            if (old?.source === player.skin) continue
            val texture = uploadSkin(player.skin)
            skins[player.id] = Skin(player.skin, texture)
            old?.let { glDeleteTextures(it.texture) }
        }
        previousPlayers = players
    }

    fun render(simulation: BackgroundCrowdSimulation, projection: Matrix4f, view: Matrix4f, sun: SunState, reflection: Boolean) {
        // A small soft contact shadow anchors each runner without rebaking the static map.
        // Shadows stay inside their supporting block on the hill, never floating across a riser.
        if (!reflection) {
            glUseProgram(shadowProgram)
            matrix(shadowProjectionLocation, viewProjection.set(projection).mul(view))
            if (scene == BackgroundSceneKind.GoldenValley) glUniform2f(shadowRadiusLocation, 0.4f, 0.4f)
            else glUniform2f(shadowRadiusLocation, 0.65f, 0.45f)
            glBindVertexArray(shadowVao)
            glDisable(GL_CULL_FACE)
            glDepthMask(false)
            for (runner in simulation.runners) {
                if (!runner.visible || runner.id !in skins) continue
                glUniform3f(shadowPositionLocation, runner.shadowX, runner.shadowY + 0.012f, runner.shadowZ)
                glDrawArrays(GL_TRIANGLES, 0, 6)
            }
            glDepthMask(true)
            glEnable(GL_CULL_FACE)
        }
        glUseProgram(program)
        matrix(projectionLocation, projection)
        matrix(viewLocation, view)
        glUniform3f(sunLocation, sun.directionX, sun.directionY, sun.directionZ)
        glUniform1f(clipHeightLocation, if (reflection) WATER_LEVEL + 0.01f else -10000f)
        glActiveTexture(GL_TEXTURE0)
        for (runner in simulation.runners) {
            if (!runner.visible) continue
            val skin = skins[runner.id] ?: continue
            val key = MeshKey(skin.source.slim, skin.source.height == 32)
            val mesh = meshes.getOrPut(key) { createMesh(key) }
            model.identity().translate(runner.x, runner.y, runner.z)
                .rotateY(runner.yaw).scale(PLAYER_VISUAL_SCALE)
            matrix(modelLocation, model)
            bones.forEach { it.identity() }
            val stride = sin(runner.phase) * 0.8f
            val arm = if (key.slim) 5.5f else 6f
            rotateBone(PlayerModelPart.LEFT_ARM, -arm, 22f, stride)
            rotateBone(PlayerModelPart.RIGHT_ARM, arm, 22f, -stride)
            rotateBone(PlayerModelPart.LEFT_LEG, -1.9f, 12f, -stride)
            rotateBone(PlayerModelPart.RIGHT_LEG, 1.9f, 12f, stride)
            bones.forEachIndexed { index, bone -> matrix(boneLocations[index], bone) }
            glBindTexture(GL_TEXTURE_2D, skin.texture)
            glBindVertexArray(mesh.vao)
            glDrawArrays(GL_TRIANGLES, 0, mesh.count)
        }
        glBindVertexArray(0)
        glUseProgram(0)
    }

    private fun rotateBone(part: PlayerModelPart, x: Float, y: Float, pitch: Float) {
        bones[part.ordinal].translate(x, y, 0f).rotateX(pitch).translate(-x, -y, 0f)
    }

    private fun matrix(location: Int, value: Matrix4f) {
        matrixBuffer.clear()
        value.get(matrixBuffer)
        matrixBuffer.position(0)
        glUniformMatrix4fv(location, false, matrixBuffer)
    }

    private fun createMesh(key: MeshKey): Mesh {
        val vertices = buildPlayerMeshData(key.slim, key.legacy, true, false).vertices
        val vao = glGenVertexArrays()
        val vbo = glGenBuffers()
        try {
            glBindVertexArray(vao)
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, BufferUtils.createFloatBuffer(vertices.size).put(vertices).flip(), GL_STATIC_DRAW)
            val stride = 11 * Float.SIZE_BYTES
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.SIZE_BYTES)
            glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 5L * Float.SIZE_BYTES)
            glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 8L * Float.SIZE_BYTES)
            glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 10L * Float.SIZE_BYTES)
            repeat(5) { glEnableVertexAttribArray(it) }
            return Mesh(vao, vbo, vertices.size / 11)
        } catch (error: Throwable) {
            glDeleteBuffers(vbo)
            glDeleteVertexArrays(vao)
            throw error
        } finally {
            glBindVertexArray(0)
        }
    }

    private fun uploadSkin(skin: PlayerSkinTexture): Int {
        require(skin.width == 64 && skin.height in listOf(32, 64)) { "Background skins must be normalized to 64px" }
        val texture = glGenTextures()
        try {
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            val pixels = BufferUtils.createByteBuffer(skin.rgba.size).put(skin.rgba).flip()
            glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, skin.width, skin.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            return texture
        } catch (error: Throwable) {
            glDeleteTextures(texture)
            throw error
        }
    }

    override fun close() {
        skins.values.forEach { glDeleteTextures(it.texture) }
        skins.clear()
        meshes.values.forEach { glDeleteBuffers(it.vbo); glDeleteVertexArrays(it.vao) }
        meshes.clear()
        if (program != 0) glDeleteProgram(program)
        if (shadowProgram != 0) glDeleteProgram(shadowProgram)
        if (shadowVao != 0) glDeleteVertexArrays(shadowVao)
        program = 0
        shadowProgram = 0
        shadowVao = 0
        previousPlayers = null
    }
}

private const val CROWD_SHADOW_VERTEX_SHADER = """
#version 450 core
uniform mat4 uViewProjection;
uniform vec3 uPosition;
uniform vec2 uRadius;
out vec2 vLocal;
const vec2 corners[6] = vec2[6](vec2(-1,-1), vec2(1,-1), vec2(1,1), vec2(-1,-1), vec2(1,1), vec2(-1,1));
void main() {
    vLocal = corners[gl_VertexID];
    gl_Position = uViewProjection * vec4(uPosition + vec3(vLocal.x * uRadius.x, 0, vLocal.y * uRadius.y), 1);
}
"""

private const val CROWD_SHADOW_FRAGMENT_SHADER = """
#version 450 core
in vec2 vLocal;
out vec4 color;
void main() {
    float alpha = (1.0 - smoothstep(0.15, 1.0, length(vLocal))) * 0.24;
    color = vec4(0.025, 0.035, 0.015, alpha);
}
"""
