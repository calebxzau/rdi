package calebxzau.rdi.mc.client.chunkcache

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoalescingWriteQueueTest {
    @Test
    fun precomputedPacketWeightSkipsTraversalButStillEnforcesLimits(): Unit {
        val sink = RecordingSink()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val queue = CoalescingWriteQueue<String, Int>(sink, failures::add, 2, 10, 5) {
            error("Packet admission must reuse its network-thread estimate")
        }
        try {
            assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("packet", 1, 4))
            assertEquals(CoalescingWriteQueue.Submission.OVERSIZED, queue.submit("oversized", 2, 6))
            assertEquals(CoalescingWriteQueue.Submission.OVERSIZED, queue.submit("invalid", 3, 0))
        } finally {
            queue.closeAsync()
            assertTrue(queue.awaitClosed(5_000))
        }
        assertEquals(listOf("packet" to 1), sink.writes)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun replacesPendingValueWithoutChangingItsQueuePosition(): Unit {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val sink = RecordingSink { key, value ->
            if (key == "busy") {
                firstWriteStarted.countDown()
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS))
            }
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val queue = queue(sink, failures::add, capacity = 2, maxPendingBytes = 10)

        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("busy", 1))
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("a", 2))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("b", 1))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("a", 3))

        queue.closeAsync()
        releaseFirstWrite.countDown()
        queue.awaitClosed()

        assertEquals(listOf("busy" to 1, "a" to 3, "b" to 1), sink.writes)
        assertTrue(sink.closed)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun evictsOldestPendingEntryToFitNewerBytesWithoutWaiting(): Unit {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val sink = RecordingSink { key, _ ->
            if (key == "busy") {
                firstWriteStarted.countDown()
                assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS))
            }
        }
        val queue = queue(sink, {}, capacity = 3, maxPendingBytes = 6)
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("busy", 1))
        assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("old", 2))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("keep", 2))
        assertEquals(CoalescingWriteQueue.Submission.EVICTED, queue.submit("new", 3))
        assertEquals(1, queue.stats().evictions())
        assertEquals(5L, queue.stats().pendingBytes())
        assertTrue(queue.canAccept(4))

        queue.closeAsync()
        releaseFirstWrite.countDown()
        queue.awaitClosed()
        assertEquals(listOf("busy" to 1, "keep" to 2, "new" to 3), sink.writes)
    }

    @Test
    fun closeDrainsAcceptedValuesAndRejectsLaterSubmissions(): Unit {
        val sink = RecordingSink()
        val queue = queue(sink, {}, capacity = 2, maxPendingBytes = 10)
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("a", 1))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("b", 2))

        queue.closeAsync()

        assertEquals(CoalescingWriteQueue.Submission.CLOSED, queue.submit("c", 3))
        queue.awaitClosed()

        assertEquals(listOf("a" to 1, "b" to 2), sink.writes)
        assertTrue(sink.closed)
    }

    @Test
    fun oversizedValuesAreRejectedAndActiveBytesAreTracked(): Unit {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val sink = RecordingSink { _, _ ->
            writeStarted.countDown()
            assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
        }
        val queue = queue(sink, {}, capacity = 1, maxPendingBytes = 5, maxValueBytes = 4)
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("writing", 3))
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        assertEquals(3L, queue.stats().activeBytes())
        assertFalse(queue.canAccept(5))
        assertEquals(CoalescingWriteQueue.Submission.OVERSIZED, queue.submit("too-large", 5))
        assertEquals(1, queue.stats().oversized())
        queue.discardPending()
        queue.closeAsync()
        releaseWrite.countDown()
        assertTrue(queue.awaitClosed(5_000))
        assertEquals(0L, queue.stats().activeBytes())
    }

    @Test
    fun updateForActivelyWrittenKeyIsQueuedAfterCurrentWrite(): Unit {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val sink = RecordingSink { _, value ->
            if (value == 1) {
                writeStarted.countDown()
                assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
            }
        }
        val queue = queue(sink, {}, capacity = 1, maxPendingBytes = 4)
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("same", 1))
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("same", 2))
        assertEquals(1, queue.stats().pendingEntries())
        queue.closeAsync()
        releaseWrite.countDown()
        queue.awaitClosed()
        assertEquals(listOf("same" to 1, "same" to 2), sink.writes)
    }

    @Test
    fun writeFailureClosesSinkAndReportsOnce(): Unit {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writeFailure = IOException("write failed")
        val closeFailure = IOException("close failed")
        val sink = RecordingSink { _, _ ->
            writeStarted.countDown()
            assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
            throw writeFailure
        }.also { it.closeFailure = closeFailure }
        val failureThread = arrayOfNulls<String>(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val queue = CoalescingWriteQueue(sink, {
            failureThread[0] = Thread.currentThread().name
            failures += it
        }, 1, 5, 5, Int::toLong)

        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("writing", 1))
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        assertEquals(CoalescingWriteQueue.Submission.ACCEPTED, queue.submit("pending", 1))
        assertEquals(1, queue.stats().pendingEntries())

        releaseWrite.countDown()
        queue.awaitClosed()

        assertTrue(sink.closed)
        assertEquals(listOf(writeFailure), failures)
        assertEquals("close failed", writeFailure.suppressed.single().message)
        assertTrue(failureThread[0]?.startsWith("rdi-chunk-cache-writer") == true)
    }

    private fun queue(
        sink: RecordingSink,
        onFailure: (Throwable) -> Unit,
        capacity: Int,
        maxPendingBytes: Long,
        maxValueBytes: Long = maxPendingBytes,
    ): CoalescingWriteQueue<String, Int> = CoalescingWriteQueue(
        sink,
        onFailure,
        capacity,
        maxPendingBytes,
        maxValueBytes,
        Int::toLong,
    )

    private class RecordingSink(
        private val onWrite: (String, Int) -> Unit = { _, _ -> },
    ) : CoalescingWriteQueue.Sink<String, Int> {
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        var closed = false
            private set
        var closeFailure: Throwable? = null

        override fun write(key: String, value: Int) {
            writes += key to value
            onWrite(key, value)
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
            closeFailure = null
        }
    }
}
