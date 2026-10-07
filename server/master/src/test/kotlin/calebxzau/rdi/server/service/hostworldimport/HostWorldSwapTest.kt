package calebxzau.rdi.server.service.hostworldimport

import calebxzau.rdi.server.service.hostworldimport.HostWorldSwap.RecoveryOutcome
import calebxzau.rdi.server.service.hostworldimport.HostWorldSwap.SwapOutcome
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostWorldSwapTest {
    private val registry = ImportExecutionRegistry()

    /** Thrown instead of an operation to simulate the process dying right before it. */
    private class SimulatedCrash : Error("simulated crash")

    /** Counts mutating operations (moves, deletes, journal writes) and can crash before one of them. */
    private class Ops(private val crashAt: Int = -1) {
        var count = 0
        val log = ArrayList<String>()

        fun step(name: String) {
            if (count == crashAt) throw SimulatedCrash()
            count++
            log += name
        }
    }

    private class FaultyFs(
        private val ops: Ops,
        private val failMove: (Path, Path) -> Boolean = { _, _ -> false },
    ) : WorldSwapFileSystem {
        override fun exists(path: Path) = NioWorldSwapFileSystem.exists(path)

        override fun moveAtomically(source: Path, target: Path) {
            ops.step("move ${source.fileName} -> ${target.fileName}")
            if (failMove(source, target)) throw IOException("simulated move failure")
            NioWorldSwapFileSystem.moveAtomically(source, target)
        }

        override fun deleteRecursively(path: Path) {
            ops.step("delete ${path.fileName}")
            NioWorldSwapFileSystem.deleteRecursively(path)
        }
    }

    private class MemoryStore(
        var current: HostWorldImportJournal,
        private val ops: Ops = Ops(),
        private val failWrite: (HostWorldImportJournal) -> Boolean = { false },
    ) : HostWorldImportJournalStore {
        override fun read() = current

        override fun write(journal: HostWorldImportJournal) {
            ops.step("write ${journal.status}/${journal.swapPhase}")
            if (failWrite(journal)) throw IOException("simulated journal write failure")
            current = journal
        }
    }

    private class Host(val paths: WorldSwapPaths) {
        fun world(content: String?) = put(paths.world, content)
        fun stage(content: String?) = put(paths.stage, content)
        fun old(content: String?) = put(paths.old, content)

        fun content(path: Path): String? = path.resolve("marker").takeIf { it.exists() }?.readText()

        /** Directory name -> marker content, for checking that nothing was touched. */
        fun snapshot() = listOf(paths.world, paths.stage, paths.old).associate { it.fileName.toString() to content(it) }

        private fun put(path: Path, content: String?) {
            if (content == null) return
            path.createDirectories()
            path.resolve("marker").writeText(content)
        }
    }

    private fun newHost(): Host {
        val dir = Files.createTempDirectory("host-world-swap")
        return Host(WorldSwapPaths.forHost(dir, UUID.randomUUID()))
    }

    private fun processing() = HostWorldImportJournal(status = ImportJournalStatus.Processing)

    private fun claim() = registry.tryClaim(UUID.randomUUID())!!

    @Test
    fun `swap with an old world commits and keeps the old world aside`() {
        val host = newHost().apply { world("old"); stage("new") }
        val store = MemoryStore(processing())

        val outcome = HostWorldSwap().swap(claim(), host.paths, store).getOrThrow()

        assertEquals(SwapOutcome.Committed, outcome)
        assertEquals("new", host.content(host.paths.world))
        assertEquals("old", host.content(host.paths.old))
        assertFalse(host.paths.stage.exists())
        assertEquals(SwapPhase.Committed, store.current.swapPhase)
        assertTrue(store.current.hadOldWorld)
        assertTrue(store.current.blocksHost, "the session stays Processing until the post-commit steps finish")
    }

    @Test
    fun `swap without an old world commits`() {
        val host = newHost().apply { stage("new") }
        val store = MemoryStore(processing())

        assertEquals(SwapOutcome.Committed, HostWorldSwap().swap(claim(), host.paths, store).getOrThrow())

        assertEquals("new", host.content(host.paths.world))
        assertFalse(host.paths.old.exists())
        assertFalse(store.current.hadOldWorld)
    }

    @Test
    fun `swap refuses a journal that is not ready to swap`() {
        val host = newHost().apply { world("old"); stage("new") }
        listOf(
            HostWorldImportJournal(status = ImportJournalStatus.Queued),
            processing().copy(swapPhase = SwapPhase.Swapping),
            processing().copy(recoveryRequired = true),
        ).forEach { journal ->
            val store = MemoryStore(journal)
            assertTrue(HostWorldSwap().swap(claim(), host.paths, store).isFailure)
            assertEquals(journal, store.current)
        }
        assertEquals(mapOf(host.paths.world.fileName.toString() to "old", host.paths.stage.fileName.toString() to "new", host.paths.old.fileName.toString() to null), host.snapshot())
    }

    @Test
    fun `a released claim cannot swap`() {
        val host = newHost().apply { world("old"); stage("new") }
        val claim = claim().also { it.release() }
        assertTrue(HostWorldSwap().swap(claim, host.paths, MemoryStore(processing())).isFailure)
        assertEquals("old", host.content(host.paths.world))
    }

    @Test
    fun `a crash at any point of a successful swap recovers to the imported world`() {
        for (hadOld in listOf(true, false)) {
            val totalOps = if (hadOld) 4 else 3
            for (crashAt in 0..totalOps) {
                val host = newHost().apply { if (hadOld) world("old"); stage("new") }
                val ops = Ops(crashAt)
                val store = MemoryStore(processing(), ops)
                val result = runCatchingCrash { HostWorldSwap(FaultyFs(ops)).swap(claim(), host.paths, store) }

                val recovered = HostWorldSwap().recover(claim(), host.paths, MemoryStore(store.current)).getOrThrow()

                val label = "hadOld=${hadOld} crashAt=${crashAt}"
                when {
                    crashAt == 0 -> {
                        assertEquals(RecoveryOutcome.Requeue, recovered, label)
                        assertEquals(if (hadOld) "old" else null, host.content(host.paths.world), label)
                        assertFalse(host.paths.stage.exists(), label)
                    }
                    crashAt == totalOps -> {
                        assertEquals(SwapOutcome.Committed, result?.getOrThrow(), label)
                        assertEquals(RecoveryOutcome.PostCommitPending, recovered, label)
                        assertEquals("new", host.content(host.paths.world), label)
                    }
                    else -> {
                        assertEquals(RecoveryOutcome.Continued(SwapOutcome.Committed), recovered, label)
                        assertEquals("new", host.content(host.paths.world), label)
                        assertFalse(host.paths.stage.exists(), label)
                        assertEquals(if (hadOld) "old" else null, host.content(host.paths.old), label)
                    }
                }
            }
        }
    }

    @Test
    fun `a failed stage move rolls back to the old world`() {
        val host = newHost().apply { world("old"); stage("new") }
        val ops = Ops()
        val store = MemoryStore(processing(), ops)
        val fs = FaultyFs(ops) { source, _ -> source == host.paths.stage }

        val outcome = HostWorldSwap(fs).swap(claim(), host.paths, store).getOrThrow()

        assertIs<SwapOutcome.RolledBack>(outcome)
        assertEquals("old", host.content(host.paths.world))
        assertFalse(host.paths.stage.exists())
        assertFalse(host.paths.old.exists())
        assertEquals(ImportJournalStatus.Failed, store.current.status)
        assertEquals(SwapPhase.RolledBack, store.current.swapPhase)
        assertEquals(HostWorldSwap.ROLLED_BACK_MESSAGE, store.current.failureMessage)
        assertFalse(store.current.blocksHost)
    }

    @Test
    fun `a crash at any point of a rollback never commits once RollingBack is persisted`() {
        // Ops: write Swapping, move world->old, move stage->world (fails), write RollingBack,
        // move old->world, write RolledBack, delete stage, write Failed.
        for (crashAt in 0..8) {
            val host = newHost().apply { world("old"); stage("new") }
            val ops = Ops(crashAt)
            val store = MemoryStore(processing(), ops)
            val fs = FaultyFs(ops) { source, _ -> source == host.paths.stage }
            runCatchingCrash { HostWorldSwap(fs).swap(claim(), host.paths, store) }

            val recovered = HostWorldSwap().recover(claim(), host.paths, MemoryStore(store.current)).getOrThrow()

            val label = "crashAt=${crashAt}"
            when (crashAt) {
                0 -> assertEquals(RecoveryOutcome.Requeue, recovered, label)
                1, 2, 3 -> {
                    // RollingBack was never persisted, so the journal still says Swapping and recovery
                    // rolls forward with a working filesystem.
                    assertEquals(RecoveryOutcome.Continued(SwapOutcome.Committed), recovered, label)
                    assertEquals("new", host.content(host.paths.world), label)
                }
                8 -> {
                    assertEquals(RecoveryOutcome.TerminalCleaned, recovered, label)
                    assertEquals("old", host.content(host.paths.world), label)
                }
                else -> {
                    assertIs<RecoveryOutcome.Continued>(recovered, label)
                    assertIs<SwapOutcome.RolledBack>(recovered.outcome, label)
                    assertEquals("old", host.content(host.paths.world), label)
                    assertFalse(host.paths.stage.exists(), label)
                }
            }
        }
    }

    @Test
    fun `regression - a finished rollback whose stage was deleted is not mistaken for a commit`() {
        val host = newHost().apply { world("old") }
        // R4 written and R5 done, then the process died before R6.
        val store = MemoryStore(processing().copy(swapPhase = SwapPhase.RolledBack, hadOldWorld = true))

        val recovered = HostWorldSwap().recover(claim(), host.paths, store).getOrThrow()

        assertIs<RecoveryOutcome.Continued>(recovered)
        assertIs<SwapOutcome.RolledBack>(recovered.outcome)
        assertEquals(ImportJournalStatus.Failed, store.current.status)
        assertEquals("old", host.content(host.paths.world))
        assertFalse(store.current.gameRulesCleared)
    }

    @Test
    fun `a failed S4 journal write touches nothing and recovery commits`() {
        val host = newHost().apply { world("old"); stage("new") }
        val ops = Ops()
        val store = MemoryStore(processing(), ops) { it.swapPhase == SwapPhase.Committed }
        val fs = FaultyFs(ops)

        assertTrue(HostWorldSwap(fs).swap(claim(), host.paths, store).isFailure)

        assertEquals(SwapPhase.Swapping, store.current.swapPhase)
        assertEquals("write Processing/Committed", ops.log.last())
        assertEquals("new", host.content(host.paths.world))
        val recovered = HostWorldSwap().recover(claim(), host.paths, MemoryStore(store.current)).getOrThrow()
        assertEquals(RecoveryOutcome.Continued(SwapOutcome.Committed), recovered)
        assertEquals("new", host.content(host.paths.world))
    }

    @Test
    fun `a failed journal write stops all further filesystem work`() {
        val host = newHost().apply { world("old"); stage("new") }
        val ops = Ops()
        val store = MemoryStore(processing(), ops) { it.swapPhase == SwapPhase.RollingBack }
        val fs = FaultyFs(ops) { source, _ -> source == host.paths.stage }

        assertTrue(HostWorldSwap(fs).swap(claim(), host.paths, store).isFailure)

        assertTrue(ops.log.last().startsWith("write"), "the failed R1 write is the last operation: ${ops.log}")
        assertEquals(SwapPhase.Swapping, store.current.swapPhase)
        assertEquals(mapOf(host.paths.world.fileName.toString() to null, host.paths.stage.fileName.toString() to "new", host.paths.old.fileName.toString() to "old"), host.snapshot())
    }

    @Test
    fun `a failed rollback move requires manual recovery and touches nothing more`() {
        val host = newHost().apply { world("old"); stage("new") }
        val ops = Ops()
        val store = MemoryStore(processing(), ops)
        val fs = FaultyFs(ops) { source, _ -> source == host.paths.stage || source == host.paths.old }

        val outcome = HostWorldSwap(fs).swap(claim(), host.paths, store).getOrThrow()

        assertIs<SwapOutcome.RecoveryRequired>(outcome)
        assertTrue(store.current.recoveryRequired)
        assertEquals(ImportJournalStatus.Failed, store.current.status)
        assertEquals(HostWorldSwap.RECOVERY_REQUIRED_MESSAGE, store.current.failureMessage)
        assertFalse(store.current.notified)
        assertTrue(store.current.blocksHost)
        val before = host.snapshot()
        assertEquals(RecoveryOutcome.NotifyOnly, HostWorldSwap().recover(claim(), host.paths, store).getOrThrow())
        assertEquals(before, host.snapshot())
    }

    @Test
    fun `recovery follows the truth table for every filesystem state`() {
        for (phase in listOf(SwapPhase.Swapping, SwapPhase.RollingBack)) {
            for (hadOld in listOf(true, false)) {
                for (bits in 0 until 8) {
                    val stage = bits and 1 != 0
                    val world = bits and 2 != 0
                    val old = bits and 4 != 0
                    val label = "${phase} hadOld=${hadOld} stage=${stage} world=${world} old=${old}"
                    val host = newHost().apply {
                        stage(if (stage) "new" else null)
                        world(if (world) (if (stage) "old" else "new") else null)
                        old(if (old) "old" else null)
                    }
                    val store = MemoryStore(processing().copy(swapPhase = phase, hadOldWorld = hadOld))
                    val before = host.snapshot()

                    val outcome = (HostWorldSwap().recover(claim(), host.paths, store).getOrThrow() as RecoveryOutcome.Continued).outcome

                    val valid = when (phase) {
                        SwapPhase.Swapping -> if (hadOld) {
                            Triple(stage, world, old) in setOf(Triple(true, true, false), Triple(true, false, true), Triple(false, true, true))
                        } else {
                            Triple(stage, world, old) in setOf(Triple(true, false, false), Triple(false, true, false))
                        }
                        else -> if (hadOld) {
                            Triple(stage, world, old) in setOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))
                        } else {
                            Triple(stage, world, old) in setOf(Triple(false, true, false), Triple(true, false, false))
                        }
                    }
                    if (!valid) {
                        assertIs<SwapOutcome.RecoveryRequired>(outcome, label)
                        assertEquals(before, host.snapshot(), label)
                        assertTrue(store.current.recoveryRequired, label)
                        continue
                    }
                    if (phase == SwapPhase.Swapping) {
                        assertEquals(SwapOutcome.Committed, outcome, label)
                        assertEquals("new", host.content(host.paths.world), label)
                        assertFalse(host.paths.stage.exists(), label)
                        assertEquals(hadOld, host.paths.old.exists(), label)
                    } else {
                        assertIs<SwapOutcome.RolledBack>(outcome, label)
                        assertEquals(if (hadOld) "old" else null, host.content(host.paths.world), label)
                        assertFalse(host.paths.stage.exists(), label)
                        assertFalse(host.paths.old.exists(), label)
                        assertEquals(ImportJournalStatus.Failed, store.current.status, label)
                    }
                }
            }
        }
    }

    @Test
    fun `a committed or rolled-back journal that does not match the filesystem requires recovery`() {
        val committed = newHost().apply { stage("new") }
        val committedStore = MemoryStore(processing().copy(swapPhase = SwapPhase.Committed, hadOldWorld = false))
        assertIs<SwapOutcome.RecoveryRequired>((HostWorldSwap().recover(claim(), committed.paths, committedStore).getOrThrow() as RecoveryOutcome.Continued).outcome)

        val rolledBack = newHost().apply { old("old") }
        val rolledBackStore = MemoryStore(processing().copy(swapPhase = SwapPhase.RolledBack, hadOldWorld = true))
        assertIs<SwapOutcome.RecoveryRequired>((HostWorldSwap().recover(claim(), rolledBack.paths, rolledBackStore).getOrThrow() as RecoveryOutcome.Continued).outcome)
        assertEquals("old", rolledBack.content(rolledBack.paths.old))
    }

    @Test
    fun `a queued or processing journal that never swapped is requeued`() {
        val host = newHost().apply { world("old"); stage("half-extracted") }
        val store = MemoryStore(processing())

        assertEquals(RecoveryOutcome.Requeue, HostWorldSwap().recover(claim(), host.paths, store).getOrThrow())

        assertFalse(host.paths.stage.exists())
        assertEquals("old", host.content(host.paths.world))
    }

    @Test
    fun `terminal sessions only clean their own leftovers and never inspect the world`() {
        val ready = newHost().apply { stage("leftover"); old("old") }
        val readyJournal = HostWorldImportJournal(status = ImportJournalStatus.Ready, swapPhase = SwapPhase.Committed, hadOldWorld = true, notified = true)
        val readyStore = MemoryStore(readyJournal)
        // The world was later deleted (for example by reset-world); recovery must not care.
        assertEquals(RecoveryOutcome.TerminalCleaned, HostWorldSwap().recover(claim(), ready.paths, readyStore).getOrThrow())
        assertFalse(ready.paths.stage.exists())
        assertFalse(ready.paths.old.exists())
        assertFalse(ready.paths.world.exists())
        assertEquals(readyJournal, readyStore.current)
        assertFalse(readyStore.current.blocksHost)

        val failed = newHost().apply { world("someone else's world") }
        val failedJournal = HostWorldImportJournal(status = ImportJournalStatus.Failed, swapPhase = SwapPhase.RolledBack, hadOldWorld = false)
        val failedStore = MemoryStore(failedJournal)
        assertEquals(RecoveryOutcome.TerminalCleaned, HostWorldSwap().recover(claim(), failed.paths, failedStore).getOrThrow())
        assertEquals("someone else's world", failed.content(failed.paths.world))
        assertEquals(failedJournal, failedStore.current)
    }

    @Test
    fun `an uploading session is not recovered`() {
        val host = newHost()
        val store = MemoryStore(HostWorldImportJournal(status = ImportJournalStatus.Uploading))
        assertEquals(RecoveryOutcome.NotStarted, HostWorldSwap().recover(claim(), host.paths, store).getOrThrow())
    }

    @Test
    fun `admin resolve writes a final phase that recovery never replays`() {
        val host = newHost().apply { world("fixed by hand"); old("old") }
        val needsHelp = processing().copy(swapPhase = SwapPhase.Swapping, hadOldWorld = true, recoveryRequired = true, status = ImportJournalStatus.Failed)
        val store = MemoryStore(needsHelp)
        val swap = HostWorldSwap()

        assertTrue(swap.resolve(claim(), host.paths, MemoryStore(processing()), ImportResolution.Other, "admin", 1L, false).isFailure, "only recoveryRequired sessions")
        val empty = newHost()
        assertTrue(swap.resolve(claim(), empty.paths, MemoryStore(needsHelp), ImportResolution.Other, "admin", 1L, false).isFailure, "world must exist")

        val resolved = swap.resolve(claim(), host.paths, store, ImportResolution.KeptImportedWorld, "admin", 42L, false).getOrThrow()

        assertEquals(SwapPhase.Resolved, resolved.swapPhase)
        assertEquals(ImportJournalStatus.Ready, resolved.status)
        assertFalse(resolved.recoveryRequired)
        assertFalse(resolved.blocksHost)
        assertEquals(ImportResolution.KeptImportedWorld, resolved.resolution)
        assertEquals(42L, resolved.resolvedAt)
        repeat(2) {
            assertEquals(RecoveryOutcome.TerminalCleaned, swap.recover(claim(), host.paths, store).getOrThrow())
        }
        assertEquals(resolved, store.current)
        assertEquals("fixed by hand", host.content(host.paths.world))
        assertFalse(host.paths.old.exists())

        val emptyStore = MemoryStore(needsHelp)
        val restored = swap.resolve(claim(), empty.paths, emptyStore, ImportResolution.RestoredOldWorld, "admin", 1L, true).getOrThrow()
        assertEquals(ImportJournalStatus.Failed, restored.status)
    }

    @Test
    fun `claims are exclusive until released`() {
        val id = UUID.randomUUID()
        val first = registry.tryClaim(id)!!
        assertNull(registry.tryClaim(id), "a queued or running session cannot be claimed by recovery")
        assertTrue(registry.isClaimed(id))
        first.release()
        first.release()
        assertFalse(first.isActive)
        assertFalse(registry.isClaimed(id))
        registry.tryClaim(id)!!.use { assertTrue(it.isActive) }
        assertFalse(registry.isClaimed(id))
    }

    @Test
    fun `journal file round trips and rejects unknown values`() {
        val dir = Files.createTempDirectory("import-journal")
        val file = dir.resolve("journal.json")
        val store = AtomicJournalFile(file)
        val journal = processing().copy(swapPhase = SwapPhase.RollingBack, hadOldWorld = true, recoveryReason = "原因")

        store.write(journal)

        assertEquals(journal, store.read())
        assertFalse(dir.resolve("journal.json.tmp").exists())
        file.writeText(file.readText().replace("RollingBack", "Teleporting"))
        assertTrue(runCatching { store.read() }.isFailure, "an unknown phase must not be coerced to a default")
    }

    @Test
    fun `journal writes never fall back to a non-atomic move`() {
        val dir = Files.createTempDirectory("import-journal-atomic")
        val file = dir.resolve("journal.json").apply { writeText("previous") }

        val failure = runCatching {
            DurableFiles.writeAtomically(file, "next".toByteArray()) { source, target ->
                throw java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "not supported")
            }
        }.exceptionOrNull()

        assertIs<java.nio.file.AtomicMoveNotSupportedException>(failure)
        assertEquals("previous", file.readText())
        assertFalse(dir.resolve("journal.json.tmp").exists())
    }

    /** Runs [block]; a [SimulatedCrash] means the process died, and its result is unknown. */
    private fun runCatchingCrash(block: () -> Result<SwapOutcome>): Result<SwapOutcome>? =
        try {
            block()
        } catch (crash: SimulatedCrash) {
            null
        }
}
