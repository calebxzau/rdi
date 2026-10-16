package calebxzau.rdi.mc.client.chunkcache

import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Ordered, bounded, nonblocking producer queue. Only its worker owns persistent delta state.
 *
 * Writes run in submission order. A read waits only for earlier writes of the same chunk, so cache reads for
 * candidate preparation are not stuck behind unrelated base writes; writes still get a turn after a few reads,
 * and go first once their queue is half full, so reads cannot starve them into rejection.
 */
class ClientChunkDeltaWriter private constructor(
    private val sinkFactory: () -> Sink,
    private val onFailure: Consumer<Throwable>,
    private val capacity: Int,
    private val maxPendingBytes: Long,
    private val flushIntervalMillis: Long,
    private val autoStart: Boolean,
) : ChunkCacheWriterHandle {
    interface Sink : AutoCloseable {
        fun accept(write: ClientChunkCacheWrite)
        fun read(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Combined? =
            throw UnsupportedOperationException("This chunk cache sink does not support reads")
        fun readTerrain(key: ClientChunkRegionSink.Key): ClientChunkDeltaStore.Terrain? =
            throw UnsupportedOperationException("This chunk cache sink does not support terrain reads")
        fun flush()
    }

    constructor(root: Path, onFailure: Consumer<Throwable>, capacity: Int, maxPendingBytes: Long) :
        this(root, onFailure, capacity, maxPendingBytes, true)

    /** Allocating a deferred writer performs no I/O; lifecycle ownership is established before start(). */
    constructor(root: Path, onFailure: Consumer<Throwable>, capacity: Int, maxPendingBytes: Long, autoStart: Boolean) : this(
        {
            val store = ClientChunkDeltaStore(root)
            object : Sink {
                override fun accept(write: ClientChunkCacheWrite) = store.accept(write)
                override fun read(key: ClientChunkRegionSink.Key) = store.read(key)
                override fun readTerrain(key: ClientChunkRegionSink.Key) = store.readTerrain(key)
                override fun flush() = store.flush()
                override fun close() = store.close()
            }
        }, onFailure, capacity, maxPendingBytes, 250, autoStart,
    )

    /** Detached sink fixture; no disk or Minecraft required for queue/lifecycle tests. */
    constructor(sink: Sink, onFailure: Consumer<Throwable>, capacity: Int, maxPendingBytes: Long, flushIntervalMillis: Long) :
        this(sink, onFailure, capacity, maxPendingBytes, flushIntervalMillis, true)

    constructor(sink: Sink, onFailure: Consumer<Throwable>, capacity: Int, maxPendingBytes: Long, flushIntervalMillis: Long, autoStart: Boolean) :
        this({ sink }, onFailure, capacity, maxPendingBytes, flushIntervalMillis, autoStart)

    data class Stats(
        val pendingEntries: Int, val pendingBytes: Long, val activeBytes: Long,
        val accepted: Long, val processed: Long, val discarded: Long, val rejected: Long,
        val peakPendingEntries: Int, val peakPendingBytes: Long,
        val flushes: Long, val workNanos: Long, val maxWorkNanos: Long, val accepting: Boolean,
        val reads: Long, val missingReads: Long, val failedReads: Long,
        val readQueueNanos: Long, val maxReadQueueNanos: Long,
        val storeReadNanos: Long, val maxStoreReadNanos: Long,
    )

    private val lock = Object()
    private sealed interface Command {
        val sequence: Long
        data class Write(val value: ClientChunkCacheWrite, override val sequence: Long) : Command
        interface Read : Command {
            val key: ClientChunkRegionSink.Key
            val enqueuedNanos: Long
            fun execute(sink: Sink)
            fun fail(error: Throwable)
        }
    }
    private inner class TypedRead<T>(
        override val key: ClientChunkRegionSink.Key,
        override val sequence: Long,
        val result: CompletableFuture<T?>,
        override val enqueuedNanos: Long,
        private val action: (Sink, ClientChunkRegionSink.Key) -> T?,
    ) : Command.Read {
        override fun execute(sink: Sink) {
            val started = System.nanoTime()
            var value: T? = null
            var failure: Throwable? = null
            try {
                value = action(sink, key)
            } catch (error: Throwable) {
                failure = error
            }
            val finished = System.nanoTime()
            recordRead(enqueuedNanos, started, finished, value == null && failure == null, failure != null)
            if (failure == null) result.complete(value) else result.completeExceptionally(failure)
        }

        override fun fail(error: Throwable) { result.completeExceptionally(error) }
    }
    private val writes = ArrayDeque<Command.Write>()
    private val readQueue = ArrayDeque<Command.Read>()
    /** Sequences of queued writes per chunk, oldest first; a read waits for those older than itself. */
    private val writeSequences = HashMap<ClientChunkRegionSink.Key, ArrayDeque<Long>>()
    private var nextSequence = 0L
    private var consecutiveReads = 0
    private var pendingWrites = 0
    private var outstandingReads = 0
    private var pendingBytes = 0L
    private var activeBytes = 0L
    private var accepting = true
    private var started = false
    private var accepted = 0L
    private var processed = 0L
    private var discarded = 0L
    private var rejected = 0L
    private var peakPendingEntries = 0
    private var peakPendingBytes = 0L
    private var flushes = 0L
    private var workNanos = 0L
    private var maxWorkNanos = 0L
    private var reads = 0L
    private var missingReads = 0L
    private var failedReads = 0L
    private var readQueueNanos = 0L
    private var maxReadQueueNanos = 0L
    private var storeReadNanos = 0L
    private var maxStoreReadNanos = 0L
    private val worker: Thread

    init {
        require(capacity > 0 && maxPendingBytes > 0 && flushIntervalMillis > 0)
        worker = Thread(::runWorker, "rdi-chunk-cache-writer").apply { isDaemon = true }
        if (autoStart) start()
    }

    fun start() = synchronized(lock) {
        if (!started && accepting) {
            started = true
            worker.start()
        }
    }

    fun submit(write: ClientChunkCacheWrite): Boolean = synchronized(lock) {
        val bytes = write.estimatedBytes()
        if (!accepting || pendingWrites >= capacity || bytes <= 0 || bytes > maxPendingBytes - pendingBytes) {
            rejected++
            return@synchronized false
        }
        val command = Command.Write(write, nextSequence++)
        writes.addLast(command)
        writeSequences.getOrPut(write.key()) { ArrayDeque() }.addLast(command.sequence)
        pendingWrites++
        pendingBytes += bytes
        accepted++
        peakPendingEntries = maxOf(peakPendingEntries, pendingWrites)
        peakPendingBytes = maxOf(peakPendingBytes, pendingBytes)
        lock.notifyAll()
        true
    }

    /** Enqueues a bounded read behind earlier writes of the same chunk without blocking the caller. */
    fun read(key: ClientChunkRegionSink.Key): CompletableFuture<ClientChunkDeltaStore.Combined?> = enqueueRead(key) { sink, readKey ->
        sink.read(readKey)
    }

    fun readTerrain(key: ClientChunkRegionSink.Key): CompletableFuture<ClientChunkDeltaStore.Terrain?> = enqueueRead(key) { sink, readKey ->
        sink.readTerrain(readKey)
    }

    private fun <T> enqueueRead(
        key: ClientChunkRegionSink.Key,
        action: (Sink, ClientChunkRegionSink.Key) -> T?,
    ): CompletableFuture<T?> = synchronized(lock) {
        if (!accepting || outstandingReads >= MAX_PENDING_READS) {
            return@synchronized failedFuture<T?>(IllegalStateException("Chunk cache read queue is unavailable or full"))
        }
        val result = CompletableFuture<T?>()
        readQueue.addLast(TypedRead(key, nextSequence++, result, System.nanoTime(), action))
        outstandingReads++
        lock.notifyAll()
        result
    }

    override fun discardPending() {
        val reads = synchronized(lock) {
            val reads = discardQueuedLocked()
            pendingBytes = 0
            lock.notifyAll()
            reads
        }
        reads.forEach { it.fail(IllegalStateException("Chunk cache read was discarded")) }
    }

    override fun closeAsync() {
        val reads = synchronized(lock) {
            accepting = false
            val reads = if (!started) {
                discardQueuedLocked().also { pendingBytes = 0 }
            } else emptyList()
            lock.notifyAll()
            reads
        }
        reads.forEach { it.fail(IllegalStateException("Chunk cache writer closed before start")) }
    }

    override fun awaitClosed() { worker.join() }

    override fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        if (timeoutMillis > 0) worker.join(timeoutMillis)
        return !worker.isAlive
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(pendingWrites, pendingBytes, activeBytes, accepted, processed, discarded, rejected,
            peakPendingEntries, peakPendingBytes, flushes, workNanos, maxWorkNanos, accepting,
            reads, missingReads, failedReads, readQueueNanos, maxReadQueueNanos,
            storeReadNanos, maxStoreReadNanos)
    }

    private fun runWorker() {
        var sink: Sink? = null
        var failure: Throwable? = null
        var dirty = false
        val interval = TimeUnit.MILLISECONDS.toNanos(flushIntervalMillis)
        var deadline = Long.MAX_VALUE
        try {
            sink = sinkFactory()
            while (true) {
                var stop = false
                val command = synchronized(lock) {
                    while (writes.isEmpty() && readQueue.isEmpty() && accepting) {
                        if (!dirty) lock.wait()
                        else {
                            val remaining = deadline - System.nanoTime()
                            if (remaining <= 0) break
                            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                        }
                    }
                    if (writes.isEmpty() && readQueue.isEmpty()) {
                        stop = !accepting
                        null
                    } else nextCommandLocked().also { next ->
                        when (next) {
                            is Command.Write -> {
                                pendingWrites--
                                pendingBytes -= next.value.estimatedBytes()
                                activeBytes = next.value.estimatedBytes()
                            }
                            is Command.Read -> Unit
                        }
                    }
                }
                if (command != null) {
                    when (command) {
                        is Command.Write -> {
                            val started = System.nanoTime()
                            try {
                                sink.accept(command.value)
                                synchronized(lock) { processed++ }
                            } finally {
                                val elapsed = System.nanoTime() - started
                                synchronized(lock) {
                                    activeBytes = 0
                                    workNanos += elapsed
                                    maxWorkNanos = maxOf(maxWorkNanos, elapsed)
                                }
                            }
                            if (!dirty) { dirty = true; deadline = System.nanoTime() + interval }
                        }
                        is Command.Read -> {
                            try {
                                command.execute(sink)
                            } finally {
                                synchronized(lock) { outstandingReads-- }
                            }
                        }
                    }
                }
                if (dirty && (stop || System.nanoTime() >= deadline)) {
                    val started = System.nanoTime()
                    sink.flush()
                    val elapsed = System.nanoTime() - started
                    synchronized(lock) {
                        flushes++
                        workNanos += elapsed
                        maxWorkNanos = maxOf(maxWorkNanos, elapsed)
                    }
                    dirty = false
                    deadline = Long.MAX_VALUE
                }
                if (stop) break
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            val reads = synchronized(lock) {
                accepting = false
                val reads = discardQueuedLocked()
                pendingBytes = 0
                pendingWrites = 0
                activeBytes = 0
                reads
            }
            reads.forEach { it.fail(failure ?: IllegalStateException("Chunk cache writer stopped")) }
            try { sink?.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            failure?.let { onFailure.accept(it) }
        }
    }

    /** Caller holds [lock] and at least one command is queued. */
    private fun nextCommandLocked(): Command {
        val writeFirst = writes.isNotEmpty() && (consecutiveReads >= MAX_CONSECUTIVE_READS || pendingWrites * 2 >= capacity)
        if (!writeFirst) {
            val iterator = readQueue.iterator()
            while (iterator.hasNext()) {
                val read = iterator.next()
                val oldestWrite = writeSequences[read.key]?.peekFirst()
                if (oldestWrite == null || oldestWrite > read.sequence) {
                    iterator.remove()
                    consecutiveReads++
                    return read
                }
            }
        }
        // Every queued read waits for an earlier write, or writes are due: the oldest write unblocks the queue.
        val next = writes.peekFirst() ?: return readQueue.removeFirst().also { consecutiveReads++ }
        // A read never observes a write submitted after it; such reads of the same chunk go first.
        val iterator = readQueue.iterator()
        while (iterator.hasNext()) {
            val read = iterator.next()
            if (read.sequence < next.sequence && read.key == next.value.key()) {
                iterator.remove()
                return read
            }
        }
        val write = writes.removeFirst()
        consecutiveReads = 0
        val key = write.value.key()
        writeSequences[key]?.let { sequences ->
            sequences.pollFirst()
            if (sequences.isEmpty()) writeSequences.remove(key)
        }
        return write
    }

    /** Caller holds [lock]. Returned futures are completed after releasing it. */
    private fun discardQueuedLocked(): List<Command.Read> {
        discarded += writes.size
        writes.clear()
        writeSequences.clear()
        val reads = ArrayList<Command.Read>(readQueue)
        outstandingReads -= readQueue.size
        readQueue.clear()
        pendingWrites = 0
        return reads
    }

    private fun recordRead(enqueued: Long, started: Long, finished: Long, missing: Boolean, failed: Boolean) = synchronized(lock) {
        val queueNanos = (started - enqueued).coerceAtLeast(0)
        val readNanos = (finished - started).coerceAtLeast(0)
        reads++
        if (missing) missingReads++
        if (failed) failedReads++
        readQueueNanos += queueNanos
        maxReadQueueNanos = maxOf(maxReadQueueNanos, queueNanos)
        storeReadNanos += readNanos
        maxStoreReadNanos = maxOf(maxStoreReadNanos, readNanos)
    }

    private fun <T> failedFuture(error: Throwable) = CompletableFuture<T>().also {
        it.completeExceptionally(error)
    }

    internal companion object {
        const val MAX_PENDING_READS = 64
        /** Reads served before a waiting write gets its turn. */
        const val MAX_CONSECUTIVE_READS = 8
    }
}
