package calebxzhou.rdi.mc.server.mixin

import calebxzau.rdi.mc.server.compat.XiaoxiangButtonIds
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.LoadingModList
import org.objectweb.asm.tree.ClassNode
import org.slf4j.LoggerFactory
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo
import org.spongepowered.asm.service.MixinService

class RMixinPlugin : IMixinConfigPlugin {
    private val logger = LoggerFactory.getLogger(RMixinPlugin::class.java)

    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean {
        if (mixinClassName == "calebxzhou.rdi.mc.server.mixin.mNightfallVfxServer") {
            return FMLEnvironment.dist == Dist.DEDICATED_SERVER && modLoaded("efn")
        }
        if (mixinClassName in XIAOXIANG_BUTTON_MIXINS) {
            return shouldFixXiaoxiangButtons(targetClassName)
        }
        return true
    }

    private fun shouldFixXiaoxiangButtons(targetClassName: String): Boolean {
        if (!modLoaded(XiaoxiangButtonIds.MOD_ID)) {
            return false
        }
        val classNode = runCatching {
            MixinService.getService().bytecodeProvider.getClassNode(targetClassName)
        }.getOrElse { exception ->
            logger.error("Skipped Xiaoxiang button id fix: cannot read {}", targetClassName, exception)
            return false
        }
        val apply = XiaoxiangButtonIds.usesWideIds(classNode, XiaoxiangButtonIds.wideIdsFor(targetClassName))
        if (apply) {
            logger.info("Xiaoxiang button id fix enabled for {}", targetClassName)
        } else {
            logger.info("Xiaoxiang button id fix skipped for {}: menu no longer uses the wide button ids", targetClassName)
        }
        return apply
    }

    private fun modLoaded(modId: String): Boolean =
        LoadingModList.get()?.mods?.any { it.modId == modId } == true

    override fun onLoad(mixinPackage: String) = Unit
    override fun getRefMapperConfig(): String? = null
    override fun acceptTargets(myTargets: MutableSet<String>, otherTargets: MutableSet<String>) = Unit
    override fun getMixins(): List<String>? = null
    override fun preApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit
    override fun postApply(targetClassName: String, targetClass: ClassNode, mixinClassName: String, mixinInfo: IMixinInfo) = Unit

    private companion object {
        val XIAOXIANG_BUTTON_MIXINS = setOf(
            "calebxzhou.rdi.mc.server.mixin.mXiaoxiangStorageButtons",
            "calebxzhou.rdi.mc.server.mixin.mXiaoxiangFurnaceButtons",
        )
    }
}
