package calebxzhou.rdi.client.ui.comp

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class CardHintHostTest {
    private val window = IntSize(400, 300)
    private val hint = IntSize(120, 40)

    private fun place(anchor: IntRect, container: IntSize = window) =
        cardAnchoredHintPlacement(anchor, hint, container, gap = 10, arrowHeight = 7, arrowInset = 16)

    @Test
    fun `hint is centered below the card with the arrow at its middle`() {
        assertEquals(
            CardHintPlacement(IntOffset(90, 117), below = true, arrowX = 60),
            place(IntRect(100, 0, 200, 100)),
        )
    }

    @Test
    fun `hint flips above when the card is near the bottom`() {
        assertEquals(
            CardHintPlacement(IntOffset(90, 143), below = false, arrowX = 60),
            place(IntRect(100, 200, 200, 290)),
        )
    }

    @Test
    fun `hint stays inside the window and the arrow still points at the card`() {
        assertEquals(
            CardHintPlacement(IntOffset(280, 117), below = true, arrowX = 70),
            place(IntRect(320, 0, 380, 100)),
        )
    }

    @Test
    fun `arrow keeps clear of the rounded corners`() {
        assertEquals(
            CardHintPlacement(IntOffset(0, 117), below = true, arrowX = 16),
            place(IntRect(0, 0, 10, 100)),
        )
    }

    @Test
    fun `hint stays below when it fits neither side`() {
        assertEquals(
            CardHintPlacement(IntOffset(0, 0), below = true, arrowX = 50),
            place(IntRect(0, 0, 100, 30), container = IntSize(120, 40)),
        )
    }
}
