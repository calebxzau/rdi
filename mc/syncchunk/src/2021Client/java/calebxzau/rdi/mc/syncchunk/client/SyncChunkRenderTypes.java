package calebxzau.rdi.mc.syncchunk.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

/** Extends RenderStateShard only to reach its shards, which are protected in 1.20.1. */
public final class SyncChunkRenderTypes extends RenderStateShard {
    /** Chunk outlines that stay visible through terrain. */
    public static final RenderType LINES = RenderType.create(
            "rdi_sync_chunk_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(OptionalDouble.empty()))
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .createCompositeState(false));

    private SyncChunkRenderTypes(String name, Runnable setupState, Runnable clearState) {
        super(name, setupState, clearState);
    }
}
