package calebxzau.rdi.client.service

import calebxzhou.rdi.common.archive.extractArchiveToDir
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** Puts a downloaded host save into a modpack's `saves` folder as a new singleplayer save. */
object HostWorldSaveInstaller {
    private val UNSAFE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /** `<房间名>-<日期>`, safe as a Windows folder name; `(2)`, `(3)`… is added when the name is taken. */
    fun folderName(savesDir: Path, hostName: String, day: LocalDate): String {
        val safeName = hostName.replace(UNSAFE_CHARS, "_").trim().trimEnd('.', ' ').ifEmpty { "房间存档" }
        val base = "${safeName}-${day}"
        if (!Files.exists(savesDir.resolve(base), LinkOption.NOFOLLOW_LINKS)) return base
        return generateSequence(2) { it + 1 }
            .map { "${base}(${it})" }
            .first { !Files.exists(savesDir.resolve(it), LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * Extracts [archive] into a hidden folder of [savesDir] first and renames it when complete, so an
     * interrupted extraction never shows up as a broken save. Existing saves are never overwritten.
     */
    fun install(
        archive: Path,
        savesDir: Path,
        hostName: String,
        day: LocalDate,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Path> = runCatching {
        Files.createDirectories(savesDir)
        val staging = Files.createTempDirectory(savesDir, ".rdi-download-")
        try {
            extractArchiveToDir(archive.toFile(), staging.toFile()) { done, total, _ -> onProgress(done, total) }
            if (!Files.isRegularFile(staging.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("存档包里没有level.dat")
            }
            val target = savesDir.resolve(folderName(savesDir, hostName, day))
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, target)
            }
            target
        } catch (error: Throwable) {
            // Only the staging folder made above is removed; nothing else in saves is touched.
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS) && !staging.toFile().deleteRecursively()) {
                error.addSuppressed(IOException("无法清理临时文件夹${staging}"))
            }
            throw error
        }
    }
}
