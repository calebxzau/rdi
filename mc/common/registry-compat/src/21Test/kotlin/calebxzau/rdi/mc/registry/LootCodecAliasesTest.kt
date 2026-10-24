package calebxzau.rdi.mc.registry

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import com.mojang.serialization.Lifecycle
import com.mojang.serialization.MapCodec
import net.minecraft.SharedConstants
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.neoforged.fml.loading.LoadingModList
import net.neoforged.neoforge.registries.BaseMappedRegistry
import net.neoforged.neoforge.registries.DeferredHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LootCodecAliasesTest {
    companion object {
        init {
            if (LoadingModList.get() == null) {
                LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            }
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private val registryKey = ResourceKey.createRegistryKey<MapCodec<String>>(
        ResourceLocation.parse("neoforge:global_loot_modifier_serializers")
    )
    private fun registry() = MappedRegistry(registryKey, Lifecycle.stable())
    private fun key(name: String) = ResourceKey.create(registryKey, ResourceLocation.parse(name))
    private fun codec(): MapCodec<String> = Codec.STRING.fieldOf("item")

    @Test
    fun familiarsNamesDecodeAndEncodeUsingOneCanonicalEntry() {
        val registry = registry()
        val codec = codec()
        val original = key("alshanex_familiars:add_items")
        val alias = key("alshanex_familiars:add_items_modifier")
        val holder = registry.register(original, codec, RegistrationInfo.BUILT_IN)
        assertSame(holder, LootCodecAliases.tryRegisterAlias(registry, alias, codec))
        assertSame(codec, registry.get(alias))
        assertSame(holder, registry.getHolder(alias).orElseThrow())
        assertSame(codec, registry.byNameCodec().parse(JsonOps.INSTANCE, JsonPrimitive(alias.location().toString())).orThrow)
        assertEquals(JsonPrimitive(original.location().toString()), registry.byNameCodec().encodeStart(JsonOps.INSTANCE, codec).orThrow)
        assertEquals(original, registry.getResourceKey(codec).orElseThrow())
        assertEquals(0, registry.getId(alias))
        assertSame(codec, registry.byId(0))
        assertEquals(1, registry.size())
        assertEquals(setOf(original.location()), registry.keySet())
        assertEquals(1, registry.entrySet().size)
        assertFalse(registry.containsKey(alias)) // Native alias semantics, not a second entry.
        registry.freeze()
        assertSame(codec, registry.get(alias))
        assertFailsWith<IllegalStateException> {
            registry.register(key("alshanex_familiars:too_late"), codec(), RegistrationInfo.BUILT_IN)
        }
    }

    @Test
    fun endRemasteredNamesWorkInBothRegistrationOrdersAndDeferredHoldersBind() {
        val names = listOf("endrem:loot_injection_codec", "endrem:loot_injection")
        for (order in listOf(names, names.reversed())) {
            val registry = registry()
            val codec = codec()
            val original = key(order[0])
            val alias = key(order[1])
            val deferred = TestDeferredHolder(alias).apply { testRegistry = registry }
            val holder = registry.register(original, codec, RegistrationInfo.BUILT_IN)
            assertSame(holder, LootCodecAliases.tryRegisterAlias(registry, alias, codec))
            assertSame(codec, deferred.get())
            assertTrue(deferred.isBound)
            val dispatch = registry.byNameCodec().dispatch("type", { _: String -> codec }, { it })
            val json = JsonParser.parseString("""{"type":"${order[1]}","item":"test:loot"}""")
            assertEquals("test:loot", dispatch.parse(JsonOps.INSTANCE, json).orThrow)
            val encoded = dispatch.encodeStart(JsonOps.INSTANCE, "test:loot").orThrow.asJsonObject
            assertEquals(order[0], encoded.get("type").asString)
            assertEquals("test:loot", encoded.get("item").asString)
        }
    }

    @Test
    fun differentCodecInstancesRemainSeparateEntries() {
        val registry = registry()
        val first = codec()
        val second = codec()
        registry.register(key("test:first"), first, RegistrationInfo.BUILT_IN)
        val secondKey = key("test:second")
        assertNull(LootCodecAliases.tryRegisterAlias(registry, secondKey, second))
        registry.register(secondKey, second, RegistrationInfo.BUILT_IN)
        assertEquals(2, registry.size())
    }

    @Test
    fun duplicateNameAndCrossNamespaceStillFail() {
        val registry = registry()
        val codec = codec()
        val original = key("test:first")
        registry.register(original, codec, RegistrationInfo.BUILT_IN)
        for (invalidKey in listOf(original, key("other:alias"))) {
            assertNull(LootCodecAliases.tryRegisterAlias(registry, invalidKey, codec))
            assertFailsWith<IllegalStateException> { registry.register(invalidKey, codec, RegistrationInfo.BUILT_IN) }
        }
        assertEquals(1, registry.size())
    }

    @Test
    fun existingAliasIsNotOverriddenOrRegisteredAgain() {
        val registry = registry()
        val codec = codec()
        val original = key("test:first")
        val alias = key("test:alias")
        registry.register(original, codec, RegistrationInfo.BUILT_IN)
        registry.addAlias(alias.location(), ResourceLocation.parse("test:other"))
        assertNull(LootCodecAliases.tryRegisterAlias(registry, alias, codec))
        assertEquals(ResourceLocation.parse("test:other"), registry.resolve(alias.location()))
        val acceptedAlias = key("test:accepted")
        assertSame(codec, LootCodecAliases.tryRegisterAlias(registry, acceptedAlias, codec)!!.value())
        assertNull(LootCodecAliases.tryRegisterAlias(registry, acceptedAlias, codec))
        assertFailsWith<IllegalStateException> { registry.register(acceptedAlias, codec, RegistrationInfo.BUILT_IN) }
    }

    @Test
    fun precreatedHolderIsNeverStrandedByAnAlias() {
        val registry = registry()
        val codec = codec()
        registry.register(key("test:first"), codec, RegistrationInfo.BUILT_IN)
        val alias = key("test:pending")
        val pending = registry.createRegistrationLookup().getOrThrow(alias)
        assertFalse(pending.isBound)
        assertNull(LootCodecAliases.tryRegisterAlias(registry, alias, codec))
        assertEquals(alias, registry.resolve(alias))
        assertFailsWith<IllegalStateException> { registry.register(alias, codec, RegistrationInfo.BUILT_IN) }
    }

    @Test
    fun otherRegistriesNonCodecsAndSyncedRegistriesAreNotHandled() {
        val otherKey = ResourceKey.createRegistryKey<MapCodec<String>>(ResourceLocation.parse("test:other_registry"))
        val other = MappedRegistry(otherKey, Lifecycle.stable())
        val codec = codec()
        other.register(ResourceKey.create(otherKey, ResourceLocation.parse("test:first")), codec, RegistrationInfo.BUILT_IN)
        assertNull(LootCodecAliases.tryRegisterAlias(other, ResourceKey.create(otherKey, ResourceLocation.parse("test:alias")), codec))

        val plainKey = ResourceKey.createRegistryKey<Any>(registryKey.location())
        val plain = MappedRegistry(plainKey, Lifecycle.stable())
        val value = Any()
        plain.register(ResourceKey.create(plainKey, ResourceLocation.parse("test:first")), value, RegistrationInfo.BUILT_IN)
        assertNull(LootCodecAliases.tryRegisterAlias(plain, ResourceKey.create(plainKey, ResourceLocation.parse("test:alias")), value))

        val synced = registry()
        synced.register(key("test:first"), codec, RegistrationInfo.BUILT_IN)
        BaseMappedRegistry::class.java.getDeclaredMethod("setSync", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(synced, true)
        }
        assertNull(LootCodecAliases.tryRegisterAlias(synced, key("test:alias"), codec))
    }

    private class TestDeferredHolder(key: ResourceKey<MapCodec<String>>) :
        DeferredHolder<MapCodec<String>, MapCodec<String>>(key) {
        // The super constructor attempts to bind before this test registry is assigned.
        var testRegistry: Registry<MapCodec<String>>? = null
        override fun getRegistry(): Registry<MapCodec<String>>? = testRegistry
    }
}
