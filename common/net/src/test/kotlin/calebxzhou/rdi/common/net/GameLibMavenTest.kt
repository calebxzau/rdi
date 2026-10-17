package calebxzhou.rdi.common.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLibMavenTest {
    private val neoformPath = "net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip"

    @Test
    fun `known repositories map to a library path`() {
        assertEquals(neoformPath, GameLibMaven.libraryPathOf("https://maven.neoforged.net/releases/$neoformPath"))
        assertEquals(
            "net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/sponge-mixin-0.15.2+mixin.0.8.7.jar",
            GameLibMaven.libraryPathOf("https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/sponge-mixin-0.15.2+mixin.0.8.7.jar"),
        )
        assertEquals(
            "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-installer.jar",
            GameLibMaven.libraryPathOf("https://files.minecraftforge.net/maven/net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-installer.jar"),
        )
    }

    @Test
    fun `other urls are not served`() {
        assertNull(GameLibMaven.libraryPathOf("https://piston-data.mojang.com/v1/objects/abc/client.jar"))
        assertNull(GameLibMaven.libraryPathOf("https://resources.download.minecraft.net/ab/abcdef"))
        assertNull(GameLibMaven.libraryPathOf("https://bmclapi2.bangbang93.com/maven/$neoformPath"))
    }

    @Test
    fun `unsafe or non-library paths are rejected`() {
        assertTrue(GameLibMaven.isValidLibraryPath(neoformPath))
        assertFalse(GameLibMaven.isValidLibraryPath("net/neoforged/../../../etc/passwd.jar"))
        assertFalse(GameLibMaven.isValidLibraryPath("net/neoforged/neoform//x.jar"))
        assertFalse(GameLibMaven.isValidLibraryPath("a/b/c.jar"))
        assertFalse(GameLibMaven.isValidLibraryPath("net/neoforged/neoform/1.0/neoform-1.0.pom"))
        assertFalse(GameLibMaven.isValidLibraryPath("net/neoforged/neoform/1.0/a b.jar"))
        assertFalse(GameLibMaven.isValidLibraryPath("net\\neoforged\\neoform\\1.0\\x.jar"))
    }

    @Test
    fun `rdi source url carries a valid sha1 only`() {
        val official = "https://maven.neoforged.net/releases/$neoformPath"
        val sha1 = "0123456789ABCDEF0123456789ABCDEF01234567"
        assertEquals(
            "https://rdi.example:65331/game-lib/maven/$neoformPath?sha1=${sha1.lowercase()}",
            GameLibMaven.rdiSourceUrl("https://rdi.example:65331/", official, sha1),
        )
        assertEquals(
            "https://rdi.example:65331/game-lib/maven/$neoformPath",
            GameLibMaven.rdiSourceUrl("https://rdi.example:65331", official, "not-a-sha1"),
        )
        assertNull(GameLibMaven.rdiSourceUrl("https://rdi.example:65331", "https://example.com/$neoformPath"))
    }

    @Test
    fun `official upstream starts with the matching repository`() {
        val urls = GameLibMaven.officialUrlsFor(neoformPath)
        assertEquals("https://maven.neoforged.net/releases/$neoformPath", urls.first())
        assertEquals(4, urls.size)
        assertEquals(
            "https://libraries.minecraft.net/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
            GameLibMaven.officialUrlsFor("com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar").first(),
        )
    }
}
