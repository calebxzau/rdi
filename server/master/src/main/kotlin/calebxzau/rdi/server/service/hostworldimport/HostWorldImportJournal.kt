package calebxzau.rdi.server.service.hostworldimport

import kotlinx.serialization.Serializable

/** Server-side lifecycle of one host world import session. */
@Serializable
enum class ImportJournalStatus {
    Uploading,
    Queued,
    Processing,
    Ready,
    Failed;

    val isTerminal: Boolean get() = this == Ready || this == Failed
}

/** Progress of the world swap. Each value is persisted before the filesystem step it guards. */
@Serializable
enum class SwapPhase {
    None,
    Swapping,
    Committed,
    RollingBack,
    RolledBack,

    /** An administrator closed an import that needed manual recovery. Never replayed. */
    Resolved,
}

/** What an administrator did with an import that needed manual recovery. */
@Serializable
enum class ImportResolution {
    KeptImportedWorld,
    RestoredOldWorld,
    Other,
}

/**
 * The persisted state of one host world import. The swap fields drive [HostWorldSwap]; the
 * post-commit flags and [notified] are maintained by the import service.
 */
@Serializable
data class HostWorldImportJournal(
    val status: ImportJournalStatus,
    val swapPhase: SwapPhase = SwapPhase.None,
    val hadOldWorld: Boolean = false,
    val gameRulesCleared: Boolean = false,
    val sizeUpdated: Boolean = false,
    val membersAdded: Boolean = false,
    val oldWorldDeleted: Boolean = false,
    val notified: Boolean = false,
    val failureMessage: String? = null,
    val recoveryRequired: Boolean = false,
    val recoveryReason: String? = null,
    val resolution: ImportResolution? = null,
    val resolvedBy: String? = null,
    val resolvedAt: Long? = null,
) {
    /**
     * Whether this import blocks start, restart, reset-world, delete, change-version, game-rule
     * changes and new imports on its host.
     */
    val blocksHost: Boolean
        get() = recoveryRequired || status == ImportJournalStatus.Queued || status == ImportJournalStatus.Processing
}
