package calebxzhou.rdi.mc.server.mixin;

import calebxzau.rdi.mc.v20.server.scoreboard.RedundantTeamJoin;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skips joins to the member's current team: vanilla would broadcast a no-op leave and join. */
@Mixin(ServerScoreboard.class)
public abstract class mRedundantTeamJoin {
    @Inject(method = "addPlayerToTeam", at = @At("HEAD"), cancellable = true)
    private void rdi$skipSameTeam(String playerName, PlayerTeam team, CallbackInfoReturnable<Boolean> cir) {
        // Vanilla returns true here too: the member is removed and then re-added successfully.
        if (RedundantTeamJoin.shouldSkip((Scoreboard) (Object) this, playerName, team)) cir.setReturnValue(true);
    }
}
