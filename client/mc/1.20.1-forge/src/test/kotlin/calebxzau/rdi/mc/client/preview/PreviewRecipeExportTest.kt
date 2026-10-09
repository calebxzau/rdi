package calebxzau.rdi.mc.client.preview

import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.core.NonNullList
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.*
import net.minecraftforge.network.NetworkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewRecipeExportTest {
    companion object {
        init {
            SharedConstants.tryDetectVersion()
            // JUnit does not run ModLauncher's event constructor transformations.
            NetworkEvent { error("Preview tests must not dispatch network events") }.listenerList
            NetworkEvent.GatherLoginPayloadsEvent(arrayListOf(), false).listenerList
            Bootstrap.bootStrap()
        }
    }

    private fun id(name: String) = ResourceLocation("test", name)
    private fun registries() = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)

    @Test
    fun exportsCookingPreviewAndContinuesAfterOneRecipeFails() {
        val recipe = SmeltingRecipe(
            id("z_valid"), "test", CookingBookCategory.MISC,
            Ingredient.of(Items.IRON_ORE), ItemStack(Items.IRON_INGOT), 0.7f, 200
        )
        val broken = object : SmeltingRecipe(
            id("a_broken"), "broken", CookingBookCategory.MISC,
            Ingredient.of(Items.IRON_ORE), ItemStack(Items.IRON_INGOT), 0.7f, 200
        ) {
            override fun getSerializer(): RecipeSerializer<*> = error("injected recipe encoding failure")
        }
        val export = PreviewRecipeExport(listOf(recipe, broken), registries())
        assertEquals("test:a_broken", export.nextId)
        assertTrue(export.encodeNext().isFailure)
        assertFalse(export.complete)
        assertEquals(1, export.processed)
        assertTrue(export.encodeNext().isSuccess)
        assertTrue(export.complete)
        assertEquals(1, export.successfulCount)
        assertEquals(1, export.failedCount)
        val json = JsonParser.parseString(export.toJson()).asJsonObject
        val encoded = json.getAsJsonObject("recipes").getAsJsonObject("test:z_valid")
        assertEquals("minecraft:smelting", encoded.get("type").asString)
        assertEquals("cooking", encoded.get("kind").asString)
        assertEquals(200, encoded.get("cookTime").asInt)
        assertEquals("minecraft:iron_ingot", encoded.getAsJsonArray("outputs")[0].asJsonObject.get("id").asString)
        assertEquals("injected recipe encoding failure", json.getAsJsonObject("failedRecipes").get("test:a_broken").asString)
    }

    @Test
    fun exportsNetworkDecodedShapedGridIncludingEmptySlotsAlternativesAndNbt() {
        val result = ItemStack(Items.YELLOW_TERRACOTTA, 8).apply {
            setHoverName(Component.literal("测试产物"))
            orCreateTag.putLong("large", Long.MAX_VALUE)
            orCreateTag.putByteArray("bytes", byteArrayOf(1, 2, 3))
        }
        val original = ShapedRecipe(
            id("shaped"), "terracotta", CraftingBookCategory.BUILDING, 2, 2,
            NonNullList.of(Ingredient.EMPTY,
                Ingredient.of(Items.TERRACOTTA, Items.WHITE_TERRACOTTA), Ingredient.EMPTY,
                Ingredient.of(Items.YELLOW_DYE), Ingredient.of(Items.TERRACOTTA)), result
        )
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        val decoded = try {
            RecipeSerializer.SHAPED_RECIPE.toNetwork(buffer, original)
            checkNotNull(RecipeSerializer.SHAPED_RECIPE.fromNetwork(id("shaped"), buffer))
        } finally {
            buffer.release()
        }
        val export = PreviewRecipeExport(listOf(decoded), registries())
        export.encodeNext().getOrThrow()
        val encoded = JsonParser.parseString(export.toJson()).asJsonObject.getAsJsonObject("recipes").getAsJsonObject("test:shaped")
        assertEquals("shaped", encoded.get("kind").asString)
        assertEquals(2, encoded.get("width").asInt)
        assertEquals(2, encoded.get("height").asInt)
        val inputs = encoded.getAsJsonArray("inputs")
        assertEquals(4, inputs.size())
        assertEquals(2, inputs[0].asJsonArray.size())
        assertEquals(0, inputs[1].asJsonArray.size())
        assertEquals("minecraft:yellow_dye", inputs[2].asJsonArray[0].asJsonObject.get("id").asString)
        val output = encoded.getAsJsonArray("outputs")[0].asJsonObject
        assertEquals("minecraft:yellow_terracotta", output.get("id").asString)
        assertEquals(8, output.get("count").asInt)
        assertFalse(output.has("Count"))
        assertFalse(output.has("components"))
        assertEquals(result.tag, TagParser.parseTag(output.get("nbt").asString))
    }

    @Test
    fun exportsShapelessAndStonecuttingInputsWithoutGrid() {
        val shapeless = ShapelessRecipe(
            id("shapeless"), "", CraftingBookCategory.MISC, ItemStack(Items.ORANGE_DYE, 2),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.RED_DYE), Ingredient.of(Items.YELLOW_DYE))
        )
        val single = StonecutterRecipe(id("single"), "", Ingredient.of(Items.STONE), ItemStack(Items.STONE_SLAB, 2))
        val export = PreviewRecipeExport(listOf(shapeless, single), registries())
        while (!export.complete) export.encodeNext().getOrThrow()
        val recipes = JsonParser.parseString(export.toJson()).asJsonObject.getAsJsonObject("recipes")
        val encoded = recipes.getAsJsonObject("test:shapeless")
        assertEquals("shapeless", encoded.get("kind").asString)
        assertEquals(2, encoded.getAsJsonArray("inputs").size())
        assertFalse(encoded.has("width"))
        assertEquals("single_item", recipes.getAsJsonObject("test:single").get("kind").asString)
        assertEquals(1, recipes.getAsJsonObject("test:single").getAsJsonArray("inputs").size())
        assertFalse(recipes.getAsJsonObject("test:single").getAsJsonArray("outputs")[0].asJsonObject.has("nbt"))
    }

    @Test
    fun unsupportedRecipeIsExplicitlyReportedInsteadOfLosingCustomFields() {
        val recipe = SmithingTrimRecipe(id("unsupported"), Ingredient.of(Items.COAL),
            Ingredient.of(Items.IRON_CHESTPLATE), Ingredient.of(Items.IRON_INGOT))
        val export = PreviewRecipeExport(listOf(recipe), registries())
        assertTrue(export.encodeNext().isFailure)
        val json = JsonParser.parseString(export.toJson()).asJsonObject
        assertEquals(0, json.getAsJsonObject("recipes").size())
        assertTrue(json.getAsJsonObject("failedRecipes").get("test:unsupported").asString.contains("minecraft:smithing_trim"))
    }

    @Test
    fun customSerializerOnVanillaSubclassDoesNotUseVanillaDisplayAdapter() {
        val custom = object : SmeltingRecipe(id("custom"), "", CookingBookCategory.MISC,
            Ingredient.of(Items.IRON_ORE), ItemStack(Items.IRON_INGOT), 0.7f, 200) {
            override fun getSerializer(): RecipeSerializer<*> = RecipeSerializer.SMITHING_TRIM
        }
        val export = PreviewRecipeExport(listOf(custom), registries())
        assertTrue(export.encodeNext().isFailure)
        assertEquals(0, export.successfulCount)
        assertEquals(1, export.failedCount)
    }

    @Test
    fun exportsEmptyRecipeList() {
        val export = PreviewRecipeExport(emptyList(), registries())
        assertTrue(export.complete)
        assertEquals("{\"source\":\"minecraft\",\"recipes\":{},\"failedRecipes\":{}}", export.toJson())
    }
}
