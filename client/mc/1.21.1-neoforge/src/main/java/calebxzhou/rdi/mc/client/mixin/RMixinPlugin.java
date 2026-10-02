package calebxzhou.rdi.mc.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

public final class RMixinPlugin implements IMixinConfigPlugin {
    private static final String EUPHORIA_WARNING_MIXIN =
            "calebxzhou.rdi.mc.client.mixin.mEuphoriaPatcherWarning";
    private static final String EUPHORIA_WARNING_TARGET =
            "com.euphoriapatches.euphoria_patcher.util.UserInstallErrorMessages";
    private static final String KUBEJS_WINDOW_ICON_MIXIN =
            "calebxzhou.rdi.mc.client.mixin.mKubeJsWindowIcon";
    private static final String KUBEJS_WINDOW_ICON_TARGET =
            "dev.latvian.mods.kubejs.core.WindowKJS$KJSScaledIconProvider";
    private static final String AE2WTLIB_RESTOCK_GUARD_MIXIN =
            "calebxzhou.rdi.mc.client.mixin.mGuiRestockGuard";
    private static final String AE2WTLIB_GUI_MIXIN_TARGET =
            "de.mari_023.ae2wtlib.mixin.GuiMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (EUPHORIA_WARNING_MIXIN.equals(mixinClassName)) {
            return classExists(EUPHORIA_WARNING_TARGET);
        }
        if (KUBEJS_WINDOW_ICON_MIXIN.equals(mixinClassName)) {
            return classExists(KUBEJS_WINDOW_ICON_TARGET);
        }
        if (AE2WTLIB_RESTOCK_GUARD_MIXIN.equals(mixinClassName)) {
            return classExists(AE2WTLIB_GUI_MIXIN_TARGET);
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
