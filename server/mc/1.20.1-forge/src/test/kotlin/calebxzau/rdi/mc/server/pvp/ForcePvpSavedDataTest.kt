package calebxzau.rdi.mc.server.pvp

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForcePvpSavedDataTest {
    @Test
    fun `new worlds and missing setting keep existing pvp rules`() {
        assertFalse(ForcePvpSavedData().protectionEnabled)
        assertFalse(ForcePvpSavedData.load(CompoundTag()).protectionEnabled)
    }

    @Test
    fun `protection changes require saving but repeated commands do not`() {
        val data = ForcePvpSavedData()
        data.setProtection(false)
        assertFalse(data.isDirty)

        data.setProtection(true)
        assertTrue(data.protectionEnabled)
        assertTrue(data.isDirty)

        data.setDirty(false)
        data.setProtection(true)
        assertFalse(data.isDirty)

        data.setProtection(false)
        assertFalse(data.protectionEnabled)
        assertTrue(data.isDirty)
    }

    @Test
    fun `enabled and disabled states survive world data round trip`() {
        val data = ForcePvpSavedData()
        data.setProtection(true)
        val restored = ForcePvpSavedData.load(data.save(CompoundTag()))
        assertTrue(restored.protectionEnabled)
        assertFalse(restored.isDirty)

        restored.setProtection(false)
        assertFalse(ForcePvpSavedData.load(restored.save(CompoundTag())).protectionEnabled)
    }

    @Test
    fun `separate world data does not share the protection switch`() {
        val firstWorld = ForcePvpSavedData()
        val secondWorld = ForcePvpSavedData()
        firstWorld.setProtection(true)
        assertFalse(secondWorld.protectionEnabled)
    }
}
