// Source-file launcher; uses the compiled Kotlin renderer without editing reference sources.
import calebxzau.rdi.bgrenderer.*;
import calebxzau.rdi.render.GlfwRuntime;
import kotlin.Unit;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

class P1VisualProbe {
    static final int W = 1280, H = 720;
    static Path output;
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }
    static void set(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    static Object call(Object object, String name, Object... args) throws Exception {
        for (Method m : object.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(object, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    static void floats(int texture, String name) throws Exception {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        FloatBuffer values = BufferUtils.createFloatBuffer(W * H * 3);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGB, GL_FLOAT, values);
        ByteBuffer bytes = ByteBuffer.allocate(W * H * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = H - 1; y >= 0; y--) for (int x = 0; x < W * 3; x++) bytes.putFloat(values.get(y * W * 3 + x));
        Files.write(output.resolve(name + ".rgb32f"), bytes.array());
    }
    static void png(String name) throws Exception {
        ByteBuffer pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = ((H - 1 - y) * W + x) * 4;
            image.setRGB(x, y, ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8) | (pixels.get(i + 2) & 255));
        }
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
    }
    static int shader(int type, String text) {
        int shader = glCreateShader(type);
        glShaderSource(shader, text);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
        return shader;
    }
    static String constant(String name) throws Exception {
        return (String) Class.forName("calebxzau.rdi.bgrenderer.BackgroundPostProcessorKt").getField(name).get(null);
    }
    static void post(Object post, String name, boolean bloom, boolean saturation, boolean dark) throws Exception {
        post(post, name, bloom, saturation, dark, false, false);
    }
    static void post(Object post, String name, boolean bloom, boolean saturation, boolean dark, boolean unitSaturation, boolean unitVibrance) throws Exception {
        String fragment = constant("FINAL_TONEMAP_FRAGMENT_SHADER");
        if (!bloom) fragment = fragment.replace("    DoBloom(colorValue, texCoord);", "");
        if (!saturation) fragment = fragment.replace("    DoBSLColorSaturation(colorValue);", "");
        if (!dark) fragment = fragment.replace("const float TM_DARK_DESATURATION = 0.25;", "const float TM_DARK_DESATURATION = 0.0;");
        if (unitSaturation) fragment = fragment.replace("const float T_SATURATION = 1.04;", "const float T_SATURATION = 0.93;");
        if (unitVibrance) fragment = fragment.replace("const float T_VIBRANCE = 1.12;", "const float T_VIBRANCE = 1.0;");
        int vs = shader(GL_VERTEX_SHADER, constant("POST_VERTEX_SHADER")), fs = shader(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram();
        glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        glBindFramebuffer(GL_FRAMEBUFFER, (int) field(post, "finalFramebuffer"));
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
        glViewport(0, 0, W, H);
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, (int) field(post, "linearTexture"));
        glUniform1i(glGetUniformLocation(program, "uLinearHdr"), 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, (int) field(post, "bloomTexture"));
        glUniform1i(glGetUniformLocation(program, "uBloomAtlas"), 1);
        glBindVertexArray((int) field(post, "vertexArray"));
        glDrawArrays(GL_TRIANGLES, 0, 3);
        png(name);
        glBindVertexArray(0); glUseProgram(0); glActiveTexture(GL_TEXTURE0);
        glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
    }
    static void variants(Object post, String prefix) throws Exception {
        post(post, prefix + "-full-post", true, true, true);
        post(post, prefix + "-no-bloom", false, true, true);
        post(post, prefix + "-no-saturation", true, false, true);
        post(post, prefix + "-no-dark-desat", true, true, false);
        post(post, prefix + "-unit-saturation", true, true, true, true, false);
        post(post, prefix + "-unit-vibrance", true, true, true, false, true);
        post(post, prefix + "-no-bloom-no-saturation", false, false, true);
        post(post, prefix + "-neutral", false, false, false);
    }
    static void mips(Object renderer) throws Exception {
        int[] textures = (int[]) field(renderer, "materialTextures");
        for (BlockMaterial m : BlockMaterial.values()) {
            if (!m.name().equals("GRASS_TOP") && !m.name().equals("SHORT_GRASS") && !m.name().equals("STONE")) continue;
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, textures[m.ordinal()]);
            System.out.printf(Locale.ROOT, "%s min=%d mag=%d wrap=%d/%d anisotropy=%.1f%n", m.name(),
                glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER), glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER),
                glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S), glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T), glGetTexParameterf(GL_TEXTURE_2D, 0x84FE));
            for (int level = 0; level < 12; level++) {
                int w = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH), h = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT);
                if (w == 0 || h == 0) break;
                ByteBuffer bytes = BufferUtils.createByteBuffer(w * h * 4);
                glGetTexImage(GL_TEXTURE_2D, level, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                byte[] data = new byte[bytes.remaining()]; bytes.get(data);
                Files.write(output.resolve(m.name() + "-mip" + level + ".rgba"), data);
                System.out.println(m.name() + " mip" + level + " " + w + "x" + h);
            }
        }
    }
    static void materialProbes(Object renderer, Object framebuffer) throws Exception {
        Class<?> constants = Class.forName("calebxzau.rdi.bgrenderer.BackgroundRendererKt");
        String vertex = (String) constants.getField("VERTEX_SHADER").get(null);
        String fragment = (String) constants.getField("FRAGMENT_SHADER").get(null);
        fragment = fragment.replace("out vec4 color;", "out vec4 color;\nuniform int uVisualProbe;")
            .replace("color = vec4(litColor, 1.0);", """
                color = vec4(litColor, 1.0);
                if (uVisualProbe == 1) color = vec4(texel.rgb, 1.0);
                if (uVisualProbe == 2) color = vec4(vColor, 1.0);
                if (uVisualProbe == 3) color = vec4(vAmbientOcclusion, ndotl, shadowFactor, 1.0);
                if (uVisualProbe == 4) color = vec4(length(dFdx(vUv) * gradientScale), length(dFdy(vUv) * gradientScale), 0.0, 1.0);
                if (uVisualProbe == 5) color = vec4((uDoubleSided && normal.y > 0.5) ? 1.0 : 0.0, translucency, texel.a, 1.0);
                """);
        int vs = shader(GL_VERTEX_SHADER, vertex), fs = shader(GL_FRAGMENT_SHADER, fragment), program = glCreateProgram();
        glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(program));
        String[][] names = {
            {"projectionLocation", "uProjection"}, {"viewLocation", "uView"}, {"sunLocation", "uSunDirection"},
            {"eyePositionLocation", "uEyePosition"}, {"clipHeightLocation", "uClipHeight"}, {"fogFromWaterLocation", "uFogFromWater"},
            {"textureLocation", "uTexture"}, {"alphaCutoutLocation", "uAlphaCutout"}, {"textureVScaleLocation", "uTextureVScale"},
            {"emissiveLocation", "uEmissive"}, {"doubleSidedLocation", "uDoubleSided"}, {"foliageTimeLocation", "uFoliageTime"},
            {"shadowViewProjectionLocation", "uShadowViewProjection"}, {"shadowMapLocation", "uShadowMap"}, {"shadowDepthRangeLocation", "uShadowDepthRange"}
        };
        int original = (int) field(renderer, "program");
        int[] locations = new int[names.length];
        for (int i = 0; i < names.length; i++) { locations[i] = (int) field(renderer, names[i][0]); set(renderer, names[i][0], glGetUniformLocation(program, names[i][1])); }
        set(renderer, "program", program);
        glUseProgram(program);
        glUniform3f(glGetUniformLocation(program, "uAmbientLight"), .33f, .40f, .56f);
        glUniform3f(glGetUniformLocation(program, "uDirectLight"), 1.70f, 1.18f, .66f);
        glUniform1i(glGetUniformLocation(program, "uNoon"), 0);
        try {
            for (int probe = 1; probe <= 5; probe++) {
                glUseProgram(program); glUniform1i(glGetUniformLocation(program, "uVisualProbe"), probe);
                call(framebuffer, "bindForRender"); call(renderer, "renderSceneBeforeWater", 0.0, null); call(framebuffer, "resolveRawHdr");
                floats((int) call(framebuffer, "rawHdrTexture"), "kotlin-probe" + probe);
            }
        } finally {
            set(renderer, "program", original);
            for (int i = 0; i < names.length; i++) set(renderer, names[i][0], locations[i]);
            glUseProgram(0); glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
        }
    }
    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]); Files.createDirectories(output);
        var lease = GlfwRuntime.INSTANCE.acquire();
        long window = GlfwRuntime.INSTANCE.createHiddenWindow("p1-visual-probe");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            System.out.println("GL renderer=" + glGetString(GL_RENDERER));
            try (var pipeline = new BackgroundFramePipeline(123L, () -> Unit.INSTANCE, BackgroundSceneKind.GoldenValley)) {
                pipeline.initialize();
                Object post = field(pipeline, "postProcessor"), framebuffer = field(pipeline, "framebuffer"), renderer = field(pipeline, "renderer");
                pipeline.render(0.0, null, BufferUtils.createByteBuffer(W * H * 4), List.of());
                floats((int) call(framebuffer, "rawHdrTexture"), "kotlin-full-raw");
                floats((int) field(post, "linearTexture"), "kotlin-full-linear");
                variants(post, "kotlin-full");
                int foggedInput = (int) field(post, "linearTexture");
                try {
                    set(post, "linearTexture", call(framebuffer, "rawHdrTexture"));
                    post(post, "kotlin-full-no-fog-no-bloom", false, true, true);
                } finally { set(post, "linearTexture", foggedInput); }
                // Re-run the original pre-water scene without players; post has no marched fog.
                call(framebuffer, "bindForRender");
                call(renderer, "renderSceneBeforeWater", 0.0, null);
                call(framebuffer, "resolveRawHdr");
                floats((int) call(framebuffer, "rawHdrTexture"), "kotlin-p1-raw");
                call(post, "process", call(framebuffer, "rawHdrTexture"), 0, call(framebuffer, "sceneDepthTexture"));
                variants(post, "kotlin-p1");
                mips(renderer);
                // Match the approved Rust AA-off mode for interior texture/lighting attribution.
                glBindRenderbuffer(GL_RENDERBUFFER, (int) field(framebuffer, "msaaColor"));
                glRenderbufferStorage(GL_RENDERBUFFER, 0x8C3A, W, H);
                System.out.println("AA-off color renderbuffer samples=" + glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES));
                glBindRenderbuffer(GL_RENDERBUFFER, (int) field(framebuffer, "msaaDepth"));
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
                System.out.println("AA-off depth renderbuffer samples=" + glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES));
                call(framebuffer, "bindForRender"); call(renderer, "renderSceneBeforeWater", 0.0, null); call(framebuffer, "resolveRawHdr");
                floats((int) call(framebuffer, "rawHdrTexture"), "kotlin-p1-aa1-raw");
                call(post, "process", call(framebuffer, "rawHdrTexture"), 0, call(framebuffer, "sceneDepthTexture"));
                variants(post, "kotlin-p1-aa1");
                materialProbes(renderer, framebuffer);
                int error = glGetError();
                if (error != GL_NO_ERROR) throw new IllegalStateException("GL error " + error);
            }
        } finally {
            glfwMakeContextCurrent(0); GlfwRuntime.INSTANCE.destroyWindow(window); GL.setCapabilities(null); lease.close();
        }
    }
}
