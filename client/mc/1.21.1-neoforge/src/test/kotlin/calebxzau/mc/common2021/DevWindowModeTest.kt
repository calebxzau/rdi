package calebxzau.mc.common2021

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevWindowModeTest {
    @Test
    fun playersWithoutThePropertyGetTheNormalWindow() {
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse(null))
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse(""))
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse("  "))
    }

    @Test
    fun readsTheModesWhateverTheirCase() {
        assertEquals(DevWindowMode.Background, DevWindowMode.parse("background"))
        assertEquals(DevWindowMode.Background, DevWindowMode.parse(" BACKGROUND "))
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse("normal"))
    }

    @Test
    fun anUnknownValueIsReportedInsteadOfGuessed() {
        assertNull(DevWindowMode.parse("hidden"))
        assertNull(DevWindowMode.parse("minimized"))
    }
}
