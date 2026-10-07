package calebxzau.rdi.server.service.hostworldimport

import calebxzau.rdi.anvilrw.remap.LevelMetadata
import calebxzau.rdi.anvilrw.remap.NbtMetadataReader
import calebxzau.rdi.anvilrw.remap.RemapLimits
import calebxzau.rdi.anvilrw.remap.SaveSyncChunks
import calebxzau.rdi.common.model.HostWorldImportCreateDto
import calebxzau.rdi.common.model.HostWorldImportMemberWarning
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckVo
import calebxzau.rdi.common.model.HostWorldImportSessionVo
import calebxzau.rdi.server.service.upload.ChunkedUploadService
import calebxzau.rdi.server.service.worldarchive.WorldArchiveExtractor
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2CancelledException
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.master.service.MailService
import calebxzhou.rdi.master.service.ServerTaskManager
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What an administrator says about an import that needed manual recovery. */
@Serializable
data class HostWorldImportResolveDto(
    val resolution: ImportResolution,
    val note: String = "",
    val allowEmptyWorld: Boolean = false,
)

/** What the resolve route did not do, for the administrator to handle by hand. */
@Serializable
data class HostWorldImportResolveVo(
    val notDone: List<String>,
    val leftoverPaths: List<String>,
)

/**
 * Imports a singleplayer save, already remapped and filtered by the client, into a stopped v2 host
 * (plan §10). The archive is uploaded in parts, checked again from its own files, and swapped in with
 * [HostWorldSwap], whose journal makes every step recoverable after a crash.
 */
class HostWorldImportService(
    private val store: HostWorldImportStore = HostWorldImportGuard.store,
    private val hosts: ImportHostAccess = DefaultImportHostAccess,
    private val registry: ImportExecutionRegistry = ImportExecutionRegistry(),
    private val swap: HostWorldSwap = HostWorldSwap(),
    private val extractor: WorldArchiveExtractor = WorldArchiveExtractor(MAX_EXTRACTED_SIZE),
    private val taskSubmitter: (Task2, String) -> String = { task, dedupeKey ->
        ServerTaskManager.submit(task, dedupeKey, autoStart = false)
    },
    private val taskStarter: (String) -> Unit = ServerTaskManager::start,
    private val notifier: suspend (ObjectId, String, String) -> Unit = { receiver, title, content ->
        MailService.sendSystemMail(receiver, title, content)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val recoveryInterval: Duration = 5.minutes,
) {
    private val chunkedUploadService = ChunkedUploadService()
    private val importLocks = ConcurrentHashMap<ObjectId, Mutex>()
    private val processingPermits = Semaphore(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recoveryJob: Job? = null

    suspend fun precheck(host: Host, requester: ObjectId, dto: HostWorldImportPrecheckDto): HostWorldImportPrecheckVo =
        withContext(Dispatchers.IO) {
            val warnings = checkImportable(host, requester, dto.mcVersionName, dto.loader, dto.memberPlayerIds)
            HostWorldImportPrecheckVo(warnings, host.gameRules.size)
        }

    suspend fun create(host: Host, requester: ObjectId, dto: HostWorldImportCreateDto): HostWorldImportSessionVo =
        withImportLock(host._id) {
            val warnings = checkImportable(host, requester, dto.mcVersionName, dto.loader, dto.memberPlayerIds)
            store.create(host._id, requester, dto.size, dto.sha1, dto.memberPlayerIds.distinct(), warnings).toVo()
        }

    suspend fun status(hostId: ObjectId, importId: UUID, requester: ObjectId): HostWorldImportSessionVo =
        withContext(Dispatchers.IO) { ownedSession(hostId, importId, requester).toVo() }

    suspend fun uploadPart(
        hostId: ObjectId,
        importId: UUID,
        requester: ObjectId,
        index: Int,
        declaredLength: Long?,
        sha1: String,
        source: ByteReadChannel,
    ) {
        val ticket = withImportLock(hostId) {
            store.preparePart(ownedSession(hostId, importId, requester), index, declaredLength, sha1)
        } ?: return
        withContext(Dispatchers.IO) {
            val temporary = store.createPartTemporary()
            try {
                chunkedUploadService.receive(source, temporary, ticket.expectedLength.toLong(), ticket.sha1)
                withImportLock(hostId) {
                    ownedSession(hostId, importId, requester)
                    store.commitPart(hostId, ticket, temporary)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    /** Queues processing once every part is uploaded. Repeated calls return the current state. */
    suspend fun complete(hostId: ObjectId, importId: UUID, requester: ObjectId): HostWorldImportSessionVo =
        withImportLock(hostId) {
            val session = ownedSession(hostId, importId, requester)
            if (session.journal.status != ImportJournalStatus.Uploading) return@withImportLock session.toVo()
            val claim = registry.tryClaim(importId) ?: throw RequestError("该存档正在处理")
            val queued = try {
                store.markQueued(session)
            } catch (error: Throwable) {
                claim.release()
                throw error
            }
            try {
                enqueue(hostId, importId, claim)
            } catch (error: Throwable) {
                claim.release()
                withContext(NonCancellable) { store.restoreUploading(queued) }
                throw error
            }
            queued.toVo()
        }

    suspend fun cancel(hostId: ObjectId, importId: UUID, requester: ObjectId) = withImportLock(hostId) {
        store.cancel(ownedSession(hostId, importId, requester))
    }

    /** Closes an import that needs manual recovery, after an administrator fixed the files (§10.5). */
    suspend fun resolve(hostId: ObjectId, importId: UUID, adminName: String, dto: HostWorldImportResolveDto): HostWorldImportResolveVo =
        withContext(Dispatchers.IO) {
            val session = store.find(hostId, importId) ?: throw RequestError("存档导入任务不存在")
            val host = hosts.host(hostId) ?: throw RequestError("无此房间")
            val claim = registry.tryClaim(importId) ?: throw RequestError("该导入任务正在处理")
            try {
                val paths = WorldSwapPaths.forHost(hosts.hostDir(host), importId)
                val journal = withContext(NonCancellable) {
                    hosts.withLifecycleLock(hostId) {
                        swap.resolve(claim, paths, store.journalStore(session), dto.resolution, adminName, clock(), dto.allowEmptyWorld).getOrThrow()
                    }
                }
                logger.warn { "管理员${adminName}处理了存档导入${importId}: ${dto.resolution} ${dto.note}" }
                store.retainFinished(session)
                HostWorldImportResolveVo(
                    notDone = buildList {
                        if (!journal.gameRulesCleared) add("房间游戏规则未清除")
                        if (!journal.membersAdded) add("存档中的玩家未加入房间")
                    },
                    leftoverPaths = listOf(paths.stage, paths.old).filter { exists(it) }.map { it.toString() },
                )
            } finally {
                claim.release()
            }
        }

    /** Recovers every session now, then again every [recoveryInterval]. */
    fun startRecovery(): Job = synchronized(this) {
        recoveryJob ?: serviceScope.launch {
            while (isActive) {
                try {
                    recoverAll()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.error(error) { "存档导入恢复失败" }
                }
                delay(recoveryInterval)
            }
        }.also { recoveryJob = it }
    }

    suspend fun shutdown() {
        recoveryJob?.cancelAndJoin()
        serviceScope.coroutineContext[Job]?.cancelAndJoin()
    }

    /** One recovery pass (plan §10.5). Claimed sessions are queued or running and are skipped. */
    internal suspend fun recoverAll() = withContext(Dispatchers.IO) {
        store.expire(registry::isClaimed)
        for (session in store.allSessions()) {
            val claim = registry.tryClaim(session.id) ?: continue
            var handedOff = false
            try {
                handedOff = recoverOne(session, claim)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.error(error) { "恢复存档导入任务失败: ${session.dir}" }
            } finally {
                if (!handedOff) claim.release()
            }
        }
    }

    /** Returns true when the claim was handed to a newly enqueued task. */
    private suspend fun recoverOne(session: ImportSession, claim: ImportExecutionRegistry.ImportClaim): Boolean {
        if (session.journal.status == ImportJournalStatus.Uploading) return false
        val host = hosts.host(session.hostId)
        if (host == null) {
            logger.warn { "存档导入任务所属的房间不存在: ${session.dir}" }
            return false
        }
        val paths = WorldSwapPaths.forHost(hosts.hostDir(host), session.id)
        val outcome = withContext(NonCancellable) {
            hosts.withLifecycleLock(session.hostId) { swap.recover(claim, paths, store.journalStore(session)).getOrThrow() }
        }
        when (outcome) {
            HostWorldSwap.RecoveryOutcome.NotifyOnly,
            HostWorldSwap.RecoveryOutcome.TerminalCleaned -> notifyIfNeeded(session)
            HostWorldSwap.RecoveryOutcome.NotStarted -> Unit
            HostWorldSwap.RecoveryOutcome.Requeue -> {
                store.update(session) { it.copy(journal = it.journal.copy(status = ImportJournalStatus.Queued)) }
                enqueue(session.hostId, session.id, claim)
                return true
            }
            HostWorldSwap.RecoveryOutcome.PostCommitPending -> finishCommitted(session, paths)
            is HostWorldSwap.RecoveryOutcome.Continued -> afterSwap(session, paths, outcome.outcome)
        }
        return false
    }

    private fun enqueue(hostId: ObjectId, importId: UUID, claim: ImportExecutionRegistry.ImportClaim) {
        val runId = taskSubmitter(
            Task2.Leaf("导入单人存档") { context ->
                try {
                    context.emit(Task2Progress("等待处理存档"))
                    processingPermits.withPermit { process(hostId, importId, claim, context) }
                } finally {
                    claim.release()
                }
            },
            "host-world-import:${importId}",
        )
        taskStarter(runId)
    }

    /** Processes a queued import (§10.4). The caller holds [claim] until this returns. */
    internal suspend fun process(hostId: ObjectId, importId: UUID, claim: ImportExecutionRegistry.ImportClaim, context: Task2Context) =
        withContext(Dispatchers.IO) {
            var session = store.find(hostId, importId) ?: return@withContext
            if (session.journal.status != ImportJournalStatus.Queued) return@withContext
            session = store.update(session) { it.copy(journal = it.journal.copy(status = ImportJournalStatus.Processing)) }
            val host = hosts.host(hostId)
            if (host == null) {
                failBeforeSwap(session, null, RequestError("无此房间"))
                return@withContext
            }
            val paths = WorldSwapPaths.forHost(hosts.hostDir(host), importId)
            var swapStarted = false
            try {
                context.emit(Task2Progress("正在合并存档分片"))
                chunkedUploadService.assemble(store.parts(session), session.metadata.size, session.metadata.sha1, session.archive)
                context.ensureActive()
                context.emit(Task2Progress("正在解压存档"))
                if (exists(paths.stage)) NioWorldSwapFileSystem.deleteRecursively(paths.stage)
                extractor.extractToDir(session.archive, paths.stage.toFile()) { context.ensureActive() }
                context.emit(Task2Progress("正在校验存档"))
                val modpack = hosts.modpack(host.modpackId) ?: throw RequestError("无此整合包")
                verifyStage(paths.stage, modpack)
                context.ensureActive()
                context.emit(Task2Progress("正在替换房间世界"))
                val outcome = withContext(NonCancellable) {
                    hosts.withLifecycleLock(hostId) {
                        val fresh = hosts.host(hostId) ?: throw RequestError("无此房间")
                        requestCheck(fresh.ownerId == session.ownerId, "房间拥有者已变更，存档导入已取消")
                        requestCheck(fresh.realVersion == 2, "仅v2房间支持导入存档")
                        requestCheck(hosts.isStopped(fresh), "房间已启动，存档导入已取消")
                        val freshModpack = hosts.modpack(fresh.modpackId) ?: throw RequestError("无此整合包")
                        verifyLevel(paths.stage, freshModpack)
                        swapStarted = true
                        swap.swap(claim, paths, store.journalStore(session)).getOrThrow()
                    }
                }
                withContext(NonCancellable) { afterSwap(session, paths, outcome) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { if (!swapStarted) requeueAfterCancel(session, paths) }
                throw cancelled
            } catch (cancelled: Task2CancelledException) {
                withContext(NonCancellable) { if (!swapStarted) requeueAfterCancel(session, paths) }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    if (swapStarted) {
                        // A journal write failed (I2): the persisted state stays for recovery to continue.
                        logger.error(error) { "替换房间世界时保存进度失败，等待恢复: ${session.dir}" }
                    } else {
                        failBeforeSwap(session, paths, error)
                    }
                }
                throw error
            }
        }

    private fun requeueAfterCancel(session: ImportSession, paths: WorldSwapPaths) {
        runCatching { if (exists(paths.stage)) NioWorldSwapFileSystem.deleteRecursively(paths.stage) }
            .onFailure { logger.error(it) { "清理存档导入临时目录失败: ${paths.stage}" } }
        runCatching { session.archive.delete() }
        runCatching {
            store.update(session) {
                if (it.journal.status == ImportJournalStatus.Processing && it.journal.swapPhase == SwapPhase.None) {
                    it.copy(journal = it.journal.copy(status = ImportJournalStatus.Queued))
                } else {
                    it
                }
            }
        }.onFailure { logger.error(it) { "恢复存档导入排队状态失败: ${session.dir}" } }
    }

    private suspend fun failBeforeSwap(session: ImportSession, paths: WorldSwapPaths?, error: Throwable) {
        if (error !is RequestError) logger.error(error) { "存档导入失败: ${session.dir}" }
        paths?.let { runCatching { if (exists(it.stage)) NioWorldSwapFileSystem.deleteRecursively(it.stage) } }
            ?.onFailure { logger.error(it) { "清理存档导入临时目录失败: ${paths.stage}" } }
        val message = (error as? RequestError)?.message?.takeIf { it.isNotBlank() } ?: "存档导入失败，请稍后重试"
        val failed = runCatching {
            store.update(session) { it.copy(journal = it.journal.copy(status = ImportJournalStatus.Failed, failureMessage = message, notified = false)) }
        }.onFailure { logger.error(it) { "保存存档导入失败状态失败: ${session.dir}" } }.isSuccess
        if (!failed) return
        runCatching { store.cleanupPayloads(session) }
        runCatching { store.retainFinished(session) }
        notifyIfNeeded(session)
    }

    private suspend fun afterSwap(session: ImportSession, paths: WorldSwapPaths, outcome: HostWorldSwap.SwapOutcome) {
        when (outcome) {
            HostWorldSwap.SwapOutcome.Committed -> finishCommitted(session, paths)
            is HostWorldSwap.SwapOutcome.RolledBack,
            is HostWorldSwap.SwapOutcome.RecoveryRequired -> {
                runCatching { store.cleanupPayloads(session) }
                runCatching { store.retainFinished(session) }
                notifyIfNeeded(session)
            }
        }
    }

    /** Post-commit steps P1–P5. Each records a flag, so a retry skips what is done (§10.5). */
    private suspend fun finishCommitted(session: ImportSession, paths: WorldSwapPaths) {
        var current = store.find(session.hostId, session.id) ?: return
        if (current.journal.swapPhase != SwapPhase.Committed || current.journal.status.isTerminal) return
        if (!current.journal.gameRulesCleared) {
            hosts.withLifecycleLock(current.hostId) { hosts.clearGameRules(current.hostId) }
            current = store.update(current) { it.copy(journal = it.journal.copy(gameRulesCleared = true)) }
        }
        if (!current.journal.sizeUpdated) {
            // v2 hosts keep no recorded world size; nothing to refresh.
            current = store.update(current) { it.copy(journal = it.journal.copy(sizeUpdated = true)) }
        }
        if (!current.journal.membersAdded) {
            val failures = current.metadata.memberPlayerIds.mapNotNull { id ->
                hosts.addMember(current.hostId, ObjectId(id)).exceptionOrNull()?.let { error ->
                    if (error !is RequestError) logger.error(error) { "存档导入后添加成员失败: ${id}" }
                    StoredMemberWarning(id, (error as? RequestError)?.message ?: "添加失败")
                }
            }
            current = store.update(current) { it.copy(memberFailures = failures, journal = it.journal.copy(membersAdded = true)) }
        }
        if (!current.journal.oldWorldDeleted) {
            val deleted = runCatching { if (exists(paths.old)) NioWorldSwapFileSystem.deleteRecursively(paths.old) }
                .onFailure { logger.error(it) { "删除房间旧世界失败，将在恢复时重试: ${paths.old}" } }
                .isSuccess
            if (deleted) current = store.update(current) { it.copy(journal = it.journal.copy(oldWorldDeleted = true)) }
        }
        current = store.update(current) { it.copy(journal = it.journal.copy(status = ImportJournalStatus.Ready, notified = false)) }
        runCatching { store.cleanupPayloads(current) }
        store.retainFinished(current)
        notifyIfNeeded(current)
    }

    private suspend fun notifyIfNeeded(session: ImportSession) {
        try {
            val current = store.find(session.hostId, session.id) ?: return
            val journal = current.journal
            if (journal.notified || !journal.status.isTerminal) return
            val hostName = hosts.host(current.hostId)?.name ?: "已删除的房间"
            if (journal.status == ImportJournalStatus.Ready) {
                val failures = current.metadata.memberFailures
                val names = hosts.playerNames(failures.map { ObjectId(it.playerId) })
                val content = buildString {
                    append("房间《${hostName}》已换成导入的存档，可以启动了。")
                    if (failures.isNotEmpty()) {
                        append("\n以下玩家未能加入房间：")
                        failures.forEach { append("\n${names[ObjectId(it.playerId)] ?: it.playerId}：${it.reason}") }
                    }
                }
                notifier(current.ownerId, "存档导入完成", content)
            } else {
                notifier(current.ownerId, "存档导入失败", "房间《${hostName}》的存档导入失败：${journal.failureMessage ?: "未知错误"}")
            }
            store.update(current) { it.copy(journal = it.journal.copy(notified = true)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error(error) { "发送存档导入结果邮件失败: ${session.dir}" }
        }
    }

    /** Checks shared by precheck and create (§10.3). Returns membership warnings. */
    private suspend fun checkImportable(
        host: Host,
        requester: ObjectId,
        mcVersionName: String,
        loader: ModLoader,
        memberPlayerIds: List<ObjectId>,
    ): List<HostWorldImportMemberWarning> {
        requestCheck(host.ownerId == requester, "只有房主可以导入存档")
        requestCheck(host.realVersion == 2, "仅v2房间支持导入存档")
        requestCheck(hosts.isStopped(host), "请先停止房间")
        store.blockingSession(host._id)?.let { store.checkNotBlocked(it) }
        val modpack = hosts.modpack(host.modpackId) ?: throw RequestError("无此整合包")
        checkLoaderSupported(modpack)
        requestCheck(mcVersionName == modpack.mcVer.mcVer, "存档版本(${mcVersionName})与房间整合包版本(${modpack.mcVer.mcVer})不一致")
        requestCheck(loader == modpack.modloader, "该存档最后一次不是用房间整合包的加载器保存的")
        val ids = memberPlayerIds.distinct()
        requestCheck(ids.size <= MAX_MEMBER_PLAYERS, "存档中的玩家过多，最多${MAX_MEMBER_PLAYERS}名")
        requestCheck(hosts.accountsExist(ids), "部分玩家账号不存在")
        return hosts.previewMemberAdds(host, ids).map { (id, reason) -> HostWorldImportMemberWarning(id, reason) }
    }

    /** The authoritative checks on the extracted save (§10.4 step 3). */
    private fun verifyStage(stage: Path, modpack: Modpack) {
        verifyLevel(stage, modpack)
        val syncFile = stage.resolve(SaveSyncChunks.RELATIVE_PATH)
        val attributes = runCatching { Files.readAttributes(syncFile, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { throw RequestError(SYNC_LIST_INVALID) }
        requestCheck(attributes.isRegularFile && attributes.size() <= RemapLimits.STRICT_NBT_FILE_BYTES, SYNC_LIST_INVALID)
        val chunks = SaveSyncChunks.parse(Files.readAllBytes(syncFile)).getOrElse { throw RequestError(SYNC_LIST_INVALID, it) }
        requestCheck(chunks.isNotEmpty(), SYNC_LIST_INVALID)
    }

    private fun verifyLevel(stage: Path, modpack: Modpack) {
        checkLoaderSupported(modpack)
        val level: LevelMetadata = NbtMetadataReader.readLevelDat(stage.resolve("level.dat"))
            .getOrElse { throw RequestError("存档的level.dat无效", it) }
        val expected = modpack.mcVer.mcVer
        requestCheck(level.versionName == expected, "存档版本(${level.versionName ?: "未知"})与房间整合包版本(${expected})不一致")
        val loaderId = if (modpack.modloader == ModLoader.neoforge) "neoforge" else "forge"
        requestCheck(level.fmlModIds?.contains(loaderId) == true, "该存档最后一次不是用Forge/NeoForge保存的")
        requestCheck(!level.hardcore, "存档仍为极限模式，请使用最新客户端重新导入")
    }

    /** 1.21.1 NeoForge stays gated until its loader evidence is confirmed with a real save (Q5). */
    private fun checkLoaderSupported(modpack: Modpack) {
        requestCheck(modpack.modloader == ModLoader.forge, "目前仅支持mc20forge导入存档")
    }

    private fun ownedSession(hostId: ObjectId, importId: UUID, requester: ObjectId): ImportSession =
        store.get(hostId, importId).also { requestCheck(it.ownerId == requester, "无权操作该存档导入任务") }

    private suspend fun <T> withImportLock(hostId: ObjectId, block: suspend () -> T): T =
        withContext(Dispatchers.IO) { importLocks.computeIfAbsent(hostId) { Mutex() }.withLock { block() } }

    private fun exists(path: Path): Boolean = NioWorldSwapFileSystem.exists(path)

    companion object {
        /** The extracted save may not exceed a host's working directory limit. */
        const val MAX_EXTRACTED_SIZE = 8L * 1024 * 1024 * 1024
        const val MAX_MEMBER_PLAYERS = 32
        private const val SYNC_LIST_INVALID = "存档中的同步区块列表无效"
        private val logger = KotlinLogging.logger {}
    }
}
