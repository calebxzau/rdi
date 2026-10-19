package calebxzau.rdi.client.platform

import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The small desktop boundary used by [moveToOsTrash] and its deterministic tests. */
internal interface OsTrashDesktop {
    fun isDesktopSupported(): Boolean
    fun isMoveToTrashSupported(): Boolean
    fun moveToTrash(file: File): Boolean
}

private object AwtOsTrashDesktop : OsTrashDesktop {
    override fun isDesktopSupported(): Boolean = Desktop.isDesktopSupported()

    override fun isMoveToTrashSupported(): Boolean =
        Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)

    override fun moveToTrash(file: File): Boolean = Desktop.getDesktop().moveToTrash(file)
}

/** Moves a file or directory to the operating system's recycle bin/trash. */
fun moveToOsTrash(path: Path): Result<Unit> = moveToOsTrash(path, AwtOsTrashDesktop)

/** Internal adapter overload keeps filesystem behavior testable without touching a real trash. */
internal fun moveToOsTrash(path: Path, desktop: OsTrashDesktop): Result<Unit> = runCatching {
    val normalized = path.toAbsolutePath().normalize()
    require(!normalized.isFileSystemRoot()) { "不能将文件系统根目录移入回收站" }
    if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return@runCatching Unit

    check(desktop.isDesktopSupported()) { "当前系统不支持回收站操作" }
    check(desktop.isMoveToTrashSupported()) { "当前系统不支持回收站操作" }
    check(desktop.moveToTrash(normalized.toFile())) { "无法将路径移入回收站: $normalized" }
    check(!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
        "无法确认路径已移入回收站: $normalized"
    }
}

private fun Path.isFileSystemRoot(): Boolean = fileSystem.rootDirectories.any { root ->
    root.toAbsolutePath().normalize() == this
}
