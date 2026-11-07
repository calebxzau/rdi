package calebxzau.rdi.service

import calebxzhou.rdi.client.service.mcInstall
import calebxzhou.rdi.client.ui.McPlayArgs
import java.nio.file.Files
import java.nio.file.Path

/** Explicit development override, applied after normal core synchronization. */
internal object DevelopmentCoreOverride {
    fun install(args: McPlayArgs): Result<Unit> = runCatching {
        if (!args.withRdiCore) return@runCatching
        val configured = System.getenv("RDI_SERVICE_CORE_JAR") ?: return@runCatching
        val source = Path.of(configured).toAbsolutePath().normalize()
        val expectedName = "rdi-5-mc-client-${args.mcVer.mcVer}-${args.modLoader.directorySlug}.jar"
        require(source.fileName.toString() == expectedName && Files.isRegularFile(source)) {
            "本地测试核心与游戏版本不匹配"
        }
        val mods = (args.versionDir?.let(Path::of) ?: mcInstall.versionListDir.resolve(args.versionId).toPath()).resolve("mods")
        val target = mods.resolve(expectedName).toAbsolutePath().normalize()
        val repository = generateSequence(target.parent) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve(".git")) }
            ?: error("本地测试核心仅可用于开发工作区")
        install(source, target, repository).getOrThrow()
    }

    internal fun install(source: Path, target: Path, repository: Path): Result<Unit> = runCatching {
        require(source != target && target.startsWith(repository)) { "本地测试核心路径无效" }
        Files.createDirectories(target.parent)
        if (Files.exists(target) && Files.mismatch(source, target) == -1L) return@runCatching
        var archived: Path? = null
        if (Files.exists(target)) {
            val archiveRoot = Files.createDirectories(repository.resolve("DEL"))
            val operation = Files.createTempDirectory(archiveRoot, "u2-local-core-")
            archived = operation.resolve(repository.relativize(target))
            Files.createDirectories(archived.parent)
            Files.move(target, archived)
        }
        try {
            Files.copy(source, target)
        } catch (failure: Exception) {
            if (!Files.exists(target) && archived != null) Files.move(archived, target)
            throw failure
        }
    }
}
