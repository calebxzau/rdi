@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DmCheckpointUploadStateTest {
    @Test
    fun interruptedUploadRetriesTheSameArchiveAndThenAdvances() = withFixture {
        val first = prepare()
        val bytes = Files.readAllBytes(first.archive.path)
        uploadFailure = IOException("request body interrupted")
        repeat(3) { assertTrue(resolve().isFailure) }
        assertSame(first, state.pending)
        assertEquals(listOf(1L, 1L, 1L), attempts.map { it.metadata.sequence })
        assertTrue(attempts.all { it === first.archive })
        assertContentEquals(bytes, Files.readAllBytes(first.archive.path))
        assertEquals(0L, remote.appliedSequence)

        uploadFailure = null
        assertSame(first, resolve().getOrThrow())
        assertEquals(first.archive.metadata.cycleId, confirmed)
        assertNull(state.pending)
        assertEquals(2L, prepare().archive.metadata.sequence)
    }

    @Test
    fun aLostSuccessResponseIsConfirmedWithoutUploadingAgain() = withFixture {
        val first = prepare()
        loseResponse = true
        assertTrue(resolve().isFailure)
        assertEquals(1L, remote.appliedSequence)
        assertSame(first, state.pending)
        assertNull(confirmed)

        assertSame(first, resolve().getOrThrow())
        assertEquals(1, attempts.size)
        assertEquals(first.archive.metadata.cycleId, confirmed)
        assertEquals(2L, prepare().archive.metadata.sequence)
    }

    @Test
    fun failedStatusLookupNeitherUploadsNorReplacesThePendingCycle() = withFixture {
        val first = prepare()
        statusFailure = IOException("master unavailable")
        repeat(3) { assertTrue(resolve().isFailure) }
        assertTrue(attempts.isEmpty())
        assertNull(marked)
        assertSame(first, state.pending)
        assertTrue(state.prepare(remote, root, {}) { error("must not build") }.isFailure)

        statusFailure = null
        assertSame(first, resolve().getOrThrow())
    }

    @Test
    fun failureBeforeSendingPreservesSequenceAndDoesNotUpload() = withFixture {
        val first = prepare()
        markFailure = IOException("cannot write host.json")
        assertTrue(resolve().isFailure)
        assertTrue(attempts.isEmpty())
        assertSame(first, state.pending)
        markFailure = null
        resolve().getOrThrow()
        assertEquals(listOf(1L), attempts.map { it.metadata.sequence })
    }

    @Test
    fun failedLocalConfirmationDoesNotForgetTheCommittedCycle() = withFixture {
        val first = prepare()
        confirmFailure = IOException("cannot confirm host.json")
        assertTrue(resolve().isFailure)
        assertSame(first, state.pending)
        assertEquals(first.archive.metadata.cycleId, marked)
        confirmFailure = null
        assertSame(first, resolve().getOrThrow())
        assertEquals(1, attempts.size)
    }

    @Test
    fun anUnappliedReceiptCannotAdvanceTheSequence() = withFixture {
        val first = prepare()
        applyUpload = false
        assertTrue(resolve().isFailure)
        assertEquals(1L, remote.lastUploadSequence)
        assertEquals(0L, remote.appliedSequence)
        assertSame(first, state.pending)
        assertNull(confirmed)
        assertTrue(resolve().isFailure)
        assertEquals(1, attempts.size)
        remote = remote.copy(appliedSequence = 1)
        assertSame(first, resolve().getOrThrow())
    }

    @Test
    fun aMismatchedUploadReceiptIsNotAcknowledged() = withFixture {
        val first = prepare()
        receiptTransform = { it.copy(cycleId = v7()) }
        assertTrue(resolve().isFailure)
        assertSame(first, state.pending)
        assertNull(confirmed)
        // A later authoritative status can still resolve the ambiguous response.
        assertSame(first, resolve().getOrThrow())
        assertEquals(1, attempts.size)
    }

    @Test
    fun statusMustMatchSessionCycleHashAndLength() = withFixture {
        val first = prepare()
        val committed = committedStatus(first.archive)
        val mismatches = listOf(
            committed.copy(lastUploadSessionId = v7()),
            committed.copy(lastUploadCycleId = v7()),
            committed.copy(lastUploadSha1 = "0".repeat(40)),
            committed.copy(lastUploadZipBytes = first.archive.bytes + 1),
            committed.copy(lastUploadSequence = 2, appliedSequence = 2),
        )
        for (mismatch in mismatches) {
            remote = mismatch
            assertTrue(resolve().isFailure)
            assertSame(first, state.pending)
            assertNull(confirmed)
        }
        assertTrue(attempts.isEmpty())
        remote = committed
        assertSame(first, resolve().getOrThrow())
    }

    @Test
    fun cancellationBeforeUploadKeepsTheArchiveUntilItsOwnerDiscardsIt() = withFixture {
        val first = prepare()
        cancelled = true
        assertTrue(resolve().isFailure)
        assertTrue(attempts.isEmpty())
        assertSame(first, state.pending)
        assertTrue(Files.exists(first.archive.path))
        assertSame(first, state.discard())
        assertNull(state.pending)
    }

    @Test
    fun cancellationDuringUploadDoesNotConfirmOrStartAnotherUpload() = withFixture {
        val first = prepare()
        afterUpload = { cancelled = true }
        assertTrue(resolve().isFailure)
        assertEquals(1L, remote.appliedSequence)
        assertNull(confirmed)
        assertSame(first, state.pending)
        assertTrue(resolve().isFailure)
        assertEquals(1, attempts.size)
    }

    @Test
    fun failedArchiveBuildRestoresDrainedChangesWithoutReplacingNewerWrites() = withFixture {
        DmRegionChangeLog.activate(root)
        DmRegionChangeLog.setKeep(setOf(SyncChunkKey("minecraft:overworld", 1, 0), SyncChunkKey("minecraft:overworld", 2, 0)))
        DmRegionChangeLog.releaseReconcile()
        DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 1, 1, byteArrayOf(1))
        DmRegionChangeLog.recordWrite("region/r.0.0.mca", 2, 1, 1, byteArrayOf(2))
        var drained = emptyList<DmRegionChange>()
        try {
            val failed = state.prepare(remote, root, { DmRegionChangeLog.mergeBack(drained) }) { sequence ->
                assertEquals(1L, sequence)
                drained = DmRegionChangeLog.drain()
                DmRegionChangeLog.recordWrite("region/r.0.0.mca", 1, 2, 1, byteArrayOf(3))
                // Exercise the real archive writer's failure path, after taking the change log.
                DmWorldCheckpointArchive.write(
                    request(sequence, root, listOf("missing.dat")), root.resolve("failed.zip"),
                ).getOrThrow()
            }
            assertTrue(failed.isFailure)
            assertNull(state.pending)
            val restored = DmRegionChangeLog.drain().associateBy { it.key.index }
            assertContentEquals(byteArrayOf(3), restored[1]?.bytes)
            assertContentEquals(byteArrayOf(2), restored[2]?.bytes)
            assertEquals(1L, prepare().archive.metadata.sequence)
        } finally {
            DmRegionChangeLog.discard()
        }
    }

    @Test
    fun finalSaveSettlesTheOldUploadThenSendsFreshWorldData() = withFixture {
        val old = prepare("periodic save")
        uploadFailure = IOException("temporary rejection")
        assertTrue(resolve().isFailure)
        uploadFailure = null
        resolve().getOrThrow()
        val final = prepare("final save")
        assertEquals(2L, final.archive.metadata.sequence)
        assertFalse(old.archive.sha1 == final.archive.sha1)
        resolve().getOrThrow()
        assertEquals(listOf(1L, 1L, 2L), attempts.map { it.metadata.sequence })
        assertEquals(final.archive.metadata.cycleId, confirmed)
        assertEquals(2L, remote.appliedSequence)
    }

    @Test
    fun aNewSessionStartsAfterTheMastersAppliedSequence() = withFixture {
        remote = remote.copy(lastUploadSequence = 17, appliedSequence = 17, lastUploadSessionId = v7())
        assertEquals(18L, prepare().archive.metadata.sequence)
        resolve().getOrThrow()
        assertEquals(18L, remote.appliedSequence)
    }

    private class Fixture(val root: Path) {
        val hostId = v7()
        val sessionId = v7()
        val state = DmCheckpointUploadState(sessionId)
        var remote = DmWorldCheckpointStatus(null, null, null, 0, false,
            DmWorldCheckpointLimits(1000000, 1000000, 1000000, 1000000, 1000000, 1000, 1024, 32))
        val attempts = mutableListOf<DmWorldCheckpointArchive.Output>()
        var statusFailure: Exception? = null
        var uploadFailure: Exception? = null
        var markFailure: Exception? = null
        var confirmFailure: Exception? = null
        var loseResponse = false
        var applyUpload = true
        var cancelled = false
        var marked: UUID? = null
        var confirmed: UUID? = null
        var receiptTransform: (DmWorldCheckpointReceipt) -> DmWorldCheckpointReceipt = { it }
        var afterUpload: () -> Unit = {}

        fun request(sequence: Long, world: Path, files: List<String>) = DmWorldCheckpointArchive.Request(
            hostId, sessionId, v7(), sequence, !remote.baselineComplete,
            world, files, emptyList(), emptyList(), emptyList(),
        )

        fun prepare(contents: String = "world data"): DmCheckpointUploadState.Pending {
            val staging = Files.createTempDirectory(root, "checkpoint-")
            val world = Files.createDirectory(staging.resolve("world"))
            Files.writeString(world.resolve("level.dat"), contents)
            return state.prepare(remote, staging, {}) { sequence ->
                DmWorldCheckpointArchive.write(request(sequence, world, listOf("level.dat")), staging.resolve("checkpoint.zip")).getOrThrow()
            }.getOrThrow()
        }

        fun committedStatus(archive: DmWorldCheckpointArchive.Output) = remote.copy(
            lastUploadSessionId = sessionId,
            lastUploadSequence = archive.metadata.sequence,
            lastUploadCycleId = archive.metadata.cycleId,
            appliedSequence = if (applyUpload) archive.metadata.sequence else remote.appliedSequence,
            baselineComplete = true,
            lastUploadSha1 = archive.sha1,
            lastUploadZipBytes = archive.bytes,
        )

        fun resolve(): Result<DmCheckpointUploadState.Pending?> = state.resolve(
            readStatus = { runCatching { statusFailure?.let { throw it }; remote } },
            upload = { archive -> runCatching {
                assertNotNull(marked)
                assertEquals(archive.metadata.cycleId, marked)
                attempts += archive
                assertTrue(Files.isRegularFile(archive.path))
                uploadFailure?.let { throw it }
                assertEquals((remote.lastUploadSequence ?: 0) + 1, archive.metadata.sequence)
                remote = committedStatus(archive)
                afterUpload()
                if (loseResponse) throw IOException("success response lost")
                receiptTransform(DmWorldCheckpointReceipt(sessionId, archive.metadata.sequence, archive.metadata.cycleId,
                    archive.sha1, archive.bytes, true, archive.metadata.sequence))
            } },
            markPending = { cycle -> runCatching { markFailure?.let { throw it }; marked = cycle } },
            markConfirmed = { cycle -> runCatching { confirmFailure?.let { throw it }; confirmed = cycle } },
            cancelled = { cancelled },
        )
    }

    private fun withFixture(test: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("dm-checkpoint-upload-test-")
        try {
            Fixture(root).test()
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    companion object {
        private fun v7(): UUID = UUID.fromString(kotlin.uuid.Uuid.generateV7().toString())
    }
}
