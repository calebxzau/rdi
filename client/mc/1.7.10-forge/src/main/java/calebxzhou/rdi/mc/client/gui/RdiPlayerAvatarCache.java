package calebxzhou.rdi.mc.client.gui;

import calebxzhou.rdi.mc.common.RGlobalPlayerList;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RdiPlayerAvatarCache {
    private static final long RETRY_INTERVAL_MS = 30000L;
    private static final Map<String, ResourceLocation> SKINS = new ConcurrentHashMap<>();
    private static final Map<String, Long> REQUESTED_AT = new ConcurrentHashMap<>();

    private RdiPlayerAvatarCache() {
    }

    public static ResourceLocation skinOf(RGlobalPlayerList.PlayerEntry player) {
        String playerId = player.playerId();
        if (playerId == null || playerId.isEmpty()) {
            return AbstractClientPlayer.locationStevePng;
        }
        ResourceLocation skin = SKINS.get(playerId);
        if (skin != null) {
            return skin;
        }

        requestSkin(player);
        return AbstractClientPlayer.locationStevePng;
    }

    private static void requestSkin(RGlobalPlayerList.PlayerEntry player) {
        String playerId = player.playerId();
        long now = System.currentTimeMillis();
        Long requestedAt = REQUESTED_AT.get(playerId);
        if (requestedAt != null && now - requestedAt < RETRY_INTERVAL_MS) {
            return;
        }
        REQUESTED_AT.put(playerId, now);

        UUID profileId;
        try {
            profileId = UUID.fromString(playerId);
        } catch (IllegalArgumentException exception) {
            return;
        }

        GameProfile profile = new GameProfile(profileId, player.playerName());
        Minecraft.getMinecraft().func_152342_ad().func_152790_a(
            profile,
            new SkinManager.SkinAvailableCallback() {
                @Override
                public void func_152121_a(MinecraftProfileTexture.Type skinPart, ResourceLocation skinLoc) {
                    if (skinPart == MinecraftProfileTexture.Type.SKIN) {
                        SKINS.put(playerId, skinLoc);
                    }
                }
            },
            true
        );
    }
}
