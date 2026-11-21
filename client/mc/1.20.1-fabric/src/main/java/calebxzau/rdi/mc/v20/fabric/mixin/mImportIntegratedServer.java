package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzau.rdi.mc.v20.fabric.syncchunk.SyncChunkIntegratedFabric;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
abstract class mImportIntegratedServer {
    @Inject(method = "publishServer", at = @At("HEAD"), cancellable = true)
    private void rdi$blockLanDuringImport(GameType mode, boolean cheats, int port, CallbackInfoReturnable<Boolean> callback) {
        if (SyncChunkIntegratedFabric.getMarking()) {
            callback.setReturnValue(false);
        }
    }
}
