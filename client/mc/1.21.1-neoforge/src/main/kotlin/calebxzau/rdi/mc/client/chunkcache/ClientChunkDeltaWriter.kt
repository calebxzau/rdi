package calebxzau.rdi.mc.client.chunkcache

import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Ordered, bounded, nonblocking producer queue. Only its worker owns persistent delta state. */
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
        data class Write(val value: ClientChunkCacheWrite) : Command
        interface Read : Command {
            val enqueuedNanos: Long
            fun execute(sink: Sink)
            fun fail(error: Throwable)
        }
    }
    private inner class TypedRead<T>(
        private val key: ClientChunkRegionSink.Key,
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
    private val pending = ArrayDeque<Command>()
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
        pending.addLast(Command.Write(write))
        pendingWrites++
        pendingBytes += bytes
        accepted++
        peakPendingEntries = maxOf(peakPendingEntries, pendingWrites)
        peakPendingBytes = maxOf(peakPendingBytes, pendingBytes)
        lock.notifyAll()
        true
    }

    /** Enqueues a bounded read behind all earlier writes without blocking the caller. */
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
        pending.addLast(TypedRead(key, result, System.nanoTime(), action))
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
                    while (pending.isEmpty() && accepting) {
                        if (!dirty) lock.wait()
                        else {
                            val remaining = deadline - System.nanoTime()
                            if (remaining <= 0) break
                            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                        }
                    }
                    if (pending.isEmpty()) {
                        stop = !accepting
                        null
                    } else pending.removeFirst().also { next ->
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

    /** Caller holds [lock]. Returned futures are completed after releasing it. */
    private fun discardQueuedLocked(): List<Command.Read> {
        val reads = mutableListOf<Command.Read>()
        while (pending.isNotEmpty()) {
            when (val command = pending.removeFirst()) {
                is Command.Write -> discarded++
                is Command.Read -> { outstandingReads--; reads += command }
            }
        }
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

    private companion object {
        const val MAX_PENDING_READS = 32
    }
}
