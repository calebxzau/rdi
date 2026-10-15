package calebxzau.rdi.bgrenderer

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
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
import org.lwjgl.opengl.GL42.glTexStorage2D

internal class BackgroundPostProcessor(
    private val width: Int,
    private val height: Int
) : AutoCloseable {
    private val linearFramebuffer = glGenFramebuffers()
    private val linearTexture = glGenTextures()
    private val bloomFramebuffer = glGenFramebuffers()
    private val bloomTexture = glGenTextures()
    private val finalFramebuffer = glGenFramebuffers()
    private val finalTexture = glGenTextures()
    private val vertexArray = glGenVertexArrays()
    private val vertexBuffer = glGenBuffers()
    private val hdrCopyProgram = createProgram(POST_VERTEX_SHADER, HDR_COPY_FRAGMENT_SHADER)
    private val bloomProgram = createProgram(POST_VERTEX_SHADER, BLOOM_ATLAS_FRAGMENT_SHADER)
    private val finalProgram = createProgram(POST_VERTEX_SHADER, FINAL_TONEMAP_FRAGMENT_SHADER)
    private val hdrCopyInputLocation = glGetUniformLocation(hdrCopyProgram, "uRawHdr")
    private val hdrCopyValleyFogLocation = glGetUniformLocation(hdrCopyProgram, "uValleyFog")
    private val hdrCopySceneDepthLocation = glGetUniformLocation(hdrCopyProgram, "uSceneDepth")
    private val hdrCopyDepthPlanesLocation = glGetUniformLocation(hdrCopyProgram, "uDepthPlanes")
    private val bloomInputLocation = glGetUniformLocation(bloomProgram, "uLinearHdr")
    private val finalColorLocation = glGetUniformLocation(finalProgram, "uLinearHdr")
    private val finalBloomLocation = glGetUniformLocation(finalProgram, "uBloomAtlas")

    init {
        val mipLevels = 32 - Integer.numberOfLeadingZeros(maxOf(width, height))
        glBindTexture(GL_TEXTURE_2D, linearTexture)
        glTexStorage2D(GL_TEXTURE_2D, mipLevels, HDR_COLOR_FORMAT, width, height)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        attachColor(linearFramebuffer, linearTexture)

        glBindTexture(GL_TEXTURE_2D, bloomTexture)
        glTexImage2D(GL_TEXTURE_2D, 0, FINAL_COLOR_FORMAT, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        configureAtlasTexture()
        attachColor(bloomFramebuffer, bloomTexture)

        glBindTexture(GL_TEXTURE_2D, finalTexture)
        glTexImage2D(GL_TEXTURE_2D, 0, FINAL_COLOR_FORMAT, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        configureAtlasTexture()
        attachColor(finalFramebuffer, finalTexture)

        glBindVertexArray(vertexArray)
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        val vertices = BufferUtils.createFloatBuffer(FULLSCREEN_TRIANGLE.size).put(FULLSCREEN_TRIANGLE).flip()
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)
        glEnableVertexAttribArray(0)
        glBindVertexArray(0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun attachColor(framebuffer: Int, texture: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        glDrawBuffer(GL_COLOR_ATTACHMENT0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Background post-process framebuffer is incomplete"
        }
    }

    private fun configureAtlasTexture() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    /** Composites the half-resolution valley fog over the raw HDR scene, then runs Bloom and the tonemap. */
    fun process(rawHdrTexture: Int, valleyFogTexture: Int, sceneDepthTexture: Int) {
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glColorMask(true, true, true, true)

        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, linearFramebuffer)
        glViewport(0, 0, width, height)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, rawHdrTexture)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, valleyFogTexture)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, sceneDepthTexture)
        glUseProgram(hdrCopyProgram)
        glUniform1i(hdrCopyInputLocation, 0)
        glUniform1i(hdrCopyValleyFogLocation, 1)
        glUniform1i(hdrCopySceneDepthLocation, 2)
        glUniform2f(hdrCopyDepthPlanesLocation, CAMERA_NEAR_PLANE, CAMERA_FAR_PLANE)
        drawFullscreen()
        glActiveTexture(GL_TEXTURE0)

        glBindTexture(GL_TEXTURE_2D, linearTexture)
        glGenerateMipmap(GL_TEXTURE_2D)

        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bloomFramebuffer)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, linearTexture)
        glUseProgram(bloomProgram)
        glUniform1i(bloomInputLocation, 0)
        drawFullscreen()

        glBindFramebuffer(GL_FRAMEBUFFER, finalFramebuffer)
        glUseProgram(finalProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, linearTexture)
        glUniform1i(finalColorLocation, 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, bloomTexture)
        glUniform1i(finalBloomLocation, 1)
        drawFullscreen()

        glActiveTexture(GL_TEXTURE0)
        glBindVertexArray(0)
        glUseProgram(0)
        glEnable(GL_DEPTH_TEST)
        glDepthMask(true)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        glColorMask(true, true, true, true)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, finalFramebuffer)
    }

    private fun drawFullscreen() {
        glBindVertexArray(vertexArray)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    override fun close() {
        glDeleteFramebuffers(linearFramebuffer)
        glDeleteFramebuffers(bloomFramebuffer)
        glDeleteFramebuffers(finalFramebuffer)
        glDeleteTextures(linearTexture)
        glDeleteTextures(bloomTexture)
        glDeleteTextures(finalTexture)
        glDeleteProgram(hdrCopyProgram)
        glDeleteProgram(bloomProgram)
        glDeleteProgram(finalProgram)
        glDeleteBuffers(vertexBuffer)
        glDeleteVertexArrays(vertexArray)
    }
}

internal val FULLSCREEN_TRIANGLE = floatArrayOf(
    -1f, -1f,
    3f, -1f,
    -1f, 3f
)

internal const val POST_VERTEX_SHADER = """
#version 450 core
layout(location = 0) in vec2 aPosition;
out vec2 texCoord;
void main() {
    texCoord = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

internal const val HDR_COPY_FRAGMENT_SHADER = """
#version 450 core
in vec2 texCoord;
uniform sampler2D uRawHdr;
uniform sampler2D uValleyFog;
uniform sampler2D uSceneDepth;
uniform vec2 uDepthPlanes;
out vec4 color;

float linearDepth(float depth) {
    float near = uDepthPlanes.x;
    float far = uDepthPlanes.y;
    return 2.0 * near * far / (far + near - (depth * 2.0 - 1.0) * (far - near));
}

// Joint bilateral upsample: bilinear weights, damped where a low-resolution texel lies at a different depth,
// so fog does not bleed across tree and ridge silhouettes.
vec4 upsampleValleyFog(vec2 uv) {
    ivec2 lowSize = textureSize(uValleyFog, 0);
    vec2 position = uv * vec2(lowSize) - 0.5;
    ivec2 base = ivec2(floor(position));
    vec2 fraction = position - vec2(base);
    float centerDepth = linearDepth(texture(uSceneDepth, uv).r);
    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + offset, ivec2(0), lowSize - 1);
        float bilinear = (offset.x == 1 ? fraction.x : 1.0 - fraction.x) * (offset.y == 1 ? fraction.y : 1.0 - fraction.y);
        float texelDepth = linearDepth(texture(uSceneDepth, (vec2(texel) + 0.5) / vec2(lowSize)).r);
        float weight = (bilinear + 1.0e-3) / (0.02 + abs(texelDepth - centerDepth) / centerDepth);
        sum += texelFetch(uValleyFog, texel, 0) * weight;
        weightSum += weight;
    }
    return sum / weightSum;
}

void main() {
    vec3 raw = texture(uRawHdr, texCoord).rgb;
    vec4 fog = upsampleValleyFog(texCoord);
    color = vec4(max(raw * fog.a + fog.rgb, vec3(0.0)), 1.0);
}
"""

internal const val BLOOM_ATLAS_FRAGMENT_SHADER = """
#version 450 core
in vec2 texCoord;
uniform sampler2D uLinearHdr;
out vec4 color;
const float weight[7] = float[7](1.0, 6.0, 15.0, 20.0, 15.0, 6.0, 1.0);
vec3 BloomTile(float lod, vec2 offset, vec2 scaledCoord) {
    float scale = exp2(lod);
    vec2 view = vec2(textureSize(uLinearHdr, 0));
    vec2 coord = (scaledCoord - offset) * scale;
    float padding = 0.5 + 0.005 * scale;
    vec3 bloom = vec3(0.0);
    if (abs(coord.x - 0.5) < padding && abs(coord.y - 0.5) < padding) {
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                float wg = weight[i + 3] * weight[j + 3];
                vec2 pixelOffset = vec2(i, j) / view;
                vec2 bloomCoord = (scaledCoord - offset + pixelOffset) * scale;
                bloom += textureLod(uLinearHdr, bloomCoord, lod).rgb * wg;
            }
        }
        bloom /= 4096.0;
    }
    return pow(max(bloom / 128.0, vec3(0.0)), vec3(0.25));
}

void main() {
    vec2 scaledCoord = texCoord;
    vec3 blur = vec3(0.0);
    blur += BloomTile(2.0, vec2(0.0, 0.0), scaledCoord);
    blur += BloomTile(3.0, vec2(0.0, 0.26), scaledCoord);
    blur += BloomTile(4.0, vec2(0.135, 0.26), scaledCoord);
    blur += BloomTile(5.0, vec2(0.2075, 0.26), scaledCoord) * 0.8;
    blur += BloomTile(6.0, vec2(0.135, 0.3325), scaledCoord) * 0.8;
    blur += BloomTile(7.0, vec2(0.160625, 0.3325), scaledCoord) * 0.6;
    blur += BloomTile(8.0, vec2(0.1784375, 0.3325), scaledCoord) * 0.4;
    color = vec4(blur, 1.0);
}
"""

internal const val FINAL_TONEMAP_FRAGMENT_SHADER = """
#version 450 core
in vec2 texCoord;
uniform sampler2D uLinearHdr;
uniform sampler2D uBloomAtlas;
out vec4 color;
const float TM_EXPOSURE = 1.00;
const float TM_CONTRAST = 1.12;
const float TM_WHITE_PATH = 1.00;
const float TM_DARK_DESATURATION = 0.25;
const float T_SATURATION = 1.04;
const float T_VIBRANCE = 1.12;

float GetLuminance(vec3 value) {
    return dot(value, vec3(0.299, 0.587, 0.114));
}

vec3 GetBloomTile(float lod, vec2 coord, vec2 offset) {
    float scale = exp2(lod);
    vec2 bloomCoord = coord / scale + offset;
    bloomCoord = clamp(bloomCoord, offset, 1.0 / scale + offset);
    vec3 bloom = texture(uBloomAtlas, bloomCoord).rgb;
    bloom *= bloom;
    bloom *= bloom;
    return bloom * 128.0;
}

void DoBloom(inout vec3 colorValue, vec2 coord) {
    vec3 blur1 = GetBloomTile(2.0, coord, vec2(0.0, 0.0));
    vec3 blur2 = GetBloomTile(3.0, coord, vec2(0.0, 0.26));
    vec3 blur3 = GetBloomTile(4.0, coord, vec2(0.135, 0.26));
    vec3 blur4 = GetBloomTile(5.0, coord, vec2(0.2075, 0.26));
    vec3 blur5 = GetBloomTile(6.0, coord, vec2(0.135, 0.3325));
    vec3 blur6 = GetBloomTile(7.0, coord, vec2(0.160625, 0.3325));
    vec3 blur7 = GetBloomTile(8.0, coord, vec2(0.1784375, 0.3325));
    vec3 blur = (blur1 + blur2 + blur3 + blur4 + blur5 + blur6 + blur7) * 0.14;
    colorValue = mix(colorValue, blur, 0.12);
}

void LinearToRGB(inout vec3 colorValue) {
    const vec3 k = vec3(0.055);
    colorValue = mix(
        (vec3(1.0) + k) * pow(colorValue, vec3(1.0 / 2.4)) - k,
        12.92 * colorValue,
        lessThan(colorValue, vec3(0.0031308))
    );
}

void DoCompTonemap(inout vec3 colorValue) {
    colorValue = TM_EXPOSURE * colorValue;
    float initialLuminance = GetLuminance(colorValue);
    vec3 a = vec3(TM_CONTRAST);
    vec3 d = vec3(1.0);
    vec3 hdrMax = vec3(8.0);
    vec3 midIn = vec3(0.25);
    vec3 midOut = vec3(0.25);
    vec3 a_d = a * d;
    vec3 hdrMaxA = pow(hdrMax, a);
    vec3 hdrMaxAD = pow(hdrMax, a_d);
    vec3 midInA = pow(midIn, a);
    vec3 midInAD = pow(midIn, a_d);
    vec3 HM1 = hdrMaxA * midOut;
    vec3 HM2 = hdrMaxAD - midInAD;
    vec3 b = (-midInA + HM1) / (HM2 * midOut);
    vec3 c = (hdrMaxAD * midInA - HM1 * midInAD) / (HM2 * midOut);
    vec3 colorOut = pow(colorValue, a) / (pow(colorValue, a_d) * b + c);
    LinearToRGB(colorOut);

    const float darkLiftStart = 0.1;
    const float darkLiftMix = 0.75;
    float darkLift = smoothstep(darkLiftStart, 0.0, initialLuminance);
    vec3 smoothColor = pow(colorValue, vec3(1.0 / 2.2));
    colorOut = mix(
        colorOut,
        smoothColor,
        darkLift * darkLiftMix * max(0.55 - abs(1.05 - TM_CONTRAST), 0.0) / 0.55
    );

    const float wpInputCurveStart = 0.0;
    const float wpInputCurveMax = 16.0;
    float modifiedLuminance = pow(initialLuminance / wpInputCurveMax, 2.0 - TM_WHITE_PATH) * wpInputCurveMax;
    float whitePath = smoothstep(wpInputCurveStart, wpInputCurveMax, modifiedLuminance);
    colorOut = mix(colorOut, vec3(1.0), whitePath);

    const float dpInputCurveStart = 0.1;
    const float dpInputCurveMax = 0.0;
    float desaturatePath = smoothstep(dpInputCurveStart, dpInputCurveMax, initialLuminance);
    colorOut = mix(colorOut, vec3(GetLuminance(colorOut)), desaturatePath * TM_DARK_DESATURATION);
    colorValue = clamp(colorOut, 0.0, 1.0);
}

void DoBSLColorSaturation(inout vec3 colorValue) {
    float saturationFactor = T_SATURATION + 0.07;
    float grayVibrance = (colorValue.r + colorValue.g + colorValue.b) / 3.0;
    float graySaturation = grayVibrance;
    if (saturationFactor < 1.00) graySaturation = dot(colorValue, vec3(0.299, 0.587, 0.114));
    float mn = min(colorValue.r, min(colorValue.g, colorValue.b));
    float mx = max(colorValue.r, max(colorValue.g, colorValue.b));
    float sat = (1.0 - (mx - mn)) * (1.0 - mx) * grayVibrance * 5.0;
    vec3 lightness = vec3((mn + mx) * 0.5);
    colorValue = mix(colorValue, mix(colorValue, lightness, 1.0 - T_VIBRANCE), sat);
    colorValue = mix(
        colorValue,
        lightness,
        (1.0 - lightness) * (2.0 - T_VIBRANCE) / 2.0 * abs(T_VIBRANCE - 1.0)
    );
    colorValue = colorValue * saturationFactor - graySaturation * (saturationFactor - 1.0);
}

void main() {
    vec3 colorValue = texture(uLinearHdr, texCoord).rgb;
    DoBloom(colorValue, texCoord);
    DoCompTonemap(colorValue);
    DoBSLColorSaturation(colorValue);
    color = vec4(clamp(colorValue, 0.0, 1.0), 1.0);
}
"""
