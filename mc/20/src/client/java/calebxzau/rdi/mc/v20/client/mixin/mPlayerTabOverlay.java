package calebxzau.rdi.mc.v20.client.mixin;

import calebxzau.rdi.mc.v20.client.GlobalPlayerAvatarCache;
import calebxzau.rdi.mc.v20.client.GlobalPlayerListState;
import calebxzau.rdi.mc.v20.client.RTabRow;
import calebxzau.rdi.mc.v20.client.TabLayoutCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.Connection;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PlayerTabOverlay.class)
public abstract class mPlayerTabOverlay {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;isEncrypted()Z"))
    private boolean rdi$alwaysDisplayAvatar(Connection connection) {
        return true;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rdi$renderGlobalPlayers(GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        GlobalPlayerListState.Snapshot snapshot = GlobalPlayerListState.snapshot();
        if (snapshot.getPlayerCount() == 0) return;

        Minecraft minecraft = Minecraft.getInstance();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        if (width <= 16 || screenHeight < 30) return;
        var font = minecraft.font;
        TabLayoutCache.Layout layout = TabLayoutCache.get(snapshot, width, screenHeight, font);
        List<RTabRow> rows = layout.rows();
        int x = layout.x();
        int y = layout.y();
        int panelHeight = rows.size() * layout.lineHeight() + layout.padding();
        graphics.fill(x - 4, y - 4, x + layout.panelWidth() + 4, Math.min(screenHeight - 2, y + panelHeight), 0x90000000);
        for (int i = 0; i < rows.size(); i++) {
            RTabRow row = rows.get(i);
            int rowY = y + i * layout.lineHeight();
            int textX = x;
            if (row.isPlayer()) {
                PlayerFaceRenderer.draw(graphics, GlobalPlayerAvatarCache.skin(row.playerId(), row.text()), x, rowY, 8);
                textX += 11;
            }
            graphics.drawString(font, layout.displayText().get(i), textX, rowY, row.color(), false);
        }
        ci.cancel();
    }

}
