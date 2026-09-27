package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzau.rdi.mc.v20.fabric.FabricRdiPlayerDataAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class mFabricPlayerRdiData implements FabricRdiPlayerDataAccess {
    private static final String FORGE_DATA_TAG = "ForgeData";
    private static final String PERSISTED_TAG = "PlayerPersisted";
    private static final String RDI_TAG = "rdi";

    @Unique
    private CompoundTag rdi$data = new CompoundTag();

    @Override
    public CompoundTag rdi$getData() {
        return rdi$data;
    }

    @Override
    public void rdi$setData(CompoundTag data) {
        rdi$data = data == null ? new CompoundTag() : data.copy();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void rdi$savePlayerData(CompoundTag tag, CallbackInfo ci) {
        if (rdi$data.isEmpty()) {
            return;
        }
        // Mirror Forge's Entity persistent-data layout so worlds remain portable between loaders.
        CompoundTag forgeData = tag.getCompound(FORGE_DATA_TAG).copy();
        CompoundTag persisted = forgeData.getCompound(PERSISTED_TAG).copy();
        persisted.put(RDI_TAG, rdi$data.copy());
        forgeData.put(PERSISTED_TAG, persisted);
        tag.put(FORGE_DATA_TAG, forgeData);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void rdi$loadPlayerData(CompoundTag tag, CallbackInfo ci) {
        rdi$data = tag.getCompound(FORGE_DATA_TAG)
                .getCompound(PERSISTED_TAG)
                .getCompound(RDI_TAG)
                .copy();
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void rdi$copyPlayerData(ServerPlayer previous, boolean keepEverything, CallbackInfo ci) {
        // Forge persists this data across death/respawn; keep the Fabric behaviour identical.
        rdi$data = ((FabricRdiPlayerDataAccess) previous).rdi$getData().copy();
    }
}
