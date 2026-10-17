package calebxzau.rdi.client.ui

import calebxzhou.rdi.common.model.LibraryOsArch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderFeaturesTest {
    @Test
    fun `only mac disables the launcher renderers`() {
        assertTrue(RenderFeatures.availableOn(LibraryOsArch.WIN_X64))
        assertTrue(RenderFeatures.availableOn(LibraryOsArch.LINUX_X64))
        assertFalse(RenderFeatures.availableOn(LibraryOsArch.MAC_ARM64))
    }
}
