package calebxzau.rdi.client.ui.window

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitialWindowPlacementTest {
    @Test
    fun `small logical screen height starts maximized`() {
        // 1080p@125%缩放
        assertTrue(shouldStartMaximized(logicalScreenHeight = 864, diagonalInches = null))
        // 1366x768@100%
        assertTrue(shouldStartMaximized(logicalScreenHeight = 768, diagonalInches = 24.0))
    }

    @Test
    fun `logical height at threshold stays floating without edid`() {
        assertFalse(shouldStartMaximized(logicalScreenHeight = 900, diagonalInches = null))
        assertFalse(shouldStartMaximized(logicalScreenHeight = 1080, diagonalInches = null))
    }

    @Test
    fun `laptop sized panel starts maximized`() {
        assertTrue(shouldStartMaximized(logicalScreenHeight = 1080, diagonalInches = 14.0))
        assertTrue(shouldStartMaximized(logicalScreenHeight = 1440, diagonalInches = 17.3))
    }

    @Test
    fun `desktop monitor stays floating`() {
        assertFalse(shouldStartMaximized(logicalScreenHeight = 1080, diagonalInches = 17.4))
        assertFalse(shouldStartMaximized(logicalScreenHeight = 1440, diagonalInches = 27.0))
    }

    @Test
    fun `placement maps from decision`() {
        assertEquals(WindowPlacement.Maximized, initialWindowPlacement(720, null))
        assertEquals(WindowPlacement.Floating, initialWindowPlacement(1080, 24.0))
    }
}
