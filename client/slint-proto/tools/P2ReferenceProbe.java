// Compiled with P1VisualProbe.java; all reflection/program edits stay in this disposable GL process.
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
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.*;

class P2ReferenceProbe extends P1VisualProbe {
    static void textureFloats(int texture,String name,int width,int height,boolean depth) throws Exception {
        glBindTexture(GL_TEXTURE_2D,texture);int channels=depth?1:3;
        FloatBuffer values=BufferUtils.createFloatBuffer(width*height*channels);
        glGetTexImage(GL_TEXTURE_2D,0,depth?GL_DEPTH_COMPONENT:GL_RGB,GL_FLOAT,values);
        ByteBuffer bytes=ByteBuffer.allocate(width*height*channels*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=height-1;y>=0;y--)for(int x=0;x<width*channels;x++)bytes.putFloat(values.get(y*width*channels+x));
        Files.write(output.resolve(name),bytes.array());
    }
    public static void main(String[] args) throws Exception {
        output=Path.of(args[0]);Files.createDirectories(output);
        var lease=GlfwRuntime.INSTANCE.acquire();
        long window=GlfwRuntime.INSTANCE.createHiddenWindow("p2-reference-probe");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println("GL renderer="+glGetString(GL_RENDERER));
            try(var pipeline=new BackgroundFramePipeline(123L,()->Unit.INSTANCE,BackgroundSceneKind.GoldenValley)) {
                pipeline.initialize();
                Object renderer=field(pipeline,"renderer"),framebuffer=field(pipeline,"framebuffer"),post=field(pipeline,"postProcessor");
                // A true nonmultisample control for matching the accepted Rust AA mode.
                glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaColor"));glRenderbufferStorage(GL_RENDERBUFFER,0x8C3A,W,H);
                glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaDepth"));glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,W,H);
                for(double time:new double[]{0.,15.,45.}) {
                    pipeline.render(time,null,BufferUtils.createByteBuffer(W*H*4),List.of());
                    png("kotlin-full-"+time);
                    floats((int)call(framebuffer,"rawHdrTexture"),"kotlin-raw-"+time);
                    textureFloats((int)call(framebuffer,"sceneDepthTexture"),"depth-"+time+".depth32f",W,H,true);
                    textureFloats((int)call(framebuffer,"reflectionColorTexture"),"reflection-"+time+".rgb32f",640,360,false);
                    call(post,"process",call(framebuffer,"rawHdrTexture"),0,call(framebuffer,"sceneDepthTexture"));
                    post(post,"kotlin-p2-"+time,false,true,true);
                    int count=(int)field(renderer,"cloudVertexCount");
                    ByteBuffer vertices=BufferUtils.createByteBuffer(count*48);
                    glBindBuffer(GL_ARRAY_BUFFER,(int)field(renderer,"cloudVertexBuffer"));glGetBufferSubData(GL_ARRAY_BUFFER,0,vertices);
                    byte[] data=new byte[vertices.remaining()];vertices.get(data);Files.write(output.resolve("clouds-"+time+".bin"),data);
                    System.out.println("time="+time+" cloudVertices="+count);
                    glBindTexture(GL_TEXTURE_2D,(int)field(renderer,"cloudTexture"));
                    int width=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH),height=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT);
                    ByteBuffer pixels=BufferUtils.createByteBuffer(width*height*4);glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                    byte[] texture=new byte[pixels.remaining()];pixels.get(texture);Files.write(output.resolve("cloud-cover.rgba"),texture);
                    System.out.println("cloud texture="+width+"x"+height);
                }
                int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("GL error "+error);
            }
        }finally{glfwMakeContextCurrent(0);GlfwRuntime.INSTANCE.destroyWindow(window);GL.setCapabilities(null);lease.close();}
    }
}
