package calebxzau.rdi.mc.client.preview

/** Render-thread request bookkeeping for login, reload, and manual export races. */
internal class PreviewExportRequests {
    data class Snapshot(
        val epoch: Long,
        val resourcesReady: Boolean,
        val forcePending: Boolean,
        val reloadInProgress: Boolean,
        val worldJoined: Boolean,
        val inFlight: StartKind?
    )

    enum class StartKind { ReuseCheck, FreshExport }
    data class Start(val epoch: Long, val kind: StartKind)

    private var epoch = 0L
    private var resourcesReady = false
    private var reloadInProgress = false
    private var worldJoined = false
    private var pendingKind: StartKind? = null
    private var inFlightKind: StartKind? = null

    fun snapshot(): Snapshot = Snapshot(
        epoch, resourcesReady, pendingKind == StartKind.FreshExport,
        reloadInProgress, worldJoined, inFlightKind
    )

    fun worldEntered(): Snapshot {
        if (worldJoined) return snapshot()
        worldJoined = true
        epoch++
        if (pendingKind == null) pendingKind = StartKind.ReuseCheck
        return snapshot()
    }

    fun worldLeft(): Snapshot {
        epoch++
        worldJoined = false
        pendingKind = null
        inFlightKind = null
        return snapshot()
    }

    fun forceRequested(): Snapshot {
        epoch++
        pendingKind = StartKind.FreshExport
        inFlightKind = null
        return snapshot()
    }

    fun reloadStarted(): Snapshot {
        epoch++
        resourcesReady = false
        reloadInProgress = true
        if (inFlightKind != null && pendingKind == null) pendingKind = inFlightKind
        inFlightKind = null
        return snapshot()
    }

    fun reloadFinished(success: Boolean): Snapshot {
        reloadInProgress = false
        resourcesReady = success
        if (!success) {
            epoch++
            pendingKind = null
            inFlightKind = null
        }
        return snapshot()
    }

    fun initialResourcesReady(): Snapshot {
        resourcesReady = true
        return snapshot()
    }

    fun consumeStartIfReady(clientReady: Boolean): Start? {
        if (!clientReady || !worldJoined || !resourcesReady || reloadInProgress || inFlightKind != null) return null
        val kind = pendingKind ?: return null
        pendingKind = null
        inFlightKind = kind
        return Start(epoch, kind)
    }

    fun reusableCompleted(startEpoch: Long, found: Boolean): Snapshot {
        if (startEpoch != epoch || !worldJoined || !resourcesReady || reloadInProgress || inFlightKind != StartKind.ReuseCheck) {
            return snapshot()
        }
        inFlightKind = null
        if (!found) pendingKind = StartKind.FreshExport
        return snapshot()
    }

    fun freshExportSettled(): Snapshot {
        pendingKind = null
        inFlightKind = null
        return snapshot()
    }
}
