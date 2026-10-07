package calebxzau.rdi.server.service.hostworldimport

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** The three directories involved in replacing a host's world. All are siblings in the host directory. */
data class WorldSwapPaths(val world: Path, val stage: Path, val old: Path) {
    companion object {
        /** Deterministic paths derived from [importId], so recovery never depends on a random name. */
        fun forHost(hostDir: Path, importId: UUID): WorldSwapPaths = WorldSwapPaths(
            world = hostDir.resolve("world"),
            stage = hostDir.resolve(".world-import-stage-${importId}"),
            old = hostDir.resolve(".world-import-old-${importId}"),
        )
    }
}

/** Filesystem operations used by [HostWorldSwap]; tests substitute failures here. */
interface WorldSwapFileSystem {
    /** Whether [path] exists, without following links. A dangling link counts as existing. */
    fun exists(path: Path): Boolean

    /** Atomically renames [source] to [target]. Fails if [target] exists. */
    fun moveAtomically(source: Path, target: Path)

    /** Deletes [path] and its contents, deleting links themselves rather than their targets. */
    fun deleteRecursively(path: Path)
}

object NioWorldSwapFileSystem : WorldSwapFileSystem {
    override fun exists(path: Path): Boolean =
        Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)

    override fun moveAtomically(source: Path, target: Path) {
        // An atomic rename can silently replace an empty directory, so refuse any existing target.
        if (exists(target)) throw FileAlreadyExistsException(target.toString())
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteRecursively(path: Path) {
        path.toFile().deleteRecursivelyNoSymlink()
        if (exists(path)) throw java.io.IOException("无法删除: ${path}")
    }
}
