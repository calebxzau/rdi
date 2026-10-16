package calebxzhou.rdi.client

import calebxzhou.rdi.common.model.LibraryOsArch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowModeTest {
    @Test
    fun `solid window disables transparency by default`() {
        assertFalse(windowTransparent(solidWindow = true, jvmProperty = null, osArch = LibraryOsArch.WIN_X64))
        assertTrue(windowTransparent(solidWindow = false, jvmProperty = null, osArch = LibraryOsArch.WIN_X64))
        assertTrue(windowTransparent(solidWindow = false, jvmProperty = null, osArch = LibraryOsArch.MAC_ARM64))
    }

    @Test
    fun `jvm property overrides solid window setting`() {
        assertTrue(windowTransparent(solidWindow = true, jvmProperty = "true", osArch = LibraryOsArch.WIN_X64))
        assertFalse(windowTransparent(solidWindow = false, jvmProperty = "false", osArch = LibraryOsArch.WIN_X64))
    }

    @Test
    fun `linux is transparent only with a running compositor`() {
        fun linux(solidWindow: Boolean, jvmProperty: String?, compositor: Boolean) = windowTransparent(
            solidWindow = solidWindow,
            jvmProperty = jvmProperty,
            osArch = LibraryOsArch.LINUX_X64,
            linuxCompositorRunning = { compositor },
        )
        assertTrue(linux(solidWindow = false, jvmProperty = null, compositor = true))
        assertFalse(linux(solidWindow = false, jvmProperty = null, compositor = false))
        assertFalse(linux(solidWindow = true, jvmProperty = null, compositor = true))
        assertTrue(linux(solidWindow = false, jvmProperty = "true", compositor = false))
    }

    @Test
    fun `other platforms never ask for a linux compositor`() {
        val unexpected = { error("只有Linux需要检测合成器") }
        assertTrue(windowTransparent(false, null, LibraryOsArch.WIN_X64, unexpected))
        assertTrue(windowTransparent(false, null, LibraryOsArch.MAC_ARM64, unexpected))
    }

    @Test
    fun `system title bar only for solid windows on windows`() {
        assertTrue(useSystemWindowDecoration(transparent = false, osArch = LibraryOsArch.WIN_X64))
        assertFalse(useSystemWindowDecoration(transparent = true, osArch = LibraryOsArch.WIN_X64))
        assertFalse(useSystemWindowDecoration(transparent = false, osArch = LibraryOsArch.LINUX_X64))
        assertFalse(useSystemWindowDecoration(transparent = false, osArch = LibraryOsArch.MAC_ARM64))
    }
}
