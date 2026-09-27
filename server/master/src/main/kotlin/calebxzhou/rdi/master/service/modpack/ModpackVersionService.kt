package calebxzhou.rdi.master.service.modpack

import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.service.ModpackModProcessor
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.runInline
import calebxzhou.rdi.common.service.validate
import calebxzhou.rdi.common.DL_MOD_DIR
import calebxzhou.rdi.master.DL_MODS_CLIENT_DIR
import calebxzhou.rdi.master.service.ClientModCacheService
import calebxzhou.rdi.master.service.ModpackContext
import calebxzhou.rdi.master.service.ModpackService
import calebxzhou.rdi.master.service.PlayerService
import calebxzhou.rdi.master.service.host.HostQueryService
import calebxzhou.rdi.master.service.host.HostControlService.status
import calebxzau.rdi.server.service.baseworld.BaseWorldReferenceCoordinator
import calebxzau.rdi.server.service.baseworld.BaseWorldService
import calebxzhou.rdi.master.service.host.dir
import calebxzhou.rdi.master.service.dir
import calebxzhou.rdi.master.service.zip
import calebxzhou.rdi.master.service.zstdPack
import calebxzhou.rdi.master.service.clientZip
import calebxzhou.rdi.master.service.clientZstdPack
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import org.bson.Document
import kotlinx.coroutines.CancellationException
import java.io.File

/** Authorization, metadata and embedded-version mutations for Legacy modpacks. */
object ModpackVersionService {
    private const val MAX_ALLOWED_UPLOADERS = 100
    private val dbcl get() = ModpackServiceKernel.dbcl
    fun ModpackContext.requireAuthor(): ModpackContext {
        if (modpack.authorId != player._id && !player.isDav) throw RequestError("不是你的整合包")
        return this
    }

    fun ModpackContext.canManageVersion(): Boolean {
        val selectedVersion = versionNull ?: return false
        return player.isDav ||
            modpack.authorId == player._id ||
            selectedVersion.uploaderId == player._id
    }

    fun ModpackContext.requireCanManageVersion(): ModpackContext {
        if (versionNull == null) throw RequestError("无此版本")
        if (!canManageVersion()) throw RequestError("不是你的整合包版本")
        return this
    }

    fun ModpackContext.canUploadVersion(): Boolean {
        val allowedUploaderIds = modpack.allowUploaderIds
        return player.isDav ||
            modpack.authorId == player._id ||
            allowedUploaderIds == null ||
            player._id in allowedUploaderIds
    }

    fun ModpackContext.requireCanUploadVersion(): ModpackContext {
        if (!canUploadVersion()) {
            throw RequestError("没有上传新版本的权限")
        }
        return this
    }

    suspend fun ModpackContext.getUploaderPolicy(): Modpack.UploaderPolicyVo {
        val ids = modpack.allowUploaderIds
        if (ids.isNullOrEmpty()) {
            return Modpack.UploaderPolicyVo(ids, emptyList())
        }
        val accountsById = PlayerService.getByIds(ids).associateBy { it._id }
        return Modpack.UploaderPolicyVo(
            allowUploaderIds = ids,
            uploaders = ids.mapNotNull { accountsById[it]?.dto },
        )
    }

    suspend fun ModpackContext.resolveUploader(payload: Modpack.UploaderResolveDto): RAccount.Dto {
        val identifier = payload.playerNameOrQq.trim()
        if (identifier.isBlank()) throw RequestError("玩家名或QQ不能为空")
        val account = PlayerService.getByQQ(identifier)
            ?: PlayerService.getByName(identifier)
            ?: throw RequestError("找不到该玩家")
        if (account._id == modpack.authorId) throw RequestError("作者始终可以上传新版本")
        val allowedIds = modpack.allowUploaderIds
        if (allowedIds != null && account._id in allowedIds) {
            throw RequestError("该玩家已在名单中")
        }
        return account.dto
    }

    suspend fun ModpackContext.updateUploaderPolicy(payload: Modpack.UploaderPolicyUpdateDto) {
        val ids = payload.allowUploaderIds
        if (ids != null) {
            if (ids.size > MAX_ALLOWED_UPLOADERS) {
                throw RequestError("最多只能添加${MAX_ALLOWED_UPLOADERS}名玩家")
            }
            if (ids.size != ids.toSet().size) throw RequestError("上传玩家名单中有重复玩家")
            if (modpack.authorId in ids) throw RequestError("作者始终可以上传新版本")
            val accounts = PlayerService.getByIds(ids)
            if (accounts.map { it._id }.toSet() != ids.toSet()) {
                throw RequestError("上传玩家名单中存在不存在的玩家")
            }
        }
        val result = dbcl.updateOne(
            eq(Modpack::_id.name, modpack._id),
            Updates.set(Modpack::allowUploaderIds.name, ids),
        )
        if (result.matchedCount == 0L) throw RequestError("整合包不存在")
    }

    private fun ModRef.normalizedVersionModRef(): ModRef = copy(
        projectId = projectId.trim(),
        fileId = fileId.trim()
    )

    private fun Mod.versionModRef(): ModRef = ModRef(projectId, fileId).normalizedVersionModRef()

    private fun Mod.normalizeVersionMutationMod(): Mod = copy(
        platform = platform.trim().lowercase(),
        projectId = projectId.trim(),
        slug = slug.trim(),
        fileId = fileId.trim(),
        hash = hash.trim(),
        downloadUrls = downloadUrls.map(String::trim).filter(String::isNotBlank)
    )

    private fun Mod.requireVersionMutationMod(): Mod {
        val normalized = normalizeVersionMutationMod()
        if (normalized.projectId.isBlank() || normalized.fileId.isBlank()) {
            throw RequestError("Mod的projectId和fileId不能为空")
        }
        return normalized
    }

    private fun ModRef.requireVersionMutationRef(): ModRef {
        val normalized = normalizedVersionModRef()
        if (normalized.projectId.isBlank() || normalized.fileId.isBlank()) {
            throw RequestError("projectId和fileId不能为空")
        }
        return normalized
    }

    private fun Mod.matchesVersionModRef(ref: ModRef): Boolean = versionModRef() == ref.normalizedVersionModRef()

    private fun MutableList<Mod>.findVersionModIndex(ref: ModRef): Int {
        val normalizedRef = ref.normalizedVersionModRef()
        return indexOfFirst { it.matchesVersionModRef(normalizedRef) }
    }

    private fun MutableList<Mod>.findVersionModIndexOrThrow(ref: ModRef): Int {
        return findVersionModIndex(ref)
            .takeIf { it >= 0 }
            ?: throw RequestError("版本内无此Mod")
    }

    private fun Collection<ModRef>.requireDistinctVersionMutationRefs(fieldName: String): List<ModRef> {
        val normalizedRefs = map { it.requireVersionMutationRef() }
        val duplicates = normalizedRefs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw RequestError("$fieldName 里有重复Mod")
        }
        return normalizedRefs
    }

    private fun Collection<Mod>.requireDistinctVersionMutationMods(fieldName: String): List<Mod> {
        val normalizedMods = map { it.requireVersionMutationMod() }
        val duplicates = normalizedMods.groupingBy { it.versionModRef() }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw RequestError("$fieldName 里有重复Mod")
        }
        return normalizedMods
    }

    private fun Modpack.Version.ensureVersionModsEditable() {
        if (status != Modpack.Status.OK) {
            throw RequestError("版本${name}当前状态不能修改Mod列表")
        }
    }

    private data class ArtifactKey(
        val platform: String,
        val fileName: String,
        val hash: String,
    )

    internal data class VersionMutationExpectation(
        val versionName: String,
        val status: Modpack.Status,
        val oldMods: List<Mod>,
    )

    internal fun Modpack.Version.versionMutationExpectation() = VersionMutationExpectation(
        versionName = name,
        status = status,
        oldMods = mods.toList(),
    )

    private fun Mod.artifactKey(): ArtifactKey {
        val normalized = copy(
            platform = platform.trim().lowercase(),
            slug = slug.trim(),
            hash = hash.trim().lowercase()
        )
        return ArtifactKey(
            platform = normalized.platform,
            fileName = normalized.fileName,
            hash = normalized.hash
        )
    }

    private fun Mod.isServerCapable() = side == Mod.Side.SERVER || side == Mod.Side.BOTH

    private fun Mod.displayName() = slug.trim().ifBlank { projectId.trim() }

    private fun List<Mod>.copyVersionMods(): MutableList<Mod> = map {
        it.copy(downloadUrls = it.downloadUrls.toList())
    }.toMutableList()

    private suspend fun prepareServerMod(mod: Mod) {
        val platform = mod.platform.trim().lowercase()
        if (platform !in setOf("cf", "mr", "github")) {
            throw RequestError("准备Mod《${mod.displayName()}》失败：不支持的Mod平台$platform")
        }
        if (mod.hash.trim().isBlank()) {
            throw RequestError("准备Mod《${mod.displayName()}》失败：缺少Hash")
        }
        ModpackServiceKernel.testServerModPreparer?.let { preparer ->
            try {
                preparer(mod)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw RequestError(
                    "准备Mod《${mod.displayName()}》失败（${platform}/${mod.fileId}/${mod.hash.trim()}）：${error.message ?: error::class.simpleName}"
                )
            }
        }
        try {
            if (!ModService.isDownloadedModFileValid(mod, DL_MOD_DIR)) {
                (ModpackServiceKernel.testServerModDownloadTaskFactory?.invoke(listOf(mod))
                    ?: ModService.downloadModsTask2(listOf(mod), DL_MOD_DIR))
                    .runInline(Task2Context(emitProgress = {}))
                if (!ModService.isDownloadedModFileValid(mod, DL_MOD_DIR)) {
                    throw IllegalStateException("下载后Hash校验失败")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is RequestError) throw error
            throw RequestError(
                "准备Mod《${mod.displayName()}》失败（${platform}/${mod.fileId}/${mod.hash.trim()}）：${error.message ?: error::class.simpleName}"
            )
        }
    }

    private suspend fun prepareCandidateArtifacts(oldMods: List<Mod>, candidateMods: List<Mod>) {
        val oldServerArtifacts = oldMods.filter { it.isServerCapable() }.map { it.artifactKey() }.toSet()
        candidateMods.filter { it.isServerCapable() }
            .distinctBy { it.artifactKey() }
            .forEach { mod ->
                if (mod.artifactKey() !in oldServerArtifacts) {
                    prepareServerMod(mod)
                }
            }

        val oldClientArtifacts = oldMods.filter { it.side == Mod.Side.CLIENT }.map { it.artifactKey() }.toSet()
        val clientMods = candidateMods.filter { it.side == Mod.Side.CLIENT }
            .filter { it.artifactKey() !in oldClientArtifacts }
            .distinctBy { it.artifactKey() }
        if (clientMods.isNotEmpty()) {
            ClientModCacheService(DL_MODS_CLIENT_DIR) { mods, targetDir ->
                ModpackServiceKernel.testClientModDownloadTaskFactory?.invoke(mods)
                    ?: ModService.downloadModsTask2(mods, targetDir)
            }.downloadTask(clientMods)
                .runInline(Task2Context(emitProgress = {}))
        }
    }

    private suspend fun ModpackContext.mutateVersionMods(mutator: (MutableList<Mod>) -> Unit) =
        ModpackVersionMutationLock.withLock(modpack._id, version.name) {
            val freshPack = ModpackQueryService.getById(modpack._id) ?: throw RequestError("整合包不存在")
            val freshVersion = freshPack.versions.firstOrNull { it.name == version.name }
                ?: throw RequestError("版本${version.name}不存在")
            freshVersion.ensureVersionModsEditable()
            ModpackContext(player, freshPack, freshVersion).requireCanManageVersion()

            val oldMods = freshVersion.mods.copyVersionMods()
            val updatedMods = oldMods.copyVersionMods()
            mutator(updatedMods)
            val processedMods = ModpackModProcessor.processMods(updatedMods, modpack.modloader).map {
                it.copy(
                    platform = it.platform.trim().lowercase(),
                    projectId = it.projectId.trim(),
                    slug = it.slug.trim(),
                    fileId = it.fileId.trim(),
                    hash = it.hash.trim().lowercase(),
                    downloadUrls = it.downloadUrls.map(String::trim).filter(String::isNotBlank)
                )
            }.sortedBy { it.slug.lowercase() }
            if (processedMods == oldMods.sortedBy { it.slug.lowercase() }) return@withLock

            prepareCandidateArtifacts(oldMods, processedMods)
            val now = System.currentTimeMillis()
            val expected = freshVersion.versionMutationExpectation()
            val result = dbcl.updateOne(
                and(
                    eq(Modpack::_id.name, freshPack._id),
                    elemMatch(
                        Modpack::versions.name,
                        and(
                            eq(Modpack.Version::name.name, expected.versionName),
                            eq(Modpack.Version::status.name, expected.status),
                            eq(Modpack.Version::mods.name, expected.oldMods)
                        )
                    )
                ),
                Updates.combine(
                    Updates.set("${Modpack::versions.name}.$[elem].${Modpack.Version::mods.name}", processedMods),
                    Updates.set("${Modpack::versions.name}.$[elem].${Modpack.Version::time.name}", now)
                ),
                UpdateOptions().arrayFilters(
                    listOf(
                        Document("elem.name", expected.versionName)
                            .append("elem.status", expected.status)
                    )
                )
            )
            if (result.matchedCount == 0L || result.modifiedCount == 0L) {
                throw RequestError("版本已被其他操作修改，请刷新后重试")
            }
        }

    suspend fun ModpackContext.addVersionMod(newMod: Mod) {
        addVersionMods(listOf(newMod))
    }

    suspend fun ModpackContext.addVersionMods(newMods: List<Mod>) {
        if (newMods.isEmpty()) throw RequestError("缺少要添加的Mod")
        val normalizedMods = newMods.requireDistinctVersionMutationMods("新增Mod")
        mutateVersionMods { mods ->
            val existingRefs = mods.map { it.versionModRef() }.toSet()
            normalizedMods.forEach { newMod ->
                if (newMod.versionModRef() in existingRefs) {
                    throw RequestError("版本内已存在Mod ${newMod.displaySlugOrProject}")
                }
            }
            mods += normalizedMods
        }
    }

    suspend fun ModpackContext.replaceVersionMod(projectId: String, fileId: String, newMod: Mod) {
        replaceVersionMods(listOf(ModBatchReplaceItem(projectId, fileId, newMod)))
    }

    suspend fun ModpackContext.replaceVersionMods(items: List<ModBatchReplaceItem>) {
        if (items.isEmpty()) throw RequestError("缺少要修改的Mod")
        val normalizedItems = items.map {
            ModBatchReplaceItem(
                projectId = it.projectId.trim(),
                fileId = it.fileId.trim(),
                mod = it.mod.requireVersionMutationMod()
            )
        }
        normalizedItems.map { ModRef(it.projectId, it.fileId) }.requireDistinctVersionMutationRefs("待修改Mod")
        mutateVersionMods { mods ->
            val targetIndices = normalizedItems.map { item ->
                mods.findVersionModIndexOrThrow(ModRef(item.projectId, item.fileId))
            }
            if (targetIndices.size != targetIndices.toSet().size) {
                throw RequestError("待修改Mod里有重复目标")
            }
            val replacedMods = mods.toMutableList()
            normalizedItems.forEachIndexed { index, item ->
                replacedMods[targetIndices[index]] = item.mod
            }
            val duplicates = replacedMods.groupingBy { it.versionModRef() }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw RequestError("批量修改后版本内存在重复Mod")
            }
            mods.clear()
            mods += replacedMods
        }
    }

    suspend fun ModpackContext.removeVersionMod(projectId: String, fileId: String) {
        removeVersionMods(listOf(ModRef(projectId, fileId)))
    }

    suspend fun ModpackContext.removeVersionMods(refs: List<ModRef>) {
        if (refs.isEmpty()) throw RequestError("缺少要删除的Mod")
        val normalizedRefs = refs.requireDistinctVersionMutationRefs("待删除Mod")
        mutateVersionMods { mods ->
            val targetIndices = normalizedRefs.map { ref -> mods.findVersionModIndexOrThrow(ref) }
                .sortedDescending()
            targetIndices.forEach(mods::removeAt)
        }
    }

    suspend fun ModpackContext.changeOptions(payload: Modpack.OptionsDto) {
        payload.validate()
        val normalizedCategories = payload.categories?.let(Modpack::normalizeCategories) ?: modpack.categories
        val update = Updates.combine(
            Updates.set(Modpack::name.name, payload.name ?: modpack.name),
            Updates.set(Modpack::iconUrl.name, payload.iconUrl),
            Updates.set(Modpack::info.name, payload.info),
            Updates.set(Modpack::sourceUrl.name, payload.sourceUrl),
            Updates.set(Modpack::categories.name, normalizedCategories)
        )
        dbcl.updateOne(eq("_id", modpack._id), update)
    }

    suspend fun ModpackContext.updateVersionBaseWorld(
        payload: Modpack.VersionBaseWorldUpdateDto,
        baseWorldService: BaseWorldService,
    ) {
        ModpackVersionMutationLock.withLock(modpack._id, version.name) {
            val freshPack = ModpackQueryService.getById(modpack._id) ?: throw RequestError("整合包不存在")
            val freshVersion = freshPack.versions.firstOrNull { it.name == version.name }
                ?: throw RequestError("版本${version.name}不存在")
            ModpackContext(player, freshPack, freshVersion).requireCanManageVersion()
            val binding = payload.baseWorld
            if (binding != null) {
                BaseWorldReferenceCoordinator.withLock(binding.id) {
                    baseWorldService.requireReadyForHost(binding.id).getOrThrow()
                    updateVersionBaseWorldBinding(freshPack, freshVersion, binding)
                }
            } else {
                updateVersionBaseWorldBinding(freshPack, freshVersion, null)
            }
        }
    }

    private suspend fun updateVersionBaseWorldBinding(
        freshPack: Modpack,
        freshVersion: Modpack.Version,
        binding: Modpack.Version.BaseWorldBinding?,
    ) {
        val path = "${Modpack::versions.name}.$[elem].${Modpack.Version::baseWorld.name}"
        val update = if (binding == null) Updates.unset(path) else Updates.set(path, binding)
        val result = dbcl.updateOne(
            and(
                eq(Modpack::_id.name, freshPack._id),
                elemMatch(
                    Modpack::versions.name,
                    and(
                        eq(Modpack.Version::name.name, freshVersion.name),
                        eq(Modpack.Version::status.name, freshVersion.status),
                    )
                )
            ),
            update,
            UpdateOptions().arrayFilters(
                listOf(
                    Document("elem.name", freshVersion.name)
                        .append("elem.status", freshVersion.status)
                )
            )
        )
        if (result.matchedCount == 0L) throw RequestError("版本已被其他操作修改，请刷新后重试")
    }

    internal suspend fun Modpack.Version.hostsUsing(): List<Host> =
        HostQueryService.findByModpackVersion(modpackId, name).filter { it.status != HostStatus.STOPPED }

    internal suspend fun Modpack.hostsUsing(): List<Host> =
        HostQueryService.findByModpack(_id).filter { it.status != HostStatus.STOPPED }

    suspend fun ModpackContext.deleteVersion() {
        val requestedName = versionNull?.name ?: throw RequestError("无此版本")
        ModpackVersionMutationLock.withLock(modpack._id, requestedName) {
            val freshPack = ModpackQueryService.getById(modpack._id) ?: throw RequestError("整合包不存在")
            val freshVersion = freshPack.versions.firstOrNull { it.name == requestedName }
                ?: throw RequestError("无此版本")
            ModpackContext(player, freshPack, freshVersion).requireCanManageVersion()
            if (freshVersion.status == Modpack.Status.WAIT ||
                freshVersion.status == Modpack.Status.BUILDING ||
                ModpackBuildService.hasActiveVersionBuildTask(freshPack._id, freshVersion.name)
            ) {
                throw RequestError("版本${freshVersion.name}正在构建中，暂时不能删除")
            }
            val hostsUsing = HostQueryService.findByModpackVersion(freshPack._id, freshVersion.name)
            if (hostsUsing.isNotEmpty()) {
                throw RequestError("以下房间正在使用此版本整合包，无法删除：${hostsUsing.map { it.name }}")
            }
            val deleteResult = dbcl.updateOne(
                and(
                    eq(Modpack::_id.name, freshPack._id),
                    elemMatch(
                        Modpack::versions.name,
                        and(
                            eq(Modpack.Version::name.name, freshVersion.name),
                            eq(Modpack.Version::status.name, freshVersion.status),
                            eq(Modpack.Version::mods.name, freshVersion.mods)
                        )
                    )
                ),
                Updates.pull(
                    Modpack::versions.name,
                    and(
                        eq(Modpack.Version::name.name, freshVersion.name),
                        eq(Modpack.Version::status.name, freshVersion.status),
                        eq(Modpack.Version::mods.name, freshVersion.mods)
                    )
                )
            )
            if (deleteResult.matchedCount == 0L || deleteResult.modifiedCount == 0L) {
                throw RequestError("版本已被其他操作修改，请刷新后重试")
            }
            freshVersion.zip.delete()
            freshVersion.zstdPack.delete()
            freshVersion.clientZip.delete()
            freshVersion.clientZstdPack.delete()
            ModpackBuildService.cleanupVersionBuildDirs(freshVersion)
        }
    }

    suspend fun ModpackContext.deleteModpack() {
        val hostsUsing = modpack.hostsUsing()
        if (hostsUsing.isNotEmpty()) throw RequestError("以下主机用了此整合包，且正在运行，无法删除：${hostsUsing.map { it.name }}")
        modpack.dir.deleteRecursivelyNoSymlink()
        dbcl.deleteOne(eq("_id", modpack._id))
    }


}
