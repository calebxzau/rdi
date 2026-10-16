package calebxzhou.rdi.client.service

import java.awt.DisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayModesTest {
    @Test
    fun `modes are formatted as width x height at refresh rate without duplicates`() {
        val modes = listOf(
            DisplayMode(2560, 1440, 32, 144),
            DisplayMode(1920, 1080, 32, 60),
            DisplayMode(2560, 1440, 32, 144),
        )

        assertEquals(listOf("2560x1440@144", "1920x1080@60"), formatDisplayModes(modes))
    }
}
