package calebxzhou.rdi.client.ui.comp

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CardHintHostTest {
    private val window = IntSize(400, 300)
    private val hint = IntSize(120, 24)

    @Test
    fun `hint sits below right of cursor when there is room`() {
        assertEquals(
            IntOffset(112, 120),
            cardMouseHintPosition(IntOffset(100, 100), hint, window, gap = 12, belowGap = 20),
        )
    }

    @Test
    fun `hint flips left near the right edge`() {
        assertEquals(
            IntOffset(218, 120),
            cardMouseHintPosition(IntOffset(350, 100), hint, window, gap = 12, belowGap = 20),
        )
    }

    @Test
    fun `hint flips above near the bottom edge`() {
        assertEquals(
            IntOffset(112, 254),
            cardMouseHintPosition(IntOffset(100, 290), hint, window, gap = 12, belowGap = 20),
        )
    }

    @Test
    fun `hint is clamped when window is smaller than hint`() {
        assertEquals(
            IntOffset(0, 0),
            cardMouseHintPosition(IntOffset(50, 10), hint, IntSize(100, 20), gap = 12, belowGap = 20),
        )
    }
}
