package calebxzau.rdi.server.service.hostworldimport

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Replaces a host's world with a validated stage directory, in a way that can always be recovered
 * after a crash.
 *
 * Invariants:
 * - I1: the journal names a phase before any move or delete that belongs to it.
 * - I2: if a journal write fails, no further filesystem work happens; the returned [Result] fails and
 *   the persisted state stays as last written, for recovery to continue.
 * - I3: `Swapping` only moves forward, `RollingBack` only moves backward, and `Committed` never
 *   touches the swap again.
 * - I4: callers hold `HostLifecycleLock` for the host and an [ImportExecutionRegistry.ImportClaim]
 *   for the session around every call.
 *
 * Because a rollback always persists `RollingBack` first, `Swapping` with the stage gone and the
 * world present can only mean the stage was moved in, so recovery may safely commit it.
 */
class HostWorldSwap(private val fs: WorldSwapFileSystem = NioWorldSwapFileSystem) {

    sealed interface SwapOutcome {
        data object Committed : SwapOutcome

        /** The old world is back in place and the session is `Failed`. */
        data class RolledBack(val cause: Throwable?) : SwapOutcome

        /** The state could not be resolved automatically; nothing more is touched. */
        data class RecoveryRequired(val reason: String) : SwapOutcome
    }

    sealed interface RecoveryOutcome {
        /** `recoveryRequired` is set: only resend the mail if it was not sent. */
        data object NotifyOnly : RecoveryOutcome

        /** The session is terminal: its own leftovers were deleted; resend the mail if it was not sent. */
        data object TerminalCleaned : RecoveryOutcome

        /** The session is still uploading: nothing to recover. */
        data object NotStarted : RecoveryOutcome

        /** The world was never touched and the stage was removed: restore `Queued` and enqueue again. */
        data object Requeue : RecoveryOutcome

        /** The swap is committed: run the remaining post-commit steps. */
        data object PostCommitPending : RecoveryOutcome

        /** An interrupted swap or rollback was continued to [outcome]. */
        data class Continued(val outcome: SwapOutcome) : RecoveryOutcome
    }

    /**
     * Swaps the stage in (S1–S4). The journal must be `Processing` with phase `None`, the stage must
     * exist and the old-world path must not. A filesystem failure before commit rolls back (R1–R6).
     */
    fun swap(
        claim: ImportExecutionRegistry.ImportClaim,
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
    ): Result<SwapOutcome> = swapResult {
        require(claim.isActive) { "导入任务未持有执行占用" }
        val initial = store.read()
        check(initial.status == ImportJournalStatus.Processing && initial.swapPhase == SwapPhase.None && !initial.recoveryRequired) {
            "导入任务状态不允许替换世界: ${initial.status}/${initial.swapPhase}"
        }
        check(fs.exists(paths.stage)) { "导入临时目录不存在: ${paths.stage}" }
        check(!fs.exists(paths.old)) { "旧世界临时目录已存在: ${paths.old}" }
        val swapping = initial.copy(swapPhase = SwapPhase.Swapping, hadOldWorld = fs.exists(paths.world))
        store.write(swapping)
        forward(paths, store, swapping)
    }

    /**
     * Recovers one session, following the dispatch order: `recoveryRequired`, then terminal status,
     * then the swap truth table for non-terminal sessions.
     */
    fun recover(
        claim: ImportExecutionRegistry.ImportClaim,
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
    ): Result<RecoveryOutcome> = swapResult {
        require(claim.isActive) { "恢复任务未持有执行占用" }
        val journal = store.read()
        when {
            journal.recoveryRequired -> RecoveryOutcome.NotifyOnly
            journal.status.isTerminal || journal.swapPhase == SwapPhase.Resolved -> {
                deleteIfExists(paths.stage)
                deleteIfExists(paths.old)
                RecoveryOutcome.TerminalCleaned
            }
            journal.status == ImportJournalStatus.Uploading -> RecoveryOutcome.NotStarted
            else -> recoverActive(paths, store, journal)
        }
    }

    /**
     * Closes an import that needed manual recovery, after an administrator fixed the files. Writes the
     * final `Resolved` phase, which recovery treats as terminal and never replays.
     */
    fun resolve(
        claim: ImportExecutionRegistry.ImportClaim,
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        resolution: ImportResolution,
        resolvedBy: String,
        resolvedAt: Long,
        allowEmptyWorld: Boolean,
    ): Result<HostWorldImportJournal> = swapResult {
        require(claim.isActive) { "管理员处理未持有执行占用" }
        val journal = store.read()
        check(journal.recoveryRequired) { "该存档导入不需要人工处理" }
        check(allowEmptyWorld || fs.exists(paths.world)) { "房间世界目录不存在: ${paths.world}" }
        val resolved = journal.copy(
            swapPhase = SwapPhase.Resolved,
            status = if (resolution == ImportResolution.KeptImportedWorld) ImportJournalStatus.Ready else ImportJournalStatus.Failed,
            recoveryRequired = false,
            resolution = resolution,
            resolvedBy = resolvedBy,
            resolvedAt = resolvedAt,
        )
        store.write(resolved)
        resolved
    }

    private fun recoverActive(
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        journal: HostWorldImportJournal,
    ): RecoveryOutcome {
        val state = observe(paths)
        return when (journal.swapPhase) {
            SwapPhase.None -> {
                if (state.old) {
                    RecoveryOutcome.Continued(requireRecovery(paths, store, journal, "未开始替换但旧世界临时目录存在: ${state}"))
                } else {
                    deleteIfExists(paths.stage)
                    RecoveryOutcome.Requeue
                }
            }
            SwapPhase.Swapping -> RecoveryOutcome.Continued(forward(paths, store, journal))
            SwapPhase.RollingBack -> RecoveryOutcome.Continued(continueRollback(paths, store, journal, null))
            SwapPhase.RolledBack -> {
                val consistent = !state.old && state.world == journal.hadOldWorld
                RecoveryOutcome.Continued(
                    if (consistent) {
                        finishRolledBack(paths, store, journal, null)
                    } else {
                        requireRecovery(paths, store, journal, "回滚已完成但文件状态不一致: ${state}")
                    },
                )
            }
            SwapPhase.Committed -> {
                if (!state.stage && state.world) {
                    RecoveryOutcome.PostCommitPending
                } else {
                    RecoveryOutcome.Continued(requireRecovery(paths, store, journal, "已提交但文件状态不一致: ${state}"))
                }
            }
            SwapPhase.Resolved -> error("Resolved is terminal and handled before")
        }
    }

    /** Continues a `Swapping` journal: S2 and S3 as needed, then S4. */
    private fun forward(
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        journal: HostWorldImportJournal,
    ): SwapOutcome {
        val state = observe(paths)
        val step = when {
            journal.hadOldWorld && state.matches(stage = true, world = true, old = false) -> ForwardStep.FromS2
            journal.hadOldWorld && state.matches(stage = true, world = false, old = true) -> ForwardStep.FromS3
            journal.hadOldWorld && state.matches(stage = false, world = true, old = true) -> ForwardStep.Done
            !journal.hadOldWorld && state.matches(stage = true, world = false, old = false) -> ForwardStep.FromS3
            !journal.hadOldWorld && state.matches(stage = false, world = true, old = false) -> ForwardStep.Done
            else -> return requireRecovery(paths, store, journal, "替换世界时文件状态不一致: ${state}")
        }
        try {
            if (step == ForwardStep.FromS2) fs.moveAtomically(paths.world, paths.old)
            if (step != ForwardStep.Done) fs.moveAtomically(paths.stage, paths.world)
        } catch (error: Exception) {
            logger.warn(error) { "替换房间世界失败，开始回滚: ${paths.world}" }
            return rollback(paths, store, journal, error)
        }
        store.write(journal.copy(swapPhase = SwapPhase.Committed))
        return SwapOutcome.Committed
    }

    private fun rollback(
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        journal: HostWorldImportJournal,
        cause: Throwable,
    ): SwapOutcome {
        val rollingBack = journal.copy(swapPhase = SwapPhase.RollingBack)
        store.write(rollingBack)
        return continueRollback(paths, store, rollingBack, cause)
    }

    /** Continues a `RollingBack` journal: R2 and R3 as needed, then R4–R6. */
    private fun continueRollback(
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        journal: HostWorldImportJournal,
        cause: Throwable?,
    ): SwapOutcome {
        val state = observe(paths)
        val step = when {
            journal.hadOldWorld && state.matches(stage = false, world = true, old = true) -> RollbackStep.FromR2
            journal.hadOldWorld && state.matches(stage = true, world = false, old = true) -> RollbackStep.FromR3
            journal.hadOldWorld && state.matches(stage = true, world = true, old = false) -> RollbackStep.Done
            !journal.hadOldWorld && state.matches(stage = false, world = true, old = false) -> RollbackStep.FromR2
            !journal.hadOldWorld && state.matches(stage = true, world = false, old = false) -> RollbackStep.Done
            else -> return requireRecovery(paths, store, journal, "回滚时文件状态不一致: ${state}")
        }
        try {
            if (step == RollbackStep.FromR2) fs.moveAtomically(paths.world, paths.stage)
            if (step != RollbackStep.Done && journal.hadOldWorld) fs.moveAtomically(paths.old, paths.world)
        } catch (error: Exception) {
            return requireRecovery(paths, store, journal, "回滚失败: ${error.message}", error)
        }
        val rolledBack = journal.copy(swapPhase = SwapPhase.RolledBack)
        store.write(rolledBack)
        return finishRolledBack(paths, store, rolledBack, cause)
    }

    /** R5 and R6. A failed stage delete is retried by terminal cleanup later. */
    private fun finishRolledBack(
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        journal: HostWorldImportJournal,
        cause: Throwable?,
    ): SwapOutcome {
        try {
            deleteIfExists(paths.stage)
        } catch (error: Exception) {
            logger.error(error) { "清理存档导入临时目录失败，将在恢复时重试: ${paths.stage}" }
        }
        store.write(journal.copy(status = ImportJournalStatus.Failed, failureMessage = ROLLED_BACK_MESSAGE, notified = false))
        return SwapOutcome.RolledBack(cause)
    }

    private fun requireRecovery(
        paths: WorldSwapPaths,
        store: HostWorldImportJournalStore,
        journal: HostWorldImportJournal,
        reason: String,
        cause: Throwable? = null,
    ): SwapOutcome {
        logger.error(cause) {
            "存档导入需要人工处理: ${reason}; world=${paths.world} stage=${paths.stage} old=${paths.old} ${observe(paths)}"
        }
        store.write(
            journal.copy(
                status = ImportJournalStatus.Failed,
                failureMessage = RECOVERY_REQUIRED_MESSAGE,
                notified = false,
                recoveryRequired = true,
                recoveryReason = reason,
            ),
        )
        return SwapOutcome.RecoveryRequired(reason)
    }

    private fun deleteIfExists(path: Path) {
        if (fs.exists(path)) fs.deleteRecursively(path)
    }

    private fun observe(paths: WorldSwapPaths) = FsState(fs.exists(paths.stage), fs.exists(paths.world), fs.exists(paths.old))

    private data class FsState(val stage: Boolean, val world: Boolean, val old: Boolean) {
        fun matches(stage: Boolean, world: Boolean, old: Boolean) = this.stage == stage && this.world == world && this.old == old

        override fun toString() = "stage=${stage}, world=${world}, old=${old}"
    }

    private enum class ForwardStep { FromS2, FromS3, Done }

    private enum class RollbackStep { FromR2, FromR3, Done }

    /** Like [runCatching], but lets cancellation propagate. */
    private inline fun <T> swapResult(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    companion object {
        const val ROLLED_BACK_MESSAGE = "存档导入失败，房间原有世界未改变"
        const val RECOVERY_REQUIRED_MESSAGE = "存档导入中断，请联系管理员"

        private val logger = KotlinLogging.logger {}
    }
}
