package calebxzau.rdi.server.service.hostworlddownload

import calebxzau.rdi.common.model.HostWorldDownloadProgressVo
import calebxzau.rdi.common.model.HostWorldDownloadReadyVo
import calebxzau.rdi.common.model.HostWorldDownloadRecordVo
import calebxzau.rdi.common.model.HostWorldDownloadStage
import calebxzau.rdi.common.model.HostWorldDownloadStartVo
import calebxzau.rdi.common.model.HostWorldDownloadStatusVo
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackFiles
import calebxzau.rdi.server.service.hostworldimport.HostWorldImportGuard
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.master.service.PlayerService
import calebxzhou.rdi.master.service.host.HostControlService
import calebxzhou.rdi.master.service.host.HostLifecycleLock
import calebxzhou.rdi.master.service.host.HostQueryService
import calebxzhou.rdi.master.service.host.dir
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** The host lookups and locks the save download needs; tests substitute their own. */
interface HostWorldDownloadAccess {
    suspend fun host(hostId: ObjectId): Host?

    fun hostDir(host: Host): Path

    fun isStopped(host: Host): Boolean

    /** Throws a `RequestError` while a save import blocks the host. */
    fun checkNotImporting(hostId: ObjectId)

    suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T

    suspend fun playerNames(ids: Collection<ObjectId>): Map<ObjectId, String>
}

object DefaultHostWorldDownloadAccess : HostWorldDownloadAccess {
    override suspend fun host(hostId: ObjectId): Host? = HostQueryService.getById(hostId)

    override fun hostDir(host: Host): Path = host.dir.toPath()

    override fun isStopped(host: Host): Boolean = with(HostControlService) { host.status } == HostStatus.STOPPED

    override fun checkNotImporting(hostId: ObjectId) = HostWorldImportGuard.check(hostId)

    override suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T =
        HostLifecycleLock.withLock(hostId) { block() }

    override suspend fun playerNames(ids: Collection<ObjectId>): Map<ObjectId, String> =
        PlayerService.getByIds(ids).associate { it._id to it.name }
}

/** What a build is doing; SSE clients follow it until it is [Ready] or [Failed]. */
sealed interface HostWorldDownloadJobState {
    data class Running(val progress: HostWorldDownloadProgressVo) : HostWorldDownloadJobState
    data class Ready(val ready: HostWorldDownloadReadyVo) : HostWorldDownloadJobState
    data class Failed(val message: String) : HostWorldDownloadJobState
}

class HostWorldDownloadJob(val buildId: String) {
    private val _state = MutableStateFlow<HostWorldDownloadJobState>(
        HostWorldDownloadJobState.Running(HostWorldDownloadProgressVo(HostWorldDownloadStage.SyncChunks, 0, 0))
    )
    val state: StateFlow<HostWorldDownloadJobState> = _state.asStateFlow()

    internal fun update(state: HostWorldDownloadJobState) {
        _state.value = state
    }
}

/**
 * Save download (下载房间存档) of v2 hosts. Members (not guests) pack the stopped host's sync chunks and
 * other world data into a tar.zst. Each host may pack 3 times per Asia/Shanghai day; only finished
 * builds count, and a packed save of an unchanged world is reused without counting. A build holds the
 * host's lifecycle lock, so the host cannot start, import, roll back or reset meanwhile.
 */
class HostWorldDownloadService(
    private val hosts: HostWorldDownloadAccess = DefaultHostWorldDownloadAccess,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val builder: (worldDir: Path, target: Path, workDir: Path, (HostWorldDownloadStage, Int, Int) -> Unit) -> Result<HostWorldDownloadBuildResult> =
        HostWorldDownloadBuilder::build,
) {
    private val jobs = ConcurrentHashMap<ObjectId, HostWorldDownloadJob>()
    private val startLocks = ConcurrentHashMap<ObjectId, Mutex>()

    suspend fun status(host: Host): HostWorldDownloadStatusVo {
        val root = rootOf(host)
        val today = todayRecords(root)
        val names = hosts.playerNames(today.map { ObjectId(it.playerId) })
        return HostWorldDownloadStatusVo(
            limit = DAILY_LIMIT,
            remaining = (DAILY_LIMIT - today.size).coerceAtLeast(0),
            today = today.map { record ->
                HostWorldDownloadRecordVo(record.playerId, names[ObjectId(record.playerId)], record.finishedAt, record.bytes)
            },
            reusable = reusableArchive(host) != null,
            blockedReason = blockedReason(host),
        )
    }

    /**
     * Reuses the packed save of an unchanged world, joins a running build, or starts one. Checks that
     * fail now are thrown; later failures reach the client as the job's [HostWorldDownloadJobState.Failed].
     */
    suspend fun start(host: Host, requester: ObjectId): HostWorldDownloadStartVo =
        startLocks.computeIfAbsent(host._id) { Mutex() }.withLock {
            jobs[host._id]?.takeIf { it.state.value is HostWorldDownloadJobState.Running }?.let {
                return@withLock HostWorldDownloadStartVo(it.buildId, reused = false)
            }
            reusableArchive(host)?.let { return@withLock HostWorldDownloadStartVo(it.id, reused = true) }
            blockedReason(host)?.let { throw RequestError(it) }
            if (todayRecords(rootOf(host)).size >= DAILY_LIMIT) throw RequestError(LIMIT_REACHED)
            val job = HostWorldDownloadJob(SyncChunkBackupIds.newUuidV7(clock()).toString())
            jobs[host._id] = job
            scope.launch { runJob(host._id, requester, job) }
            HostWorldDownloadStartVo(job.buildId, reused = false)
        }

    /**
     * The build [buildId] of [host] while it runs or after it failed, or its packed save while that can
     * still be downloaded; null otherwise, e.g. after the host started again.
     */
    fun job(host: Host, buildId: String): StateFlow<HostWorldDownloadJobState>? {
        jobs[host._id]?.takeIf { it.buildId == buildId && it.state.value !is HostWorldDownloadJobState.Ready }?.let { return it.state }
        val archive = reusableArchive(host)?.takeIf { it.id == buildId } ?: return null
        return MutableStateFlow(HostWorldDownloadJobState.Ready(archive.toReady()))
    }

    /** The archive file of [buildId] while the world is unchanged; null otherwise. */
    fun archiveFile(host: Host, buildId: String): Path? {
        val archive = reusableArchive(host)?.takeIf { it.id == buildId } ?: return null
        return HostWorldDownloadFiles.archiveFile(rootOf(host), archive.id).takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private suspend fun runJob(hostId: ObjectId, requester: ObjectId, job: HostWorldDownloadJob) {
        val state = try {
            hosts.withLifecycleLock(hostId) { buildLocked(hostId, requester, job) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RequestError) {
            HostWorldDownloadJobState.Failed(error.message ?: "打包存档失败")
        } catch (error: Exception) {
            logger.error(error) { "房间${hostId}打包存档失败，打包${job.buildId}" }
            HostWorldDownloadJobState.Failed("打包存档失败：${error.message ?: error.javaClass.simpleName}")
        }
        job.update(state)
    }

    private suspend fun buildLocked(hostId: ObjectId, requester: ObjectId, job: HostWorldDownloadJob): HostWorldDownloadJobState {
        val host = hosts.host(hostId) ?: throw RequestError("无此房间")
        blockedReason(host)?.let { throw RequestError(it) }
        val root = rootOf(host)
        if (todayRecords(root).size >= DAILY_LIMIT) throw RequestError(LIMIT_REACHED)
        val worldDir = worldDir(host)
        val stamp = HostWorldStamp.of(worldDir).getOrThrow() ?: throw RequestError(NO_WORLD)
        HostWorldDownloadFiles.discardArchives(root).getOrThrow()
        Files.createDirectories(root)
        val startedAt = clock()
        val building = root.resolve(".build-${job.buildId}.tar.zst")
        try {
            val result = builder(worldDir, building, root.resolve(".work-${job.buildId}")) { stage, done, total ->
                job.update(HostWorldDownloadJobState.Running(HostWorldDownloadProgressVo(stage, done, total)))
            }.getOrThrow()
            if (HostWorldStamp.of(worldDir).getOrThrow() != stamp) throw RequestError("打包时存档发生了变化，请重试")
            val finishedAt = clock()
            // Re-read under the lock, so a build never takes a fourth download of the day.
            val records = HostWorldDownloadFiles.readRecords(root, finishedAt).getOrThrow()
            if (records.records.count { it.day == dayOf(finishedAt) } >= DAILY_LIMIT) throw RequestError(LIMIT_REACHED)
            Files.move(building, HostWorldDownloadFiles.archiveFile(root, job.buildId), StandardCopyOption.REPLACE_EXISTING)
            val archive = HostWorldDownloadArchive(job.buildId, result.bytes, result.sha1, result.chunkCount, finishedAt, stamp)
            HostWorldDownloadFiles.writeArchive(root, archive).getOrThrow()
            HostWorldDownloadFiles.appendRecord(
                root,
                records,
                HostWorldDownloadRecord(job.buildId, requester.toHexString(), dayOf(finishedAt), startedAt, finishedAt, result.bytes, result.sha1, result.chunkCount),
            ).getOrThrow()
            logger.info { "房间${hostId}打包存档${job.buildId}：${result.bytes}字节，区块${result.chunkCount}个，玩家${requester}" }
            return HostWorldDownloadJobState.Ready(archive.toReady())
        } catch (error: Throwable) {
            HostWorldDownloadFiles.discardArchives(root).onFailure(error::addSuppressed)
            throw error
        }
    }

    /** Null when a download can start; otherwise the reason, in player terms. */
    private fun blockedReason(host: Host): String? {
        if (host.realVersion != 2 || host.worldId != null) return "仅2026.9.5以后创建的房间支持下载存档"
        runCatching { hosts.checkNotImporting(host._id) }.onFailure { return it.message ?: "房间正在导入存档" }
        if (!hosts.isStopped(host)) return "请先停止房间再下载存档"
        val rollback = SyncChunkRollbackFiles.readRequest(SyncChunkBackupStore.rootOf(hosts.hostDir(host))).getOrElse { exception ->
            logger.warn(exception) { "房间${host._id}的回档请求无法读取" }
            return PENDING_ROLLBACK
        }
        if (rollback != null) return PENDING_ROLLBACK
        val stamp = HostWorldStamp.of(worldDir(host)).getOrElse { exception ->
            logger.warn(exception) { "房间${host._id}的存档无法读取" }
            return "房间存档暂时无法读取"
        }
        if (stamp == null) return NO_WORLD
        return null
    }

    /** The packed save while the host is stopped and its world unchanged since packing. */
    private fun reusableArchive(host: Host): HostWorldDownloadArchive? {
        if (!hosts.isStopped(host)) return null
        val root = rootOf(host)
        val archive = HostWorldDownloadFiles.readArchive(root).getOrElse { exception ->
            logger.warn(exception) { "房间${host._id}的存档包信息无法读取" }
            return null
        } ?: return null
        val stamp = HostWorldStamp.of(worldDir(host)).getOrElse { exception ->
            logger.warn(exception) { "房间${host._id}的存档无法读取" }
            return null
        } ?: return null
        if (archive.world != stamp) return null
        if (!Files.isRegularFile(HostWorldDownloadFiles.archiveFile(root, archive.id), LinkOption.NOFOLLOW_LINKS)) return null
        return archive
    }

    private fun todayRecords(root: Path): List<HostWorldDownloadRecord> {
        val now = clock()
        val records = HostWorldDownloadFiles.readRecords(root, now).getOrElse { exception ->
            logger.error(exception) { "存档下载记录无法读取: ${root}" }
            throw RequestError("存档下载记录暂时无法读取", exception)
        }
        val today = dayOf(now)
        return records.records.filter { it.day == today }.sortedBy { it.finishedAt }
    }

    private fun rootOf(host: Host): Path = HostWorldDownloadFiles.rootOf(hosts.hostDir(host))

    private fun worldDir(host: Host): Path = hosts.hostDir(host).resolve("world")

    private fun HostWorldDownloadArchive.toReady() = HostWorldDownloadReadyVo(id, bytes, sha1, chunkCount)

    companion object {
        const val DAILY_LIMIT = 3
        private const val LIMIT_REACHED = "今天的3次下载已经用完，明天再来"
        private const val PENDING_ROLLBACK = "有等待中的回档，请先启动房间完成回档或取消回档"
        private const val NO_WORLD = "房间还没有存档，请先启动一次房间"
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private val logger = KotlinLogging.logger {}

        fun dayOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().toString()

        /** Called when the host starts: the world will change, so the packed save is dropped. */
        fun discardForStart(hostDir: Path) {
            HostWorldDownloadFiles.discardArchives(HostWorldDownloadFiles.rootOf(hostDir)).onFailure { exception ->
                logger.warn(exception) { "清除房间存档包失败: ${hostDir}" }
            }
        }
    }
}
