package calebxzau.rdi.mc.server.mixin;

import calebxzhou.rdi.mc.server.network.RServerPersistentPackets;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

// NeoForge queues modded configuration tasks after the tag synchronisation, too late to name kept tags.
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class mPersistentPacketTask {
    @Shadow
    @Final
    private Queue<ConfigurationTask> configurationTasks;

    @Inject(
            method = "runConfiguration",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/network/ConfigurationInitialization;configureEarlyTasks(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;Ljava/util/function/Consumer;)V"
            )
    )
    private void RDI$askForPersistentPackets(CallbackInfo ci) {
        ConfigurationTask task = RServerPersistentPackets.task((ServerConfigurationPacketListener) (Object) this);
        if (task != null) configurationTasks.add(task);
    }
}
