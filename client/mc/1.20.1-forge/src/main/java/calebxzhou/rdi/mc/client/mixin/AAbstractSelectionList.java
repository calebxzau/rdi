package calebxzhou.rdi.mc.client.mixin;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Row bounds for the MCP screen tools. */
@Mixin(AbstractSelectionList.class)
public interface AAbstractSelectionList {
    @Invoker("getRowTop")
    int rdi$getRowTop(int index);

    @Invoker("getRowBottom")
    int rdi$getRowBottom(int index);
}
