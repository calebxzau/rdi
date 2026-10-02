package calebxzau.rdi.mc.client.preview

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SingleItemRecipe
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder

/** Captures the client's recipe list and encodes one recipe at a time on the client thread. */
internal class PreviewRecipeExport(holders: Collection<RecipeHolder<*>>, private val registries: HolderLookup.Provider) {
    private val holders = holders.sortedBy { it.id().toString() }
    private val ops = registries.createSerializationContext(JsonOps.INSTANCE)
    private val recipes = JsonObject()
    private val failedRecipes = JsonObject()
    var processed: Int = 0
        private set
    val total: Int get() = holders.size
    val complete: Boolean get() = processed == total
    val successfulCount: Int get() = recipes.size()
    val failedCount: Int get() = failedRecipes.size()
    val nextId: String get() = holders[processed].id().toString()

    fun encodeNext(): Result<Unit> {
        check(!complete) { "all recipes have already been encoded" }
        val holder = holders[processed]
        val id = holder.id().toString()
        return runCatching {
            recipes.add(id, encodePreview(holder.value()))
        }.onFailure { failure ->
            failedRecipes.addProperty(id, failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
        }.also { processed++ }
    }

    private fun encodePreview(recipe: Recipe<*>): JsonObject {
        val serializer = recipe.serializer
        val kind = when {
            recipe is ShapedRecipe && serializer === RecipeSerializer.SHAPED_RECIPE -> "shaped"
            recipe is ShapelessRecipe && serializer === RecipeSerializer.SHAPELESS_RECIPE -> "shapeless"
            recipe is AbstractCookingRecipe && serializer in listOf(
                RecipeSerializer.SMELTING_RECIPE, RecipeSerializer.BLASTING_RECIPE,
                RecipeSerializer.SMOKING_RECIPE, RecipeSerializer.CAMPFIRE_COOKING_RECIPE
            ) -> "cooking"
            recipe is SingleItemRecipe && serializer === RecipeSerializer.STONECUTTER -> "single_item"
            else -> "codec"
        }
        return JsonObject().apply {
            addProperty("type", BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer).toString())
            addProperty("kind", kind)
            if (kind == "codec") {
                // Preserve custom Mod fields rather than treating every subclass as a vanilla recipe.
                add("data", Recipe.CODEC.encodeStart(ops, recipe).getOrThrow())
                return@apply
            }
            addProperty("group", recipe.group)
            addProperty("special", recipe.isSpecial)
            add("inputs", JsonArray().apply {
                recipe.ingredients.forEach { ingredient -> add(encodeIngredient(ingredient)) }
            })
            add("outputs", JsonArray().apply {
                val output = recipe.getResultItem(registries)
                if (!output.isEmpty) add(ItemStack.CODEC.encodeStart(ops, output).getOrThrow())
            })
            when (recipe) {
                is ShapedRecipe -> {
                    addProperty("width", recipe.width)
                    addProperty("height", recipe.height)
                }
                is AbstractCookingRecipe -> {
                    addProperty("cookTime", recipe.cookingTime)
                    addProperty("experience", recipe.experience)
                }
            }
        }
    }

    /** Each slot is a list of display alternatives; an empty list preserves an empty grid cell. */
    private fun encodeIngredient(ingredient: Ingredient): JsonElement = JsonArray().apply {
        ingredient.items.forEach { stack ->
            if (!stack.isEmpty) add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow())
        }
    }

    /** Called by the I/O worker only after encoding has completed. */
    fun toJson(): String {
        check(complete) { "recipe export is not complete" }
        return JsonObject().apply {
            add("recipes", recipes)
            add("failedRecipes", failedRecipes)
        }.toString()
    }
}
