// Uses compiled Kotlin crowd classes only; no reference source edits. AA off to match the Rust default.
import calebxzau.rdi.bgrenderer.*;
import calebxzau.rdi.playermodel.core.*;
import calebxzau.rdi.render.GlfwRuntime;
import kotlin.Unit;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

class P4bReferenceProbe extends P4aReferenceProbe {
    static Object get(Object target,String getter) throws Exception {
        Method method=target.getClass().getMethod(getter);method.setAccessible(true);return method.invoke(target);
    }
    static void route(Object simulation,Path file) throws Exception {
        Object route=get(simulation,"getRoute");
        StringBuilder nodes=new StringBuilder();
        for(Object node:(List<?>)get(route,"getNodes")) {
            if(nodes.length()>0)nodes.append(',');
            nodes.append('[').append(get(node,"getX")).append(',').append(get(node,"getZ")).append(',').append(get(node,"getHeight")).append(']');
        }
        Files.writeString(file,"{\"nodes\":["+nodes+"],\"summitStart\":"+get(route,"getSummitStartIndex")+",\"summitLapEnd\":"+get(route,"getSummitLapEndIndex")
            +",\"descentStart\":"+get(route,"getDescentStartIndex")+",\"initialSlot\":"+get(route,"getInitialSlot")+",\"travel\":"+get(route,"getTravelSeconds")
            +",\"headway\":"+get(route,"getHeadwaySeconds")+",\"cycle\":"+get(route,"getCycleSeconds")+"}");
    }
    static void poses(Object simulation,double time,Path file) throws Exception {
        StringBuilder runners=new StringBuilder();
        for(Object runner:(Collection<?>)get(simulation,"getRunners")) {
            if(runners.length()>0)runners.append(',');
            runners.append("{\"id\":\"").append(get(runner,"getId")).append("\",\"slot\":").append(get(runner,"getSlot")).append(",\"visible\":").append(get(runner,"getVisible"));
            for(String key:new String[]{"X","Y","Z","Yaw","Phase","ShadowX","ShadowY","ShadowZ"}) {
                String name=key.substring(0,1).toLowerCase()+key.substring(1);
                runners.append(",\"").append(name).append("\":").append(get(runner,"get"+key));
            }
            runners.append('}');
        }
        Files.writeString(file,"{\"time\":"+time+",\"runners\":["+runners+"]}");
    }
    static List<BackgroundCrowdPlayer> crowd(List<Path> skins) throws Exception {
        List<BackgroundCrowdPlayer> players=new ArrayList<>();
        Map<Path,PlayerSkinTexture> cache=new HashMap<>();
        for(int i=0;i<50;i++) {
            Path path=skins.get(i%skins.size());
            PlayerSkinTexture skin=cache.get(path);
            if(skin==null) {
                PlayerSkinTexture raw=load(path,false,false);
                skin=new PlayerSkinTexture(64,raw.getHeight(),raw.getRgba(),PlayerModelCoreKt.detectSlimSkin(64,raw.getHeight(),raw.getRgba()));
                cache.put(path,skin);
            }
            players.add(new BackgroundCrowdPlayer("preview-"+i,skin));
        }
        return players;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path steve=Path.of("../ui/assets/src/main/resources/assets/skins/steve.png");
        List<Path> mixed;
        try(Stream<Path> files=Files.list(Path.of(args[1]))) {
            mixed=files.filter(p->p.toString().endsWith(".png")).sorted().collect(Collectors.toList());
        }
        if(mixed.isEmpty())throw new IllegalStateException("No mixed crowd fixtures in "+args[1]);
        var lease=GlfwRuntime.INSTANCE.acquire();long window=GlfwRuntime.INSTANCE.createHiddenWindow("p4b-reference");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            for(String variant:new String[]{"steve","mixed"}) {
                try(var pipeline=new BackgroundFramePipeline(123L,()->Unit.INSTANCE,BackgroundSceneKind.GoldenValley)) {
                    pipeline.initialize();
                    Object framebuffer=field(pipeline,"framebuffer"),renderer=field(pipeline,"renderer");
                    glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaColor"));glRenderbufferStorage(GL_RENDERBUFFER,0x8C3A,W,H);
                    glBindRenderbuffer(GL_RENDERBUFFER,(int)field(framebuffer,"msaaDepth"));glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,W,H);
                    output=root.resolve(variant);Files.createDirectories(output);
                    var appearance=new BackgroundPlayerAppearance(load(steve,false,false),null);
                    List<BackgroundCrowdPlayer> players=crowd(variant.equals("steve")?List.of(steve):mixed);
                    ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);
                    pipeline.render(0.0,appearance,pixels,players);
                    Object simulation=field(renderer,"crowdSimulation");
                    route(simulation,output.resolve("crowd-route.json"));
                    double simulated=0;
                    for(double time:new double[]{0,15,45}) {
                        while(simulated<time) {
                            simulated=Math.min(time,simulated+1.0/60);
                            pipeline.render(simulated,appearance,pixels,players);
                        }
                        pipeline.render(time,appearance,pixels,players);
                        png("kotlin-matched-"+time);
                        poses(simulation,time,output.resolve("crowd-poses-"+time+".json"));
                        System.out.println("Captured crowd "+variant+" time="+time);
                    }
                    int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("GL error "+error);
                }
            }
        }finally{glfwMakeContextCurrent(0);GlfwRuntime.INSTANCE.destroyWindow(window);GL.setCapabilities(null);lease.close();}
    }
}
