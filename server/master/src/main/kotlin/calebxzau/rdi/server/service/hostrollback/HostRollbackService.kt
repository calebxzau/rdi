package calebxzau.rdi.server.service.hostrollback

import calebxzau.rdi.common.model.HostRollbackCreateDto
import calebxzau.rdi.common.model.HostRollbackCreateVo
import calebxzau.rdi.common.model.HostRollbackOutcome
import calebxzau.rdi.common.model.HostRollbackPendingVo
import calebxzau.rdi.common.model.HostRollbackPointKind
import calebxzau.rdi.common.model.HostRollbackPointVo
import calebxzau.rdi.common.model.HostRollbackRequestState
import calebxzau.rdi.common.model.HostRollbackResultVo
import calebxzau.rdi.common.model.HostRollbackStatusVo
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIndex
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupJson
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackFiles
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackRequest
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackState
import calebxzau.rdi.server.service.hostworldimport.HostWorldImportGuard
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.master.service.host.HostControlService
import calebxzhou.rdi.master.service.host.HostLifecycleLock
import calebxzhou.rdi.master.service.host.HostQueryService
import calebxzhou.rdi.master.service.host.dir
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The host lookups and locks the rollback needs; tests substitute their own. */
interface HostRollbackAccess {
    suspend fun host(hostId: ObjectId): Host?

    fun hostDir(host: Host): Path

    fun isStopped(host: Host): Boolean

    /** Throws a `RequestError` while a save import blocks the host. */
    fun checkNotImporting(hostId: ObjectId)

    suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T
}

object DefaultHostRollbackAccess : HostRollbackAccess {
    override suspend fun host(hostId: ObjectId): Host? = HostQueryService.getById(hostId)

    override fun hostDir(host: Host): Path = host.dir.toPath()

    override fun isStopped(host: Host): Boolean = with(HostControlService) { host.status } == HostStatus.STOPPED

    override fun checkNotImporting(hostId: ObjectId) = HostWorldImportGuard.check(hostId)

    override suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T =
        HostLifecycleLock.withLock(hostId) { block() }
}

/**
 * Rollback (回档) of a host's sync chunks and player data to a backup point. The host mod takes the
 * points and applies a rollback itself, before it loads the world; the master only lists the points,
 * leaves a request in the host's backup store and stops a running host. It never starts the host:
 * the rollback applies when someone starts it next.
 */
class HostRollbackService(
    private val hosts: HostRollbackAccess = DefaultHostRollbackAccess,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun status(host: Host): HostRollbackStatusVo {
        val root = SyncChunkBackupStore.rootOf(hosts.hostDir(host))
        val index = readIndex(root)
        val pending = SyncChunkRollbackFiles.readRequest(root).getOrElse { exception ->
            logger.error(exception) { "回档请求无法读取: ${root}" }
            throw RequestError("回档状态暂时无法读取", exception)
        }
        val result = SyncChunkRollbackFiles.readResult(root).getOrElse { exception ->
            logger.error(exception) { "回档结果无法读取: ${root}" }
            null
        }
        return HostRollbackStatusVo(
            points = index?.points.orEmpty().map { point ->
                HostRollbackPointVo(
                    id = point.id,
                    kind = HostRollbackPointKind.valueOf(point.kind.name),
                    createdAt = point.createdAt,
                    chunkCount = point.chunkCount,
                    playerCount = point.playerCount,
                    changedChunks = point.changed.terrainChunks,
                    changedPlayers = point.changed.players,
                )
            },
            pending = pending?.let {
                HostRollbackPendingVo(it.pointId, it.requestedAt, HostRollbackRequestState.valueOf(it.state.name), it.abandoned)
            },
            lastResult = result?.let {
                HostRollbackResultVo(it.pointId, it.beforePointId, HostRollbackOutcome.valueOf(it.outcome.name), it.message, it.finishedAt)
            },
            usedBytes = index?.usedBytes ?: 0,
            maxBytes = index?.maxBytes ?: SyncChunkBackupStore.MAX_BYTES,
        )
    }

    /**
     * Leaves a request for [HostRollbackCreateDto.pointId] and, when the host runs, stops it through
     * [stopHost] after releasing the lifecycle lock (stopping takes the lock itself).
     */
    suspend fun request(host: Host, requester: ObjectId, dto: HostRollbackCreateDto, stopHost: suspend () -> Unit): HostRollbackCreateVo {
        val running = hosts.withLifecycleLock(host._id) {
            val fresh = hosts.host(host._id) ?: throw RequestError("无此房间")
            if (fresh.ownerId != requester) throw RequestError("仅房主可以回档")
            hosts.checkNotImporting(fresh._id)
            val root = SyncChunkBackupStore.rootOf(hosts.hostDir(fresh))
            val index = readIndex(root) ?: throw RequestError("此房间还没有回档点")
            if (index.points.none { it.id == dto.pointId }) throw RequestError("回档点不存在或已过期")
            val stopped = hosts.isStopped(fresh)
            val pending = SyncChunkRollbackFiles.readRequest(root).getOrElse { exception ->
                // The host fails such a request on its next start anyway; a new one replaces it.
                logger.warn(exception) { "替换无法读取的回档请求: ${root}" }
                null
            }
            if (pending != null) {
                if (pending.state == SyncChunkRollbackState.Applying) throw RequestError("上一次回档还没有完成，请先启动房间完成它，或者取消回档")
                if (!stopped) throw RequestError("房间正在运行，请等房间停止后再更改回档点")
            }
            val now = clock()
            val request = SyncChunkRollbackRequest(
                id = SyncChunkBackupIds.newUuidV7(now).toString(),
                pointId = dto.pointId,
                worldToken = index.worldToken,
                requestedBy = requester.toHexString(),
                requestedAt = now,
            )
            SyncChunkRollbackFiles.writeRequest(root, request).getOrElse { exception ->
                logger.error(exception) { "写入回档请求失败: ${root}" }
                throw RequestError("回档请求保存失败", exception)
            }
            logger.info { "房间${fresh._id}请求回档到${dto.pointId}，请求${request.id}" }
            !stopped
        }
        if (running) {
            try {
                stopHost()
            } catch (exception: Exception) {
                logger.warn(exception) { "回档后关闭房间失败: ${host._id}" }
                throw RequestError("已设置回档，但关闭房间失败，请手动停止房间后再启动", exception)
            }
        }
        return HostRollbackCreateVo(stoppedHost = running)
    }

    /**
     * Cancels a request that has not started. One that started writing is turned into restoring the
     * before-rollback point instead, since the world may already be partly rolled back.
     */
    suspend fun cancel(host: Host) {
        hosts.withLifecycleLock(host._id) {
            val fresh = hosts.host(host._id) ?: throw RequestError("无此房间")
            val root = SyncChunkBackupStore.rootOf(hosts.hostDir(fresh))
            val pending = SyncChunkRollbackFiles.readRequest(root).getOrElse { exception ->
                logger.warn(exception) { "删除无法读取的回档请求: ${root}" }
                deleteRequest(root)
                return@withLifecycleLock
            } ?: throw RequestError("没有等待中的回档")
            // A running host may be applying the request right now.
            if (!hosts.isStopped(fresh)) throw RequestError("房间正在运行，请等房间停止后再取消回档")
            when (pending.state) {
                SyncChunkRollbackState.Requested -> deleteRequest(root)
                SyncChunkRollbackState.Applying -> {
                    if (pending.abandoned) throw RequestError("已经取消，房间下次启动时会恢复到回档前的状态")
                    SyncChunkRollbackFiles.writeRequest(root, pending.abandon()).getOrElse { exception ->
                        logger.error(exception) { "写入取消回档请求失败: ${root}" }
                        throw RequestError("取消回档失败", exception)
                    }
                }
            }
            logger.info { "房间${fresh._id}取消回档${pending.id}（${pending.state}）" }
        }
    }

    private fun deleteRequest(root: Path) {
        SyncChunkRollbackFiles.deleteRequest(root).getOrElse { exception ->
            logger.error(exception) { "删除回档请求失败: ${root}" }
            throw RequestError("取消回档失败", exception)
        }
    }

    /** Null before the host took its first point. */
    private fun readIndex(root: Path): SyncChunkBackupIndex? {
        val file = root.resolve(SyncChunkBackupStore.INDEX)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching { SyncChunkBackupJson.decodeIndex(Files.readString(file)).getOrThrow() }.getOrElse { exception ->
            logger.error(exception) { "回档点列表无法读取: ${file}" }
            throw RequestError("回档点列表暂时无法读取", exception)
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Drops a pending rollback of a world that is being replaced (reset or save import). The host
         * would refuse it anyway, since the new world has another token.
         */
        fun discardForReplacedWorld(hostDir: Path) {
            SyncChunkRollbackFiles.deleteRequest(SyncChunkBackupStore.rootOf(hostDir)).onFailure { exception ->
                logger.warn(exception) { "清除旧世界的回档请求失败: ${hostDir}" }
            }
        }
    }
}
