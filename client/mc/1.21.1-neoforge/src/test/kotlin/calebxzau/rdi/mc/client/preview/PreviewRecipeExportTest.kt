package calebxzau.rdi.mc.client.preview

import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import com.mojang.serialization.JsonOps
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapedRecipePattern
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe
import net.minecraft.world.item.crafting.SmithingTrimRecipe
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.CookingBookCategory
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.SmeltingRecipe
import net.neoforged.neoforge.network.connection.ConnectionType
import net.neoforged.neoforge.registries.BaseMappedRegistry
import net.neoforged.fml.loading.LoadingModList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewRecipeExportTest {
    companion object {
        init {
            if (LoadingModList.get() == null) LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun exportsCookingPreviewAndContinuesAfterOneRecipeFails(): Unit {
        val recipe = SmeltingRecipe(
            "test", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), ItemStack(Items.IRON_INGOT), 0.7f, 200
        )
        val broken = object : SmeltingRecipe(
            "broken", CookingBookCategory.MISC, Ingredient.of(Items.IRON_ORE), ItemStack(Items.IRON_INGOT), 0.7f, 200
        ) {
            override fun getSerializer(): RecipeSerializer<*> = error("injected recipe encoding failure")
        }
        val export = PreviewRecipeExport(
            listOf(
                RecipeHolder(ResourceLocation.parse("test:z_valid"), recipe),
                RecipeHolder(ResourceLocation.parse("test:a_broken"), broken)
            ),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        )
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
    fun exportsNetworkDecodedShapedGridIncludingEmptySlotsAlternativesAndComponents(): Unit {
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val result = ItemStack(Items.YELLOW_TERRACOTTA, 8).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal("测试产物"))
        }
        val original = ShapedRecipe(
            "terracotta", CraftingBookCategory.BUILDING,
            ShapedRecipePattern.of(
                mapOf('A' to Ingredient.of(Items.TERRACOTTA, Items.WHITE_TERRACOTTA), 'B' to Ingredient.of(Items.YELLOW_DYE)),
                "A ", "BA"
            ), result
        )
        // Plain unit-test bootstrap does not run NeoForge's ModifyRegistriesEvent.
        val syncedRegistries = listOf(BuiltInRegistries.ITEM, BuiltInRegistries.DATA_COMPONENT_TYPE)
        val previousSync = syncedRegistries.map { it.doesSync() }
        val setSync = BaseMappedRegistry::class.java.getDeclaredMethod("setSync", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), registries, ConnectionType.NEOFORGE)
        val decoded = try {
            syncedRegistries.forEach { setSync.invoke(it, true) }
            ShapedRecipe.Serializer.STREAM_CODEC.encode(buffer, original)
            ShapedRecipe.Serializer.STREAM_CODEC.decode(buffer)
        } finally {
            buffer.release()
            syncedRegistries.forEachIndexed { index, registry -> setSync.invoke(registry, previousSync[index]) }
        }
        // Reproduce the real client failure before verifying the display exporter.
        val oldEncoding = Recipe.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), decoded)
        assertTrue(oldEncoding.error().orElseThrow().message().contains("Cannot encode unpacked recipe"))
        val export = PreviewRecipeExport(listOf(RecipeHolder(ResourceLocation.parse("test:shaped"), decoded)), registries)
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
        assertTrue(output.getAsJsonObject("components").has("minecraft:custom_name"))
    }

    @Test
    fun exportsShapelessAndStonecuttingInputsWithoutGrid(): Unit {
        val shapeless = ShapelessRecipe(
            "", CraftingBookCategory.MISC, ItemStack(Items.ORANGE_DYE, 2),
            NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.RED_DYE), Ingredient.of(Items.YELLOW_DYE))
        )
        val single = StonecutterRecipe("", Ingredient.of(Items.STONE), ItemStack(Items.STONE_SLAB, 2))
        val export = PreviewRecipeExport(
            listOf(RecipeHolder(ResourceLocation.parse("test:shapeless"), shapeless), RecipeHolder(ResourceLocation.parse("test:single"), single)),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        )
        while (!export.complete) export.encodeNext().getOrThrow()
        val recipes = JsonParser.parseString(export.toJson()).asJsonObject.getAsJsonObject("recipes")
        val encoded = recipes.getAsJsonObject("test:shapeless")
        assertEquals("shapeless", encoded.get("kind").asString)
        assertEquals(2, encoded.getAsJsonArray("inputs").size())
        assertFalse(encoded.has("width"))
        assertEquals("single_item", recipes.getAsJsonObject("test:single").get("kind").asString)
        assertEquals(1, recipes.getAsJsonObject("test:single").getAsJsonArray("inputs").size())
    }

    @Test
    fun keepsOtherRecipeCodecData(): Unit {
        val recipe = SmithingTrimRecipe(Ingredient.of(Items.COAL), Ingredient.of(Items.IRON_CHESTPLATE), Ingredient.of(Items.IRON_INGOT))
        val export = PreviewRecipeExport(
            listOf(RecipeHolder(ResourceLocation.parse("test:codec"), recipe)),
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        )
        export.encodeNext().getOrThrow()
        val encoded = JsonParser.parseString(export.toJson()).asJsonObject.getAsJsonObject("recipes").getAsJsonObject("test:codec")
        assertEquals("codec", encoded.get("kind").asString)
        assertEquals("minecraft:smithing_trim", encoded.getAsJsonObject("data").get("type").asString)
        assertTrue(encoded.getAsJsonObject("data").has("template"))
    }

    @Test
    fun exportsEmptyRecipeList(): Unit {
        val export = PreviewRecipeExport(emptyList(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
        assertTrue(export.complete)
        assertEquals("{\"recipes\":{},\"failedRecipes\":{}}", export.toJson())
    }
}
