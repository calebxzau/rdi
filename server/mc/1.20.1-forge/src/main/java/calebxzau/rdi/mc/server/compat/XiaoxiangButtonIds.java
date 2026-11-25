package calebxzau.rdi.mc.server.compat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Xiaoxiang Cultivation World (0.3.377) sends container button ids above 127, but the 1.20.1
 * {@code ServerboundContainerButtonClickPacket} writes the id as a single byte. The server then sees
 * 262 as 6, 263 as 7 and 261 as 5, and {@code clickMenuButton} ignores the click. None of these menus
 * uses the low ids, so mapping them back is safe.
 */
public final class XiaoxiangButtonIds {
    public static final String MOD_ID = "xiaoxiang_cultivation";
    public static final String STORAGE_MENU = "com.xiaoxiang.cultivation.inventory.StorageNetworkMenu";
    public static final String ALCHEMY_MENU = "com.xiaoxiang.cultivation.inventory.AlchemyMenu";
    public static final String REFINING_MENU = "com.xiaoxiang.cultivation.inventory.RefiningMenu";
    public static final String CLICK_MENU_BUTTON = "m_6366_";
    public static final String CLICK_MENU_BUTTON_DESC = "(Lnet/minecraft/world/entity/player/Player;I)Z";

    static final int STORAGE_INPUT = 262;
    static final int STORAGE_OUTPUT = 263;
    static final int FURNACE_AUTOMATION = 261;

    private XiaoxiangButtonIds() {
    }

    /** 天工镜自动输入／输出. */
    public static int storageButton(int buttonId) {
        if (buttonId == truncated(STORAGE_INPUT)) {
            return STORAGE_INPUT;
        }
        if (buttonId == truncated(STORAGE_OUTPUT)) {
            return STORAGE_OUTPUT;
        }
        return buttonId;
    }

    /** 炼丹炉／炼器炉自动运作. */
    public static int furnaceButton(int buttonId) {
        return buttonId == truncated(FURNACE_AUTOMATION) ? FURNACE_AUTOMATION : buttonId;
    }

    /** The wide ids the menu still compares against when the bug is present, or an empty array. */
    public static int[] wideIdsFor(String targetClassName) {
        return switch (targetClassName) {
            case STORAGE_MENU -> new int[]{STORAGE_INPUT, STORAGE_OUTPUT};
            case ALCHEMY_MENU, REFINING_MENU -> new int[]{FURNACE_AUTOMATION};
            default -> new int[0];
        };
    }

    /**
     * True when {@code clickMenuButton} of the class still checks every given wide id. Once the mod
     * author changes the ids, the constants disappear and the fix must stay off.
     */
    public static boolean usesWideIds(ClassNode classNode, int[] wideIds) {
        if (wideIds.length == 0) {
            return false;
        }
        for (MethodNode method : classNode.methods) {
            if (!CLICK_MENU_BUTTON.equals(method.name) || !CLICK_MENU_BUTTON_DESC.equals(method.desc)) {
                continue;
            }
            Set<Integer> constants = new HashSet<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof IntInsnNode intInsn && intInsn.getOpcode() == Opcodes.SIPUSH) {
                    constants.add(intInsn.operand);
                }
            }
            for (int wideId : wideIds) {
                if (!constants.contains(wideId)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static int truncated(int buttonId) {
        return (byte) buttonId;
    }
}
