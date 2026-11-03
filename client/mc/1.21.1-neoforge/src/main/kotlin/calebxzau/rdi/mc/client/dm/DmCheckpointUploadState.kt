package calebxzau.rdi.mc.client.dm

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException

/** One unresolved upload per hosting session. Only its checkpoint worker may use this state. */
internal class DmCheckpointUploadState(private val sessionId: UUID) {
    data class Pending(val staging: Path, val archive: DmWorldCheckpointArchive.Output)

    var pending: Pending? = null
        private set

    /** A failed build does not consume a sequence or take ownership of drained region changes. */
    fun prepare(
        status: DmWorldCheckpointStatus,
        staging: Path,
        restoreChanges: () -> Unit,
        build: (Long) -> DmWorldCheckpointArchive.Output,
    ): Result<Pending> = runCatching {
        check(pending == null) { "上一个存档点尚未确认" }
        val sequence = Math.addExact(appliedSequence(status), 1L)
        try {
            val archive = build(sequence)
            check(archive.metadata.sessionId == sessionId && archive.metadata.sequence == sequence) {
                "存档点归档身份不匹配"
            }
            Pending(staging, archive).also { pending = it }
        } catch (error: Throwable) {
            restoreChanges()
            throw error
        }
    }

    /**
     * Resolve the original ZIP before building another checkpoint. A timeout does not prove that
     * the master rejected it: inspect its receipt first, and retry exactly the same bytes if absent.
     * Keep the ZIP until both the applied status and the local association have been confirmed.
     */
    fun resolve(
        readStatus: () -> Result<DmWorldCheckpointStatus>,
        upload: (DmWorldCheckpointArchive.Output) -> Result<DmWorldCheckpointReceipt>,
        markPending: (UUID) -> Result<Unit>,
        markConfirmed: (UUID) -> Result<Unit>,
        cancelled: () -> Boolean,
    ): Result<Pending?> = runCatching {
        val current = pending ?: return@runCatching null
        fun checkActive() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("世界存档点已取消")
        }
        checkActive()
        val archive = current.archive
        val metadata = archive.metadata
        val status = readStatus().getOrThrow()
        checkActive()
        if (status.lastUploadSequence == metadata.sequence) {
            checkCommitted(status, archive)
        } else {
            check(appliedSequence(status) == metadata.sequence - 1) { "房间存档点序号已变化，停止重试" }
            markPending(metadata.cycleId).getOrThrow()
            checkActive()
            val receipt = upload(archive).getOrThrow()
            check(receipt.sessionId == sessionId && receipt.sequence == metadata.sequence &&
                receipt.cycleId == metadata.cycleId && receipt.uploadSha1 == archive.sha1 &&
                receipt.uploadZipBytes == archive.bytes && receipt.appliedSequence == metadata.sequence) {
                "存档点上传回执不匹配"
            }
            checkActive()
            // A stored receipt alone does not prove that the master's journal finished applying.
            checkCommitted(readStatus().getOrThrow(), archive)
        }
        checkActive()
        markConfirmed(metadata.cycleId).getOrThrow()
        pending = null
        current
    }

    /** Called only once its worker has finished, or while the binding has no worker. */
    fun discard(): Pending? = pending.also { pending = null }

    private fun checkCommitted(status: DmWorldCheckpointStatus, archive: DmWorldCheckpointArchive.Output) {
        val metadata = archive.metadata
        check(status.lastUploadSessionId == sessionId && status.lastUploadSequence == metadata.sequence &&
            status.lastUploadCycleId == metadata.cycleId && status.lastUploadSha1 == archive.sha1 &&
            status.lastUploadZipBytes == archive.bytes) { "房间存档点与待确认上传不匹配" }
        check(appliedSequence(status) == metadata.sequence) { "房间存档点尚未应用完成" }
    }

    private fun appliedSequence(status: DmWorldCheckpointStatus): Long {
        val uploaded = status.lastUploadSequence ?: 0L
        check(uploaded >= 0 && status.appliedSequence == uploaded) { "房间存档点尚未应用完成" }
        return uploaded
    }
}
