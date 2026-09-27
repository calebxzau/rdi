package calebxzhou.rdi.mc.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static calebxzau.rdi.mc.v20.client.RoomJoinUi20.JOIN_BUTTON;
import static calebxzau.rdi.mc.v20.client.RoomJoinUi20.layoutJoinButton;

/**
 * calebxzhou @ 2025-04-15 10:32
 */
@Mixin(TitleScreen.class)
public class mTitleScreen extends Screen {
    protected mTitleScreen(Component title) {
        super(title);
    }

    @Redirect(method = "render",at= @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PanoramaRenderer;render(FF)V"))
    public void RDI$NOrenderPanorama(PanoramaRenderer instance, float deltaT, float alpha) {

    }
    @Inject(method = "render",at=@At("HEAD"))
    private void RDI$RenderBG(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci){
        guiGraphics.blit(ResourceLocation.tryBuild("rdi", "textures/bg.avif"), 0, 0, 0, 0, this.width, this.height, this.width, this.height);
    }

    @Inject(method = "createNormalMenuOptions", at = @At("HEAD"))
    private void RDI$AddMultiplayerButton(int y, int rowHeight, CallbackInfo ci) {
         layoutJoinButton(this.width);
         addRenderableWidget(JOIN_BUTTON);
    }

}
