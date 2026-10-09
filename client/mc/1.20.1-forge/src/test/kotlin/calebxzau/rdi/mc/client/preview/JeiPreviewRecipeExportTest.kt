package calebxzau.rdi.mc.client.preview

import com.google.gson.JsonParser
import mezz.jei.api.gui.IRecipeLayoutDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotView
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IJeiHelpers
import mezz.jei.api.ingredients.IIngredientType
import mezz.jei.api.ingredients.ITypedIngredient
import mezz.jei.api.recipe.*
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.SharedConstants
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import net.minecraftforge.fluids.FluidStack
import net.minecraftforge.network.NetworkEvent
import java.lang.reflect.Proxy
import java.util.Optional
import kotlin.test.*

class JeiPreviewRecipeExportTest {
    companion object {
        init {
            SharedConstants.tryDetectVersion()
            NetworkEvent { error("No networking in preview tests") }.listenerList
            NetworkEvent.GatherLoginPayloadsEvent(arrayListOf(), false).listenerList
            Bootstrap.bootStrap()
        }
    }

    private data class DisplayRecipe(val id: String?, val slots: List<IRecipeSlotView>, val broken: Boolean = false)
    private data class Category(val id: String, val recipes: List<DisplayRecipe>, val catalysts: List<ITypedIngredient<*>> = emptyList())

    @Test
    fun readsJeiOnlyRecipesWithAllAlternativesNbtFluidsAndCatalysts() {
        val tagged = ItemStack(Items.POTION, 2).apply { orCreateTag.putLong("large", Long.MAX_VALUE) }
        val fluid = FluidStack(Fluids.WATER, 1500).apply { tag = TagParser.parseTag("{variant:3b}") }
        val recipe = DisplayRecipe("test:mix", listOf(
            slot(RecipeIngredientRole.INPUT, listOf(typed(tagged), typed(ItemStack(Items.APPLE)))),
            slot(RecipeIngredientRole.INPUT, emptyList()),
            slot(RecipeIngredientRole.OUTPUT, listOf(typed(fluid))),
            slot(RecipeIngredientRole.CATALYST, listOf(typed(ItemStack(Items.BUCKET)))),
            slot(RecipeIngredientRole.RENDER_ONLY, listOf(typed(ItemStack(Items.STONE))))
        ))
        val export = export(listOf(Category("test:machine", listOf(recipe), listOf(typed(ItemStack(Items.FURNACE))))))
        val json = finish(export)
        assertEquals("jei", json["source"].asString)
        assertFalse(json["includesHidden"].asBoolean)
        val encoded = json.getAsJsonObject("recipes").getAsJsonObject("test:machine|test:mix")
        assertEquals("jei", encoded["kind"].asString)
        val slots = encoded.getAsJsonArray("slots")
        assertEquals(5, slots.size())
        val alternatives = slots[0].asJsonObject.getAsJsonArray("ingredients")
        assertEquals(2, alternatives.size())
        assertEquals(2, alternatives[0].asJsonObject["count"].asInt)
        assertEquals(tagged.tag, TagParser.parseTag(alternatives[0].asJsonObject["nbt"].asString))
        assertEquals(0, slots[1].asJsonObject.getAsJsonArray("ingredients").size())
        val water = slots[2].asJsonObject.getAsJsonArray("ingredients")[0].asJsonObject
        assertEquals("fluid", water["kind"].asString)
        assertEquals("minecraft:water", water["id"].asString)
        assertEquals(1500, water["amount"].asInt)
        assertEquals(fluid.tag, TagParser.parseTag(water["nbt"].asString))
        assertEquals("catalyst", slots[3].asJsonObject["role"].asString)
        assertEquals("render_only", slots[4].asJsonObject["role"].asString)
        val category = json.getAsJsonObject("categories").getAsJsonObject("test:machine")
        assertEquals("minecraft:furnace", category.getAsJsonArray("catalysts")[0].asJsonObject["id"].asString)
        assertEquals(1, export.successfulCount)
    }

    @Test
    fun deduplicatesWithinCategoryButPreservesSameRecipeInOtherCategories() {
        val recipe = DisplayRecipe("test:one", emptyList())
        val export = export(listOf(Category("test:a", listOf(recipe, recipe.copy())), Category("test:b", listOf(recipe))))
        val json = finish(export)
        assertEquals(2, json.getAsJsonObject("recipes").size())
        assertEquals(2, export.total)
        assertEquals(2, export.processed)
    }

    @Test
    fun recipesWithoutRegistryIdsReceiveDistinctUuidV7ExportIds() {
        val first = DisplayRecipe(null, emptyList())
        val second = DisplayRecipe(null, emptyList())
        val json = finish(export(listOf(Category("test:a", listOf(first, first, second)))))
        val recipes = json.getAsJsonObject("recipes")
        assertEquals(2, recipes.size())
        recipes.entrySet().forEach { (id, value) ->
            assertEquals(7, java.util.UUID.fromString(id.substringAfter("|generated:")).version())
            assertTrue(value.asJsonObject["generatedId"].asBoolean)
            assertFalse(value.asJsonObject.has("recipeId"))
        }
    }

    @Test
    fun layoutAndUnsupportedIngredientFailuresDoNotDiscardOtherRecipes() {
        val recipes = listOf(
            DisplayRecipe("test:broken", emptyList(), broken = true),
            DisplayRecipe("test:custom", listOf(slot(RecipeIngredientRole.INPUT, listOf(typed("gas"))))),
            DisplayRecipe("test:ok", emptyList())
        )
        val export = export(listOf(Category("test:a", recipes)))
        val json = finish(export)
        assertEquals(1, export.successfulCount)
        assertEquals(2, export.failedCount)
        assertTrue(json.getAsJsonObject("failedRecipes")["test:a|test:custom"].asString.contains("Unsupported JEI ingredient"))
        assertTrue(json.getAsJsonObject("failedRecipes")["test:a|test:broken"].asString.contains("layout"))
    }

    @Test
    fun discoveryIsStagedAndRuntimeReplacementStopsFurtherCalls() {
        var current = true
        val export = export(listOf(Category("test:a", listOf(DisplayRecipe("test:a", emptyList()))))) { current }
        assertTrue(export.preparing)
        export.encodeNext().getOrThrow() // category list only
        assertEquals(0, export.total)
        export.encodeNext().getOrThrow() // one category only
        assertFalse(export.preparing)
        assertEquals(0, export.processed)
        assertEquals(1, export.total)
        current = false
        assertFalse(export.valid)
        assertFailsWith<IllegalStateException> { export.encodeNext() }
        assertFailsWith<IllegalStateException> { export.toJson() }
    }

    @Test
    fun categoryMetadataFailureIsReportedWhileItsRecipesAreRetained() {
        val export = export(listOf(Category("test:a", listOf(DisplayRecipe("test:ok", emptyList())), listOf(typed("unknown")))))
        val json = finish(export)
        assertEquals(1, export.successfulCount)
        assertEquals(1, export.failedCount)
        assertEquals(1, json.getAsJsonObject("failedSources").size())
    }

    @Test
    fun categoryLookupFailureIsObservable() {
        val runtime = proxy<IJeiRuntime> { _, _ -> error("injected discovery failure") }
        val export = JeiPreviewRecipeExport(runtime) { true }
        assertTrue(export.encodeNext().isFailure)
        assertTrue(export.complete)
        assertEquals(1, JsonParser.parseString(export.toJson()).asJsonObject.getAsJsonObject("failedSources").size())
    }

    private fun finish(export: JeiPreviewRecipeExport): com.google.gson.JsonObject {
        var steps = 0
        while (!export.complete) {
            export.encodeNext() // Expected failures are asserted in the serialized failure records.
            check(++steps < 100)
        }
        return JsonParser.parseString(export.toJson()).asJsonObject
    }

    private fun slot(role: RecipeIngredientRole, ingredients: List<ITypedIngredient<*>>): IRecipeSlotView =
        proxy { method, _ -> when (method) {
            "getRole" -> role
            "getSlotName" -> Optional.of("slot")
            "getAllIngredients" -> ingredients.stream()
            else -> error("Unexpected slot call: $method")
        } }

    private fun <T : Any> typed(value: T): ITypedIngredient<T> {
        val type = IIngredientType<T> { value.javaClass }
        return proxy { method, _ -> when (method) {
            "getType" -> type
            "getIngredient" -> value
            else -> error("Unexpected typed ingredient call: $method")
        } }
    }

    private fun export(data: List<Category>, valid: () -> Boolean = { true }): JeiPreviewRecipeExport {
        val types = data.associate { it.id to RecipeType(ResourceLocation(it.id), DisplayRecipe::class.java) }
        val categories = data.map { category -> proxy<IRecipeCategory<DisplayRecipe>> { method, args -> when (method) {
            "getRecipeType" -> types.getValue(category.id)
            "getRegistryName" -> (args[0] as DisplayRecipe).id?.let(::ResourceLocation)
            "getTitle" -> Component.literal(category.id)
            "getWidth", "getHeight" -> 100
            "isHandled" -> true
            else -> error("Unexpected category call: $method")
        } } }
        val manager = proxy<IRecipeManager> { method, args -> when (method) {
            "createRecipeCategoryLookup" -> proxy<IRecipeCategoriesLookup> { call, _ ->
                check(call == "get"); categories.stream()
            }
            "createRecipeLookup" -> proxy<IRecipeLookup<DisplayRecipe>> { call, _ ->
                check(call == "get")
                data.first { it.id == (args[0] as RecipeType<*>).uid.toString() }.recipes.stream()
            }
            "createRecipeCatalystLookup" -> proxy<IRecipeCatalystLookup> { call, _ ->
                check(call == "get")
                data.first { it.id == (args[0] as RecipeType<*>).uid.toString() }.catalysts.stream()
            }
            "createRecipeLayoutDrawable" -> {
                val recipe = args[1] as DisplayRecipe
                if (recipe.broken) Optional.empty<IRecipeLayoutDrawable<DisplayRecipe>>()
                else Optional.of(proxy<IRecipeLayoutDrawable<DisplayRecipe>> { call, _ ->
                    check(call == "getRecipeSlotsView")
                    IRecipeSlotsView { recipe.slots }
                })
            }
            else -> error("Unexpected manager call: $method")
        } }
        val helpers = proxy<IJeiHelpers> { method, _ ->
            check(method == "getFocusFactory")
            proxy<IFocusFactory> { call, _ ->
                check(call == "getEmptyFocusGroup")
                proxy<IFocusGroup> { _, _ -> error("Focus must not be queried by exporter") }
            }
        }
        val runtime = proxy<IJeiRuntime> { method, _ -> when (method) {
            "getRecipeManager" -> manager
            "getJeiHelpers" -> helpers
            else -> error("Unexpected runtime call: $method")
        } }
        return JeiPreviewRecipeExport(runtime, valid)
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
            when (method.name) {
                "toString" -> "Test${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.get(0)
                else -> call(method.name, args ?: emptyArray())
            }
        } as T
}
