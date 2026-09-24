package calebxzau.rdi.mc.client.syncchunk
import calebxzau.rdi.mc.syncchunk.SyncChunkKey

import java.util.UUID
import java.util.concurrent.Executor

/** Master-authoritative DM sync-chunk state machine. Calls are owned by the integrated-server dispatcher. */
class DmSyncChunkSessionController(
    private val transport: Transport,
    private val background: Executor,
    private val retryLater: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val listener: Listener,
    initialSnapshot: DmSyncChunkSnapshot? = null,
) {
    interface Transport {
        fun get(hostId: UUID, sessionId: UUID): Result<DmSyncChunkSnapshot>
        fun add(hostId: UUID, sessionId: UUID, revision: Long, playerId: UUID, chunk: SyncChunkKey): Result<DmSyncChunkMutation>
        fun remove(hostId: UUID, sessionId: UUID, revision: Long, playerId: UUID, chunk: SyncChunkKey): Result<DmSyncChunkMutation>
    }

    interface Listener {
        fun onSnapshot(snapshot: DmSyncChunkSnapshot, loading: Boolean)
        fun onOperationAccepted(operation: DmSyncChunkOperation, mutation: DmSyncChunkMutation)
        fun onOperationFailed(operation: DmSyncChunkOperation, message: String)
        fun onOperationReconciled(operation: DmSyncChunkOperation, achieved: Boolean)
        fun onUnavailable(message: String)
    }

    private var token = 0L
    private var hostId: UUID? = null
    private var sessionId: UUID? = null
    private var snapshot: DmSyncChunkSnapshot? = initialSnapshot
    private var loading = false
    private var paused = false
    private var inFlight: Pending? = null
    private var recovering: Pending? = null
    private var lastUnavailableMessage: String? = null
    private val queue = ArrayDeque<DmSyncChunkOperation>()

    fun begin(host: UUID, session: UUID) {
        cancelQueued("DM会话已切换，操作未提交")
        inFlight?.let { listener.onOperationFailed(it.operation, "DM会话已切换，操作结果未知") }
        inFlight = null
        recovering = null
        hostId = host
        sessionId = session
        token++
        paused = false
        lastUnavailableMessage = null
        loading = true
        listener.onSnapshot(snapshot ?: EMPTY, true)
        load(token)
    }

    fun pause(message: String = "同步区块暂时不可用") {
        token++
        cancelQueued(message)
        inFlight?.let { listener.onOperationFailed(it.operation, "操作结果未知：$message") }
        inFlight = null
        recovering = null
        loading = false
        paused = true
        notifyUnavailable(message)
    }

    fun stop() {
        token++
        cancelQueued("DM房间已停止")
        inFlight?.let { listener.onOperationFailed(it.operation, "DM房间已停止，操作结果未知") }
        inFlight = null
        recovering = null
        hostId = null
        sessionId = null
        snapshot = null
        loading = false
        paused = true
        lastUnavailableMessage = null
        listener.onSnapshot(EMPTY, false)
    }

    fun currentSnapshot(): DmSyncChunkSnapshot? = snapshot
    fun canEdit(): Boolean = !loading && !paused && snapshot != null

    fun request(operation: DmSyncChunkOperation): Boolean {
        if (!canEdit()) {
            listener.onOperationFailed(operation, "同步区块资料仍在加载中或暂时不可用")
            return false
        }
        val occupied = queue.size + if (inFlight == null) 0 else 1
        if (occupied >= MAX_QUEUE) {
            listener.onOperationFailed(operation, "同步区块操作过多，请稍后再试")
            return false
        }
        queue.addLast(operation)
        pump()
        return true
    }

    private fun load(expectedToken: Long) {
        val host = hostId ?: return
        val session = sessionId ?: return
        background.execute {
            val result = transport.get(host, session)
            dispatch(Runnable { onLoad(expectedToken, result) })
        }
    }

    private fun onLoad(expectedToken: Long, result: Result<DmSyncChunkSnapshot>) {
        if (expectedToken != token) return
        result.onSuccess {
            val pending = recovering
            if (pending == null) {
                snapshot = it
                loading = false
                paused = false
                lastUnavailableMessage = null
                listener.onSnapshot(it, false)
                pump()
            } else when {
                it.revision > pending.revision -> {
                    snapshot = it
                    loading = false
                    paused = false
                    lastUnavailableMessage = null
                    recovering = null
                    inFlight = null
                    listener.onSnapshot(it, false)
                    listener.onOperationReconciled(pending.operation, achieved(pending.operation, it))
                    pump()
                }
                it.revision == pending.revision -> {
                    snapshot = it
                    listener.onSnapshot(it, true)
                    retryFrozen(pending)
                }
                else -> {
                    loading = false
                    paused = true
                    notifyUnavailable("同步区块版本回退，暂时无法确认操作")
                }
            }
        }.onFailure { error ->
            if ((error as? SyncChunkRequestError)?.reason == "HostNotFound") {
                loading = false
                paused = true
                notifyUnavailable(error.message ?: "同步区块暂时不可用")
                return@onFailure
            }
            loading = true
            notifyUnavailable(error.message ?: "无法加载同步区块")
            retryLater(Runnable { dispatch(Runnable { if (expectedToken == token) load(expectedToken) }) })
        }
    }

    private fun pump() {
        if (loading || paused || inFlight != null) return
        val operation = queue.removeFirstOrNull() ?: return
        val host = hostId
        val session = sessionId
        val revision = snapshot?.revision
        if (host == null || session == null || revision == null) {
            listener.onOperationFailed(operation, "同步区块资料尚未加载")
            return
        }
        val pending = Pending(operation, revision)
        inFlight = pending
        val requestToken = token
        background.execute {
            val result = when (operation.kind) {
                DmSyncChunkOperationKind.Add -> transport.add(host, session, revision, operation.playerId, operation.chunk)
                DmSyncChunkOperationKind.Remove -> transport.remove(host, session, revision, operation.playerId, operation.chunk)
            }
            dispatch(Runnable { onMutation(requestToken, pending, result) })
        }
    }

    private fun retryFrozen(pending: Pending) {
        val host = hostId ?: return
        val session = sessionId ?: return
        inFlight = pending
        val requestToken = token
        background.execute {
            val result = when (pending.operation.kind) {
                DmSyncChunkOperationKind.Add -> transport.add(host, session, pending.revision, pending.operation.playerId, pending.operation.chunk)
                DmSyncChunkOperationKind.Remove -> transport.remove(host, session, pending.revision, pending.operation.playerId, pending.operation.chunk)
            }
            dispatch(Runnable { onMutation(requestToken, pending, result) })
        }
    }

    private fun onMutation(expectedToken: Long, pending: Pending, result: Result<DmSyncChunkMutation>) {
        if (expectedToken != token || inFlight != pending) return
        result.onSuccess { mutation ->
            if (mutation.snapshot.revision != pending.revision + 1) {
                beginRecovery(pending, "DM服务器返回的同步区块版本无效")
                return@onSuccess
            }
            inFlight = null
            recovering = null
            loading = false
            paused = false
            snapshot = mutation.snapshot
            listener.onSnapshot(mutation.snapshot, false)
            listener.onOperationAccepted(pending.operation, mutation)
            pump()
        }.onFailure { error ->
            when ((error as? SyncChunkRequestError)?.reason) {
                "RevisionConflict" -> {
                    if (recovering == pending) {
                        loading = true
                        load(token)
                    } else {
                        inFlight = null
                        cancelQueued("同步区块版本已变化，操作已取消")
                        loading = true
                        listener.onOperationFailed(pending.operation, "同步区块版本已变化，操作未执行")
                        load(token)
                    }
                }
                "OwnedByOther", "TotalLimitReached", "InvalidRequest" -> {
                    inFlight = null
                    recovering = null
                    listener.onOperationFailed(pending.operation, error.message ?: "同步区块操作失败")
                    pump()
                }
                "SessionUnavailable", "HostNotFound" -> {
                    inFlight = null
                    recovering = null
                    cancelQueued("DM会话已失效，操作未提交")
                    loading = false
                    paused = true
                    listener.onOperationFailed(pending.operation, error.message ?: "同步区块操作失败")
                    notifyUnavailable("DM会话已失效，同步区块暂时不可用")
                }
                else -> beginRecovery(pending, "同步区块结果未知，正在重新确认")
            }
        }
    }

    private fun beginRecovery(pending: Pending, message: String) {
        recovering = pending
        cancelQueued("操作已暂停，正在确认同步区块状态")
        loading = true
        notifyUnavailable(message)
        load(token)
    }

    private fun achieved(operation: DmSyncChunkOperation, current: DmSyncChunkSnapshot): Boolean {
        val entry = current.chunks.firstOrNull { it.key == operation.chunk }
        return when (operation.kind) {
            DmSyncChunkOperationKind.Add -> entry?.ownerId == operation.playerId
            DmSyncChunkOperationKind.Remove -> entry == null || entry.ownerId != operation.playerId
        }
    }

    private fun cancelQueued(message: String) {
        queue.forEach { listener.onOperationFailed(it, message) }
        queue.clear()
    }

    private fun notifyUnavailable(message: String) {
        if (lastUnavailableMessage == message) return
        lastUnavailableMessage = message
        listener.onUnavailable(message)
    }

    private data class Pending(val operation: DmSyncChunkOperation, val revision: Long)

    companion object {
        private const val MAX_QUEUE = 64
        private val EMPTY = DmSyncChunkSnapshot(0, emptyList(), DmSyncChunkLimits(256))
    }
}
