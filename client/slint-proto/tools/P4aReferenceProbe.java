// Uses compiled Kotlin model/renderer only; no reference source edits.
import calebxzau.rdi.bgrenderer.*;
import calebxzau.rdi.playermodel.core.*;
import calebxzau.rdi.render.GlfwRuntime;
import kotlin.Unit;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

class P4aReferenceProbe extends P3ReferenceProbe {
    static PlayerSkinTexture load(Path path, boolean cape, boolean slim) throws Exception {
        var source=ImageIO.read(path.toFile());int w=64,h=source.getHeight()==source.getWidth()?64:32;
        if(cape)h=32;
        byte[] bytes=new byte[w*h*4];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int argb=source.getRGB(x*source.getWidth()/w,y*source.getHeight()/h),i=(y*w+x)*4;
            bytes[i]=(byte)(argb>>16);bytes[i+1]=(byte)(argb>>8);bytes[i+2]=(byte)argb;bytes[i+3]=(byte)(argb>>>24);
        }
        return new PlayerSkinTexture(w,h,bytes,slim);
    }
    static void mesh(Path output,boolean slim,boolean legacy,boolean cape) throws Exception {
        var mesh=PlayerModelCoreKt.buildPlayerMeshData(slim,legacy,true,cape);
        ByteBuffer data=ByteBuffer.allocate(mesh.getVertices().length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float v:mesh.getVertices())data.putFloat(v);
        Files.write(output.resolve("player-mesh.bin"),data.array());
    }
    static void parameters(Object renderer,double time,boolean cape) throws Exception {
        Matrix4f model=(Matrix4f)field(renderer,"playerModel");
        Matrix4f[] bones=(Matrix4f[])field(renderer,"playerBones");
        ByteBuffer bytes=ByteBuffer.allocate(64*8+16).order(ByteOrder.LITTLE_ENDIAN);
        for(Matrix4f m:new Matrix4f[]{model,bones[0],bones[1],bones[2],bones[3],bones[4],bones[5],bones[6]}) {
            for(float value:m.get(new float[16]))bytes.putFloat(value);
        }
        bytes.putFloat(cape?1f:0f).putFloat(0).putFloat(0).putFloat(0);
        Files.write(output.resolve("player-params-"+time+".bin"),bytes.array());
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        var lease=GlfwRuntime.INSTANCE.acquire();long window=GlfwRuntime.INSTANCE.createHiddenWindow("p4a-reference");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            try(var pipeline=new BackgroundFramePipeline(123L,()->Unit.INSTANCE,BackgroundSceneKind.GoldenValley)) {
                pipeline.initialize();Object framebuffer=field(pipeline,"framebuffer"),renderer=field(pipeline,"renderer");
                glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaColor"));glRenderbufferStorage(GL_RENDERBUFFER,0x8C3A,W,H);
                glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaDepth"));glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,W,H);
                Path steve=Path.of("../ui/assets/src/main/resources/assets/skins/steve.png");
                for(String variant:new String[]{"steve","slim-cape","legacy","hd"}) {
                    output=root.resolve(variant);Files.createDirectories(output);
                    boolean slim=variant.equals("slim-cape"),cape=slim,legacy=variant.equals("legacy");
                    Path skin=legacy?root.getParent().resolve("fixtures/legacy.png"):variant.equals("hd")?root.getParent().resolve("fixtures/hd-steve.png"):steve;
                    var appearance=new BackgroundPlayerAppearance(load(skin,false,slim),cape?load(root.getParent().resolve("fixtures/cape.png"),true,false):null);
                    mesh(output,slim,legacy,cape);
                    double[] times=variant.equals("steve")||cape?new double[]{0,15,45}:new double[]{0};
                    for(double time:times) {
                        pipeline.render(time,appearance,BufferUtils.createByteBuffer(W*H*4),List.of());
                        png("kotlin-matched-"+time);
                        parameters(renderer,time,cape);
                        textureFloats((int)call(framebuffer,"reflectionColorTexture"),"reflection-"+time+".rgb32f",640,360,false);
                    }
                    System.out.println("Captured player "+variant);
                }
                int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("GL error "+error);
            }
        }finally{glfwMakeContextCurrent(0);GlfwRuntime.INSTANCE.destroyWindow(window);GL.setCapabilities(null);lease.close();}
    }
}
