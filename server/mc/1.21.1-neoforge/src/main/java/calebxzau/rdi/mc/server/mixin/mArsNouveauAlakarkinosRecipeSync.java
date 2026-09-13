package calebxzau.rdi.mc.server.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(targets = "com.hollingsworth.arsnouveau.common.crafting.recipes.AlakarkinosRecipe$Serializer", remap = false)
public abstract class mArsNouveauAlakarkinosRecipeSync {
    private static final Logger RDI_LOGGER = LogManager.getLogger("rdi");

    @WrapOperation(
            method = "toNetwork",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V")
    )
    private static void RDI$sanitizeAlakarkinosRecipe(StreamCodec<Object, Object> codec, Object buffer, Object recipe, Operation<Void> original) {
        original.call(codec, buffer, sanitizeAlakarkinosRecipe(recipe));
    }

    private static Object sanitizeAlakarkinosRecipe(Object recipe) {
        try {
            var dropsOptional = (Optional<?>) invoke(recipe, "drops");
            if (dropsOptional.isEmpty()) {
                return recipe;
            }
            var drops = dropsOptional.get();
            var dropsList = (List<?>) invoke(drops, "list");
            var safeDrops = new ArrayList<>(dropsList);
            safeDrops.removeIf(drop -> isInvalidDrop(recipe, drop));
            if (safeDrops.size() == dropsList.size()) {
                return recipe;
            }
            var sanitizedDrops = safeDrops.isEmpty()
                    ? Optional.empty()
                    : Optional.of(newLootDrops(drops.getClass(), safeDrops, (Integer) invoke(drops, "weight")));
            return newRecipe(recipe, sanitizedDrops);
        } catch (ReflectiveOperationException | RuntimeException e) {
            RDI_LOGGER.warn("Failed to sanitize Ars Nouveau Alakarkinos recipe before recipe packet sync", e);
            return recipe;
        }
    }

    private static boolean isInvalidDrop(Object recipe, Object drop) {
        try {
            var stack = (ItemStack) invoke(drop, "item");
            if (!stack.isEmpty() && stack.getCount() > 0) {
                return false;
            }
            RDI_LOGGER.warn(
                    "Removed invalid Ars Nouveau Alakarkinos loot drop before recipe packet sync: table={}, item={}, count={}, chance={}",
                    invoke(recipe, "table"),
                    itemId(stack),
                    stack.getCount(),
                    invoke(drop, "chance")
            );
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RDI_LOGGER.warn("Failed to inspect Ars Nouveau Alakarkinos loot drop before recipe packet sync", e);
            return false;
        }
    }

    private static Object newRecipe(Object recipe, Optional<?> sanitizedDrops) throws ReflectiveOperationException {
        for (Constructor<?> constructor : recipe.getClass().getConstructors()) {
            if (constructor.getParameterCount() == 4) {
                return constructor.newInstance(
                        invoke(recipe, "input"),
                        invoke(recipe, "table"),
                        invoke(recipe, "weight"),
                        sanitizedDrops
                );
            }
        }
        throw new NoSuchMethodException("AlakarkinosRecipe 4-argument constructor");
    }

    private static Object newLootDrops(Class<?> dropsClass, List<?> safeDrops, int weight) throws ReflectiveOperationException {
        for (Constructor<?> constructor : dropsClass.getConstructors()) {
            if (constructor.getParameterCount() == 2) {
                return constructor.newInstance(List.copyOf(safeDrops), weight);
            }
        }
        throw new NoSuchMethodException("Alakarkinos LootDrops 2-argument constructor");
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
