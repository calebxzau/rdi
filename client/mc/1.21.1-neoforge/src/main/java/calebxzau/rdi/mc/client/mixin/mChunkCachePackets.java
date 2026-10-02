package calebxzau.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.chunkcache.RdiChunkCache;
import calebxzau.rdi.mc.client.chunkcache.ClientChunkSnapshot;
import calebxzau.rdi.mc.client.chunkcache.ChunkCacheClient;
import calebxzau.rdi.mc.client.chunkcache.ChunkCachePacketAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class mChunkCachePackets implements ChunkCachePacketAccess {
    @Shadow private ClientLevel level;
    @Unique private volatile RdiChunkCache.PacketCaptureContext rdi$packetCaptureContext;

    @Override
    public RdiChunkCache.PacketCaptureContext rdi$cacheContext() {
        return rdi$packetCaptureContext;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rdi$bindPacketCapture(Minecraft minecraft, Connection connection,
                                      CommonListenerCookie cookie, CallbackInfo ci) {
        rdi$packetCaptureContext = RdiChunkCache.createPacketCaptureContext();
    }

    // Capture packet-owned data before vanilla schedules handleLevelChunkWithLight on the client thread.
    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void rdi$captureChunkPacket(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) {
            rdi$captureChunkPacketData(packet);
        }
    }

    @Inject(method = "handleBundlePacket", at = @At("HEAD"))
    private void rdi$captureBundledChunkPackets(ClientboundBundlePacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) return;
        long capturedBytes = 0;
        for (var subPacket : packet.subPackets()) {
            if (subPacket instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
                rdi$captureChunkPacketData(chunkPacket);
            } else {
                long copied = RdiChunkCache.captureDelta(rdi$packetCaptureContext, subPacket,
                        ClientChunkSnapshot.MAX_SNAPSHOT_BYTES - capturedBytes);
                if (copied > 0) capturedBytes += copied;
            }
        }
    }

    @Unique
    private void rdi$captureChunkPacketData(ClientboundLevelChunkWithLightPacket packet) {
        RdiChunkCache.captureBase(rdi$packetCaptureContext, packet.getX(), packet.getZ(), packet.getChunkData());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rdi$closePacketCapture(CallbackInfo ci) {
        ChunkCacheClient.reset((ClientPacketListener) (Object) this);
        if (rdi$packetCaptureContext != null) rdi$packetCaptureContext.close();
        rdi$packetCaptureContext = null;
    }

    // updateLevelChunk is reached after vanilla's main-thread guard, before BE tags are applied.
    @Inject(method = "updateLevelChunk", at = @At("HEAD"))
    private void rdi$receivedChunk(int x, int z, ClientboundLevelChunkPacketData data, CallbackInfo ci) {
        RdiChunkCache.receivedBase(level, x, z, data, rdi$packetCaptureContext);
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void rdi$captureBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) RdiChunkCache.captureDelta(rdi$packetCaptureContext, packet);
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void rdi$repairBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        rdi$dropRepairUpdate(packet, ci);
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void rdi$blockChanged(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        RdiChunkCache.receivedDelta(level, packet, rdi$packetCaptureContext);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void rdi$captureSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) RdiChunkCache.captureDelta(rdi$packetCaptureContext, packet);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"), cancellable = true)
    private void rdi$repairSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        rdi$dropRepairUpdate(packet, ci);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void rdi$sectionChanged(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        RdiChunkCache.receivedDelta(level, packet, rdi$packetCaptureContext);
    }

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"))
    private void rdi$captureBlockEntityUpdate(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) RdiChunkCache.captureDelta(rdi$packetCaptureContext, packet);
    }

    @Inject(method = "handleBlockEntityData", at = @At("HEAD"), cancellable = true)
    private void rdi$repairBlockEntityUpdate(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        rdi$dropRepairUpdate(packet, ci);
    }

    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void rdi$blockEntityChanged(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        RdiChunkCache.receivedDelta(level, packet, rdi$packetCaptureContext);
    }

    @Inject(method = "handleChunksBiomes", at = @At("HEAD"))
    private void rdi$captureBiomeUpdates(ClientboundChunksBiomesPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) RdiChunkCache.captureDelta(rdi$packetCaptureContext, packet);
    }

    @Inject(method = "handleChunksBiomes", at = @At("HEAD"), cancellable = true)
    private void rdi$repairBiomeUpdates(ClientboundChunksBiomesPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        ClientPacketListener listener = (ClientPacketListener) (Object) this;
        ClientboundChunksBiomesPacket filtered = ChunkCacheClient.filterBiomes(listener, packet);
        if (filtered == packet) return;
        RdiChunkCache.discardDelta(rdi$packetCaptureContext, packet);
        if (!filtered.chunkBiomeData().isEmpty()) {
            RdiChunkCache.captureDelta(rdi$packetCaptureContext, filtered);
            listener.handleChunksBiomes(filtered);
        }
        ci.cancel();
    }

    @Inject(method = "handleChunksBiomes", at = @At("TAIL"))
    private void rdi$biomesChanged(ClientboundChunksBiomesPacket packet, CallbackInfo ci) {
        RdiChunkCache.receivedDelta(level, packet, rdi$packetCaptureContext);
    }

    @Unique
    private void rdi$dropRepairUpdate(net.minecraft.network.protocol.Packet<?> packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()
                && ChunkCacheClient.shouldDropUpdate((ClientPacketListener) (Object) this, packet)) {
            RdiChunkCache.discardDelta(rdi$packetCaptureContext, packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleLightUpdatePacket", at = @At("HEAD"), cancellable = true)
    private void rdi$repairLight(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
        rdi$dropRepairUpdate(packet, ci);
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void rdi$fullChunkReceived(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkCacheClient.fullReceived((ClientPacketListener) (Object) this, packet.getX(), packet.getZ());
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
    private void rdi$chunkForgotten(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        ChunkCacheClient.forgotten((ClientPacketListener) (Object) this, packet.pos().x, packet.pos().z);
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void rdi$worldReset(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) ChunkCacheClient.reset((ClientPacketListener) (Object) this);
    }

}
