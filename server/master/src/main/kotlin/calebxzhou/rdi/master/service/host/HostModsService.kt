package calebxzhou.rdi.master.service.host

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.CurseForgeFile
import calebxzhou.rdi.common.model.CurseForgeModInfo
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.LoadProgress
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModrinthVersionInfo
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.displaySlugOrProject
import calebxzhou.rdi.common.model.normalizedProjectId
import calebxzhou.rdi.common.model.normalizedSlug
import calebxzhou.rdi.common.model.sameMod
import calebxzhou.rdi.common.service.CurseForgeService
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.ModrinthService
import calebxzhou.rdi.common.service.runInline
import calebxzhou.rdi.master.service.MailService
import calebxzhou.rdi.master.service.ModpackService
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.getVersion
import calebxzhou.rdi.master.service.ServerTaskManager
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.set
import io.ktor.client.call.body
import org.bson.types.ObjectId
import java.net.URI
import java.nio.file.Files

object HostModsService {
    private val lgr by Loggers

    private fun addExtraModsTaskKey(hostId: ObjectId, mods: List<Mod>): String {
        val modKeys = mods.map(::projectIdentity).sorted().joinToString(",")
        return "server-host-add-extra-mods:${hostId.toHexString()}:$modKeys"
    }

    private fun modIdentity(mod: Mod): String {
        return buildString {
            append(mod.platform)
            append(':')
            append(mod.projectId)
            append(':')
            append(mod.fileId)
            append(':')
            append(mod.hash)
            append(':')
            append(mod.slug)
        }
    }

    private fun List<Mod>.distinctBySameMod(): List<Mod> = buildList {
        this@distinctBySameMod.forEach { mod ->
            if (none { sameMod(it, mod) }) add(mod)
        }
    }

    private fun Host.effectiveBaseMods(baseVersion: Modpack.Version): List<Mod> =
        baseVersion.mods.filterNot { baseMod -> disabledMods.any { sameMod(it, baseMod) } }

    private fun projectIdentity(mod: Mod): String = mod.normalizedProjectId

    private fun slugIdentity(mod: Mod): String = mod.normalizedSlug

    private fun modLabel(mod: Mod): String = mod.displaySlugOrProject

    private fun duplicateRequestSlugLabels(mods: List<Mod>): List<String> = mods
        .groupBy(::slugIdentity)
        .filterKeys { it.isNotBlank() }
        .values
        .filter { it.size > 1 }
        .map { modLabel(it.first()) }
        .distinct()

    private fun duplicateExistingSlugLabels(candidateMods: List<Mod>, existingMods: List<Mod>): List<String> {
        val existingSlugs = existingMods.map(::slugIdentity)
            .filter { it.isNotBlank() }
            .toSet()
        return candidateMods
            .filter { slugIdentity(it).isNotBlank() && slugIdentity(it) in existingSlugs }
            .map(::modLabel)
            .distinct()
    }

    private fun String.isValidDownloadUrl(): Boolean {
        return runCatching {
            val uri = URI(this)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private suspend fun validateExtraMods(mods: List<Mod>) {
        val curseForgeMods = mods.filter { it.platform.equals("cf", ignoreCase = true) }
        val curseForgeProjects = curseForgeMods
            .mapNotNull { it.projectId.toIntOrNull() }
            .distinct()
            .let { ids ->
                if (ids.isEmpty()) emptyMap() else CurseForgeService.getModsInfo(ids).associateBy { it.id }
            }
        val curseForgeFiles = curseForgeMods
            .mapNotNull { it.fileId.toIntOrNull() }
            .distinct()
            .let { ids ->
                if (ids.isEmpty()) emptyMap() else CurseForgeService.getModFilesInfo(ids).associateBy { it.id }
            }
        mods.forEach { mod -> validateExtraMod(mod, curseForgeProjects, curseForgeFiles) }
    }

    private suspend fun validateExtraMod(
        mod: Mod,
        curseForgeProjects: Map<Int, CurseForgeModInfo> = emptyMap(),
        curseForgeFiles: Map<Int, CurseForgeFile> = emptyMap()
    ) {
        if (mod.projectId.isBlank()) throw RequestError("Mod projectId不能为空")
        if (mod.fileId.isBlank()) throw RequestError("Mod fileId不能为空")
        if (mod.slug.isBlank()) throw RequestError("Mod slug不能为空")

        when (mod.platform.lowercase()) {
            "mr" -> {
                if (mod.downloadUrls.none { it.isValidDownloadUrl() }) {
                    throw RequestError("Modrinth Mod ${mod.slug} 缺少有效下载链接")
                }
                val projects = ModrinthService.getMultipleProjects(listOf(mod.projectId))
                if (projects.isEmpty()) {
                    throw RequestError("Modrinth不存在此项目: ${mod.projectId}")
                }
                val version = runCatching {
                    ModrinthService.mrreq("version/${mod.fileId}").body<ModrinthVersionInfo>()
                }.getOrElse {
                    throw RequestError("Modrinth不存在此版本: ${mod.fileId}")
                }
                if (version.projectId != mod.projectId) {
                    throw RequestError("Modrinth版本${mod.fileId}不属于项目${mod.projectId}")
                }
            }

            "cf" -> {
                val projectId = mod.projectId.toIntOrNull()
                    ?: throw RequestError("CurseForge projectId无效: ${mod.projectId}")
                val fileId = mod.fileId.toIntOrNull()
                    ?: throw RequestError("CurseForge fileId无效: ${mod.fileId}")
                val project = curseForgeProjects[projectId]
                    ?: throw RequestError("CurseForge不存在此项目: ${mod.projectId}")
                val fileInfo = curseForgeFiles[fileId]
                    ?: throw RequestError("CurseForge不存在此文件: ${mod.fileId}")
                if (fileInfo.realDownloadUrl.isBlank() || !fileInfo.realDownloadUrl.isValidDownloadUrl()) {
                    throw RequestError("CurseForge Mod ${project.slug} 缺少有效下载链接")
                }
            }

            "github" -> {
                if (mod.hash.isBlank()) {
                    throw RequestError("GitHub Mod ${mod.slug} SHA1不能为空")
                }
                if (mod.downloadUrls.none { it.isValidDownloadUrl() }) {
                    throw RequestError("GitHub Mod ${mod.slug} 缺少有效下载链接")
                }
            }

            else -> throw RequestError("不支持的Mod平台: ${mod.platform}")
        }
    }

    suspend fun HostContext.addExtraMods(mods: List<Mod>) {
        if (mods.isEmpty()) throw RequestError("附加Mod列表不能为空")
        val duplicateRequestIds = mods.groupBy(::projectIdentity)
            .filterKeys { it.isNotBlank() }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateRequestIds.isNotEmpty()) {
            throw RequestError("请求中包含重复Mod项目: ${duplicateRequestIds.joinToString()}")
        }
        val duplicateRequestSlugs = duplicateRequestSlugLabels(mods)
        if (duplicateRequestSlugs.isNotEmpty()) {
            throw RequestError("请求中包含重复Mod名: ${duplicateRequestSlugs.joinToString()}")
        }

        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(host.modpackId) ?: throw RequestError("无此整合包")
        val baseVersion = modpack.getVersion(host.packVer) ?: throw RequestError("无此整合包版本: ${host.packVer}")
        val activeBaseMods = host.effectiveBaseMods(baseVersion)
        val existingProjectIds = (host.extraMods + activeBaseMods).map(::projectIdentity).toSet()
        val duplicateExistingIds = mods.map(::projectIdentity).filter { it in existingProjectIds }
        if (duplicateExistingIds.isNotEmpty()) {
            val duplicateExistingSlugs = mods
                .filter { projectIdentity(it) in duplicateExistingIds }
                .map { it.slug.trim().ifBlank { projectIdentity(it) } }
                .distinct()
            throw RequestError("房间已有这些mod: ${duplicateExistingSlugs.joinToString()}")
        }
        val duplicateExistingSlugs = duplicateExistingSlugLabels(mods, host.extraMods + activeBaseMods)
        if (duplicateExistingSlugs.isNotEmpty()) {
            throw RequestError("房间已有这些同名mod: ${duplicateExistingSlugs.joinToString()}")
        }

        val mailId = MailService.sendSystemMail(
            player._id,
            "附加Mod添加中",
            "开始为房间${host.name}添加${mods.size}个附加Mod"
        )._id
        enqueueAddExtraMods(host._id, host.name, host.modpackId, host.packVer, mods, mailId)
    }

    suspend fun HostContext.addDisabledMods(mods: List<Mod>): List<Mod> {
        if (mods.isEmpty()) throw RequestError("禁用Mod列表不能为空")
        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(host.modpackId) ?: throw RequestError("无此整合包")
        val baseVersion = modpack.getVersion(host.packVer) ?: throw RequestError("无此整合包版本: ${host.packVer}")
        val matchedMods = mods.map { requestMod ->
            baseVersion.mods.firstOrNull { sameMod(it, requestMod) }
                ?: throw RequestError("整合包未安装此Mod: ${requestMod.displaySlugOrProject}")
        }.distinctBySameMod()
        val alreadyDisabled = matchedMods.filter { matched ->
            host.disabledMods.any { sameMod(it, matched) }
        }
        if (alreadyDisabled.isNotEmpty()) {
            throw RequestError(
                "这些Mod已被禁用: ${alreadyDisabled.map { it.displaySlugOrProject }.distinct().joinToString()}"
            )
        }
        val updatedMods = (host.disabledMods + matchedMods).distinctBySameMod()
        HostService.dbcl.updateOne(
            eq("_id", host._id),
            set(Host::disabledMods.name, updatedMods)
        )
        host.disabledMods = updatedMods
        deleteMountedDisabledModFiles(host, matchedMods)
        return updatedMods
    }

    private fun enqueueAddExtraMods(
        hostId: ObjectId,
        hostName: String,
        modpackId: ObjectId,
        packVer: String,
        mods: List<Mod>,
        mailId: ObjectId
    ) {
        ServerTaskManager.submit(
            task = Task2.Leaf("为房间添加附加Mod $hostName") { ctx ->
                runCatching {
                    MailService.changeMail(mailId, newContent = "开始校验Mod信息")
                    ctx.emit(LoadProgress.Phase("开始校验Mod信息"))
                    val currentHost = HostQueryService.getById(hostId) ?: throw RequestError("无此房间")
                    val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(modpackId) ?: throw RequestError("无此整合包")
                    val baseVersion = modpack.getVersion(packVer) ?: throw RequestError("无此整合包版本: $packVer")
                    validateExtraMods(mods)
                    mods.forEachIndexed { index, mod ->
                        val message = "已校验 ${index + 1}/${mods.size}: ${mod.slug}"
                        MailService.changeMail(mailId, newContent = message)
                        ctx.emit(
                            LoadProgress.Percent(
                                message,
                                (index + 1).toFloat() / mods.size.coerceAtLeast(1)
                            )
                        )
                    }

                    val activeBaseMods = currentHost.effectiveBaseMods(baseVersion)
                    val existingProjectIds = (currentHost.extraMods + activeBaseMods).map(::projectIdentity).toSet()
                    val duplicateExistingMods = mods.filter { projectIdentity(it) in existingProjectIds }
                    if (duplicateExistingMods.isNotEmpty()) {
                        val duplicateSlugs = duplicateExistingMods
                            .map { it.slug.trim().ifBlank { projectIdentity(it) } }
                            .distinct()
                        throw RequestError("主机已有这些mod: ${duplicateSlugs.joinToString()}")
                    }
                    val duplicateExistingSlugMods =
                        duplicateExistingSlugLabels(mods, currentHost.extraMods + activeBaseMods)
                    if (duplicateExistingSlugMods.isNotEmpty()) {
                        throw RequestError("主机已有这些同slug mod: ${duplicateExistingSlugMods.joinToString()}")
                    }

                    ModService.downloadModsTask2(mods).runInline(
                        Task2Context { progress ->
                            val percentText = progress.fraction?.let { fraction ->
                                " ${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"
                            }.orEmpty()
                            MailService.changeMail(mailId, newContent = "${progress.message}$percentText")
                            ctx.emit(progress)
                        }
                    )

                    val latestHost = HostQueryService.getById(hostId) ?: throw RequestError("无此房间")
                    val latestModpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(latestHost.modpackId) ?: throw RequestError("无此整合包")
                    val latestBaseVersion = latestModpack.getVersion(latestHost.packVer)
                        ?: throw RequestError("无此整合包版本: ${latestHost.packVer}")
                    val latestActiveBaseMods = latestHost.effectiveBaseMods(latestBaseVersion)
                    val latestExistingProjectIds =
                        (latestHost.extraMods + latestActiveBaseMods).map(::projectIdentity).toSet()
                    val latestExistingSlugs = (latestHost.extraMods + latestActiveBaseMods)
                        .map(::slugIdentity)
                        .filter { it.isNotBlank() }
                        .toSet()
                    val modsToAppend = mods.filterNot {
                        projectIdentity(it) in latestExistingProjectIds ||
                                (slugIdentity(it).isNotBlank() && slugIdentity(it) in latestExistingSlugs)
                    }
                    if (modsToAppend.isEmpty()) {
                        throw RequestError("这些mod在任务执行期间已添加到主机")
                    }

                    val updatedMods = (latestHost.extraMods + modsToAppend)
                        .distinctBy(::modIdentity)
                        .toList()
                    HostService.dbcl.updateOne(
                        eq("_id", hostId),
                        set(Host::extraMods.name, updatedMods)
                    )
                    val skippedCount = mods.size - modsToAppend.size
                    MailService.changeMail(
                        mailId,
                        newTitle = "主机附加Mod添加完成",
                        newContent = buildString {
                            append("已为房间")
                            append(hostName)
                            append("添加")
                            append(modsToAppend.size)
                            append("个附加Mod")
                            if (skippedCount > 0) {
                                append("，另有")
                                append(skippedCount)
                                append("个mod因执行期间已存在而跳过")
                            }
                        }
                    )
                }.onFailure { error ->
                    lgr.error { "添加主机附加Mod失败 host=$hostId\n$error" }
                    MailService.changeMail(
                        mailId,
                        newTitle = "主机附加Mod添加失败",
                        newContent = "无法为房间${hostName}添加附加Mod: ${error.message ?: error}"
                    )
                    throw error
                }
            },
            dedupeKey = addExtraModsTaskKey(hostId, mods)
        )
    }

    suspend fun HostContext.deleteExtraMods(projectIds: List<String>): List<Mod> {
        val normalizedProjectIds = projectIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (normalizedProjectIds.isEmpty()) throw RequestError("projectIds不能为空")

        val removedMods = host.extraMods
            .filter { projectIdentity(it) in normalizedProjectIds }
            .toList()
        val foundProjectIds = removedMods.map(::projectIdentity).toSet()
        val missingProjectIds = normalizedProjectIds - foundProjectIds
        if (missingProjectIds.isNotEmpty()) {
            throw RequestError("这些Mod不在附加Mod列表中: ${missingProjectIds.joinToString()}")
        }
        val updatedMods = host.extraMods
            .filterNot { projectIdentity(it) in normalizedProjectIds }
            .toList()
        HostService.dbcl.updateOne(
            eq("_id", host._id),
            set(Host::extraMods.name, updatedMods)
        )
        removedMods.forEach { mod ->
            mod.fileNames.forEach { fileName ->
                val hostModPath = host.dir.resolve("mods").resolve(fileName).toPath()
                runCatching { Files.deleteIfExists(hostModPath) }
                    .onSuccess { deleted ->
                        if (deleted) {
                            lgr.info { "Host ${host._id} 删除附加Mod文件: ${hostModPath.fileName}" }
                        }
                    }
                    .onFailure { err ->
                        lgr.warn { "Host ${host._id} 删除附加Mod文件失败 ${hostModPath.fileName}: ${err.message}" }
                    }
            }
        }
        host.extraMods = updatedMods
        return updatedMods
    }

    suspend fun HostContext.updateExtraModSide(dto: Host.ExtraModSideDto): List<Mod> {
        val projectId = dto.projectId.trim()
        if (projectId.isBlank()) throw RequestError("projectId不能为空")
        if (dto.side == Mod.Side.UNKNOWN) throw RequestError("至少保留客户端或服务端之一")
        val target = host.extraMods.firstOrNull { projectIdentity(it) == projectId }
            ?: throw RequestError("这个Mod不在附加Mod列表中: $projectId")
        if (target.side == dto.side) return host.extraMods

        val updatedMods = host.extraMods.map { mod ->
            if (projectIdentity(mod) == projectId) mod.copy(side = dto.side) else mod
        }
        HostService.dbcl.updateOne(
            eq("_id", host._id),
            set(Host::extraMods.name, updatedMods)
        )
        lgr.info { "Host ${host._id} 附加Mod ${modLabel(target)} 运行侧 ${target.side} -> ${dto.side}" }
        if (dto.side == Mod.Side.CLIENT) {
            target.fileNames.forEach { fileName ->
                val hostModPath = host.dir.resolve("mods").resolve(fileName).toPath()
                runCatching { Files.deleteIfExists(hostModPath) }
                    .onFailure { err ->
                        lgr.warn(err) { "Host ${host._id} 删除仅客户端附加Mod文件失败 ${hostModPath.fileName}" }
                    }
            }
        }
        host.extraMods = updatedMods
        return updatedMods
    }

    suspend fun HostContext.deleteDisabledMods(mods: List<Mod>): List<Mod> {
        if (mods.isEmpty()) throw RequestError("禁用Mod列表不能为空")
        val modsToReEnable = mods.filter { requestMod ->
            host.disabledMods.any { sameMod(it, requestMod) }
        }.distinctBySameMod()
        if (modsToReEnable.isEmpty()) {
            throw RequestError("这些Mod未被禁用")
        }
        val conflictWithExtra = modsToReEnable.filter { reenableMod ->
            host.extraMods.any { sameMod(it, reenableMod) }
        }
        if (conflictWithExtra.isNotEmpty()) {
            throw RequestError(
                "这些Mod已在附加Mod中存在，请先移除附加Mod再重新启用: ${
                    conflictWithExtra.map { it.displaySlugOrProject }.distinct().joinToString()
                }"
            )
        }
        val updatedMods = host.disabledMods
            .filterNot { disabledMod -> modsToReEnable.any { sameMod(it, disabledMod) } }
            .toList()
        HostService.dbcl.updateOne(
            eq("_id", host._id),
            set(Host::disabledMods.name, updatedMods)
        )
        host.disabledMods = updatedMods
        return updatedMods
    }

    private fun deleteMountedDisabledModFiles(host: Host, disabledMods: List<Mod>) {
        if (disabledMods.isEmpty()) return
        disabledMods.forEach { mod ->
            mod.fileNames.forEach { fileName ->
                val hostModFile = host.dir.resolve("mods").resolve(fileName).toPath()
                runCatching { Files.deleteIfExists(hostModFile) }
                    .onSuccess { deleted ->
                        if (deleted) {
                            lgr.info { "Host ${host._id} 删除已停用Mod占位文件: ${hostModFile.fileName}" }
                        }
                    }
                    .onFailure { err ->
                        lgr.warn { "Host ${host._id} 删除已停用Mod占位文件失败 ${hostModFile.fileName}: ${err.message}" }
                    }
            }
        }
    }
}
