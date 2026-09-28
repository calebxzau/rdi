package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmWorldInitArchiveTest {
    @Test
    fun removesOwnerPlayerAndExcludesRegionAndLegacyFirmSectionMetadata() {
        val temporary = Files.createTempDirectory("dm-world-init-test-")
        try {
            val world = temporary.resolve("world")
            Files.createDirectories(world.resolve("region"))
            Files.createDirectories(world.resolve("rdi"))
            Files.createDirectories(world.resolve("data"))
            writeLevel(world.resolve("level.dat"))
            writeLevel(world.resolve("level.dat_old"))
            Files.writeString(world.resolve("config.txt"), "kept")
            Files.writeString(world.resolve("region/r.0.0.mca"), "excluded")
            Files.writeString(world.resolve("region/c.0.0.MCC"), "excluded")
            Files.writeString(world.resolve("session.lock"), "excluded")
            Files.writeString(world.resolve("rdi/host.json"), "excluded")
            Files.writeString(world.resolve("data/rdi_firm_sections.dat"), "legacy markers")
            Files.writeString(world.resolve("data/rdi_firm_sections.dat_old"), "legacy backup")
            Files.writeString(world.resolve("data/other.dat"), "kept")

            val archive = temporary.resolve("world.zip")
            DmWorldInitArchive.create(world.resolve("."), archive).getOrThrow()
            val entries = readEntries(archive)

            assertEquals(setOf("level.dat", "level.dat_old", "config.txt", "data/other.dat"), entries.keys)
            assertEquals("legacy markers", Files.readString(world.resolve("data/rdi_firm_sections.dat")))
            assertEquals("legacy backup", Files.readString(world.resolve("data/rdi_firm_sections.dat_old")))
            for (levelName in listOf("level.dat", "level.dat_old")) {
                val root = NbtIo.readCompressed(
                    ByteArrayInputStream(entries.getValue(levelName)),
                    NbtAccounter.unlimitedHeap(),
                )
                val data = root.get("Data") as CompoundTag
                assertFalse(data.contains("Player"))
                assertTrue(data.contains("WorldGenSettings"))
            }
        } finally {
            DmWorldInitArchive.deleteRecursively(temporary).getOrThrow()
        }
    }

    @Test
    fun rejectsTraversalDuringExtraction() {
        val temporary = Files.createTempDirectory("dm-world-extract-test-")
        try {
            val archive = temporary.resolve("bad.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            assertFails {
                DmWorldInitArchive.extract(archive, temporary.resolve("output")).getOrThrow()
            }
            assertFalse(Files.exists(temporary.resolve("outside.txt")))
        } finally {
            DmWorldInitArchive.deleteRecursively(temporary).getOrThrow()
        }
    }

    private fun writeLevel(path: java.nio.file.Path) {
        val data = CompoundTag().apply {
            put("Player", CompoundTag().apply { putInt("XpLevel", 8) })
            put("WorldGenSettings", CompoundTag().apply { putLong("seed", 123L) })
        }
        NbtIo.writeCompressed(CompoundTag().apply { put("Data", data) }, path)
    }

    private fun readEntries(path: java.nio.file.Path): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                zip.copyTo(output)
                entries[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        return entries
    }
}
