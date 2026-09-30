package calebxzau.rdi.mc.client.l2.mixin;

import java.util.function.Supplier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AttributeModifier.class)
public interface AAttributeModifierName {
    @Mutable
    @Accessor("nameGetter")
    void rdi$setNameGetter(Supplier<String> nameGetter);
}
