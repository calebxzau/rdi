package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.EXTRA_MOD_PREFIX
import calebxzhou.rdi.common.model.Mod
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HostExtraModSyncTest {
    @Test
    fun `modified existing extra jar is present despite digest mismatch`() {
        val root = Files.createTempDirectory("host-extra-mods-modified")
        try {
            val mod = mod("modified")
            Files.write(root.resolve(EXTRA_MOD_PREFIX + mod.fileName), byteArrayOf(1, 2, 3))

            assertEquals(emptyList(), missingHostExtraMods(root, listOf(mod)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `only missing extra mods are selected`() {
        val root = Files.createTempDirectory("host-extra-mods-missing")
        try {
            val present = mod("present")
            val missing = mod("missing")
            Files.write(root.resolve(EXTRA_MOD_PREFIX + present.fileName), byteArrayOf(1))

            assertEquals(listOf(missing), missingHostExtraMods(root, listOf(present, missing)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unprefixed base jar does not satisfy extra target`() {
        val root = Files.createTempDirectory("host-extra-mods-prefix")
        try {
            val mod = mod("base-name")
            Files.write(root.resolve(mod.fileName), byteArrayOf(1, 2, 3))

            assertEquals(listOf(mod), missingHostExtraMods(root, listOf(mod)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory at extra target is missing`() {
        val root = Files.createTempDirectory("host-extra-mods-directory")
        try {
            val mod = mod("directory")
            Files.createDirectory(root.resolve(EXTRA_MOD_PREFIX + mod.fileName))

            assertEquals(listOf(mod), missingHostExtraMods(root, listOf(mod)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `builder inputs filter removed slug case insensitively while ordinary mod remains eligible`() {
        val removed = mod("ChUnKy")
        val ordinary = mod("sodium")

        assertEquals(
            listOf(ordinary),
            prepareHostExtraModSyncMods(listOf(removed, ordinary)),
        )
    }

    @Test
    fun `server only and unknown side extra mods are not synced to client`() {
        val both = mod("both")
        val client = mod("client").copy(side = Mod.Side.CLIENT)
        val server = mod("server").copy(side = Mod.Side.SERVER)
        val unknown = mod("unknown").copy(side = Mod.Side.UNKNOWN)

        assertEquals(
            listOf(both, client),
            prepareHostExtraModSyncMods(listOf(both, client, server, unknown)),
        )
    }

    private fun mod(name: String) = Mod(
        platform = "mr",
        projectId = name,
        slug = name,
        fileId = "file",
        hash = "declared-digest-that-does-not-match-file",
    )
}
