package calebxzhou.rdi.mc.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(
        targets = "com.kurome.ageofmythology.infinite_dimension.hollow.HollowFlowProfile",
        remap = false
)
public abstract class HollowFlowProfileMixin {
    @WrapWithCondition(
            method = "loadProfiles()Lcom/kurome/ageofmythology/infinite_dimension/hollow/HollowFlowProfile$LoadedProfiles;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/kurome/ageofmythology/infinite_dimension/hollow/HollowFlowProfile;verifyMediumTexture(Lcom/kurome/ageofmythology/infinite_dimension/hollow/HollowFlowProfile$MediumSettings;)V",
                    remap = false
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private static boolean rdi$shouldVerifyMediumTexture(@Coerce Object medium) {
        // Re-encoded textures (including AVIF under a .png path) no longer match
        // the bundled byte hash. Keep profile parsing and validation intact.
        return false;
    }
}
