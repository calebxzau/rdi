package calebxzhou.rdi.mc.server.mixin

import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.LoadingModList
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo

class RMixinPlugin : IMixinConfigPlugin {
    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean {
        if (mixinClassName == "calebxzhou.rdi.mc.server.mixin.mNightfallVfxServer") {
            return FMLEnvironment.dist == Dist.DEDICATED_SERVER &&
                LoadingModList.get()?.mods?.any { it.modId == "efn" } == true
        }
        return true
    }

    override fun onLoad(mixinPackage: String) = Unit
    override fun getRefMapperConfig(): String? = null
    override fun acceptTargets(myTargets: MutableSet<String>, otherTargets: MutableSet<String>) = Unit
    override fun getMixins(): List<String>? = null
    override fun preApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit
    override fun postApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit
}
