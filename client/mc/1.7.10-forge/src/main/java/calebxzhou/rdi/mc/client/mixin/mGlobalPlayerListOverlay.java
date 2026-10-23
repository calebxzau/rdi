package calebxzhou.rdi.mc.client.mixin;

import calebxzhou.rdi.mc.client.gui.RdiGlobalTabRow;
import calebxzhou.rdi.mc.client.gui.RdiPlayerAvatarCache;
import calebxzhou.rdi.mc.client.network.GlobalPlayerListState;
import calebxzhou.rdi.mc.common.RGlobalPlayerList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.GuiIngameForge;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = GuiIngameForge.class, remap = false)
public class mGlobalPlayerListOverlay {
    private static final int TITLE_COLOR = 0xFFFFD86B;
    private static final int TEXT_COLOR = 0xFFEFEFEF;
    private static final int AVATAR_SIZE = 8;
    private static final int AVATAR_TEXT_GAP = 4;
    private static final float SKIN_TEX_WIDTH_1710 = 64.0F;
    private static final float SKIN_TEX_HEIGHT_1710 = 32.0F;

    @Inject(method = "renderPlayerList", at = @At("HEAD"), cancellable = true, remap = false)
    private void renderRdiGlobalPlayers(int width, int height, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.gameSettings.keyBindPlayerList.getIsKeyPressed()) {
            return;
        }
        RGlobalPlayerList playerList = GlobalPlayerListState.getCurrent();
        List<RGlobalPlayerList.HostEntry> hosts = playerList.hosts();
        if (hosts.isEmpty()) {
            return;
        }

        ArrayList<RdiGlobalTabRow> rows = getRdiGlobalTabRows(hosts);
        if (rows.size() <= 1) {
            return;
        }

        FontRenderer font = mc.fontRenderer;
        int maxTextWidth = 0;
        for (RdiGlobalTabRow row : rows) {
            int rowWidth = font.getStringWidth(row.text);
            if (row.player != null) {
                rowWidth += AVATAR_SIZE + AVATAR_TEXT_GAP;
            }
            maxTextWidth = Math.max(maxTextWidth, rowWidth);
        }
        int panelWidth = Math.min(maxTextWidth + 12, Math.max(120, width - 16));
        int lineHeight = 10;
        int x = Math.max(4, (width - panelWidth) / 2);
        int y = 10;
        int panelHeight = rows.size() * lineHeight + 8;

        Gui.drawRect(x - 4, y - 4, x + panelWidth + 4, y + panelHeight, 0x90000000);
        for (int i = 0; i < rows.size(); i++) {
            RdiGlobalTabRow row = rows.get(i);
            int rowY = y + i * lineHeight;
            int textX = x;
            if (row.player != null) {
                drawAvatar(mc, row.player, x, rowY + 1);
                textX += AVATAR_SIZE + AVATAR_TEXT_GAP;
            }
            font.drawStringWithShadow(row.text, textX, rowY, row.color);
        }
        ci.cancel();
    }

    @NotNull
    private static ArrayList<RdiGlobalTabRow> getRdiGlobalTabRows(List<RGlobalPlayerList.HostEntry> hosts) {
        ArrayList<RdiGlobalTabRow> rows = new ArrayList<>();
        rows.add(new RdiGlobalTabRow("RDI在线玩家", TITLE_COLOR));
        for (RGlobalPlayerList.HostEntry host : hosts) {
            if (host.players().isEmpty()) {
                continue;
            }
            String title = host.modpackName().isEmpty() && host.packVer().isEmpty()
                    ? host.hostName()
                    : host.hostName() + " · " + host.modpackName() + " " + host.packVer();
            rows.add(new RdiGlobalTabRow(title, TEXT_COLOR));
            for (RGlobalPlayerList.PlayerEntry player : host.players()) {
                rows.add(new RdiGlobalTabRow(player.playerName(), TEXT_COLOR, player));
            }
        }
        return rows;
    }

    private static void drawAvatar(Minecraft mc, RGlobalPlayerList.PlayerEntry player, int x, int y) {
        ResourceLocation skin = RdiPlayerAvatarCache.skinOf(player);
        mc.getTextureManager().bindTexture(skin);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        Gui.func_152125_a(x, y, 8.0F, 8.0F, 8, 8, AVATAR_SIZE, AVATAR_SIZE, SKIN_TEX_WIDTH_1710, SKIN_TEX_HEIGHT_1710);
        GL11.glEnable(GL11.GL_BLEND);
        Gui.func_152125_a(x, y, 40.0F, 8.0F, 8, 8, AVATAR_SIZE, AVATAR_SIZE, SKIN_TEX_WIDTH_1710, SKIN_TEX_HEIGHT_1710);
        GL11.glDisable(GL11.GL_BLEND);
    }
}
