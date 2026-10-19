package calebxzau.rdi.client.service

import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostWorldSaveInstallerTest {
    private val temp: Path = Files.createTempDirectory("host-world-save-installer")
    private val saves = temp.resolve("saves")
    private val day = LocalDate.of(2026, 10, 13)

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun folderNameIsSafeAndNeverReusesAnExistingSave() {
        Files.createDirectories(saves)
        assertEquals("我的_房间_-2026-10-13", HostWorldSaveInstaller.folderName(saves, "我的/房间?", day))
        assertEquals("房间存档-2026-10-13", HostWorldSaveInstaller.folderName(saves, " .. ", day))
        Files.createDirectories(saves.resolve("房间-2026-10-13"))
        Files.createDirectories(saves.resolve("房间-2026-10-13(2)"))
        assertEquals("房间-2026-10-13(3)", HostWorldSaveInstaller.folderName(saves, "房间", day))
    }

    @Test
    fun installsANewSaveNextToTheExistingOne() {
        Files.createDirectories(saves.resolve("房间-2026-10-13"))
        Files.writeString(saves.resolve("房间-2026-10-13/level.dat"), "old")
        val archive = archive("level.dat" to "new", "region/r.0.0.mca" to "chunks")

        val installed = HostWorldSaveInstaller.install(archive, saves, "房间", day).getOrThrow()

        assertEquals("房间-2026-10-13(2)", installed.fileName.toString())
        assertEquals("new", Files.readString(installed.resolve("level.dat")))
        assertEquals("chunks", Files.readString(installed.resolve("region/r.0.0.mca")))
        assertEquals("old", Files.readString(saves.resolve("房间-2026-10-13/level.dat")), "the existing save is untouched")
    }

    @Test
    fun archiveWithoutLevelDatLeavesNothingBehind() {
        val archive = archive("region/r.0.0.mca" to "chunks")
        val result = HostWorldSaveInstaller.install(archive, saves, "房间", day)
        assertTrue(result.isFailure)
        assertEquals(emptyList(), Files.list(saves).use { it.toList() })
    }

    private fun archive(vararg entries: Pair<String, String>): Path {
        val file = temp.resolve("save.tar.zst")
        TarZstArchiveWriter(file.toFile()).use { writer -> entries.forEach { (path, text) -> writer.addFile(path, text.toByteArray()) } }
        return file
    }
}
