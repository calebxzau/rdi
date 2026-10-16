package calebxzhou.rdi.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryOsArchTest {
    @Test
    fun `supported launcher platforms map both ways`() {
        val supported = mapOf(
            LibraryOsArch.WIN_X64 to "windows-x64",
            LibraryOsArch.LINUX_X64 to "linux-x64",
            LibraryOsArch.MAC_ARM64 to "macos-arm64",
        )
        supported.forEach { (osArch, id) ->
            assertEquals(id, osArch.clientPlatformId)
            assertEquals(osArch, LibraryOsArch.fromClientPlatformId(id))
        }
        assertEquals(supported.keys, LibraryOsArch.entries.filter { it.clientPlatformId != null }.toSet())
    }

    @Test
    fun `unsupported or unknown platform ids are rejected`() {
        listOf("macos-x64", "linux-arm64", "windows-arm64", "win-x64", "", "../windows-x64").forEach { id ->
            assertNull(LibraryOsArch.fromClientPlatformId(id), id)
        }
    }

    @Test
    fun `os family flags`() {
        assertTrue(LibraryOsArch.MAC_ARM64.isMac)
        assertTrue(LibraryOsArch.MAC_X64.isMac)
        assertFalse(LibraryOsArch.LINUX_X64.isMac)
        assertFalse(LibraryOsArch.WIN_X64.isMac)
        assertTrue(LibraryOsArch.LINUX_X64.isLinux)
        assertFalse(LibraryOsArch.MAC_ARM64.isLinux)
        assertFalse(LibraryOsArch.WIN_X64.isLinux)
        assertTrue(LibraryOsArch.LINUX_X64.isUnixLike)
        assertFalse(LibraryOsArch.WIN_X64.isUnixLike)
    }
}
