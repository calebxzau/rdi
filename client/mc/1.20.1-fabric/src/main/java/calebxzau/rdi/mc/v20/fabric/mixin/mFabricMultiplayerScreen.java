package calebxzau.rdi.mc.v20.fabric.mixin;

import calebxzau.rdi.mc.v20.client.RoomJoinUi20;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class mFabricMultiplayerScreen extends Screen {
    @Unique
    private static final int RDI$ROOM_BUTTON_TOP = 32;
    @Unique
    private static final int RDI$ROOM_BUTTON_HEIGHT = 20;
    @Unique
    private static final int RDI$SERVER_LIST_TOP = 58;

    @Shadow
    protected ServerSelectionList serverSelectionList;

    @Unique
    private Button rdi$roomButton;
    @Unique
    private int rdi$layoutWidth = Integer.MIN_VALUE;
    @Unique
    private int rdi$layoutHeight = Integer.MIN_VALUE;

    protected mFabricMultiplayerScreen(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void rdi$addRoomButton(CallbackInfo ci) {
        this.rdi$roomButton = this.addRenderableWidget(
                RoomJoinUi20.createRoomButton((Screen) (Object) this)
                        .size(200, RDI$ROOM_BUTTON_HEIGHT)
                        .build()
        );
        this.rdi$roomButton.active = this.minecraft.allowsMultiplayer();
        this.rdi$layoutRoomEntry();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void rdi$refreshRoomButtonLayout(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci
    ) {
        if (this.rdi$layoutWidth != this.width || this.rdi$layoutHeight != this.height) {
            this.rdi$layoutRoomEntry();
        }
    }

    @Unique
    private void rdi$layoutRoomEntry() {
        if (this.rdi$roomButton == null || this.serverSelectionList == null) {
            return;
        }
        int buttonWidth = Math.min(200, Math.max(0, this.width - 20));
        this.rdi$roomButton.setX((this.width - buttonWidth) / 2);
        this.rdi$roomButton.setY(RDI$ROOM_BUTTON_TOP);
        this.rdi$roomButton.setWidth(buttonWidth);
        this.serverSelectionList.updateSize(
                this.width,
                this.height,
                RDI$SERVER_LIST_TOP,
                Math.max(RDI$SERVER_LIST_TOP, this.height - 64)
        );
        this.rdi$layoutWidth = this.width;
        this.rdi$layoutHeight = this.height;
    }
}
