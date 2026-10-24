package calebxzau.rdi.mc.registry

import com.mojang.serialization.MapCodec
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import org.slf4j.LoggerFactory

/** Compatibility for duplicate global-loot codecs, not duplicate registry entries in general. */
object LootCodecAliases {
    private val logger = LoggerFactory.getLogger(LootCodecAliases::class.java)
    private const val REGISTRY_NAME = "neoforge:global_loot_modifier_serializers"

    /**
     * Called after MappedRegistry's frozen/id/duplicate-name checks and before its duplicate-value
     * check. Null means the original registration must continue, including its original failures.
     * Aliases resolve on lookup but are deliberately absent from keySet/entrySet and have no own ID.
     */
    @JvmStatic
    fun <T : Any> tryRegisterAlias(
        registry: Registry<T>,
        requestedKey: ResourceKey<T>,
        value: T,
    ): Holder.Reference<T>? {
        if (registry.key().location().toString() != REGISTRY_NAME || value !is MapCodec<*>) return null
        if (requestedKey.registry() != registry.key().location() || registry.doesSync()) return null

        // containsKey(ResourceKey) also catches a pre-created, still-unbound holder. Replacing it
        // with an alias would strand existing holder references and make freeze() fail later.
        if (registry.containsKey(requestedKey) || registry.resolve(requestedKey) != requestedKey) return null
        val canonicalKey = registry.getResourceKey(value).orElse(null) ?: return null
        if (canonicalKey.location().namespace != requestedKey.location().namespace) return null
        val canonicalHolder = registry.getHolder(canonicalKey).orElse(null) ?: return null
        if (!canonicalHolder.isBound || canonicalHolder.value() !== value) return null

        // NeoForge validates alias conflicts/loops; propagate those errors rather than overriding.
        registry.addAlias(requestedKey.location(), canonicalKey.location())
        logger.warn(
            "RDI loot codec compatibility: aliased {} -> {} in {} (same codec instance)",
            requestedKey.location(), canonicalKey.location(), registry.key().location(),
        )
        return canonicalHolder
    }
}
