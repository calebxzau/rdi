package calebxzhou.rdi.mc.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

public final class RMixinPlugin implements IMixinConfigPlugin {
    private static final String TCR_CORE_FANCY_MENU_LOGO_MIXIN =
            "calebxzhou.rdi.mc.client.mixin.mTcrCoreFancyMenuLogo";
    private static final String TCR_CORE_CLIENT_MOD_EVENTS_TARGET =
            "com.p1nero.tcrcore.events.ClientModEvents";
    private static final String EUPHORIA_WARNING_MIXIN =
            "calebxzhou.rdi.mc.client.mixin.mEuphoriaPatcherWarning";
    private static final String EUPHORIA_WARNING_TARGET =
            "com.euphoriapatches.euphoria_patcher.util.UserInstallErrorMessages";
    private static final String FARMING_TALES_SERVER_LINKS_MIXIN =
            "calebxzhou.rdi.mc.client.mixin.mFarmingTalesServerLinks";
    private static final String FARMING_TALES_SERVER_LINKS_TARGET =
            "com.farmingtales.serverlinks.client.ClientScreenEvents";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (TCR_CORE_FANCY_MENU_LOGO_MIXIN.equals(mixinClassName)) {
            return classExists(TCR_CORE_CLIENT_MOD_EVENTS_TARGET);
        }
        if (EUPHORIA_WARNING_MIXIN.equals(mixinClassName)) {
            return classExists(EUPHORIA_WARNING_TARGET);
        }
        if (FARMING_TALES_SERVER_LINKS_MIXIN.equals(mixinClassName)) {
            return classExists(FARMING_TALES_SERVER_LINKS_TARGET);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean classExists(String className) {
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(className);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
