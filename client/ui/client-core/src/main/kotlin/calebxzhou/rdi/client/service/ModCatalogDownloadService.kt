package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.ModrinthProjectInfoVo
import calebxzhou.rdi.client.model.ModrinthProjectVersionVo
import calebxzhou.rdi.client.model.ModCatalogSource
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.toCurseForgeModSide
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.service.murmur2
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.sha1
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ModCatalogDownloadService {
    fun toMod(project: ModrinthProjectInfoVo, version: ModrinthProjectVersionVo): Mod {
        val file = version.primaryFile ?: error("此版本没有可下载文件")
        return when (project.source) {
            ModCatalogSource.MODRINTH -> {
                val sha1 = file.sha1?.trim()?.takeIf(String::isNotBlank) ?: error("此版本缺少SHA1")
                Mod(
                    platform = "mr",
                    projectId = project.projectId,
                    slug = project.slug,
                    fileId = version.id,
                    hash = sha1,
                    side = version.environment.toModSide(),
                    downloadUrls = listOf(file.url)
                )
            }

            ModCatalogSource.CURSEFORGE -> {
                val murmur2 = file.murmur2?.trim()?.takeIf(String::isNotBlank) ?: error("此版本缺少CurseForge指纹")
                Mod(
                    platform = "cf",
                    projectId = project.projectId,
                    slug = project.slug,
                    fileId = file.fileId ?: version.id,
                    hash = murmur2,
                    side = version.gameVersions.toCurseForgeModSide() ?: Mod.Side.BOTH,
                    downloadUrls = listOf(file.url)
                )
            }
        }
    }

    fun downloadToLocalModpackTask2(
        mod: Mod,
        packdir: ModpackLocalDir
    ): Task2 = downloadToLocalModpackTask2(listOf(mod), packdir)

    fun downloadToLocalModpackTask2(
        mods: List<Mod>,
        packdir: ModpackLocalDir
    ): Task2 {
        val distinctMods = mods.distinctBy { "${it.platform}:${it.projectId}" }
        return Task2.Leaf("安装${distinctMods.size}个Mod到${packdir.name}") { ctx ->
            require(packdir.dir.isDirectory) { "目标整合包已不存在" }
            val installed = LocalContentInstallStore.read(packdir).getOrThrow()
            val existingByProject = installed
                .filter { it.type == LocalContentType.MOD }
                .associateBy { "${it.source}:${it.projectId}" }
            val plans = distinctMods.map { mod ->
                ModInstallPlan(
                    mod = mod,
                    existing = existingByProject["${mod.platform}:${mod.projectId}"]
                )
            }
            val modsDir = packdir.dir.resolve("mods").also { it.mkdirs() }
            val pendingPlans = plans.filterNot { plan ->
                plan.existing?.versionId == plan.mod.fileId && plan.matchesInstalledFile(modsDir)
            }
            if (pendingPlans.isEmpty()) {
                ctx.emit(Task2Progress("所有Mod均已安装", 1f))
                return@Leaf
            }
            if (RunningGameGuard.aliveCount(packdir.versionId) > 0 && pendingPlans.any { it.existing != null }) {
                error("当前整合包正在运行，不能更新Mod")
            }
            val installingWhileRunning = RunningGameGuard.aliveCount(packdir.versionId) > 0
            pendingPlans.forEach { plan ->
                val target = modsDir.resolve(plan.mod.fileName)
                if (target.exists() && plan.existing?.fileName != target.name) {
                    error("目标文件已存在且不属于RDI管理: ${target.name}")
                }
            }

            val workDir = packdir.dir.resolve(".rdi/installing").also { it.mkdirs() }
            val stagingDir = Files.createTempDirectory(workDir.toPath(), "mods-").toFile()
            val requests = pendingPlans.map { plan ->
                plan.mod.toClientContentRequest(targetRelativePath = plan.mod.fileName)
            }
            try {
                ClientContentStores.shared.materialize(
                    requests = requests,
                    targetRoot = stagingDir.toPath(),
                    onProgress = ctx::emit
                ).getOrThrow()

                val backups = mutableListOf<Pair<java.io.File, java.io.File>>()
                val linkedTargets = mutableListOf<java.io.File>()
                try {
                    pendingPlans.forEach { plan ->
                        plan.existing?.let { record ->
                            val oldFile = modsDir.resolve(record.fileName)
                            if (oldFile.exists()) {
                                val backup = workDir.resolve(
                                    "mod-${plan.mod.platform}-${plan.mod.projectId.safePathSegment()}.backup"
                                )
                                Files.deleteIfExists(backup.toPath())
                                Files.move(oldFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                backups += oldFile to backup
                            }
                        }
                    }
                    pendingPlans.forEach { plan ->
                        val target = modsDir.resolve(plan.mod.fileName)
                        val staged = stagingDir.resolve(plan.mod.fileName)
                        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        linkedTargets += target
                    }
                    LocalContentInstallStore.replace(
                        packdir,
                        pendingPlans.map { plan ->
                            LocalContentInstallRecord(
                                type = LocalContentType.MOD,
                                source = plan.mod.platform,
                                projectId = plan.mod.projectId,
                                versionId = plan.mod.fileId,
                                fileName = plan.mod.fileName,
                                hash = plan.mod.hash
                            )
                        }
                    ).getOrThrow()
                    backups.forEach { (_, backup) -> runCatching { Files.deleteIfExists(backup.toPath()) } }
                } catch (cause: Exception) {
                    linkedTargets.forEach { Files.deleteIfExists(it.toPath()) }
                    backups.forEach { (original, backup) ->
                        if (backup.exists()) {
                            Files.move(backup.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    throw cause
                }
                ctx.emit(
                    Task2Progress(
                        if (installingWhileRunning) "已安装${pendingPlans.size}个Mod，重启游戏后生效"
                        else "已安装${pendingPlans.size}个Mod到${packdir.name}",
                        1f
                    )
                )
            } finally {
                stagingDir.deleteRecursivelyNoSymlink()
            }
        }
    }
}

private data class ModInstallPlan(
    val mod: Mod,
    val existing: LocalContentInstallRecord?
)

private fun ModInstallPlan.matchesInstalledFile(modsDir: java.io.File): Boolean {
    val record = existing ?: return false
    val file = modsDir.resolve(record.fileName)
    if (!file.isFile) return false
    return when (mod.platform) {
        "mr" -> file.toPath().sha1.equals(mod.hash, ignoreCase = true)
        "cf" -> file.toPath().murmur2.toString() == mod.hash
        else -> true
    }
}

private fun String?.toModSide(): Mod.Side =
    when (this?.lowercase()) {
        "client_only" -> Mod.Side.CLIENT
        "server_only" -> Mod.Side.SERVER
        else -> Mod.Side.BOTH
    }
