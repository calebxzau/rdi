package calebxzau.rdi.mc.server.compat

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XiaoxiangButtonIdsTest {
    @Test
    fun `truncated storage ids map back to the wide ids`() {
        assertEquals(262, XiaoxiangButtonIds.storageButton(262.toByte().toInt()))
        assertEquals(263, XiaoxiangButtonIds.storageButton(263.toByte().toInt()))
        assertEquals(6, 262.toByte().toInt())
        assertEquals(7, 263.toByte().toInt())
    }

    @Test
    fun `truncated furnace id maps back to the wide id`() {
        assertEquals(5, 261.toByte().toInt())
        assertEquals(261, XiaoxiangButtonIds.furnaceButton(5))
    }

    @Test
    fun `other ids pass through unchanged`() {
        for (id in listOf(0, 1, 5, 8, 11, 13, 14, 15, 127, -1)) {
            assertEquals(id, XiaoxiangButtonIds.storageButton(id))
        }
        for (id in listOf(0, 4, 6, 7, 11, 12, 13, 14, 15, -1)) {
            assertEquals(id, XiaoxiangButtonIds.furnaceButton(id))
        }
    }

    @Test
    fun `wide ids per menu`() {
        assertContentEquals(intArrayOf(262, 263), XiaoxiangButtonIds.wideIdsFor(XiaoxiangButtonIds.STORAGE_MENU))
        assertContentEquals(intArrayOf(261), XiaoxiangButtonIds.wideIdsFor(XiaoxiangButtonIds.ALCHEMY_MENU))
        assertContentEquals(intArrayOf(261), XiaoxiangButtonIds.wideIdsFor(XiaoxiangButtonIds.REFINING_MENU))
        assertContentEquals(intArrayOf(), XiaoxiangButtonIds.wideIdsFor("com.example.OtherMenu"))
    }

    @Test
    fun `gate applies while clickMenuButton compares every wide id`() {
        assertTrue(XiaoxiangButtonIds.usesWideIds(menu(262, 263), intArrayOf(262, 263)))
        assertTrue(XiaoxiangButtonIds.usesWideIds(menu(261, 14, 15), intArrayOf(261)))
    }

    @Test
    fun `gate skips once the mod stops using the wide ids`() {
        assertFalse(XiaoxiangButtonIds.usesWideIds(menu(262), intArrayOf(262, 263)))
        assertFalse(XiaoxiangButtonIds.usesWideIds(menu(6, 7), intArrayOf(262, 263)))
        assertFalse(XiaoxiangButtonIds.usesWideIds(menu(262, 263), intArrayOf()))
    }

    @Test
    fun `gate only reads clickMenuButton`() {
        val node = menu(1)
        node.methods.add(method("m_6366_", "()Z", 262, 263))
        node.methods.add(method("other", XiaoxiangButtonIds.CLICK_MENU_BUTTON_DESC, 262, 263))
        assertFalse(XiaoxiangButtonIds.usesWideIds(node, intArrayOf(262, 263)))
    }

    private fun menu(vararg constants: Int): ClassNode = ClassNode().apply {
        name = "com/xiaoxiang/cultivation/inventory/TestMenu"
        methods.add(method(XiaoxiangButtonIds.CLICK_MENU_BUTTON, XiaoxiangButtonIds.CLICK_MENU_BUTTON_DESC, *constants))
    }

    private fun method(name: String, desc: String, vararg constants: Int): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null).apply {
            for (constant in constants) {
                // Small constants compile to BIPUSH, wide ones to SIPUSH, as javac does.
                val opcode = if (constant in Byte.MIN_VALUE..Byte.MAX_VALUE) Opcodes.BIPUSH else Opcodes.SIPUSH
                instructions.add(IntInsnNode(opcode, constant))
                instructions.add(InsnNode(Opcodes.POP))
            }
            instructions.add(InsnNode(Opcodes.ICONST_0))
            instructions.add(InsnNode(Opcodes.IRETURN))
        }
}
