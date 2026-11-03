package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.client.DmHelloTicket;
import calebxzau.rdi.mc.client.dm.DmJoinCheck;
import calebxzau.rdi.mc.client.dm.DmJoinGate;
import calebxzau.rdi.mc.client.dm.DmJoinTicketVerifier;
import calebxzau.rdi.mc.client.dm.DmParticipantSession;
import calebxzhou.rdi.mc.common.RDI;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * calebxzhou @ 2025-10-21 10:33
 */
@Mixin(ServerboundHelloPacket.class)
class mServerboundHelloPacket implements DmHelloTicket {
    @Unique
    private String rdi$joinTicket;

    // Only connections to a DM room have a ticket, so other servers see the plain vanilla packet.
    @Inject(method = "write", at = @At("TAIL"))
    private void RDI$WriteJoinTicket(FriendlyByteBuf buf, CallbackInfo ci) {
        String ticket = DmParticipantSession.takeHelloTicket();
        if (ticket != null) buf.writeUtf(ticket, DmJoinTicketVerifier.MAX_TICKET_CHARS);
    }

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void RDI$ReadJoinTicket(FriendlyByteBuf buf, CallbackInfo ci) {
        if (buf.isReadable()) rdi$joinTicket = buf.readUtf(DmJoinTicketVerifier.MAX_TICKET_CHARS);
    }

    @Override
    public String rdi$joinTicket() {
        return rdi$joinTicket;
    }

    @ModifyConstant(method = "write",constant = @Constant(intValue = 16))
    private static int RDI$WiderLengthForChineseName(int constant){
        return 64;
    }

    // The integrated server reads guests' hellos too, e.g. in a DM room.
    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @Constant(intValue = 16))
    private static int RDI$WiderReadLengthForChineseName(int constant){
        return 64;
    }
}
/**
 * Worlds opened to others (DM rooms, LAN) skip the Mojang session check: players have RDI accounts,
 * and {@link mServerLoginPacketListener} takes their id from the hello packet instead.
 */
@Mixin(IntegratedServer.class)
class mIntegratedServerOffline {
    @Inject(method = "publishServer", at = @At("HEAD"))
    private void RDI$OfflineWhenPublished(GameType gameMode, boolean cheats, int port, CallbackInfoReturnable<Boolean> cir) {
        ((IntegratedServer) (Object) this).setUsesAuthentication(false);
    }
}
/**
 * The integrated server's side of logging in, used when other players join a DM room or a LAN world:
 * guests keep their RDI player id, as on RDI servers, instead of an offline id derived from the name.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class mServerLoginPacketListener {
    @Shadow
    @Final
    MinecraftServer server;
    @Shadow
    @Final
    Connection connection;
    @Unique
    private GameProfile rdi$submittedProfile;

    @Shadow
    public abstract void disconnect(Component reason);

    // The host's own memory connection keeps the singleplayer profile, which carries the skin.
    // A guest with the host's id would kick the host out of their own world.
    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void RDI$CaptureSubmittedId(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (connection.isMemoryConnection()) return;
        GameProfile submitted = new GameProfile(packet.profileId(), packet.name());
        // In a DM room the signed ticket decides who this is, not what the guest says about itself.
        DmJoinCheck check = DmJoinGate.check(server, ((DmHelloTicket) (Object) packet).rdi$joinTicket());
        if (check instanceof DmJoinCheck.Refused refused) {
            disconnect(Component.literal(refused.getReason()));
            ci.cancel();
            return;
        }
        if (check instanceof DmJoinCheck.Allowed allowed) {
            submitted = new GameProfile(allowed.getPlayerId(), allowed.getPlayerName());
        }
        GameProfile host = server.getSingleplayerProfile();
        if (host != null && submitted.getId().equals(host.getId())) {
            disconnect(Component.literal("这个账号正在当房主，不能再以访客身份进入"));
            ci.cancel();
            return;
        }
        rdi$submittedProfile = submitted;
    }

    // Every way in ends here, with or without a session check, so the offline id derived from the name never sticks.
    @ModifyVariable(method = "startClientVerification", at = @At("HEAD"), argsOnly = true)
    private GameProfile RDI$UseSubmittedId(GameProfile profile) {
        return rdi$submittedProfile != null ? rdi$submittedProfile : profile;
    }

    // 120 seconds instead of 30, so a slow link can finish logging in. Optional and chainable, since
    // network mods such as Connectivity modify the same constant.
    @ModifyExpressionValue(method = "tick", at = @At(value = "CONSTANT", args = "intValue=600"), require = 0)
    private int RDI$LoginTimeoutTicks(int ticks) {
        return 2400;
    }
}
@Mixin(ClientIntentionPacket.class)
class mClientIntentionPacket {
    @Shadow
    @Final
    @Mutable
    private int port;

    @Inject(method = "<init>(ILjava/lang/String;ILnet/minecraft/network/protocol/handshake/ClientIntent;)V",at=@At("TAIL"))
    private void RDI$InjectHostPort(int protocolVersion, String hostName, int port, ClientIntent intention, CallbackInfo ci){
        this.port = RDI.HOST_PORT;
    }
    @Redirect(method = "write",at= @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeShort(I)Lnet/minecraft/network/FriendlyByteBuf;"))
    private FriendlyByteBuf RDI$WriteCorrectPort(FriendlyByteBuf buf, int value){
        buf.writeShort(RDI.HOST_PORT);
        return buf;
    }
}