package calebxzhou.rdi.mc.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * calebxzhou @ 2026-01-10 22:15
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public class mPlayerInfoUpdatePacket {
    @Shadow
    @Final
    private List<ClientboundPlayerInfoUpdatePacket.Entry> entries;
    @Unique
    private static final Logger lgr = LogManager.getLogger("rdi-player-info-update");
    @Shadow
    @Final
    private EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions;

    @Inject(method = "<init>(Lnet/minecraft/network/RegistryFriendlyByteBuf;)V", at = @At("TAIL"))
    private void RDI$InjectPlayerInfoUpdatePacketTail(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        //读取皮肤数据（服务端不会发） 写入entries里
        //在这里注入不会阻塞主线程
        if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            var newEntries = entries.stream().map(entry -> {
                lgr.info("Inject Profile for {}",entry.profileId());
                var profileResult = Minecraft.getInstance().getMinecraftSessionService().fetchProfile(entry.profileId(), false);
                if (profileResult == null) {
                    lgr.warn("Could not fetch profile for {}; keeping original player info", entry.profileId());
                    return entry;
                }
                var profile = profileResult.profile();
                lgr.info("Injected profile for {}: {}", entry.profileId(), profile.getProperties().get("textures"));
                return new ClientboundPlayerInfoUpdatePacket.Entry(
                        entry.profileId(),
                        profile,
                        entry.listed(),
                        entry.latency(),
                        entry.gameMode(),
                        entry.displayName(),
                        entry.chatSession());
            }).toList();
            entries.clear();
            entries.addAll(newEntries);
        }
    }
}
