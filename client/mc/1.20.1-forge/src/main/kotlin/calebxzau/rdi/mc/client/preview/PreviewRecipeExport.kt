package calebxzau.rdi.mc.client.preview

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SingleItemRecipe
import net.minecraft.world.item.crafting.Recipe

/** Captures the client's recipe list and encodes one recipe at a time on the client thread. */
internal class PreviewRecipeExport(recipes: Collection<Recipe<*>>, private val registries: RegistryAccess) : PreviewRecipeSource {
    override val source: String = "minecraft"
    private val holders = recipes.sortedBy { it.id.toString() }
    private val recipes = JsonObject()
    private val failedRecipes = JsonObject()
    override var processed: Int = 0
        private set
    override val total: Int get() = holders.size
    override val complete: Boolean get() = processed == total
    override val successfulCount: Int get() = recipes.size()
    override val failedCount: Int get() = failedRecipes.size()
    override val nextId: String get() = holders[processed].id.toString()

    override fun encodeNext(): Result<Unit> {
        check(!complete) { "all recipes have already been encoded" }
        val holder = holders[processed]
        val id = holder.id.toString()
        return runCatching {
            recipes.add(id, encodePreview(holder))
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
            else -> throw UnsupportedOperationException(
                "Unsupported recipe serializer on Minecraft1.20.1: ${BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer)}"
            )
        }
        return JsonObject().apply {
            addProperty("type", BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer).toString())
            addProperty("kind", kind)
            addProperty("group", recipe.group)
            addProperty("special", recipe.isSpecial)
            add("inputs", JsonArray().apply {
                recipe.ingredients.forEach { ingredient -> add(encodeIngredient(ingredient)) }
            })
            add("outputs", JsonArray().apply {
                val output = recipe.getResultItem(registries)
                if (!output.isEmpty) add(encodeStack(output))
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
            if (!stack.isEmpty) add(encodeStack(stack))
        }
    }

    /** Stable display fields; legacy NBT remains typed SNBT instead of pretending to be components. */
    private fun encodeStack(stack: ItemStack): JsonObject = JsonObject().apply {
        addProperty("id", BuiltInRegistries.ITEM.getKey(stack.item).toString())
        addProperty("count", stack.count)
        stack.tag?.let { addProperty("nbt", it.toString()) }
    }

    /** Called by the I/O worker only after encoding has completed. */
    override fun toJson(): String {
        check(complete) { "recipe export is not complete" }
        return JsonObject().apply {
            addProperty("source", source)
            add("recipes", recipes)
            add("failedRecipes", failedRecipes)
        }.toString()
    }
}
