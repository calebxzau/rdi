package calebxzau.rdi.server.service.baseworld

import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionCreateDto
import calebxzau.rdi.common.model.BaseWorldUploadSessionVo
import calebxzau.rdi.common.model.BaseWorldUploadStatus
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2CancelledException
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.master.BaseWorldDir
import calebxzhou.rdi.master.infra.postgres.DatabaseProvider
import calebxzhou.rdi.master.service.MailService
import calebxzhou.rdi.master.service.ServerTaskManager
import calebxzhou.rdi.master.service.modpack.ModpackServiceKernel
import calebxzhou.rdi.common.util.toObjectId
import calebxzau.rdi.server.service.upload.ChunkedUploadService
import calebxzau.rdi.server.account.PgAccountRepo
import calebxzhou.rdi.common.util.validateModpackName
import calebxzau.rdi.common.util.uuid7j
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.model.isDav
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.AtomicMoveNotSupportedException
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.mongodb.client.model.Filters.eq

val BaseWorld.dir get() = BaseWorldDir.resolve(id.toString())
class BaseWorldService(
    private val database: DatabaseProvider,
    private val repository: PgBaseWorldRepo,
    private val uploads: BaseWorldUploadStore = BaseWorldUploadStore(),
    private val worldDestination: (BaseWorld) -> java.io.File = { it.dir },
    private val taskSubmitter: (Task2, String) -> String = { task, dedupeKey ->
        ServerTaskManager.submit(task, dedupeKey, autoStart = false)
    },
    private val taskStarter: (String) -> Unit = ServerTaskManager::start,
    private val notifier: suspend (UUID, String, String) -> Unit = { receiver, title, content ->
        MailService.sendSystemMail(receiver.toObjectId().getOrThrow(), title, content)
    },
    private val taskCanceller: suspend (String) -> Unit = { runId -> ServerTaskManager.cancelAndJoin(runId) },
    private val accounts: PgAccountRepo = PgAccountRepo(),
    validationConcurrency: Int = 2,
    internal val restoreQueuedForRecovery: suspend (UUID, UUID, UUID) -> Unit = { ownerId, worldId, uploadId ->
        uploads.restoreQueued(ownerId, worldId, uploadId)
    },
    internal val afterMarkProcessing: suspend (UUID) -> Unit = {},
    internal val movePreparedWorld: (java.nio.file.Path, java.nio.file.Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
) {
    init {
        require(validationConcurrency > 0) { "地图模板校验并发数必须为正数" }
    }
    private val chunkedUploadService = ChunkedUploadService()
    private val activePartCounts = ConcurrentHashMap<UUID, Int>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskLock = Any()
    private val validationRunIds = linkedSetOf<String>()
    private val validationPermits = Semaphore(validationConcurrency)
    @Volatile private var acceptingCompletions = true
    private var recoveryJob: Job? = null
    suspend fun create(
        ownerId: UUID,
        name: String,
        levelType: String,
        generatorSettings: String?,
        size: Long,
        generated: Boolean = false,
        owner: RAccount,
    ): Result<BaseWorld> = resultOf {
        name.validateModpackName().getOrElse { error ->
            throw RequestError(error.message?.replace("整合包", "地图模板") ?: "地图模板名称不正确", error)
        }
        if (generated && size != 0L) throw RequestError("生成地图模板大小必须为0")
        if (!generated && size !in 0..BaseWorld.MaxSize) {
            throw RequestError("地图模板大小必须在${BaseWorld.MaxSize.humanFileSize}以内")
        }
        if (generated) {
            val id = uuid7j()
            val pending = BaseWorld(id, ownerId, name, levelType, generatorSettings, size)
            return@resultOf withWorldLock(id) {
                val destination = worldDestination(pending)
                val marker = destination.resolve(".generated")
                var createdDirectory = false
                var createdMarker = false
                var inserted = false
                try {
                    if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination.toPath())) {
                        throw RequestError("地图模板目录已存在")
                    }
                    Files.createDirectories(destination.toPath())
                    createdDirectory = true
                    Files.createFile(marker.toPath())
                    createdMarker = true
                    val created = database.transaction {
                        if (!accounts.lock(ownerId)) throw RequestError("玩家不存在")
                        if(!owner.isDav){
                            if (repository.countByOwner(ownerId) >= 3) throw RequestError("每位玩家最多拥有3个地图模板")
                        }
                        repository.create(id, ownerId, name, levelType, generatorSettings, size)
                    }
                    inserted = true
                    created
                } catch (error: Throwable) {
                    if (createdMarker) runCatching { Files.deleteIfExists(marker.toPath()) }
                    if (createdDirectory) runCatching { destination.deleteRecursivelyNoSymlink() }
                    if (inserted) {
                        runCatching {
                            database.transaction { repository.delete(ownerId, id) }
                        }.onFailure { error.addSuppressed(it) }
                    }
                    throw error
                }
            }
        }
        database.transaction {
            if (!accounts.lock(ownerId)) throw RequestError("玩家不存在")
            if (!owner.isDav && repository.countByOwner(ownerId) >= 3) throw RequestError("每位玩家最多拥有3个地图模板")
            repository.create(ownerId, name, levelType, generatorSettings, size)
        }
    }

    suspend fun findById(ownerId: UUID, id: UUID): Result<BaseWorld?> = resultOf {
        database.transaction {
            repository.findById(ownerId, id)
        }
    }

    suspend fun listByOwner(ownerId: UUID): Result<List<BaseWorld>> = resultOf {
        database.transaction { repository.listByOwner(ownerId) }
    }

    suspend fun listReleased(): Result<List<BaseWorld>> = resultOf {
        val worlds = database.transaction { repository.listAll() }
        buildList {
            worlds.forEach { listed ->
                withWorldLock(listed.id) {
                    if (publishedContent(listed) != null) add(listed)
                }
            }
        }
    }

    /** Returns a template only when its published archive is available. */
    suspend fun requireReadyForHost(worldId: UUID): Result<BaseWorld> = resultOf {
        withWorldLock(worldId) {
            val world = database.transaction { repository.findById(worldId) }
                ?: throw RequestError("地图模板不存在")
            if (publishedContent(world) == null) throw RequestError("地图模板尚未准备好")
            world
        }
    }

    /** Checks readiness and reserves the template until the queued host snapshots it. */
    suspend fun acquireSnapshotLease(worldId: UUID): Result<SnapshotLease> = resultOf {
        BaseWorldReferenceCoordinator.withLock(worldId) {
            val world = withWorldLock(worldId) {
                database.transaction { repository.findById(worldId) }
                    ?: throw RequestError("地图模板不存在")
            }
            val content = publishedContent(world) ?: throw RequestError("地图模板尚未准备好")
            SnapshotLease(world, content.generated, BaseWorldReferenceCoordinator.acquireSnapshotLeaseLocked(worldId))
        }
    }

    data class SnapshotLease(
        val world: BaseWorld,
        val generated: Boolean,
        private val delegate: BaseWorldReferenceCoordinator.SnapshotLease,
    ) {
        suspend fun release() = delegate.release()
    }

    /** Copies a published archive while holding the world lock, producing an independent snapshot. */
    suspend fun snapshotForHost(
        worldId: UUID,
        ensureTaskActive: () -> Unit = {},
    ): Result<File> = resultOf {
        var snapshot: File? = null
        var completed = false
        try {
            val result = withWorldLock(worldId) {
                val world = database.transaction { repository.findById(worldId) }
                    ?: throw RequestError("地图模板不存在")
                val source = publishedArchive(world) ?: throw RequestError("地图模板尚未准备好")
                val snapshotRoot = source.parentFile?.parentFile ?: BaseWorldDir
                snapshotRoot.mkdirs()
                val createdSnapshot = Files.createTempFile(
                    snapshotRoot.toPath(),
                    ".base-world-host-",
                    ".tar.zst",
                ).toFile()
                snapshot = createdSnapshot
                withContext(Dispatchers.IO) {
                    BufferedInputStream(source.inputStream()).use { input ->
                        BufferedOutputStream(
                            Files.newOutputStream(
                                createdSnapshot.toPath(),
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                            )
                        ).use { output ->
                            val buffer = ByteArray(SNAPSHOT_BUFFER_SIZE)
                            while (true) {
                                ensureTaskActive()
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                createdSnapshot
            }
            completed = true
            result
        } finally {
            if (!completed) snapshot?.delete()
        }
    }

    suspend fun extractSnapshotForHost(
        snapshot: File,
        targetDir: File,
        ensureTaskActive: () -> Unit = {},
    ): Result<Long> = resultOf {
        withContext(Dispatchers.IO) {
            val targetPath = targetDir.toPath()
            if (!Files.notExists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                throw RequestError("房间已存在世界目录")
            }
            val parent = targetDir.parentFile ?: throw RequestError("世界目录路径不正确")
            check(parent.isDirectory) { "房间目录不存在" }
            val stage = Files.createTempDirectory(parent.toPath(), ".world-stage-").toFile()
            try {
                val size = uploads.extractArchiveToDir(snapshot, stage, ensureTaskActive)
                ensureTaskActive()
                try {
                    Files.move(stage.toPath(), targetPath, StandardCopyOption.ATOMIC_MOVE)
                } catch (error: AtomicMoveNotSupportedException) {
                    throw RequestError("无法原子发布世界目录").also { it.addSuppressed(error) }
                }
                size
            } catch (error: Throwable) {
                if (stage.exists()) stage.deleteRecursively()
                throw error
            }
        }
    }

    /** Replaces an existing host world with a fully validated snapshot. */
    suspend fun replaceWorldFromSnapshot(
        snapshot: File,
        targetDir: File,
        ensureTaskActive: () -> Unit = {},
    ): Result<Long> = resultOf {
        withContext(Dispatchers.IO) {
            val targetPath = targetDir.toPath()
            val parent = targetDir.parentFile ?: throw RequestError("世界目录路径不正确")
            check(parent.isDirectory) { "房间目录不存在" }
            val stage = Files.createTempDirectory(parent.toPath(), ".world-stage-").toFile()
            val backup = parent.resolve(".${targetDir.name}.backup-${UUID.randomUUID()}")
            var oldMoved = false
            var preparedMoved = false
            try {
                val size = uploads.extractArchiveToDir(snapshot, stage, ensureTaskActive)
                ensureTaskActive()
                if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(targetPath)) {
                    Files.move(targetPath, backup.toPath())
                    oldMoved = true
                }
                try {
                    movePreparedWorld(stage.toPath(), targetPath)
                } catch (error: AtomicMoveNotSupportedException) {
                    throw RequestError("无法原子发布世界目录").also { it.addSuppressed(error) }
                }
                preparedMoved = true
                if (oldMoved) {
                    runCatching { backup.deleteRecursivelyNoSymlink() }
                        .onFailure { cleanupError -> logger.error(cleanupError) { "清理房间旧世界失败: ${backup.absolutePath}" } }
                    if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backup.toPath())) {
                        logger.error { "清理房间旧世界失败，旧世界备份仍保留: ${backup.absolutePath}" }
                    }
                }
                size
            } catch (error: Throwable) {
                if (preparedMoved && (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(targetPath))) {
                    runCatching { Files.move(targetPath, stage.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        .onFailure { restoreError ->
                            error.addSuppressed(restoreError)
                            logger.error(restoreError) { "无法移走发布失败的新世界: ${targetDir.absolutePath}" }
                        }
                }
                if (oldMoved && Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    runCatching { Files.move(backup.toPath(), targetPath) }
                        .onFailure { restoreError ->
                            error.addSuppressed(restoreError)
                            logger.error(restoreError) { "无法恢复房间旧世界: ${targetDir.absolutePath}" }
                        }
                }
                throw error
            } finally {
                if (stage.exists() || Files.isSymbolicLink(stage.toPath())) {
                    runCatching { stage.deleteRecursivelyNoSymlink() }
                        .onFailure { cleanupError -> logger.error(cleanupError) { "清理房间世界临时目录失败: ${stage.absolutePath}" } }
                    if (Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stage.toPath())) {
                        logger.error { "清理房间世界临时目录失败，临时目录仍保留: ${stage.absolutePath}" }
                    }
                }
            }
        }
    }

    suspend fun delete(ownerId: UUID, id: UUID): Result<Boolean> = resultOf {
        BaseWorldReferenceCoordinator.withLock(id) {
            if (BaseWorldReferenceCoordinator.hasPendingSnapshotLocked(id)) {
                throw RequestError("地图模板正在被房间创建使用，请稍后再删除")
            }
            if (ModpackServiceKernel.dbcl.countDocuments(eq("versions.baseWorld.id", id)) > 0) {
                throw RequestError("地图模板已被整合包版本引用，请先解除版本绑定")
            }
            withWorldLock(id) {
                val world = database.transaction { repository.findById(ownerId, id) } ?: return@withWorldLock false
                uploads.checkDeletionAllowed(id)
                publishDelete(ownerId, world)
                true
            }
        }
    }

    suspend fun rename(requesterId: UUID, id: UUID, name: String, isDav: Boolean): Result<BaseWorld> = resultOf {
        name.validateModpackName().getOrElse { error ->
            throw RequestError(error.message?.replace("整合包", "地图模板") ?: "地图模板名称不正确", error)
        }
        BaseWorldReferenceCoordinator.withLock(id) {
            val world = database.transaction { repository.findById(id) }
                ?: throw RequestError("地图模板不存在")
            if (!isDav && world.ownerId != requesterId) throw RequestError("不是你的地图模板")
            database.transaction { repository.updateName(world.ownerId, id, name) }
                ?: throw RequestError("地图模板不存在")
        }
    }

    suspend fun createUpload(ownerId: UUID, worldId: UUID, dto: BaseWorldUploadSessionCreateDto): Result<BaseWorldUploadSessionVo> = resultOf {
        withWorldLock(worldId) {
            val world = requireWorld(ownerId, worldId)
            if (publishedContent(world) != null) {
                throw RequestError("地图模板已发布，内容不可替换；请创建新的地图模板")
            }
            uploads.create(ownerId, worldId, dto.size, dto.sha1)
        }
    }

    suspend fun uploadStatus(ownerId: UUID, worldId: UUID, uploadId: UUID): Result<BaseWorldUploadSessionVo> = resultOf {
        requireWorld(ownerId, worldId)
        val current = uploads.statusFast(ownerId, worldId, uploadId)
        if (current.status == BaseWorldUploadStatus.Uploading) {
            withWorldLock(worldId) { uploads.status(ownerId, worldId, uploadId) }
        } else {
            current
        }
    }

    suspend fun uploadPart(
        ownerId: UUID,
        worldId: UUID,
        uploadId: UUID,
        index: Int,
        declaredLength: Long?,
        sha1: String,
        source: ByteReadChannel,
    ): Result<Unit> = resultOf {
        var reserved = false
        try {
            val ticket = withWorldLock(worldId) {
                requireWorld(ownerId, worldId)
                uploads.preparePart(ownerId, worldId, uploadId, index, declaredLength, sha1)?.also {
                    val active = activePartCounts[worldId] ?: 0
                    if (active >= MAX_ACTIVE_PARTS) throw RequestError("同时上传的分片过多，请稍后重试")
                    activePartCounts[worldId] = active + 1
                    reserved = true
                }
            }
            if (ticket == null) return@resultOf
            withContext(Dispatchers.IO) {
                val temporary = uploads.createPartTemporary()
                try {
                    chunkedUploadService.receive(source, temporary, ticket.expectedLength.toLong(), ticket.sha1)
                    withWorldLock(worldId) {
                        requireWorld(ownerId, worldId)
                        uploads.commitPart(ticket, temporary)
                    }
                } finally {
                    temporary.delete()
                }
            }
        } finally {
            if (reserved) {
                withContext(NonCancellable + Dispatchers.IO) {
                    withWorldLock(worldId) {
                        activePartCounts.computeIfPresent(worldId) { _, active ->
                            if (active <= 1) null else active - 1
                        }
                    }
                }
            }
        }
    }

    suspend fun completeUpload(ownerId: UUID, worldId: UUID, uploadId: UUID): Result<BaseWorldUploadSessionVo> = resultOf {
        requireWorld(ownerId, worldId)
        val current = uploads.statusFast(ownerId, worldId, uploadId)
        if (current.status != BaseWorldUploadStatus.Uploading) return@resultOf current
        withWorldLock(worldId) {
            val lockedWorld = requireWorld(ownerId, worldId)
            ownerId.toObjectId().getOrThrow()
            val lockedSession = uploads.statusFast(ownerId, worldId, uploadId)
            if (lockedSession.status != BaseWorldUploadStatus.Uploading) return@withWorldLock lockedSession
            val session = uploads.acceptForCompletion(ownerId, worldId, uploadId)
            if (session.status != BaseWorldUploadStatus.Queued) return@withWorldLock session
            var runId: String? = null
            try {
                runId = enqueueValidationTask(ownerId, lockedWorld, uploadId)
            } catch (error: Throwable) {
                val startedRunId = (error as? TaskStartException)?.runId ?: runId
                startedRunId?.let { submitted ->
                    try {
                        taskCanceller(submitted)
                    } catch (cancelError: Throwable) {
                        error.addSuppressed(cancelError)
                    }
                }
                withContext(NonCancellable) { uploads.restoreUploading(ownerId, worldId, uploadId) }
                throw (error.cause ?: error)
            }
            session.copy(status = BaseWorldUploadStatus.Queued)
        }
    }

    private suspend fun processUpload(ownerId: UUID, worldId: UUID, uploadId: UUID, context: Task2Context) {
        withWorldLock(worldId) {
            val world = requireWorld(ownerId, worldId)
            var prepared: BaseWorldUploadStore.PreparedUpload? = null
            var staged: BaseWorldUploadStore.ExtractedUpload? = null
            var publicationCommitted = false
            try {
                val processing = uploads.markProcessing(ownerId, worldId, uploadId)
                if (processing.status == BaseWorldUploadStatus.Ready || processing.status == BaseWorldUploadStatus.Failed) {
                    return@withWorldLock
                }
                afterMarkProcessing(uploadId)
                context.emit(Task2Progress("正在合并地图模板分片"))
                val readyPrepared = uploads.openForCompletion(ownerId, worldId, uploadId)
                prepared = readyPrepared
                context.emit(Task2Progress("正在校验地图模板"))
                val extracted = uploads.extract(readyPrepared) { context.ensureActive() }
                staged = extracted
                context.ensureActive()
                context.emit(Task2Progress("正在发布地图模板"))
                withContext(NonCancellable) {
                    publish(ownerId, world, extracted)
                    publicationCommitted = true
                    uploads.markTerminal(ownerId, worldId, uploadId, BaseWorldUploadStatus.Ready)
                    runCatching { uploads.cleanupPayloads(readyPrepared) }
                        .onFailure { logger.error(it) { "清理地图模板上传文件失败" } }
                }
                notifyTerminal(ownerId, world, uploadId, BaseWorldUploadStatus.Ready, extracted.size)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    staged?.stage?.deleteRecursively()
                    prepared?.archive?.delete()
                    runCatching { uploads.restoreQueued(ownerId, worldId, uploadId) }
                        .onFailure { logger.error(it) { "恢复地图模板上传任务失败" } }
                }
                throw error
            } catch (error: Task2CancelledException) {
                withContext(NonCancellable) {
                    staged?.stage?.deleteRecursively()
                    prepared?.archive?.delete()
                    runCatching { uploads.restoreQueued(ownerId, worldId, uploadId) }
                        .onFailure { logger.error(it) { "恢复地图模板上传任务失败" } }
                }
                throw error
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    if (publicationCommitted) {
                        logger.error(error) { "地图模板发布后保存成功状态失败，保留会话等待恢复" }
                    } else {
                        staged?.stage?.deleteRecursively()
                        val message = safeFailureMessage(error)
                        val failed = runCatching {
                            uploads.markTerminal(ownerId, worldId, uploadId, BaseWorldUploadStatus.Failed, message)
                        }.onFailure { logger.error(it) { "保存地图模板失败状态失败" } }.isSuccess
                        if (failed) {
                            if (prepared != null) {
                                runCatching { uploads.cleanupPayloads(prepared!!) }
                            } else {
                                runCatching { uploads.cleanupSessionPayloads(ownerId, worldId, uploadId) }
                            }
                            notifyTerminal(ownerId, world, uploadId, BaseWorldUploadStatus.Failed, null, message)
                        }
                    }
                }
                throw error
            }
        }
    }

    private suspend fun notifyTerminal(
        owner: UUID,
        world: BaseWorld,
        uploadId: UUID,
        status: BaseWorldUploadStatus,
        size: Long? = null,
        errorMessage: String? = null,
    ) {
        try {
            val session = uploads.statusFast(owner, world.id, uploadId)
            if (session.status != status || session.errorMessage != errorMessage || !uploads.shouldNotify(owner, world.id, uploadId)) return
            val title: String
            val content: String
            if (status == BaseWorldUploadStatus.Ready) {
                title = "地图模板已准备好"
                content = "地图模板${world.name}已准备好，大小${(size ?: world.size)}字节。"
            } else {
                title = "地图模板校验失败"
                content = "地图模板${world.name}校验失败：${errorMessage ?: "未知错误"}"
            }
            notifier(owner, title, content)
            withContext(NonCancellable) { uploads.setNotified(owner, world.id, uploadId) }
        } catch (error: Throwable) {
            logger.error(error) { "发送地图模板结果邮件失败" }
        }
    }

    private fun safeFailureMessage(error: Throwable): String =
        if (error is RequestError) {
            error.message?.takeIf { it.isNotBlank() } ?: "地图模板校验失败"
        } else {
            "地图模板校验或发布失败"
        }

    fun startRecovery(): Job {
        synchronized(taskLock) {
            if (recoveryJob != null) return recoveryJob!!
            recoveryJob = serviceScope.launch { recoverOnStartup() }
            return recoveryJob!!
        }
    }

    private suspend fun recoverOnStartup() {
        uploads.sessions().forEach { snapshot ->
            val state = snapshot.state
            val owner = runCatching { UUID.fromString(state.metadata.ownerId) }.getOrNull()
            val worldId = snapshot.worldId
            if (owner == null) return@forEach
            val worldLookup = runCatching { database.transaction { repository.findById(owner, worldId) } }
            if (worldLookup.isFailure) {
                logger.error(worldLookup.exceptionOrNull()) { "恢复地图模板上传会话时读取模板失败" }
                return@forEach
            }
            val world = worldLookup.getOrNull()
            if (world == null) {
                logger.warn { "忽略不存在地图模板的上传会话: $worldId" }
                runCatching { uploads.cancelWorld(worldId) }
                    .onFailure { error -> logger.error(error) { "清理不存在地图模板的上传会话失败" } }
                return@forEach
            }
            when (state.metadata.status) {
                BaseWorldUploadStatus.Queued,
                BaseWorldUploadStatus.Processing -> {
                    val current = runCatching { uploads.statusFast(owner, worldId, state.id) }
                        .onFailure { error -> logger.error(error) { "恢复地图模板上传会话状态失败" } }
                        .getOrNull()
                    when (current?.status) {
                        BaseWorldUploadStatus.Processing -> {
                            val restored = runCatching { restoreQueuedForRecovery(owner, worldId, state.id) }
                                .onFailure { error -> logger.error(error) { "恢复地图模板上传任务排队状态失败" } }
                            if (restored.isSuccess) {
                                val reread = runCatching { uploads.statusFast(owner, worldId, state.id) }
                                    .onFailure { error -> logger.error(error) { "恢复地图模板上传任务状态复核失败" } }
                                    .getOrNull()
                                if (reread?.status == BaseWorldUploadStatus.Queued) {
                                    enqueueValidation(owner, world, state.id)
                                }
                            }
                        }
                        BaseWorldUploadStatus.Queued -> enqueueValidation(owner, world, state.id)
                        else -> Unit
                    }
                }
                BaseWorldUploadStatus.Ready,
                BaseWorldUploadStatus.Failed -> if (!state.metadata.notified) {
                    withWorldLock(world.id) {
                        runCatching {
                            notifyTerminal(owner, world, state.id, state.metadata.status, if (state.metadata.status == BaseWorldUploadStatus.Ready) world.size else null, state.metadata.errorMessage)
                        }.onFailure { error -> logger.error(error) { "恢复地图模板结果邮件失败" } }
                    }
                }
                BaseWorldUploadStatus.Uploading -> Unit
            }
        }
    }

    private suspend fun enqueueValidation(owner: UUID, world: BaseWorld, uploadId: UUID) {
        runCatching {
            enqueueValidationTask(owner, world, uploadId)
        }.onFailure { logger.error(it) { "恢复地图模板上传任务失败" } }
    }

    private fun enqueueValidationTask(owner: UUID, world: BaseWorld, uploadId: UUID): String {
        val dedupeKey = "baseworld-validate:${world.id}:${uploadId}"
        var submittedRunId: String? = null
        val runId = synchronized(taskLock) {
            check(acceptingCompletions) { "服务器正在关闭，请稍后重试" }
            taskSubmitter(
                Task2.Leaf("校验地图模板${world.name}") { context ->
                    try {
                        context.emit(Task2Progress("等待校验地图模板"))
                        context.ensureActive()
                        validationPermits.withPermit {
                            context.ensureActive()
                            processUpload(owner, world.id, uploadId, context)
                        }
                    } finally {
                        submittedRunId?.let { completedId ->
                            synchronized(taskLock) { validationRunIds.remove(completedId) }
                        }
                    }
                },
                dedupeKey,
            ).also {
                submittedRunId = it
                validationRunIds += it
            }
        }
        try {
            taskStarter(runId)
        } catch (error: Throwable) {
            synchronized(taskLock) { validationRunIds.remove(runId) }
            throw TaskStartException(runId, error)
        }
        return runId
    }

    private class TaskStartException(val runId: String, cause: Throwable) : RuntimeException(cause)

    suspend fun shutdown() {
        synchronized(taskLock) { acceptingCompletions = false }
        recoveryJob?.cancelAndJoin()
        val ids = synchronized(taskLock) { validationRunIds.toList() }
        ids.forEach { runCatching { taskCanceller(it) }.onFailure { error -> logger.error(error) { "停止地图模板校验任务失败" } } }
        serviceScope.coroutineContext[Job]?.cancelAndJoin()
    }

    suspend fun cancelUpload(ownerId: UUID, worldId: UUID, uploadId: UUID): Result<Unit> = resultOf {
        withWorldLock(worldId) {
            requireWorld(ownerId, worldId)
            uploads.cancel(ownerId, worldId, uploadId)
        }
    }

    private suspend fun publish(ownerId: UUID, world: BaseWorld, extracted: BaseWorldUploadStore.ExtractedUpload): BaseWorld =
        withContext(NonCancellable + Dispatchers.IO) {
            val destination = worldDestination(world)
            if (publishedArchive(world) != null) {
                throw RequestError("地图模板已发布，内容不可替换；请创建新的地图模板")
            }
            val backup = destination.resolveSibling(".${destination.name}.backup-${UUID.randomUUID()}")
            var oldMoved = false
            var newMoved = false
            val published = try {
                destination.parentFile.mkdirs()
                if (destination.exists()) {
                    Files.move(destination.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    oldMoved = true
                }
                Files.move(extracted.stage.toPath(), destination.toPath())
                newMoved = true
                val updated = database.transaction { repository.updateSize(ownerId, world.id, extracted.size) }
                    ?: throw RequestError("地图模板不存在")
                updated
            } catch (error: Throwable) {
                var newMovedAside = false
                if (newMoved) {
                    runCatching {
                        Files.move(destination.toPath(), extracted.stage.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        newMovedAside = true
                    }.onFailure {
                        error.addSuppressed(it)
                        logger.error(it) { "无法移回失败的地图模板目录，旧目录保留在${backup.absolutePath}" }
                    }
                }
                if (oldMoved && backup.exists()) {
                    runCatching { Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        .onFailure { error.addSuppressed(it) }
                }
                if (newMovedAside && !extracted.stage.deleteRecursively()) {
                    logger.error { "清理失败的地图模板目录失败: ${extracted.stage.absolutePath}" }
                }
                throw error
            }
            if (oldMoved && !backup.deleteRecursively()) {
                logger.error { "清理地图模板旧文件失败: ${backup.absolutePath}" }
            }
            published
        }

    private suspend fun publishDelete(ownerId: UUID, world: BaseWorld) = withContext(NonCancellable + Dispatchers.IO) {
        val destination = worldDestination(world)
        val backup = destination.resolveSibling(".${destination.name}.delete-${UUID.randomUUID()}")
        var moved = false
        try {
            if (destination.exists()) {
                Files.move(destination.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                moved = true
            }
            val deleted = database.transaction { repository.delete(ownerId, world.id) }
            if (!deleted) throw RequestError("地图模板不存在")
        } catch (error: Throwable) {
            if (moved && backup.exists()) {
                runCatching { Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                    .onFailure { error.addSuppressed(it) }
            }
            throw error
        }
        if (moved) backup.deleteRecursively()
        runCatching { uploads.cancelWorld(world.id) }
            .onFailure { logger.error(it) { "清理地图模板上传会话失败" } }
    }

    private suspend fun requireWorld(ownerId: UUID, worldId: UUID): BaseWorld =
        database.transaction { repository.findById(ownerId, worldId) }
            ?: throw RequestError("地图模板不存在")

    private suspend fun <T> withWorldLock(worldId: UUID, block: suspend () -> T): T =
        worldLocks[(worldId.hashCode() and Int.MAX_VALUE) % worldLocks.size].withLock { block() }

    private data class PublishedContent(val generated: Boolean, val archive: File?)

    private fun publishedContent(world: BaseWorld): PublishedContent? {
        val destination = worldDestination(world)
        val marker = destination.resolve(".generated")
        if (Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return PublishedContent(generated = true, archive = null)
        }
        val archive = worldDestination(world).resolve("world.tar.zst")
        return archive.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            ?.let { PublishedContent(generated = false, archive = it) }
    }

    private fun publishedArchive(world: BaseWorld): File? =
        publishedContent(world)?.archive

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        val logger = KotlinLogging.logger {}
        val worldLocks = Array(256) { Mutex() }
        const val MAX_ACTIVE_PARTS = 8
        const val SNAPSHOT_BUFFER_SIZE = 128 * 1024
    }
}
