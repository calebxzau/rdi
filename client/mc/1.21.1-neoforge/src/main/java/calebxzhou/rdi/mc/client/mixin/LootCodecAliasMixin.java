package calebxzhou.rdi.mc.client.mixin;

import calebxzau.rdi.mc.registry.LootCodecAliases;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MappedRegistry.class)
public abstract class LootCodecAliasMixin<T> {
    // The int overload is the implementation, including NeoForge's explicit-ID callers.
    // This point is AFTER frozen/null/id/duplicate-key validation, BEFORE any registration writes.
    @Inject(
            method = "register(ILnet/minecraft/resources/ResourceKey;Ljava/lang/Object;Lnet/minecraft/core/RegistrationInfo;)Lnet/minecraft/core/Holder$Reference;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/core/MappedRegistry;byValue:Ljava/util/Map;", opcode = Opcodes.GETFIELD, ordinal = 0),
            cancellable = true,
            require = 1,
            allow = 1
    )
    @SuppressWarnings("unchecked")
    private void rdi$aliasDuplicateLootCodec(int id, ResourceKey<T> key, T value,
                                           RegistrationInfo info, CallbackInfoReturnable<Holder.Reference<T>> cir) {
        Registry<T> registry = (Registry<T>) (Object) this;
        // Avoid loading the Kotlin helper during unrelated early vanilla registry bootstrap.
        if (!registry.key().location().toString().equals("neoforge:global_loot_modifier_serializers")) return;
        Holder.Reference<T> holder = LootCodecAliases.tryRegisterAlias(registry, key, value);
        if (holder != null) cir.setReturnValue(holder);
    }
}
