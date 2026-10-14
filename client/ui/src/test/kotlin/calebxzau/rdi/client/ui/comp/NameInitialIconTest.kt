package calebxzau.rdi.client.ui.comp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameInitialIconTest {
    @Test
    fun `initial skips leading brackets and symbols`() {
        assertEquals("导", nameInitial("【导入包】TheFool"))
    }

    @Test
    fun `latin initial is upper-cased`() {
        assertEquals("A", nameInitial("all the mods"))
    }

    @Test
    fun `digits count as initials`() {
        assertEquals("1", nameInitial("1.21 vanilla"))
    }

    @Test
    fun `name without letters falls back to question mark`() {
        assertEquals("?", nameInitial(""))
        assertEquals("?", nameInitial("【】 -"))
    }

    @Test
    fun `hue is stable and in range`() {
        val hue = nameHue("GregTech Leisure-私货版")
        assertEquals(hue, nameHue("GregTech Leisure-私货版"))
        assertTrue(hue >= 0f && hue < 360f)
    }
}
