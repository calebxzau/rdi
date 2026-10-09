package calebxzau.rdi.mc.client.preview

import net.minecraft.core.RegistryAccess
import net.minecraft.world.item.crafting.Recipe
import net.minecraftforge.fml.ModList
import org.slf4j.LoggerFactory

/** All discovery/encoding runs on the client thread; toJson only reads the completed snapshot. */
internal interface PreviewRecipeSource {
    val source: String
    val preparing: Boolean get() = false
    val valid: Boolean get() = true
    val processed: Int
    val total: Int
    val complete: Boolean
    val successfulCount: Int
    val failedCount: Int
    val nextId: String
    fun encodeNext(): Result<Unit>
    fun toJson(): String
}

internal object PreviewRecipeSources {
    fun create(recipes: Collection<Recipe<*>>, registries: RegistryAccess): PreviewRecipeSource {
        // Keep JEI linkage behind the loader check so the vanilla exporter works without JEI.
        if (ModList.get().isLoaded("jei")) {
            JeiPreviewRecipeExport.createIfAvailable()?.let { return it }
        }
        LoggerFactory.getLogger(PreviewRecipeSources::class.java)
            .info("JEI is absent or not ready; exporting client RecipeManager recipes")
        return PreviewRecipeExport(recipes, registries)
    }
}
