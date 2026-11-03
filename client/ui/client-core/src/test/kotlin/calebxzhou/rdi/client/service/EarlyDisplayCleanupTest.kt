package calebxzhou.rdi.client.service

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EarlyDisplayCleanupTest {
    private fun withVersionDir(block: (File) -> Unit) {
        val versionDir = Files.createTempDirectory("early-display-cleanup").toFile()
        try {
            block(versionDir)
        } finally {
            versionDir.deleteRecursively()
        }
    }

    private fun File.writeFmlConfig(text: String): File =
        resolve("config/fml.toml").apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun restoresTheDefaultLoadingWindowAndRemovesTheLegacyJar() = withVersionDir { versionDir ->
        val config = versionDir.writeFmlConfig("earlyWindowControl = true\r\nearlyWindowProvider = \"rdiearlywindow\"\r\nearlyWindowWidth = 2560\r\n")
        val jar = versionDir.resolve("mods/rdi-early-display.jar").apply {
            parentFile.mkdirs()
            writeText("legacy")
        }
        val otherMod = versionDir.resolve("mods/other.jar").apply { writeText("other") }

        EarlyDisplayCleanup.clean(versionDir).getOrThrow()

        assertEquals(
            "earlyWindowControl = true\r\nearlyWindowProvider = \"fmlearlywindow\"\r\nearlyWindowWidth = 2560\r\n",
            config.readText(),
        )
        assertFalse(jar.exists())
        assertTrue(otherMod.exists())
    }

    @Test
    fun keepsWhatThePlayerChose() = withVersionDir { versionDir ->
        val text = "earlyWindowControl = false\nearlyWindowProvider = \"custom\"\n"
        val config = versionDir.writeFmlConfig(text)

        EarlyDisplayCleanup.clean(versionDir).getOrThrow()

        assertEquals(text, config.readText())
    }

    @Test
    fun aFreshGameFolderNeedsNoCleanup() = withVersionDir { versionDir ->
        EarlyDisplayCleanup.clean(versionDir).getOrThrow()

        assertFalse(versionDir.resolve("config").exists())
        assertFalse(versionDir.resolve("mods").exists())
    }
}
