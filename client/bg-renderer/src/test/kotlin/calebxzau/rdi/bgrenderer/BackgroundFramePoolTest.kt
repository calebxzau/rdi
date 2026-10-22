package calebxzau.rdi.bgrenderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class BackgroundFramePoolTest {
    private class Payload : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private fun pool(created: MutableList<Payload> = mutableListOf()) =
        FrameSlotPool(3, { Payload().also(created::add) }) { _, _ -> }

    private fun publish(pool: FrameSlotPool<Payload>): BackgroundFramePainter<Payload> {
        val write = assertNotNull(pool.write())
        return assertNotNull(write.publish())
    }

    @Test
    fun createsExactlyThreeReusableSlots() {
        val created = mutableListOf<Payload>()
        val pool = pool(created)
        publish(pool)
        assertEquals(3, created.size)
        repeat(20) { publish(pool) }
        assertEquals(3, created.size)
        pool.close()
    }

    @Test
    fun oldReadyIsReturnedToFree() {
        val pool = pool()
        publish(pool)
        val first = pool.snapshots()
        publish(pool)
        val second = pool.snapshots()
        assertEquals(1, first.count { it.state == FrameSlotState.READY })
        assertEquals(1, second.count { it.state == FrameSlotState.READY })
        assertEquals(2, second.count { it.state == FrameSlotState.FREE })
        pool.close()
    }

    @Test
    fun displayedSlotRemainsReservedAfterDraw() {
        val pool = pool()
        publish(pool)
        val lease = assertNotNull(pool.drawLease())
        lease.close()
        assertEquals(1, pool.snapshots().count { it.state == FrameSlotState.DISPLAYED })
        assertEquals(2, pool.snapshots().count { it.state == FrameSlotState.FREE })
        pool.close()
    }

    @Test
    fun activeReadersDelayOldDisplayedReuse() {
        val pool = pool()
        publish(pool)
        val oldLease = assertNotNull(pool.drawLease())
        publish(pool)
        val newLease = assertNotNull(pool.drawLease())
        assertEquals(2, pool.snapshots().count { it.state == FrameSlotState.DISPLAYED })
        oldLease.close()
        assertEquals(1, pool.snapshots().count { it.state == FrameSlotState.DISPLAYED })
        assertEquals(2, pool.snapshots().count { it.state == FrameSlotState.FREE })
        newLease.close()
        pool.close()
    }

    @Test
    fun noFreeSlotDropsFrameWithoutOverwriting() {
        val created = mutableListOf<Payload>()
        val pool = pool(created)
        val leases = buildList {
            repeat(3) {
                publish(pool)
                add(assertNotNull(pool.drawLease()))
            }
        }
        assertNull(pool.write())
        pool.close()
        leases.forEach { it.close() }
        assertTrue(created.all(Payload::closed))
    }

    @Test
    fun payloadFactoryFailureRollsBackAlreadyCreatedSlots() {
        val created = mutableListOf<Payload>()
        var attempts = 0
        val pool = FrameSlotPool<Payload>(3, {
            if (++attempts == 2) error("payload allocation failed")
            Payload().also(created::add)
        }) { _, _ -> }

        assertFailsWith<IllegalStateException> { pool.write() }
        assertEquals(1, created.size)
        assertTrue(created.single().closed)
        assertEquals(0, pool.createdSlotCount)
        pool.close()
    }

    @Test
    fun closeRejectsWritesAndDefersActiveReaderPayload() {
        val created = mutableListOf<Payload>()
        val pool = pool(created)
        publish(pool)
        val lease = assertNotNull(pool.drawLease())
        pool.close()
        assertTrue(pool.isClosed())
        assertNull(pool.write())
        assertFalse(created[0].closed)
        lease.close()
        assertTrue(created.all(Payload::closed))
    }

    @Test
    fun closeReturnsWhileThreadStartIsStillBlocked() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val loopEntered = AtomicBoolean(false)
        lateinit var renderThread: Thread
        val state = RendererState(
            threadFactory = { runnable, name ->
                object : Thread(runnable, name) {
                    override fun start() {
                        startEntered.countDown()
                        releaseStart.await(5, TimeUnit.SECONDS)
                        super.start()
                    }
                }.also { renderThread = it }
            },
            renderLoopOverride = { loopEntered.set(true) }
        )
        val ensureThread = Thread { state.ensureThread() }
        val closeThread = Thread {
            state.close()
            closeReturned.countDown()
        }
        try {
            ensureThread.start()
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            closeThread.start()
            assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            assertEquals(1L, releaseStart.count)
        } finally {
            state.close()
            releaseStart.countDown()
            ensureThread.join(5_000)
            closeThread.join(5_000)
            renderThread.join(5_000)
        }
        assertFalse(renderThread.isAlive)
        assertFalse(loopEntered.get())
        assertTrue(state.framePool.isClosed())
        assertNull(state.failure.value)
        assertNull(state.thread)
    }

    @Test
    fun closeBeforeActivationNeverStartsAWorker() {
        val starts = AtomicBoolean(false)
        val state = RendererState(renderLoopOverride = { starts.set(true) })
        val session = BackgroundRenderSession(state)
        session.close()
        session.close()
        session.setActive(true)
        assertFalse(starts.get())
        assertNull(state.thread)
        assertEquals(0, state.framePool.createdSlotCount)
    }

    @Test
    fun closeDoesNotWaitForBlockedWorkAndCheckpointSkipsNextStage() {
        val entered = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val nextStageEntered = AtomicBoolean(false)
        val cleanupThread = AtomicReference<Thread?>()
        lateinit var state: RendererState
        state = RendererState(renderLoopOverride = {
            try {
                entered.countDown()
                releaseWork.await(5, TimeUnit.SECONDS)
                state.checkRunning()
                nextStageEntered.set(true)
            } finally {
                cleanupThread.set(Thread.currentThread())
            }
        })
        state.ensureThread()
        val renderThread = assertNotNull(state.thread)
        val closeThread = Thread {
            state.close()
            closeReturned.countDown()
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closeThread.start()
            assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            assertTrue(renderThread.isAlive)
            assertFalse(state.framePool.isClosed())
            assertNull(cleanupThread.get())
        } finally {
            state.close()
            releaseWork.countDown()
            closeThread.join(5_000)
            renderThread.join(5_000)
        }
        assertFalse(renderThread.isAlive)
        assertFalse(nextStageEntered.get())
        assertEquals(renderThread, cleanupThread.get())
        assertTrue(state.framePool.isClosed())
        assertNull(state.failure.value)
        assertFalse(state.lowPerformance.value)
    }

    @Test
    fun closeWakesAParkedWorker() {
        val entered = CountDownLatch(1)
        lateinit var state: RendererState
        state = RendererState(renderLoopOverride = {
            entered.countDown()
            while (true) {
                state.checkRunning()
                LockSupport.park()
            }
        })
        state.ensureThread()
        val renderThread = assertNotNull(state.thread)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        } finally {
            state.close()
            renderThread.join(5_000)
        }
        assertFalse(renderThread.isAlive)
        assertTrue(state.framePool.isClosed())
        assertNull(state.thread)
        assertNull(state.failure.value)
    }
}
