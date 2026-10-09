@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.preview

import calebxzhou.rdi.mc.client.compat.RJeiRuntimeStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import mezz.jei.api.gui.ingredient.IRecipeSlotView
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraftforge.fluids.FluidStack
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.uuid.Uuid

/** JEI objects never leave the client thread. Only completed JSON is handed to the writer. */
internal class JeiPreviewRecipeExport(
    private val runtime: IJeiRuntime,
    private val stillCurrent: () -> Boolean
) : PreviewRecipeSource {
    override val source = "jei"
    override val valid: Boolean get() = stillCurrent()
    private var categories: List<IRecipeCategory<*>>? = null
    private var categoryIndex = 0
    private val entries = ArrayList<Entry>()
    private val categoryData = JsonObject()
    private val recipes = JsonObject()
    private val failures = JsonObject()
    private val sourceFailures = JsonObject()
    private val seenIds = HashSet<String>()
    override var processed = 0
        private set
    override val total: Int get() = entries.size
    override val preparing: Boolean get() = categories == null || categoryIndex < checkNotNull(categories).size
    override val complete: Boolean get() = !preparing && processed == total
    override val successfulCount: Int get() = recipes.size()
    override val failedCount: Int get() = failures.size() + sourceFailures.size()
    override val nextId: String get() = when {
        categories == null -> "jei:categories"
        preparing -> "jei:category/$categoryIndex"
        else -> entries[processed].key
    }

    private data class Entry(
        val key: String,
        val recipeId: String?,
        val category: IRecipeCategory<Any>,
        val recipe: Any,
        val identityFailure: Exception? = null
    )

    override fun encodeNext(): Result<Unit> {
        check(valid) { "JEI runtime changed during export" }
        check(!complete) { "all JEI recipes have already been encoded" }
        if (categories == null) {
            return runCatching {
                categories = runtime.recipeManager.createRecipeCategoryLookup().get().use { it.toList() }
            }.onFailure {
                categories = emptyList()
                sourceFailures.addProperty("jei:categories", reason(it))
            }
        }
        if (preparing) {
            val index = categoryIndex++
            return runCatching { discoverCategory(checkNotNull(categories)[index]) }
                .onFailure { sourceFailures.addProperty("jei:category/$index", reason(it)) }
        }
        val entry = entries[processed++]
        return runCatching {
            entry.identityFailure?.let { throw it }
            recipes.add(entry.key, encode(entry))
        }.onFailure { failures.addProperty(entry.key, reason(it)) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun discoverCategory(untyped: IRecipeCategory<*>) {
        val category = untyped as IRecipeCategory<Any>
        val uid = category.recipeType.uid.toString()
        // No includeHidden(): mirror the recipes currently exposed to the player by JEI.
        val found = runtime.recipeManager.createRecipeLookup(category.recipeType).get().use { it.toList() }
        val seenObjects = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        for (recipe in found) {
            if (!seenObjects.add(recipe)) continue
            var failure: Exception? = null
            val id = try {
                category.getRegistryName(recipe)?.toString() ?: (recipe as? Recipe<*>)?.id?.toString()
            } catch (exception: Exception) {
                failure = exception
                null
            }
            val key = if (id != null) "$uid|$id" else "$uid|generated:${Uuid.generateV7()}"
            if (!seenIds.add(key)) continue
            entries += Entry(key, id, category, recipe, failure)
        }
        // Failure to obtain category metadata must not discard its recipe entries.
        val metadata = JsonObject().apply {
            addProperty("title", category.title.string)
            addProperty("width", category.width)
            addProperty("height", category.height)
            add("catalysts", JsonArray().apply {
                runtime.recipeManager.createRecipeCatalystLookup(category.recipeType).get().use { stream ->
                    stream.forEach { add(encodeIngredient(it)) }
                }
            })
        }
        categoryData.add(uid, metadata)
    }

    private fun encode(entry: Entry): JsonObject {
        check(entry.category.isHandled(entry.recipe)) { "JEI category does not handle this recipe" }
        val layout = runtime.recipeManager.createRecipeLayoutDrawable(
            entry.category, entry.recipe, runtime.jeiHelpers.focusFactory.emptyFocusGroup
        ).orElseThrow { IllegalStateException("JEI could not create the recipe layout") }
        return JsonObject().apply {
            addProperty("kind", "jei")
            addProperty("category", entry.category.recipeType.uid.toString())
            entry.recipeId?.let { addProperty("recipeId", it) }
            addProperty("generatedId", entry.recipeId == null)
            add("slots", JsonArray().apply {
                layout.recipeSlotsView.slotViews.forEach { add(encodeSlot(it)) }
            })
            (entry.recipe as? Recipe<*>)?.let { recipe ->
                addProperty("type", BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.serializer).toString())
                addProperty("group", recipe.group)
                addProperty("special", recipe.isSpecial)
                if (recipe is ShapedRecipe) {
                    addProperty("width", recipe.width)
                    addProperty("height", recipe.height)
                }
                if (recipe is AbstractCookingRecipe) {
                    addProperty("cookTime", recipe.cookingTime)
                    addProperty("experience", recipe.experience)
                }
            }
        }
    }

    private fun encodeSlot(slot: IRecipeSlotView): JsonObject = JsonObject().apply {
        addProperty("role", slot.role.name.lowercase(java.util.Locale.ROOT))
        slot.slotName.ifPresent { addProperty("name", it) }
        add("ingredients", JsonArray().apply {
            slot.allIngredients.use { stream -> stream.forEach { add(encodeIngredient(it)) } }
        })
    }

    internal fun encodeIngredient(typed: ITypedIngredient<*>): JsonObject {
        val ingredient = typed.ingredient
        return JsonObject().apply {
            when (ingredient) {
                is ItemStack -> {
                    addProperty("kind", "item")
                    addProperty("id", BuiltInRegistries.ITEM.getKey(ingredient.item).toString())
                    addProperty("count", ingredient.count)
                    ingredient.tag?.let { addProperty("nbt", it.toString()) }
                }
                is FluidStack -> {
                    addProperty("kind", "fluid")
                    addProperty("id", BuiltInRegistries.FLUID.getKey(ingredient.fluid).toString())
                    addProperty("amount", ingredient.amount)
                    addProperty("unit", "mB")
                    ingredient.tag?.let { addProperty("nbt", it.toString()) }
                }
                else -> throw UnsupportedOperationException("Unsupported JEI ingredient type: ${typed.type.uid}")
            }
        }
    }

    override fun toJson(): String {
        check(complete) { "JEI recipe export is not complete" }
        return JsonObject().apply {
            addProperty("source", source)
            addProperty("includesHidden", false)
            add("categories", categoryData)
            add("recipes", recipes)
            add("failedRecipes", failures)
            add("failedSources", sourceFailures)
        }.toString()
    }

    private fun reason(failure: Throwable): String = failure.message?.takeIf { it.isNotBlank() }
        ?: failure.javaClass.simpleName

    companion object {
        fun createIfAvailable(): JeiPreviewRecipeExport? {
            val generation = RJeiRuntimeStore.generation
            val runtime = RJeiRuntimeStore.runtime ?: return null
            return JeiPreviewRecipeExport(runtime) {
                RJeiRuntimeStore.generation == generation && RJeiRuntimeStore.runtime === runtime
            }
        }
    }
}
