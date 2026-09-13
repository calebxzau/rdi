package calebxzau.rdi.mc.server.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

public final class RMixinPlugin implements IMixinConfigPlugin {
    private static final String ARS_ALAKARKINOS_SYNC_MIXIN = "calebxzhou.rdi.mc.server.mixin.mArsNouveauAlakarkinosRecipeSync";
    private static final String ARS_ALAKARKINOS_SERIALIZER = "com.hollingsworth.arsnouveau.common.crafting.recipes.AlakarkinosRecipe$Serializer";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (ARS_ALAKARKINOS_SYNC_MIXIN.equals(mixinClassName)) {
            return classExists(ARS_ALAKARKINOS_SERIALIZER);
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
