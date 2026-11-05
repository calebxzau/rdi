// Disposable matched GL captures; reference Kotlin sources and shaders are unchanged.
import calebxzau.rdi.bgrenderer.*;
import calebxzau.rdi.render.GlfwRuntime;
import kotlin.Unit;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

class P3ReferenceProbe extends P2ReferenceProbe {
    static void rgba(int texture,String name,int width,int height) throws Exception {
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);
        FloatBuffer values=BufferUtils.createFloatBuffer(width*height*4);
        glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_FLOAT,values);
        ByteBuffer bytes=ByteBuffer.allocate(width*height*16).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=height-1;y>=0;y--)for(int x=0;x<width*4;x++)bytes.putFloat(values.get(y*width*4+x));
        Files.write(output.resolve(name),bytes.array());
    }
    public static void main(String[] args) throws Exception {
        output=Path.of(args[0]);Files.createDirectories(output);
        var lease=GlfwRuntime.INSTANCE.acquire();
        long window=GlfwRuntime.INSTANCE.createHiddenWindow("p3-reference-probe");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println("GL renderer="+glGetString(GL_RENDERER));
            try(var pipeline=new BackgroundFramePipeline(123L,()->Unit.INSTANCE,BackgroundSceneKind.GoldenValley)) {
                pipeline.initialize();
                Object framebuffer=field(pipeline,"framebuffer"),post=field(pipeline,"postProcessor"),fog=field(pipeline,"valleyFog");
                glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaColor"));glRenderbufferStorage(GL_RENDERBUFFER,0x8C3A,W,H);
                glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaDepth"));glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,W,H);
                for(double time:new double[]{0.,15.,45.}) {
                    pipeline.render(time,null,BufferUtils.createByteBuffer(W*H*4),List.of());
                    png("kotlin-matched-"+time);
                    floats((int)call(framebuffer,"rawHdrTexture"),"hdr-"+time);
                    floats((int)field(post,"linearTexture"),"linear-"+time);
                    rgba((int)field(fog,"texture"),"fog-"+time+".rgba32f",640,360);
                    rgba((int)field(post,"bloomTexture"),"bloom-"+time+".rgba32f",W,H);
                    textureFloats((int)call(framebuffer,"sceneDepthTexture"),"depth-"+time+".depth32f",W,H,true);
                    textureFloats((int)call(framebuffer,"reflectionColorTexture"),"reflection-"+time+".rgb32f",640,360,false);
                    System.out.println("Captured matched time="+time);
                }
                glBindTexture(GL_TEXTURE_3D,(int)field(fog,"noiseTexture"));
                ByteBuffer bytes=BufferUtils.createByteBuffer(64*64*64);glGetTexImage(GL_TEXTURE_3D,0,GL_RED,GL_UNSIGNED_BYTE,bytes);
                byte[] data=new byte[bytes.remaining()];bytes.get(data);Files.write(output.resolve("fog-noise.r8"),data);
                int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("GL error "+error);
            }
        }finally{glfwMakeContextCurrent(0);GlfwRuntime.INSTANCE.destroyWindow(window);GL.setCapabilities(null);lease.close();}
    }
}
