package calebxzau.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.dm.DmRegionChangeLog;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Taps only vanilla RegionFile writes under the currently hosted world root. */
@Mixin(RegionFile.class)
public abstract class mDmRegionFile {
    @Unique private final ThreadLocal<byte[]> rdi$pending = new ThreadLocal<>();

    @Inject(method = "write", at = @At("HEAD"))
    private void rdi$captureWrite(ChunkPos chunkPos, ByteBuffer buffer, CallbackInfo callbackInfo) {
        if (!DmRegionChangeLog.isActive()) return;
        Path path = ((AccessorRegionFile) this).rdi$getPath();
        if (!DmRegionChangeLog.isUnderActiveWorld(path)) return;
        ByteBuffer duplicate = buffer.duplicate();
        byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        rdi$pending.set(bytes);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void rdi$recordWrite(ChunkPos chunkPos, ByteBuffer buffer, CallbackInfo callbackInfo) {
        byte[] bytes = rdi$pending.get();
        rdi$pending.remove();
        if (bytes == null) return;
        AccessorRegionFile accessor = (AccessorRegionFile) this;
        Path path = accessor.rdi$getPath();
        Path root = DmRegionChangeLog.activeWorldRoot();
        if (root == null) return;
        String region = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        int index = (chunkPos.getRegionLocalZ() << 5) | chunkPos.getRegionLocalX();
        IntBuffer timestamps = accessor.rdi$getTimestamps();
        IntBuffer offsets = accessor.rdi$getOffsets();
        int timestamp = timestamps.get(index);
        int sectors = offsets.get(index) & 0xff;
        DmRegionChangeLog.recordWrite(region, index, timestamp, sectors, bytes);
    }

    @Inject(method = "clear", at = @At("RETURN"))
    private void rdi$recordClear(ChunkPos chunkPos, CallbackInfo callbackInfo) {
        if (!DmRegionChangeLog.isActive()) return;
        AccessorRegionFile accessor = (AccessorRegionFile) this;
        Path path = accessor.rdi$getPath();
        Path root = DmRegionChangeLog.activeWorldRoot();
        if (root == null || !DmRegionChangeLog.isUnderActiveWorld(path)) return;
        String region = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        int index = (chunkPos.getRegionLocalZ() << 5) | chunkPos.getRegionLocalX();
        DmRegionChangeLog.recordClear(region, index, accessor.rdi$getTimestamps().get(index));
    }
}
