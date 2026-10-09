package calebxzau.rdi.mc.client.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewExportRequestsTest {
    @Test
    fun resourcesAndLoginWithoutManualRequestNeverStart(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(clientReady = true))
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun loginBeforeResourcesStaysIdleWhenResourcesBecomeReady(): Unit {
        val requests = PreviewExportRequests()
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(false))
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun duplicateLoginAndReadinessTogglesDoNotRetrigger(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
        requests.worldEntered()
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun reconnectAndIdleReloadNeverStartAnExport(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(true))
        requests.worldLeft()
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun forceQueuesDuringLoadingAndCoalesces(): Unit {
        val requests = PreviewExportRequests()
        requests.forceRequested()
        requests.forceRequested()
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(clientReady = true))
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(clientReady = false))
        val fresh = assertNotNull(requests.consumeStartIfReady(true))
        assertEquals(PreviewExportRequests.StartKind.FreshExport, fresh.kind)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun staleReuseCompletionCannotMutatePendingManualExport(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val staleEpoch = requests.snapshot().epoch - 1
        requests.forceRequested()
        requests.reusableCompleted(staleEpoch, found = true)
        val fresh = assertNotNull(requests.consumeStartIfReady(true))
        assertEquals(PreviewExportRequests.StartKind.FreshExport, fresh.kind)
    }

    @Test
    fun reloadPreservesPendingKindButIdleReloadDoesNotStartWork(): Unit {
        val idle = PreviewExportRequests()
        idle.initialResourcesReady()
        idle.reloadStarted()
        idle.reloadFinished(success = true)
        assertNull(idle.consumeStartIfReady(true))

        val requests = PreviewExportRequests()
        requests.worldEntered()
        requests.forceRequested()
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertEquals(PreviewExportRequests.StartKind.FreshExport, requests.consumeStartIfReady(true)?.kind)
    }

    @Test
    fun reloadPreservesInFlightFreshAndFailureClearsIt(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        requests.forceRequested()
        val fresh = assertNotNull(requests.consumeStartIfReady(true))
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertEquals(PreviewExportRequests.StartKind.FreshExport, requests.consumeStartIfReady(true)?.kind)
        assertEquals(PreviewExportRequests.StartKind.FreshExport, requests.snapshot().inFlight)
        assertFalse(requests.snapshot().forcePending)

        requests.reloadStarted()
        requests.reloadFinished(success = false)
        assertFalse(requests.snapshot().forcePending)
        assertNull(requests.consumeStartIfReady(true))
        assertTrue(fresh.epoch < requests.snapshot().epoch)
    }

    @Test
    fun logoutCancelsManualRequestAndStaleReuseCannotStartRelogin(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        requests.forceRequested()
        val oldEpoch = requests.snapshot().epoch
        requests.worldLeft()
        assertNull(requests.consumeStartIfReady(true))
        requests.worldEntered()
        requests.reusableCompleted(oldEpoch, found = false)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun logoutCancelsForceAndReconnectsIdle(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        requests.forceRequested()
        requests.worldLeft()
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun freshSettledClearsActiveAndPendingWork(): Unit {
        val requests = PreviewExportRequests()
        requests.forceRequested()
        requests.freshExportSettled()
        assertFalse(requests.snapshot().forcePending)
        assertNull(requests.snapshot().inFlight)
    }

    @Test
    fun idleReloadAfterManualExportDoesNotRestart(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        requests.forceRequested()
        assertNotNull(requests.consumeStartIfReady(true))
        requests.freshExportSettled()

        requests.reloadStarted()
        requests.reloadFinished(success = true)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun forceDuringReloadWaitsForResources(): Unit {
        val requests = PreviewExportRequests()
        requests.worldEntered()
        requests.reloadStarted()
        requests.forceRequested()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertEquals(PreviewExportRequests.StartKind.FreshExport, requests.consumeStartIfReady(true)?.kind)
    }

    @Test
    fun reloadResumesPendingManualExport(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        requests.forceRequested()
        val requestedEpoch = requests.snapshot().epoch
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        val resumed = assertNotNull(requests.consumeStartIfReady(true))
        assertEquals(PreviewExportRequests.StartKind.FreshExport, resumed.kind)
        assertTrue(resumed.epoch > requestedEpoch)
    }

    @Test
    fun reloadFinishingAfterLogoutDoesNotRestart(): Unit {
        val requests = PreviewExportRequests()
        requests.worldEntered()
        requests.reloadStarted()
        requests.worldLeft()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(true))
    }
}
