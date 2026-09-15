package calebxzau.rdi.mc.client.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewExportRequestsTest {
    @Test
    fun resourcesWithoutLoginNeverStart(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(clientReady = true))
        requests.worldEntered()
        assertEquals(PreviewExportRequests.StartKind.ReuseCheck, requests.consumeStartIfReady(true)?.kind)
    }

    @Test
    fun loginBeforeResourcesWaitsUntilBothAreReady(): Unit {
        val requests = PreviewExportRequests()
        requests.worldEntered()
        assertNull(requests.consumeStartIfReady(true))
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(false))
        assertEquals(PreviewExportRequests.StartKind.ReuseCheck, requests.consumeStartIfReady(true)?.kind)
    }

    @Test
    fun duplicateLoginAndReadinessTogglesDoNotRetrigger(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val first = assertNotNull(requests.consumeStartIfReady(true))
        requests.worldEntered()
        requests.initialResourcesReady()
        assertNull(requests.consumeStartIfReady(true))
        requests.reusableCompleted(first.epoch, found = true)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun reusableHitEndsWorkAndMissSchedulesOneFreshExport(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val reuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.reusableCompleted(reuse.epoch, found = true)
        assertNull(requests.consumeStartIfReady(true))

        requests.worldLeft()
        requests.worldEntered()
        val secondReuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.reusableCompleted(secondReuse.epoch, found = false)
        val fresh = assertNotNull(requests.consumeStartIfReady(true))
        assertEquals(PreviewExportRequests.StartKind.FreshExport, fresh.kind)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun forceQueuesDuringLoadingAndCoalesces(): Unit {
        val requests = PreviewExportRequests()
        requests.forceRequested()
        requests.forceRequested()
        requests.initialResourcesReady()
        requests.worldEntered()
        val fresh = assertNotNull(requests.consumeStartIfReady(true))
        assertEquals(PreviewExportRequests.StartKind.FreshExport, fresh.kind)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun forceReplacesReuseAndStaleReuseCannotMutateIt(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val reuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.forceRequested()
        requests.reusableCompleted(reuse.epoch, found = true)
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
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertEquals(PreviewExportRequests.StartKind.ReuseCheck, requests.consumeStartIfReady(true)?.kind)
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
    fun logoutCancelsPendingAndStaleCompletionCannotAffectRelogin(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val oldReuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.worldLeft()
        assertNull(requests.consumeStartIfReady(true))
        requests.worldEntered()
        val newReuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.reusableCompleted(oldReuse.epoch, found = false)
        assertEquals(PreviewExportRequests.StartKind.ReuseCheck, requests.snapshot().inFlight)
        requests.reusableCompleted(newReuse.epoch, found = true)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun logoutCancelsForceAndReconnectsWithReuseCheck(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        requests.forceRequested()
        requests.worldLeft()
        requests.worldEntered()
        assertEquals(PreviewExportRequests.StartKind.ReuseCheck, requests.consumeStartIfReady(true)?.kind)
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
    fun idleReloadAfterReusableHitDoesNotRestart(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val reuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.reusableCompleted(reuse.epoch, found = true)
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        assertNull(requests.consumeStartIfReady(true))
    }

    @Test
    fun forceDuringReloadWinsOverPendingReuse(): Unit {
        val requests = PreviewExportRequests()
        requests.worldEntered()
        requests.reloadStarted()
        requests.forceRequested()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        assertEquals(PreviewExportRequests.StartKind.FreshExport, requests.consumeStartIfReady(true)?.kind)
    }

    @Test
    fun reloadInterruptingReusePreservesReuseCheck(): Unit {
        val requests = PreviewExportRequests()
        requests.initialResourcesReady()
        requests.worldEntered()
        val reuse = assertNotNull(requests.consumeStartIfReady(true))
        requests.reloadStarted()
        requests.reloadFinished(success = true)
        requests.initialResourcesReady()
        val resumed = assertNotNull(requests.consumeStartIfReady(true))
        assertEquals(PreviewExportRequests.StartKind.ReuseCheck, resumed.kind)
        assertTrue(resumed.epoch > reuse.epoch)
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
