package calebxzau.rdi.mc.client.syncchunk
import calebxzau.rdi.mc.syncchunk.SyncChunkKey

import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmSyncChunkSessionControllerTest {
    private val hostId = UUID.fromString("018f0000-0000-7000-8000-000000000001")
    private val sessionId = UUID.fromString("018f0000-0000-7000-8000-000000000002")
    private val playerId = UUID.fromString("018f0000-0000-7000-8000-000000000003")
    private val key = SyncChunkKey("minecraft:overworld", -3, 8)

    @Test
    fun doesNotChangeConfirmedSnapshotBeforeMasterAcknowledges(): Unit {
        val fixture = Fixture()
        fixture.transport.getResults += Result.success(snapshot(0))
        fixture.beginAndLoad()
        fixture.transport.addResults += Result.success(
            mutation(1, DmSyncChunkMutation.Outcome.Added, listOf(entry())),
        )

        assertTrue(fixture.controller.request(operation()))
        assertEquals(0L, fixture.controller.currentSnapshot()?.revision)
        assertTrue(fixture.listener.accepted.isEmpty())

        fixture.background.runNext()
        assertEquals(1L, fixture.controller.currentSnapshot()?.revision)
        assertEquals(1, fixture.listener.accepted.size)
    }

    @Test
    fun sendsQueuedOperationsWithSequentialConfirmedRevisions(): Unit {
        val fixture = Fixture()
        val secondKey = SyncChunkKey("minecraft:the_nether", 1, 3)
        fixture.transport.getResults += Result.success(snapshot(7))
        fixture.beginAndLoad()
        fixture.transport.addResults += Result.success(
            mutation(8, DmSyncChunkMutation.Outcome.Added, listOf(entry())),
        )
        fixture.transport.addResults += Result.success(
            mutation(
                9,
                DmSyncChunkMutation.Outcome.Added,
                listOf(entry(), DmSyncChunkEntry(secondKey, playerId)),
            ),
        )

        fixture.controller.request(operation())
        fixture.controller.request(operation(secondKey))
        fixture.background.runNext()
        fixture.background.runNext()

        assertEquals(listOf(7L, 8L), fixture.transport.addRevisions)
        assertEquals(9L, fixture.controller.currentSnapshot()?.revision)
    }

    @Test
    fun retriesTheFrozenRequestWhenReconciliationKeepsTheSameRevision(): Unit {
        val fixture = Fixture()
        fixture.transport.getResults += Result.success(snapshot(4))
        fixture.beginAndLoad()
        fixture.transport.addResults += Result.failure(IOException("response lost"))
        fixture.transport.getResults += Result.success(snapshot(4))
        fixture.transport.addResults += Result.success(
            mutation(5, DmSyncChunkMutation.Outcome.Added, listOf(entry())),
        )

        fixture.controller.request(operation())
        fixture.background.runNext()
        fixture.background.runNext()
        fixture.background.runNext()

        assertEquals(listOf(4L, 4L), fixture.transport.addRevisions)
        assertEquals(5L, fixture.controller.currentSnapshot()?.revision)
        assertEquals(1, fixture.listener.accepted.size)
    }

    @Test
    fun advancedRevisionReconcilesWithoutReplayingTheOperation(): Unit {
        val fixture = Fixture()
        fixture.transport.getResults += Result.success(snapshot(10))
        fixture.beginAndLoad()
        fixture.transport.addResults += Result.failure(IOException("response lost"))
        fixture.transport.getResults += Result.success(snapshot(11, listOf(entry())))

        fixture.controller.request(operation())
        fixture.background.runNext()
        fixture.background.runNext()

        assertEquals(listOf(10L), fixture.transport.addRevisions)
        assertEquals(listOf(true), fixture.listener.reconciled)
        assertEquals(11L, fixture.controller.currentSnapshot()?.revision)
    }

    @Test
    fun revisionConflictReloadsButNeverRebasesTheRejectedOperation(): Unit {
        val fixture = Fixture()
        fixture.transport.getResults += Result.success(snapshot(2))
        fixture.beginAndLoad()
        fixture.transport.addResults += Result.failure(
            SyncChunkRequestError("RevisionConflict", "conflict"),
        )
        fixture.transport.getResults += Result.success(snapshot(3))

        fixture.controller.request(operation())
        fixture.background.runNext()
        fixture.background.runNext()

        assertEquals(listOf(2L), fixture.transport.addRevisions)
        assertEquals(3L, fixture.controller.currentSnapshot()?.revision)
        assertTrue(fixture.listener.failed.any { it.contains("操作未执行") })
    }

    @Test
    fun pauseInvalidatesLateCallbacksAndRejectsNewOperations(): Unit {
        val fixture = Fixture()
        fixture.transport.getResults += Result.success(snapshot(0))
        fixture.controller.begin(hostId, sessionId)
        fixture.controller.pause("disconnected")

        fixture.background.runNext()

        assertNull(fixture.controller.currentSnapshot())
        assertFalse(fixture.controller.canEdit())
        assertFalse(fixture.controller.request(operation()))
    }

    @Test
    fun initialSessionUnavailableIsRetriedBecauseGatewayPresenceCanLagReady(): Unit {
        val fixture = Fixture()
        fixture.transport.getResults += Result.failure(
            SyncChunkRequestError("SessionUnavailable", "presence pending"),
        )
        fixture.transport.getResults += Result.success(snapshot(0))

        fixture.controller.begin(hostId, sessionId)
        fixture.background.runNext()
        assertFalse(fixture.controller.canEdit())
        assertEquals(1, fixture.scheduled.size)

        fixture.scheduled.removeFirst().run()
        fixture.background.runNext()
        assertTrue(fixture.controller.canEdit())
    }

    @Test
    fun snapshotDefensivelyCopiesEntries(): Unit {
        val entries = mutableListOf(entry())
        val snapshot = snapshot(3, entries)

        entries.clear()

        assertEquals(1, snapshot.chunks.size)
    }

    private fun operation(
        target: SyncChunkKey = key,
    ) = DmSyncChunkOperation(DmSyncChunkOperationKind.Add, playerId, target)

    private fun entry() = DmSyncChunkEntry(key, playerId)

    private fun snapshot(
        revision: Long,
        entries: List<DmSyncChunkEntry> = emptyList(),
    ) = DmSyncChunkSnapshot(revision, entries, DmSyncChunkLimits(256))

    private fun mutation(
        revision: Long,
        outcome: DmSyncChunkMutation.Outcome,
        entries: List<DmSyncChunkEntry>,
    ) = DmSyncChunkMutation(snapshot(revision, entries), outcome)

    private inner class Fixture {
        val background = HoldingExecutor()
        val transport = FakeTransport()
        val listener = RecordingListener()
        val scheduled = ArrayDeque<Runnable>()
        val controller = DmSyncChunkSessionController(
            transport = transport,
            background = background,
            retryLater = { scheduled += it },
            dispatch = { it.run() },
            listener = listener,
        )

        fun beginAndLoad() {
            controller.begin(hostId, sessionId)
            background.runNext()
            assertTrue(controller.canEdit())
        }
    }

    private class HoldingExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private class FakeTransport : DmSyncChunkSessionController.Transport {
        val getResults = ArrayDeque<Result<DmSyncChunkSnapshot>>()
        val addResults = ArrayDeque<Result<DmSyncChunkMutation>>()
        val removeResults = ArrayDeque<Result<DmSyncChunkMutation>>()
        val addRevisions = mutableListOf<Long>()

        override fun get(hostId: UUID, sessionId: UUID): Result<DmSyncChunkSnapshot> =
            getResults.removeFirst()

        override fun add(
            hostId: UUID,
            sessionId: UUID,
            revision: Long,
            playerId: UUID,
            chunk: SyncChunkKey,
        ): Result<DmSyncChunkMutation> {
            addRevisions += revision
            return addResults.removeFirst()
        }

        override fun remove(
            hostId: UUID,
            sessionId: UUID,
            revision: Long,
            playerId: UUID,
            chunk: SyncChunkKey,
        ): Result<DmSyncChunkMutation> = removeResults.removeFirst()
    }

    private class RecordingListener : DmSyncChunkSessionController.Listener {
        val accepted = mutableListOf<DmSyncChunkOperation>()
        val failed = mutableListOf<String>()
        val reconciled = mutableListOf<Boolean>()

        override fun onSnapshot(snapshot: DmSyncChunkSnapshot, loading: Boolean) = Unit

        override fun onOperationAccepted(
            operation: DmSyncChunkOperation,
            mutation: DmSyncChunkMutation,
        ) {
            accepted += operation
        }

        override fun onOperationFailed(operation: DmSyncChunkOperation, message: String) {
            failed += message
        }

        override fun onOperationReconciled(operation: DmSyncChunkOperation, achieved: Boolean) {
            reconciled += achieved
        }

        override fun onUnavailable(message: String) = Unit
    }
}
