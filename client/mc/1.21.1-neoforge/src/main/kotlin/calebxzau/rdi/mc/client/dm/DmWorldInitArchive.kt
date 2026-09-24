package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayOutputStream
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.HashSet
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DmWorldInitArchive {
    private val excludedExtensions = setOf("mca", "mcc")

    fun create(worldRoot: Path, destination: Path, cancelled: () -> Boolean = { false }): Result<Unit> = runCatching {
        require(Files.isDirectory(worldRoot)) { "世界目录不存在" }
        require(!destination.toAbsolutePath().normalize().startsWith(worldRoot.toAbsolutePath().normalize())) {
            "初始化资料暂存位置不能位于世界目录内"
        }
        destination.parent?.let(Files::createDirectories)
        Files.newOutputStream(destination).use { output ->
            ZipOutputStream(output).use { zip ->
            Files.walk(worldRoot, FileVisitOption.FOLLOW_LINKS).use { paths ->
                    val entries = HashSet<String>()
                    paths.filter { it != worldRoot && Files.isRegularFile(it) }.forEach { file ->
                        if (cancelled()) throw CancellationException("已取消DM房间创建")
                        val relative = worldRoot.relativize(file).normalize()
                        val entryName = relative.toString().replace('\\', '/')
                        if (excluded(relative, entryName)) return@forEach
                        require(entries.add(entryName)) { "世界目录包含重复的初始化路径：$entryName" }
                        zip.putNextEntry(ZipEntry(entryName))
                        if (entryName == "level.dat" || entryName == "level.dat_old") {
                            sanitizedLevelData(file).use { input -> copyCheckingCancellation(input, zip, cancelled) }
                        } else {
                            Files.newInputStream(file).use { input -> copyCheckingCancellation(input, zip, cancelled) }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun copyCheckingCancellation(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        cancelled: () -> Boolean,
    ) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (cancelled()) throw CancellationException("已取消DM房间创建")
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    fun extract(archive: Path, destination: Path): Result<Unit> = runCatching {
        require(Files.isRegularFile(archive)) { "初始化ZIP不存在" }
        Files.createDirectories(destination)
        ZipInputStream(Files.newInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val normalized = validateEntry(entry.name)
                val destinationRoot = destination.toAbsolutePath().normalize()
                val target = destinationRoot.resolve(normalized).normalize()
                require(target.startsWith(destinationRoot)) { "ZIP路径越界：${entry.name}" }
                require(!hasSymlinkParent(destinationRoot, normalized)) { "ZIP目标路径包含符号链接：${entry.name}" }
                if (entry.isDirectory) {
                    require(!Files.isSymbolicLink(target)) { "ZIP目标不能是符号链接：${entry.name}" }
                    Files.createDirectories(target)
                } else {
                    target.parent?.let(Files::createDirectories)
                    require(!Files.isSymbolicLink(target)) { "ZIP目标不能是符号链接：${entry.name}" }
                    Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { input.copyTo(it) }
                }
                input.closeEntry()
            }
        }
    }

    private fun excluded(relative: Path, entryName: String): Boolean {
        val fileName = relative.fileName.toString()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in excludedExtensions ||
            fileName.equals("session.lock", ignoreCase = true) ||
            entryName.equals("rdi/host.json", ignoreCase = true) ||
            entryName.equals("data/rdi_firm_sections.dat", ignoreCase = true) ||
            entryName.equals("data/rdi_firm_sections.dat_old", ignoreCase = true)
    }

    private fun sanitizedLevelData(file: Path): java.io.InputStream {
        val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        val data = tag.get("Data")
        require(data == null || data is CompoundTag) { "${file.fileName}的Data不是NBT复合标签" }
        (data as? CompoundTag)?.remove("Player")
        val bytes = ByteArrayOutputStream()
        NbtIo.writeCompressed(tag, bytes)
        return bytes.toByteArray().inputStream()
    }

    private fun validateEntry(name: String): Path {
        require(name.isNotBlank() && !name.contains('\u0000')) { "ZIP包含无效路径" }
        val portable = name.replace('\\', '/')
        require(!portable.startsWith('/') && !portable.startsWith("//")) { "ZIP不能包含绝对路径" }
        require(!portable.matches(Regex("^[A-Za-z]:($|/).*"))) { "ZIP不能包含Windows绝对路径" }
        val path = Path.of(portable)
        require(!path.isAbsolute) { "ZIP不能包含绝对路径" }
        val normalized = path.normalize()
        require(normalized != Path.of(".") && normalized.firstOrNull()?.toString() != "..") { "ZIP路径越界" }
        return normalized
    }

    private fun hasSymlinkParent(root: Path, relative: Path): Boolean {
        var current = root
        val parent = relative.parent ?: return false
        for (part in parent) {
            current = current.resolve(part.toString())
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    fun deleteRecursively(path: Path): Result<Unit> = runCatching {
        if (!Files.exists(path)) return@runCatching
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
