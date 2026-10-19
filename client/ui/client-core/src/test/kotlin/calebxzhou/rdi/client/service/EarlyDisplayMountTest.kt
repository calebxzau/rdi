package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EarlyDisplayMountTest {
    @Test
    fun `mounts provider and directly replaces old target`() {
        val root = Files.createTempDirectory("early-display-mount").toFile()
        try {
            val source = root.resolve("lib/rdi-early-display.jar").apply {
                parentFile.mkdirs()
                writeText("current")
            }
            val versionDir = root.resolve("versions/pack")
            val target = versionDir.resolve("mods/rdi-early-display.jar").apply {
                parentFile.mkdirs()
                writeText("old")
            }

            assertTrue(
                EarlyDisplayMount.mount(source, McVersion.V211, ModLoader.neoforge, versionDir)
                    .getOrThrow()
            )
            assertTrue(Files.isSameFile(source.toPath(), target.toPath()))
            assertEquals("current", target.readText())
            assertFalse(versionDir.resolve("DEL").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `mounts the shared jar on Forge 20`() {
        val root = Files.createTempDirectory("early-display-mount-forge20").toFile()
        try {
            val source = root.resolve("rdi-early-display.jar").apply { writeText("current") }
            val versionDir = root.resolve("versions/pack")

            assertTrue(
                EarlyDisplayMount.mount(source, McVersion.V201, ModLoader.forge, versionDir)
                    .getOrThrow()
            )
            assertTrue(Files.isSameFile(source.toPath(), versionDir.resolve("mods/rdi-early-display.jar").toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not mount on unsupported loader`() {
        val root = Files.createTempDirectory("early-display-mount-unsupported").toFile()
        try {
            val source = root.resolve("rdi-early-display.jar").apply { writeText("current") }
            val versionDir = root.resolve("versions/pack")

            assertFalse(
                EarlyDisplayMount.mount(source, McVersion.V201, ModLoader.neoforge, versionDir)
                    .getOrThrow()
            )
            assertFalse(versionDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `keeps an existing hard link`() {
        val root = Files.createTempDirectory("early-display-mount-existing").toFile()
        try {
            val source = root.resolve("rdi-early-display.jar").apply { writeText("current") }
            val versionDir = root.resolve("versions/pack")
            val target = versionDir.resolve("mods/rdi-early-display.jar")
            target.parentFile.mkdirs()
            Files.createLink(target.toPath(), source.toPath())

            assertFalse(
                EarlyDisplayMount.mount(source, McVersion.V211, ModLoader.neoforge, versionDir)
                    .getOrThrow()
            )
            assertTrue(Files.isSameFile(source.toPath(), target.toPath()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `configures the rdi provider for supported client`() {
        val root = Files.createTempDirectory("early-display-config").toFile()
        try {
            val versionDir = root.resolve("versions/pack")
            val config = versionDir.resolve("config/fml.toml").apply {
                parentFile.mkdirs()
                writeText("""
                    earlyWindowControl = true
                    earlyWindowProvider = "fmlearlywindow"
                """.trimIndent())
            }

            assertTrue(
                EarlyDisplayMount.configureProvider(McVersion.V211, ModLoader.neoforge, versionDir)
                    .getOrThrow()
            )
            assertTrue(config.readText().contains("earlyWindowProvider = \"rdiearlywindow\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not override a disabled early window`() {
        val root = Files.createTempDirectory("early-display-config-disabled").toFile()
        try {
            val versionDir = root.resolve("versions/pack")
            val config = versionDir.resolve("config/fml.toml").apply {
                parentFile.mkdirs()
                writeText("""
                    earlyWindowControl = false
                    earlyWindowProvider = "fmlearlywindow"
                """.trimIndent())
            }

            assertFalse(
                EarlyDisplayMount.configureProvider(McVersion.V211, ModLoader.neoforge, versionDir)
                    .getOrThrow()
            )
            assertTrue(config.readText().contains("earlyWindowProvider = \"fmlearlywindow\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not replace a custom early window provider`() {
        val root = Files.createTempDirectory("early-display-config-custom").toFile()
        try {
            val versionDir = root.resolve("versions/pack")
            val config = versionDir.resolve("config/fml.toml").apply {
                parentFile.mkdirs()
                writeText("""
                    earlyWindowControl = true
                    earlyWindowProvider = "custom"
                """.trimIndent())
            }

            assertFalse(
                EarlyDisplayMount.configureProvider(McVersion.V211, ModLoader.neoforge, versionDir)
                    .getOrThrow()
            )
            assertTrue(config.readText().contains("earlyWindowProvider = \"custom\""))
        } finally {
            root.deleteRecursively()
        }
    }
}
