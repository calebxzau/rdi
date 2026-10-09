package calebxzhou.rdi.mc.client.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.ByteBuffer;

@Mixin(BufferBuilder.class)
public interface mPreviewBufferBuilder {
    @Accessor("buffer")
    ByteBuffer rdiPreviewBuffer();
}
