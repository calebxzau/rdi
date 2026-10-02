package calebxzhou.rdi.mc.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Contains a crash from AE2WTLib 19.5.1: its restock overlay injects into {@code Gui.renderSlot} and calls
 * {@code ReadableNumberConverter.format} with a negative amount, which throws before drawing anything and
 * takes the whole client tick down with it.
 *
 * <p>The whole {@code renderSlot} method is wrapped instead of the decoration call site, because AE2WTLib
 * throws before it reaches that call. MixinExtras relocates the method body only in
 * {@code WrapMethodApplicatorExtension#postApply}, after every mixin for this class has been applied, so the
 * relocated body still contains AE2WTLib's injected callback and our try/catch encloses it.
 *
 * <p>Only the known failure is swallowed; every other exception is rethrown, so unrelated AE2 bugs still
 * surface. After swallowing, the vanilla item decorations are drawn so the hotbar count/durability survives.
 */
@Mixin(Gui.class)
public abstract class mGuiRestockGuard {
    @Unique
    private static final Logger RDI$LOGGER = LoggerFactory.getLogger("rdi.ae2wtlib.restock");
    @Unique
    private static final String RDI$RESTOCK_FAILURE_MESSAGE =
            "Non-negative numbers cannot be formatted by this method";
    @Unique
    private static final long RDI$WARN_INTERVAL_MS = 10_000L;
    @Unique
    private static long RDI$lastWarnAt;
    @Unique
    private static int RDI$suppressedCount;

    @Shadow
    private Minecraft minecraft;

    @WrapMethod(method = "renderSlot")
    private void rdi$guardRestockOverlay(GuiGraphics guiGraphics, int x, int y, DeltaTracker deltaTracker,
                                         Player player, ItemStack stack, int seed, Operation<Void> original) {
        try {
            original.call(guiGraphics, x, y, deltaTracker, player, stack, seed);
        } catch (IllegalArgumentException exception) {
            if (!rdi$isAe2WtLibRestockFailure(exception)) {
                throw exception;
            }
            rdi$logRestockFailure(exception);
            rdi$renderVanillaDecorations(guiGraphics, stack, x, y);
        }
    }

    @Unique
    private static boolean rdi$isAe2WtLibRestockFailure(IllegalArgumentException exception) {
        if (!RDI$RESTOCK_FAILURE_MESSAGE.equals(exception.getMessage())) {
            return false;
        }
        boolean converterFrame = false;
        boolean ae2WtLibFrame = false;
        for (StackTraceElement frame : exception.getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("appeng.util.ReadableNumberConverter")) {
                converterFrame = true;
            }
            if (className.contains("ae2wtlib") || frame.getMethodName().contains("ae2wtlib")) {
                ae2WtLibFrame = true;
            }
        }
        return converterFrame && ae2WtLibFrame;
    }

    @Unique
    private static void rdi$logRestockFailure(IllegalArgumentException exception) {
        long now = System.currentTimeMillis();
        int suppressed;
        synchronized (RDI$LOGGER) {
            if (now - RDI$lastWarnAt < RDI$WARN_INTERVAL_MS) {
                RDI$suppressedCount++;
                return;
            }
            suppressed = RDI$suppressedCount;
            RDI$suppressedCount = 0;
            RDI$lastWarnAt = now;
        }
        RDI$LOGGER.warn("AE2WTLib restock overlay failed in Gui.renderSlot, falling back to the vanilla item "
                + "decorations ({} repeat(s) suppressed since the last report)", suppressed, exception);
    }

    @Unique
    private void rdi$renderVanillaDecorations(GuiGraphics guiGraphics, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) {
            return;
        }
        try {
            guiGraphics.renderItemDecorations(this.minecraft.font, stack, x, y);
        } catch (Throwable fallbackFailure) {
            RDI$LOGGER.debug("Vanilla item decorations failed as well", fallbackFailure);
        }
    }
}
