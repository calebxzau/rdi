package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.FullChunkStatus
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DmSnapshotCaptureModelsTest {
    @Test
    fun modeParsingKeepsDefaultAndExplicitWireValuesStable(): Unit {
        assertEquals(DmSnapshotMode.Disk, DmSnapshotMode.parse("disk"))
        assertEquals(DmSnapshotMode.Memory, DmSnapshotMode.parse("memory"))
        assertEquals(null, DmSnapshotMode.parse("other"))
    }

    @Test
    fun sourceClassificationIsExclusive(): Unit {
        assertEquals("memory", DmSnapshotMemoryHelpers.classifyColumn(false, false, false))
        assertEquals("stored", DmSnapshotMemoryHelpers.classifyColumn(true, true, true))
        assertEquals("mixed", DmSnapshotMemoryHelpers.classifyColumn(true, true, false))
        assertEquals("mixed", DmSnapshotMemoryHelpers.classifyColumn(false, true, false))
        assertEquals("wait", DmSnapshotMemoryHelpers.classifyTerrain(false, false, true, true))
        assertEquals("stored", DmSnapshotMemoryHelpers.classifyTerrain(false, false, false, false))
        assertEquals("wait", DmSnapshotMemoryHelpers.classifyEntities(true, false, true, false))
    }

    @Test
    fun fullChunkReadinessAcceptsTickingStatusesOnlyAfterFull(): Unit {
        assertTrue(DmSnapshotMemoryHelpers.isFullChunkReady(FullChunkStatus.FULL))
        assertTrue(DmSnapshotMemoryHelpers.isFullChunkReady(FullChunkStatus.BLOCK_TICKING))
        assertTrue(DmSnapshotMemoryHelpers.isFullChunkReady(FullChunkStatus.ENTITY_TICKING))
        assertFalse(DmSnapshotMemoryHelpers.isFullChunkReady(FullChunkStatus.INACCESSIBLE))
        assertFalse(DmSnapshotMemoryHelpers.isFullChunkReady(null))
    }

    @Test
    fun readinessKeepsOnlyTheCurrentDistinctBlockersAndClears(): Unit {
        val readiness = DmSnapshotReadiness()
        val source = mutableListOf("B: pendingUnload=true", "B: pendingUnload=true")

        readiness.update(source)
        source += "A: fullStatus=INACCESSIBLE"

        assertEquals(listOf("B: pendingUnload=true"), readiness.blockers)
        assertEquals("B: pendingUnload=true", readiness.describe())

        readiness.clear()
        assertTrue(readiness.blockers.isEmpty())
        assertEquals("未观察到就绪检查结果", readiness.describe())
    }

    @Test
    fun terrainSourceUsesRetainedFullChunkEvenWhenAccessibilityIsUnavailable(): Unit {
        val decision = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = true,
            pendingUnload = false,
            readyForSaving = true,
            saveFailed = false,
            hasRetainedFullChunk = true,
            retainedDirty = true,
        )

        assertEquals(DmSnapshotTerrainSource.Memory, decision.source)
        assertTrue(decision.reason.contains("dirty=true"))
    }

    @Test
    fun terrainSourceWaitsForStableRetainedFullChunkAndNeverFallsBackToStored(): Unit {
        val incomplete = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = true,
            pendingUnload = false,
            readyForSaving = true,
            saveFailed = false,
            hasRetainedFullChunk = false,
        )
        val notReady = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = true,
            pendingUnload = false,
            readyForSaving = false,
            saveFailed = false,
            hasRetainedFullChunk = true,
        )
        val unloading = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = true,
            pendingUnload = true,
            readyForSaving = true,
            saveFailed = false,
            hasRetainedFullChunk = true,
        )
        val absent = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = false,
            holdersAgree = true,
            pendingUnload = false,
            readyForSaving = false,
            saveFailed = false,
            hasRetainedFullChunk = false,
        )
        val mismatchedHolders = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = false,
            pendingUnload = false,
            readyForSaving = true,
            saveFailed = false,
            hasRetainedFullChunk = true,
        )
        val missingRetainedChunk = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = true,
            pendingUnload = false,
            readyForSaving = true,
            saveFailed = false,
            hasRetainedFullChunk = false,
        )

        assertEquals(DmSnapshotTerrainSource.Wait, incomplete.source)
        assertEquals(DmSnapshotTerrainSource.Wait, notReady.source)
        assertEquals(DmSnapshotTerrainSource.Wait, unloading.source)
        assertEquals(DmSnapshotTerrainSource.Stored, absent.source)
        assertEquals(DmSnapshotTerrainSource.Wait, mismatchedHolders.source)
        assertEquals(DmSnapshotTerrainSource.Wait, missingRetainedChunk.source)
    }

    @Test
    fun terrainSourceReportsSaveFailureInsteadOfUsingDisk(): Unit {
        val decision = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = true,
            holdersAgree = true,
            pendingUnload = false,
            readyForSaving = false,
            saveFailed = true,
            hasRetainedFullChunk = true,
        )

        assertEquals(DmSnapshotTerrainSource.Failure, decision.source)
    }

    @Test
    fun poiOverlayReplacesPresentAndRemovesKnownEmptyWithoutMutatingDisk(): Unit {
        val disk = CompoundTag().apply {
            putInt("DataVersion", 42)
            put("Unknown", CompoundTag().apply { putString("keep", "yes") })
            put("Sections", CompoundTag().apply {
                put("-1", CompoundTag().apply { putBoolean("Valid", true) })
                put("0", CompoundTag().apply { putBoolean("stale", true) })
            })
        }
        val before = disk.copy()
        val merged = DmSnapshotMemoryHelpers.mergePoiOverlay(
            disk,
            listOf(
                DmSnapshotPoiOverlay(-1, CompoundTag().apply { putBoolean("Valid", false) }, false),
                DmSnapshotPoiOverlay(0, null, true),
            ),
            42,
        ) ?: error("expected POI")

        assertEquals(before.toString(), disk.toString())
        assertEquals(false, merged.getCompound("Sections").getCompound("-1").getBoolean("Valid"))
        assertFalse(merged.getCompound("Sections").contains("0"))
        assertEquals("yes", merged.getCompound("Unknown").getString("keep"))
        assertEquals(42, merged.getInt("DataVersion"))
    }

    @Test
    fun absentPoiUsesCurrentVersionAndIncompatibleDiskFails(): Unit {
        val created = DmSnapshotMemoryHelpers.mergePoiOverlay(null, listOf(DmSnapshotPoiOverlay(0, null, true)), 123)!!
        assertEquals(123, created.getInt("DataVersion"))
        assertFailsWith<IllegalArgumentException> {
            DmSnapshotMemoryHelpers.mergePoiOverlay(CompoundTag().apply { putInt("DataVersion", 122) }, emptyList(), 123)
        }
        assertFailsWith<IllegalArgumentException> {
            DmSnapshotMemoryHelpers.mergePoiOverlay(CompoundTag(), emptyList(), 123)
        }
    }

    @Test
    fun memoryAggregateRejectsNegativeAndOverBudgetSizes(): Unit {
        val tag = CompoundTag().apply { putString("payload", "ok") }
        val size = DmSnapshotMemoryHelpers.checkedSize(tag)
        assertTrue(DmSnapshotMemoryHelpers.checkedAggregate(0, tag, "test") >= size)
        assertFailsWith<IllegalArgumentException> {
            DmSnapshotMemoryHelpers.checkedAggregate(DmSnapshotMemoryHelpers.MAX_MEMORY_BYTES, tag, "test")
        }
        val budget = DmSnapshotMemoryBudget(limit = size)
        assertEquals(size, budget.reserve(tag, "test"))
        assertFailsWith<IllegalArgumentException> { budget.reserve(tag, "overflow") }
    }

    @Test
    fun delayedReadIsAwaitedWithoutRunningCaptureContinuationInline(): Unit {
        val future = CompletableFuture<Optional<CompoundTag>>()
        Thread {
            Thread.sleep(25)
            future.complete(Optional.of(CompoundTag().apply { putString("value", "detached") }))
        }.start()
        val result = DmSnapshotMemoryHelpers.awaitOptional(future, TimeUnit.SECONDS.toNanos(1), "test")
        assertTrue(result.isSuccess)
        assertEquals("detached", result.getOrThrow().get().getString("value"))
    }

    @Test
    fun failedReadDoesNotBecomeAnEmptyRecord(): Unit {
        val future = CompletableFuture<Optional<CompoundTag>>()
        future.completeExceptionally(IllegalStateException("read failed"))
        val result = DmSnapshotMemoryHelpers.awaitOptional(future, TimeUnit.SECONDS.toNanos(1), "terrain")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("terrain"))
    }
}
