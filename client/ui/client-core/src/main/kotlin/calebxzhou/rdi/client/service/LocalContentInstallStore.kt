package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
enum class LocalContentType {
    MOD,
    RESOURCE_PACK,
    SHADER_PACK
}

@Serializable
data class LocalContentInstallRecord(
    val type: LocalContentType,
    val source: String,
    val projectId: String,
    val versionId: String,
    val fileName: String,
    val hash: String? = null,
    val installedAt: Long = System.currentTimeMillis()
) {
    val projectKey: String get() = "${type.name}:$source:$projectId"
}

@Serializable
private data class LocalContentInstallManifest(
    val entries: List<LocalContentInstallRecord> = emptyList()
)

object LocalContentInstallStore {
    fun validateRemoval(packdir: ModpackLocalDir): Result<Unit> = runCatching {
        require(RunningGameGuard.aliveCount(packdir.versionId) == 0) {
            "当前整合包正在运行，不能删除已安装内容"
        }
    }

    fun read(packdir: ModpackLocalDir): Result<List<LocalContentInstallRecord>> = runCatching {
        val file = manifestFile(packdir)
        if (!file.isFile) emptyList()
        else serdesJson.decodeFromString<LocalContentInstallManifest>(file.readText()).entries
    }

    fun find(
        packdir: ModpackLocalDir,
        type: LocalContentType,
        source: String,
        projectId: String
    ): Result<LocalContentInstallRecord?> = read(packdir).map { entries ->
        entries.firstOrNull {
            it.type == type && it.source == source && it.projectId == projectId
        }
    }

    @Synchronized
    fun replace(
        packdir: ModpackLocalDir,
        records: Collection<LocalContentInstallRecord>
    ): Result<Unit> = runCatching {
        val replacements = records.associateBy(LocalContentInstallRecord::projectKey)
        val entries = read(packdir).getOrThrow()
            .filterNot { it.projectKey in replacements } + replacements.values
        write(packdir, LocalContentInstallManifest(entries))
    }

    private fun write(packdir: ModpackLocalDir, manifest: LocalContentInstallManifest) {
        val file = manifestFile(packdir)
        file.parentFile.mkdirs()
        val temporary = file.parentFile.resolve("${file.name}.tmp")
        temporary.writeText(serdesJson.encodeToString(LocalContentInstallManifest.serializer(), manifest))
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun manifestFile(packdir: ModpackLocalDir) =
        packdir.dir.resolve(".rdi/content-installations.json")
}
