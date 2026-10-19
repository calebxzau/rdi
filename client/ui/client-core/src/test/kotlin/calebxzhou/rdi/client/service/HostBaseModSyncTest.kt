package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.Mod
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class HostBaseModSyncTest {
    @Test
    fun `existing regular file and hard link are not missing`() {
        val root = Files.createTempDirectory("host-base-mods")
        try {
            val regular = mod("regular")
            val hardLink = mod("hard")
            Files.write(root.resolve(regular.fileName), byteArrayOf(1, 2, 3))
            val source = Files.write(root.resolve("source.jar"), byteArrayOf(4, 5, 6))
            Files.createLink(root.resolve(hardLink.fileName), source)

            assertEquals(emptyList(), missingHostBaseMods(root, listOf(regular, hardLink)))
            assertTrue(Files.isRegularFile(root.resolve(regular.fileName), LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(root.resolve(hardLink.fileName), LinkOption.NOFOLLOW_LINKS))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing directory and symbolic links require materialization`() {
        val root = Files.createTempDirectory("host-base-mods-missing")
        try {
            val missing = mod("missing")
            val directory = mod("directory")
            val symbolic = mod("symbolic")
            val broken = mod("broken")
            Files.createDirectory(root.resolve(directory.fileName))
            val target = Files.write(root.resolve("target.jar"), byteArrayOf(1))
            try {
                Files.createSymbolicLink(root.resolve(symbolic.fileName), target.fileName)
                Files.createSymbolicLink(root.resolve(broken.fileName), root.resolve("no-such.jar"))
            } catch (_: UnsupportedOperationException) {
                assumeTrue(false, "symbolic links are not supported")
            } catch (_: SecurityException) {
                assumeTrue(false, "symbolic links are not available")
            }

            assertEquals(
                listOf(missing, directory, symbolic, broken),
                missingHostBaseMods(root, listOf(missing, directory, symbolic, broken)),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy filename alias is treated as existing`() {
        val root = Files.createTempDirectory("host-base-mods-alias")
        try {
            val mod = Mod(
                platform = "cf",
                projectId = "true-ending",
                slug = "true-ending",
                fileId = "file",
                hash = "abcdef",
            )
            assumeTrue(mod.fileName != mod.legacyFileName)
            Files.write(root.resolve(mod.legacyFileName), byteArrayOf(1, 2, 3))

            assertEquals(emptyList(), missingHostBaseMods(root, listOf(mod)))
            assertTrue(Files.notExists(root.resolve(mod.fileName), LinkOption.NOFOLLOW_LINKS))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `builder inputs filter removed slug case insensitively while ordinary mod remains eligible`() {
        val removed = mod("Chunky")
        val ordinary = mod("sodium")

        assertEquals(
            HostBaseModSyncInputs(
                activeMods = listOf(ordinary),
                disabledMods = listOf(ordinary),
            ),
            prepareHostBaseModSyncInputs(
                activeMods = listOf(removed, ordinary),
                disabledMods = listOf(removed, ordinary),
            ),
        )
    }

    private fun mod(name: String) = Mod(
        platform = "mr",
        projectId = name,
        slug = name,
        fileId = "file",
        hash = name,
    )
}
