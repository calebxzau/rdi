package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object PackProcStartupCleanup {
    internal fun clear(root: Path): Result<Unit> = runCatching {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            root.toFile().deleteRecursivelyNoSymlink()
            check(!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                "清理pack-proc目录失败: $root"
            }
        }

        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "清理后pack-proc路径不是目录: $root"
        }
    }
}
